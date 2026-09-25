"""Central TradeWize quota/cooldown/single-flight primitives.

No network client is embedded here. Every TradeWize adapter call should pass through
this gate so the backend has one quota authority instead of per-endpoint throttles.
"""
from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable
from typing import TypeVar

T = TypeVar("T")


class TradeWizeQuotaManager:
    def __init__(self, min_interval_seconds: float = 1.0, clock: Callable[[], float] | None = None, sleeper: Callable[[float], Awaitable[None]] | None = None):
        if min_interval_seconds < 0:
            raise ValueError("min_interval_seconds must be >= 0")
        self._interval = float(min_interval_seconds)
        self._clock = clock or time.monotonic
        self._sleep = sleeper or asyncio.sleep
        self._dispatch_lock = asyncio.Lock()
        self._singleflight_lock = asyncio.Lock()
        self._last_dispatch_at: float | None = None
        self._cooldown_until = 0.0
        self._inflight: dict[str, asyncio.Task] = {}

    @property
    def cooldown_until(self) -> float:
        return self._cooldown_until

    async def acquire(self) -> None:
        async with self._dispatch_lock:
            now = self._clock()
            wait_for = max(0.0, self._cooldown_until - now)
            if self._last_dispatch_at is not None:
                wait_for = max(wait_for, self._interval - (now - self._last_dispatch_at))
            if wait_for > 0:
                await self._sleep(wait_for)
            self._last_dispatch_at = self._clock()

    def apply_retry_after(self, retry_after_seconds: float | int | None) -> None:
        try:
            value = float(retry_after_seconds or 0)
        except (TypeError, ValueError):
            value = 0.0
        if value <= 0:
            value = self._interval or 1.0
        self._cooldown_until = max(self._cooldown_until, self._clock() + value)

    async def singleflight(self, key: str, factory: Callable[[], Awaitable[T]]) -> T:
        normalized = key.strip()
        if not normalized:
            raise ValueError("singleflight key is required")
        async with self._singleflight_lock:
            existing = self._inflight.get(normalized)
            if existing is None:
                existing = asyncio.create_task(factory())
                self._inflight[normalized] = existing
        try:
            return await existing
        finally:
            async with self._singleflight_lock:
                if self._inflight.get(normalized) is existing and existing.done():
                    self._inflight.pop(normalized, None)
