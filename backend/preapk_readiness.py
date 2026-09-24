"""Redacted pre-APK / production-readiness inspector for V6.1.1 source package.

This module never prints secret values. It separates source-policy readiness from external
provider/device/deployment gates so an unavailable integration cannot be accidentally reported as
complete.
"""
from __future__ import annotations

import argparse
import json
from typing import Any

import main

SOURCE_PACKAGE_VERSION = "V6.1.1"


def _bool(value: object) -> bool:
    return bool(value)


def collect_readiness(*, validate_startup: bool = False) -> dict[str, Any]:
    startup_error: str | None = None
    if validate_startup:
        try:
            main._validate_startup_configuration()
        except Exception as exc:  # intentionally redacted: only exception text, never secret values
            startup_error = str(exc)

    source_policy = {
        "sourceOnly": True,
        "fakeRealtimeForbidden": main.FEATURE_CAPABILITIES.get("realtimeScannerRest") is False,
        "fakeAttestationForbidden": main.FEATURE_CAPABILITIES.get("attestationReady") is False,
        "fakeResearchForbidden": main.FEATURE_CAPABILITIES.get("researchFoundation") is False,
        "fiveMinuteSlaFailClosed": main.FEATURE_CAPABILITIES.get("fullBistFiveMinuteSla") is False,
        "providerWeightFormulaFailClosed": main.FEATURE_CAPABILITIES.get("providerWeightFormulaVerified") is False,
    }

    production_config = {
        "appEnvProduction": main.APP_ENV == "production",
        "appApiKeyConfigured": _bool(main.APP_API_KEY),
        "sessionSecretStrong": main._secret_strength_ok(main.SESSION_TOKEN_SECRET),
        "tradeWizeCredentialConfigured": _bool(main.TRADEWIZE_API_KEY or main.UPSTREAM_ACCESS_TOKEN),
        "distributedQuotaConfigured": _bool(main.UPSTREAM_DISTRIBUTED_QUOTA and main.REDIS_URL),
        "distributedSessionRevocationConfigured": _bool(main.SESSION_REVOKE_DISTRIBUTED and main.REDIS_URL),
        "distributedSnapshotIdempotencyConfigured": _bool(main.SNAPSHOT_BATCH_DISTRIBUTED_IDEMPOTENCY and main.REDIS_URL),
        "viopMetadataConfigured": _bool(main.VIOP_CONTRACT_METADATA_PATH),
        "tradingViewConfigured": _bool(main.TRADINGVIEW_WEBHOOK_SECRET and main.TRADINGVIEW_DB_PATH),
        "attestationSigningConfigured": _bool(
            main.ATTESTATION_PRIVATE_KEYS_JSON
            and main.ATTESTATION_ACTIVE_KEY_ID
            and main.ATTESTATION_ACTIVE_KEY_GENERATION > 0
        ),
    }

    external_gates = {
        "providerWeightFormulaVerified": _bool(main.FEATURE_CAPABILITIES.get("providerWeightFormulaVerified")),
        "realtimeProviderE2EPassed": _bool(main.FEATURE_CAPABILITIES.get("realtimeScannerRest")),
        "realDeviceAttestationE2EPassed": _bool(main.FEATURE_CAPABILITIES.get("attestationReady")),
        # Local readiness can prove configuration presence only. Provider verification belongs to production E2E.
        "viopMetadataConfigured": _bool(main.VIOP_CONTRACT_METADATA_PATH),
        "viopMetadataVerified": False,
        "viopContractMetadataReady": False,
        "researchBackendReady": _bool(main.FEATURE_CAPABILITIES.get("researchFoundation")),
        "allTimeHistoryVerified": _bool(main.FEATURE_CAPABILITIES.get("allTimeHistory")),
        "fullBistFiveMinuteSlaVerified": _bool(main.FEATURE_CAPABILITIES.get("fullBistFiveMinuteSla")),
    }

    source_policy_ok = all(source_policy.values())
    startup_ok = startup_error is None if validate_startup else None
    production_startup_ready = bool(
        validate_startup
        and production_config["appEnvProduction"]
        and production_config["appApiKeyConfigured"]
        and production_config["sessionSecretStrong"]
        and production_config["tradeWizeCredentialConfigured"]
        and startup_ok is True
    )
    return {
        "schemaVersion": 2,
        "sourcePackageVersion": SOURCE_PACKAGE_VERSION,
        "version": main.ENGINE_VERSION,
        "sourcePolicy": source_policy,
        "sourcePolicyOk": source_policy_ok,
        "productionConfig": production_config,
        "startupValidationRequested": validate_startup,
        "startupValidationOk": startup_ok,
        "productionStartupReady": production_startup_ready,
        "startupValidationError": startup_error,
        "externalGates": external_gates,
        "note": "False external gates are intentionally not promoted to ready without independent provider/device/deployment evidence.",
    }


def cli(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--validate-startup",
        action="store_true",
        help="Run the same fail-closed startup validation used by the production backend.",
    )
    parser.add_argument(
        "--require-production-startup",
        action="store_true",
        help="Exit non-zero unless production startup validation passes. Implies --validate-startup.",
    )
    args = parser.parse_args(argv)
    validate = args.validate_startup or args.require_production_startup
    payload = collect_readiness(validate_startup=validate)
    print(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True))
    if not payload["sourcePolicyOk"]:
        return 2
    if args.require_production_startup and payload["productionStartupReady"] is not True:
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(cli())
