from __future__ import annotations

import importlib
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BACKEND = REPO / "backend"
if str(BACKEND) not in sys.path:
    sys.path.insert(0, str(BACKEND))

import main  # noqa: E402
import preapk_readiness  # noqa: E402


def test_source_policy_is_fail_closed_for_unverified_external_features():
    payload = preapk_readiness.collect_readiness(validate_startup=False)
    assert payload["sourcePolicyOk"] is True
    assert payload["sourcePolicy"]["fakeRealtimeForbidden"] is True
    assert payload["sourcePolicy"]["fakeAttestationForbidden"] is True
    assert payload["sourcePolicy"]["providerWeightFormulaFailClosed"] is True
    assert payload["externalGates"]["providerWeightFormulaVerified"] is False
    assert payload["externalGates"]["fullBistFiveMinuteSlaVerified"] is False


def test_readiness_never_exposes_secret_values(monkeypatch):
    monkeypatch.setattr(main, "APP_API_KEY", "super-secret-api-key-value")
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "Very-Strong-Session-Secret-2026!A")
    monkeypatch.setattr(main, "TRADEWIZE_API_KEY", "trade-secret-provider-key")
    payload = preapk_readiness.collect_readiness(validate_startup=False)
    encoded = json.dumps(payload)
    assert "super-secret-api-key-value" not in encoded
    assert "Very-Strong-Session-Secret-2026!A" not in encoded
    assert "trade-secret-provider-key" not in encoded
    assert payload["productionConfig"]["appApiKeyConfigured"] is True
    assert payload["productionConfig"]["sessionSecretStrong"] is True
    assert payload["productionConfig"]["tradeWizeCredentialConfigured"] is True


def test_production_startup_validation_reports_missing_provider_credential(monkeypatch):
    monkeypatch.setattr(main, "APP_ENV", "production")
    monkeypatch.setattr(main, "APP_API_KEY", "bootstrap-key-present")
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "Very-Strong-Session-Secret-2026!A")
    monkeypatch.setattr(main, "TRADEWIZE_API_KEY", "")
    monkeypatch.setattr(main, "UPSTREAM_ACCESS_TOKEN", "")
    payload = preapk_readiness.collect_readiness(validate_startup=True)
    assert payload["startupValidationOk"] is False
    assert "TradeWize credentials are required" in payload["startupValidationError"]


def test_require_production_startup_never_passes_in_development(monkeypatch, capsys):
    monkeypatch.setattr(main, "APP_ENV", "development")
    monkeypatch.setattr(main, "APP_API_KEY", "")
    monkeypatch.setattr(main, "SESSION_TOKEN_SECRET", "")
    monkeypatch.setattr(main, "TRADEWIZE_API_KEY", "")
    monkeypatch.setattr(main, "UPSTREAM_ACCESS_TOKEN", "")
    rc = preapk_readiness.cli(["--require-production-startup"])
    assert rc != 0
    payload = json.loads(capsys.readouterr().out)
    assert payload["productionConfig"]["appEnvProduction"] is False
    assert payload["productionStartupReady"] is False


def test_viop_readiness_distinguishes_configured_from_verified(monkeypatch):
    monkeypatch.setattr(main, "VIOP_CONTRACT_METADATA_PATH", "/fake/viop/meta")
    monkeypatch.setitem(main.FEATURE_CAPABILITIES, "viopContractsReady", True)
    payload = preapk_readiness.collect_readiness(validate_startup=False)
    assert payload["productionConfig"]["viopMetadataConfigured"] is True
    assert payload["externalGates"]["viopMetadataConfigured"] is True
    assert payload["externalGates"]["viopMetadataVerified"] is False
    assert payload["externalGates"]["viopContractMetadataReady"] is False
