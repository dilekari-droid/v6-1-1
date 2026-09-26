const required = {
  TRADEWIZE_API_KEY: String(process.env.TRADEWIZE_API_KEY || '').trim(),
  TRADEWIZE_BASE_URL: String(process.env.TRADEWIZE_BASE_URL || '').trim(),
  TRADEWIZE_TOKEN_URL: String(process.env.TRADEWIZE_TOKEN_URL || '').trim(),
  TRADEWIZE_UNIVERSE_URL: String(process.env.TRADEWIZE_UNIVERSE_URL || '').trim(),
  TRADEWIZE_QUOTE_URL_TEMPLATE: String(process.env.TRADEWIZE_QUOTE_URL_TEMPLATE || '').trim(),
  TRADEWIZE_HISTORY_URL_TEMPLATE: String(process.env.TRADEWIZE_HISTORY_URL_TEMPLATE || '').trim(),
};

function httpsUrl(value, { symbolTemplate = false } = {}) {
  if (!value) return false;
  try {
    const probe = symbolTemplate ? value.replace('{symbol}', 'TEST') : value;
    const url = new URL(probe);
    return url.protocol === 'https:' && (!symbolTemplate || value.includes('{symbol}'));
  } catch {
    return false;
  }
}

function fail(code, detail) {
  console.error(`V611_BOOTSTRAP_FAIL code=${code} detail=${detail}`);
  process.exit(78);
}

const missing = [];
if (!required.TRADEWIZE_API_KEY) missing.push('TRADEWIZE_API_KEY');
for (const key of ['TRADEWIZE_BASE_URL', 'TRADEWIZE_TOKEN_URL', 'TRADEWIZE_UNIVERSE_URL']) {
  if (!httpsUrl(required[key])) missing.push(key);
}
for (const key of ['TRADEWIZE_QUOTE_URL_TEMPLATE', 'TRADEWIZE_HISTORY_URL_TEMPLATE']) {
  if (!httpsUrl(required[key], { symbolTemplate: true })) missing.push(key);
}

if (missing.length) {
  fail('TRADEWIZE_CONFIG_INCOMPLETE', `missing_or_invalid=${missing.join(',')}`);
}

const base = required.TRADEWIZE_BASE_URL.replace(/\/$/, '');
const token = required.TRADEWIZE_TOKEN_URL.replace(/\/$/, '');
const expectedToken = `${base}/oauth/token`;
if (token !== expectedToken) {
  fail('TRADEWIZE_TOKEN_URL_BASE_MISMATCH', 'explicit token URL does not match configured base + /oauth/token');
}

console.log('V611_BOOTSTRAP_CONFIG_VALID=YES');
console.log('V611_BOOTSTRAP_ENDPOINTS_EXPLICIT=YES');
console.log('V611_BOOTSTRAP_SECRETS_LOGGED=NO');

await import('./viop_sidecar_r2.mjs');
