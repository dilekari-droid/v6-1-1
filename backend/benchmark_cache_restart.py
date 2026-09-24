#!/usr/bin/env python3
"""Source-only restart persistence benchmark; no provider/network calls."""
import json
import tempfile
import time
from pathlib import Path

import main


def candle(ts: int):
    return {"timestamp": ts, "open": 1.0, "high": 2.0, "low": 0.5, "close": 1.5, "volume": 1.0}


def run(symbols: int = 200, candles_per_symbol: int = 20):
    original_path = main.BAR_CACHE_PERSIST_PATH
    original_cache = dict(main._bar_series_cache)
    try:
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "bar-cache.json"
            main.BAR_CACHE_PERSIST_PATH = str(path)
            main._bar_series_cache.clear()
            base = int(time.time() * 1000) - candles_per_symbol * 60_000
            now = time.time()
            for idx in range(symbols):
                candles = [candle(base + j * 60_000) for j in range(candles_per_symbol)]
                main._store_bar_cache(("BIST", f"SYM{idx:04d}", "5m"), now + idx / 1000.0, candles, request_count_back=candles_per_symbol)
            before_keys = len(main._bar_series_cache)
            before_bars = sum(len(e.get("candles") or []) for e in main._bar_series_cache.values())
            t0 = time.perf_counter()
            ok = main._persist_bar_cache_to_disk()
            persist_ms = (time.perf_counter() - t0) * 1000.0
            main._bar_series_cache.clear()
            t1 = time.perf_counter()
            loaded = main._load_bar_cache_from_disk()
            load_ms = (time.perf_counter() - t1) * 1000.0
            after_keys = len(main._bar_series_cache)
            after_bars = sum(len(e.get("candles") or []) for e in main._bar_series_cache.values())
            result = {
                "benchmark": "bar-cache-restart-persistence",
                "networkUsed": False,
                "symbols": symbols,
                "candlesPerSymbol": candles_per_symbol,
                "beforeKeys": before_keys,
                "beforeBars": before_bars,
                "persisted": bool(ok),
                "loadedKeys": loaded,
                "afterKeys": after_keys,
                "afterBars": after_bars,
                "persistMs": round(persist_ms, 3),
                "loadMs": round(load_ms, 3),
                "pass": bool(ok and loaded == before_keys and after_bars == before_bars),
            }
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0 if result["pass"] else 1
    finally:
        main.BAR_CACHE_PERSIST_PATH = original_path
        main._bar_series_cache.clear()
        main._bar_series_cache.update(original_cache)


if __name__ == "__main__":
    raise SystemExit(run())
