import http from 'node:http';

const PORT = Number(process.env.PORT || 8080);
const LEGACY_BACKEND_URL = String(process.env.LEGACY_BACKEND_URL || 'http://127.0.0.1:8081').replace(/\/$/, '');
const VIOP_SIDECAR_URL = String(process.env.VIOP_SIDECAR_URL || '').replace(/\/$/, '');
const SOURCE_REVISION = String(process.env.V611_GATEWAY_REVISION || 'v611-viop-production-wiring');

function send(res,status,body){
  const data=JSON.stringify(body);
  res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff'});
  res.end(data);
}
function forwardHeaders(req){
  const h={accept:String(req.headers.accept||'application/json')};
  if(req.headers.authorization) h.authorization=String(req.headers.authorization);
  if(req.headers['x-api-key']) h['x-api-key']=String(req.headers['x-api-key']);
  if(req.headers['user-agent']) h['user-agent']=String(req.headers['user-agent']);
  return h;
}
async function fetchJson(base,req){
  const r=await fetch(base+req.url,{method:req.method,headers:forwardHeaders(req),signal:AbortSignal.timeout(30_000)});
  const text=await r.text();
  let body=null;
  try{ body=text?JSON.parse(text):{}; }catch{ throw Object.assign(new Error('UPSTREAM_NON_JSON'),{status:502}); }
  return {status:r.status,body};
}
async function proxyRaw(base,req,res){
  try{
    const r=await fetch(base+req.url,{method:req.method,headers:forwardHeaders(req),signal:AbortSignal.timeout(30_000)});
    const buf=Buffer.from(await r.arrayBuffer());
    res.writeHead(r.status,{'content-type':r.headers.get('content-type')||'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff'});
    res.end(buf);
  }catch(e){ send(res,502,{ok:false,code:'UPSTREAM_UNAVAILABLE',message:String(e?.message||e)}); }
}
function unavailableViop(reason='VIOP_SIDECAR_UNAVAILABLE'){
  return {ready:false,supported:true,discovery:false,marketData:false,historicalData:false,realtime:false,realtimeReady:false,analysisReady:false,symbolCount:0,provider:'TradeWize',reasonCode:reason,message:'V6.1.1 VİOP sidecar kullanılamıyor.',authReady:false,contractsReady:false,metadataReady:false,activeFutureReady:false,quoteReady:false,historyReady:false,liquidityReady:false,freshnessReady:false,scannerReady:false};
}
async function mergedCapabilities(req,res){
  let legacy;
  try{ legacy=await fetchJson(LEGACY_BACKEND_URL,req); }
  catch(e){ return send(res,502,{ok:false,code:'LEGACY_CAPABILITY_UNAVAILABLE',message:String(e?.message||e)}); }
  if(legacy.status<200||legacy.status>=300) return send(res,legacy.status,legacy.body);
  const root=legacy.body&&typeof legacy.body==='object'?legacy.body:{};
  let side=null;
  if(VIOP_SIDECAR_URL){ try{ side=await fetchJson(VIOP_SIDECAR_URL,req); }catch{} }
  const sideOk=side&&side.status>=200&&side.status<300&&side.body&&typeof side.body==='object';
  const sideBody=sideOk?side.body:{};
  const sideViop=sideBody?.markets?.VIOP || unavailableViop(side?.body?.code || 'VIOP_SIDECAR_UNAVAILABLE');
  const viopContractsReady=sideBody?.features?.viopContractsReady===true;
  const merged={
    ...root, ok:true,
    version:`${String(root.version||'legacy')}+v611-gateway-r1`, sourceRevision:SOURCE_REVISION,
    gateway:{ready:true,viopSidecarConfigured:Boolean(VIOP_SIDECAR_URL),viopSidecarReachable:Boolean(sideOk)},
    primaryConfiguredProvider: sideBody?.tradeWize?.configReady===true ? 'BIST legacy + TradeWize VİOP' : (root.primaryConfiguredProvider||'BIST legacy'),
    tradeWize: sideBody.tradeWize || root.tradeWize || {state:'NOT_CONFIGURED',authenticated:false,adapterVerified:false},
    features:{...(root.features||{}),...(sideBody.features||{}),viopContractsReady},
    scanPolicy:{...(root.scanPolicy||{}),...(sideBody.scanPolicy||{})},
    markets:{...(root.markets||{}),VIOP:sideViop},
    providerReady:Boolean(root?.markets?.BIST?.ready) && sideViop.ready===true,
    multiMarketReady:false,
    coreMarketsReady:Boolean(root?.markets?.BIST?.ready) && sideViop.ready===true,
    globalProviderReady:false
  };
  return send(res,200,merged);
}
const server=http.createServer((req,res)=>{
  Promise.resolve().then(async()=>{
    const url=new URL(req.url,'http://localhost');
    if(req.method!=='GET') return send(res,405,{ok:false,code:'METHOD_NOT_ALLOWED'});
    if(url.pathname==='/v1/provider/capabilities') return mergedCapabilities(req,res);
    if(url.pathname.startsWith('/v1/viop/')){
      if(!VIOP_SIDECAR_URL) return send(res,503,{ok:false,code:'VIOP_SIDECAR_NOT_CONFIGURED',message:'VİOP sidecar URL yapılandırılmadı.'});
      return proxyRaw(VIOP_SIDECAR_URL,req,res);
    }
    return proxyRaw(LEGACY_BACKEND_URL,req,res);
  }).catch(e=>send(res,500,{ok:false,code:'GATEWAY_ERROR',message:String(e?.message||e)}));
});
server.listen(PORT,'0.0.0.0',()=>console.log(`V611_PRODUCTION_GATEWAY_LISTENING port=${PORT} revision=${SOURCE_REVISION}`));
