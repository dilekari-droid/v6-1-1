const PORT = Number(process.env.PORT || 8080);
const APP_KEY = (process.env.BORSA_API_KEY || process.env.APP_API_KEY || '').trim();
const TW_KEY = (process.env.TRADEWIZE_API_KEY || '').trim();
const TW_BASE = (process.env.TRADEWIZE_BASE_URL || 'https://api.tradewize.com.tr').replace(/\/$/, '');
const UNIVERSE_URL = (process.env.TRADEWIZE_UNIVERSE_URL || '').trim();
const QUOTE_TEMPLATE = (process.env.TRADEWIZE_QUOTE_URL_TEMPLATE || '').trim();
const HISTORY_TEMPLATE = (process.env.TRADEWIZE_HISTORY_URL_TEMPLATE || '').trim();

let authCache = { token: '', expiresAt: 0, state: TW_KEY ? 'CONFIGURED_UNVERIFIED' : 'NOT_CONFIGURED', error: null };
let lastAuthAt = 0;

function json(body, status=200, headers={}) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type':'application/json; charset=utf-8', 'cache-control':'no-store', ...headers } });
}
function authOk(req) {
  if (!APP_KEY) return true;
  const apiKey = (req.headers.get('x-api-key') || '').trim();
  const auth = (req.headers.get('authorization') || '').trim();
  return apiKey === APP_KEY || auth === `Bearer ${APP_KEY}`;
}
function canonicalSymbol(s) { return String(s || '').trim().toUpperCase(); }
function endpointConfigured(v, needsSymbol=false) {
  if (!v) return false;
  try { const u = new URL(needsSymbol ? v.replace('{symbol}','X') : v); return u.protocol === 'https:' && (!needsSymbol || v.includes('{symbol}')); }
  catch { return false; }
}
async function ensureTradeWizeAuth(force=false) {
  if (!TW_KEY) {
    authCache = { token:'', expiresAt:0, state:'NOT_CONFIGURED', error:'TRADEWIZE_API_KEY missing' };
    return authCache;
  }
  const now = Date.now();
  if (!force && authCache.token && authCache.expiresAt > now + 30_000) return authCache;
  lastAuthAt = now;
  try {
    const r = await fetch(`${TW_BASE}/oauth/token`, {
      method:'POST',
      headers:{ 'X-API-Key':TW_KEY, 'Accept':'application/json', 'Content-Type':'application/json' },
      body:JSON.stringify({ grant_type:'api_key' }),
      signal:AbortSignal.timeout(15000)
    });
    const text = await r.text();
    let body = {}; try { body = text ? JSON.parse(text) : {}; } catch {}
    if (!r.ok) {
      authCache = { token:'', expiresAt:0, state:'AUTH_FAILED', error:`HTTP ${r.status}` };
      return authCache;
    }
    const token = String(body.access_token || '').trim();
    if (!token) {
      authCache = { token:'', expiresAt:0, state:'AUTH_FAILED', error:'access_token missing' };
      return authCache;
    }
    const ttl = Number(body.expires_in || 300);
    authCache = { token, expiresAt:now + Math.max(60, ttl) * 1000, state:'CONNECTED', error:null };
    return authCache;
  } catch (e) {
    authCache = { token:'', expiresAt:0, state:'AUTH_FAILED', error:e?.name || 'AUTH_ERROR' };
    return authCache;
  }
}
async function providerGet(url) {
  const auth = await ensureTradeWizeAuth();
  if (auth.state !== 'CONNECTED') throw Object.assign(new Error(auth.error || 'TradeWize auth unavailable'), { code:'TRADEWIZE_AUTH_FAILED', status:503 });
  const r = await fetch(url, { headers:{ 'Authorization':`Bearer ${auth.token}`, 'Accept':'application/json', 'User-Agent':'BorsaTakip-V611-VIOP-Sidecar/1' }, signal:AbortSignal.timeout(20000) });
  const text = await r.text();
  let body = {}; try { body = text ? JSON.parse(text) : {}; } catch { throw Object.assign(new Error('Provider non-JSON response'), { code:'TRADEWIZE_INVALID_RESPONSE', status:502 }); }
  if (r.status === 401 || r.status === 403) { authCache.token=''; authCache.expiresAt=0; authCache.state='AUTH_FAILED'; }
  if (!r.ok) throw Object.assign(new Error(`Provider HTTP ${r.status}`), { code:'TRADEWIZE_HTTP_ERROR', status:r.status===429?429:502 });
  return { body, headers:r.headers };
}
function capabilitySnapshot() {
  const endpointsReady = endpointConfigured(UNIVERSE_URL) && endpointConfigured(QUOTE_TEMPLATE,true) && endpointConfigured(HISTORY_TEMPLATE,true);
  const authReady = authCache.state === 'CONNECTED';
  const contractsReady = authReady && endpointConfigured(UNIVERSE_URL);
  const quoteReady = authReady && endpointConfigured(QUOTE_TEMPLATE,true);
  const historyReady = authReady && endpointConfigured(HISTORY_TEMPLATE,true);
  const viopReady = contractsReady && quoteReady && historyReady;
  return {
    ok:true, version:'6.1.1-sidecar', providerReady:viopReady, multiMarketReady:false, coreMarketsReady:false, globalProviderReady:false,
    primaryConfiguredProvider:'TradeWize',
    tradeWize:{ state:authCache.state, authenticated:authReady, adapterVerified:endpointsReady && authReady, lastAuthAt, error:authCache.error },
    features:{ viopContractsReady:contractsReady, shortLivedSessionAuth:false, dynamicScanner:false, realtimeScannerRest:false, liveMarketWebSocket:false, realtimeScannerWebSocket:false, attestationReady:false, tradingViewSignals:false, researchFoundation:false, allTimeHistory:false, barHistoryCache:false, ingressRateLimit:false, fullBistFiveMinuteSla:false, bistSnapshotBatch:false },
    scanPolicy:{ snapshotBatchMaxSymbols:20, snapshotBatchReadTimeoutMs:175000, snapshotBatchCallTimeoutMs:180000, snapshotBatchOuterTimeoutMs:185000 },
    markets:{ VIOP:{ ready:viopReady, supported:true, discovery:contractsReady, marketData:quoteReady, historicalData:historyReady, realtime:quoteReady, realtimeReady:false, analysisReady:false, symbolCount:0, provider:'TradeWize', reasonCode:viopReady?null:(authReady?'TRADEWIZE_ENDPOINTS_NOT_CONFIGURED':authCache.state), message:viopReady?'VİOP provider connected':'VİOP provider not ready', contractsReady, metadataReady:contractsReady, activeFutureReady:false, quoteReady, historyReady, liquidityReady:false, freshnessReady:false, scannerReady:false } }
  };
}
function providerError(e) { return json({ ok:false, code:e.code || 'VIOP_PROVIDER_ERROR', message:e.message || 'Provider error' }, e.status || 502); }

const server = Bun.serve({
  port:PORT,
  async fetch(req) {
    const u = new URL(req.url);
    if (u.pathname === '/health' || u.pathname === '/v1/health') return json({ ok:true, service:'v611-viop-sidecar', version:'6.1.1' });
    if (!authOk(req)) return json({ ok:false, code:'AUTH_FAILED' }, 401);
    if (u.pathname === '/v1/provider/capabilities') {
      await ensureTradeWizeAuth(false);
      return json(capabilitySnapshot());
    }
    if (u.pathname === '/v1/preflight') {
      await ensureTradeWizeAuth(true);
      const c = capabilitySnapshot();
      return json({ ok:c.tradeWize.state === 'CONNECTED', tradeWize:c.tradeWize, markets:c.markets }, c.tradeWize.state === 'CONNECTED' ? 200 : 503);
    }
    if (u.pathname === '/v1/viop/contracts') {
      if (!endpointConfigured(UNIVERSE_URL)) return json({ ok:false, code:'TRADEWIZE_UNIVERSE_URL_MISSING', message:'TRADEWIZE_UNIVERSE_URL yapılandırılmadı.' }, 503);
      try {
        const url = new URL(UNIVERSE_URL);
        if (u.searchParams.has('limit')) url.searchParams.set('limit', u.searchParams.get('limit'));
        if (u.searchParams.has('cursor')) url.searchParams.set('cursor', u.searchParams.get('cursor'));
        const {body, headers} = await providerGet(url.toString());
        const complete = String(headers.get('x-tradewise-selection-complete') || '').toLowerCase();
        if (!['true','1','yes'].includes(complete)) return json({ ok:false, code:'SELECTION_INCOMPLETE', message:'X-TradeWise-Selection-Complete doğrulanmadı.' }, 502);
        const items = Array.isArray(body.items) ? body.items : null;
        if (!items) return json({ ok:false, code:'UNIVERSE_SCHEMA_INVALID', message:'Provider items dizisi eksik.' }, 502);
        const canonical = items.filter(x => x && typeof x === 'object').map(x => ({...x, symbol:canonicalSymbol(x.symbol)})).filter(x => x.symbol);
        const missingRequired = canonical.find(x => !x.underlying || !x.expiry || !x.lastTradingAt || !(Number(x.tickSize)>0) || !(Number(x.multiplier)>0));
        if (missingRequired) return json({ ok:false, code:'VIOP_CONTRACT_METADATA_UNAVAILABLE', message:'Provider kontrat metadata alanları eksik.' }, 502);
        return json({ items:canonical, totalCount:Number.isFinite(Number(body.totalCount))?Number(body.totalCount):canonical.length, hasMore:body.hasMore===true, nextCursor:body.nextCursor || null, universeAsOf:Number(body.universeAsOf)||Date.now() });
      } catch(e) { return providerError(e); }
    }
    const qm = u.pathname.match(/^\/v1\/viop\/quote\/([^/]+)$/);
    if (qm) {
      if (!endpointConfigured(QUOTE_TEMPLATE,true)) return json({ ok:false, code:'TRADEWIZE_QUOTE_URL_MISSING', message:'TRADEWIZE_QUOTE_URL_TEMPLATE yapılandırılmadı.' }, 503);
      try {
        const symbol = canonicalSymbol(decodeURIComponent(qm[1]));
        const {body} = await providerGet(QUOTE_TEMPLATE.replace('{symbol}', encodeURIComponent(symbol)));
        if (canonicalSymbol(body.symbol) !== symbol || !(Number(body.price)>0) || !(Number(body.exchangeTimestamp)>0)) return json({ ok:false, code:'VIOP_QUOTE_SCHEMA_INVALID', message:'Provider quote alanları doğrulanamadı.' }, 502);
        return json(body);
      } catch(e) { return providerError(e); }
    }
    const hm = u.pathname.match(/^\/v1\/viop\/history\/([^/]+)$/);
    if (hm) {
      if (!endpointConfigured(HISTORY_TEMPLATE,true)) return json({ ok:false, code:'TRADEWIZE_HISTORY_URL_MISSING', message:'TRADEWIZE_HISTORY_URL_TEMPLATE yapılandırılmadı.' }, 503);
      try {
        const symbol = canonicalSymbol(decodeURIComponent(hm[1]));
        const range = u.searchParams.get('range') || '1y';
        const interval = u.searchParams.get('interval') || '1d';
        const url = new URL(HISTORY_TEMPLATE.replace('{symbol}', encodeURIComponent(symbol)));
        url.searchParams.set('range', range); url.searchParams.set('interval', interval);
        const {body} = await providerGet(url.toString());
        if (canonicalSymbol(body.symbol) !== symbol || body.interval !== interval || !Array.isArray(body.candles)) return json({ ok:false, code:'VIOP_HISTORY_SCHEMA_INVALID', message:'Provider history alanları doğrulanamadı.' }, 502);
        return json(body);
      } catch(e) { return providerError(e); }
    }
    return json({ ok:false, code:'NOT_FOUND' }, 404);
  }
});
console.log(`V611_VIOP_SIDECAR_LISTENING port=${server.port}`);
console.log(`V611_TRADEWIZE_KEY_PRESENT=${TW_KEY?'YES':'NO'}`);
console.log(`V611_ENDPOINTS_CONFIGURED=${endpointConfigured(UNIVERSE_URL)&&endpointConfigured(QUOTE_TEMPLATE,true)&&endpointConfigured(HISTORY_TEMPLATE,true)?'YES':'NO'}`);
