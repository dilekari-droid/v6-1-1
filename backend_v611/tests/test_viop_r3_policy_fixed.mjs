import assert from 'node:assert/strict';
import {canonicalContract, metadataVerdict, activeFutureVerdict, splitReadiness} from '../viop_r3_policy.mjs';

const now=Date.UTC(2026,8,26,19,50,0);
const base={
  symbol:'F_XU0301026', underlying:'XU030', expiry:'2026-10',
  lastTradingAt: now+86400000, tickSize:1, multiplier:10,
  currency:'TRY', contractType:'FUTURE'
};

{
  const c=canonicalContract({...base,lastTradingAt:now-1000});
  assert.equal(metadataVerdict(c).ok,true,'expired contract metadata must still be structurally valid');
  assert.equal(activeFutureVerdict(c,now).ok,false,'expired contract must not be active');
  assert.equal(activeFutureVerdict(c,now).code,'VIOP_CONTRACT_EXPIRED');
}
{
  const c=canonicalContract({...base,expiry:'2026-09',lastTradingAt:now+86400000});
  assert.equal(c.expiry,'2026-09');
  assert.equal(c.expiryAt,null,'YYYY-MM must not synthesize expiryAt');
  assert.equal(metadataVerdict(c).ok,true);
  assert.equal(activeFutureVerdict(c,now).ok,true,'month label alone must not prematurely expire contract');
}
{
  const c=canonicalContract({...base,tickSize:null});
  const m=metadataVerdict(c);
  assert.equal(m.ok,false);
  assert.equal(m.code,'VIOP_CONTRACT_METADATA_UNAVAILABLE');
  assert.ok(m.missing.includes('tickSize'));
}
{
  const items=[
    canonicalContract({...base,lastTradingAt:now-1000}),
    canonicalContract({...base,symbol:'F_XU0301126',expiry:'2026-11',lastTradingAt:now+86400000}),
  ];
  const r=splitReadiness(items,now);
  assert.equal(r.contractsReady,true);
  assert.equal(r.metadataReady,true);
  assert.equal(r.activeFutureReady,true);
  assert.equal(r.active.length,1);
}
console.log('VIOP_R3_POLICY_FIXED=PASS');
