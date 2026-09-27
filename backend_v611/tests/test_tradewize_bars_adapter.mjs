import assert from 'node:assert/strict';
import {
  parseTradeWizeTime,
  providerIntervalFor,
  buildTradeWizeBarsUrl,
  normalizeTradeWizeBarsPage,
  mergeTradeWizeBarsPages,
} from '../tradewize_bars_adapter.mjs';

const now = Date.parse('2026-09-27T09:00:00Z');
assert.equal(parseTradeWizeTime('2026-09-04T07:00:00Z'), Date.parse('2026-09-04T07:00:00Z'));
assert.equal(providerIntervalFor('1d'), '1D');
assert.throws(() => providerIntervalFor('1m'), /doğrulanmadı/);

const url = new URL(buildTradeWizeBarsUrl({
  baseUrl: 'https://api.tradewize.com.tr/api/v1/market-data/bars',
  symbol: 'F_ASELS0926',
  requestedInterval: '1d',
  countBack: 220,
  cursor: 'abc',
}));
assert.equal(url.pathname, '/api/v1/market-data/bars');
assert.equal(url.searchParams.get('symbol'), 'F_ASELS0926');
assert.equal(url.searchParams.get('interval'), '1D');
assert.equal(url.searchParams.get('countBack'), '220');
assert.equal(url.searchParams.get('includeOpenBar'), 'false');
assert.equal(url.searchParams.get('cursor'), 'abc');

const page = normalizeTradeWizeBarsPage({
  symbol: 'F_ASELS0926',
  bars: [
    { timeUtc:'2026-09-04T07:00:00Z', closeTimeUtc:'2026-09-04T07:05:00Z', open:380, high:381, low:379.75, close:380.25 },
    { timeUtc:'2026-09-05T07:00:00Z', closeTimeUtc:'2026-09-05T07:05:00Z', open:381, high:382, low:380.25, close:381.50 },
  ],
  noData:false,
  hasMore:false,
  nextCursor:null,
  isPartial:false,
}, 'F_ASELS0926', '1d', now);
assert.equal(page.candles.length, 2);
assert.equal(page.providerInterval, '1D');
assert.equal(page.volumeComplete, false);
assert.equal(page.candles[0].volume, 0);
assert.equal(page.candles[0].volumeAvailable, false);

const volumePage = normalizeTradeWizeBarsPage({
  symbol: 'F_ASELS0926',
  bars: [
    { timeUtc:'2026-09-06T07:00:00Z', closeTimeUtc:'2026-09-06T07:05:00Z', open:382, high:383, low:381.25, close:382.5, volume:1234 },
  ],
  noData:false,
  hasMore:false,
  nextCursor:null,
  isPartial:false,
}, 'F_ASELS0926', '1d', now);
assert.equal(volumePage.volumeComplete, true);
assert.equal(volumePage.candles[0].volume, 1234);

assert.throws(() => normalizeTradeWizeBarsPage({
  symbol:'F_ASELS0926',
  bars:[{ timeUtc:'2026-09-30T07:00:00Z', closeTimeUtc:'2026-09-30T07:05:00Z', open:1, high:2, low:1, close:1.5 }],
  noData:false, hasMore:false, isPartial:false,
}, 'F_ASELS0926', '1d', now), /gelecekte/);

const synthetic220 = normalizeTradeWizeBarsPage({
  symbol:'F_TEST0926',
  bars:Array.from({length:220}, (_,i) => {
    const t = Date.parse('2025-12-01T07:00:00Z') + i * 24 * 60 * 60 * 1000;
    return { timeUtc:new Date(t).toISOString(), closeTimeUtc:new Date(t+5*60*1000).toISOString(), open:100+i, high:101+i, low:99+i, close:100.5+i, volume:1000+i };
  }),
  noData:false, hasMore:false, nextCursor:null, isPartial:false,
}, 'F_TEST0926', '1d', now);
const merged = mergeTradeWizeBarsPages([synthetic220], 220);
assert.equal(merged.candles.length, 220);
assert.equal(merged.lastBarClosed, true);
assert.equal(merged.volumeAvailable, true);
assert.ok(merged.exchangeTimestamp > 0);

console.log('TRADEWIZE_BARS_ADAPTER=PASS');
console.log('TRADEWIZE_1D_COUNTBACK_220=PASS');
console.log('TRADEWIZE_ISO_TIME_PARSE=PASS');
console.log('TRADEWIZE_CLOSED_BAR_SEMANTICS=PASS');
console.log('TRADEWIZE_VOLUME_NOT_FABRICATED=PASS');
