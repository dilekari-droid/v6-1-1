const PORT = Number(process.env.PORT || 18090);
let oauthCalls = 0;
let universeCalls = 0;
let quoteCalls = 0;
let historyCalls = 0;

function json(body, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), {status, headers:{'content-type':'application/json', ...headers}});
}
const now = () => Date.now();
const server = Bun.serve({
  port: PORT,
  async fetch(req) {
    const u = new URL(req.url);
    if (u.pathname === '/oauth/token' && req.method === 'POST') {
      oauthCalls += 1;
      return json({access_token:'mock-access-token',expires_in:300});
    }
    if (u.pathname === '/stats') return json({oauthCalls, universeCalls, quoteCalls, historyCalls});
    if (u.pathname === '/universe') {
      universeCalls += 1;
      const t=now();
      return json({items:[{symbol:'F_XU0301126',underlying:'XU030',expiry:'2026-11',lastTradingAt:t+30*86400000,tickSize:0.025,multiplier:10,currency:'TRY',contractType:'FUTURE',source:'MockTradeWize'}],totalCount:1,hasMore:false,nextCursor:null,universeAsOf:t});
    }
    const q=u.pathname.match(/^\/quote\/([^/]+)$/);
    if(q){
      quoteCalls += 1;
      return json({symbol:decodeURIComponent(q[1]).toUpperCase(),price:12345.5,exchangeTimestamp:now(),realtime:true,currentSessionIncluded:true,delaySeconds:0,volume:1000,openInterest:500,source:'MockTradeWize'});
    }
    const h=u.pathname.match(/^\/history\/([^/]+)$/);
    if(h){
      historyCalls += 1;
      const symbol=decodeURIComponent(h[1]).toUpperCase(); const t=now();
      const candles=Array.from({length:220},(_,i)=>{const base=100+i;return{timestamp:t-(220-i)*86400000,open:base,high:base+2,low:base-2,close:base+1,volume:1000+i};});
      return json({symbol,interval:u.searchParams.get('interval')||'1d',candles,lastBarClosed:true,exchangeTimestamp:t,source:'MockTradeWize'});
    }
    return json({ok:false,code:'NOT_FOUND'},404);
  }
});
console.log(`MOCK_TRADEWIZE_LISTENING port=${server.port}`);
