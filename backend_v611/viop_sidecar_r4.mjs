const PORT = Number(process.env.PORT || 8080);
const APP_KEY = String(process.env.BORSA_API_KEY || process.env.APP_API_KEY || '').trim();

const TW_BASE = String(process.env.TRADEWIZE_BASE_URL || 'https://api.tradewize.com.tr').trim().replace(/\/$/, '');
const TW_TOKEN_URL = String(process.env.TRADEWIZE_TOKEN_URL || `${TW_BASE}/oauth/token`).trim();
const TW_DETAILS_URL = String(process.env.TRADEWIZE_DETAILS_URL || `${TW_BASE}/api/v1/market-data/viop/last-price/details?all=true`).trim();
const TW_UNIVERSE_URL = String(process.env.TRADEWIZE_UNIVERSE_URL || '').trim();
const TW_HISTORY_TEMPLATE = String(process.env.TRADEWIZE_HISTORY_URL_TEMPLATE || '').trim();
const TW_ACCESS_TOKEN = String(process.env.TRADEWIZE_ACCESS_TOKEN || '').trim();
const TW_API_KEY = String(process.env.TRADEWIZE_API_KEY || '').trim();
const TW_AUTH_MODE = String(process.env.TRADEWIZE_AUTH_MODE || '').trim().toUpperCase();
const TW_API_KEY_HEADER = String(process.env.TRADEWIZE_API_KEY_HEADER || '').trim();
const TW_DATA_AUTH_HEADER = String(process.env.TRADEWIZE_DATA_AUTH_HEADER || 'Authorization').trim();
const TW_DATA_AUTH_PREFIX = String(process.env.TRADEWIZE_DATA_AUTH_PREFIX || 'Bearer ').replace(/\\n/g, '');
const TW_TOKEN_FIELD = String(process.env.TRADEWIZE_TOKEN_FIELD || 'access_token').trim();
const TW_TOKEN_TTL_FIELD = String(process.env.TRADEWIZE_TOKEN_TTL_FIELD || 'expires_in').trim();
const TW_TOKEN_HEADERS = parseJsonObjectEnv('TRADEWIZE_TOKEN_HEADERS_JSON');
const TW_TOKEN_BODY = parseJsonObjectEnv('TRADEWIZE_TOKEN_BODY_JSON');
const TW_FIELD_MAP = parseJsonObjectEnv('TRADEWIZE_FIELD_MAP_JSON');
const TW_HISTORY_MAP = parseJsonObjectEnv('TRADEWIZE_HISTORY_FIELD_MAP_JSON');
const MAX_QUOTE_AGE_MS = positiveNumber(process.env.VIOP_MAX_QUOTE_AGE_MS, 60_000);
const MAX_DELAY_SECONDS = positiveNumber(process.env.VIOP_MAX_DELAY_SECONDS, 5);
const DETAILS_CACHE_MS = positiveNumber(process.env.VIOP_DETAILS_CACHE_MS, 5_000);
const HISTORY_CACHE_MS = positiveNumber(process.env.VIOP_HISTORY_CACHE_MS, 15_000);

const state = {
  auth: { status: authConfigured() ? 'CONFIGURED_UNVERIFIED' : 'NOT_CONFIGURED', token: '', expiresAt: 0, lastAttemptAt: 0, error: null },
  details: { fetchedAt: 0, providerTimestamp: 0, entries: new Map(), totalCount: null, universeComplete: false, error: null },
  history: new Map(),
  metrics: {
    contractCount: 0,
    metadataValidCount: 0,
    metadataInvalidCount: 0,
    activeContractCount: 0,
    liveQuoteCount: 0,
    staleQuoteCount: 0,
    lastQuoteTimestamp: 0,
    lastQuoteAgeSeconds: null,
    currentSession: 'UNKNOWN',
    historyReady: false,
    technicalEngineReady: false,
    lastRefreshAt: 0,
    lastHistoryAt: 0,
    lastErrorCode: null,
    lastErrorMessage: null,
  }
};

function parseJsonObjectEnv(name) {
  const raw = String(process.env[name] || '').trim();
  if (!raw) return {};
  try {
    const v = JSON.parse(raw);
    return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
  } catch { return {}; }
}
function positiveNumber(raw, fallback) {
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}
function json(body, status=200, headers={}) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type':'application/json; charset=utf-8', 'cache-control':'no-store', ...headers } });
}
function authOk(req) {
  if (!APP_KEY) return true;
  const x = String(req.headers.get('x-api-key') || '').trim();
  const a = String(req.headers.get('authorization') || '').trim();
  return x === APP_KEY || a === `Bearer ${APP_KEY}`;
}
function canonicalSymbol(v) { return String(v || '').trim().toUpperCase(); }
function endpointConfigured(v, symbolTemplate=false) {
  if (!v) return false;
  try {
    const sample = symbolTemplate ? v.replace('{symbol}','F_X') : v;
    const u = new URL(sample);
    return u.protocol === 'https:' && (!symbolTemplate || v.includes('{symbol}'));
  } catch { return false; }
}
function authConfigured() {
  if (TW_ACCESS_TOKEN) return true;
  if (!TW_AUTH_MODE) return false;
  if (TW_AUTH_MODE === 'API_KEY_HEADER') return Boolean(TW_API_KEY && TW_API_KEY_HEADER);
  if (TW_AUTH_MODE === 'OAUTH_API_KEY') return Boolean(TW_API_KEY && TW_API_KEY_HEADER && endpointConfigured(TW_TOKEN_URL));
  return false;
}
function substituteSecrets(value) {
  if (typeof value !== 'string') return value;
  return value.replaceAll('{apiKey}', TW_API_KEY);
}
function mapped(obj, canonical) {
  const providerKey = String(TW_FIELD_MAP[canonical] || canonical);
  return obj?.[providerKey];
}
function mappedHistory(obj, canonical) {
  const providerKey = String(TW_HISTORY_MAP[canonical] || canonical);
  return obj?.[providerKey];
}
function epochMs(v) {
  const n = Number(v);
  if (!Number.isFinite(n) || n <= 0) return 0;
  if (n < 10_000_000_000) return Math.trunc(n * 1000);
  return Math.trunc(n);
}
function parsedFuture(symbol) {
  const s = canonicalSymbol(symbol);
  const m = /^F_(?:P_)?(.+?)(0[1-9]|1[0-2])(\d{2})$/.exec(s);
  if (!m) return null;
  return { underlying:m[1], expiry:`20${m[3]}-${m[2]}` };
}
function safeUrl(url, label) {
  if (!endpointConfigured(url)) throw providerFailure('UPSTREAM_ERROR', `${label} geçerli HTTPS URL değil.`, 503);
  return url;
}
function providerFailure(code, message, status=502) {
  const e = new Error(message); e.code = code; e.status = status; return e;
}
function recordError(code, message) {
  state.metrics.lastErrorCode = code;
  state.metrics.lastErrorMessage = message;
}

async function ensureProviderAuth(force=false) {
  const now = Date.now();
  if (TW_ACCESS_TOKEN) {
    state.auth = { status:'CONNECTED', token:TW_ACCESS_TOKEN, expiresAt:Number.MAX_SAFE_INTEGER, lastAttemptAt:now, error:null };
    return state.auth;
  }
  if (!authConfigured()) {
    const reason = TW_API_KEY && !TW_AUTH_MODE ? 'TRADEWIZE_AUTH_MODE missing; auth contract must be configured explicitly.' : 'TRADEWIZE production credential missing.';
    state.auth = { status:'NOT_CONFIGURED', token:'', expiresAt:0, lastAttemptAt:now, error:reason };
    return state.auth;
  }
  if (!force && state.auth.token && state.auth.expiresAt > now + 30_000) return state.auth;
  state.auth.lastAttemptAt = now;
  try {
    if (TW_AUTH_MODE === 'API_KEY_HEADER') {
      state.auth = { status:'CONNECTED', token:'', expiresAt:Number.MAX_SAFE_INTEGER, lastAttemptAt:now, error:null };
      return state.auth;
    }
    if (TW_AUTH_MODE !== 'OAUTH_API_KEY') {
      state.auth = { status:'AUTH_FAILED', token:'', expiresAt:0, lastAttemptAt:now, error:`Unsupported explicit auth mode: ${TW_AUTH_MODE}` };
      return state.auth;
    }
    const headers = { 'Accept':'application/json', 'Content-Type':'application/json' };
    headers[TW_API_KEY_HEADER] = TW_API_KEY;
    for (const [k,v] of Object.entries(TW_TOKEN_HEADERS)) headers[k] = String(substituteSecrets(v));
    const bodyObj = {};
    for (const [k,v] of Object.entries(TW_TOKEN_BODY)) bodyObj[k] = substituteSecrets(v);
    const r = await fetch(safeUrl(TW_TOKEN_URL, 'TradeWize token endpoint'), {
      method:'POST', headers, body:JSON.stringify(bodyObj), signal:AbortSignal.timeout(15_000)
    });
    const text = await r.text();
    let payload = {}; try { payload = text ? JSON.parse(text) : {}; } catch {}
    if (!r.ok) {
      state.auth = { status:'AUTH_FAILED', token:'', expiresAt:0, lastAttemptAt:now, error:`HTTP ${r.status}` };
      recordError('AUTH_ERROR', `TradeWize authentication HTTP ${r.status}`);
      return state.auth;
    }
    const token = String(payload?.[TW_TOKEN_FIELD] || '').trim();
    if (!token) {
      state.auth = { status:'AUTH_FAILED', token:'', expiresAt:0, lastAttemptAt:now, error:`Token field ${TW_TOKEN_FIELD} missing` };
      recordError('AUTH_ERROR', state.auth.error);
      return state.auth;
    }
    const ttl = positiveNumber(payload?.[TW_TOKEN_TTL_FIELD], 300);
    state.auth = { status:'CONNECTED', token, expiresAt:now + Math.max(60, ttl) * 1000, lastAttemptAt:now, error:null };
    return state.auth;
  } catch (e) {
    const timeout = e?.name === 'TimeoutError' || e?.name === 'AbortError';
    state.auth = { status:'AUTH_FAILED', token:'', expiresAt:0, lastAttemptAt:now, error:timeout?'TIMEOUT':(e?.message || 'AUTH_ERROR') };
    recordError(timeout?'TIMEOUT':'AUTH_ERROR', state.auth.error);
    return state.auth;
  }
}
function dataAuthHeaders(auth) {
  const headers = { 'Accept':'application/json', 'User-Agent':'BorsaTakip-V611-VIOP-Gateway/4' };
  if (TW_AUTH_MODE === 'API_KEY_HEADER') headers[TW_API_KEY_HEADER] = TW_API_KEY;
  else if (auth.token) headers[TW_DATA_AUTH_HEADER] = `${TW_DATA_AUTH_PREFIX}${auth.token}`;
  return headers;
}
async function providerGet(url) {
  const auth = await ensureProviderAuth(false);
  if (auth.status !== 'CONNECTED') throw providerFailure(auth.status === 'NOT_CONFIGURED'?'NO_PROVIDER':'AUTH_ERROR', auth.error || 'TradeWize auth unavailable', 503);
  try {
    const r = await fetch(safeUrl(url, 'TradeWize data endpoint'), { headers:dataAuthHeaders(auth), signal:AbortSignal.timeout(20_000) });
    const text = await r.text();
    let body = {}; try { body = text ? JSON.parse(text) : {}; } catch { throw providerFailure('UPSTREAM_ERROR','Provider non-JSON response',502); }
    if (r.status === 401 || r.status === 403) {
      if (!TW_ACCESS_TOKEN) { state.auth.token=''; state.auth.expiresAt=0; }
      state.auth.status='AUTH_FAILED'; state.auth.error=`HTTP ${r.status}`;
      throw providerFailure('AUTH_ERROR', `TradeWize data HTTP ${r.status}`, 503);
    }
    if (r.status === 429) throw providerFailure('RATE_LIMIT','TradeWize rate limit (HTTP 429)',429);
    if (!r.ok) throw providerFailure('UPSTREAM_ERROR', `TradeWize data HTTP ${r.status}`,502);
    return { body, headers:r.headers };
  } catch(e) {
    if (e?.code) throw e;
    const timeout = e?.name === 'TimeoutError' || e?.name === 'AbortError';
    throw providerFailure(timeout?'TIMEOUT':'UPSTREAM_ERROR', timeout?'TradeWize timeout':(e?.message || 'TradeWize request failed'), timeout?504:502);
  }
}

function detailsEntries(body) {
  if (Array.isArray(body?.items)) return body.items.filter(x => x && typeof x === 'object').map(x => [canonicalSymbol(mapped(x,'symbol')), x]).filter(([s])=>s);
  const data = body?.data && typeof body.data === 'object' && !Array.isArray(body.data) ? body.data : body;
  if (!data || typeof data !== 'object' || Array.isArray(data)) return [];
  return Object.entries(data).filter(([k,v]) => canonicalSymbol(k) && v && typeof v === 'object' && !Array.isArray(v)).map(([k,v]) => [canonicalSymbol(mapped(v,'symbol') || k), v]);
}
function universeProof(body, headers, uniqueCount) {
  const completeHeader = String(headers.get('x-tradewise-selection-complete') || '').trim().toLowerCase();
  if (['1','true','yes'].includes(completeHeader)) return { complete:true, totalCount:uniqueCount };
  const totalRaw = Number(body?.totalCount);
  const hasMore = body?.hasMore === true;
  if (Number.isFinite(totalRaw) && totalRaw >= 0 && !hasMore && totalRaw === uniqueCount) return { complete:true, totalCount:totalRaw };
  return { complete:false, totalCount:Number.isFinite(totalRaw)&&totalRaw>=0?totalRaw:null };
}
function normalizeContract(symbol, raw) {
  const parsed = parsedFuture(symbol);
  if (!parsed) return { valid:false, reason:'UNSUPPORTED_SYMBOL', symbol, raw };
  const underlying = canonicalSymbol(mapped(raw,'underlying') || parsed.underlying);
  const expiry = String(mapped(raw,'expiry') || parsed.expiry).trim();
  const lastTradingAt = epochMs(mapped(raw,'lastTradingAt'));
  const expiryAt = epochMs(mapped(raw,'expiryAt')) || lastTradingAt;
  const tickSize = Number(mapped(raw,'tickSize'));
  const multiplier = Number(mapped(raw,'multiplier'));
  const currency = canonicalSymbol(mapped(raw,'currency'));
  const exchangeTimestamp = epochMs(mapped(raw,'exchangeTimestamp') ?? mapped(raw,'dataTimestamp') ?? mapped(raw,'timestamp'));
  const realtime = mapped(raw,'realtime') === true;
  const currentSessionIncluded = mapped(raw,'currentSessionIncluded') === true;
  const delaySeconds = Number(mapped(raw,'delaySeconds'));
  const source = String(mapped(raw,'source') || 'TradeWize').trim();
  const requiredOk = underlying === parsed.underlying && expiry === parsed.expiry && lastTradingAt>0 && Number.isFinite(tickSize)&&tickSize>0 && Number.isFinite(multiplier)&&multiplier>0 && /^[A-Z]{3}$/.test(currency);
  return {
    valid:requiredOk, reason:requiredOk?null:'NO_METADATA', symbol, underlying, expiry, contractType:'FUTURE', lastTradingAt, expiryAt,
    tickSize:Number.isFinite(tickSize)?tickSize:null, multiplier:Number.isFinite(multiplier)?multiplier:null, currency:currency||null,
    lastPrice:numberOrNull(mapped(raw,'price') ?? mapped(raw,'lastPrice')), bid:numberOrNull(mapped(raw,'bid')), ask:numberOrNull(mapped(raw,'ask')),
    dailyChangePct:numberOrNull(mapped(raw,'dailyChangePct')), openInterest:intOrNull(mapped(raw,'openInterest')), volume:numberOrNull(mapped(raw,'volume')),
    dataTimestamp:exchangeTimestamp, realtime, delaySeconds:Number.isFinite(delaySeconds)?Math.trunc(delaySeconds):null,
    currentSessionIncluded, source, settlementType:String(mapped(raw,'settlementType') || '').trim() || null,
    exchangeTimezone:String(mapped(raw,'exchangeTimezone') || '').trim() || null,
  };
}
function numberOrNull(v){ const n=Number(v); return Number.isFinite(n)?n:null; }
function intOrNull(v){ const n=Number(v); return Number.isFinite(n)&&n>=0?Math.trunc(n):null; }
function quoteState(contract, now=Date.now()) {
  const ts = contract.dataTimestamp || 0;
  const age = ts>0 ? now-ts : Number.POSITIVE_INFINITY;
  const delayOk = Number.isFinite(contract.delaySeconds) && contract.delaySeconds >= 0 && contract.delaySeconds <= MAX_DELAY_SECONDS;
  const live = Boolean(contract.realtime && contract.currentSessionIncluded && ts>0 && age >= -15_000 && age <= MAX_QUOTE_AGE_MS && delayOk && contract.lastPrice>0);
  const sessionStatus = canonicalSymbol(mapped(contract._raw || {}, 'sessionStatus'));
  if (live) return { state:'LIVE', live:true, ageMs:age };
  if (sessionStatus === 'CLOSED' || sessionStatus === 'MARKET_CLOSED') return { state:'MARKET_CLOSED', live:false, ageMs:age };
  if (ts>0 && age > MAX_QUOTE_AGE_MS) return { state:'STALE_QUOTE', live:false, ageMs:age };
  return { state:'NO_LIVE_QUOTE', live:false, ageMs:age };
}
async function refreshDetails(force=false) {
  const now = Date.now();
  if (!force && state.details.fetchedAt && now-state.details.fetchedAt < DETAILS_CACHE_MS) return state.details;
  const {body,headers} = await providerGet(TW_UNIVERSE_URL || TW_DETAILS_URL);
  const rows = detailsEntries(body);
  const entries = new Map();
  for (const [symbol,raw] of rows) {
    if (!symbol.startsWith('F_')) continue;
    const c = normalizeContract(symbol, raw); c._raw = raw; entries.set(symbol,c);
  }
  const proof = universeProof(body, headers, rows.length);
  state.details = { fetchedAt:now, providerTimestamp:epochMs(body?.universeAsOf || body?.timestamp), entries, totalCount:proof.totalCount, universeComplete:proof.complete, error:null };
  const contracts=[...entries.values()];
  const active=contracts.filter(c=>c.valid && c.lastTradingAt>now);
  const qs=contracts.map(c=>quoteState(c,now));
  state.metrics.contractCount=contracts.length;
  state.metrics.metadataValidCount=contracts.filter(c=>c.valid).length;
  state.metrics.metadataInvalidCount=contracts.filter(c=>!c.valid).length;
  state.metrics.activeContractCount=active.length;
  state.metrics.liveQuoteCount=qs.filter(q=>q.live).length;
  state.metrics.staleQuoteCount=qs.filter(q=>q.state==='STALE_QUOTE').length;
  const latest=Math.max(0,...contracts.map(c=>c.dataTimestamp||0));
  state.metrics.lastQuoteTimestamp=latest;
  state.metrics.lastQuoteAgeSeconds=latest>0?Math.max(0,Math.floor((now-latest)/1000)):null;
  state.metrics.currentSession=qs.some(q=>q.live)?'OPEN':(qs.some(q=>q.state==='MARKET_CLOSED')?'CLOSED':'UNKNOWN');
  state.metrics.lastRefreshAt=now;
  state.metrics.lastErrorCode=null; state.metrics.lastErrorMessage=null;
  return state.details;
}
function canonicalContractResponse(c) {
  return { symbol:c.symbol, underlying:c.underlying, expiry:c.expiry, contractType:'FUTURE', lastTradingAt:c.lastTradingAt, expiryAt:c.expiryAt,
    tickSize:c.tickSize, multiplier:c.multiplier, currency:c.currency, lastPrice:c.lastPrice, bid:c.bid, ask:c.ask, dailyChangePct:c.dailyChangePct,
    openInterest:c.openInterest, volume:c.volume, dataTimestamp:c.dataTimestamp, realtime:c.realtime, delaySeconds:c.delaySeconds,
    currentSessionIncluded:c.currentSessionIncluded, source:c.source, settlementType:c.settlementType, exchangeTimezone:c.exchangeTimezone };
}
function canonicalQuoteResponse(c) {
  const q=quoteState(c);
  if (!q.live) throw providerFailure(q.state, q.state==='MARKET_CLOSED'?'VİOP piyasa/seans kapalı; son veri canlı olarak kullanılmaz.':q.state==='STALE_QUOTE'?`Quote stale; age=${Math.floor(q.ageMs/1000)}s`:'Doğrulanmış current-session quote yok.', 409);
  return { symbol:c.symbol, price:c.lastPrice, bid:c.bid, ask:c.ask, dailyChangePct:c.dailyChangePct, volume:c.volume, openInterest:c.openInterest,
    exchangeTimestamp:c.dataTimestamp, receivedAt:Date.now(), source:c.source, realtime:true, delaySeconds:c.delaySeconds, currentSessionIncluded:true };
}

async function loadHistory(symbol, rangeValue, interval) {
  if (!endpointConfigured(TW_HISTORY_TEMPLATE,true)) throw providerFailure('NO_HISTORY','Doğrulanmış TradeWize history/OHLC endpoint yapılandırılmamış.',503);
  const key=`${symbol}|${rangeValue}|${interval}`;
  const cached=state.history.get(key); const now=Date.now();
  if (cached && now-cached.fetchedAt<HISTORY_CACHE_MS) return cached.body;
  const base=TW_HISTORY_TEMPLATE.replace('{symbol}', encodeURIComponent(symbol));
  const u=new URL(base); u.searchParams.set('range',rangeValue); u.searchParams.set('interval',interval);
  const {body}=await providerGet(u.toString());
  const responseSymbol=canonicalSymbol(mappedHistory(body,'symbol'));
  const responseInterval=String(mappedHistory(body,'interval')||'').trim();
  const lastBarClosed=mappedHistory(body,'lastBarClosed')===true;
  const exchangeTimestamp=epochMs(mappedHistory(body,'exchangeTimestamp') ?? mappedHistory(body,'lastExchangeTimestamp'));
  const rawCandles=mappedHistory(body,'candles');
  if (responseSymbol!==symbol || responseInterval!==interval || !lastBarClosed || exchangeTimestamp<=0 || !Array.isArray(rawCandles)) throw providerFailure('OHLC_UNAVAILABLE','History/OHLC metadata doğrulanamadı.',502);
  const candles=rawCandles.map((x)=>({ timestamp:epochMs(mappedHistory(x,'timestamp')), open:Number(mappedHistory(x,'open')), high:Number(mappedHistory(x,'high')), low:Number(mappedHistory(x,'low')), close:Number(mappedHistory(x,'close')), volume:Number(mappedHistory(x,'volume')) })).filter(c=>c.timestamp>0&&[c.open,c.high,c.low,c.close,c.volume].every(Number.isFinite)&&c.open>0&&c.high>0&&c.low>0&&c.close>0&&c.volume>=0&&c.low<=c.high&&c.open>=c.low&&c.open<=c.high&&c.close>=c.low&&c.close<=c.high).sort((a,b)=>a.timestamp-b.timestamp);
  if (!candles.length || candles.length!==rawCandles.length) throw providerFailure('OHLC_UNAVAILABLE','History/OHLC candle doğrulaması başarısız.',502);
  for(let i=1;i<candles.length;i++) if(candles[i].timestamp<=candles[i-1].timestamp) throw providerFailure('OHLC_UNAVAILABLE','History timestamps strictly increasing değil.',502);
  const normalized={ symbol, interval, lastBarClosed:true, exchangeTimestamp, candles };
  state.history.set(key,{fetchedAt:now,body:normalized});
  state.metrics.historyReady=true; state.metrics.lastHistoryAt=now;
  state.metrics.technicalEngineReady=state.metrics.activeContractCount>0 && state.metrics.liveQuoteCount>0 && state.metrics.historyReady;
  return normalized;
}

function capabilitySnapshot() {
  const authReady=state.auth.status==='CONNECTED';
  const metadataReady=state.metrics.contractCount>0 && state.metrics.metadataInvalidCount===0 && state.metrics.metadataValidCount===state.metrics.contractCount;
  const contractsReady=authReady && state.details.universeComplete && metadataReady && state.metrics.activeContractCount>0;
  const quoteReady=contractsReady && state.metrics.liveQuoteCount>0;
  const historyReady=state.metrics.historyReady;
  const scannerReady=contractsReady && quoteReady && historyReady && state.metrics.technicalEngineReady;
  const reasonCode = !authReady ? (state.auth.status==='NOT_CONFIGURED'?'NO_PROVIDER':'AUTH_ERROR') : !state.details.universeComplete?'NO_CONTRACT_UNIVERSE':!metadataReady?'NO_METADATA':state.metrics.activeContractCount<=0?'NO_ACTIVE_CONTRACT':state.metrics.liveQuoteCount<=0?(state.metrics.currentSession==='CLOSED'?'MARKET_CLOSED':'NO_LIVE_QUOTE'):!historyReady?'NO_HISTORY':null;
  return {
    ok:true, version:'6.1.1-sidecar-r4', providerReady:scannerReady, multiMarketReady:false, coreMarketsReady:false, globalProviderReady:false,
    primaryConfiguredProvider:'TradeWize', tradeWize:{state:state.auth.status,authenticated:authReady,lastAuthAt:state.auth.lastAttemptAt,error:state.auth.error},
    features:{viopContractsReady:contractsReady,shortLivedSessionAuth:false,dynamicScanner:false,realtimeScannerRest:false,liveMarketWebSocket:false,realtimeScannerWebSocket:false,attestationReady:false,tradingViewSignals:false,researchFoundation:false,allTimeHistory:false,barHistoryCache:true,ingressRateLimit:false,fullBistFiveMinuteSla:false,bistSnapshotBatch:false},
    markets:{VIOP:{ready:scannerReady,supported:true,discovery:contractsReady,marketData:quoteReady,historicalData:historyReady,realtime:quoteReady,realtimeReady:quoteReady,analysisReady:scannerReady,symbolCount:state.metrics.activeContractCount,provider:'TradeWize',reasonCode,message:reasonCode?'VİOP provider not ready':'VİOP provider connected',contractsReady,metadataReady,activeFutureReady:state.metrics.activeContractCount>0,quoteReady,historyReady,liquidityReady:quoteReady,freshnessReady:quoteReady,scannerReady}}
  };
}
function healthSnapshot() {
  const c=capabilitySnapshot();
  return { ok:c.markets.VIOP.ready, provider:'TradeWize', connection:state.auth.status, capability:c.markets.VIOP.ready?'READY':'NOT_READY', metadata:c.markets.VIOP.metadataReady?'OK':'FAIL', contractUniverse:c.markets.VIOP.contractsReady?'OK':'FAIL', session:state.metrics.currentSession, quotes:c.markets.VIOP.quoteReady?'OK':'FAIL', ohlc:c.markets.VIOP.historyReady?'OK':'FAIL', technicalEngine:c.markets.VIOP.analysisReady?'OK':'FAIL',
    contractCount:state.metrics.contractCount, metadataValidCount:state.metrics.metadataValidCount, metadataInvalidCount:state.metrics.metadataInvalidCount, activeContractCount:state.metrics.activeContractCount, liveQuoteCount:state.metrics.liveQuoteCount, staleQuoteCount:state.metrics.staleQuoteCount, lastQuoteTimestamp:state.metrics.lastQuoteTimestamp||null, quoteAgeSeconds:state.metrics.lastQuoteAgeSeconds, lastRefreshAt:state.metrics.lastRefreshAt||null, lastHistoryAt:state.metrics.lastHistoryAt||null, reasonCode:c.markets.VIOP.reasonCode, lastErrorCode:state.metrics.lastErrorCode, lastErrorMessage:state.metrics.lastErrorMessage };
}
function providerError(e) { const code=e?.code||'UPSTREAM_ERROR'; const msg=e?.message||'Provider error'; recordError(code,msg); return json({ok:false,code,message:msg},e?.status||502); }

const server=Bun.serve({port:PORT,async fetch(req){
  const u=new URL(req.url);
  if(u.pathname==='/health'||u.pathname==='/v1/health') return json({ok:true,service:'v611-viop-sidecar',version:'6.1.1-sidecar-r4'});
  if(!authOk(req)) return json({ok:false,code:'AUTH_FAILED'},401);
  if(u.pathname==='/v1/provider/capabilities'){
    await ensureProviderAuth(false);
    try { if(state.auth.status==='CONNECTED') await refreshDetails(false); } catch(e){ recordError(e.code||'UPSTREAM_ERROR',e.message||'refresh failed'); }
    return json(capabilitySnapshot());
  }
  if(u.pathname==='/v1/viop/health'){
    await ensureProviderAuth(false);
    try { if(state.auth.status==='CONNECTED') await refreshDetails(u.searchParams.get('refresh')==='1'); } catch(e){ recordError(e.code||'UPSTREAM_ERROR',e.message||'refresh failed'); }
    return json(healthSnapshot(),200);
  }
  if(u.pathname==='/v1/preflight'){
    await ensureProviderAuth(true);
    try { if(state.auth.status==='CONNECTED') await refreshDetails(true); } catch(e){ return providerError(e); }
    const h=healthSnapshot(); return json({ok:h.ok,tradeWize:{state:state.auth.status,authenticated:state.auth.status==='CONNECTED',error:state.auth.error},viop:h},h.ok?200:503);
  }
  if(u.pathname==='/v1/viop/contracts'){
    try {
      await refreshDetails(u.searchParams.get('refresh')==='1');
      if(!state.details.universeComplete) throw providerFailure('NO_CONTRACT_UNIVERSE','Provider tam kontrat evrenini doğrulayamadı.',503);
      const now=Date.now(); const rows=[...state.details.entries.values()].filter(c=>c.valid&&c.lastTradingAt>now).map(canonicalContractResponse);
      if(!rows.length) throw providerFailure(state.metrics.metadataValidCount?'NO_ACTIVE_CONTRACT':'NO_METADATA',state.metrics.metadataValidCount?'Aktif doğrulanmış VİOP kontratı yok.':'Kontrat metadata doğrulanamadı.',503);
      const limit=Math.min(200,Math.max(1,Number(u.searchParams.get('limit'))||200)); const offset=Math.max(0,Number(u.searchParams.get('cursor'))||0); const slice=rows.slice(offset,offset+limit); const next=offset+slice.length<rows.length?String(offset+slice.length):null;
      return json({items:slice,totalCount:rows.length,hasMore:next!==null,nextCursor:next,universeAsOf:state.details.providerTimestamp||state.details.fetchedAt});
    }catch(e){return providerError(e);}
  }
  const qm=u.pathname.match(/^\/v1\/viop\/quote\/([^/]+)$/);
  if(qm){try{await refreshDetails(false); const symbol=canonicalSymbol(decodeURIComponent(qm[1])); const c=state.details.entries.get(symbol); if(!c) throw providerFailure('NO_LIVE_QUOTE',`${symbol} provider details içinde bulunamadı.`,404); return json(canonicalQuoteResponse(c));}catch(e){return providerError(e);}}
  const hm=u.pathname.match(/^\/v1\/viop\/history\/([^/]+)$/);
  if(hm){try{const symbol=canonicalSymbol(decodeURIComponent(hm[1])); const range=String(u.searchParams.get('range')||'1y'); const interval=String(u.searchParams.get('interval')||'1d'); if(!/^[0-9]+[dmy]$/.test(range)||!['1m','3m','5m','15m','60m','1d'].includes(interval)) throw providerFailure('OHLC_UNAVAILABLE','Geçersiz history range/interval.',400); return json(await loadHistory(symbol,range,interval));}catch(e){return providerError(e);}}
  return json({ok:false,code:'NOT_FOUND'},404);
}});
console.log(`V611_VIOP_SIDECAR_R4_LISTENING port=${server.port}`);
console.log(`V611_TRADEWIZE_AUTH_CONFIGURED=${authConfigured()?'YES':'NO'}`);
console.log(`V611_DETAILS_ENDPOINT=${endpointConfigured(TW_DETAILS_URL)?'CONFIGURED':'MISSING'}`);
console.log(`V611_HISTORY_ENDPOINT=${endpointConfigured(TW_HISTORY_TEMPLATE,true)?'CONFIGURED':'MISSING'}`);
