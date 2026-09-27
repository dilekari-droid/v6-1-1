import http from 'node:http';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';

function serve(port,handler){return new Promise(resolve=>{const s=http.createServer(handler);s.listen(port,'127.0.0.1',()=>resolve(s));});}
function json(res,status,body){const data=JSON.stringify(body);res.writeHead(status,{'content-type':'application/json'});res.end(data);}
const legacy=await serve(19081,(req,res)=>{
  if(req.url==='/v1/preflight')return json(res,200,{ok:true,authentication:{ok:true},markets:{BIST:{ready:true}}});
  if(req.url==='/v1/provider/capabilities')return json(res,200,{ok:true,version:'legacy-test',markets:{BIST:{ready:true}},features:{}});
  return json(res,200,{ok:true,legacy:true});
});
const side=await serve(19082,(req,res)=>{
  if(req.url==='/v1/preflight')return json(res,503,{ok:false,sourceRevision:'side-r3-test',tradeWize:{state:'NOT_CONFIGURED',authenticated:false,adapterVerified:false},markets:{VIOP:{ready:false,contractsReady:false,metadataReady:false,activeFutureReady:false,quoteReady:false,historyReady:false,freshnessReady:false,liquidityReady:false,scannerReady:false,reasonCode:'TRADEWIZE_AUTH_CONFIG_MISSING'}}});
  if(req.url==='/v1/provider/capabilities')return json(res,200,{ok:true,sourceRevision:'side-r3-test',tradeWize:{state:'NOT_CONFIGURED',authenticated:false,adapterVerified:false},features:{viopContractsReady:false},markets:{VIOP:{ready:false,contractsReady:false,metadataReady:false,activeFutureReady:false,quoteReady:false,historyReady:false,freshnessReady:false,liquidityReady:false,scannerReady:false,reasonCode:'TRADEWIZE_AUTH_CONFIG_MISSING'}}});
  if(req.url.startsWith('/v1/viop/contracts'))return json(res,503,{ok:false,code:'TRADEWIZE_AUTH_CONFIG_MISSING'});
  return json(res,404,{ok:false,code:'NOT_FOUND'});
});
const child=spawn(process.execPath,['backend_v611/production_gateway_r3.mjs'],{env:{...process.env,PORT:'19080',LEGACY_BACKEND_URL:'http://127.0.0.1:19081',VIOP_SIDECAR_URL:'http://127.0.0.1:19082',V611_GATEWAY_REVISION:'gateway-r3-test'},stdio:['ignore','pipe','pipe']});
try{
  for(let i=0;i<40;i++){try{const r=await fetch('http://127.0.0.1:19080/v1/preflight');if(r.status)break;}catch{}await new Promise(r=>setTimeout(r,100));}
  const pf=await fetch('http://127.0.0.1:19080/v1/preflight');
  assert.equal(pf.status,200);
  const p=await pf.json();
  assert.equal(p.ok,true);
  assert.equal(p.bist.ready,true);
  assert.equal(p.viop.ready,false);
  assert.equal(p.viop.status,503);
  assert.equal(p.viop.reasonCode,'TRADEWIZE_AUTH_CONFIG_MISSING');
  assert.equal(p.markets.VIOP.ready,false);
  assert.equal(p.gateway.viopSourceRevision,'side-r3-test');

  const cap=await fetch('http://127.0.0.1:19080/v1/provider/capabilities');
  assert.equal(cap.status,200);
  const c=await cap.json();
  assert.equal(c.markets.BIST.ready,true);
  assert.equal(c.markets.VIOP.ready,false);
  assert.equal(c.features.viopContractsReady,false);

  const vc=await fetch('http://127.0.0.1:19080/v1/viop/contracts');
  assert.equal(vc.status,503);
  const v=await vc.json();
  assert.equal(v.code,'TRADEWIZE_AUTH_CONFIG_MISSING');
  console.log('VIOP_R3_GATEWAY_RUNTIME=PASS');
}finally{
  child.kill('SIGTERM');legacy.close();side.close();
}
