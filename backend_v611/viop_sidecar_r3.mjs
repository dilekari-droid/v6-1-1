import {canonicalSymbol,canonicalContract,splitReadiness,toMs,positive} from './viop_r3_policy.mjs';

const PORT=Number(process.env.PORT||8080);
const APP_KEY=(process.env.BORSA_API_KEY||process.env.APP_API_KEY||'').trim();
const TW_KEY=(process.env.TRADEWIZE_API_KEY||'').trim();
const OAUTH_URL=(process.env.TRADEWIZE_OAUTH_URL||'').trim();
const UNIVERSE_URL=(process.env.TRADEWIZE_UNIVERSE_URL||'').trim();
const QUOTE_TEMPLATE=(process.env.TRADEWIZE_QUOTE_URL_TEMPLATE||'').trim();
const HISTORY_TEMPLATE=(process.env.TRADEWIZE_HISTORY_URL_TEMPLATE||'').trim();
const SOURCE_REVISION=(process.env.V611_SOURCE_REVISION||'v611-viop-r3').trim();
const TEST_ALLOW_HTTP=process.env.V611_TEST_ALLOW_HTTP==='1';
const MIN_PROVIDER_INTERVAL_MS=Math.max(1000,Number(process.env.TRADEWIZE_MIN_INTERVAL_MS||1000));
const MAX_DATA_AGE_MS=60_000;
const MAX_FUTURE_CLOCK_SKEW_MS=5_000;
const MAX_DECLARED_DELAY_SECONDS=5;

let authCache={token:'',expiresAt:0,state:TW_KEY&&endpointConfigured(OAUTH_URL)?'CONFIGURED_UNVERIFIED':'NOT_CONFIGURED',error:null,reasonCode:!TW_KEY?'TRADEWIZE_API_KEY_MISSING':(endpointConfigured(OAUTH_URL)?null:'TRADEWIZE_OAUTH_URL_MISSING')};
let lastAuthAt=0;
let nextDispatchAt=0;
let cooldownUntil=0;
let dispatchTail=Promise.resolve();
const inflight=new Map();
let lastProbe=blankProbe();

function json(body,status=200,headers={}){return new Response(JSON.stringify(body),{status,headers:{'content-type':'application/json; charset=utf-8','cache-control':'no-store',...headers}});}
function authOk(req){if(!APP_KEY)return false;const k=(req.headers.get('x-api-key')||'').trim();const a=(req.headers.get('authorization')||'').trim();return k===APP_KEY||a===`Bearer ${APP_KEY}`;}
function endpointConfigured(v,needsSymbol=false){if(!v)return false;try{const u=new URL(needsSymbol?v.replace('{symbol}','X'):v);const httpsOk=u.protocol==='https:';const localTestOk=TEST_ALLOW_HTTP&&u.protocol==='http:'&&['127.0.0.1','localhost'].includes(u.hostname);return(httpsOk||localTestOk)&&(!needsSymbol||v.includes('{symbol}'));}catch{return false;}}
function authConfigState(){const missing=[];if(!TW_KEY)missing.push('TRADEWIZE_API_KEY');if(!endpointConfigured(OAUTH_URL))missing.push('TRADEWIZE_OAUTH_URL');return{ready:missing.length===0,missing};}
function dataConfigState(){const missing=[];if(!endpointConfigured(UNIVERSE_URL))missing.push('TRADEWIZE_UNIVERSE_URL');if(!endpointConfigured(QUOTE_TEMPLATE,true))missing.push('TRADEWIZE_QUOTE_URL_TEMPLATE');if(!endpointConfigured(HISTORY_TEMPLATE,true))missing.push('TRADEWIZE_HISTORY_URL_TEMPLATE');return{ready:missing.length===0,missing};}
function errorWith(code,message,status=502){return Object.assign(new Error(message),{code,status});}
function blankProbe(){return{contractsReady:false,metadataReady:false,activeFutureReady:false,quoteReady:false,historyReady:false,freshnessReady:false,liquidityReady:false,scannerReady:false,symbolCount:0,sampleSymbol:null,reasonCode:null,message:null,lastProbeAt:0};}
function parseRetryAfter(v){if(v==null)return null;const raw=String(v).trim();if(!raw)return null;const seconds=Number(raw);if(Number.isFinite(seconds)&&seconds>=0)return seconds*1000;const d=Date.parse(raw);return Number.isFinite(d)?Math.max(0,d-Date.now()):null;}
async function acquireQuota(){let release;const prev=dispatchTail;dispatchTail=new Promise(r=>{release=r;});await prev;try{const wait=Math.max(0,nextDispatchAt-Date.now(),cooldownUntil-Date.now());if(wait>0)await new Promise(r=>setTimeout(r,wait));nextDispatchAt=Date.now()+MIN_PROVIDER_INTERVAL_MS;}finally{release();}}
async function singleflight(key,fn){if(inflight.has(key))return inflight.get(key);const p=(async()=>{try{return await fn();}finally{inflight.delete(key);}})();inflight.set(key,p);return p;}

async function ensureTradeWizeAuth(force=false){
  const cfg=authConfigState();
  if(!cfg.ready){const reasonCode=!TW_KEY?'TRADEWIZE_API_KEY_MISSING':'TRADEWIZE_OAUTH_URL_MISSING';authCache={token:'',expiresAt:0,state:'NOT_CONFIGURED',error:`missing=${cfg.missing.join(',')}`,reasonCode};return authCache;}
  const now=Date.now();
  if(!force&&authCache.token&&authCache.expiresAt>now+30_000)return authCache;
  return singleflight('oauth',async()=>{
    const again=Date.now();if(!force&&authCache.token&&authCache.expiresAt>again+30_000)return authCache;
    lastAuthAt=again;await acquireQuota();
    try{
      const r=await fetch(OAUTH_URL,{method:'POST',headers:{'X-API-Key':TW_KEY,'Accept':'application/json','Content-Type':'application/json'},body:JSON.stringify({grant_type:'api_key'}),signal:AbortSignal.timeout(15_000)});
      const text=await r.text();let body={};try{body=text?JSON.parse(text):{};}catch{}
      if(r.status===429){const retry=parseRetryAfter(r.headers.get('retry-after'))??MIN_PROVIDER_INTERVAL_MS;cooldownUntil=Math.max(cooldownUntil,Date.now()+Math.max(MIN_PROVIDER_INTERVAL_MS,retry));authCache={token:'',expiresAt:0,state:'AUTH_FAILED',error:'HTTP 429',reasonCode:'TRADEWIZE_RATE_LIMITED'};return authCache;}
      if(!r.ok){const code=r.status===401?'TRADEWIZE_AUTH_401':r.status===403?'TRADEWIZE_PERMISSION_403':'TRADEWIZE_AUTH_HTTP_ERROR';authCache={token:'',expiresAt:0,state:'AUTH_FAILED',error:`HTTP ${r.status}`,reasonCode:code};return authCache;}
      const token=String(body.access_token||'').trim();if(!token){authCache={token:'',expiresAt:0,state:'AUTH_FAILED',error:'access_token missing',reasonCode:'TRADEWIZE_ACCESS_TOKEN_MISSING'};return authCache;}
      const ttl=Number(body.expires_in||300);authCache={token,expiresAt:Date.now()+Math.max(60,Number.isFinite(ttl)?ttl:300)*1000,state:'CONNECTED',error:null,reasonCode:null};return authCache;
    }catch(e){authCache={token:'',expiresAt:0,state:'AUTH_FAILED',error:e?.name||'AUTH_ERROR',reasonCode:'TRADEWIZE_AUTH_NETWORK_ERROR'};return authCache;}
  });
}

async function providerGet(url,key=url){return singleflight(`get:${key}`,async()=>{const auth=await ensureTradeWizeAuth(false);if(auth.state!=='CONNECTED')throw errorWith(auth.reasonCode||'TRADEWIZE_AUTH_FAILED','TradeWize authentication unavailable',503);await acquireQuota();const r=await fetch(url,{headers:{Authorization:`Bearer ${auth.token}`,Accept:'application/json','User-Agent':'BorsaTakip-V611-VIOP-Sidecar/3'},signal:AbortSignal.timeout(20_000)});const text=await r.text();let body={};try{body=text?JSON.parse(text):{};}catch{throw errorWith('TRADEWIZE_INVALID_RESPONSE','Provider non-JSON response',502);}if(r.status===429){const retry=parseRetryAfter(r.headers.get('retry-after'))??MIN_PROVIDER_INTERVAL_MS;cooldownUntil=Math.max(cooldownUntil,Date.now()+Math.max(MIN_PROVIDER_INTERVAL_MS,retry));throw errorWith('TRADEWIZE_RATE_LIMITED','Provider rate limited',429);}if(r.status===401||r.status===403){authCache={token:'',expiresAt:0,state:'AUTH_FAILED',error:`HTTP ${r.status}`,reasonCode:r.status===401?'TRADEWIZE_AUTH_401':'TRADEWIZE_PERMISSION_403'};throw errorWith(authCache.reasonCode,`Provider HTTP ${r.status}`,503);}if(!r.ok)throw errorWith('TRADEWIZE_HTTP_ERROR',`Provider HTTP ${r.status}`,502);return{body,headers:r.headers};});}

function selectionComplete(headers){const raw=headers.get('x-tradewise-selection-complete')??headers.get('x-tradewize-selection-complete');if(raw==null)return null;return['true','1','yes'].includes(String(raw).trim().toLowerCase());}
async function fetchFullUniverse(){if(!endpointConfigured(UNIVERSE_URL))throw errorWith('TRADEWIZE_UNIVERSE_URL_MISSING','Universe endpoint not configured',503);const all=[];const seen=new Set();let cursor=null,totalCount=null,universeAsOf=null,explicitComplete=null;for(let pageNo=0;pageNo<100;pageNo++){const url=new URL(UNIVERSE_URL);url.searchParams.set('all','true');url.searchParams.set('limit','100');if(cursor)url.searchParams.set('cursor',cursor);const {body,headers}=await providerGet(url.toString(),`universe:${cursor||'first'}`);if(!Array.isArray(body.items))throw errorWith('UNIVERSE_SCHEMA_INVALID','Provider items array missing',502);const headerComplete=selectionComplete(headers);if(headerComplete!==null)explicitComplete=headerComplete;all.push(...body.items.filter(x=>x&&typeof x==='object'));if(body.totalCount!=null){const n=Number(body.totalCount);if(!Number.isInteger(n)||n<0)throw errorWith('UNIVERSE_SCHEMA_INVALID','totalCount invalid',502);if(totalCount!=null&&totalCount!==n)throw errorWith('UNIVERSE_INCOMPLETE','totalCount changed',502);totalCount=n;}if(body.universeAsOf!=null){const ms=toMs(body.universeAsOf);if(!ms)throw errorWith('UNIVERSE_SCHEMA_INVALID','universeAsOf invalid',502);if(universeAsOf!=null&&universeAsOf!==ms)throw errorWith('UNIVERSE_INCOMPLETE','universeAsOf changed',502);universeAsOf=ms;}const hasMore=body.hasMore===true;const next=String(body.nextCursor||'').trim();if(hasMore){if(!next||seen.has(next))throw errorWith('UNIVERSE_INCOMPLETE','cursor missing/loop',502);seen.add(next);cursor=next;}else{cursor=null;}if(!cursor)break;}const unique=new Map();for(const item of all){const c=canonicalContract(item);if(c&&!unique.has(c.symbol))unique.set(c.symbol,c);}const items=[...unique.values()];if(!items.length)throw errorWith('VIOP_CONTRACTS_EMPTY','Provider contract universe empty',502);const countComplete=totalCount!=null&&totalCount===items.length;if(explicitComplete===false)throw errorWith('SELECTION_INCOMPLETE','Provider selection explicitly incomplete',502);if(explicitComplete!==true&&!countComplete)throw errorWith('SELECTION_INCOMPLETE','Universe completeness could not be proven',502);return{items,totalCount:totalCount??items.length,hasMore:false,nextCursor:null,...(universeAsOf?{universeAsOf}:{})};}

function validateQuote(body,symbol){const responseSymbol=canonicalSymbol(body?.symbol);const price=positive(body?.price);const ts=toMs(body?.exchangeTimestamp);const delay=Number(body?.delaySeconds);const now=Date.now();if(responseSymbol!==symbol||!price||!ts)throw errorWith('VIOP_QUOTE_SCHEMA_INVALID','Quote symbol/price/timestamp invalid',502);if(body?.realtime!==true||body?.currentSessionIncluded!==true)throw errorWith('STALE_DATA','Quote realtime/current-session not verified',502);if(!Number.isInteger(delay)||delay<0||delay>MAX_DECLARED_DELAY_SECONDS)throw errorWith('STALE_DATA','Quote delaySeconds invalid',502);const age=now-ts;if(age< -MAX_FUTURE_CLOCK_SKEW_MS||age>MAX_DATA_AGE_MS)throw errorWith('STALE_DATA',`Quote age=${age}ms`,502);return{...body,symbol:responseSymbol,price,exchangeTimestamp:ts};}
async function fetchQuote(symbol){if(!endpointConfigured(QUOTE_TEMPLATE,true))throw errorWith('TRADEWIZE_QUOTE_URL_MISSING','Quote endpoint not configured',503);const {body}=await providerGet(QUOTE_TEMPLATE.replace('{symbol}',encodeURIComponent(symbol)),`quote:${symbol}`);return validateQuote(body,symbol);}
function validateHistory(body,symbol,interval){if(canonicalSymbol(body?.symbol)!==symbol||String(body?.interval||'')!==interval||!Array.isArray(body?.candles))throw errorWith('VIOP_HISTORY_SCHEMA_INVALID','History schema invalid',502);if(body.lastBarClosed!==true)throw errorWith('VIOP_HISTORY_SCHEMA_INVALID','History lastBarClosed not true',502);const candles=[];let previous=0;for(const [i,x] of body.candles.entries()){const ts=toMs(x?.timestamp),o=positive(x?.open),h=positive(x?.high),l=positive(x?.low),c=positive(x?.close),v=Number(x?.volume);if(!ts||!o||!h||!l||!c||!Number.isFinite(v)||v<0||l>h||o<l||o>h||c<l||c>h||ts<=previous)throw errorWith('VIOP_HISTORY_SCHEMA_INVALID',`candles[${i}] invalid`,502);previous=ts;candles.push({timestamp:ts,open:o,high:h,low:l,close:c,volume:v});}if(candles.length<220)throw errorWith('INSUFFICIENT_HISTORY',`${candles.length} candles; minimum 220`,502);return{...body,symbol,interval,candles};}
async function fetchHistory(symbol,range='1y',interval='1d'){if(!endpointConfigured(HISTORY_TEMPLATE,true))throw errorWith('TRADEWIZE_HISTORY_URL_MISSING','History endpoint not configured',503);const url=new URL(HISTORY_TEMPLATE.replace('{symbol}',encodeURIComponent(symbol)));url.searchParams.set('range',range);url.searchParams.set('interval',interval);const {body}=await providerGet(url.toString(),`history:${symbol}:${range}:${interval}`);return validateHistory(body,symbol,interval);}

async function probeFull(){
  const state=blankProbe();state.lastProbeAt=Date.now();
  const auth=await ensureTradeWizeAuth(true);
  if(auth.state!=='CONNECTED'){state.reasonCode=auth.reasonCode||'TRADEWIZE_AUTH_FAILED';state.message='TradeWize authentication not ready';lastProbe=state;return state;}
  try{
    if(!endpointConfigured(UNIVERSE_URL))throw errorWith('TRADEWIZE_UNIVERSE_URL_MISSING','Universe endpoint not configured',503);
    const universe=await fetchFullUniverse();
    const split=splitReadiness(universe.items,Date.now());
    state.symbolCount=universe.items.length;
    state.contractsReady=split.contractsReady;
    state.metadataReady=split.metadataReady;
    state.activeFutureReady=split.activeFutureReady;
    state.sampleSymbol=split.active[0]?.symbol||null;
    if(!state.contractsReady)throw errorWith('VIOP_CONTRACTS_EMPTY','No contracts',502);
    if(!state.metadataReady)throw errorWith(split.firstMetadataFailure?.code||'VIOP_CONTRACT_METADATA_UNAVAILABLE',`missing=${(split.firstMetadataFailure?.missing||[]).join(',')}`,502);
    if(!state.activeFutureReady)throw errorWith('NO_ACTIVE_FUTURE','No active FUTURE contract',502);
    if(!endpointConfigured(QUOTE_TEMPLATE,true))throw errorWith('TRADEWIZE_QUOTE_URL_MISSING','Quote endpoint not configured',503);
    const quote=await fetchQuote(state.sampleSymbol);
    state.quoteReady=true;
    state.freshnessReady=true;
    if(!endpointConfigured(HISTORY_TEMPLATE,true))throw errorWith('TRADEWIZE_HISTORY_URL_MISSING','History endpoint not configured',503);
    const history=await fetchHistory(state.sampleSymbol,'1y','1d');
    state.historyReady=true;
    const volume=Number(quote.volume),oi=Number(quote.openInterest);
    const historyVolumeReady=Array.isArray(history.candles)&&history.candles.some(c=>Number(c.volume)>0);
    state.liquidityReady=(Number.isFinite(volume)&&volume>0)||(Number.isFinite(oi)&&oi>0)||historyVolumeReady;
    if(!state.liquidityReady)throw errorWith('VIOP_LIQUIDITY_UNVERIFIED','volume/openInterest/history volume not verified',502);
    state.scannerReady=state.contractsReady&&state.metadataReady&&state.activeFutureReady&&state.quoteReady&&state.historyReady&&state.freshnessReady&&state.liquidityReady;
    if(!state.scannerReady)throw errorWith('VIOP_SCANNER_PREREQUISITES_NOT_READY','Scanner prerequisites not ready',502);
    state.reasonCode=null;state.message='VİOP provider E2E verified';lastProbe=state;return state;
  }catch(e){state.reasonCode=e.code||'VIOP_PROVIDER_ERROR';state.message=e.message||'Provider error';lastProbe=state;return state;}
}

function capabilitySnapshot(state=lastProbe){const authReady=authCache.state==='CONNECTED';const ready=authReady&&state.contractsReady&&state.metadataReady&&state.activeFutureReady&&state.quoteReady&&state.historyReady&&state.freshnessReady&&state.liquidityReady&&state.scannerReady;return{ok:true,version:'6.1.1-sidecar-r3',sourceRevision:SOURCE_REVISION,providerReady:ready,tradeWize:{state:authCache.state,authenticated:authReady,adapterVerified:ready,lastAuthAt,error:authCache.error,reasonCode:authCache.reasonCode,authConfigReady:authConfigState().ready,dataConfigReady:dataConfigState().ready,missingDataConfig:dataConfigState().missing},features:{viopContractsReady:state.contractsReady&&state.metadataReady&&state.activeFutureReady},markets:{VIOP:{ready,supported:true,discovery:state.contractsReady,marketData:state.quoteReady,historicalData:state.historyReady,realtime:state.quoteReady&&state.freshnessReady,realtimeReady:state.quoteReady&&state.freshnessReady,analysisReady:state.scannerReady,symbolCount:state.symbolCount,provider:'TradeWize',reasonCode:ready?null:(state.reasonCode||authCache.reasonCode||'VIOP_NOT_READY'),message:ready?'VİOP provider E2E ready':(state.message||'VİOP provider not ready'),contractsReady:state.contractsReady,metadataReady:state.metadataReady,activeFutureReady:state.activeFutureReady,quoteReady:state.quoteReady,historyReady:state.historyReady,liquidityReady:state.liquidityReady,freshnessReady:state.freshnessReady,scannerReady:state.scannerReady,sampleSymbol:state.sampleSymbol,lastProbeAt:state.lastProbeAt}}};}
function providerError(e){return json({ok:false,code:e.code||'VIOP_PROVIDER_ERROR',message:e.message||'Provider error'},e.status||502);}

const server=Bun.serve({port:PORT,async fetch(req){const u=new URL(req.url);if(u.pathname==='/health'||u.pathname==='/v1/health')return json({ok:true,service:'v611-viop-sidecar',version:'6.1.1-sidecar-r3',sourceRevision:SOURCE_REVISION,authConfig:authConfigState().ready?'CONFIGURED':'NOT_CONFIGURED',dataConfig:dataConfigState().ready?'CONFIGURED':'NOT_CONFIGURED'});if(!authOk(req))return json({ok:false,code:'AUTH_FAILED'},401);if(u.pathname==='/v1/provider/capabilities')return json(capabilitySnapshot(lastProbe));if(u.pathname==='/v1/preflight'){const state=await probeFull();const c=capabilitySnapshot(state);return json({ok:c.markets.VIOP.ready,sourceRevision:SOURCE_REVISION,tradeWize:c.tradeWize,markets:c.markets},c.markets.VIOP.ready?200:503);}if(u.pathname==='/v1/viop/contracts'){try{const universe=await fetchFullUniverse();const split=splitReadiness(universe.items,Date.now());if(!split.metadataReady)return json({ok:false,code:split.firstMetadataFailure?.code||'VIOP_CONTRACT_METADATA_UNAVAILABLE',message:`missing=${(split.firstMetadataFailure?.missing||[]).join(',')}`},502);return json(universe);}catch(e){return providerError(e);}}const qm=u.pathname.match(/^\/v1\/viop\/quote\/([^/]+)$/);if(qm){try{return json(await fetchQuote(canonicalSymbol(decodeURIComponent(qm[1]))));}catch(e){return providerError(e);}}const hm=u.pathname.match(/^\/v1\/viop\/history\/([^/]+)$/);if(hm){try{return json(await fetchHistory(canonicalSymbol(decodeURIComponent(hm[1])),u.searchParams.get('range')||'1y',u.searchParams.get('interval')||'1d'));}catch(e){return providerError(e);}}return json({ok:false,code:'NOT_FOUND'},404);}});
console.log(`V611_VIOP_SIDECAR_LISTENING port=${server.port}`);
console.log(`V611_SOURCE_REVISION=${SOURCE_REVISION}`);
console.log(`V611_TRADEWIZE_KEY_PRESENT=${TW_KEY?'YES':'NO'}`);
console.log(`V611_AUTH_CONFIGURED=${authConfigState().ready?'YES':'NO'}`);
console.log(`V611_DATA_ENDPOINTS_CONFIGURED=${dataConfigState().ready?'YES':'NO'}`);
