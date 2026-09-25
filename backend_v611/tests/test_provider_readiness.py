from backend_v611.provider_readiness import (
    ProviderReadinessInput,
    TradeWizeState,
    ViopReadinessInput,
    build_capability_patch,
    trade_wize_state,
    viop_ready,
)


def viop(**overrides):
    values = dict(
        credentials_configured=True,
        auth_verified=True,
        contracts_valid=True,
        metadata_valid=True,
        active_future_exists=True,
        quote_valid=True,
        history_valid=True,
        freshness_valid=True,
    )
    values.update(overrides)
    return ViopReadinessInput(**values)


def test_not_configured_is_not_ready():
    x = viop(credentials_configured=False, auth_verified=None)
    assert trade_wize_state(x) is TradeWizeState.NOT_CONFIGURED
    assert viop_ready(x) is False


def test_configured_but_unverified_is_not_ready():
    x = viop(auth_verified=None)
    assert trade_wize_state(x) is TradeWizeState.CONFIGURED_UNVERIFIED
    assert viop_ready(x) is False


def test_auth_failure_is_explicit_and_not_ready():
    x = viop(auth_verified=False)
    assert trade_wize_state(x) is TradeWizeState.AUTH_FAILED
    assert viop_ready(x) is False


def test_every_viop_gate_is_required():
    fields = [
        "contracts_valid",
        "metadata_valid",
        "active_future_exists",
        "quote_valid",
        "history_valid",
        "freshness_valid",
    ]
    for field in fields:
        assert viop_ready(viop(**{field: False})) is False, field
    assert viop_ready(viop()) is True


def test_provider_ready_is_core_markets_not_all_ui_markets():
    result = build_capability_patch(
        ProviderReadinessInput(
            bist_ready=True,
            viop=viop(),
            additional_required_markets={"GOLD": False, "FX": False},
        )
    )
    assert result["providerReady"] is True
    assert result["coreMarketsReady"] is True
    assert result["globalProviderReady"] is False


def test_bist_fallback_does_not_make_viop_ready():
    result = build_capability_patch(
        ProviderReadinessInput(
            bist_ready=True,
            viop=viop(credentials_configured=False, auth_verified=None),
            additional_required_markets={},
        )
    )
    assert result["markets"]["BIST"]["ready"] is True
    assert result["markets"]["VIOP"]["ready"] is False
    assert result["tradeWize"]["state"] == "NOT_CONFIGURED"
