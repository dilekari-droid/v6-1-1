"""Production smoke/E2E verifier for V5.4.16.

Runs only against an explicitly supplied HTTPS backend. It never guesses credentials or provider
metadata. Example:
  BORSA_E2E_BASE_URL=https://... BORSA_E2E_API_KEY=... python backend/e2e_smoke.py
"""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from dataclasses import dataclass
from typing import Any

import httpx

BASE = os.getenv("BORSA_E2E_BASE_URL", "").strip().rstrip("/")
KEY = os.getenv("BORSA_E2E_API_KEY", "").strip()
INSTALL = os.getenv("BORSA_E2E_INSTALL_ID", "ci-e2e-v611").strip()
TIMEOUT = float(os.getenv("BORSA_E2E_TIMEOUT_SECONDS", "30"))
ARTIFACT_PATH = os.getenv("BORSA_E2E_ARTIFACT_PATH", "").strip()
COMMIT_SHA = os.getenv("BORSA_E2E_COMMIT_SHA", os.getenv("GITHUB_SHA", "")).strip()
DEPLOYMENT_REVISION = os.getenv("BORSA_E2E_DEPLOYMENT_REVISION", "").strip()
EXPECTED_REVISION = os.getenv("BORSA_E2E_EXPECTED_REVISION", COMMIT_SHA).strip()
REQUIRE_OPEN_SESSION_FRESHNESS = os.getenv("BORSA_E2E_REQUIRE_OPEN_SESSION_FRESHNESS", "0").strip().lower() in {"1", "true", "yes", "on"}


@dataclass
class Check:
    name: str
    ok: bool
    detail: str


def require_config() -> None:
    if not BASE.startswith("https://"):
        raise SystemExit("BORSA_E2E_BASE_URL must be an explicit HTTPS URL")
    if not KEY:
        raise SystemExit("BORSA_E2E_API_KEY is required")


def _artifact_payload(
    checks: list[Check],
    *,
    started_at: str,
    elapsed_ms: int,
    error: str | None = None,
    deployment_revision: str = "",
    failure_stage: str = "",
    quota_metrics: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "schemaVersion": 2,
        "appVersion": "5.4.16",
        "startedAt": started_at,
        "finishedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "elapsedMs": elapsed_ms,
        "commitSha": COMMIT_SHA or None,
        "deploymentRevision": deployment_revision or DEPLOYMENT_REVISION or None,
        "backendOrigin": BASE,
        "result": "PASS" if error is None and all(item.ok for item in checks) else "FAIL",
        "error": error,
        "failureStage": failure_stage or None,
        "checks": [{"name": item.name, "ok": item.ok, "detail": item.detail} for item in checks],
        # Metrics are numeric/config-state only. No auth/session/provider secret is serialized.
        "quotaMetrics": quota_metrics or {},
    }


def _write_artifact(payload: dict[str, Any]) -> None:
    if not ARTIFACT_PATH:
        return
    target = Path(ARTIFACT_PATH)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _deployment_revision_from_health(payload: dict[str, Any], headers: httpx.Headers) -> str:
    for key in ("deploymentRevision", "revision", "gitSha", "commitSha"):
        value = str(payload.get(key) or "").strip()
        if value:
            return value[:128]
    for key in ("x-deployment-revision", "x-render-git-commit", "x-railway-deployment-id"):
        value = str(headers.get(key) or "").strip()
        if value:
            return value[:128]
    return ""


def main() -> int:
    checks: list[Check] = []
    started_monotonic = time.monotonic()
    started_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    deployment_revision = ""
    error: str | None = None
    failure_stage = "config"
    quota_metrics: dict[str, Any] = {}
    headers = {"Authorization": f"Bearer {KEY}", "X-Install-ID": INSTALL, "Accept": "application/json"}
    try:
        require_config()
        failure_stage = "health"
        with httpx.Client(base_url=BASE, headers=headers, timeout=TIMEOUT, follow_redirects=False) as client:
            def get(path: str, **params: Any) -> httpx.Response:
                response = client.get(path, params=params or None)
                retry = response.headers.get("Retry-After")
                if response.status_code == 429:
                    raise AssertionError(f"{path} ingress/upstream rate limited during smoke test; Retry-After={retry}")
                return response

            health = get("/v1/health")
            health.raise_for_status()
            health_json = health.json()
            deployment_revision = _deployment_revision_from_health(health_json, health.headers)
            checks.append(Check("health", health_json.get("ok") is True, f"HTTP {health.status_code}"))
            if EXPECTED_REVISION:
                same_revision = bool(deployment_revision) and deployment_revision == EXPECTED_REVISION
                checks.append(Check(
                    "same-source-revision",
                    same_revision,
                    f"expected={EXPECTED_REVISION[:12]} observed={(deployment_revision or 'missing')[:12]}",
                ))
                if not same_revision:
                    raise AssertionError("production deployment revision does not match expected source revision")

            failure_stage = "capabilities"
            caps_r = get("/v1/provider/capabilities", force="true")
            caps_r.raise_for_status()
            caps = caps_r.json()
            features = caps.get("features", {})
            quota_metrics = caps.get("quotaMetrics") if isinstance(caps.get("quotaMetrics"), dict) else {}
            if quota_metrics:
                checks.append(Check(
                    "quota-metrics-observable",
                    all(key in quota_metrics for key in ("accepted", "delayed", "rateLimited", "retries", "cooldownExtensions", "waitMs", "requestWeight", "cacheHit", "cacheMiss")),
                    "numeric limiter/cache metrics present",
                ))
            checks.append(Check("capabilities", caps.get("ok") is True, f"version={caps.get('version')}"))
            provider_configured = caps.get("providerConfigured") is True
            checks.append(Check("provider-configured", provider_configured, "configured" if provider_configured else "TradeWize credential not configured on target"))
            if not provider_configured:
                raise AssertionError("target backend has no configured TradeWize credential")

            # Bootstrap-key -> short-lived BorsaSession -> protected request. The raw token is
            # intentionally never printed or persisted in the evidence artifact.
            if features.get("shortLivedSessionAuth") is True:
                session_r = client.post("/v1/auth/session", json={})
                session_r.raise_for_status()
                session_json = session_r.json()
                session_token = str(session_json.get("accessToken") or "").strip()
                expires_at = int(session_json.get("expiresAt", 0) or 0)
                if not session_token or expires_at <= int(time.time() * 1000) + 30_000:
                    raise AssertionError("short-lived session bootstrap returned invalid token/expiry")
                with httpx.Client(
                    base_url=BASE,
                    headers={"Authorization": f"BorsaSession {session_token}", "X-Install-ID": INSTALL, "Accept": "application/json"},
                    timeout=TIMEOUT,
                    follow_redirects=False,
                ) as session_client:
                    session_health = session_client.get("/v1/health")
                    session_health.raise_for_status()
                    checks.append(Check("session-bootstrap-protected-call", session_health.json().get("ok") is True, "BorsaSession accepted"))
                    wrong_install = session_client.get("/v1/health", headers={"X-Install-ID": INSTALL + "-wrong"})
                    checks.append(Check("session-install-binding", wrong_install.status_code == 401, f"HTTP {wrong_install.status_code}"))
                    if features.get("sessionRevocation") is True:
                        revoke_r = client.post("/v1/auth/revoke-installation")
                        revoke_r.raise_for_status()
                        revoked_call = session_client.get("/v1/health")
                        checks.append(Check("session-subject-epoch-revoke", revoked_call.status_code == 401, f"HTTP {revoked_call.status_code}"))
                        remint_r = client.post("/v1/auth/session", json={})
                        remint_r.raise_for_status()
                        remint_token = str(remint_r.json().get("accessToken") or "").strip()
                        if not remint_token:
                            raise AssertionError("session remint after revoke did not return a token")
                        with httpx.Client(
                            base_url=BASE,
                            headers={"Authorization": f"BorsaSession {remint_token}", "X-Install-ID": INSTALL, "Accept": "application/json"},
                            timeout=TIMEOUT,
                            follow_redirects=False,
                        ) as remint_client:
                            remint_health = remint_client.get("/v1/health")
                            checks.append(Check("session-remint-after-revoke", remint_health.status_code == 200, f"HTTP {remint_health.status_code}"))

            failure_stage = "preflight"
            pre_r = get("/v1/preflight")
            pre_r.raise_for_status()
            pre = pre_r.json()
            auth_ok = pre.get("authentication", {}).get("ok") is True
            checks.append(Check("preflight-authentication", auth_ok, str(pre.get("authentication", {}).get("message") or "")))
            symbols_ok = pre.get("symbols", {}).get("ok") is True
            checks.append(Check("preflight-symbols", symbols_ok, f"count={pre.get('symbolCount')}"))
            sample = str(pre.get("sampleSymbol") or "").strip().upper()
            checks.append(Check("preflight-sample-symbol", bool(sample), sample or "missing"))
            if not auth_ok:
                raise AssertionError("preflight authentication is not configured/ready")
            if not sample:
                raise AssertionError("preflight did not provide sampleSymbol")

            failure_stage = "bist-market-data"
            quote_r = get(f"/v1/bist/quote/{sample}")
            quote_r.raise_for_status()
            quote = quote_r.json()
            checks.append(Check("bist-quote", int(quote.get("exchangeTimestamp", 0)) > 0, sample))

            history_r = get(f"/v1/bist/history/{sample}", range="1y", interval="1d")
            history_r.raise_for_status()
            history = history_r.json()
            checks.append(Check("bist-history-1d", bool(history.get("candles")), f"bars={len(history.get('candles') or [])}"))

            intraday_r = get(f"/v1/bist/history/{sample}", range="5d", interval="5m")
            intraday_r.raise_for_status()
            checks.append(Check("bist-history-5m", bool(intraday_r.json().get("candles")), "5m"))

            max_r = get(f"/v1/bist/history/{sample}", range="max", interval="1d")
            max_r.raise_for_status()
            checks.append(Check("bist-history-max", bool(max_r.json().get("candles")), f"bars={len(max_r.json().get('candles') or [])}"))

            batch_symbols = [sample]
            symbols_r = get("/v1/bist/symbols")
            symbols_r.raise_for_status()
            for symbol in symbols_r.json().get("items", []):
                sym = str(symbol).strip().upper()
                if sym and sym not in batch_symbols:
                    batch_symbols.append(sym)
                if len(batch_symbols) >= 5:
                    break
            batch_r = get("/v1/bist/snapshot-batch", symbols=",".join(batch_symbols), interval="5m", range="5d")
            batch_r.raise_for_status()
            batch = batch_r.json()
            items = batch.get("items") or []
            if len(items) != len(batch_symbols):
                raise AssertionError(f"snapshot-batch cardinality mismatch: {len(items)} != {len(batch_symbols)}")
            if [str(row.get("symbol") or "").upper() for row in items] != batch_symbols:
                raise AssertionError("snapshot-batch order mismatch")
            checks.append(Check("snapshot-batch-cardinality-order", True, f"rows={len(items)}"))

            preflight_realtime = str(pre.get("analysisMode") or "").upper() == "REALTIME" and pre.get("quote", {}).get("ok") is True
            now_ms = int(time.time() * 1000)
            max_age_ms = int(os.getenv("BORSA_E2E_MAX_QUOTE_AGE_MS", "15000"))
            preflight_ts = int((pre.get("quote") or {}).get("exchangeTimestamp", 0) or 0)
            single_ts = int(quote.get("exchangeTimestamp", 0) or 0)
            if REQUIRE_OPEN_SESSION_FRESHNESS and not preflight_realtime:
                raise AssertionError("open-session freshness required but preflight is not REALTIME")
            if preflight_realtime:
                stale: list[str] = []
                if preflight_ts <= 0 or now_ms - preflight_ts > max_age_ms:
                    stale.append("preflight")
                single_live = quote.get("realtime") is True and quote.get("currentSessionIncluded") is True
                if not single_live or single_ts <= 0 or now_ms - single_ts > max_age_ms:
                    stale.append(f"quote:{sample}")
                for row in items:
                    q = row.get("quote") or {}
                    ts = int(q.get("exchangeTimestamp", 0) or 0)
                    realtime = q.get("realtime") is True and q.get("currentSessionIncluded") is True
                    if not realtime or ts <= 0 or now_ms - ts > max_age_ms:
                        stale.append(f"batch:{row.get('symbol')}")
                if stale:
                    raise AssertionError("preflight REALTIME freshness parity failed: " + ",".join(stale))
                checks.append(Check(
                    "open-session-freshness-parity",
                    True,
                    f"preflightAgeMs={now_ms-preflight_ts};quoteAgeMs={now_ms-single_ts};rows={len(items)};maxAgeMs={max_age_ms}",
                ))
            else:
                checks.append(Check("open-session-freshness-parity", True, "preflight not realtime; open-session claim not asserted"))

            failure_stage = "dynamic-scanner"
            scanner_r = get("/v1/scanner/opportunities", market="BIST", assetType="STOCK", timeframe="5m", symbols=",".join(batch_symbols), limit=len(batch_symbols))
            scanner_r.raise_for_status()
            checks.append(Check("dynamic-scanner", scanner_r.json().get("success") is True, f"HTTP {scanner_r.status_code}"))

            failure_stage = "optional-capabilities"
            viop_ready = features.get("viopContractsReady") is True
            viop_r = get("/v1/viop/contracts", limit=20)
            if viop_ready:
                viop_r.raise_for_status()
                universe = viop_r.json()
                contracts = universe.get("items") or []
                if not contracts:
                    raise AssertionError("viopContractsReady=true but contract universe is empty")
                required = ("symbol", "underlying", "expiry", "tickSize", "multiplier", "lastTradingAt")
                usable = []
                for contract in contracts:
                    missing = [key for key in required if contract.get(key) in (None, "")]
                    if str(contract.get("contractType")).upper() != "FUTURE" or missing:
                        raise AssertionError(f"invalid VIOP contract metadata: missing={missing} type={contract.get('contractType')}")
                    try:
                        volume = float(contract.get("volume") or 0)
                        oi = int(contract.get("openInterest") or 0)
                    except (TypeError, ValueError):
                        volume, oi = 0.0, 0
                    if volume > 0 and oi > 0:
                        usable.append(contract)
                if not usable:
                    raise AssertionError("viopContractsReady=true but no contract has volume/openInterest liquidity evidence")
                first = usable[0]
                vsymbol = str(first["symbol"])
                vq = get(f"/v1/viop/quote/{vsymbol}")
                vq.raise_for_status()
                vq_payload = vq.json()
                if float(vq_payload.get("volume") or 0) <= 0 or int(vq_payload.get("openInterest") or 0) <= 0:
                    raise AssertionError("VIOP quote lacks positive volume/openInterest while capability is ready")
                vh = get(f"/v1/viop/history/{vsymbol}", range="1y", interval="1d")
                vh.raise_for_status()
                checks.append(Check("viop-contract-quote-history", bool(vh.json().get("candles")), vsymbol))
            else:
                if viop_r.status_code != 503:
                    raise AssertionError(f"viopContractsReady=false but /v1/viop/contracts returned {viop_r.status_code}")
                checks.append(Check("viop-fail-closed", True, "metadata capability disabled; endpoint returned 503"))

            # Optional surfaces must match their advertised capability; no fake-ready state is accepted.
            if features.get("tradingViewSignals") is True:
                tv = get("/v1/tradingview/signals", limit=20)
                tv.raise_for_status()
                payload = tv.json()
                checks.append(Check("tradingview-enabled-store-read", isinstance(payload.get("items"), list), f"HTTP {tv.status_code}"))
            else:
                tv = client.get("/v1/tradingview/signals")
                checks.append(Check("tradingview-fail-closed", tv.status_code == 503, f"HTTP {tv.status_code}"))

            for capability, path, name in (
                ("researchFoundation", "/v1/research/foundation?symbol=THYAO", "research-fail-closed"),
                ("realtimeScannerRest", "/v1/realtime/opportunities", "realtime-rest-fail-closed"),
            ):
                if features.get(capability) is not True:
                    response = client.get(path)
                    checks.append(Check(name, response.status_code == 503, f"HTTP {response.status_code}"))

    except Exception as exc:
        error = f"{type(exc).__name__}: {exc}"
    elapsed_ms = int((time.monotonic() - started_monotonic) * 1000)
    failed = [check for check in checks if not check.ok]
    for check in checks:
        print(("PASS" if check.ok else "FAIL"), check.name, "-", check.detail)
    if failed and error is None:
        error = f"{len(failed)} check(s) failed"
    _write_artifact(_artifact_payload(
        checks,
        started_at=started_at,
        elapsed_ms=elapsed_ms,
        error=error,
        deployment_revision=deployment_revision,
        failure_stage=failure_stage if error else "",
        quota_metrics=quota_metrics,
    ))
    if error is not None:
        print(f"FAILED: {error}", file=sys.stderr)
        return 1
    print(f"PASS: {len(checks)} production E2E checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
