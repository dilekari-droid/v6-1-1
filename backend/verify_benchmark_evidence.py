"""Validate redacted full-BIST production benchmark evidence.

This validator is intentionally strict: it prevents a partial, wrong-revision, undersized or
resource-metric-free benchmark from being promoted to FULL-HARDENING acceptance evidence.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

REQUIRED_TIMEFRAMES = ("1m", "5m", "15m", "30m", "60m", "1d")
MIN_SYMBOLS = 640
FORBIDDEN_FIELD_TOKENS = ("apikey", "api_key", "accesstoken", "access_token", "secret", "authorization")


def _walk_keys(value: Any, prefix: str = "") -> list[str]:
    out: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            path = f"{prefix}.{key}" if prefix else str(key)
            out.append(path)
            out.extend(_walk_keys(child, path))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            out.extend(_walk_keys(child, f"{prefix}[{index}]"))
    return out


def validate_report(report: dict[str, Any], expected_revision: str) -> list[str]:
    errors: list[str] = []
    if report.get("result") != "PASS":
        errors.append("RESULT_NOT_PASS")
    if int(report.get("symbolCount") or 0) < MIN_SYMBOLS:
        errors.append("SYMBOL_COUNT_LT_640")
    commit_sha = str(report.get("commitSha") or "").strip()
    deployment_revision = str(report.get("deploymentRevision") or "").strip()
    expected = expected_revision.strip()
    if not expected:
        errors.append("EXPECTED_REVISION_REQUIRED")
    if not commit_sha:
        errors.append("COMMIT_SHA_MISSING")
    if not deployment_revision:
        errors.append("DEPLOYMENT_REVISION_MISSING")
    if expected and (commit_sha != expected or deployment_revision != expected):
        errors.append("REVISION_MISMATCH")
    if report.get("providerConfigured") is not True:
        errors.append("PROVIDER_NOT_CONFIGURED")

    timeframes = report.get("timeframes")
    if not isinstance(timeframes, list):
        errors.append("TIMEFRAMES_MISSING")
        timeframes = []
    by_tf = {str(item.get("timeframe")): item for item in timeframes if isinstance(item, dict)}
    if set(by_tf) != set(REQUIRED_TIMEFRAMES):
        errors.append("TIMEFRAME_SET_INVALID")
    for timeframe in REQUIRED_TIMEFRAMES:
        item = by_tf.get(timeframe)
        if not isinstance(item, dict):
            continue
        if int(item.get("rows") or 0) < MIN_SYMBOLS:
            errors.append(f"{timeframe}:ROWS_LT_640")
        if int(item.get("rowErrors") or 0) != 0:
            errors.append(f"{timeframe}:ROW_ERRORS")
        if int(item.get("httpCalls") or 0) <= 0:
            errors.append(f"{timeframe}:HTTP_CALLS_MISSING")
        if int(item.get("elapsedMs") or 0) <= 0:
            errors.append(f"{timeframe}:ELAPSED_MISSING")
        latency = item.get("batchLatencyMs") or {}
        if not isinstance(latency, dict) or float(latency.get("p50") or 0) <= 0 or float(latency.get("p95") or 0) <= 0:
            errors.append(f"{timeframe}:LATENCY_MISSING")
        quota = item.get("quotaDelta") or {}
        for key in ("accepted", "delayed", "rateLimited", "retries", "cooldownExtensions", "waitMs", "cacheHit", "cacheMiss"):
            if key not in quota:
                errors.append(f"{timeframe}:QUOTA_{key}_MISSING")
        process = item.get("backendProcessDelta") or {}
        if process.get("cpuProcessSecondsDelta") is None:
            errors.append(f"{timeframe}:CPU_METRIC_MISSING")
        if process.get("rssBytesBefore") is None or process.get("rssBytesAfter") is None:
            errors.append(f"{timeframe}:RSS_METRIC_MISSING")
        if "upstreamAcceptedCalls" not in item:
            errors.append(f"{timeframe}:UPSTREAM_CALL_COUNT_MISSING")

    normalized_forbidden = tuple("".join(ch for ch in token.lower() if ch.isalnum()) for token in FORBIDDEN_FIELD_TOKENS)
    for path in _walk_keys(report):
        lowered = "".join(ch for ch in path.lower() if ch.isalnum())
        if any(token and token in lowered for token in normalized_forbidden):
            errors.append(f"SECRET_FIELD_PRESENT:{path}")
    return sorted(set(errors))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--expected-revision", required=True)
    args = parser.parse_args()
    report = json.loads(args.artifact.read_text(encoding="utf-8"))
    if not isinstance(report, dict):
        raise SystemExit("benchmark artifact must contain a JSON object")
    errors = validate_report(report, args.expected_revision)
    if errors:
        print(json.dumps({"ok": False, "errors": errors}, ensure_ascii=False, sort_keys=True))
        return 1
    print(json.dumps({"ok": True, "symbolCount": report["symbolCount"], "timeframes": list(REQUIRED_TIMEFRAMES)}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
