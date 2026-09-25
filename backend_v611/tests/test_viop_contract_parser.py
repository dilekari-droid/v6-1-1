from backend_v611.viop_contract_parser import parse_viop_future_symbol, validate_verified_metadata


def run():
    cases = {
        "F_XU0300926": ("XU030", 2026, 9, "STANDARD"),
        "F_ASELS0926": ("ASELS", 2026, 9, "STANDARD"),
        "F_P_USDTRY0926": ("USDTRY", 2026, 9, "PHYSICAL"),
        "F_XAUTRY1026": ("XAUTRY", 2026, 10, "STANDARD"),
    }
    for symbol, expected in cases.items():
        parsed = parse_viop_future_symbol(symbol)
        assert parsed is not None, symbol
        assert (parsed.underlying, parsed.expiry_year, parsed.expiry_month, parsed.settlement_type) == expected
    assert parse_viop_future_symbol("F_XU0301326") is None
    assert parse_viop_future_symbol("F_XU03009AA") is None
    ok, missing = validate_verified_metadata({"symbol":"F_XU0300926","underlying":"XU030"})
    assert ok is False and "tickSize" in missing and "multiplier" in missing
    ok, missing = validate_verified_metadata({
        "symbol":"F_XU0300926","underlying":"XU030","expiry":"2026-09","lastTradingAt":1,
        "tickSize":1,"multiplier":1,"currency":"TRY"
    })
    assert ok is True and not missing
    return 8


if __name__ == "__main__":
    print(f"{run()}/8 VIOP contract parser regression groups PASS")
