#!/usr/bin/env python3
import json, os, threading, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

BASE=os.getenv("TRADEWIZE_BASE_URL","https://api.tradewize.com.tr").rstrip("/")
KEY=(os.getenv("TRADEWIZE_API_KEY","").strip() or os.getenv("BORSA_API_KEY","").strip())
PORT=int(os.getenv("PORT","8080"))

def req(method,path,headers=None,body=None,params=None):
    url=BASE+path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data=json.dumps(body).encode() if body is not None else None
    r=urllib.request.Request(url,data=data,method=method,headers=headers or {})
    with urllib.request.urlopen(r,timeout=20) as resp:
        return resp.status, dict(resp.headers.items()), json.loads(resp.read().decode("utf-8"))

def probe():
    print("V611_PROBE_KEY_PRESENT="+("YES" if KEY else "NO"), flush=True)
    if not KEY:
        return
    try:
        s,_,a=req("POST","/oauth/token",headers={"X-API-Key":KEY,"Accept":"application/json","Content-Type":"application/json"},body={"grant_type":"api_key"})
        tok=a.get("access_token") if isinstance(a,dict) else None
        print("V611_PROBE_AUTH_STATUS="+str(s), flush=True)
        print("V611_PROBE_AUTH_TOKEN="+("PASS" if tok else "MISSING"), flush=True)
        if not tok:
            return
        headers={"Authorization":"Bearer "+tok,"Accept":"application/json","User-Agent":"BorsaTakip-V611-Probe/2"}
        s,_,p=req("GET","/api/v1/market-data/viop/last-price/details",headers=headers,params={"all":"true"})
        raw=p.get("data") if isinstance(p,dict) and isinstance(p.get("data"),(dict,list)) else p
        count=len(raw) if isinstance(raw,(dict,list)) else 0
        print("V611_PROBE_VIOP_STATUS="+str(s), flush=True)
        print("V611_PROBE_RECORD_COUNT="+str(count), flush=True)
        if isinstance(raw,dict) and raw:
            symbols=sorted(str(k) for k,v in raw.items() if isinstance(v,dict))
            print("V611_PROBE_SAMPLE_SYMBOL="+(symbols[0] if symbols else "NONE"), flush=True)
        print("V611_PROBE_PASS", flush=True)
    except Exception as e:
        code=getattr(e,"code",None)
        print("V611_PROBE_FAIL type="+type(e).__name__+" http="+str(code), flush=True)

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/health","/v1/health"):
            self.send_response(404); self.end_headers(); return
        b=b'{"ok":true,"service":"v611-viop-probe-runtime"}'
        self.send_response(200)
        self.send_header("Content-Type","application/json")
        self.send_header("Content-Length",str(len(b)))
        self.end_headers(); self.wfile.write(b)
    def log_message(self,*args):
        pass

if __name__=="__main__":
    threading.Thread(target=probe,daemon=True).start()
    HTTPServer(("0.0.0.0",PORT),H).serve_forever()
