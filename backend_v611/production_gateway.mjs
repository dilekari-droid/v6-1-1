import http from 'node:http';

const PORT = Number(process.env.PORT || 8080);
const LEGACY_BACKEND_URL = String(process.env.LEGACY_BACKEND_URL || 'http://127.0.0.1:8081').replace(/\/$/, '');
const VIOP_SIDECAR_URL = String(process.env.VIOP_SIDECAR_URL || '').replace(/\/$/, '');
const SOURCE_REVISION = String(process.env.V611_GATEWAY_REVISION || 'v611-viop-sidecar-hardening').trim();

function validBaseUrl(value, allowEmpty = false) {
  if (!value) return allowEmpty;
  try {
    const u = new URL(value);
    return u.protocol === 'http:' || u.protocol === 'https:';
  } catch {
    return false;
  }
}

function send(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
  });
  res.end(data);
}

function forwardHeaders(req) {
  const headers = { accept: String(req.headers.accept || 'application/json') };
  if (req.headers.authorization) headers.authorization = String(req.headers.authorization);
  if (req.headers['x-api-key']) headers['x-api-key'] = String(req.headers['x-api-key']);
  if (req.headers['user-agent']) headers['user-agent'] = String(req.headers['user-agent']);
  if (req.headers['x-install-id']) headers['x-install-id'] = String(req.headers['x-install-id']);
  return headers;
}

async function fetchJson(base, req, path = req.url, timeoutMs = 30_000) {
  const r = await fetch(base + path, {
    method: req.method,
    headers: forwardHeaders(req),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await r.text();
  let body;
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    const e = new Error('UPSTREAM_NON_JSON');
    e.status = 502;
    throw e;
  }
  return { status: r.status, body, headers: r.headers };
}

async function proxyRaw(base, req, res) {
  try {
    const r = await fetch(base + req.url, {
      method: req.method,
      headers: forwardHeaders(req),
      signal: AbortSignal.timeout(30_000),
    });
    const buf = Buffer.from(await r.arrayBuffer());
    res.writeHead(r.status, {
      'content-type': r.headers.get('content-type') || 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
    });
    res.end(buf);
  } catch (e) {
    send(res, 502, { ok: false, code: 'UPSTREAM_UNAVAILABLE', message: String(e?.message || e) });
  }
}

function unavailableViop(reason = 'VIOP_SIDECAR_UNAVAILABLE') {
  return {
    ready: false,
    supported: true,
    discovery: false,
    marketData: false,
    historicalData: false,
    realtime: false,
    realtimeReady: false,
    analysisReady: false,
    symbolCount: 0,
    provider: 'TradeWize',
    reasonCode: reason,
    message: 'V6.1.1 VİOP sidecar kullanılamıyor.',
    contractsReady: false,
    metadataReady: false,
    activeFutureReady: false,
    quoteReady: false,
    historyReady: false,
    liquidityReady: false,
    freshnessReady: false,
    scannerReady: false,
  };
}

function sidecarConfigured() {
  return validBaseUrl(VIOP_SIDECAR_URL, false);
}

async function readSidecar(req, path) {
  if (!sidecarConfigured()) {
    return {
      configured: false,
      reachable: false,
      status: 503,
      body: {
        ok: false,
        code: 'VIOP_SIDECAR_NOT_CONFIGURED',
        markets: { VIOP: unavailableViop('VIOP_SIDECAR_NOT_CONFIGURED') },
      },
    };
  }
  try {
    const result = await fetchJson(VIOP_SIDECAR_URL, req, path);
    return { configured: true, reachable: true, ...result };
  } catch (e) {
    return {
      configured: true,
      reachable: false,
      status: 502,
      body: {
        ok: false,
        code: 'VIOP_SIDECAR_UNAVAILABLE',
        message: String(e?.message || e),
        markets: { VIOP: unavailableViop('VIOP_SIDECAR_UNAVAILABLE') },
      },
    };
  }
}

function sideViopFrom(result) {
  const body = result?.body && typeof result.body === 'object' ? result.body : {};
  return body?.markets?.VIOP || unavailableViop(body?.code || 'VIOP_SIDECAR_UNAVAILABLE');
}

function componentInfo(legacyBody, sideResult) {
  return {
    gateway: { sourceRevision: SOURCE_REVISION },
    legacy: { sourceRevision: legacyBody?.sourceRevision || legacyBody?.version || null },
    viopSidecar: {
      configured: Boolean(sideResult?.configured),
      reachable: Boolean(sideResult?.reachable),
      sourceRevision: sideResult?.body?.sourceRevision || null,
    },
  };
}

async function mergedHealth(req, res) {
  let legacy;
  try {
    legacy = await fetchJson(LEGACY_BACKEND_URL, req, '/v1/health');
  } catch (e) {
    return send(res, 502, { ok: false, code: 'LEGACY_HEALTH_UNAVAILABLE', sourceRevision: SOURCE_REVISION });
  }
  const side = await readSidecar(req, '/v1/health');
  const legacyBody = legacy.body && typeof legacy.body === 'object' ? legacy.body : {};
  return send(res, legacy.status, {
    ...legacyBody,
    sourceRevision: SOURCE_REVISION,
    gateway: {
      ready: legacy.status >= 200 && legacy.status < 300,
      viopSidecarConfigured: side.configured,
      viopSidecarReachable: side.reachable,
    },
    components: componentInfo(legacyBody, side),
  });
}

async function mergedCapabilities(req, res) {
  let legacy;
  try {
    legacy = await fetchJson(LEGACY_BACKEND_URL, req, '/v1/provider/capabilities');
  } catch (e) {
    return send(res, 502, { ok: false, code: 'LEGACY_CAPABILITY_UNAVAILABLE', sourceRevision: SOURCE_REVISION });
  }
  if (legacy.status < 200 || legacy.status >= 300) return send(res, legacy.status, legacy.body);

  const side = await readSidecar(req, '/v1/provider/capabilities');
  const root = legacy.body && typeof legacy.body === 'object' ? legacy.body : {};
  const sideBody = side.body && typeof side.body === 'object' ? side.body : {};
  const sideViop = sideViopFrom(side);
  const bistReady = root?.markets?.BIST?.ready === true;
  const viopReady = sideViop.ready === true;

  return send(res, 200, {
    ...root,
    ok: true,
    version: `${String(root.version || 'legacy')}+v611-gateway-r2`,
    sourceRevision: SOURCE_REVISION,
    components: componentInfo(root, side),
    gateway: {
      ready: true,
      viopSidecarConfigured: side.configured,
      viopSidecarReachable: side.reachable,
    },
    tradeWize: sideBody.tradeWize || root.tradeWize || { state: 'NOT_CONFIGURED', authenticated: false, adapterVerified: false },
    features: { ...(root.features || {}), ...(sideBody.features || {}) },
    scanPolicy: { ...(root.scanPolicy || {}), ...(sideBody.scanPolicy || {}) },
    markets: { ...(root.markets || {}), VIOP: sideViop },
    providerReady: bistReady && viopReady,
    coreMarketsReady: bistReady && viopReady,
    multiMarketReady: bistReady && viopReady,
    globalProviderReady: false,
  });
}

async function mergedPreflight(req, res) {
  let legacy;
  try {
    legacy = await fetchJson(LEGACY_BACKEND_URL, req, '/v1/preflight');
  } catch (e) {
    return send(res, 502, { ok: false, code: 'LEGACY_PREFLIGHT_UNAVAILABLE', sourceRevision: SOURCE_REVISION });
  }
  if (legacy.status < 200 || legacy.status >= 300) return send(res, legacy.status, legacy.body);

  const side = await readSidecar(req, '/v1/preflight');
  const root = legacy.body && typeof legacy.body === 'object' ? legacy.body : {};
  const sideBody = side.body && typeof side.body === 'object' ? side.body : {};
  const sideViop = sideViopFrom(side);

  // Preserve legacy/BIST success independently. VIOP may be fail-closed without turning BIST preflight into an HTTP failure.
  return send(res, 200, {
    ...root,
    ok: root.ok !== false,
    sourceRevision: SOURCE_REVISION,
    components: componentInfo(root, side),
    gateway: {
      ready: true,
      viopSidecarConfigured: side.configured,
      viopSidecarReachable: side.reachable,
    },
    tradeWize: sideBody.tradeWize || { state: 'NOT_CONFIGURED', authenticated: false, adapterVerified: false },
    markets: { ...(root.markets || {}), VIOP: sideViop },
    viop: {
      ready: sideViop.ready === true,
      status: side.status,
      reasonCode: sideViop.reasonCode || sideBody.code || null,
    },
  });
}

if (!validBaseUrl(LEGACY_BACKEND_URL, false)) {
  throw new Error('LEGACY_BACKEND_URL_INVALID');
}

const server = http.createServer((req, res) => {
  Promise.resolve().then(async () => {
    const url = new URL(req.url, 'http://localhost');
    if (req.method !== 'GET') return send(res, 405, { ok: false, code: 'METHOD_NOT_ALLOWED' });
    if (url.pathname === '/health' || url.pathname === '/v1/health') return mergedHealth(req, res);
    if (url.pathname === '/v1/provider/capabilities') return mergedCapabilities(req, res);
    if (url.pathname === '/v1/preflight') return mergedPreflight(req, res);
    if (url.pathname.startsWith('/v1/viop/')) {
      if (!sidecarConfigured()) {
        return send(res, 503, { ok: false, code: 'VIOP_SIDECAR_NOT_CONFIGURED', message: 'VİOP sidecar URL yapılandırılmadı.' });
      }
      return proxyRaw(VIOP_SIDECAR_URL, req, res);
    }
    return proxyRaw(LEGACY_BACKEND_URL, req, res);
  }).catch((e) => send(res, 500, { ok: false, code: 'GATEWAY_ERROR', message: String(e?.message || e) }));
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`V611_PRODUCTION_GATEWAY_LISTENING port=${PORT} revision=${SOURCE_REVISION}`);
  console.log(`V611_GATEWAY_VIOP_SIDECAR_CONFIGURED=${sidecarConfigured() ? 'YES' : 'NO'}`);
});
