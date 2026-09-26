import assert from 'node:assert/strict';
import { buildViopReadiness, validateContractMetadata, validateQuote, validateHistory, resolveLiquidity } from '../viop_runtime_policy.mjs';

const now = Date.UTC(2026, 8, 26, 12, 0, 0);

{
  const r = buildViopReadiness({ authState: 'CONNECTED' });
  assert.equal(r.ready, false); assert.equal(r.reasonCode, 'VIOP_CONTRACTS_NOT_READY');
}
{
  const r = buildViopReadiness({ authState: 'CONNECTED', contractsReady: true, metadataReady: false });
  assert.equal(r.ready, false); assert.equal(r.reasonCode, 'VIOP_METADATA_NOT_READY');
}
const contract = {
  symbol: 'F_XU0301226', underlying: 'XU030', expiry: '2026-12', contractType: 'FUTURE',
  lastTradingAt: now + 10 * 24 * 60 * 60 * 1000, expiryAt: now + 11 * 24 * 60 * 60 * 1000,
  tickSize: 0.25, multiplier: 10, currency: 'TRY', dataTimestamp: now - 5_000,
  realtime: true, currentSessionIncluded: true, delaySeconds: 0, volume: 100, openInterest: 25,
};
assert.equal(validateContractMetadata(contract, now).ok, true);
assert.equal(validateContractMetadata({ ...contract, tickSize: null }, now).code, 'VIOP_CONTRACT_METADATA_UNAVAILABLE');

const quote = { symbol: contract.symbol, price: 123.4, exchangeTimestamp: now - 5_000, realtime: true, currentSessionIncluded: true, delaySeconds: 0, volume: 55, openInterest: 12 };
assert.equal(validateQuote(quote, contract.symbol, now).ok, true);
assert.equal(validateQuote({ ...quote, exchangeTimestamp: now - 61_000 }, contract.symbol, now).code, 'VIOP_QUOTE_STALE');

const candles = Array.from({ length: 220 }, (_, i) => ({ timestamp: now - (221 - i) * 24 * 60 * 60 * 1000, open: 100, high: 110, low: 90, close: 105, volume: 10 }));
const history = { symbol: contract.symbol, interval: '1d', lastBarClosed: true, exchangeTimestamp: now - 60_000, candles };
assert.equal(validateHistory(history, contract.symbol, '1d', now).ok, true);
assert.equal(validateHistory({ ...history, candles: candles.slice(0, 219) }, contract.symbol, '1d', now).code, 'INSUFFICIENT_HISTORY');
assert.equal(resolveLiquidity({ quote, contract, candles }).ok, true);
assert.equal(resolveLiquidity({ quote: { ...quote, volume: 0 }, contract: { ...contract, volume: 0, openInterest: 0 }, candles: [{ ...candles.at(-1), volume: 0 }] }).code, 'LOW_LIQUIDITY');

{
  const r = buildViopReadiness({ authState: 'CONNECTED', contractsReady: true, metadataReady: true, activeFutureReady: true, quoteReady: true, historyReady: true, freshnessReady: true, liquidityReady: true, scannerReady: true });
  assert.equal(r.ready, true); assert.equal(r.reasonCode, null);
}
console.log('VIOP_RUNTIME_POLICY_TESTS=PASS');
