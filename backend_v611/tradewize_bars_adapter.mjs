const MAX_FUTURE_SKEW_MS = 30_000;

export function parseTradeWizeTime(value) {
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
    return value < 10_000_000_000 ? Math.trunc(value * 1000) : Math.trunc(value);
  }
  if (typeof value === 'string' && value.trim()) {
    const numeric = Number(value);
    if (Number.isFinite(numeric) && numeric > 0) {
      return numeric < 10_000_000_000 ? Math.trunc(numeric * 1000) : Math.trunc(numeric);
    }
    const parsed = Date.parse(value);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
  }
  return 0;
}

export function providerIntervalFor(requestedInterval) {
  const key = String(requestedInterval || '').trim().toLowerCase();
  if (key === '1d') return '1D';
  throw Object.assign(new Error(`TradeWize interval mapping doğrulanmadı: ${requestedInterval}`), { code: 'UNSUPPORTED_PROVIDER_INTERVAL', status: 400 });
}

export function buildTradeWizeBarsUrl({ baseUrl, symbol, requestedInterval = '1d', countBack = 220, cursor = null }) {
  const url = new URL(String(baseUrl || '').trim());
  if (url.protocol !== 'https:') throw new Error('TradeWize bars endpoint HTTPS olmalıdır.');
  const canonicalSymbol = String(symbol || '').trim().toUpperCase();
  if (!canonicalSymbol.startsWith('F_')) throw new Error('TradeWize VİOP symbol geçersiz.');
  const n = Number(countBack);
  if (!Number.isInteger(n) || n < 1 || n > 5000) throw new Error('TradeWize countBack geçersiz.');
  url.searchParams.set('symbol', canonicalSymbol);
  url.searchParams.set('interval', providerIntervalFor(requestedInterval));
  url.searchParams.set('countBack', String(n));
  url.searchParams.set('includeOpenBar', 'false');
  if (cursor) url.searchParams.set('cursor', String(cursor));
  return url.toString();
}

function finitePositive(v) {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function optionalVolume(v) {
  if (v === null || v === undefined || v === '') return { value: 0, available: false };
  const n = Number(v);
  if (!Number.isFinite(n) || n < 0) throw new Error('TradeWize bar volume geçersiz.');
  return { value: n, available: true };
}

export function normalizeTradeWizeBarsPage(body, requestedSymbol, requestedInterval = '1d', nowMs = Date.now()) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error('TradeWize bars response nesne değil.');
  const symbol = String(body.symbol || '').trim().toUpperCase();
  const expected = String(requestedSymbol || '').trim().toUpperCase();
  if (!symbol || symbol !== expected) throw new Error(`TradeWize bars symbol uyuşmuyor: ${symbol || 'EMPTY'} != ${expected}`);
  if (body.isPartial === true) throw new Error('TradeWize bars response isPartial=true; eksik sayfa kabul edilmedi.');
  if (body.noData === true) {
    return { symbol: expected, interval: String(requestedInterval).toLowerCase(), candles: [], hasMore: false, nextCursor: null, exchangeTimestamp: 0, volumeComplete: false };
  }
  if (!Array.isArray(body.bars)) throw new Error('TradeWize bars[] alanı yok.');

  const candles = [];
  let previous = 0;
  let exchangeTimestamp = 0;
  let volumeComplete = true;
  for (let i = 0; i < body.bars.length; i += 1) {
    const bar = body.bars[i];
    if (!bar || typeof bar !== 'object' || Array.isArray(bar)) throw new Error(`TradeWize bars[${i}] nesne değil.`);
    const timestamp = parseTradeWizeTime(bar.timeUtc);
    const closeTime = parseTradeWizeTime(bar.closeTimeUtc);
    const open = finitePositive(bar.open);
    const high = finitePositive(bar.high);
    const low = finitePositive(bar.low);
    const close = finitePositive(bar.close);
    if (!timestamp || !closeTime || closeTime <= timestamp) throw new Error(`TradeWize bars[${i}] zaman bilgisi geçersiz.`);
    if (closeTime > nowMs + MAX_FUTURE_SKEW_MS) throw new Error(`TradeWize bars[${i}] kapanış zamanı gelecekte.`);
    if ([open, high, low, close].some(v => v === null)) throw new Error(`TradeWize bars[${i}] OHLC geçersiz.`);
    if (low > high || open < low || open > high || close < low || close > high) throw new Error(`TradeWize bars[${i}] OHLC aralığı tutarsız.`);
    if (timestamp <= previous) throw new Error(`TradeWize bars[] kronolojik artan sırada değil; index=${i}.`);
    previous = timestamp;
    const volume = optionalVolume(bar.volume);
    if (!volume.available) volumeComplete = false;
    candles.push({ timestamp, closeTime, open, high, low, close, volume: volume.value, volumeAvailable: volume.available });
    exchangeTimestamp = Math.max(exchangeTimestamp, closeTime);
  }

  const hasMore = body.hasMore === true;
  const nextCursor = body.nextCursor == null || String(body.nextCursor).trim() === '' ? null : String(body.nextCursor);
  if (hasMore && !nextCursor) throw new Error('TradeWize hasMore=true fakat nextCursor yok.');
  return {
    symbol: expected,
    interval: String(requestedInterval).toLowerCase(),
    providerInterval: providerIntervalFor(requestedInterval),
    candles,
    hasMore,
    nextCursor,
    exchangeTimestamp,
    volumeComplete,
  };
}

export function mergeTradeWizeBarsPages(pages, minimumBars = 220) {
  if (!Array.isArray(pages) || pages.length === 0) throw new Error('TradeWize bars sayfası yok.');
  const symbol = pages[0].symbol;
  const interval = pages[0].interval;
  const providerInterval = pages[0].providerInterval;
  const all = [];
  const seen = new Set();
  let exchangeTimestamp = 0;
  let volumeComplete = true;
  for (const page of pages) {
    if (page.symbol !== symbol || page.interval !== interval) throw new Error('TradeWize bars pagination kimliği değişti.');
    for (const candle of page.candles) {
      if (seen.has(candle.timestamp)) throw new Error(`TradeWize bars duplicate timestamp: ${candle.timestamp}`);
      if (all.length && candle.timestamp <= all[all.length - 1].timestamp) throw new Error('TradeWize bars pagination kronolojisi bozuk.');
      seen.add(candle.timestamp);
      all.push(candle);
    }
    exchangeTimestamp = Math.max(exchangeTimestamp, page.exchangeTimestamp || 0);
    volumeComplete = volumeComplete && page.volumeComplete;
  }
  if (all.length < minimumBars) throw Object.assign(new Error(`INSUFFICIENT_HISTORY: ${all.length} mum; minimum ${minimumBars}.`), { code: 'INSUFFICIENT_HISTORY', status: 409 });
  return {
    symbol,
    interval,
    providerInterval,
    lastBarClosed: true,
    exchangeTimestamp,
    volumeAvailable: volumeComplete,
    candles: all.map(({ closeTime, ...c }) => c),
  };
}
