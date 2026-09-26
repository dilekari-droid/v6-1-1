import http from 'node:http';

const PORT = Number(process.env.PORT || 8080);
const BASE = (process.env.TRADEWIZE_BASE_URL || 'https://api.tradewize.com.tr').replace(/\/$/, '');
const API_KEY = (process.env.TRADEWIZE_API_KEY || process.env.BORSA_API_KEY || '').trim();

async function probe() {
  console.log(`V611_TW_KEY_PRESENT=${API_KEY ? 'YES' : 'NO'}`);
  if (!API_KEY) return;
  try {
    const auth = await fetch(`${BASE}/oauth/token`, {
      method: 'POST',
      headers: {'X-API-Key': API_KEY, 'Content-Type': 'application/json', Accept: 'application/json'},
      body: JSON.stringify({grant_type: 'api_key'})
    });
    console.log(`V611_TW_AUTH_STATUS=${auth.status}`);
    if (!auth.ok) return;
    const authJson = await auth.json();
    const token = authJson?.access_token;
    console.log(`V611_TW_AUTH_TOKEN=${token ? 'PASS' : 'MISSING'}`);
    if (!token) return;
    const details = await fetch(`${BASE}/api/v1/market-data/viop/last-price/details?all=true`, {
      headers: {Authorization: `Bearer ${token}`, Accept: 'application/json'}
    });
    console.log(`V611_TW_DETAILS_STATUS=${details.status}`);
    if (!details.ok) return;
    const body = await details.json();
    const data = body && typeof body.data === 'object' && body.data ? body.data : body;
    const count = data && typeof data === 'object' ? Object.keys(data).length : 0;
    console.log(`V611_TW_RECORD_COUNT=${count}`);
    console.log('V611_TW_PROBE_PASS');
  } catch (e) {
    console.log(`V611_TW_PROBE_FAIL=${e?.name || 'Error'}`);
  }
}

probe();
http.createServer((req, res) => {
  if (req.url === '/v1/health') {
    res.writeHead(200, {'content-type': 'application/json'});
    res.end(JSON.stringify({ok:true, service:'v611-viop-runtime-probe'}));
    return;
  }
  res.writeHead(404);
  res.end();
}).listen(PORT, '0.0.0.0', () => console.log(`V611_TW_PROBE_LISTEN=${PORT}`));
