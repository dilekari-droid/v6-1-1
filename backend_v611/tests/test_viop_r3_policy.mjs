import assert from 'node:assert/strict';
import {canonicalContract,metadataVerdict,activeFutureVerdict,splitReadiness} from '../viop_r3_policy.mjs';

const now=Date.parse('2026-09-26T19:30:00Z');
const base={
  symbol:'F_XU0301026',underlying:'XU030',expiry:'2026-10',expiryAt:'2026-10-30T15:00:00Z',
  lastTradingAt:'2026-10-30T15:00:00Z',tickSize:0.25,multiplier:10,currency:'TRY',contractType:'FUTURE'
};

const live=canonicalContract(base);
assert.equal(metadataVerdict(live).ok,true);
assert.equal(activeFutureVerdict(live,now).ok,true);

const expired=canonicalContract({...base,expiry:'2026-08',expiryAt:'2026-08-30T15:00:00Z',lastTradingAt:'2026-08-30T15:00:00Z'});
assert.equal(metadataVerdict(expired).ok,true,'expired contract can still have valid metadata');
assert.equal(activeFutureVerdict(expired,now).ok,false,'expired contract must not be active');
assert.equal(activeFutureVerdict(expired,now).code,'VIOP_CONTRACT_EXPIRED');

const missingTick=canonicalContract({...base,tickSize:null});
assert.equal(metadataVerdict(missingTick).ok,false);
assert.equal(metadataVerdict(missingTick).missing.includes('tickSize'),true);

const onlyExpired=splitReadiness([expired],now);
assert.equal(onlyExpired.contractsReady,true);
assert.equal(onlyExpired.metadataReady,true);
assert.equal(onlyExpired.activeFutureReady,false);

const mixed=splitReadiness([expired,live],now);
assert.equal(mixed.metadataReady,true);
assert.equal(mixed.activeFutureReady,true);

console.log('VIOP_R3_METADATA_ACTIVE_FUTURE_SEPARATION=PASS');
