from __future__ import annotations

from copy import deepcopy

from verify_benchmark_evidence import REQUIRED_TIMEFRAMES, validate_report


def _valid_report() -> dict:
    sha = "a" * 40
    quota = {"accepted": 10, "delayed": 0, "rateLimited": 0, "retries": 0, "cooldownExtensions": 0, "waitMs": 0, "cacheHit": 10, "cacheMiss": 10}
    return {
        "result": "PASS",
        "symbolCount": 640,
        "commitSha": sha,
        "deploymentRevision": sha,
        "providerConfigured": True,
        "timeframes": [
            {
                "timeframe": tf,
                "elapsedMs": 1000,
                "rows": 640,
                "rowErrors": 0,
                "httpCalls": 32,
                "upstreamAcceptedCalls": 10,
                "batchLatencyMs": {"p50": 10.0, "p95": 20.0},
                "quotaDelta": dict(quota),
                "backendProcessDelta": {"cpuProcessSecondsDelta": 0.25, "rssBytesBefore": 10_000_000, "rssBytesAfter": 11_000_000, "rssBytesDelta": 1_000_000},
            }
            for tf in REQUIRED_TIMEFRAMES
        ],
    }


def test_valid_benchmark_evidence_passes():
    report = _valid_report()
    assert validate_report(report, "a" * 40) == []


def test_wrong_revision_is_rejected():
    report = _valid_report()
    report["deploymentRevision"] = "b" * 40
    assert "REVISION_MISMATCH" in validate_report(report, "a" * 40)


def test_partial_or_resource_metric_free_benchmark_is_rejected():
    report = _valid_report()
    report["symbolCount"] = 639
    report["timeframes"][0]["backendProcessDelta"]["rssBytesAfter"] = None
    report["timeframes"][1]["rowErrors"] = 1
    errors = validate_report(report, "a" * 40)
    assert "SYMBOL_COUNT_LT_640" in errors
    assert "1m:RSS_METRIC_MISSING" in errors
    assert "5m:ROW_ERRORS" in errors


def test_secret_named_fields_are_rejected():
    report = _valid_report()
    report["apiKey"] = "must-not-appear"
    assert any(item.startswith("SECRET_FIELD_PRESENT:") for item in validate_report(report, "a" * 40))


def test_underscore_and_variant_secret_fields_are_rejected():
    for key in ("api_key", "access_token", "client_secret", "client-secret", "client.secret"):
        report = _valid_report()
        report[key] = "must-not-appear"
        errors = validate_report(report, "a" * 40)
        assert any(item.startswith("SECRET_FIELD_PRESENT:") for item in errors), key
