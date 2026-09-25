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
        liquidity_valid=True,
        scanner_ready=True,
    )
    values.update(overrides)
    return ViopReadinessInput(**values)


def run():
    x = viop(credentials_configured=False, auth_verified=None)
    assert trade_wize_state(x) is TradeWizeState.NOT_CONFIGURED
    assert viop_ready(x) is False

    x = viop(auth_verified=None)
    assert trade_wize_state(x) is TradeWizeState.CONFIGURED_UNVERIFIED
    assert viop_ready(x) is False

    x = viop(auth_verified=False)
    assert trade_wize_state(x) is TradeWizeState.AUTH_FAILED
    assert viop_ready(x) is False

    for field in [
        "contracts_valid",
        "metadata_valid",
        "active_future_exists",
        "quote_valid",
        "history_valid",
        "freshness_valid",
        "liquidity_valid",
        "scanner_ready",
    ]:
        assert viop_ready(viop(**{field: False})) is False, field
    assert viop_ready(viop()) is True

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

    return 6


if __name__ == "__main__":
    count = run()
    print(f"{count}/6 backend readiness regression groups PASS")
