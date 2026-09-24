"""Production-like source E2E fixtures for PRE-APK hardening.

These tests deliberately use local TestClient/fakes. They prove route semantics without claiming
that Railway/provider/device external acceptance has passed.
"""
import hashlib
import hmac
import json
import time

import pytest
from fastapi.testclient import TestClient

import main


@pytest.fixture(autouse=True)
def _reset_ingress_state():
    main._ingress_buckets.clear()
    main._ingress_metrics.update({"accepted": 0, "rejected": 0, "rateLimited429": 0, "weightedUnits": 0})
    yield
    main._ingress_buckets.clear()


def _client():
    return TestClient(main.app)


def _fresh_quote(symbol: str, *, source: str = "TradeWize") -> dict:
    now = int(time.time() * 1000)
    return {
        "symbol": symbol,
        "price": 100.0,
        "timestamp": now,
        "realtime": True,
        "delaySeconds": 0,
        "currentSessionIncluded": True,
        "source": source,
        "market": "BIST",
        "assetType": "STOCK",
        "name": symbol,
    }


def _stale_quote(symbol: str) -> dict:
    row = _fresh_quote(symbol)
    row.update({"timestamp": int(time.time() * 1000) - 120_000, "realtime": False, "delaySeconds": 120})
    return row


def _history(symbol: str) -> list[dict]:
    base = 1_700_000_000_000
    return [
        {
            "timestamp": base + i * 86_400_000,
            "open": 100.0 + i,
            "high": 102.0 + i,
            "low": 99.0 + i,
            "close": 101.0 + i,
            "volume": 1000.0,
        }
        for i in range(60)
    ]


def _expire_token(token: str, secret: str) -> str:
    body, _ = token.split(".", 1)
    payload = json.loads(main._b64url_decode(body).decode("utf-8"))
    now = int(time.time())
    payload["iat"] = now - 120
    payload["exp"] = now - 1
    expired_body = main._b64url_encode(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8"))
    signature = hmac.new(secret.encode("utf-8"), expired_body.encode("ascii"), hashlib.sha256).digest()
    return f"{expired_body}.{main._b64url_encode(signature)}"


def test_140_auth_bootstrap_session_to_protected_call_is_install_bound(monkeypatch):
    secret = "V5416-R8-Session-Secret-For-Source-E2E-2026!"
    monkeypatch.setattr(main, "APP_API_KEY", "bootstrap-r8")
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", secret)
    monkeypatch.setattr(main, "SESSION_TOKEN_KEY_ID", "kid-r8")
    monkeypatch.setattr(main, "SESSION_TOKEN_PREVIOUS_KEYS_JSON", "")
    main._session_subject_epochs.clear()
    main._ingress_buckets.clear()
    c = _client()

    minted = c.post(
        "/v1/auth/session",
        headers={"Authorization": "Bearer bootstrap-r8", "X-Install-ID": "install-r8-a"},
    )
    assert minted.status_code == 200
    body = minted.json()
    token = body["accessToken"]
    decoded = json.loads(main._b64url_decode(token.split(".", 1)[0]).decode("utf-8"))
    assert decoded["installId"] == "install-r8-a"
    assert decoded["sub"] == main._session_subject("install-r8-a")
    assert decoded["exp"] > decoded["iat"]

    protected = c.get(
        "/v1/health",
        headers={"Authorization": f"BorsaSession {token}", "X-Install-ID": "install-r8-a"},
    )
    assert protected.status_code == 200


def test_142_expired_session_401_then_controlled_bootstrap_refresh(monkeypatch):
    secret = "V5416-R8-Session-Secret-For-Expiry-2026!"
    monkeypatch.setattr(main, "APP_API_KEY", "bootstrap-r8")
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", secret)
    monkeypatch.setattr(main, "SESSION_TOKEN_KEY_ID", "kid-r8")
    monkeypatch.setattr(main, "SESSION_TOKEN_PREVIOUS_KEYS_JSON", "")
    main._session_subject_epochs.clear()
    main._ingress_buckets.clear()
    c = _client()

    valid, _ = main._issue_session_token("install-expired")
    expired = _expire_token(valid, secret)
    denied = c.get(
        "/v1/health",
        headers={"Authorization": f"BorsaSession {expired}", "X-Install-ID": "install-expired"},
    )
    assert denied.status_code == 401

    refreshed = c.post(
        "/v1/auth/session",
        headers={"Authorization": "Bearer bootstrap-r8", "X-Install-ID": "install-expired"},
    )
    assert refreshed.status_code == 200
    replacement = refreshed.json()["accessToken"]
    assert replacement != expired
    ok = c.get(
        "/v1/health",
        headers={"Authorization": f"BorsaSession {replacement}", "X-Install-ID": "install-expired"},
    )
    assert ok.status_code == 200


def test_143_installation_id_missing_or_changed_is_rejected_for_session(monkeypatch):
    monkeypatch.setattr(main, "APP_API_KEY", "bootstrap-r8")
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "V5416-R8-Session-Secret-Install-Binding-2026!")
    monkeypatch.setattr(main, "SESSION_TOKEN_KEY_ID", "kid-r8")
    monkeypatch.setattr(main, "SESSION_TOKEN_PREVIOUS_KEYS_JSON", "")
    main._session_subject_epochs.clear()
    main._ingress_buckets.clear()
    token, _ = main._issue_session_token("install-owner")
    c = _client()
    headers = {"Authorization": f"BorsaSession {token}"}
    assert c.get("/v1/health", headers=headers).status_code == 401
    assert c.get("/v1/health", headers={**headers, "X-Install-ID": "install-other"}).status_code == 401
    assert c.get("/v1/health", headers={**headers, "X-Install-ID": "install-owner"}).status_code == 200


def test_144_ingress_burst_returns_429_and_retry_after(monkeypatch):
    monkeypatch.setattr(main, "APP_API_KEY", "")
    monkeypatch.setattr(main, "INGRESS_RATE_LIMIT_PER_MINUTE", 1)
    monkeypatch.setattr(main, "INGRESS_RATE_LIMIT_BURST", 1)
    main._ingress_buckets.clear()
    c = _client()
    headers = {"X-Install-ID": "install-rate-r8"}
    assert c.get("/v1/health", headers=headers).status_code == 200
    limited = c.get("/v1/health", headers=headers)
    assert limited.status_code == 429
    assert int(limited.headers["Retry-After"]) >= 1
    assert limited.json()["detail"] == "BACKEND_INGRESS_RATE_LIMIT"


@pytest.mark.asyncio
async def test_146_148_snapshot_partial_error_order_and_cardinality(monkeypatch):
    monkeypatch.setattr(main, "APP_API_KEY", "")
    requested = ["THYAO", "ASELS", "GARAN"]

    async def discover(force=False):
        return [_fresh_quote(s) for s in requested]

    async def history(symbol, interval="1d", range_value="1y", from_ms=None, to_ms=None, scanner=False):
        if symbol == "ASELS":
            raise main.HTTPException(status_code=503, detail="INSUFFICIENT_HISTORY: fixture")
        return _history(symbol)

    monkeypatch.setattr(main, "discover_bist_quotes", discover)
    monkeypatch.setattr(main, "_fetch_bist_history", history)
    payload = await main.bist_snapshot_batch(",".join(requested), None, "1d", "1y", None, None)
    assert payload["requestedCount"] == payload["returnedCount"] == 3
    assert [row["symbol"] for row in payload["items"]] == requested
    assert "quote" in payload["items"][0]
    assert payload["items"][1]["error"]["code"] == "INSUFFICIENT_HISTORY"
    assert "quote" in payload["items"][2]

    with pytest.raises(main.HTTPException) as dup:
        await main.bist_snapshot_batch("THYAO,THYAO", None, "1d", "1y", None, None)
    assert dup.value.status_code == 400


@pytest.mark.asyncio
async def test_147_stale_last_price_uses_fresh_recent_tick_with_source(monkeypatch):
    monkeypatch.setattr(main, "APP_API_KEY", "")

    async def discover(force=False):
        return [_stale_quote("THYAO")]

    async def recent(symbol):
        return _fresh_quote(symbol, source="TradeWize/recent-ticks")

    async def history(symbol, interval="1d", range_value="1y", from_ms=None, to_ms=None, scanner=False):
        return _history(symbol)

    monkeypatch.setattr(main, "discover_bist_quotes", discover)
    monkeypatch.setattr(main, "_recent_tick", recent)
    monkeypatch.setattr(main, "_fetch_bist_history", history)
    payload = await main.bist_snapshot_batch("THYAO", None, "1d", "1y", None, None)
    quote = payload["items"][0]["quote"]
    assert quote["realtime"] is True
    assert quote["delaySeconds"] == 0
    assert quote["source"] == "TradeWize/recent-ticks"


def test_149_150_optional_provider_surfaces_fail_closed_without_configuration(monkeypatch):
    monkeypatch.setattr(main, "APP_API_KEY", "")
    monkeypatch.setattr(main, "VIOP_CONTRACT_METADATA_PATH", "")
    monkeypatch.setattr(main, "TRADINGVIEW_WEBHOOK_SECRET", "")
    monkeypatch.setattr(main, "TRADINGVIEW_DB_PATH", "")
    main._viop_contract_cache.update({"at": 0.0, "items": [], "universeAsOf": 0})
    c = _client()

    viop = c.get("/v1/viop/contracts")
    assert viop.status_code == 503
    assert "VIOP_CONTRACT_METADATA" in str(viop.json()["detail"])

    trading = c.get("/v1/tradingview/signals?limit=10")
    assert trading.status_code == 503
    assert trading.json()["detail"]["feature"] == "tradingViewSignals"


def test_151_research_is_intentionally_out_of_scope_and_fail_closed_after_dead_client_cleanup(monkeypatch):
    monkeypatch.setattr(main, "APP_API_KEY", "")
    c = _client()
    response = c.get("/v1/research/foundation?symbol=THYAO")
    assert response.status_code == 503
    detail = response.json()["detail"]
    assert detail["feature"] == "researchFoundation"
    # No fallback is fabricated because task 101 selected the "remove unused surface" branch.
    assert "sourceType" not in detail

@pytest.mark.asyncio
async def test_145_global_retry_after_blocks_other_market_data_dispatches(monkeypatch):
    """Local production-like fixture for checklist 145; real Railway E2E remains separate."""
    main._scanner_next_allowed_at = 0.0
    monkeypatch.setattr(main, "SCANNER_PACING_MS", 0)
    monkeypatch.setattr(main, "SCANNER_RETRY_COUNT", 0)
    monkeypatch.setattr(main, "UPSTREAM_DISTRIBUTED_QUOTA", False)

    async def fake_raw(path, params):
        return {"ok": True}

    monkeypatch.setattr(main, "_raw_upstream_get", fake_raw)
    exc = main.HTTPException(status_code=429, detail="rate", headers={"Retry-After": "0.05"})
    seconds = main._retry_after_seconds(exc)
    assert seconds is not None and seconds >= 0.05
    await main._extend_upstream_cooldown(seconds)
    started = time.monotonic()
    result = await main.scanner_upstream_get("/api/v1/market-data/bars", {})
    waited = time.monotonic() - started
    assert result == {"ok": True}
    assert waited >= 0.04
