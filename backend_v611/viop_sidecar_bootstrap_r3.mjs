const TOKEN_URL = String(process.env.TRADEWIZE_TOKEN_URL || '').trim();
const BASE_URL = String(process.env.TRADEWIZE_BASE_URL || '').trim().replace(/\/$/, '');

function httpsUrl(value) {
  if (!value) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'https:';
  } catch {
    return false;
  }
}

const tokenConfigured = httpsUrl(TOKEN_URL);
const originalFetch = globalThis.fetch.bind(globalThis);

/*
 * viop_sidecar_r2 historically builds `${TRADEWIZE_BASE_URL}/oauth/token`.
 * Production must never contact that inferred endpoint unless an explicit
 * TRADEWIZE_TOKEN_URL has been configured. Intercept only that OAuth request,
 * route it to the explicit HTTPS URL when present, and otherwise return a
 * synthetic fail-closed response without making any provider network call.
 */
globalThis.fetch = async function guardedFetch(input, init = undefined) {
  const rawUrl = typeof input === 'string'
    ? input
    : input instanceof URL
      ? input.toString()
      : String(input?.url || '');
  const method = String(init?.method || input?.method || 'GET').toUpperCase();
  const inferredTokenUrl = BASE_URL ? `${BASE_URL}/oauth/token` : '';

  if (method === 'POST' && inferredTokenUrl && rawUrl === inferredTokenUrl) {
    if (!tokenConfigured) {
      return new Response(
        JSON.stringify({
          ok: false,
          code: 'TRADEWIZE_TOKEN_URL_NOT_CONFIGURED',
          message: 'Explicit TradeWize token endpoint is not configured.',
        }),
        {
          status: 503,
          headers: {
            'content-type': 'application/json; charset=utf-8',
            'cache-control': 'no-store',
          },
        },
      );
    }
    return originalFetch(TOKEN_URL, init);
  }

  return originalFetch(input, init);
};

console.log(`V611_EXPLICIT_TOKEN_URL_CONFIGURED=${tokenConfigured ? 'YES' : 'NO'}`);
console.log('V611_OAUTH_ENDPOINT_GUARD=ENABLED');
console.log('V611_BOOTSTRAP_SECRETS_LOGGED=NO');

await import('./viop_sidecar_r2.mjs');
