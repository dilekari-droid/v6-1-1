export const VIOP_LIMITS = Object.freeze({
  maxQuoteAgeMs: 60_000,
  maxQuoteFutureSkewMs: 15_000,
  maxDeclaredDelaySeconds: 5,
  maxHistoryFutureSkewMs: 30_000,
  maxDailyHistoryAgeMs: 4 * 24 * 60 * 60 * 1000,
  minHistoryBars: 220,
  minVolume: 1.0,
});

export function canonicalSymbol(value) {
  return String(value ?? '').trim().toUpperCase();
}

export function endpointConfigured(value, needsSymbol = false) {
  if (!value) return false;
  try {
    const candidate = needsSymbol ? String(value).replace('{symbol}', 'X') : String(value);
    const url = new URL(candidate);
    return url.protocol === 'https:' && (!needsSymbol || String(value).includes('{symbol}'));
  } catch {
    return false;
  }
}

function finitePositive(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function finiteNonNegative(value) {
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

function positiveTimestamp(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function validYearMonth(value, nowMs) {
  const text = String(value ?? '').trim();
  const match = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(text);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const now = new Date(nowMs);
  const current = now.getUTCFullYear() * 12 + now.getUTCMonth() + 1;
  return year * 12 + month >= current;
}

export function validateContractMetadata(raw, nowMs = Date.now()) {
  if (!raw || typeof raw !== 'object') return { ok: false, code: 'VIOP_CONTRACT_SCHEMA_INVALID', missing: ['object'] };
  const required = ['symbol', 'underlying', 'expiry', 'lastTradingAt', 'tickSize', 'multiplier', 'currency'];
  const missing = required.filter((key) => raw[key] === undefined || raw[key] === null || String(raw[key]).trim() === '');
  if (missing.length) return { ok: false, code: 'VIOP_CONTRACT_METADATA_UNAVAILABLE', missing };

  const symbol = canonicalSymbol(raw.symbol);
  const underlying = canonicalSymbol(raw.underlying);
  const contractType = String(raw.contractType ?? '').trim().toUpperCase();
  if (!symbol || !underlying) return { ok: false, code: 'VIOP_CONTRACT_METADATA_UNAVAILABLE', missing: ['symbol/underlying'] };
  if (contractType !== 'FUTURE') return { ok: false, code: 'VIOP_NOT_ACTIVE_FUTURE', missing: ['contractType=FUTURE'] };
  if (!validYearMonth(raw.expiry, nowMs)) return { ok: false, code: 'VIOP_NOT_ACTIVE_FUTURE', missing: ['expiry'] };

  const lastTradingAt = positiveTimestamp(raw.lastTradingAt);
  if (!lastTradingAt || lastTradingAt <= nowMs) return { ok: false, code: 'VIOP_NOT_ACTIVE_FUTURE', missing: ['lastTradingAt'] };
  const expiryAt = raw.expiryAt == null ? null : positiveTimestamp(raw.expiryAt);
  if (raw.expiryAt != null && (!expiryAt || expiryAt <= nowMs)) return { ok: false, code: 'VIOP_NOT_ACTIVE_FUTURE', missing: ['expiryAt'] };
  if (!finitePositive(raw.tickSize)) return { ok: false, code: 'VIOP_CONTRACT_METADATA_UNAVAILABLE', missing: ['tickSize'] };
  if (!finitePositive(raw.multiplier)) return { ok: false, code: 'VIOP_CONTRACT_METADATA_UNAVAILABLE', missing: ['multiplier'] };

  const dataTimestamp = positiveTimestamp(raw.dataTimestamp);
  const delay = Number(raw.delaySeconds);
  const dataAge = dataTimestamp ? nowMs - dataTimestamp : Number.POSITIVE_INFINITY;
  const realtimeValid = raw.realtime === true && raw.currentSessionIncluded === true && Number.isInteger(delay) && delay >= 0 && delay <= VIOP_LIMITS.maxDeclaredDelaySeconds;
  const timestampValid = !!dataTimestamp && dataAge >= -VIOP_LIMITS.maxQuoteFutureSkewMs && dataAge <= VIOP_LIMITS.maxQuoteAgeMs;
  if (!realtimeValid || !timestampValid) return { ok: false, code: 'VIOP_CONTRACT_NOT_REALTIME', missing: ['dataTimestamp/realtime/currentSessionIncluded/delaySeconds'] };

  return { ok: true, code: null, symbol, lastTradingAt, expiryAt };
}

export function validateQuote(raw, requestedSymbol, nowMs = Date.now()) {
  if (!raw || typeof raw !== 'object') return { ok: false, code: 'VIOP_QUOTE_SCHEMA_INVALID' };
  const expected = canonicalSymbol(requestedSymbol);
  const actual = canonicalSymbol(raw.symbol);
  const price = finitePositive(raw.price);
  const exchangeTimestamp = positiveTimestamp(raw.exchangeTimestamp);
  const delay = Number(raw.delaySeconds);
  if (!actual || actual !== expected || !price || !exchangeTimestamp) return { ok: false, code: 'VIOP_QUOTE_SCHEMA_INVALID' };
  if (raw.realtime !== true || raw.currentSessionIncluded !== true || !Number.isInteger(delay) || delay < 0 || delay > VIOP_LIMITS.maxDeclaredDelaySeconds) {
    return { ok: false, code: 'VIOP_QUOTE_NOT_REALTIME' };
  }
  const ageMs = nowMs - exchangeTimestamp;
  if (ageMs < -VIOP_LIMITS.maxQuoteFutureSkewMs || ageMs > VIOP_LIMITS.maxQuoteAgeMs) return { ok: false, code: 'VIOP_QUOTE_STALE', ageMs };
  return { ok: true, code: null, symbol: actual, price, exchangeTimestamp, ageMs: Math.max(0, ageMs) };
}

export function validateHistory(raw, requestedSymbol, requestedInterval = '1d', nowMs = Date.now(), minimumBars = VIOP_LIMITS.minHistoryBars) {
  if (!raw || typeof raw !== 'object') return { ok: false, code: 'VIOP_HISTORY_SCHEMA_INVALID' };
  const expected = canonicalSymbol(requestedSymbol);
  const actual = canonicalSymbol(raw.symbol);
  const interval = String(raw.interval ?? '').trim().toLowerCase();
  const exchangeTimestamp = positiveTimestamp(raw.exchangeTimestamp ?? raw.lastExchangeTimestamp);
  if (!actual || actual !== expected || interval !== String(requestedInterval).toLowerCase() || raw.lastBarClosed !== true || !exchangeTimestamp || !Array.isArray(raw.candles)) {
    return { ok: false, code: 'VIOP_HISTORY_SCHEMA_INVALID' };
  }
  const exchangeAge = nowMs - exchangeTimestamp;
  if (exchangeAge < -VIOP_LIMITS.maxHistoryFutureSkewMs) return { ok: false, code: 'VIOP_HISTORY_FUTURE_TIMESTAMP' };
  if (interval === '1d' && exchangeAge > VIOP_LIMITS.maxDailyHistoryAgeMs) return { ok: false, code: 'VIOP_HISTORY_STALE', ageMs: exchangeAge };
  if (raw.candles.length < minimumBars) return { ok: false, code: 'INSUFFICIENT_HISTORY', count: raw.candles.length, minimumBars };

  let previous = -1;
  const seen = new Set();
  for (let i = 0; i < raw.candles.length; i += 1) {
    const c = raw.candles[i];
    if (!c || typeof c !== 'object') return { ok: false, code: 'VIOP_HISTORY_CANDLE_INVALID', index: i };
    const ts = positiveTimestamp(c.timestamp);
    const open = finitePositive(c.open); const high = finitePositive(c.high); const low = finitePositive(c.low); const close = finitePositive(c.close); const volume = finiteNonNegative(c.volume);
    if (!ts || !open || !high || !low || !close || volume == null || seen.has(ts) || ts <= previous || ts > nowMs + VIOP_LIMITS.maxHistoryFutureSkewMs || low > high || open < low || open > high || close < low || close > high) {
      return { ok: false, code: 'VIOP_HISTORY_CANDLE_INVALID', index: i };
    }
    seen.add(ts); previous = ts;
  }
  const frameMs = interval === '1d' ? 24 * 60 * 60 * 1000 : null;
  const last = raw.candles[raw.candles.length - 1];
  if (frameMs && nowMs < Number(last.timestamp) + frameMs) return { ok: false, code: 'VIOP_HISTORY_LAST_BAR_OPEN' };
  return { ok: true, code: null, count: raw.candles.length, exchangeTimestamp };
}

export function resolveLiquidity({ quote, contract, candles }) {
  const candidates = [quote?.volume, contract?.volume, Array.isArray(candles) && candles.length ? candles[candles.length - 1]?.volume : null]
    .map(finiteNonNegative)
    .filter((value) => value != null && value >= VIOP_LIMITS.minVolume);
  if (!candidates.length) return { ok: false, code: 'LOW_LIQUIDITY', volume: null, openInterest: null, openInterestAvailable: false };
  const oiCandidates = [quote?.openInterest, contract?.openInterest]
    .map((value) => Number(value))
    .filter((value) => Number.isInteger(value) && value >= 1);
  return { ok: true, code: null, volume: candidates[0], openInterest: oiCandidates[0] ?? null, openInterestAvailable: oiCandidates.length > 0 };
}

export function buildViopReadiness({ authState, contractsReady = false, metadataReady = false, activeFutureReady = false, quoteReady = false, historyReady = false, freshnessReady = false, liquidityReady = false, scannerReady = false, reasonCode = null }) {
  const authenticated = authState === 'CONNECTED';
  const gates = { contractsReady: !!contractsReady, metadataReady: !!metadataReady, activeFutureReady: !!activeFutureReady, quoteReady: !!quoteReady, historyReady: !!historyReady, freshnessReady: !!freshnessReady, liquidityReady: !!liquidityReady, scannerReady: !!scannerReady };
  const order = [
    ['contractsReady', 'VIOP_CONTRACTS_NOT_READY'],
    ['metadataReady', 'VIOP_METADATA_NOT_READY'],
    ['activeFutureReady', 'VIOP_ACTIVE_FUTURE_NOT_READY'],
    ['quoteReady', 'VIOP_QUOTE_NOT_READY'],
    ['historyReady', 'VIOP_HISTORY_NOT_READY'],
    ['freshnessReady', 'VIOP_FRESHNESS_NOT_READY'],
    ['liquidityReady', 'VIOP_LIQUIDITY_NOT_READY'],
    ['scannerReady', 'VIOP_SCANNER_NOT_READY'],
  ];
  let firstFailure = reasonCode;
  if (!authenticated && !firstFailure) firstFailure = authState || 'AUTH_NOT_READY';
  if (authenticated && !firstFailure) {
    const failed = order.find(([key]) => !gates[key]);
    firstFailure = failed?.[1] ?? null;
  }
  const ready = authenticated && Object.values(gates).every(Boolean);
  return { ready, authenticated, ...gates, reasonCode: ready ? null : firstFailure };
}
