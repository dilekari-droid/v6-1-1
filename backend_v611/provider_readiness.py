"""V6.1.1 backend provider-readiness policy.

This module deliberately contains no provider credentials and never fabricates market data.
It separates configuration, authentication and end-to-end VIOP readiness so the API can
report truthful states without conflating BIST fallback availability with TradeWize readiness.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Mapping


class TradeWizeState(str, Enum):
    NOT_CONFIGURED = "NOT_CONFIGURED"
    CONFIGURED_UNVERIFIED = "CONFIGURED_UNVERIFIED"
    AUTH_FAILED = "AUTH_FAILED"
    CONNECTED = "CONNECTED"


@dataclass(frozen=True)
class ViopReadinessInput:
    credentials_configured: bool
    auth_verified: bool | None
    contracts_valid: bool
    metadata_valid: bool
    active_future_exists: bool
    quote_valid: bool
    history_valid: bool
    freshness_valid: bool


@dataclass(frozen=True)
class ProviderReadinessInput:
    bist_ready: bool
    viop: ViopReadinessInput
    additional_required_markets: Mapping[str, bool]


def trade_wize_state(data: ViopReadinessInput) -> TradeWizeState:
    if not data.credentials_configured:
        return TradeWizeState.NOT_CONFIGURED
    if data.auth_verified is None:
        return TradeWizeState.CONFIGURED_UNVERIFIED
    if data.auth_verified is False:
        return TradeWizeState.AUTH_FAILED
    return TradeWizeState.CONNECTED


def viop_ready(data: ViopReadinessInput) -> bool:
    return (
        trade_wize_state(data) is TradeWizeState.CONNECTED
        and data.contracts_valid
        and data.metadata_valid
        and data.active_future_exists
        and data.quote_valid
        and data.history_valid
        and data.freshness_valid
    )


def build_capability_patch(data: ProviderReadinessInput) -> dict:
    tw_state = trade_wize_state(data.viop)
    v_ready = viop_ready(data.viop)
    provider_ready = bool(data.bist_ready and v_ready)
    global_ready = bool(provider_ready and all(data.additional_required_markets.values()))

    return {
        "providerConfigured": data.viop.credentials_configured,
        "providerReady": provider_ready,
        "globalProviderReady": global_ready,
        "coreMarketsReady": provider_ready,
        "tradeWize": {
            "state": tw_state.value,
            "authenticated": data.viop.auth_verified is True,
        },
        "markets": {
            "BIST": {"ready": bool(data.bist_ready)},
            "VIOP": {
                "ready": v_ready,
                "contractsReady": bool(data.viop.contracts_valid),
                "metadataReady": bool(data.viop.metadata_valid),
                "activeFutureReady": bool(data.viop.active_future_exists),
                "quoteReady": bool(data.viop.quote_valid),
                "historyReady": bool(data.viop.history_valid),
                "freshnessReady": bool(data.viop.freshness_valid),
            },
            **{name: {"ready": bool(value)} for name, value in data.additional_required_markets.items()},
        },
    }
