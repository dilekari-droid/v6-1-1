#!/usr/bin/env python3
import json
import os
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

BASE = os.getenv("TRADEWIZE_BASE_URL", "https://api.tradewize.com.tr").rstrip("/")
API_KEY = os.getenv("TRADEWIZE_API_KEY", "").strip() or os.getenv("BORSA_API_KEY", "").strip()
PORT = int(os.getenv("PORT", "8080"))

SENSITIVE_MARKERS = ("token", "secret", "password", "authorization", "api_key", "apikey", "key")


def safe_type(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "bool"
    if isinstance(value, int):
        return "int"
    if isinstance(value, float):
        return "float"
    if isinstance(value, str):
        return "str"
    if isinstance(value, list):
        return "list"
    if isinstance(value, dict):
        return "object"
    return type(value).__name__


def safe_sample(record):
    out = {}
    for key, value in sorted(record.items()):
        low = str(key).lower()
        if any(marker in low for marker in SENSITIVE_MARKERS):
            out[key] = "<redacted>"
        elif isinstance(value, (dict, list)):
            out[key] = {"type": safe_type(value), "size": len(value)}
        elif isinstance(value, str):
            out[key] = value[:160]
        else:
            out[key] = value
    return out


def request_json(method, path, *, headers=None, body=None, params=None):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def unwrap(payload):
    if isinstance(payload, dict) and isinstance(payload.get("data"), (dict, list)):
        return payload["data"]
    return payload


def probe():
    if not API_KEY:
        print("VIOP_PROBE_FAIL missing BORSA_API_KEY/TRADEWIZE_API_KEY", flush=True)
        return
    try:
        auth = request_json(
            "POST", "/oauth/token",
            headers={"X-API-Key": API_KEY, "Accept": "application/json", "Content-Type": "application/json"},
            body={"grant_type": "api_key"},
        )
        token = auth.get("access_token") if isinstance(auth, dict) else None
        if not token:
            print("VIOP_PROBE_FAIL oauth response missing access_token", flush=True)
            return
        headers = {"Authorization": f"Bearer {token}", "Accept": "application/json", "User-Agent": "BorsaTakip-V611-VIOP-Probe/1"}
        payload = request_json("GET", "/api/v1/market-data/viop/last-price/details", headers=headers, params={"all": "true"})
        raw = unwrap(payload)
        print("VIOP_PROBE_TOP_TYPE", safe_type(raw), flush=True)
        if isinstance(payload, dict):
            print("VIOP_PROBE_TOP_KEYS", json.dumps(sorted(payload.keys()), ensure_ascii=False), flush=True)
        records = raw if isinstance(raw, dict) else {}
        print("VIOP_PROBE_RECORD_COUNT", len(records), flush=True)
        candidates = []
        for symbol, record in records.items():
            if not isinstance(symbol, str) or not isinstance(record, dict):
                continue
            candidates.append((0 if symbol.upper().startswith("F_XU030") else 1, symbol, record))
        if not candidates:
            print("VIOP_PROBE_FAIL no dict records", flush=True)
            return
        _, symbol, record = sorted(candidates, key=lambda x: (x[0], x[1]))[0]
        print("VIOP_PROBE_SYMBOL", symbol, flush=True)
        print("VIOP_PROBE_FIELD_TYPES", json.dumps({k: safe_type(v) for k, v in sorted(record.items())}, ensure_ascii=False, sort_keys=True), flush=True)
        print("VIOP_PROBE_SAFE_SAMPLE", json.dumps(safe_sample(record), ensure_ascii=False, sort_keys=True), flush=True)
        print("VIOP_PROBE_PASS", flush=True)
    except Exception as exc:
        print("VIOP_PROBE_FAIL", type(exc).__name__, str(exc)[:500], flush=True)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/health", "/v1/health"):
            self.send_response(404); self.end_headers(); return
        body = b'{"ok":true,"service":"v611-viop-probe"}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)
    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    threading.Thread(target=probe, daemon=True).start()
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
