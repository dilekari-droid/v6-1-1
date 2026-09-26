import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';

function json(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(data);
}

function listen(server, port) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', resolve);
  });
}

const legacy = http.createServer((req, res) => {
  if (req.url === '/v1/health') return json(res, 200, { ok: true, version: 'legacy-test', sourceRevision: 'legacy-rev' });
  if (req.url === '/v1/provider/capabilities') return json(res, 200, { ok: true, version: 'legacy-test', markets: { BIST: { ready: true, analysisReady: true, symbolCount: 630 } }, features: { bistSnapshotBatch: true } });
  if (req.url === '/v1/preflight') return json(res, 200, { ok: true, authentication: { ok: true }, markets: { BIST: { ready: true } }, analysisMode: 'REAL' });
  if (req.url === '/v1/bist/symbols') return json(res, 200, { items: ['THYAO'] });
  return json(res, 404, { ok: false, code: 'LEGACY_NOT_FOUND' });
});

const sidecar = http.createServer((req, res) => {
  if (req.url === '/v1/health') return json(res, 200, { ok: true, version: '6.1.1', sourceRevision: 'sidecar-rev' });
  if (req.url === '/v1/provider/capabilities') return json(res, 200, {
    ok: true,
    sourceRevision: 'sidecar-rev',
    tradeWize: { state: 'NOT_CONFIGURED', authenticated: false, adapterVerified: false },
    features: { viopContractsReady: false },
    markets: { VIOP: { ready: false, supported: true, reasonCode: 'TRADEWIZE_API_KEY_MISSING', contractsReady: false, metadataReady: false, activeFutureReady: false, quoteReady: false, historyReady: false, freshnessReady: false, liquidityReady: false, scannerReady: false } },
  });
  if (req.url === '/v1/preflight') return json(res, 503, {
    ok: false,
    sourceRevision: 'sidecar-rev',
    tradeWize: { state: 'NOT_CONFIGURED', authenticated: false, adapterVerified: false },
    markets: { VIOP: { ready: false, supported: true, reasonCode: 'TRADEWIZE_API_KEY_MISSING', contractsReady: false, metadataReady: false, activeFutureReady: false, quoteReady: false, historyReady: false, freshnessReady: false, liquidityReady: false, scannerReady: false } },
  });
  if (req.url.startsWith('/v1/viop/contracts')) return json(res, 503, { ok: false, code: 'TRADEWIZE_API_KEY_MISSING' });
  return json(res, 404, { ok: false, code: 'SIDECAR_NOT_FOUND' });
});

await listen(legacy, 19081);
await listen(sidecar, 19082);

const child = spawn(process.execPath, ['backend_v611/production_gateway.mjs'], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    PORT: '19080',
    LEGACY_BACKEND_URL: 'http://127.0.0.1:19081',
    VIOP_SIDECAR_URL: 'http://127.0.0.1:19082',
    V611_GATEWAY_REVISION: 'gateway-test-rev',
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});

let logs = '';
child.stdout.on('data', (d) => { logs += d.toString(); });
child.stderr.on('data', (d) => { logs += d.toString(); });

async function get(path) {
  const r = await fetch(`http://127.0.0.1:19080${path}`, { headers: { 'x-api-key': 'test-app-key' } });
  let body = {};
  try { body = await r.json(); } catch {}
  return { status: r.status, body };
}

try {
  let healthy = false;
  for (let i = 0; i < 40; i += 1) {
    try {
      const h = await get('/v1/health');
      if (h.status === 200) { healthy = true; break; }
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.equal(healthy, true, `gateway did not start: ${logs}`);

  const health = await get('/v1/health');
  assert.equal(health.status, 200);
  assert.equal(health.body.sourceRevision, 'gateway-test-rev');
  assert.equal(health.body.components.viopSidecar.sourceRevision, 'sidecar-rev');
  assert.equal(health.body.gateway.viopSidecarReachable, true);

  const caps = await get('/v1/provider/capabilities');
  assert.equal(caps.status, 200);
  assert.equal(caps.body.markets.BIST.ready, true);
  assert.equal(caps.body.markets.VIOP.ready, false);
  assert.equal(caps.body.markets.VIOP.reasonCode, 'TRADEWIZE_API_KEY_MISSING');
  assert.equal(caps.body.providerReady, false);
  assert.equal(caps.body.components.viopSidecar.sourceRevision, 'sidecar-rev');

  const preflight = await get('/v1/preflight');
  assert.equal(preflight.status, 200);
  assert.equal(preflight.body.ok, true);
  assert.equal(preflight.body.markets.BIST.ready, true);
  assert.equal(preflight.body.markets.VIOP.ready, false);
  assert.equal(preflight.body.viop.ready, false);
  assert.equal(preflight.body.viop.status, 503);
  assert.equal(preflight.body.viop.reasonCode, 'TRADEWIZE_API_KEY_MISSING');

  const viop = await get('/v1/viop/contracts');
  assert.equal(viop.status, 503);
  assert.equal(viop.body.code, 'TRADEWIZE_API_KEY_MISSING');

  const bist = await get('/v1/bist/symbols');
  assert.equal(bist.status, 200);
  assert.deepEqual(bist.body.items, ['THYAO']);

  assert.match(logs, /V611_PRODUCTION_GATEWAY_LISTENING/);
  assert.match(logs, /V611_GATEWAY_VIOP_SIDECAR_CONFIGURED=YES/);
  console.log('V611_PRODUCTION_GATEWAY_TESTS=PASS');
} finally {
  child.kill('SIGTERM');
  legacy.close();
  sidecar.close();
}
