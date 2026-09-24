import asyncio
import importlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path

import pytest

main = importlib.import_module("main")


@pytest.fixture(autouse=True)
def reset_state():
    main._bar_series_cache.clear()
    main._bar_series_locks.clear()
    yield
    main._bar_series_cache.clear()
    main._bar_series_locks.clear()


def _candle(ts: int):
    return {"timestamp": ts, "open": 1.0, "high": 2.0, "low": 0.5, "close": 1.5, "volume": 1.0}


def test_bar_cache_idle_ttl_evicts_idle_and_preserves_active(monkeypatch):
    monkeypatch.setattr(main, "BAR_CACHE_IDLE_TTL_SECONDS", 10)
    monkeypatch.setattr(main, "BAR_CACHE_MAX_KEYS", 100)
    monkeypatch.setattr(main, "BAR_CACHE_MAX_TOTAL_BARS", 10_000)
    now = 1_000.0
    old = ("BIST", "OLD", "5m")
    active = ("BIST", "ACTIVE", "5m")
    main._bar_series_cache[old] = {"at": 900.0, "lastAccess": 900.0, "candles": [_candle(1)]}
    main._bar_series_cache[active] = {"at": 995.0, "lastAccess": 995.0, "candles": [_candle(2)]}
    main._bar_series_locks[old] = asyncio.Lock()
    main._bar_series_locks[active] = asyncio.Lock()

    main._prune_bar_cache(now)

    assert old not in main._bar_series_cache
    assert old not in main._bar_series_locks
    assert active in main._bar_series_cache
    assert active in main._bar_series_locks


def test_bar_cache_lru_eviction_order_is_deterministic(monkeypatch):
    monkeypatch.setattr(main, "BAR_CACHE_IDLE_TTL_SECONDS", 10_000)
    monkeypatch.setattr(main, "BAR_CACHE_MAX_KEYS", 2)
    monkeypatch.setattr(main, "BAR_CACHE_MAX_TOTAL_BARS", 10_000)
    a = ("BIST", "AAA", "5m")
    b = ("BIST", "BBB", "5m")
    c = ("BIST", "CCC", "5m")
    main._store_bar_cache(a, 100.0, [_candle(1)])
    main._store_bar_cache(b, 200.0, [_candle(2)])
    # Touch A so B becomes least-recently-used.
    main._bar_series_cache[a]["lastAccess"] = 300.0
    main._store_bar_cache(c, 400.0, [_candle(3)])

    assert a in main._bar_series_cache
    assert c in main._bar_series_cache
    assert b not in main._bar_series_cache


@pytest.mark.asyncio
async def test_orphan_bar_locks_are_cleaned_but_active_lock_is_preserved(monkeypatch):
    monkeypatch.setattr(main, "BAR_CACHE_IDLE_TTL_SECONDS", 10_000)
    orphan = ("BIST", "ORPHAN", "5m")
    active = ("BIST", "ACTIVELOCK", "5m")
    main._bar_series_locks[orphan] = asyncio.Lock()
    locked = asyncio.Lock()
    await locked.acquire()
    main._bar_series_locks[active] = locked

    main._prune_bar_cache(time.time())
    assert orphan not in main._bar_series_locks
    assert active in main._bar_series_locks

    locked.release()
    main._prune_bar_cache(time.time())
    assert active not in main._bar_series_locks


@pytest.mark.asyncio
async def test_all_history_markets_use_single_cache_freshness_policy(monkeypatch):
    calls = []

    def policy(market, interval, session_state="UNKNOWN"):
        calls.append((market.upper(), interval.lower(), session_state))
        return {"market": market.upper(), "interval": interval.lower(), "sessionState": session_state, "ttlSeconds": 60, "policyVersion": "test"}

    class Response:
        def json(self):
            return {"bars": [_candle(1_700_000_000_000 + i * 60_000) for i in range(60)]}

    async def upstream(path, params):
        return Response()

    monkeypatch.setattr(main, "_cache_freshness_policy", policy)
    monkeypatch.setattr(main, "bar_upstream_get", upstream)
    await main._cached_market_bars("BIST", "THYAO", "5m", "5m", range_value="1d")
    await main._cached_market_bars("VIOP", "F_XU0301026", "5m", "5m", range_value="1d")

    assert ("BIST", "5m", "UNKNOWN") in calls
    assert ("VIOP", "5m", "UNKNOWN") in calls


def _tv_signal(event_id: str, symbol: str, received_at: int, signal_time: int | None = None):
    return {
        "id": event_id,
        "semanticKey": f"semantic-{event_id}",
        "symbol": symbol,
        "action": "LONG",
        "price": 100.0,
        "signalTime": signal_time if signal_time is not None else received_at,
        "receivedAt": received_at,
        "interval": "5m",
        "strategy": "r8-test",
        "message": None,
        "rsi": None,
        "macd": None,
        "macdSignal": None,
        "macdHistogram": None,
        "emaFast": None,
        "emaSlow": None,
        "atr": None,
        "volume": None,
        "volumeRatio": None,
        "score": 80.0,
        "trend": "LONG",
        "barConfirmed": True,
        "rawJson": json.dumps({"eventId": event_id, "symbol": symbol}),
    }


def test_tradingview_persistence_survives_store_reopen(monkeypatch, tmp_path):
    db = tmp_path / "tv.sqlite3"
    monkeypatch.setattr(main, "TRADINGVIEW_WEBHOOK_SECRET", "R8-Webhook-Secret-Alpha-2026")
    monkeypatch.setattr(main, "TRADINGVIEW_DB_PATH", str(db))
    now = int(time.time() * 1000)
    inserted, _ = main._tradingview_insert(_tv_signal("persist-1", "THYAO", now))
    assert inserted is True

    # Every list call opens a fresh SQLite connection; no in-memory signal state is reused.
    result = main._tradingview_list(10, None)
    assert [item["id"] for item in result["items"]] == ["persist-1"]


def test_tradingview_retention_removes_old_records(monkeypatch, tmp_path):
    db = tmp_path / "tv.sqlite3"
    monkeypatch.setattr(main, "TRADINGVIEW_WEBHOOK_SECRET", "R8-Webhook-Secret-Alpha-2026")
    monkeypatch.setattr(main, "TRADINGVIEW_DB_PATH", str(db))
    monkeypatch.setattr(main, "TRADINGVIEW_RETENTION_DAYS", 7)
    now = int(time.time() * 1000)
    assert main._tradingview_insert(_tv_signal("old", "OLD", now - 9 * 86_400_000))[0]
    assert main._tradingview_insert(_tv_signal("new", "NEW", now))[0]

    result = main._tradingview_list(10, None)
    ids = [item["id"] for item in result["items"]]
    assert "old" not in ids
    assert "new" in ids


def test_tradingview_cursor_pagination_has_no_duplicates_or_gaps(monkeypatch, tmp_path):
    db = tmp_path / "tv.sqlite3"
    monkeypatch.setattr(main, "TRADINGVIEW_WEBHOOK_SECRET", "R8-Webhook-Secret-Alpha-2026")
    monkeypatch.setattr(main, "TRADINGVIEW_DB_PATH", str(db))
    now = int(time.time() * 1000)
    for idx in range(5):
        assert main._tradingview_insert(_tv_signal(f"evt-{idx}", f"SYM{idx}", now + idx))[0]

    seen = []
    cursor = None
    while True:
        page = main._tradingview_list(2, cursor)
        seen.extend(item["id"] for item in page["items"])
        assert all("sourceAt" in item and "receivedAt" in item for item in page["items"])
        if not page["hasMore"]:
            break
        assert page["nextCursor"] is not None
        cursor = int(page["nextCursor"])

    assert len(seen) == 5
    assert len(set(seen)) == 5
    assert set(seen) == {f"evt-{idx}" for idx in range(5)}


@pytest.mark.asyncio
async def test_attestation_signing_key_rotation_overlap_supports_old_and_new_kids(monkeypatch):
    import base64
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    old_key = ec.generate_private_key(ec.SECP256R1())
    new_key = ec.generate_private_key(ec.SECP256R1())

    def der_b64(key):
        return base64.b64encode(key.private_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )).decode()

    monkeypatch.setattr(main, "ATTESTATION_PRIVATE_KEYS_JSON", json.dumps({
        "kid-old": {"generation": 1, "privateKeyBase64": der_b64(old_key)},
        "kid-new": {"generation": 2, "privateKeyBase64": der_b64(new_key)},
    }))
    monkeypatch.setattr(main, "ATTESTATION_PROVIDER_ID", "tradewize")
    monkeypatch.setattr(main, "ATTESTATION_TTL_MS", 60_000)
    main._attestation_sequence = 0
    main._attestation_recent_nonces.clear()

    async def sign_for(kid, generation, request):
        monkeypatch.setattr(main, "ATTESTATION_ACTIVE_KEY_ID", kid)
        monkeypatch.setattr(main, "ATTESTATION_ACTIVE_KEY_GENERATION", generation)
        return await main._sign_v538_attestation(
            snapshot_hash=("a" if generation == 1 else "b") * 64,
            request_id=request,
            snapshot_id=f"snapshot-{request}",
            calculation_engine_version="V5.4.16",
            generated_at=1_800_000_000_000 + generation,
            server_time=1_800_000_000_100 + generation,
        )

    old_env = await sign_for("kid-old", 1, "old")
    new_env = await sign_for("kid-new", 2, "new")

    for env, key in ((old_env, old_key), (new_env, new_key)):
        signature = base64.b64decode(env["attestationSignature"])
        key.public_key().verify(signature, main._v538_canonical(env).encode(), ec.ECDSA(hashes.SHA256()))

    assert old_env["attestationKeyId"] == "kid-old"
    assert new_env["attestationKeyId"] == "kid-new"
    # Overlap: old private material remains addressable while the new key is active.
    assert main._load_attestation_private_key("kid-old", 1) is not None
    assert main._load_attestation_private_key("kid-new", 2) is not None

@pytest.mark.asyncio
async def test_slow_snapshot_batch_same_request_id_does_not_retry_storm(monkeypatch):
    monkeypatch.setattr(main, "APP_API_KEY", "")
    symbols = [f"S{i:02d}" for i in range(20)]
    now_ms = int(time.time() * 1000)
    universe = [
        {"symbol": symbol, "price": 100.0 + idx, "timestamp": now_ms, "realtime": True,
         "delaySeconds": 0, "currentSessionIncluded": True, "source": "TradeWize"}
        for idx, symbol in enumerate(symbols)
    ]
    history_calls = []

    async def discover(force=False):
        return universe

    async def history(symbol, interval="1d", range_value="1y", from_ms=None, to_ms=None, scanner=False):
        history_calls.append(symbol)
        await asyncio.sleep(0.005)
        return [_candle(now_ms - 60_000), _candle(now_ms)]

    monkeypatch.setattr(main, "discover_bist_quotes", discover)
    monkeypatch.setattr(main, "_fetch_bist_history", history)
    main._snapshot_batch_tasks.clear()
    main._snapshot_batch_completed.clear()
    main._snapshot_batch_metrics.update({"started": 0, "joined": 0, "completedHit": 0})
    raw = ",".join(symbols)

    first, second = await asyncio.gather(
        main.bist_snapshot_batch(raw, None, "1d", "1y", None, None, "r8-slow-same-request"),
        main.bist_snapshot_batch(raw, None, "1d", "1y", None, None, "r8-slow-same-request"),
    )
    assert first == second
    assert len(history_calls) == len(symbols)
    assert sorted(history_calls) == sorted(symbols)
    assert main._snapshot_batch_metrics["started"] == 1
    assert main._snapshot_batch_metrics["joined"] == 1

@pytest.mark.asyncio
async def test_distributed_quota_two_callers_share_one_redis_budget(monkeypatch):
    monkeypatch.setattr(main, "UPSTREAM_DISTRIBUTED_QUOTA", True)
    monkeypatch.setattr(main, "REDIS_URL", "redis://shared-quota.invalid/0")
    main._provider_quota_metrics["distributedWaitMs"] = 0
    main._provider_quota_metrics["distributedGatePass"] = 0

    class SharedFakeRedis:
        def __init__(self):
            self.calls = 0
            self.granted = 0
        async def eval(self, script, numkeys, key, *args):
            self.calls += 1
            # Two callers share the same external state. First gets budget, second must wait once,
            # then the simulated next window allows it.
            if self.granted == 0:
                self.granted += 1
                return [1, 0]
            if self.calls == 2:
                return [0, 1]
            self.granted += 1
            return [1, 0]

    shared = SharedFakeRedis()
    async def client():
        return shared
    monkeypatch.setattr(main, "_get_redis_quota_client", client)
    await asyncio.gather(main._distributed_quota_wait(1), main._distributed_quota_wait(1))
    assert shared.calls >= 3
    assert shared.granted == 2
    assert main._provider_quota_metrics["distributedWaitMs"] >= 1
    assert main._provider_quota_metrics["distributedGatePass"] == 2


def test_e2e_artifact_schema_includes_secret_free_quota_metric_summary():
    repo_root = Path(__file__).resolve().parents[2]
    source = (repo_root / "backend" / "e2e_smoke.py").read_text()
    assert '"quotaMetrics": quota_metrics or {}' in source
    assert '"quota-metrics-observable"' in source
    for forbidden in ("TRADEWIZE_API_KEY", "SESSION_TOKEN_SECRET", "BORSA_E2E_API_KEY"):
        # Artifact payload must not serialize these variable names as fields.
        payload_block = source[source.index("def _artifact_payload"):source.index("def _write_artifact")]
        assert forbidden not in payload_block

def test_tradingview_sqlite_survives_real_python_process_restart(tmp_path):
    backend_dir = Path(__file__).resolve().parents[1]
    db = tmp_path / "tv-restart.sqlite3"
    env = os.environ.copy()
    env.update({
        "PYTHONPATH": str(backend_dir),
        "TRADINGVIEW_WEBHOOK_SECRET": "R8-Process-Restart-Secret-2026!",
        "TRADINGVIEW_DB_PATH": str(db),
        "APP_ENV": "development",
    })
    writer = r'''
import json, time, main
now=int(time.time()*1000)
signal={
'id':'restart-event','semanticKey':'restart-semantic','symbol':'THYAO','action':'LONG','price':100.0,
'signalTime':now,'receivedAt':now,'interval':'5m','strategy':'restart','message':None,'rsi':None,'macd':None,
'macdSignal':None,'macdHistogram':None,'emaFast':None,'emaSlow':None,'atr':None,'volume':None,'volumeRatio':None,
'score':80.0,'trend':'LONG','barConfirmed':True,'rawJson':'{}'}
inserted,_=main._tradingview_insert(signal)
assert inserted
print('WRITE_OK')
'''
    reader = r'''
import json, main
payload=main._tradingview_list(10,None)
print(json.dumps([x['id'] for x in payload['items']]))
'''
    first = subprocess.check_output([sys.executable, "-c", writer], cwd=backend_dir, env=env, text=True)
    second = subprocess.check_output([sys.executable, "-c", reader], cwd=backend_dir, env=env, text=True)
    assert "WRITE_OK" in first
    assert "restart-event" in json.loads(second.strip())


def test_android_session_refresh_source_guards_single_flight_and_401_fallback():
    repo = Path(__file__).resolve().parents[2]
    client = (repo / "android/app/src/main/java/tr/borsatakip/v5/data/BackendSessionClient.kt").read_text()
    assert "private val refreshGate = SessionRefreshGate()" in client
    gate = (repo / "android/app/src/main/java/tr/borsatakip/v5/data/SessionRefreshGate.kt").read_text()
    assert "private val mutex = Mutex()" in gate
    assert "mutex.withLock" in gate
    assert "if (isSatisfied()) true else refresh()" in gate
    assert "isSatisfied = { enabled && settings.backendSessionToken.isNotBlank() }" in client

    fallback_sources = [
        repo / "android/app/src/main/java/tr/borsatakip/v5/data/BackendHealthClient.kt",
        repo / "android/app/src/main/java/tr/borsatakip/v5/data/MarketCapabilityClient.kt",
    ]
    for path in fallback_sources:
        source = path.read_text()
        assert "401" in source
        assert "refreshIfNeeded" in source
    # Preflight itself remains fail-closed on auth error; callers do not silently downgrade auth failure.
    preflight = (repo / "android/app/src/main/java/tr/borsatakip/v5/data/BackendPreflightClient.kt").read_text()
    assert "FailureKind.AUTH_ERROR" in preflight and "401" in preflight


def test_ui_market_state_and_signal_visuals_are_centralized_in_shared_policies():
    repo = Path(__file__).resolve().parents[2]
    quality = (repo / "android/app/src/main/java/tr/borsatakip/v5/data/MarketDataQuality.kt").read_text()
    assert "MarketPresentationPolicy.providerStatus" in quality
    assert "fun uiStatus(metadata: MarketDataMetadata?): String = providerStatus(metadata).stateLabel" in quality

    opportunity = (repo / "android/app/src/main/java/tr/borsatakip/v5/ui/OpportunityAdapter.kt").read_text()
    viop = (repo / "android/app/src/main/java/tr/borsatakip/v5/ui/ViopOpportunityAdapter.kt").read_text()
    detail = (repo / "android/app/src/main/java/tr/borsatakip/v5/ui/StockDetailActivity.kt").read_text()
    assert "SignalVisualPolicy.resolve" in opportunity
    assert "SignalVisualPolicy.resolve" in viop
    assert "MarketDataQuality.providerStatus" in detail


def test_e2e_same_revision_gate_is_wired_to_final_source_sha():
    repo_root = Path(__file__).resolve().parents[2]
    workflow = (repo_root / ".github" / "workflows" / "production-e2e.yml").read_text()
    script = (repo_root / "backend" / "e2e_smoke.py").read_text()
    assert "BORSA_E2E_EXPECTED_REVISION: ${{ github.sha }}" in workflow
    assert 'EXPECTED_REVISION = os.getenv("BORSA_E2E_EXPECTED_REVISION", COMMIT_SHA).strip()' in script
    assert '"same-source-revision"' in script
    assert "production deployment revision does not match expected source revision" in script



def test_open_session_freshness_gate_is_explicit_and_checks_all_three_surfaces():
    repo_root = Path(__file__).resolve().parents[2]
    workflow = (repo_root / ".github" / "workflows" / "production-e2e.yml").read_text()
    script = (repo_root / "backend" / "e2e_smoke.py").read_text()
    assert "require_open_session_freshness" in workflow
    assert "BORSA_E2E_REQUIRE_OPEN_SESSION_FRESHNESS" in workflow
    assert "open-session freshness required but preflight is not REALTIME" in script
    assert '"open-session-freshness-parity"' in script
    assert "preflight_ts" in script and "single_ts" in script and "batch:" in script



def test_health_exposes_secret_free_process_metrics_for_concurrency_benchmark():
    metrics = main._process_resource_metrics()
    assert "cpuProcessSeconds" in metrics
    assert metrics["cpuProcessSeconds"] >= 0
    assert "rssBytes" in metrics
    assert metrics["rssBytes"] is None or metrics["rssBytes"] > 0


def test_full_bist_benchmark_captures_concurrency_and_backend_resource_evidence():
    repo_root = Path(__file__).resolve().parents[2]
    source = (repo_root / "backend" / "benchmark_full_bist.py").read_text()
    assert '"barConcurrency": scan_policy.get("barConcurrency")' in source
    assert '"backendProcessDelta": process_metric_delta(before_process, after_process)' in source
    assert '"p95": round(percentile(call_latencies_ms, 95), 2)' in source


def test_full_bist_benchmark_refuses_incomplete_or_wrong_revision_evidence():
    repo_root = Path(__file__).resolve().parents[2]
    source = (repo_root / "backend" / "benchmark_full_bist.py").read_text()
    assert 'BORSA_BENCHMARK_MIN_SYMBOLS", "640"' in source
    assert 'REQUIRED_TIMEFRAMES = ("1m", "5m", "15m", "30m", "60m", "1d")' in source
    assert 'deployment revision mismatch' in source
    assert 'provider is not configured; benchmark cannot be production evidence' in source
    assert 'benchmark contains {row_errors} row errors' in source
    assert '"upstreamAcceptedCalls": quota_delta.get("accepted", 0)' in source


@pytest.mark.asyncio
async def test_distributed_session_revocation_rejects_token_across_instance_local_state(monkeypatch):
    monkeypatch.setattr(main, "SESSION_REVOKE_DISTRIBUTED", True)
    monkeypatch.setattr(main, "REDIS_URL", "redis://unit-test")
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "Very-Strong-Session-Secret-2026!A")
    monkeypatch.setattr(main, "SESSION_TOKEN_KEY_ID", "v1")
    main._session_subject_epochs.clear()

    class SharedRedis:
        def __init__(self):
            self.hashes = {}
        async def hget(self, key, field):
            return self.hashes.get(key, {}).get(field)
        async def hincrby(self, key, field, amount):
            bucket = self.hashes.setdefault(key, {})
            bucket[field] = int(bucket.get(field, 0)) + int(amount)
            return bucket[field]
        async def expire(self, key, ttl):
            return True

    shared = SharedRedis()
    async def client():
        return shared
    monkeypatch.setattr(main, "_get_redis_quota_client", client)

    token, _ = await main._issue_session_token_shared("install-distributed")
    assert await main._verify_session_token_shared(token, "install-distributed") is True

    epoch = await main._revoke_installation_sessions("install-distributed")
    assert epoch == 1

    # Simulate another process with no local revocation cache; Redis must still reject the old token.
    main._session_subject_epochs.clear()
    assert await main._verify_session_token_shared(token, "install-distributed") is False

    fresh, _ = await main._issue_session_token_shared("install-distributed")
    assert await main._verify_session_token_shared(fresh, "install-distributed") is True


@pytest.mark.asyncio
async def test_distributed_snapshot_idempotency_runs_factory_once_for_two_callers(monkeypatch):
    monkeypatch.setattr(main, "SNAPSHOT_BATCH_DISTRIBUTED_IDEMPOTENCY", True)
    monkeypatch.setattr(main, "REDIS_URL", "redis://unit-test")
    main._snapshot_batch_metrics.update({"started": 0, "joined": 0, "completedHit": 0})

    class SharedRedis:
        def __init__(self):
            self.values = {}
        async def get(self, key):
            return self.values.get(key)
        async def set(self, key, value, nx=False, px=None, ex=None):
            if nx and key in self.values:
                return False
            self.values[key] = value
            return True
        async def eval(self, script, numkeys, key, owner):
            if self.values.get(key) == owner:
                self.values.pop(key, None)
                return 1
            return 0

    shared = SharedRedis()
    async def client():
        return shared
    monkeypatch.setattr(main, "_get_redis_quota_client", client)

    calls = 0
    async def factory():
        nonlocal calls
        calls += 1
        await asyncio.sleep(0.12)
        return {"items": [{"symbol": "THYAO"}], "returnedCount": 1}

    first, second = await asyncio.gather(
        main._idempotent_snapshot_batch("same-key", factory),
        main._idempotent_snapshot_batch("same-key", factory),
    )
    assert calls == 1
    assert first == second
    assert first["returnedCount"] == 1
    assert main._snapshot_batch_metrics["started"] == 1
    assert main._snapshot_batch_metrics["joined"] >= 1
