"""Fail-closed VIOP contract-code parser for verified TradeWize symbols.

The parser extracts only what is encoded in the symbol itself. It does not invent
provider metadata such as tickSize, multiplier, lastTradingAt, volume or openInterest.
"""
from __future__ import annotations

from dataclasses import dataclass
import re


_VIOP_FUTURE_RE = re.compile(
    r"^F_(?:(?P<physical>P)_)?(?P<underlying>[A-Z0-9_.-]+?)(?P<month>0[1-9]|1[0-2])(?P<year>\d{2})$"
)


@dataclass(frozen=True)
class ParsedViopFuture:
    symbol: str
    underlying: str
    contract_type: str
    settlement_type: str
    expiry_year: int
    expiry_month: int


def parse_viop_future_symbol(symbol: str) -> ParsedViopFuture | None:
    """Parse supported VIOP future symbols without fabricating metadata."""
    normalized = (symbol or "").strip().upper()
    match = _VIOP_FUTURE_RE.fullmatch(normalized)
    if not match:
        return None
    year = 2000 + int(match.group("year"))
    month = int(match.group("month"))
    underlying = match.group("underlying")
    if not underlying:
        return None
    return ParsedViopFuture(
        symbol=normalized,
        underlying=underlying,
        contract_type="FUTURE",
        settlement_type="PHYSICAL" if match.group("physical") else "STANDARD",
        expiry_year=year,
        expiry_month=month,
    )


def validate_verified_metadata(raw: dict) -> tuple[bool, tuple[str, ...]]:
    """Require provider-supplied metadata; symbol parsing is never a substitute."""
    required = ("symbol", "underlying", "expiry", "lastTradingAt", "tickSize", "multiplier", "currency")
    missing = tuple(key for key in required if raw.get(key) in (None, ""))
    return not missing, missing
