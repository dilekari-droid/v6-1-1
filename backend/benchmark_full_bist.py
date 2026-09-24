"""Explicit production-like full-universe benchmark for V5.4.16.

This tool never runs automatically. It requires an explicit HTTPS backend and API key, records
only redacted timing/count metrics, and does not claim the 5-minute SLA. It is intended for the
FULL-HARDENING 1m/5m/15m/30m/60m/1d acceptance benchmarks.
"""
from __future__ import annotations

import json
import math
import os
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx

BASE = os.getenv("BORSA_BENCHMARK_BASE_URL", "").strip().rstrip("/")
KEY = os.getenv("BORSA_BENCHMARK_API_KEY", "").strip()
INSTALL = os.getenv("BORSA_BENCHMARK_INSTALL_ID", "ci-benchmark-v611").strip()
TIMEOUT = float(os.getenv("BORSA_BENCHMARK_TIMEOUT_SECONDS", "240"))
ARTIFACT = os.getenv("BORSA_BENCHMARK_ARTIFACT_PATH", "").strip()
TIMEFRAMES = [x.strip().lower() for x in os.getenv("BORSA_BENCHMARK_TIMEFRAMES", "1m,5m,15m,30m,60m,1d").split(",") if x.strip()]
MAX_SYMBOLS = max(1, int(os.getenv("BORSA_BENCHMARK_MAX_SYMBOLS", "1000")))
MIN_SYMBOLS = max(1, int(os.getenv("BORSA_BENCHMARK_MIN_SYMBOLS", "640")))
COMMIT_SHA = os.getenv("BORSA_BENCHMARK_COMMIT_SHA", os.getenv("GITHUB_SHA", "")).strip()
EXPECTED_REVISION = os.getenv("BORSA_BENCHMARK_EXPECTED_REVISION", COMMIT_SHA).strip()
REQUIRE_ALL_TIMEFRAMES = os.getenv("BORSA_BENCHMARK_REQUIRE_ALL_TIMEFRAMES", "true").strip().lower() in {"1", "true", "yes", "on"}
REQUIRED_TIMEFRAMES = ("1m", "5m", "15m", "30m", "60m", "1d")


def require_config() -> None:
    if not BASE.startswith("https://"):
        raise SystemExit("BORSA_BENCHMARK_BASE_URL must be an explicit HTTPS URL")
    if not KEY:
        raise SystemExit("BORSA_BENCHMARK_API_KEY is required")
    if REQUIRE_ALL_TIMEFRAMES:
        missing = [item for item in REQUIRED_TIMEFRAMES if item not in TIMEFRAMES]
        if missing:
            raise SystemExit(f"benchmark requires all acceptance timeframes; missing={','.join(missing)}")


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = max(0, min(len(ordered) - 1, math.ceil((pct / 100.0) * len(ordered)) - 1))
    return ordered[idx]


def int_metric(root: dict[str, Any], *path: str) -> int:
    value: Any = root
    for key in path:
        if not isinstance(value, dict):
            return 0
        value = value.get(key)
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def metric_delta(before: dict[str, Any], after: dict[str, Any]) -> dict[str, int]:
    keys = ("accepted", "delayed", "rateLimited", "retries", "cooldownExtensions", "waitMs", "cacheHit", "cacheMiss")
    return {key: max(0, int_metric(after, key) - int_metric(before, key)) for key in keys}


def process_metric_delta(before: dict[str, Any], after: dict[str, Any]) -> dict[str, float | int | None]:
    before_cpu = before.get("cpuProcessSeconds")
    after_cpu = after.get("cpuProcessSeconds")
    before_rss = before.get("rssBytes")
    after_rss = after.get("rssBytes")
    cpu_delta = None
    try:
        if before_cpu is not None and after_cpu is not None:
            cpu_delta = round(max(0.0, float(after_cpu) - float(before_cpu)), 6)
    except (TypeError, ValueError):
        cpu_delta = None
    rss_delta = None
    try:
        if before_rss is not None and after_rss is not None:
            rss_delta = int(after_rss) - int(before_rss)
    except (TypeError, ValueError):
        rss_delta = None
    return {
        "cpuProcessSecondsDelta": cpu_delta,
        "rssBytesBefore": int(before_rss) if isinstance(before_rss, (int, float)) else None,
        "rssBytesAfter": int(after_rss) if isinstance(after_rss, (int, float)) else None,
        "rssBytesDelta": rss_delta,
    }


def main() -> int:
    require_config()
    started_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    started = time.monotonic()
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "appVersion": "5.4.16",
        "commitSha": COMMIT_SHA or None,
        "backendOrigin": BASE,
        "startedAt": started_at,
        "requestedTimeframes": TIMEFRAMES,
        "fullBistFiveMinuteSlaClaimed": False,
        "timeframes": [],
    }
    headers = {"Authorization": f"Bearer {KEY}", "X-Install-ID": INSTALL, "Accept": "application/json"}
    try:
        with httpx.Client(base_url=BASE, headers=headers, timeout=TIMEOUT, follow_redirects=False) as client:
            initial_health = client.get("/v1/health")
            initial_health.raise_for_status()
            initial_health_json = initial_health.json()
            deployment_revision = str(initial_health_json.get("revision") or "").strip()
            report["deploymentRevision"] = deployment_revision or None
            if EXPECTED_REVISION and deployment_revision != EXPECTED_REVISION:
                raise RuntimeError(
                    f"deployment revision mismatch: expected={EXPECTED_REVISION} actual={deployment_revision or 'MISSING'}"
                )

            cap = client.get("/v1/provider/capabilities", params={"force": "true"})
            cap.raise_for_status()
            cap_json = cap.json()
            if cap_json.get("providerConfigured") is not True:
                raise RuntimeError("provider is not configured; benchmark cannot be production evidence")
            report["providerConfigured"] = True
            scan_policy = cap_json.get("scanPolicy") or {}
            batch_size = max(1, min(100, int(scan_policy.get("snapshotBatchMaxSymbols", 20) or 20)))
            report["scanPolicy"] = {
                "snapshotBatchMaxSymbols": batch_size,
                "barConcurrency": scan_policy.get("barConcurrency"),
                "pacingMs": scan_policy.get("pacingMs"),
                "quotaScope": scan_policy.get("quotaScope"),
                "requestWeight": scan_policy.get("requestWeight"),
                "providerWeightFormulaVerified": scan_policy.get("providerWeightFormulaVerified") is True,
            }

            symbols_r = client.get("/v1/bist/symbols")
            symbols_r.raise_for_status()
            symbols = [str(x).strip().upper() for x in symbols_r.json().get("items", []) if str(x).strip()][:MAX_SYMBOLS]
            if not symbols:
                raise RuntimeError("BIST symbol universe is empty")
            if len(symbols) < MIN_SYMBOLS:
                raise RuntimeError(f"BIST benchmark requires at least {MIN_SYMBOLS} symbols; got {len(symbols)}")
            report["symbolCount"] = len(symbols)
            report["minimumRequiredSymbolCount"] = MIN_SYMBOLS

            for timeframe in TIMEFRAMES:
                before_health = client.get("/v1/health")
                before_health.raise_for_status()
                before_health_json = before_health.json()
                before_quota = before_health_json.get("quota") or {}
                before_process = before_health_json.get("processMetrics") or {}
                tf_started = time.monotonic()
                call_latencies_ms: list[float] = []
                rows = 0
                row_errors = 0
                http_calls = 0
                for offset in range(0, len(symbols), batch_size):
                    chunk = symbols[offset: offset + batch_size]
                    call_started = time.monotonic()
                    response = client.get(
                        "/v1/bist/snapshot-batch",
                        params={"symbols": ",".join(chunk), "interval": timeframe, "range": "5d" if timeframe != "1d" else "1y"},
                    )
                    call_latencies_ms.append((time.monotonic() - call_started) * 1000.0)
                    http_calls += 1
                    if response.status_code == 429:
                        raise RuntimeError(f"benchmark hit backend 429 Retry-After={response.headers.get('Retry-After')}")
                    response.raise_for_status()
                    items = response.json().get("items") or []
                    if len(items) != len(chunk):
                        raise RuntimeError(f"batch cardinality mismatch at offset={offset}: {len(items)} != {len(chunk)}")
                    if [str(row.get("symbol") or "").upper() for row in items] != chunk:
                        raise RuntimeError(f"batch order mismatch at offset={offset}")
                    rows += len(items)
                    row_errors += sum(1 for row in items if row.get("error"))

                after_health = client.get("/v1/health")
                after_health.raise_for_status()
                after_health_json = after_health.json()
                after_quota = after_health_json.get("quota") or {}
                after_process = after_health_json.get("processMetrics") or {}
                tf_elapsed = time.monotonic() - tf_started
                quota_delta = metric_delta(before_quota, after_quota)
                if rows != len(symbols):
                    raise RuntimeError(f"{timeframe} benchmark row count mismatch: {rows} != {len(symbols)}")
                if row_errors:
                    raise RuntimeError(f"{timeframe} benchmark contains {row_errors} row errors")
                report["timeframes"].append({
                    "timeframe": timeframe,
                    "elapsedMs": int(tf_elapsed * 1000),
                    "rows": rows,
                    "rowErrors": row_errors,
                    "httpCalls": http_calls,
                    "upstreamAcceptedCalls": quota_delta.get("accepted", 0),
                    "batchLatencyMs": {
                        "min": round(min(call_latencies_ms), 2) if call_latencies_ms else 0,
                        "mean": round(statistics.fmean(call_latencies_ms), 2) if call_latencies_ms else 0,
                        "p50": round(percentile(call_latencies_ms, 50), 2),
                        "p95": round(percentile(call_latencies_ms, 95), 2),
                        "max": round(max(call_latencies_ms), 2) if call_latencies_ms else 0,
                    },
                    "quotaDelta": quota_delta,
                    "backendProcessDelta": process_metric_delta(before_process, after_process),
                })

            report["result"] = "PASS"
    except Exception as exc:
        report["result"] = "FAIL"
        report["error"] = f"{type(exc).__name__}: {exc}"
    report["elapsedMs"] = int((time.monotonic() - started) * 1000)
    report["finishedAt"] = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    text = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if ARTIFACT:
        target = Path(ARTIFACT)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if report.get("result") == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
