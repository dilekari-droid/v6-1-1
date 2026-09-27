import { createHash } from 'node:crypto';

const BASE_REF = 'a9094e80f73b8a369746b6ce77af28e62501e00b';
const BASE_SHA256 = '93a6ff24b53b42a3a4a2200a69c7e3daa2d3e5ff73de0c8d487d7717b9ef1a30';
const BASE_URL = `https://raw.githubusercontent.com/dilekari-droid/v6-1-1/${BASE_REF}/backend_v611/viop_sidecar_r4.mjs`;

const response = await fetch(BASE_URL);
if (!response.ok) throw new Error(`SIDECAR_BASE_FETCH_FAILED HTTP ${response.status}`);
const source = await response.text();
const actual = createHash('sha256').update(source).digest('hex');
if (actual !== BASE_SHA256) throw new Error(`SIDECAR_BASE_INTEGRITY_FAILED ${actual}`);

const oldText = "provider:'TradeWize',reasonCode,message:reasonCode?'VİOP provider not ready':'VİOP provider connected',contractsReady,metadataReady";
const newText = "provider:'TradeWize',reasonCode,message:reasonCode?'VİOP provider not ready':'VİOP provider connected',authReady,contractsReady,metadataReady";
const occurrences = source.split(oldText).length - 1;
if (occurrences !== 1) throw new Error(`SIDECAR_AUTH_READY_PATCH_INVARIANT_FAILED occurrences=${occurrences}`);
const patched = source.replace(oldText, newText);
const patchedSha = createHash('sha256').update(patched).digest('hex');
const expectedPatchedSha = '6c3f699bd56b1b7d1f7fe5ff7e3e6d36d92a78b41b2d7cc78dc5c5247cdd6e0c';
if (patchedSha !== expectedPatchedSha) throw new Error(`SIDECAR_PATCHED_INTEGRITY_FAILED ${patchedSha}`);

await Bun.write('/tmp/viop_sidecar_vc612.mjs', patched);
console.log(`V611_VC612_SIDECAR_BASE_SHA256=${actual}`);
console.log(`V611_VC612_SIDECAR_PATCHED_SHA256=${patchedSha}`);
await import('file:///tmp/viop_sidecar_vc612.mjs');
