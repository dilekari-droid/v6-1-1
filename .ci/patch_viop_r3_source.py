from pathlib import Path
import re

p=Path('backend_v611/viop_sidecar_r3.mjs')
s=p.read_text()

old="const TW_BASE=(process.env.TRADEWIZE_BASE_URL||'').trim().replace(/\\/$/,'');"
new="const OAUTH_URL=(process.env.TRADEWIZE_OAUTH_URL||'').trim();"
assert old in s, 'TW_BASE declaration not found'
s=s.replace(old,new,1)

old="const SOURCE_REVISION=(process.env.V611_SOURCE_REVISION||'v611-viop-r3').trim();"
new=old+"\nconst TEST_ALLOW_HTTP=process.env.V611_TEST_ALLOW_HTTP==='1';"
assert old in s
s=s.replace(old,new,1)

old="let authCache={token:'',expiresAt:0,state:TW_KEY&&TW_BASE?'CONFIGURED_UNVERIFIED':'NOT_CONFIGURED',error:null,reasonCode:TW_KEY&&TW_BASE?null:'TRADEWIZE_AUTH_CONFIG_MISSING'};"
new="let authCache={token:'',expiresAt:0,state:TW_KEY&&endpointConfigured(OAUTH_URL)?'CONFIGURED_UNVERIFIED':'NOT_CONFIGURED',error:null,reasonCode:!TW_KEY?'TRADEWIZE_API_KEY_MISSING':(endpointConfigured(OAUTH_URL)?null:'TRADEWIZE_OAUTH_URL_MISSING')};"
assert old in s
s=s.replace(old,new,1)

old="function endpointConfigured(v,needsSymbol=false){if(!v)return false;try{const u=new URL(needsSymbol?v.replace('{symbol}','X'):v);return u.protocol==='https:'&&(!needsSymbol||v.includes('{symbol}'));}catch{return false;}}"
new="function endpointConfigured(v,needsSymbol=false){if(!v)return false;try{const u=new URL(needsSymbol?v.replace('{symbol}','X'):v);const httpsOk=u.protocol==='https:';const localTestOk=TEST_ALLOW_HTTP&&u.protocol==='http:'&&['127.0.0.1','localhost'].includes(u.hostname);return(httpsOk||localTestOk)&&(!needsSymbol||v.includes('{symbol}'));}catch{return false;}}"
assert old in s
s=s.replace(old,new,1)

old="function authConfigState(){const missing=[];if(!TW_KEY)missing.push('TRADEWIZE_API_KEY');if(!endpointConfigured(TW_BASE))missing.push('TRADEWIZE_BASE_URL');return{ready:missing.length===0,missing};}"
new="function authConfigState(){const missing=[];if(!TW_KEY)missing.push('TRADEWIZE_API_KEY');if(!endpointConfigured(OAUTH_URL))missing.push('TRADEWIZE_OAUTH_URL');return{ready:missing.length===0,missing};}"
assert old in s
s=s.replace(old,new,1)

old="if(!cfg.ready){authCache={token:'',expiresAt:0,state:'NOT_CONFIGURED',error:`missing=${cfg.missing.join(',')}`,reasonCode:'TRADEWIZE_AUTH_CONFIG_MISSING'};return authCache;}"
new="if(!cfg.ready){const reasonCode=!TW_KEY?'TRADEWIZE_API_KEY_MISSING':'TRADEWIZE_OAUTH_URL_MISSING';authCache={token:'',expiresAt:0,state:'NOT_CONFIGURED',error:`missing=${cfg.missing.join(',')}`,reasonCode};return authCache;}"
assert old in s
s=s.replace(old,new,1)

old="fetch(`${TW_BASE}/oauth/token`,"
new="fetch(OAUTH_URL,"
assert old in s
s=s.replace(old,new,1)

start=s.index('async function probeFull(){')
end=s.index('\nfunction capabilitySnapshot',start)
new_probe=r'''async function probeFull(){
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
'''
s=s[:start]+new_probe+s[end:]

assert 'TW_BASE' not in s
assert 'TRADEWIZE_BASE_URL' not in s
assert 'TRADEWIZE_OAUTH_URL' in s
assert 'fetch(OAUTH_URL,' in s
assert s.index('ensureTradeWizeAuth(true)') < s.index('TRADEWIZE_UNIVERSE_URL_MISSING')
assert s.index('TRADEWIZE_UNIVERSE_URL_MISSING') < s.index('VIOP_CONTRACT_METADATA_UNAVAILABLE')
assert s.index('VIOP_CONTRACT_METADATA_UNAVAILABLE') < s.index('TRADEWIZE_QUOTE_URL_MISSING')
assert s.index('TRADEWIZE_QUOTE_URL_MISSING') < s.index('TRADEWIZE_HISTORY_URL_MISSING')
assert s.index('TRADEWIZE_HISTORY_URL_MISSING') < s.index('VIOP_LIQUIDITY_UNVERIFIED')

p.write_text(s)
print('VIOP_R3_SOURCE_PATCH=PASS')
