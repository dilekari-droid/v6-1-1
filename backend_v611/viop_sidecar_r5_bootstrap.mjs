import { createHash } from 'node:crypto';

const BASE_REF = 'a9094e80f73b8a369746b6ce77af28e62501e00b';
const BASE_SHA256 = '93a6ff24b53b42a3a4a2200a69c7e3daa2d3e5ff73de0c8d487d7717b9ef1a30';
const ADAPTER_REF = 'f4f0aafc4df676362a71c8244eb76b329c0ff710';
const ADAPTER_BLOB_SHA1 = '232e912141b906d6908b39a214516b1d332e4e2a';
const RAW = 'https://raw.githubusercontent.com/dilekari-droid/v6-1-1';

async function fetchText(url, label) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${label}_FETCH_FAILED HTTP ${r.status}`);
  return await r.text();
}
function sha256(text) { return createHash('sha256').update(text).digest('hex'); }
function gitBlobSha1(text) {
  const b = Buffer.from(text, 'utf8');
  return createHash('sha1').update(`blob ${b.length}\0`).update(b).digest('hex');
}
function replaceOnce(source, oldText, newText, label) {
  const count = source.split(oldText).length - 1;
  if (count !== 1) throw new Error(`${label}_PATCH_INVARIANT occurrences=${count}`);
  return source.replace(oldText, newText);
}
function replaceBlock(source, startMarker, endMarker, replacement, label) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  if (start < 0 || end < 0 || end <= start) throw new Error(`${label}_BLOCK_NOT_FOUND`);
  return source.slice(0, start) + replacement + source.slice(end);
}

const base = await fetchText(`${RAW}/${BASE_REF}/backend_v611/viop_sidecar_r4.mjs`, 'R4_BASE');
const baseHash = sha256(base);
if (baseHash !== BASE_SHA256) throw new Error(`R4_BASE_INTEGRITY_FAILED ${baseHash}`);

const adapter = await fetchText(`${RAW}/${ADAPTER_REF}/backend_v611/tradewize_bars_adapter.mjs`, 'BARS_ADAPTER');
const adapterBlob = gitBlobSha1(adapter);
if (adapterBlob !== ADAPTER_BLOB_SHA1) throw new Error(`BARS_ADAPTER_INTEGRITY_FAILED ${adapterBlob}`);
await Bun.write('/tmp/tradewize_bars_adapter.mjs', adapter);

let source = `import { buildTradeWizeBarsUrl, normalizeTradeWizeBarsPage, mergeTradeWizeBarsPages } from './tradewize_bars_adapter.mjs';\n` + base;
source = replaceOnce(
  source,
  "const TW_HISTORY_TEMPLATE = String(process.env.TRADEWIZE_HISTORY_URL_TEMPLATE || '').trim();",
  "const TW_HISTORY_URL = String(process.env.TRADEWIZE_HISTORY_URL || `${TW_BASE}/api/v1/market-data/bars`).trim();",
  'HISTORY_ENDPOINT'
);
source = replaceOnce(
  source,
  "const HISTORY_CACHE_MS = positiveNumber(process.env.VIOP_HISTORY_CACHE_MS, 15_000);",
  "const HISTORY_CACHE_MS = positiveNumber(process.env.VIOP_HISTORY_CACHE_MS, 15_000);\nconst MIN_HISTORY_BARS = Math.max(220, Math.trunc(positiveNumber(process.env.VIOP_MIN_HISTORY_BARS, 220)));\nconst HISTORY_COUNT_BACK = Math.max(MIN_HISTORY_BARS, Math.trunc(positiveNumber(process.env.VIOP_HISTORY_COUNT_BACK, 220)));\nconst MAX_HISTORY_PAGES = Math.min(20, Math.max(1, Math.trunc(positiveNumber(process.env.VIOP_HISTORY_MAX_PAGES, 8))));\nconst MIN_LIQUIDITY_VOLUME = positiveNumber(process.env.VIOP_MIN_LIQUIDITY_VOLUME, 1);\nconst MAX_SPREAD_BPS = positiveNumber(process.env.VIOP_MAX_SPREAD_BPS, 250);",
  'READINESS_CONSTANTS'
);
source = replaceOnce(
  source,
  "function epochMs(v) {\n  const n = Number(v);\n  if (!Number.isFinite(n) || n <= 0) return 0;\n  if (n < 10_000_000_000) return Math.trunc(n * 1000);\n  return Math.trunc(n);\n}",
  "function epochMs(v) {\n  const n = Number(v);\n  if (Number.isFinite(n) && n > 0) return n < 10_000_000_000 ? Math.trunc(n * 1000) : Math.trunc(n);\n  if (typeof v === 'string' && v.trim()) { const parsed = Date.parse(v); if (Number.isFinite(parsed) && parsed > 0) return parsed; }\n  return 0;\n}",
  'ISO_TIME'
);

const historyBlock = `function liquidityEvidence(c) {
  const volume = Number(c?.volume);
  const volumeReady = Number.isFinite(volume) && volume >= MIN_LIQUIDITY_VOLUME;
  const bid = Number(c?.bid); const ask = Number(c?.ask);
  const spreadKnown = Number.isFinite(bid) && Number.isFinite(ask) && bid > 0 && ask >= bid;
  const mid = spreadKnown ? (bid + ask) / 2 : null;
  const spreadBps = spreadKnown && mid > 0 ? ((ask - bid) / mid) * 10_000 : null;
  const spreadReady = spreadBps == null || spreadBps <= MAX_SPREAD_BPS;
  return { ready: volumeReady && spreadReady, volumeReady, spreadKnown, spreadBps };
}

async function loadLiveQuote(symbol) {
  const c = state.details.entries.get(symbol);
  if (!c || !c.valid || c.lastTradingAt <= Date.now()) throw providerFailure('NO_ACTIVE_CONTRACT', `${symbol} doğrulanmış aktif FUTURE değil.`, 409);
  const { body } = await providerGet(TW_DETAILS_URL);
  const row = detailsEntries(body).find(([s]) => s === symbol);
  if (!row) throw providerFailure('NO_LIVE_QUOTE', `${symbol} TradeWize last-price/details içinde bulunamadı.`, 404);
  const raw = row[1];
  const price = Number(mapped(raw,'price') ?? mapped(raw,'lastPrice'));
  const ts = epochMs(mapped(raw,'timestamp') ?? mapped(raw,'exchangeTimestamp') ?? mapped(raw,'dataTimestamp'));
  const now = Date.now();
  if (!Number.isFinite(price) || price <= 0 || ts <= 0) throw providerFailure('NO_LIVE_QUOTE','TradeWize last-price fiyat/timestamp geçersiz.',502);
  const age = now - ts;
  if (age < -15_000) throw providerFailure('NO_LIVE_QUOTE','TradeWize quote timestamp gelecekte.',409);
  if (age > MAX_QUOTE_AGE_MS) throw providerFailure('STALE_QUOTE', `TradeWize quote stale; age=${Math.floor(age/1000)}s`,409);
  const delaySeconds = Math.max(0, Math.floor(age / 1000));
  if (delaySeconds > MAX_DELAY_SECONDS) throw providerFailure('STALE_QUOTE', `TradeWize quote gecikmesi ${delaySeconds}s`,409);
  c.lastPrice = price;
  c.dataTimestamp = ts;
  c.realtime = true;
  c.currentSessionIncluded = true;
  c.delaySeconds = delaySeconds;
  const bid = numberOrNull(mapped(raw,'bid')); if (bid != null) c.bid = bid;
  const ask = numberOrNull(mapped(raw,'ask')); if (ask != null) c.ask = ask;
  const volume = numberOrNull(mapped(raw,'volume')); if (volume != null && volume >= 0) c.volume = volume;
  const oi = intOrNull(mapped(raw,'openInterest')); if (oi != null) c.openInterest = oi;
  state.metrics.liveQuoteCount = [...state.details.entries.values()].filter(x => quoteState(x).live).length;
  state.metrics.lastQuoteTimestamp = Math.max(state.metrics.lastQuoteTimestamp || 0, ts);
  state.metrics.lastQuoteAgeSeconds = delaySeconds;
  state.metrics.currentSession = 'OPEN';
  return { symbol, price, bid:c.bid, ask:c.ask, dailyChangePct:c.dailyChangePct, volume:c.volume, openInterest:c.openInterest,
    exchangeTimestamp:ts, receivedAt:now, source:'TradeWize', realtime:true, delaySeconds, currentSessionIncluded:true };
}

async function loadHistory(symbol, rangeValue, interval) {
  if (String(interval).toLowerCase() !== '1d') throw providerFailure('UNSUPPORTED_PROVIDER_INTERVAL', `TradeWize bars interval mapping doğrulanmadı: ${interval}`, 400);
  if (String(rangeValue).toLowerCase() !== '1y') throw providerFailure('UNSUPPORTED_HISTORY_RANGE', `Production scanner için doğrulanan history range yalnız 1y: ${rangeValue}`, 400);
  if (!endpointConfigured(TW_HISTORY_URL)) throw providerFailure('NO_HISTORY','TradeWize /api/v1/market-data/bars endpoint yapılandırması geçersiz.',503);
  const c = state.details.entries.get(symbol);
  if (!c || !c.valid || c.lastTradingAt <= Date.now()) throw providerFailure('NO_ACTIVE_CONTRACT', `${symbol} doğrulanmış aktif FUTURE değil.`,409);

  const key = `${symbol}|${rangeValue}|${interval}`;
  const cached = state.history.get(key); const now = Date.now();
  if (cached && now - cached.fetchedAt < HISTORY_CACHE_MS) return cached.body;

  const pages = [];
  const visited = new Set();
  let cursor = null;
  for (let pageIndex = 0; pageIndex < MAX_HISTORY_PAGES; pageIndex += 1) {
    const url = buildTradeWizeBarsUrl({ baseUrl:TW_HISTORY_URL, symbol, requestedInterval:'1d', countBack:HISTORY_COUNT_BACK, cursor });
    const { body } = await providerGet(url);
    let page;
    try { page = normalizeTradeWizeBarsPage(body, symbol, '1d', Date.now()); }
    catch (e) { throw providerFailure('OHLC_UNAVAILABLE', e?.message || 'TradeWize bars normalizasyonu başarısız.', 502); }
    pages.push(page);
    const count = pages.reduce((n,p) => n + p.candles.length, 0);
    if (!page.hasMore || count >= MIN_HISTORY_BARS) break;
    if (!page.nextCursor || visited.has(page.nextCursor)) throw providerFailure('OHLC_UNAVAILABLE','TradeWize bars cursor döngüsü/eksik cursor.',502);
    visited.add(page.nextCursor); cursor = page.nextCursor;
  }

  let normalized;
  try { normalized = mergeTradeWizeBarsPages(pages, MIN_HISTORY_BARS); }
  catch (e) { throw providerFailure(e?.code || 'OHLC_UNAVAILABLE', e?.message || 'TradeWize bars birleştirme başarısız.', e?.status || 502); }
  normalized.source = 'TradeWize';
  normalized.range = '1y';
  state.history.set(key, { fetchedAt:now, body:normalized });
  state.metrics.historyReady = true;
  state.metrics.lastHistoryAt = now;
  state.metrics.technicalEngineReady = false;
  return normalized;
}

`;
source = replaceBlock(source, 'async function loadHistory(', 'function capabilitySnapshot()', historyBlock, 'HISTORY_RUNTIME');

const capabilityBlock = `function capabilitySnapshot() {
  const now = Date.now();
  const authReady = state.auth.status === 'CONNECTED';
  const metadataReady = state.metrics.contractCount > 0 && state.metrics.metadataInvalidCount === 0 && state.metrics.metadataValidCount === state.metrics.contractCount;
  const contractsReady = authReady && state.details.universeComplete && metadataReady && state.metrics.activeContractCount > 0;
  const active = [...state.details.entries.values()].filter(c => c.valid && c.lastTradingAt > now);
  const quoteReady = contractsReady && active.some(c => quoteState(c, now).live);
  const freshnessReady = quoteReady && state.metrics.lastQuoteAgeSeconds != null && state.metrics.lastQuoteAgeSeconds * 1000 <= MAX_QUOTE_AGE_MS;
  const liquidityReady = contractsReady && active.some(c => quoteState(c, now).live && liquidityEvidence(c).ready);
  const activeSymbols = new Set(active.map(c => c.symbol));
  const historySymbols = new Set([...state.history.values()].filter(x => now - x.fetchedAt < HISTORY_CACHE_MS).map(x => x.body?.symbol).filter(Boolean));
  const historyReady = [...activeSymbols].some(s => historySymbols.has(s));
  const scanEligible = active.some(c => quoteState(c, now).live && liquidityEvidence(c).ready && historySymbols.has(c.symbol));
  const scannerReady = contractsReady && quoteReady && freshnessReady && liquidityReady && historyReady && scanEligible;
  const reasonCode = !authReady ? (state.auth.status==='NOT_CONFIGURED'?'NO_PROVIDER':'AUTH_ERROR')
    : !state.details.universeComplete ? 'NO_CONTRACT_UNIVERSE'
    : !metadataReady ? 'NO_METADATA'
    : state.metrics.activeContractCount <= 0 ? 'NO_ACTIVE_CONTRACT'
    : !quoteReady ? (state.metrics.currentSession==='CLOSED'?'MARKET_CLOSED':'NO_LIVE_QUOTE')
    : !freshnessReady ? 'STALE_QUOTE'
    : !historyReady ? 'NO_HISTORY'
    : !liquidityReady ? 'LIQUIDITY_UNVERIFIED'
    : null;
  state.metrics.historyReady = historyReady;
  state.metrics.technicalEngineReady = false;
  return {
    ok:true, version:'6.1.1-sidecar-r5-bars', providerReady:scannerReady, multiMarketReady:false, coreMarketsReady:false, globalProviderReady:false,
    primaryConfiguredProvider:'TradeWize', tradeWize:{state:state.auth.status,authenticated:authReady,lastAuthAt:state.auth.lastAttemptAt,error:state.auth.error},
    features:{viopContractsReady:contractsReady,shortLivedSessionAuth:false,dynamicScanner:false,realtimeScannerRest:false,liveMarketWebSocket:false,realtimeScannerWebSocket:false,attestationReady:false,tradingViewSignals:false,researchFoundation:false,allTimeHistory:false,barHistoryCache:true,ingressRateLimit:false,fullBistFiveMinuteSla:false,bistSnapshotBatch:false},
    markets:{VIOP:{ready:scannerReady,supported:true,discovery:contractsReady,marketData:quoteReady,historicalData:historyReady,realtime:quoteReady,realtimeReady:quoteReady,analysisReady:scannerReady,symbolCount:state.metrics.activeContractCount,provider:'TradeWize',reasonCode,message:reasonCode?'VİOP provider not ready':'VİOP provider connected',authReady,contractsReady,metadataReady,activeFutureReady:state.metrics.activeContractCount>0,quoteReady,historyReady,liquidityReady,freshnessReady,scannerReady}}
  };
}
`;
source = replaceBlock(source, 'function capabilitySnapshot()', 'function healthSnapshot()', capabilityBlock, 'CAPABILITY_RUNTIME');

const quoteRoute = `const qm=u.pathname.match(/^\\/v1\\/viop\\/quote\\/([^/]+)$/);
  if(qm){try{await refreshDetails(false); const symbol=canonicalSymbol(decodeURIComponent(qm[1])); return json(await loadLiveQuote(symbol));}catch(e){return providerError(e);}}
  `;
source = replaceBlock(source, "const qm=u.pathname.match(/^\\/v1\\/viop\\/quote\\/([^/]+)$/);", "const hm=u.pathname.match(/^\\/v1\\/viop\\/history\\/([^/]+)$/);", quoteRoute, 'QUOTE_ROUTE');

const preflightOld = "try { if(state.auth.status==='CONNECTED') await refreshDetails(true); } catch(e){ return providerError(e); }\n    const h=healthSnapshot(); return json({ok:h.ok,tradeWize:{state:state.auth.status,authenticated:state.auth.status==='CONNECTED',error:state.auth.error},viop:h},h.ok?200:503);";
const preflightNew = "try {\n      if(state.auth.status==='CONNECTED') {\n        await refreshDetails(true);\n        const now=Date.now();\n        const candidates=[...state.details.entries.values()].filter(c=>c.valid&&c.lastTradingAt>now);\n        let lastError=null;\n        for(const c of candidates.slice(0,5)){\n          try { await loadLiveQuote(c.symbol); await loadHistory(c.symbol,'1y','1d'); if(liquidityEvidence(c).ready) break; }\n          catch(e){ lastError=e; }\n        }\n        if(candidates.length && lastError && !capabilitySnapshot().scannerReady) recordError(lastError.code||'UPSTREAM_ERROR',lastError.message||'preflight probe failed');\n      }\n    } catch(e){ return providerError(e); }\n    const h=healthSnapshot(); return json({ok:h.ok,tradeWize:{state:state.auth.status,authenticated:state.auth.status==='CONNECTED',error:state.auth.error},viop:h},h.ok?200:503);";
source = replaceOnce(source, preflightOld, preflightNew, 'PREFLIGHT_E2E');

source = replaceOnce(source, "console.log(`V611_VIOP_SIDECAR_R4_LISTENING port=${server.port}`);", "console.log(`V611_VIOP_SIDECAR_R5_LISTENING port=${server.port}`);", 'VERSION_LOG');
source = replaceOnce(source, "console.log(`V611_HISTORY_ENDPOINT=${endpointConfigured(TW_HISTORY_TEMPLATE,true)?'CONFIGURED':'MISSING'}`);", "console.log(`V611_HISTORY_ENDPOINT=${endpointConfigured(TW_HISTORY_URL)?'CONFIGURED':'MISSING'}`);\nconsole.log(`V611_HISTORY_MODE=TRADEWIZE_BARS_1D_COUNTBACK`);", 'HISTORY_LOG');

const patchedHash = sha256(source);
await Bun.write('/tmp/viop_sidecar_r5.mjs', source);
console.log(`V611_R5_BASE_SHA256=${baseHash}`);
console.log(`V611_R5_ADAPTER_BLOB_SHA1=${adapterBlob}`);
console.log(`V611_R5_PATCHED_SHA256=${patchedHash}`);
await import('file:///tmp/viop_sidecar_r5.mjs');
