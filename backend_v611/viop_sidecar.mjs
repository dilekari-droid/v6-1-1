import {
  VIOP_LIMITS,
  canonicalSymbol,
  endpointConfigured,
  validateContractMetadata,
  validateQuote,
  validateHistory,
  resolveLiquidity,
  buildViopReadiness,
} from './viop_runtime_policy.mjs';

const PORT = Number(process.env.PORT || 8080);
const APP_KEY = (process.env.BORSA_API_KEY || process.env.APP_API_KEY || '').trim();
const TW_KEY = (process.env.TRADEWIZE_API_KEY || '').trim();
const TW_BASE = (process.env.TRADEWIZE_BASE_URL || 'https://api.tradewize.com.tr').replace(/\/$/, '');
const UNIVERSE_URL = (process.env.TRADEWIZE_UNIVERSE_URL || '').trim();
const QUOTE_TEMPLATE = (process.env.TRADEWIZE_QUOTE_URL_TEMPLATE || '').trim();
const HISTORY_TEMPLATE = (process.env.TRADEWIZE_HISTORY_URL_TEMPLATE || '').trim();
const SOURCE_REVISION = (process.env.V611_SOURCE_REVISION || 'v611-viop-sidecar-hardening').trim();
const MIN_INTERVAL_MS = Math.max(1000, Number(process.env.TRADEWIZE_MIN_INTERVAL_MS || 1000));
const MAX_UNIVERSE_PAGES = Math.max(1, Math.min(100, Number(process.env.TRADEWIZE_MAX_UNIVERSE_PAGES || 50)));

let authCache = { token: '', expiresAt: 0, state: TW_KEY ? 'CONFIGURED_UNVERIFIED' : 'NOT_CONFIGURED', error: null, reasonCode: TW_KEY ? null : 'TRADEWIZE_API_KEY_MISSING' };
let lastAuthAt = 0;
let lastProbeAt = 0;
let lastProbe = buildViopReadiness({ authState: authCache.state, reasonCode: authCache.reasonCode });
let lastProbeStats = { contractCount: 0, validMetadataCount: 0, activeFutureCount: 0, historyBars: 0, quoteAgeMs: null, openInterestAvailable: false };

function json(body, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', ...headers } });
}
function authOk(req) {
  if (!APP_KEY) return true;
  const apiKey = (req.headers.get('x-api-key') || '').trim();
  const auth = (req.headers.get('authorization') || '').trim();
  return apiKey === APP_KEY || auth === `Bearer ${APP_KEY}`;
}
function providerError(code, message, status = 502) {
  const e = new Error(message || code); e.code = code; e.status = status; return e;
}
function safeError(e) {
  return { ok: false, code: e?.code || 'VIOP_PROVIDER_ERROR', message: e?.message || 'Provider error' };
}
function parseRetryAfterMs(value) {
  const text = String(value || '').trim();
  if (!text) return MIN_INTERVAL_MS;
  const seconds = Number(text);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.max(MIN_INTERVAL_MS, Math.ceil(seconds * 1000));
  const when = Date.parse(text);
  return Number.isFinite(when) ? Math.max(MIN_INTERVAL_MS, when - Date.now()) : MIN_INTERVAL_MS;
}

const quota = {
  lastDispatchAt: 0,
  cooldownUntil: 0,
  tail: Promise.resolve(),
  inflight: new Map(),
  async acquire() {
    let release;
    const previous = this.tail;
    this.tail = new Promise((resolve) => { release = resolve; });
    await previous;
    try {
      const now = Date.now();
      const wait = Math.max(0, this.cooldownUntil - now, MIN_INTERVAL_MS - (now - this.lastDispatchAt));
      if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
      this.lastDispatchAt = Date.now();
    } finally {
      release();
    }
  },
  applyRetryAfter(value) {
    this.cooldownUntil = Math.max(this.cooldownUntil, Date.now() + parseRetryAfterMs(value));
  },
  singleflight(key, factory) {
    const normalized = String(key || '').trim();
    if (!normalized) throw new Error('singleflight key required');
    const existing = this.inflight.get(normalized);
    if (existing) return existing;
    const p = Promise.resolve().then(factory).finally(() => {
      if (this.inflight.get(normalized) === p) this.inflight.delete(normalized);
    });
    this.inflight.set(normalized, p);
    return p;
  },
};

async function ensureTradeWizeAuth(force = false) {
  if (!TW_KEY) {
    authCache = { token: '', expiresAt: 0, state: 'NOT_CONFIGURED', error: 'TRADEWIZE_API_KEY missing', reasonCode: 'TRADEWIZE_API_KEY_MISSING' };
    return authCache;
  }
  const now = Date.now();
  if (!force && authCache.token && authCache.expiresAt > now + 30_000) return authCache;
  return quota.singleflight('oauth-token', async () => {
    const again = Date.now();
    if (!force && authCache.token && authCache.expiresAt > again + 30_000) return authCache;
    lastAuthAt = again;
    await quota.acquire();
    try {
      const r = await fetch(`${TW_BASE}/oauth/token`, {
        method: 'POST',
        headers: { 'X-API-Key': TW_KEY, 'Accept': 'application/json', 'Content-Type': 'application/json' },
        body: JSON.stringify({ grant_type: 'api_key' }),
        signal: AbortSignal.timeout(15_000),
      });
      const text = await r.text();
      let body = {}; try { body = text ? JSON.parse(text) : {}; } catch { body = {}; }
      if (r.status === 429) {
        quota.applyRetryAfter(r.headers.get('retry-after'));
        authCache = { token: '', expiresAt: 0, state: 'AUTH_FAILED', error: 'HTTP 429', reasonCode: 'TRADEWIZE_RATE_LIMITED' };
        return authCache;
      }
      if (!r.ok) {
        const reasonCode = r.status === 401 ? 'TRADEWIZE_AUTH_401' : r.status === 403 ? 'TRADEWIZE_PERMISSION_403' : 'TRADEWIZE_AUTH_HTTP_ERROR';
        authCache = { token: '', expiresAt: 0, state: 'AUTH_FAILED', error: `HTTP ${r.status}`, reasonCode };
        return authCache;
      }
      const token = String(body.access_token || '').trim();
      if (!token) {
        authCache = { token: '', expiresAt: 0, state: 'AUTH_FAILED', error: 'access_token missing', reasonCode: 'TRADEWIZE_ACCESS_TOKEN_MISSING' };
        return authCache;
      }
      const ttl = Number(body.expires_in || 300);
      authCache = { token, expiresAt: again + Math.max(60, Number.isFinite(ttl) ? ttl : 300) * 1000, state: 'CONNECTED', error: null, reasonCode: null };
      return authCache;
    } catch (e) {
      authCache = { token: '', expiresAt: 0, state: 'AUTH_FAILED', error: e?.name || 'AUTH_ERROR', reasonCode: 'TRADEWIZE_AUTH_NETWORK_ERROR' };
      return authCache;
    }
  });
}

async function providerGet(url, singleflightKey = url) {
  return quota.singleflight(`GET:${singleflightKey}`, async () => {
    const auth = await ensureTradeWizeAuth(false);
    if (auth.state !== 'CONNECTED') throw providerError(auth.reasonCode || 'TRADEWIZE_AUTH_FAILED', 'TradeWize authentication unavailable', 503);
    await quota.acquire();
    const r = await fetch(url, {
      headers: { 'Authorization': `Bearer ${auth.token}`, 'Accept': 'application/json', 'User-Agent': 'BorsaTakip-V611-VIOP-Sidecar/2' },
      signal: AbortSignal.timeout(20_000),
    });
    const text = await r.text();
    let body = {};
    try { body = text ? JSON.parse(text) : {}; }
    catch { throw providerError('TRADEWIZE_INVALID_RESPONSE', 'Provider non-JSON response', 502); }
    if (r.status === 429) {
      quota.applyRetryAfter(r.headers.get('retry-after'));
      throw providerError('TRADEWIZE_RATE_LIMITED', 'Provider rate limited request', 429);
    }
    if (r.status === 401 || r.status === 403) {
      authCache = { token: '', expiresAt: 0, state: 'AUTH_FAILED', error: `HTTP ${r.status}`, reasonCode: r.status === 401 ? 'TRADEWIZE_AUTH_401' : 'TRADEWIZE_PERMISSION_403' };
      throw providerError(authCache.reasonCode, `Provider HTTP ${r.status}`, r.status);
    }
    if (!r.ok) throw providerError('TRADEWIZE_HTTP_ERROR', `Provider HTTP ${r.status}`, 502);
    return { body, headers: r.headers };
  });
}

function selectionHeader(headers) {
  const raw = headers.get('x-tradewise-selection-complete') ?? headers.get('x-tradewize-selection-complete');
  if (raw == null) return null;
  return ['true', '1', 'yes'].includes(String(raw).trim().toLowerCase());
}

async function loadUniverseComplete() {
  if (!endpointConfigured(UNIVERSE_URL)) throw providerError('TRADEWIZE_UNIVERSE_URL_MISSING', 'TRADEWIZE_UNIVERSE_URL not configured', 503);
  const all = [];
  const seenCursors = new Set();
  let cursor = null;
  let totalCount = null;
  let universeAsOf = null;
  let explicitComplete = null;
  let page = 0;
  do {
    page += 1;
    if (page > MAX_UNIVERSE_PAGES) throw providerError('UNIVERSE_INCOMPLETE', 'Universe page limit exceeded', 502);
    const url = new URL(UNIVERSE_URL);
    if (cursor) url.searchParams.set('cursor', cursor);
    const { body, headers } = await providerGet(url.toString(), `universe:${cursor || 'first'}`);
    if (!Array.isArray(body.items)) throw providerError('UNIVERSE_SCHEMA_INVALID', 'Provider items array missing', 502);
    all.push(...body.items.filter((x) => x && typeof x === 'object'));
    const headerComplete = selectionHeader(headers);
    if (headerComplete !== null) explicitComplete = headerComplete;
    if (body.totalCount != null) {
      const current = Number(body.totalCount);
      if (!Number.isInteger(current) || current < 0) throw providerError('UNIVERSE_SCHEMA_INVALID', 'totalCount invalid', 502);
      if (totalCount != null && totalCount !== current) throw providerError('UNIVERSE_INCOMPLETE', 'totalCount changed between pages', 502);
      totalCount = current;
    }
    if (body.universeAsOf != null) {
      const current = Number(body.universeAsOf);
      if (!Number.isFinite(current) || current <= 0) throw providerError('UNIVERSE_SCHEMA_INVALID', 'universeAsOf invalid', 502);
      if (universeAsOf != null && universeAsOf !== current) throw providerError('UNIVERSE_INCOMPLETE', 'universeAsOf changed between pages', 502);
      universeAsOf = current;
    }
    const hasMore = body.hasMore === true;
    const next = String(body.nextCursor || '').trim();
    if (hasMore) {
      if (!next) throw providerError('UNIVERSE_INCOMPLETE', 'hasMore=true without nextCursor', 502);
      if (seenCursors.has(next)) throw providerError('UNIVERSE_INCOMPLETE', 'cursor loop detected', 502);
      seenCursors.add(next); cursor = next;
    } else {
      cursor = null;
    }
  } while (cursor);

  const uniqueMap = new Map();
  for (const item of all) {
    const symbol = canonicalSymbol(item.symbol);
    if (!symbol) continue;
    if (!uniqueMap.has(symbol)) uniqueMap.set(symbol, { ...item, symbol });
  }
  const items = [...uniqueMap.values()];
  const bodyComplete = totalCount != null && totalCount === items.length;
  if (explicitComplete === false) throw providerError('SELECTION_INCOMPLETE', 'Provider selection-complete explicitly false', 502);
  if (explicitComplete !== true && !bodyComplete) throw providerError('SELECTION_INCOMPLETE', 'Universe completeness could not be proven', 502);
  if (!items.length) throw providerError('VIOP_CONTRACTS_EMPTY', 'Provider contract universe is empty', 502);
  return { items, totalCount: totalCount ?? items.length, hasMore: false, nextCursor: null, universeAsOf, pageCount: page, completenessEvidence: explicitComplete === true ? 'HEADER' : 'TOTAL_COUNT' };
}

function assessMetadata(universe, nowMs = Date.now()) {
  const valid = [];
  const invalid = [];
  for (const item of universe.items) {
    const verdict = validateContractMetadata(item, nowMs);
    if (verdict.ok) valid.push(item); else invalid.push({ symbol: canonicalSymbol(item.symbol), code: verdict.code, missing: verdict.missing || [] });
  }
  const active = valid.filter((item) => Number(item.lastTradingAt) > nowMs && (item.expiryAt == null || Number(item.expiryAt) > nowMs));
  return { valid, invalid, active };
}

async function loadQuoteValidated(symbol, nowMs = Date.now()) {
  if (!endpointConfigured(QUOTE_TEMPLATE, true)) throw providerError('TRADEWIZE_QUOTE_URL_MISSING', 'TRADEWIZE_QUOTE_URL_TEMPLATE not configured', 503);
  const normalized = canonicalSymbol(symbol);
  const url = QUOTE_TEMPLATE.replace('{symbol}', encodeURIComponent(normalized));
  const { body } = await providerGet(url, `quote:${normalized}`);
  const verdict = validateQuote(body, normalized, nowMs);
  if (!verdict.ok) throw providerError(verdict.code, 'Provider quote failed validation', 502);
  return { body, verdict };
}

async function loadHistoryValidated(symbol, range = '1y', interval = '1d', minimumBars = VIOP_LIMITS.minHistoryBars, nowMs = Date.now()) {
  if (!endpointConfigured(HISTORY_TEMPLATE, true)) throw providerError('TRADEWIZE_HISTORY_URL_MISSING', 'TRADEWIZE_HISTORY_URL_TEMPLATE not configured', 503);
  const normalized = canonicalSymbol(symbol);
  const url = new URL(HISTORY_TEMPLATE.replace('{symbol}', encodeURIComponent(normalized)));
  url.searchParams.set('range', range); url.searchParams.set('interval', interval);
  const { body } = await providerGet(url.toString(), `history:${normalized}:${range}:${interval}`);
  const verdict = validateHistory(body, normalized, interval, nowMs, minimumBars);
  if (!verdict.ok) throw providerError(verdict.code, 'Provider history failed validation', 502);
  return { body, verdict };
}

function snapshotFromProbe() {
  const v = lastProbe;
  const viopReady = v.ready === true;
  return {
    ok: true,
    version: '6.1.1-sidecar-hardened',
    sourceRevision: SOURCE_REVISION,
    providerReady: viopReady,
    multiMarketReady: false,
    coreMarketsReady: false,
    globalProviderReady: false,
    primaryConfiguredProvider: 'TradeWize',
    tradeWize: { state: authCache.state, authenticated: v.authenticated, adapterVerified: viopReady, lastAuthAt, error: authCache.error, reasonCode: v.reasonCode },
    features: {
      viopContractsReady: v.contractsReady && v.metadataReady,
      shortLivedSessionAuth: false, dynamicScanner: v.scannerReady, realtimeScannerRest: false,
      liveMarketWebSocket: false, realtimeScannerWebSocket: false, attestationReady: false,
      tradingViewSignals: false, researchFoundation: false, allTimeHistory: false,
      barHistoryCache: false, ingressRateLimit: true, fullBistFiveMinuteSla: false, bistSnapshotBatch: false,
    },
    markets: {
      VIOP: {
        ready: viopReady, supported: true, discovery: v.contractsReady, marketData: v.quoteReady,
        historicalData: v.historyReady, realtime: v.quoteReady && v.freshnessReady,
        realtimeReady: v.quoteReady && v.freshnessReady, analysisReady: v.scannerReady,
        symbolCount: lastProbeStats.contractCount, provider: 'TradeWize', reasonCode: v.reasonCode,
        message: viopReady ? 'VİOP provider E2E verified' : 'VİOP provider not ready',
        contractsReady: v.contractsReady, metadataReady: v.metadataReady, activeFutureReady: v.activeFutureReady,
        quoteReady: v.quoteReady, historyReady: v.historyReady, liquidityReady: v.liquidityReady,
        freshnessReady: v.freshnessReady, scannerReady: v.scannerReady,
      },
    },
    probe: { lastProbeAt, ...lastProbeStats },
  };
}

async function runViopPreflight(forceAuth = true) {
  lastProbeAt = Date.now();
  lastProbeStats = { contractCount: 0, validMetadataCount: 0, activeFutureCount: 0, historyBars: 0, quoteAgeMs: null, openInterestAvailable: false };
  const configReady = endpointConfigured(UNIVERSE_URL) && endpointConfigured(QUOTE_TEMPLATE, true) && endpointConfigured(HISTORY_TEMPLATE, true);
  if (!TW_KEY) {
    lastProbe = buildViopReadiness({ authState: 'NOT_CONFIGURED', reasonCode: 'TRADEWIZE_API_KEY_MISSING' });
    return snapshotFromProbe();
  }
  if (!configReady) {
    lastProbe = buildViopReadiness({ authState: authCache.state, reasonCode: 'TRADEWIZE_ENDPOINTS_NOT_CONFIGURED' });
    return snapshotFromProbe();
  }
  const auth = await ensureTradeWizeAuth(forceAuth);
  if (auth.state !== 'CONNECTED') {
    lastProbe = buildViopReadiness({ authState: auth.state, reasonCode: auth.reasonCode || 'TRADEWIZE_AUTH_FAILED' });
    return snapshotFromProbe();
  }

  const gates = { authState: 'CONNECTED' };
  let universe;
  try {
    universe = await loadUniverseComplete();
    gates.contractsReady = true;
    lastProbeStats.contractCount = universe.items.length;
  } catch (e) {
    lastProbe = buildViopReadiness({ ...gates, reasonCode: e.code || 'VIOP_CONTRACTS_NOT_READY' });
    return snapshotFromProbe();
  }

  const metadata = assessMetadata(universe, Date.now());
  lastProbeStats.validMetadataCount = metadata.valid.length;
  lastProbeStats.activeFutureCount = metadata.active.length;
  gates.metadataReady = metadata.valid.length > 0;
  gates.activeFutureReady = metadata.active.length > 0;
  if (!gates.metadataReady) {
    lastProbe = buildViopReadiness({ ...gates, reasonCode: metadata.invalid[0]?.code || 'VIOP_METADATA_NOT_READY' });
    return snapshotFromProbe();
  }
  if (!gates.activeFutureReady) {
    lastProbe = buildViopReadiness({ ...gates, reasonCode: 'VIOP_ACTIVE_FUTURE_NOT_READY' });
    return snapshotFromProbe();
  }

  const contract = metadata.active[0];
  let quote;
  try {
    quote = await loadQuoteValidated(contract.symbol, Date.now());
    gates.quoteReady = true; gates.freshnessReady = true;
    lastProbeStats.quoteAgeMs = quote.verdict.ageMs;
  } catch (e) {
    lastProbe = buildViopReadiness({ ...gates, reasonCode: e.code || 'VIOP_QUOTE_NOT_READY' });
    return snapshotFromProbe();
  }

  let history;
  try {
    history = await loadHistoryValidated(contract.symbol, '1y', '1d', VIOP_LIMITS.minHistoryBars, Date.now());
    gates.historyReady = true;
    lastProbeStats.historyBars = history.verdict.count;
  } catch (e) {
    lastProbe = buildViopReadiness({ ...gates, reasonCode: e.code || 'VIOP_HISTORY_NOT_READY' });
    return snapshotFromProbe();
  }

  const liquidity = resolveLiquidity({ quote: quote.body, contract, candles: history.body.candles });
  gates.liquidityReady = liquidity.ok;
  lastProbeStats.openInterestAvailable = liquidity.openInterestAvailable;
  if (!liquidity.ok) {
    lastProbe = buildViopReadiness({ ...gates, reasonCode: liquidity.code || 'VIOP_LIQUIDITY_NOT_READY' });
    return snapshotFromProbe();
  }
  gates.scannerReady = gates.contractsReady && gates.metadataReady && gates.activeFutureReady && gates.quoteReady && gates.historyReady && gates.freshnessReady && gates.liquidityReady && history.verdict.count >= VIOP_LIMITS.minHistoryBars;
  lastProbe = buildViopReadiness(gates);
  return snapshotFromProbe();
}

const server = Bun.serve({
  port: PORT,
  async fetch(req) {
    const u = new URL(req.url);
    if (u.pathname === '/health' || u.pathname === '/v1/health') return json({ ok: true, service: 'v611-viop-sidecar', version: '6.1.1', sourceRevision: SOURCE_REVISION });
    if (!authOk(req)) return json({ ok: false, code: 'AUTH_FAILED' }, 401);

    if (u.pathname === '/v1/provider/capabilities') {
      const snapshot = await runViopPreflight(false);
      return json(snapshot, 200);
    }
    if (u.pathname === '/v1/preflight') {
      const snapshot = await runViopPreflight(true);
      return json({ ok: snapshot.markets.VIOP.ready, sourceRevision: SOURCE_REVISION, tradeWize: snapshot.tradeWize, markets: snapshot.markets, probe: snapshot.probe }, snapshot.markets.VIOP.ready ? 200 : 503);
    }
    if (u.pathname === '/v1/viop/contracts') {
      try {
        const universe = await loadUniverseComplete();
        const metadata = assessMetadata(universe, Date.now());
        return json({
          items: universe.items,
          totalCount: universe.totalCount,
          hasMore: false,
          nextCursor: null,
          ...(universe.universeAsOf != null ? { universeAsOf: universe.universeAsOf } : {}),
          completenessEvidence: universe.completenessEvidence,
          validMetadataCount: metadata.valid.length,
          activeFutureCount: metadata.active.length,
        });
      } catch (e) { return json(safeError(e), e.status || 502); }
    }
    const qm = u.pathname.match(/^\/v1\/viop\/quote\/([^/]+)$/);
    if (qm) {
      try {
        const symbol = canonicalSymbol(decodeURIComponent(qm[1]));
        const { body } = await loadQuoteValidated(symbol, Date.now());
        return json(body);
      } catch (e) { return json(safeError(e), e.status || 502); }
    }
    const hm = u.pathname.match(/^\/v1\/viop\/history\/([^/]+)$/);
    if (hm) {
      try {
        const symbol = canonicalSymbol(decodeURIComponent(hm[1]));
        const range = u.searchParams.get('range') || '1y';
        const interval = u.searchParams.get('interval') || '1d';
        const { body } = await loadHistoryValidated(symbol, range, interval, 0, Date.now());
        return json(body);
      } catch (e) { return json(safeError(e), e.status || 502); }
    }
    return json({ ok: false, code: 'NOT_FOUND' }, 404);
  },
});

console.log(`V611_VIOP_SIDECAR_LISTENING port=${server.port}`);
console.log(`V611_SOURCE_REVISION=${SOURCE_REVISION}`);
console.log(`V611_TRADEWIZE_KEY_PRESENT=${TW_KEY ? 'YES' : 'NO'}`);
console.log(`V611_ENDPOINTS_CONFIGURED=${endpointConfigured(UNIVERSE_URL) && endpointConfigured(QUOTE_TEMPLATE, true) && endpointConfigured(HISTORY_TEMPLATE, true) ? 'YES' : 'NO'}`);
