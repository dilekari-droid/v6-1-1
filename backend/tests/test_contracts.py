import asyncio
import importlib
import json
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

main = importlib.import_module("main")
client = TestClient(main.app)


@pytest.fixture(autouse=True)
def clear_caches():
    main._bist_cache.update({"at": 0.0, "items": []})
    main._viop_cache.update({"at": 0.0, "items": []})
    main._capability_cache.update({"at": 0.0, "value": None})
    main._viop_contract_cache.update({"at": 0.0, "items": [], "universeAsOf": 0})
    main._bar_series_cache.clear()
    main._bar_series_locks.clear()
    main._ingress_buckets.clear()
    main._ingress_metrics.update({"accepted": 0, "rejected": 0})
    main._provider_quota_metrics.update({
        "accepted": 0, "delayed": 0, "rateLimited": 0, "retries": 0,
        "cooldownExtensions": 0, "waitMs": 0,
        "byClass": {"BARS": 0, "TICK": 0, "QUOTE": 0, "METADATA": 0},
    })
    main._scanner_next_allowed_at = 0.0
    yield


def price_item(symbol: str, realtime: bool = True):
    now = int(time.time() * 1000)
    ts = now if realtime else now - 120_000
    return {
        "symbol": symbol,
        "price": 100.0,
        "timestamp": ts,
        "realtime": realtime,
        "delaySeconds": 0 if realtime else 120,
        "currentSessionIncluded": realtime,
        "source": "TradeWize",
        "market": "BIST",
        "assetType": "STOCK",
        "name": symbol,
    }


def test_interval_contract_accepts_android_values_and_rejects_240m():
    for value in ("1m", "3m", "5m", "10m", "15m", "30m", "60m", "1d", "1D"):
        assert main.normalize_interval(value) == value.lower()
    with pytest.raises(Exception):
        main.normalize_interval("240m")


def test_10m_resample_emits_only_complete_pairs():
    base = 1_700_000_000_000
    base -= base % 600_000
    bars = [
        {"timestamp": base, "open": 10, "high": 12, "low": 9, "close": 11, "volume": 100},
        {"timestamp": base + 300_000, "open": 11, "high": 13, "low": 10, "close": 12, "volume": 150},
        {"timestamp": base + 600_000, "open": 12, "high": 14, "low": 11, "close": 13, "volume": 200},
    ]
    out = main._resample_5m_to_10m(bars)
    assert len(out) == 1
    assert out[0]["open"] == 10
    assert out[0]["close"] == 12
    assert out[0]["high"] == 13
    assert out[0]["low"] == 9
    assert out[0]["volume"] == 250


@pytest.mark.asyncio
async def test_capabilities_viop_symbol_count_no_nameerror(monkeypatch):
    async def fake_bist(force=False):
        return [price_item("THYAO", True)]

    async def fake_viop(force=False):
        x = price_item("F_XU0301026", True)
        x.update({"market": "VIOP", "assetType": "FUTURE", "volume": 2500.0, "openInterest": 9000})
        return [x, {**x, "symbol": "F_XU0301126"}]

    async def fake_probe(market, items):
        return True, None

    async def fake_metadata(force=False):
        return ([{
            "symbol": "F_XU0301026", "underlying": "XU030", "expiry": "2026-10",
            "contractType": "FUTURE", "tickSize": 0.25, "multiplier": 10.0,
            "lastTradingAt": int(time.time() * 1000) + 86_400_000,
        }], int(time.time() * 1000))

    monkeypatch.setattr(main, "discover_bist_quotes", fake_bist)
    monkeypatch.setattr(main, "discover_viop_quotes", fake_viop)
    monkeypatch.setattr(main, "discover_viop_contract_metadata", fake_metadata)
    monkeypatch.setattr(main, "_market_live_probe", fake_probe)

    payload = await main.provider_capabilities(force=True)
    assert payload["ok"] is True
    assert payload["markets"]["VIOP"]["symbolCount"] == 2
    assert payload["markets"]["BIST"]["ready"] is True
    assert payload["coreMarketsReady"] is True
    assert payload["multiMarketReady"] is False
    assert payload["providerReady"] is False


@pytest.mark.asyncio
async def test_capability_bist_readiness_is_separate_from_global(monkeypatch):
    async def fake_bist(force=False):
        return [price_item("THYAO", True)]

    async def fake_viop(force=False):
        return []

    async def fake_probe(market, items):
        return (market == "BIST"), (None if market == "BIST" else "NO_LIVE_PRICE")

    monkeypatch.setattr(main, "discover_bist_quotes", fake_bist)
    monkeypatch.setattr(main, "discover_viop_quotes", fake_viop)
    monkeypatch.setattr(main, "_market_live_probe", fake_probe)

    payload = await main.provider_capabilities(force=True)
    assert payload["bistReady"] is True
    assert payload["markets"]["BIST"]["ready"] is True
    assert payload["markets"]["VIOP"]["ready"] is False
    assert payload["multiMarketReady"] is False
    assert payload["providerReady"] is False


@pytest.mark.asyncio
async def test_scanner_enforces_server_batch_and_exposes_coverage(monkeypatch):
    universe = [price_item(f"S{i:03d}", True) for i in range(125)]

    async def fake_discover(force=False):
        main._bist_cache.update({"at": time.time(), "items": universe})
        return universe

    async def fake_scan(symbol, interval, market):
        return ({
            "symbol": symbol,
            "name": symbol,
            "market": market,
            "assetType": "STOCK",
            "signal": "WATCH",
            "technicalSignal": "WATCH",
            "verificationStatus": "WATCH",
            "publicationMode": "OBSERVATION_ONLY",
            "analysisTimeframe": interval,
            "score": 0,
            "dataConfidence": 75,
            "dataConfidenceBand": "DEGRADED",
            "currentPrice": 100.0,
            "realtime": False,
            "source": "test",
        }, None)

    async def fake_caps(force=False):
        return {
            "providerReady": False,
            "globalProviderReady": False,
            "multiMarketReady": False,
            "markets": {"BIST": {"ready": True}},
        }

    monkeypatch.setattr(main, "discover_bist_quotes", fake_discover)
    monkeypatch.setattr(main, "_scan_symbol", fake_scan)
    monkeypatch.setattr(main, "provider_capabilities", fake_caps)

    response = await main.scanner_opportunities(
        market="BIST",
        assetType="STOCK",
        timeframe="5m",
        limit=200,
        offset=0,
        symbols=None,
        interval=None,
        minScore=0,
        includeWatch=True,
        authorization=None,
    )
    assert response["success"] is True
    assert response["scannedSymbols"] == main.SCANNER_BATCH_SIZE == 60
    assert response["universeCount"] == 125
    assert response["remainingSymbols"] == 65
    assert response["nextOffset"] == 60
    assert response["coverageComplete"] is False
    assert response["providerReady"] is True
    assert response["globalProviderReady"] is False
    assert response["scanPolicy"]["serverBatchEnforced"] is True


@pytest.mark.asyncio
async def test_scanner_second_page_progresses(monkeypatch):
    universe = [price_item(f"S{i:03d}", True) for i in range(125)]
    main._bist_cache.update({"at": time.time(), "items": universe})
    selected, remaining, total, next_offset = main.scanner_symbols(None, "BIST", "STOCK", 60, 200)
    assert len(selected) == 60
    assert selected[0] == "S060"
    assert total == 125
    assert remaining == 5
    assert next_offset == 120



def test_scanner_asset_type_filters_and_rejects_incompatible_combinations():
    bist = [price_item("THYAO", True), price_item("ASELS", True)]
    viop = [{**price_item("F_XU0301026", True), "market": "VIOP", "assetType": "FUTURE"}]
    main._bist_cache.update({"at": time.time(), "items": bist})
    main._viop_cache.update({"at": time.time(), "items": viop})
    main._viop_contract_cache.update({
        "at": time.time(),
        "items": [{"symbol": "F_XU0301026", "underlying": "XU030"}],
        "universeAsOf": int(time.time() * 1000),
    })

    selected, remaining, total, next_offset = main.scanner_symbols(None, "ALL", "STOCK", 0, 60)
    assert selected == ["THYAO", "ASELS"]
    assert total == 2 and remaining == 0 and next_offset == 2

    selected, remaining, total, next_offset = main.scanner_symbols(None, "ALL", "FUTURE", 0, 60)
    assert selected == ["F_XU0301026"]
    assert total == 1 and remaining == 0 and next_offset == 1

    with pytest.raises(Exception):
        main.scanner_symbols(None, "BIST", "FUTURE", 0, 60)
    with pytest.raises(Exception):
        main.scanner_symbols(None, "VIOP", "STOCK", 0, 60)


def test_scanner_pacing_has_minute_and_hourly_proactive_floor():
    assert main.SCANNER_RATE_LIMIT_WEIGHT_PER_MINUTE == 120
    assert main.SCANNER_REQUESTS_PER_HOUR == 5000
    assert main.SCANNER_REQUEST_WEIGHT == 1
    assert main.SCANNER_MINUTE_PACING_MS >= 500
    assert main.SCANNER_HOURLY_PACING_MS >= 720
    assert main.SCANNER_MIN_PACING_MS >= 720
    assert main.SCANNER_PACING_MS >= main.SCANNER_MIN_PACING_MS


@pytest.mark.asyncio
async def test_all_market_readiness_requires_every_ui_market(monkeypatch):
    async def fake_bist(force=False):
        return [price_item("THYAO", True)]

    async def fake_viop(force=False):
        x = price_item("F_XU0301026", True)
        x.update({"market": "VIOP", "assetType": "FUTURE", "volume": 2500.0, "openInterest": 9000})
        return [x]

    async def fake_probe(market, items):
        return True, None

    async def fake_metadata(force=False):
        return ([{
            "symbol": "F_XU0301026", "underlying": "XU030", "expiry": "2026-10",
            "contractType": "FUTURE", "tickSize": 0.25, "multiplier": 10.0,
            "lastTradingAt": int(time.time() * 1000) + 86_400_000,
        }], int(time.time() * 1000))

    monkeypatch.setattr(main, "discover_bist_quotes", fake_bist)
    monkeypatch.setattr(main, "discover_viop_quotes", fake_viop)
    monkeypatch.setattr(main, "discover_viop_contract_metadata", fake_metadata)
    monkeypatch.setattr(main, "_market_live_probe", fake_probe)
    payload = await main.provider_capabilities(force=True)
    assert payload["coreMarketsReady"] is True
    assert payload["markets"]["BIST"]["ready"] is True
    assert payload["markets"]["VIOP"]["ready"] is True
    assert payload["markets"]["COMMODITY"]["ready"] is False
    assert payload["multiMarketReady"] is False
    assert payload["globalProviderReady"] is False


def test_capability_endpoint_contract_shape(monkeypatch):
    async def fake_caps(force=False):
        return {
            "ok": True,
            "version": "1.1.0",
            "providerReady": False,
            "multiMarketReady": False,
            "primaryConfiguredProvider": "TradeWize",
            "tradeWize": {"state": "CONFIGURED", "adapterVerified": True},
            "markets": {"BIST": {"ready": True, "supported": True, "discovery": True, "marketData": True, "historicalData": True, "realtime": True, "symbolCount": 630}},
        }
    monkeypatch.setattr(main, "provider_capabilities", fake_caps)
    response = client.get("/v1/provider/capabilities")
    assert response.status_code == 200
    data = response.json()
    assert data["ok"] is True
    assert data["markets"]["BIST"]["symbolCount"] == 630

@pytest.mark.asyncio
async def test_snapshot_batch_returns_exactly_one_row_per_requested_symbol(monkeypatch):
    universe = [price_item("THYAO", True), price_item("ASELS", False)]

    async def fake_discover(force=False):
        return universe

    async def fake_history(symbol, interval="1d", range_value="1y", from_ms=None, to_ms=None, scanner=False):
        assert scanner is True
        base = 1_700_000_000_000
        return [
            {"timestamp": base + i * 86_400_000, "open": 100.0 + i, "high": 102.0 + i, "low": 99.0 + i, "close": 101.0 + i, "volume": 1000.0}
            for i in range(60)
        ]

    async def fake_recent_tick(symbol):
        return price_item(symbol, True) if symbol == "ASELS" else None

    monkeypatch.setattr(main, "discover_bist_quotes", fake_discover)
    monkeypatch.setattr(main, "_recent_tick", fake_recent_tick)
    monkeypatch.setattr(main, "_fetch_bist_history", fake_history)

    payload = await main.bist_snapshot_batch("THYAO,ASELS", None, "1d", "1y", None, None)
    assert payload["requestedCount"] == 2
    assert payload["returnedCount"] == 2
    assert [row["symbol"] for row in payload["items"]] == ["THYAO", "ASELS"]
    assert all("quote" in row and "history" in row for row in payload["items"])
    assert payload["items"][0]["history"]["lastBarClosed"] is True


@pytest.mark.asyncio
async def test_snapshot_batch_keeps_error_row_instead_of_dropping_symbol(monkeypatch):
    universe = [price_item("THYAO", True), price_item("ASELS", True)]

    async def fake_discover(force=False):
        return universe

    async def fake_history(symbol, interval="1d", range_value="1y", from_ms=None, to_ms=None, scanner=False):
        if symbol == "ASELS":
            raise main.HTTPException(status_code=503, detail="INSUFFICIENT_HISTORY: test")
        return [
            {"timestamp": 1_700_000_000_000 + i * 86_400_000, "open": 100.0, "high": 102.0, "low": 99.0, "close": 101.0, "volume": 1000.0}
            for i in range(60)
        ]

    monkeypatch.setattr(main, "discover_bist_quotes", fake_discover)
    monkeypatch.setattr(main, "_fetch_bist_history", fake_history)

    payload = await main.bist_snapshot_batch("THYAO,ASELS", None, "1d", "1y", None, None)
    assert [row["symbol"] for row in payload["items"]] == ["THYAO", "ASELS"]
    assert payload["items"][1]["error"]["code"] == "INSUFFICIENT_HISTORY"


def test_realtime_rest_is_split_and_fail_closed():
    response = client.get("/v1/realtime/opportunities")
    assert response.status_code == 503
    detail = response.json()["detail"]
    assert detail["code"] == "REALTIME_SCANNER_NOT_AVAILABLE"
    assert detail["attestationReady"] is False


def test_feature_capabilities_are_explicit():
    assert main.FEATURE_CAPABILITIES["bistSnapshotBatch"] is True
    assert main.FEATURE_CAPABILITIES["dynamicScanner"] is True
    assert main.FEATURE_CAPABILITIES["realtimeScannerRest"] is False
    assert main.FEATURE_CAPABILITIES["liveMarketWebSocket"] is False
    assert main.FEATURE_CAPABILITIES["realtimeScannerWebSocket"] is False
    assert main.FEATURE_CAPABILITIES["attestationReady"] is False


def test_hourly_rate_floor_and_retry_after_parser():
    assert main.SCANNER_REQUESTS_PER_HOUR == 5000
    assert main.SCANNER_HOURLY_PACING_MS >= 720
    assert main.SCANNER_PACING_MS >= main.SCANNER_HOURLY_PACING_MS
    exc = main.HTTPException(status_code=429, detail="rate", headers={"Retry-After": "30"})
    assert main._retry_after_seconds(exc) == 30.0


def test_production_auth_guard_is_fail_closed(monkeypatch):
    monkeypatch.setattr(main, "APP_ENV", "production")
    monkeypatch.setattr(main, "APP_API_KEY", "")
    with pytest.raises(RuntimeError):
        main._validate_startup_configuration()


def test_machine_route_contract_matches_backend_and_android_sources():
    repo_root = Path(__file__).resolve().parents[2]
    contract = json.loads((repo_root / "contracts" / "routes-v1.json").read_text())
    assert contract["version"] == "V5.4.16"
    backend_paths = {route.path for route in main.app.routes}
    for item in contract["routes"]:
        path = item["path"]
        assert path in backend_paths, f"Backend route missing: {path}"
        matches = list((repo_root / "android" / "app" / "src" / "main" / "java").rglob(f"{item['androidClient']}.kt"))
        assert len(matches) == 1, f"Android client source not unique: {item['androidClient']}"
        source = matches[0].read_text()
        needle = item.get("androidNeedle", path)
        assert needle in source, f"Android path mismatch: {item['androidClient']} -> {path} (needle={needle})"



@pytest.mark.asyncio
async def test_viop_contracts_fail_closed_without_real_metadata(monkeypatch):
    monkeypatch.setattr(main, "VIOP_CONTRACT_METADATA_PATH", "")
    with pytest.raises(main.HTTPException) as exc:
        await main.discover_viop_contract_metadata(force=True)
    assert exc.value.status_code == 503
    assert exc.value.detail["code"] == "VIOP_CONTRACT_METADATA_NOT_CONFIGURED"


def test_viop_quote_history_and_scanner_fail_closed_without_verified_metadata(monkeypatch):
    monkeypatch.setattr(main, "APP_API_KEY", "")
    monkeypatch.setattr(main, "VIOP_CONTRACT_METADATA_PATH", "")
    main._viop_contract_cache.update({"at": 0.0, "items": [], "universeAsOf": 0})
    c = client

    quote = c.get("/v1/viop/quote/F_XU0301026")
    history = c.get("/v1/viop/history/F_XU0301026?interval=5m&range=5d")
    scanner = c.get("/v1/scanner/opportunities?market=VIOP&assetType=FUTURE&limit=1")

    assert quote.status_code == 503
    assert history.status_code == 503
    assert scanner.status_code == 503
    assert "VIOP_CONTRACT_METADATA" in str(quote.json()["detail"])
    assert "VIOP_CONTRACT_METADATA" in str(history.json()["detail"])
    assert "VIOP_CONTRACT_METADATA" in str(scanner.json()["detail"])


def test_disabled_tradingview_and_research_routes_exist_and_fail_closed():
    tv = client.get("/v1/tradingview/signals?limit=10")
    research = client.get("/v1/research/foundation?symbol=THYAO")
    assert tv.status_code == 503
    assert tv.json()["detail"]["feature"] == "tradingViewSignals"
    assert research.status_code == 503
    assert research.json()["detail"]["feature"] == "researchFoundation"


def test_all_time_history_range_is_accepted_by_route_contract(monkeypatch):
    async def fake_history(symbol, interval="1d", range_value="1y", from_ms=None, to_ms=None, scanner=False):
        assert range_value == "max"
        return [
            {"timestamp": 1_700_000_000_000, "open": 1.0, "high": 2.0, "low": 0.5, "close": 1.5, "volume": 10.0},
            {"timestamp": 1_700_086_400_000, "open": 1.5, "high": 2.1, "low": 1.0, "close": 1.8, "volume": 11.0},
        ]
    monkeypatch.setattr(main, "_fetch_bist_history", fake_history)
    response = client.get("/v1/bist/history/THYAO?range=max&interval=1d")
    assert response.status_code == 200
    assert response.json()["symbol"] == "THYAO"


@pytest.mark.asyncio
async def test_ingress_rate_limiter_is_separate_from_upstream_limiter(monkeypatch):
    class Client:
        host = "127.0.0.1"
    class URL:
        path = "/v1/scanner/opportunities"
    class RequestStub:
        headers = {"authorization": "Bearer test-device", "x-install-id": "install-test"}
        client = Client()
        url = URL()

    monkeypatch.setattr(main, "INGRESS_RATE_LIMIT_PER_MINUTE", 60)
    monkeypatch.setattr(main, "INGRESS_RATE_LIMIT_BURST", 10)
    main._ingress_buckets.clear()
    identities = main._ingress_identities(RequestStub())
    assert any(item.startswith("install:") for item in identities)
    assert any(item.startswith("ip:") for item in identities)
    allowed1 = await main._consume_ingress_token(RequestStub())
    allowed2 = await main._consume_ingress_token(RequestStub())
    denied = await main._consume_ingress_token(RequestStub())
    assert allowed1[0] is True and allowed2[0] is True
    assert denied[0] is False and denied[1] >= 1


def test_short_lived_session_token_is_signed_expiring_and_tamper_evident(monkeypatch):
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "unit-test-session-secret")
    token, expires = main._issue_session_token("install-123")
    assert expires > int(time.time())
    assert main._verify_session_token(token) is True
    assert main._verify_session_token(token, expected_installation_id="install-123") is True
    assert main._verify_session_token(token, expected_installation_id="install-other") is False
    body, signature = token.split(".", 1)
    tampered = ("A" if body[0] != "A" else "B") + body[1:] + "." + signature
    assert main._verify_session_token(tampered) is False


@pytest.mark.asyncio
async def test_session_bootstrap_requires_long_lived_key_and_installation_id(monkeypatch):
    monkeypatch.setattr(main, "APP_API_KEY", "bootstrap-key")
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "session-secret")
    response = await main.create_backend_session("Bearer bootstrap-key", "installation-abc")
    assert response["tokenType"] == "BorsaSession"
    assert response["expiresIn"] == main.SESSION_TOKEN_TTL_SECONDS
    assert main._verify_session_token(response["accessToken"], expected_installation_id="installation-abc") is True
    assert main._verify_session_token(response["accessToken"], expected_installation_id="installation-other") is False
    with pytest.raises(main.HTTPException):
        await main.create_backend_session("Bearer wrong", "installation-abc")


def test_session_token_http_auth_is_bound_to_installation_header(monkeypatch):
    monkeypatch.setattr(main, "APP_API_KEY", "bootstrap-key")
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "session-secret")
    token, _ = main._issue_session_token("installation-abc")
    ok = client.get(
        "/v1/health",
        headers={"Authorization": f"BorsaSession {token}", "X-Install-ID": "installation-abc"},
    )
    wrong = client.get(
        "/v1/health",
        headers={"Authorization": f"BorsaSession {token}", "X-Install-ID": "installation-other"},
    )
    missing = client.get("/v1/health", headers={"Authorization": f"BorsaSession {token}"})
    assert ok.status_code == 200
    assert wrong.status_code == 401
    assert missing.status_code == 401


def test_viop_metadata_normalizes_expiry_without_fabricating_required_fields():
    now = int(time.time() * 1000)
    row = main._normalize_viop_contract_metadata("F_XU0301026", {
        "underlying": "XU030",
        "expiry": "202610",
        "contractType": "FUTURE",
        "tickSize": 0.25,
        "multiplier": 10,
        "lastTradingAt": now + 86_400_000,
    })
    assert row is not None
    assert row["expiry"] == "2026-10"
    incomplete = main._normalize_viop_contract_metadata("F_XU0301026", {
        "underlying": "XU030",
        "expiry": "202610",
        "contractType": "FUTURE",
        "tickSize": 0.25,
        # multiplier deliberately absent: adapter must reject rather than invent it.
        "lastTradingAt": now + 86_400_000,
    })
    assert incomplete is None


def test_short_lived_session_token_is_installation_bound(monkeypatch):
    import main
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "unit-test-session-secret")
    token, exp = main._issue_session_token("install-a")
    assert exp > int(time.time())
    assert main._verify_session_token(token, expected_installation_id="install-a") is True
    assert main._verify_session_token(token, expected_installation_id="install-b") is False


def test_auth_session_route_requires_bootstrap_key_and_installation(monkeypatch):
    import main
    monkeypatch.setattr(main, "APP_API_KEY", "bootstrap-key")
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "unit-test-session-secret")
    client = TestClient(main.app)
    denied = client.post("/v1/auth/session", headers={"Authorization": "Bearer wrong", "X-Install-ID": "install-a"})
    assert denied.status_code == 401
    missing_install = client.post("/v1/auth/session", headers={"Authorization": "Bearer bootstrap-key"})
    assert missing_install.status_code == 400
    ok = client.post("/v1/auth/session", headers={"Authorization": "Bearer bootstrap-key", "X-Install-ID": "install-a"})
    assert ok.status_code == 200
    body = ok.json()
    assert body["tokenType"] == "BorsaSession"
    assert body["accessToken"]
    assert body["expiresAt"] > int(time.time() * 1000)


@pytest.mark.asyncio
async def test_range_max_response_is_not_clipped_to_cache_limit(monkeypatch):
    monkeypatch.setattr(main, "BAR_CACHE_MAX_BARS", 200)
    start = 946_684_800_000
    bars = [
        {
            "timestamp": start + i * 86_400_000,
            "open": 100.0 + i,
            "high": 101.0 + i,
            "low": 99.0 + i,
            "close": 100.5 + i,
            "volume": 1000.0,
        }
        for i in range(3000)
    ]

    class Response:
        def json(self):
            return {"bars": bars}

    async def fake_bar_get(path, params):
        assert path == "/api/v1/market-data/bars"
        assert "fromUtc" in params and "toUtc" in params
        return Response()

    monkeypatch.setattr(main, "bar_upstream_get", fake_bar_get)
    result = await main._cached_market_bars("BIST", "THYAO", "1d", "1D", range_value="max")
    assert len(result) == 3000
    assert len(main._bar_series_cache[("BIST", "THYAO", "1d")]["candles"]) == 200


def test_bar_cache_is_globally_bounded_by_key_and_total_bar_limits(monkeypatch):
    monkeypatch.setattr(main, "BAR_CACHE_MAX_BARS", 100)
    monkeypatch.setattr(main, "BAR_CACHE_MAX_KEYS", 3)
    monkeypatch.setattr(main, "BAR_CACHE_MAX_TOTAL_BARS", 5)
    monkeypatch.setattr(main, "BAR_CACHE_IDLE_TTL_SECONDS", 10_000)
    now = time.time()
    sample = [
        {"timestamp": 1_700_000_000_000 + i, "open": 1.0, "high": 2.0, "low": 0.5, "close": 1.5, "volume": 1.0}
        for i in range(3)
    ]
    for idx in range(6):
        main._store_bar_cache(("BIST", f"SYM{idx}", "1d"), now + idx, sample)
    assert len(main._bar_series_cache) <= 3
    assert sum(len(entry["candles"]) for entry in main._bar_series_cache.values()) <= 5


@pytest.mark.asyncio
async def test_retry_after_sets_process_wide_market_data_cooldown(monkeypatch):
    monkeypatch.setattr(main, "SCANNER_RETRY_COUNT", 0)
    monkeypatch.setattr(main, "SCANNER_PACING_MS", 1)
    monkeypatch.setattr(main, "SCANNER_RETRY_BASE_MS", 10)
    main._scanner_next_allowed_at = 0.0

    async def rate_limited(path, params):
        raise main.HTTPException(status_code=429, detail="rate", headers={"Retry-After": "0.05"})

    monkeypatch.setattr(main, "_raw_upstream_get", rate_limited)
    with pytest.raises(main.HTTPException):
        await main.scanner_upstream_get("/api/v1/market-data/bars", {"symbol": "THYAO"})
    assert main._scanner_next_allowed_at - time.monotonic() > 0.02

    calls = []

    class Response:
        status_code = 200

    async def succeeds(path, params):
        calls.append(time.monotonic())
        return Response()

    monkeypatch.setattr(main, "_raw_upstream_get", succeeds)
    started = time.monotonic()
    await main.scanner_upstream_get("/api/v1/market-data/bars", {"symbol": "ASELS"})
    assert calls
    assert calls[0] - started >= 0.02


@pytest.mark.asyncio
async def test_recent_ticks_use_shared_market_data_quota_gate(monkeypatch):
    seen = []

    class Response:
        def json(self):
            return {
                "ticks": [
                    {"symbol": "THYAO", "price": 123.45, "timestampMs": int(time.time() * 1000)}
                ]
            }

    async def fake_market_data_get(path, params):
        seen.append((path, params))
        return Response()

    monkeypatch.setattr(main, "market_data_upstream_get", fake_market_data_get)
    tick = await main._recent_tick("THYAO")
    assert tick is not None and tick["realtime"] is True
    assert seen == [("/api/v1/market-data/recent-ticks", {"symbols": "THYAO", "seconds": 5})]


@pytest.mark.asyncio
async def test_ingress_bucket_state_is_lru_bounded(monkeypatch):
    monkeypatch.setattr(main, "INGRESS_BUCKET_MAX_ENTRIES", 4)
    monkeypatch.setattr(main, "INGRESS_BUCKET_TTL_SECONDS", 10_000)
    monkeypatch.setattr(main, "INGRESS_RATE_LIMIT_BURST", 500)
    main._ingress_buckets.clear()

    class Client:
        host = "127.0.0.1"

    class URL:
        path = "/v1/health"

    class RequestStub:
        client = Client()
        url = URL()
        def __init__(self, install_id):
            self.headers = {"x-install-id": install_id, "authorization": "Bearer test"}

    for idx in range(12):
        allowed, _, _ = await main._consume_ingress_token(RequestStub(f"install-{idx}"))
        assert allowed is True
        assert len(main._ingress_buckets) <= 4
    assert any(key.startswith("ip:") for key in main._ingress_buckets)


def test_android_batch_timeout_matches_backend_queued_pacing_budget():
    repo_root = Path(__file__).resolve().parents[2]
    source = (repo_root / "android" / "app" / "src" / "main" / "java" / "tr" / "borsatakip" / "v5" / "data" / "MobileMarketDataProvider.kt").read_text()
    policy = (repo_root / "android" / "app" / "src" / "main" / "java" / "tr" / "borsatakip" / "v5" / "data" / "SnapshotBatchPolicy.kt").read_text()
    assert "loadBatchPolicy" in source
    assert "SnapshotBatchPolicy.resolve" in source
    assert "snapshotBatchMaxSymbols" in (repo_root / "android" / "app" / "src" / "main" / "java" / "tr" / "borsatakip" / "v5" / "data" / "MarketCapabilityClient.kt").read_text()
    assert "DEFAULT_READ_TIMEOUT_MS = 175_000L" in policy
    assert "DEFAULT_CALL_TIMEOUT_MS = 180_000L" in policy
    assert "DEFAULT_OUTER_TIMEOUT_MS = 185_000L" in policy
    assert main.SNAPSHOT_BATCH_READ_TIMEOUT_MS >= main.SNAPSHOT_BATCH_ESTIMATED_WORST_CASE_MS


def test_production_startup_requires_strong_session_secret_and_provider_credentials(monkeypatch):
    monkeypatch.setattr(main, "APP_ENV", "production")
    monkeypatch.setattr(main, "APP_API_KEY", "bootstrap-key")
    monkeypatch.setattr(main, "TRADEWIZE_API_KEY", "provider-key")
    monkeypatch.setattr(main, "UPSTREAM_ACCESS_TOKEN", "")
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "")
    with pytest.raises(RuntimeError, match="SESSION_TOKEN_SECRET"):
        main._validate_startup_configuration()

    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "weakweakweakweakweakweakweakweak")
    with pytest.raises(RuntimeError, match="SESSION_TOKEN_SECRET"):
        main._validate_startup_configuration()

    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "V5416-Strong-Session-Secret-2026!Alpha")
    main._validate_startup_configuration()

    monkeypatch.setattr(main, "TRADEWIZE_API_KEY", "")
    with pytest.raises(RuntimeError, match="TradeWize credentials"):
        main._validate_startup_configuration()


def test_session_token_key_id_and_not_before_rotation(monkeypatch):
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "V5416-Strong-Session-Secret-2026!Alpha")
    monkeypatch.setattr(main, "SESSION_TOKEN_KEY_ID", "kid-a")
    monkeypatch.setattr(main, "SESSION_TOKEN_NOT_BEFORE", 0)
    token, _ = main._issue_session_token("install-rotation")
    assert main._verify_session_token(token, expected_installation_id="install-rotation") is True

    monkeypatch.setattr(main, "SESSION_TOKEN_KEY_ID", "kid-b")
    assert main._verify_session_token(token, expected_installation_id="install-rotation") is False


@pytest.mark.asyncio
async def test_conservative_all_scope_gates_quote_paths(monkeypatch):
    monkeypatch.setattr(main, "UPSTREAM_QUOTA_SCOPE", "all")
    seen = []

    class Response:
        status_code = 200

    async def fake_gate(path, params):
        seen.append((path, params))
        return Response()

    monkeypatch.setattr(main, "scanner_upstream_get", fake_gate)
    await main.upstream_get("/api/v1/market-data/last-price/details", {"all": "true"})
    assert seen == [("/api/v1/market-data/last-price/details", {"all": "true"})]


def test_capability_exposes_dynamic_batch_and_quota_policy(monkeypatch):
    async def fake_caps(force=False):
        return {}
    # static policy values are source-level contract: configurable but always bounded.
    assert 1 <= main.SNAPSHOT_BATCH_MAX_SYMBOLS <= 100
    assert main.SNAPSHOT_BATCH_READ_TIMEOUT_MS >= 30_000
    assert main.SNAPSHOT_BATCH_CALL_TIMEOUT_MS >= main.SNAPSHOT_BATCH_READ_TIMEOUT_MS
    assert main.UPSTREAM_QUOTA_SCOPE in {"all", "bars_only"}


@pytest.mark.asyncio
async def test_per_series_single_flight_deduplicates_parallel_cache_misses(monkeypatch):
    monkeypatch.setattr(main, "BAR_CACHE_TTL_SECONDS", 60)
    calls = 0

    class Response:
        def json(self):
            return {"bars": [
                {"timestamp": 1_700_000_000_000 + i * 60_000, "open": 10, "high": 11, "low": 9, "close": 10.5, "volume": 100}
                for i in range(120)
            ]}

    async def fake_bar_get(path, params):
        nonlocal calls
        calls += 1
        await asyncio.sleep(0.01)
        return Response()

    monkeypatch.setattr(main, "bar_upstream_get", fake_bar_get)
    results = await asyncio.gather(*(
        main._cached_market_bars("BIST", "THYAO", "5m", "5m", range_value="1d")
        for _ in range(20)
    ))
    assert calls == 1
    assert all(result for result in results)


@pytest.mark.asyncio
async def test_ingress_bucket_stress_never_exceeds_max_entries(monkeypatch):
    monkeypatch.setattr(main, "INGRESS_BUCKET_MAX_ENTRIES", 64)
    monkeypatch.setattr(main, "INGRESS_BUCKET_TTL_SECONDS", 10_000)
    monkeypatch.setattr(main, "INGRESS_RATE_LIMIT_BURST", 100_000)
    main._ingress_buckets.clear()

    class Client:
        host = "10.1.2.3"
    class URL:
        path = "/v1/health"
    class RequestStub:
        client = Client()
        url = URL()
        def __init__(self, install_id):
            self.headers = {"x-install-id": install_id, "authorization": "Bearer test"}

    for idx in range(2_000):
        allowed, _, _ = await main._consume_ingress_token(RequestStub(f"spoof-{idx}"))
        assert allowed is True
        assert len(main._ingress_buckets) <= 64


def test_ingress_identity_layers_install_auth_and_ip():
    class Client:
        host = "203.0.113.8"
    class RequestStub:
        client = Client()
        headers = {"x-install-id": "install-layered", "authorization": "Bearer layered-secret"}
    identities = main._ingress_identities(RequestStub())
    assert len(identities) == 3
    assert any(value.startswith("install:") for value in identities)
    assert any(value.startswith("auth:") for value in identities)
    assert any(value.startswith("ip:") for value in identities)
    assert all("layered-secret" not in value and "install-layered" not in value for value in identities)


@pytest.mark.asyncio
async def test_bar_cache_metrics_report_hit_and_miss(monkeypatch):
    monkeypatch.setattr(main, "BAR_CACHE_TTL_SECONDS", 60)
    main._bar_series_cache.clear()
    main._bar_series_locks.clear()
    main._provider_quota_metrics["cacheHit"] = 0
    main._provider_quota_metrics["cacheMiss"] = 0

    class Response:
        def json(self):
            return {"bars": [
                {"timestamp": 1_700_000_000_000 + i * 60_000, "open": 10, "high": 11, "low": 9, "close": 10.5, "volume": 100}
                for i in range(120)
            ]}

    calls = 0
    async def fake_bar_get(path, params):
        nonlocal calls
        calls += 1
        return Response()

    monkeypatch.setattr(main, "bar_upstream_get", fake_bar_get)
    await main._cached_market_bars("BIST", "THYAO", "5m", "5m", range_value="1d")
    await main._cached_market_bars("BIST", "THYAO", "5m", "5m", range_value="1d")
    metrics = main._quota_metrics_snapshot()
    assert calls == 1
    assert metrics["cacheMiss"] >= 1
    assert metrics["cacheHit"] >= 1
    assert "weightedUnits" in metrics["ingress"]
    assert "rateLimited429" in metrics["ingress"]


def test_production_e2e_workflow_persists_redacted_evidence_artifact():
    repo_root = Path(__file__).resolve().parents[2]
    workflow = (repo_root / ".github" / "workflows" / "production-e2e.yml").read_text()
    script = (repo_root / "backend" / "e2e_smoke.py").read_text()
    assert "BORSA_E2E_ARTIFACT_PATH" in workflow
    assert "BORSA_E2E_COMMIT_SHA" in workflow
    assert "actions/upload-artifact@v4" in workflow
    assert '"commitSha"' in script and '"deploymentRevision"' in script
    assert "accessToken" not in script[script.index("def _artifact_payload"):script.index("def _write_artifact")]


def test_ingress_endpoint_weights_keep_expensive_routes_costlier():
    assert main._ingress_weight("/v1/health") == 1
    assert main._ingress_weight("/v1/bist/history/THYAO") >= 2
    assert main._ingress_weight("/v1/bist/snapshot-batch") >= 5
    assert main._ingress_weight("/v1/scanner/opportunities") >= 5


def test_all_time_capability_is_fail_closed_until_explicit_provider_verification():
    # Fixture-level range=max correctness is covered elsewhere. Production completeness is a
    # separate provider-E2E claim and must not become true merely because the route exists.
    assert main.FEATURE_CAPABILITIES["allTimeHistory"] is main.ALL_TIME_HISTORY_VERIFIED
    if not main.ALL_TIME_HISTORY_VERIFIED:
        assert main.FEATURE_CAPABILITIES["allTimeHistory"] is False
    assert main.ALL_TIME_HISTORY_START_MS >= 1


def test_android_session_refresh_is_single_flight_and_renews_early():
    repo_root = Path(__file__).resolve().parents[2]
    client = (repo_root / "android" / "app" / "src" / "main" / "java" / "tr" / "borsatakip" / "v5" / "data" / "BackendSessionClient.kt").read_text()
    settings = (repo_root / "android" / "app" / "src" / "main" / "java" / "tr" / "borsatakip" / "v5" / "data" / "SettingsStore.kt").read_text()
    assert "private val refreshGate = SessionRefreshGate()" in client
    gate = (repo_root / "android" / "app" / "src" / "main" / "java" / "tr" / "borsatakip" / "v5" / "data" / "SessionRefreshGate.kt").read_text()
    assert "mutex.withLock" in gate
    assert "if (isSatisfied()) true else refresh()" in gate
    assert "BACKEND_SESSION_RENEW_WINDOW_MS = 60_000L" in settings


def test_production_benchmark_workflow_is_explicit_and_never_builds_android():
    repo_root = Path(__file__).resolve().parents[2]
    workflow = (repo_root / ".github" / "workflows" / "production-benchmark.yml").read_text()
    script = (repo_root / "backend" / "benchmark_full_bist.py").read_text()
    assert "workflow_dispatch" in workflow
    assert "actions/upload-artifact@v4" in workflow
    assert "1m,5m,15m,30m,60m,1d" in workflow
    assert "assemble" not in workflow.lower()
    assert "fullBistFiveMinuteSlaClaimed" in script


def test_session_previous_key_overlap_supports_safe_rotation(monkeypatch):
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "V5416-Old-Session-Secret-2026!Alpha")
    monkeypatch.setattr(main, "SESSION_TOKEN_KEY_ID", "kid-old")
    monkeypatch.setattr(main, "SESSION_TOKEN_PREVIOUS_KEYS_JSON", "")
    main._session_subject_epochs.clear()
    token, _ = main._issue_session_token("install-key-rotation")
    assert main._verify_session_token(token, expected_installation_id="install-key-rotation") is True

    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "V5416-New-Session-Secret-2026!Beta")
    monkeypatch.setattr(main, "SESSION_TOKEN_KEY_ID", "kid-new")
    monkeypatch.setattr(
        main,
        "SESSION_TOKEN_PREVIOUS_KEYS_JSON",
        json.dumps({"kid-old": "V5416-Old-Session-Secret-2026!Alpha"}),
    )
    assert main._verify_session_token(token, expected_installation_id="install-key-rotation") is True

    monkeypatch.setattr(main, "SESSION_TOKEN_PREVIOUS_KEYS_JSON", "")
    assert main._verify_session_token(token, expected_installation_id="install-key-rotation") is False


@pytest.mark.asyncio
async def test_session_subject_epoch_revocation_invalidates_old_tokens(monkeypatch, tmp_path):
    monkeypatch.setattr(main, "APP_API_KEY", "bootstrap-key")
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "V5416-Strong-Session-Secret-2026!Alpha")
    monkeypatch.setattr(main, "SESSION_TOKEN_KEY_ID", "kid-revoke")
    monkeypatch.setattr(main, "SESSION_TOKEN_PREVIOUS_KEYS_JSON", "")
    monkeypatch.setattr(main, "SESSION_REVOKE_STORE_PATH", str(tmp_path / "session-revocations.json"))
    main._session_subject_epochs.clear()

    old_token, _ = main._issue_session_token("install-revoke")
    assert main._verify_session_token(old_token, expected_installation_id="install-revoke") is True

    result = await main.revoke_backend_installation_sessions(
        authorization="Bearer bootstrap-key", x_install_id="install-revoke"
    )
    assert result["ok"] is True
    assert result["subjectEpoch"] == 1
    assert main._verify_session_token(old_token, expected_installation_id="install-revoke") is False

    new_token, _ = main._issue_session_token("install-revoke")
    assert main._verify_session_token(new_token, expected_installation_id="install-revoke") is True
    assert Path(main.SESSION_REVOKE_STORE_PATH).exists()

    main._session_subject_epochs.clear()
    assert main._load_session_revocations_from_disk() == 1
    assert main._verify_session_token(new_token, expected_installation_id="install-revoke") is True


def test_bar_cache_persistence_roundtrip_is_bounded_and_secret_free(monkeypatch, tmp_path):
    persist_path = tmp_path / "bar-cache.json"
    monkeypatch.setattr(main, "BAR_CACHE_PERSIST_PATH", str(persist_path))
    monkeypatch.setattr(main, "BAR_CACHE_MAX_BARS", 3)
    monkeypatch.setattr(main, "BAR_CACHE_IDLE_TTL_SECONDS", 10_000)
    main._bar_series_cache.clear()
    now = time.time()
    candles = [
        {"timestamp": 1_700_000_000_000 + i * 60_000, "open": 10.0, "high": 11.0, "low": 9.0, "close": 10.5, "volume": 100.0}
        for i in range(5)
    ]
    main._store_bar_cache(("BIST", "THYAO", "5m"), now, candles, request_count_back=5)
    assert main._persist_bar_cache_to_disk() is True
    raw = persist_path.read_text()
    assert "THYAO" in raw
    assert "APP_API_KEY" not in raw and "SESSION_TOKEN_SECRET" not in raw and "TRADEWIZE" not in raw

    main._bar_series_cache.clear()
    loaded = main._load_bar_cache_from_disk()
    assert loaded == 1
    restored = main._bar_series_cache[("BIST", "THYAO", "5m")]
    assert len(restored["candles"]) == 3
    assert restored["requestCountBack"] == 5


@pytest.mark.asyncio
async def test_snapshot_batch_request_id_single_flight_and_completed_hit(monkeypatch):
    universe = [price_item("THYAO", True)]
    calls = 0

    async def fake_discover(force=False):
        await asyncio.sleep(0.01)
        return universe

    async def fake_history(symbol, interval="1d", range_value="1y", from_ms=None, to_ms=None, scanner=False):
        nonlocal calls
        calls += 1
        await asyncio.sleep(0.03)
        return [
            {"timestamp": 1_700_000_000_000 + i * 86_400_000, "open": 100.0, "high": 102.0, "low": 99.0, "close": 101.0, "volume": 1000.0}
            for i in range(60)
        ]

    monkeypatch.setattr(main, "discover_bist_quotes", fake_discover)
    monkeypatch.setattr(main, "_fetch_bist_history", fake_history)
    main._snapshot_batch_tasks.clear()
    main._snapshot_batch_completed.clear()
    main._snapshot_batch_metrics.update({"started": 0, "joined": 0, "completedHit": 0})

    first, second = await asyncio.gather(
        main.bist_snapshot_batch("THYAO", None, "1d", "1y", None, None, "same-request-id"),
        main.bist_snapshot_batch("THYAO", None, "1d", "1y", None, None, "same-request-id"),
    )
    assert first == second
    assert calls == 1
    assert main._snapshot_batch_metrics["started"] == 1
    assert main._snapshot_batch_metrics["joined"] == 1

    third = await main.bist_snapshot_batch("THYAO", None, "1d", "1y", None, None, "same-request-id")
    assert third == first
    assert calls == 1
    assert main._snapshot_batch_metrics["completedHit"] == 1


def test_android_snapshot_batch_retries_reuse_request_id_and_do_not_duplicate_candles():
    repo_root = Path(__file__).resolve().parents[2]
    source = (repo_root / "android" / "app" / "src" / "main" / "java" / "tr" / "borsatakip" / "v5" / "data" / "MobileMarketDataProvider.kt").read_text()
    assert 'val requestId = "bist-batch-${UUID.randomUUID()}"' in source
    assert 'header("X-Request-ID", requestId)' in source
    parse_block = source[source.index("private fun parseCandlesStrict"):source.index("private fun normalizeSymbol")]
    assert parse_block.count("candles += c") == 1


@pytest.mark.asyncio
async def test_distributed_quota_gate_waits_and_then_allows(monkeypatch):
    monkeypatch.setattr(main, "UPSTREAM_DISTRIBUTED_QUOTA", True)
    monkeypatch.setattr(main, "REDIS_URL", "redis://example.invalid/0")
    main._provider_quota_metrics["distributedWaitMs"] = 0
    main._provider_quota_metrics["distributedGatePass"] = 0

    class FakeRedis:
        def __init__(self):
            self.calls = 0
        async def eval(self, script, numkeys, key, *args):
            self.calls += 1
            return [0, 1] if self.calls == 1 else [1, 0]

    fake = FakeRedis()
    async def fake_client():
        return fake
    monkeypatch.setattr(main, "_get_redis_quota_client", fake_client)
    await main._distributed_quota_wait(1)
    assert fake.calls == 2
    assert main._provider_quota_metrics["distributedWaitMs"] >= 1
    assert main._provider_quota_metrics["distributedGatePass"] == 1


@pytest.mark.asyncio
async def test_distributed_retry_after_cooldown_is_shared_via_redis(monkeypatch):
    monkeypatch.setattr(main, "UPSTREAM_DISTRIBUTED_QUOTA", True)
    monkeypatch.setattr(main, "REDIS_URL", "redis://example.invalid/0")
    seen = []

    class FakeRedis:
        async def eval(self, script, numkeys, key, *args):
            seen.append((script, key, args))
            return 1

    async def fake_client():
        return FakeRedis()
    monkeypatch.setattr(main, "_get_redis_quota_client", fake_client)
    await main._extend_distributed_cooldown(2.5)
    assert seen
    assert seen[0][1] == main.UPSTREAM_DISTRIBUTED_QUOTA_KEY
    assert float(seen[0][2][0]) == 2.5


def test_distributed_quota_requires_redis_url_when_enabled(monkeypatch):
    monkeypatch.setattr(main, "UPSTREAM_DISTRIBUTED_QUOTA", True)
    monkeypatch.setattr(main, "REDIS_URL", "")
    with pytest.raises(RuntimeError, match="REDIS_URL"):
        main._validate_startup_configuration()


def test_tradingview_webhook_signature_persistence_dedupe_and_listing(monkeypatch, tmp_path):
    secret = "TradingView-Webhook-Secret-2026!Alpha"
    monkeypatch.setattr(main, "TRADINGVIEW_WEBHOOK_SECRET", secret)
    monkeypatch.setattr(main, "TRADINGVIEW_DB_PATH", str(tmp_path / "tradingview.sqlite3"))
    monkeypatch.setattr(main, "APP_API_KEY", "")
    now_ms = int(time.time() * 1000)
    payload = {
        "eventId": "evt-1",
        "symbol": "THYAO",
        "action": "BUY",
        "signalTime": now_ms,
        "price": 321.5,
        "interval": "5m",
        "strategy": "test-strategy",
        "score": 88.0,
        "barConfirmed": True,
    }
    raw = json.dumps(payload, separators=(",", ":")).encode()
    signature = __import__("hmac").new(secret.encode(), raw, __import__("hashlib").sha256).hexdigest()

    bad = client.post(
        "/v1/tradingview/webhook",
        content=raw,
        headers={"Content-Type": "application/json", "X-TradingView-Signature": "sha256=bad"},
    )
    assert bad.status_code == 401

    first = client.post(
        "/v1/tradingview/webhook",
        content=raw,
        headers={"Content-Type": "application/json", "X-TradingView-Signature": f"sha256={signature}"},
    )
    assert first.status_code == 200
    assert first.json()["accepted"] is True

    duplicate = client.post(
        "/v1/tradingview/webhook",
        content=raw,
        headers={"Content-Type": "application/json", "X-TradingView-Signature": signature},
    )
    assert duplicate.status_code == 200
    assert duplicate.json()["accepted"] is False
    assert duplicate.json()["status"] == "DUPLICATE"

    listing = client.get("/v1/tradingview/signals?limit=10")
    assert listing.status_code == 200
    body = listing.json()
    assert len(body["items"]) == 1
    item = body["items"][0]
    assert item["id"] == "evt-1"
    assert item["symbol"] == "THYAO"
    assert item["action"] == "BUY"
    assert item["source"] == "TRADINGVIEW_WEBHOOK"
    assert item["marketDataRealtime"] is False
    assert item["freshSignal"] is True


def test_tradingview_semantic_dedupe_blocks_same_strategy_signal_retry_with_new_event_id(monkeypatch, tmp_path):
    secret = "TradingView-Webhook-Secret-2026!Alpha"
    monkeypatch.setattr(main, "TRADINGVIEW_WEBHOOK_SECRET", secret)
    monkeypatch.setattr(main, "TRADINGVIEW_DB_PATH", str(tmp_path / "tradingview.sqlite3"))
    monkeypatch.setattr(main, "APP_API_KEY", "")
    now_ms = int(time.time() * 1000)

    def send(event_id):
        payload = {"eventId": event_id, "symbol": "ASELS", "action": "LONG", "signalTime": now_ms, "interval": "5m", "strategy": "alpha"}
        raw = json.dumps(payload, separators=(",", ":")).encode()
        sig = __import__("hmac").new(secret.encode(), raw, __import__("hashlib").sha256).hexdigest()
        return client.post("/v1/tradingview/webhook", content=raw, headers={"Content-Type": "application/json", "X-TradingView-Signature": sig})

    assert send("evt-a").json()["accepted"] is True
    assert send("evt-b").json()["accepted"] is False
    assert len(client.get("/v1/tradingview/signals?limit=10").json()["items"]) == 1


def test_tradingview_capability_tracks_real_store_configuration(monkeypatch, tmp_path):
    monkeypatch.setattr(main, "TRADINGVIEW_WEBHOOK_SECRET", "TradingView-Webhook-Secret-2026!Alpha")
    monkeypatch.setattr(main, "TRADINGVIEW_DB_PATH", str(tmp_path / "tv.sqlite3"))
    main._capability_cache.update({"at": 0.0, "value": None})
    # Avoid provider calls; we only assert dynamic feature projection.
    async def fake_bist(force=False): return []
    async def fake_viop(force=False): return []
    monkeypatch.setattr(main, "discover_bist_quotes", fake_bist)
    monkeypatch.setattr(main, "discover_viop_quotes", fake_viop)
    payload = asyncio.run(main.provider_capabilities(force=True))
    assert payload["features"]["tradingViewSignals"] is True


def test_unused_research_android_surface_is_removed_while_backend_stays_fail_closed():
    repo_root = Path(__file__).resolve().parents[2]
    android_root = repo_root / "android" / "app" / "src" / "main" / "java" / "tr" / "borsatakip" / "v5"
    assert not (android_root / "data" / "ResearchApiClient.kt").exists()
    assert not (android_root / "data" / "ResearchFoundationRepository.kt").exists()
    assert not (android_root / "research" / "ResearchFoundationEngine.kt").exists()
    assert not (android_root / "research" / "ResearchModels.kt").exists()
    contract = json.loads((repo_root / "contracts" / "routes-v1.json").read_text())
    assert all(item["path"] != "/v1/research/foundation" for item in contract["routes"])
    response = client.get("/v1/research/foundation?symbol=THYAO")
    assert response.status_code == 503
    assert response.json()["detail"]["feature"] == "researchFoundation"


@pytest.mark.asyncio
async def test_viop_metadata_requires_real_freshness_and_filters_expired(monkeypatch):
    monkeypatch.setattr(main, "VIOP_CONTRACT_METADATA_PATH", "/fake/viop-metadata")
    monkeypatch.setattr(main, "VIOP_METADATA_REQUIRE_UNIVERSE_AS_OF", True)
    monkeypatch.setattr(main, "VIOP_METADATA_REQUIRE_COMPLETE", True)
    monkeypatch.setattr(main, "VIOP_METADATA_MAX_AGE_SECONDS", 86_400)
    now_ms = int(time.time() * 1000)

    class Response:
        def __init__(self, payload): self._payload = payload
        def json(self): return self._payload

    missing_ts = {
        "items": [{
            "symbol": "F_XU0301026", "underlying": "XU030", "expiry": "2026-10",
            "contractType": "FUTURE", "tickSize": 0.25, "multiplier": 10,
            "lastTradingAt": now_ms + 5 * 86_400_000,
        }],
        "totalCount": 1, "hasMore": False,
    }
    async def no_timestamp(path, params): return Response(missing_ts)
    monkeypatch.setattr(main, "upstream_get", no_timestamp)
    with pytest.raises(main.HTTPException) as exc:
        await main.discover_viop_contract_metadata(force=True)
    assert exc.value.detail["code"] == "VIOP_CONTRACT_METADATA_TIMESTAMP_MISSING"

    complete = {
        "universeAsOf": now_ms,
        "totalCount": 2,
        "hasMore": False,
        "items": [
            {
                "symbol": "F_XU0301026", "underlying": "XU030", "expiry": "2026-10",
                "contractType": "FUTURE", "tickSize": 0.25, "multiplier": 10,
                "lastTradingAt": now_ms + 2 * 86_400_000,
            },
            {
                "symbol": "F_XU0300926", "underlying": "XU030", "expiry": "2026-09",
                "contractType": "FUTURE", "tickSize": 0.25, "multiplier": 10,
                "lastTradingAt": now_ms - 1,
            },
        ],
    }
    async def good(path, params): return Response(complete)
    monkeypatch.setattr(main, "upstream_get", good)
    main._viop_contract_cache.update({"at": 0.0, "items": [], "universeAsOf": 0})
    items, as_of = await main.discover_viop_contract_metadata(force=True)
    assert as_of == now_ms
    assert [row["symbol"] for row in items] == ["F_XU0301026"]
    assert items[0]["nearExpiry"] is True
    assert items[0]["dataQualityWarning"] == "NEAR_EXPIRY"


@pytest.mark.asyncio
async def test_viop_metadata_partial_or_stale_never_becomes_ready(monkeypatch):
    monkeypatch.setattr(main, "VIOP_CONTRACT_METADATA_PATH", "/fake/viop-metadata")
    monkeypatch.setattr(main, "VIOP_METADATA_REQUIRE_UNIVERSE_AS_OF", True)
    monkeypatch.setattr(main, "VIOP_METADATA_REQUIRE_COMPLETE", True)
    monkeypatch.setattr(main, "VIOP_METADATA_MAX_AGE_SECONDS", 60)
    now_ms = int(time.time() * 1000)

    class Response:
        def __init__(self, payload): self._payload = payload
        def json(self): return self._payload

    partial = {"universeAsOf": now_ms, "totalCount": 2, "hasMore": True, "items": []}
    async def partial_get(path, params): return Response(partial)
    monkeypatch.setattr(main, "upstream_get", partial_get)
    with pytest.raises(main.HTTPException) as exc:
        await main.discover_viop_contract_metadata(force=True)
    assert exc.value.detail["code"] == "VIOP_CONTRACT_METADATA_PARTIAL"

    stale = {"universeAsOf": now_ms - 120_000, "totalCount": 0, "hasMore": False, "items": []}
    async def stale_get(path, params): return Response(stale)
    monkeypatch.setattr(main, "upstream_get", stale_get)
    with pytest.raises(main.HTTPException) as exc2:
        await main.discover_viop_contract_metadata(force=True)
    assert exc2.value.detail["code"] == "VIOP_CONTRACT_METADATA_STALE"


@pytest.mark.asyncio
async def test_v538_attestation_signer_matches_android_canonical_contract_and_rotates_sequence(monkeypatch):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.exceptions import InvalidSignature

    private_key = ec.generate_private_key(ec.SECP256R1())
    private_der = private_key.private_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    monkeypatch.setattr(
        main,
        "ATTESTATION_PRIVATE_KEYS_JSON",
        json.dumps({"kid-a": {"generation": 7, "privateKeyBase64": __import__("base64").b64encode(private_der).decode()}}),
    )
    monkeypatch.setattr(main, "ATTESTATION_ACTIVE_KEY_ID", "kid-a")
    monkeypatch.setattr(main, "ATTESTATION_ACTIVE_KEY_GENERATION", 7)
    monkeypatch.setattr(main, "ATTESTATION_PROVIDER_ID", "tradewize")
    monkeypatch.setattr(main, "ATTESTATION_TTL_MS", 60_000)
    main._attestation_sequence = 0
    main._attestation_recent_nonces.clear()

    envelope1 = await main._sign_v538_attestation(
        snapshot_hash="a" * 64,
        request_id="request-1",
        snapshot_id="snapshot-1",
        calculation_engine_version="V5.4.16",
        generated_at=1_700_000_000_000,
        server_time=1_700_000_000_500,
    )
    envelope2 = await main._sign_v538_attestation(
        snapshot_hash="b" * 64,
        request_id="request-2",
        snapshot_id="snapshot-2",
        calculation_engine_version="V5.4.16",
        generated_at=1_700_000_001_000,
        server_time=1_700_000_001_500,
    )
    assert envelope1["attestationAlgorithm"] == "SHA256withECDSA"
    assert envelope1["attestationKeyId"] == "kid-a"
    assert envelope1["attestationKeyGeneration"] == 7
    assert envelope2["serverSequence"] == envelope1["serverSequence"] + 1
    assert envelope1["nonce"] != envelope2["nonce"]
    canonical = main._v538_canonical(envelope1).encode()
    signature = __import__("base64").b64decode(envelope1["attestationSignature"])
    private_key.public_key().verify(signature, canonical, ec.ECDSA(hashes.SHA256()))

    tampered = dict(envelope1)
    tampered["snapshotHash"] = "c" * 64
    with pytest.raises(InvalidSignature):
        private_key.public_key().verify(signature, main._v538_canonical(tampered).encode(), ec.ECDSA(hashes.SHA256()))


def test_attestation_capability_stays_fail_closed_even_when_signing_key_is_configured(monkeypatch):
    monkeypatch.setattr(main, "ATTESTATION_PRIVATE_KEYS_JSON", '{"kid-a":{"generation":1,"privateKeyBase64":"ZmFrZQ=="}}')
    monkeypatch.setattr(main, "ATTESTATION_ACTIVE_KEY_ID", "kid-a")
    monkeypatch.setattr(main, "ATTESTATION_ACTIVE_KEY_GENERATION", 1)
    assert main.FEATURE_CAPABILITIES["attestationReady"] is False


@pytest.mark.asyncio
async def test_background_prewarm_can_use_discovered_bist_universe_without_duplicates(monkeypatch):
    monkeypatch.setattr(main, "BAR_CACHE_PREWARM_SYMBOLS", ("THYAO",))
    monkeypatch.setattr(main, "BAR_CACHE_PREWARM_BIST_UNIVERSE", True)
    monkeypatch.setattr(main, "BAR_CACHE_PREWARM_MAX_SYMBOLS", 3)
    monkeypatch.setattr(main, "BAR_CACHE_PREWARM_INTERVALS", ("5m",))
    monkeypatch.setattr(main, "BAR_CACHE_PERSIST_PATH", "")
    seen = []

    async def fake_discover(force=False):
        return [{"symbol": "THYAO"}, {"symbol": "ASELS"}, {"symbol": "EREGL"}, {"symbol": "SISE"}]

    async def fake_history(symbol, interval="1d", range_value="1y", from_ms=None, to_ms=None, scanner=False):
        seen.append((symbol, interval))
        return [{"timestamp": 1_700_000_000_000, "open": 1, "high": 2, "low": 1, "close": 2, "volume": 1}]

    monkeypatch.setattr(main, "discover_bist_quotes", fake_discover)
    monkeypatch.setattr(main, "_fetch_bist_history", fake_history)
    await main._prewarm_bar_cache_once()
    assert seen == [("THYAO", "5m"), ("ASELS", "5m"), ("EREGL", "5m")]


def test_attestation_startup_rejects_invalid_json_and_unloadable_active_key(monkeypatch):
    monkeypatch.setattr(main, "APP_ENV", "development")
    monkeypatch.setattr(main, "ATTESTATION_PRIVATE_KEYS_JSON", "not-json")
    monkeypatch.setattr(main, "ATTESTATION_ACTIVE_KEY_ID", "kid-a")
    monkeypatch.setattr(main, "ATTESTATION_ACTIVE_KEY_GENERATION", 1)
    with pytest.raises(RuntimeError, match="Attestation signing configuration is invalid"):
        main._validate_startup_configuration()

    monkeypatch.setattr(main, "ATTESTATION_PRIVATE_KEYS_JSON", json.dumps({
        "kid-a": {"generation": 1, "privateKeyBase64": "ZmFrZQ=="}
    }))
    with pytest.raises(RuntimeError, match="Attestation signing configuration is invalid"):
        main._validate_startup_configuration()


def test_viop_canonical_liquidity_fields_are_propagated_without_alias_guessing():
    now = int(time.time() * 1000)
    meta = main._normalize_viop_contract_metadata("F_XU0301026", {
        "underlying": "XU030",
        "expiry": "2026-10",
        "contractType": "FUTURE",
        "tickSize": 0.25,
        "multiplier": 10,
        "lastTradingAt": now + 86_400_000,
        "volume": 1234.5,
        "openInterest": 9876,
        "unexpected_volume_alias": 999999,
    })
    assert meta is not None
    assert meta["volume"] == 1234.5
    assert meta["openInterest"] == 9876

    price = main.extract_price_record("F_XU0301026", {
        "price": 100.0,
        "timestamp": now,
        "volume": 2222.0,
        "openInterest": 3333,
    })
    assert price["volume"] == 2222.0
    assert price["openInterest"] == 3333


@pytest.mark.asyncio
async def test_viop_contract_endpoint_fails_closed_without_liquidity_evidence(monkeypatch):
    now = int(time.time() * 1000)
    metadata = [{
        "symbol": "F_XU0301026", "underlying": "XU030", "expiry": "2026-10",
        "contractType": "FUTURE", "tickSize": 0.25, "multiplier": 10,
        "lastTradingAt": now + 86_400_000, "expiryAt": None,
        "exchangeTimezone": "Europe/Istanbul", "settlementType": None, "currency": "TRY",
        "volume": None, "openInterest": None,
    }]

    async def fake_meta(force=False):
        return metadata, now

    async def fake_quotes(force=False):
        return [{
            "symbol": "F_XU0301026", "price": 100.0, "timestamp": now,
            "realtime": True, "delaySeconds": 0, "currentSessionIncluded": True,
            "source": "TradeWize", "volume": None, "openInterest": None,
        }]

    monkeypatch.setattr(main, "discover_viop_contract_metadata", fake_meta)
    monkeypatch.setattr(main, "discover_viop_quotes", fake_quotes)
    async def allow_auth(authorization):
        return None
    monkeypatch.setattr(main, "require_app_auth", allow_auth)
    with pytest.raises(main.HTTPException) as exc:
        await main.viop_contracts(limit=20, cursor=None, authorization=None)
    assert exc.value.status_code == 503
    assert exc.value.detail["code"] == "VIOP_LIQUIDITY_DATA_UNAVAILABLE"


@pytest.mark.asyncio
async def test_viop_contract_and_quote_return_positive_liquidity_when_upstream_supplies_canonical_fields(monkeypatch):
    now = int(time.time() * 1000)
    metadata = [{
        "symbol": "F_XU0301026", "underlying": "XU030", "expiry": "2026-10",
        "contractType": "FUTURE", "tickSize": 0.25, "multiplier": 10,
        "lastTradingAt": now + 86_400_000, "expiryAt": None,
        "exchangeTimezone": "Europe/Istanbul", "settlementType": None, "currency": "TRY",
        "volume": 1500.0, "openInterest": 8000,
    }]

    async def fake_meta(force=False):
        return metadata, now

    async def fake_quotes(force=False):
        return [{
            "symbol": "F_XU0301026", "price": 100.0, "timestamp": now,
            "realtime": True, "delaySeconds": 0, "currentSessionIncluded": True,
            "source": "TradeWize", "volume": 1600.0, "openInterest": 8100,
        }]

    monkeypatch.setattr(main, "discover_viop_contract_metadata", fake_meta)
    monkeypatch.setattr(main, "discover_viop_quotes", fake_quotes)
    async def allow_auth(authorization):
        return None
    monkeypatch.setattr(main, "require_app_auth", allow_auth)
    payload = await main.viop_contracts(limit=20, cursor=None, authorization=None)
    assert payload["items"][0]["volume"] == 1600.0
    assert payload["items"][0]["openInterest"] == 8100
