const PORT=Number(process.env.PORT||18190);
const calls=new Map();
const hit=(k)=>calls.set(k,(calls.get(k)||0)+1);
const json=(body,status=200,headers={})=>new Response(JSON.stringify(body),{status,headers:{'content-type':'application/json',...headers}});
const modeOf=(u)=>u.searchParams.get('mode')||'ok';
const now=()=>Date.now();

const server=Bun.serve({port:PORT,async fetch(req){
  const u=new URL(req.url); const mode=modeOf(u); hit(`${mode}:${u.pathname}`);
  if(u.pathname==='/stats') return json(Object.fromEntries(calls));
  if(u.pathname==='/oauth/token'&&req.method==='POST'){
    if(mode==='auth401') return json({error:'invalid'},401);
    if(mode==='auth403') return json({error:'forbidden'},403);
    if(mode==='auth429') return json({error:'rate_limited'},429,{'Retry-After':'1'});
    return json({access_token:'test-token',expires_in:300});
  }
  if(u.pathname==='/universe'){
    const t=now();
    const base={symbol:'F_XU0301126',underlying:'XU030',expiry:'2026-11',lastTradingAt:t+30*86400000,tickSize:0.025,multiplier:10,currency:'TRY',contractType:'FUTURE',source:'MockTradeWize'};
    if(mode==='metadata_missing') delete base.tickSize;
    if(mode==='expired') base.lastTradingAt=t-86400000;
    const headers={'X-TradeWise-Selection-Complete':mode==='selection_incomplete'?'false':'true'};
    return json({items:[base],totalCount:1,hasMore:false,nextCursor:null,universeAsOf:t},200,headers);
  }
  const qm=u.pathname.match(/^\/quote\/([^/]+)$/);
  if(qm){
    const t=now(); const symbol=decodeURIComponent(qm[1]).toUpperCase();
    const stale=mode==='stale_quote'; const noLiq=mode==='no_liquidity';
    return json({symbol,price:12345.5,exchangeTimestamp:stale?t-120000:t,realtime:true,currentSessionIncluded:true,delaySeconds:0,volume:noLiq?0:1000,openInterest:noLiq?0:500,source:'MockTradeWize'});
  }
  const hm=u.pathname.match(/^\/history\/([^/]+)$/);
  if(hm){
    const t=now(); const symbol=decodeURIComponent(hm[1]).toUpperCase(); const count=mode==='short_history'?219:220; const noLiq=mode==='no_liquidity';
    const candles=Array.from({length:count},(_,i)=>{const base=100+i;return{timestamp:t-(count-i)*86400000,open:base,high:base+2,low:base-2,close:base+1,volume:noLiq?0:1000+i};});
    if(mode==='duplicate_history'&&candles.length>10) candles[10].timestamp=candles[9].timestamp;
    return json({symbol,interval:u.searchParams.get('interval')||'1d',candles,lastBarClosed:true,exchangeTimestamp:t,source:'MockTradeWize'});
  }
  return json({ok:false,code:'NOT_FOUND'},404);
}});
console.log(`MOCK_TRADEWIZE_NEGATIVE_LISTENING port=${server.port}`);
