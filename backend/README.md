# Production backend

FastAPI adapter for the Android V5.4.16 contract. The backend separates global multi-market readiness from market-specific readiness, implements the classic BIST snapshot-batch contract, and keeps stale data in delayed/observation mode. Dynamic closed-bar scanning remains on `/v1/scanner/opportunities`; V538 canonical ECDSA signing/key-rotation infrastructure is implemented, but attested realtime REST/WebSocket features remain explicitly fail-closed until a real provider stream and real-device/E2E attestation path are verified.

Production Docker deployments set `APP_ENV=production`; startup fails unless `APP_API_KEY`, a strong `SESSION_TOKEN_SECRET` (minimum 32 characters by default), and TradeWize credentials are present. `SESSION_TOKEN_KEY_ID`, `SESSION_TOKEN_PREVIOUS_KEYS_JSON` and `SESSION_TOKEN_NOT_BEFORE` provide deterministic rotation overlap/boundaries; subject-epoch revocation can be persisted with `SESSION_REVOKE_STORE_PATH`. The conservative production default `UPSTREAM_QUOTA_SCOPE=all` routes TradeWize market-data GETs through the same minute/hour pacing and bounded retry gate; `bars_only` is available only for deployments that have independently verified a narrower provider quota contract. Upstream HTTP 429 `Retry-After` extends a process-wide market-data cooldown, and stale-quote `recent-ticks` recovery uses that same gate. A rolling bar cache reuses verified closed candles and supports incremental windows while bounding idle age, key count and total candle count. `range=max` returns the untruncated normalized upstream result while only the cached tail is bounded. Android→backend ingress has a separate layered token-bucket guard with TTL/LRU-bounded identity state. VİOP contracts remain fail-closed unless `TRADEWIZE_VIOP_CONTRACT_METADATA_PATH` points to a verified metadata source. Installation-bound short-lived `BorsaSession` tokens are mandatory in production configuration and remain optional only in non-production development/test environments.

Optional hardening layers are deliberately config-gated: `BAR_CACHE_PERSIST_PATH` + prewarm settings provide restart cache recovery; `UPSTREAM_DISTRIBUTED_QUOTA=true` + `REDIS_URL` adds a Redis/Lua cross-instance provider quota gate; `TRADINGVIEW_WEBHOOK_SECRET` + `TRADINGVIEW_DB_PATH` enables the authenticated SQLite-backed TradingView webhook/signal store. These features are never reported ready from a partial configuration. Snapshot-batch retries reuse `X-Request-ID` and backend single-flight/idempotency so cancellation/retry does not multiply identical work.

Run locally (credentials required for live upstream calls):

```bash
python -m pip install -r requirements-dev.txt
PYTHONPATH=. pytest -q
uvicorn main:app --host 0.0.0.0 --port 10000
```

Canonical API details are in `../contracts/api-v1.md` and the machine-readable route map is `../contracts/routes-v1.json`.


Live production verification is intentionally manual: set `BORSA_E2E_BASE_URL` and `BORSA_E2E_API_KEY`, then run `python e2e_smoke.py`. The script checks health/preflight/capabilities, API-key→short-lived-session bootstrap and installation binding, BIST quote/history/batch freshness parity, dynamic scanner, `range=max`, VİOP ready-or-fail-closed, and optional TradingView/Research/realtime fail-closed contracts. `BORSA_E2E_ARTIFACT_PATH` writes a redacted JSON evidence artifact containing timestamps, commit/deployment revision (when supplied), check results and no credentials.

Performance acceptance is also explicit/manual. `benchmark_full_bist.py` walks the discovered BIST universe using the backend-advertised batch policy across `1m,5m,15m,30m,60m,1d` (configurable), records elapsed/batch p50/p95/error/quota/cache deltas and writes a redacted artifact. It never enables or claims the five-minute full-BIST SLA.


## Snapshot batch policy
`/v1/provider/capabilities` exposes `scanPolicy.snapshotBatchMaxSymbols`, read/call/outer timeout budgets, quota scope and pacing. Android consumes these values with bounded conservative fallbacks instead of hard-coding the backend batch contract. `allTimeHistory` defaults to false until a real provider E2E explicitly verifies complete historical coverage; the `range=max` route remains implemented without clipping the response to the rolling cache.

## Source-build note
The Android source archive includes a pinned Gradle 8.11.1 bootstrap wrapper with SHA-256 verification. APK generation is intentionally gated behind explicit `workflow_dispatch` input; normal source CI does not assemble or upload an APK.
