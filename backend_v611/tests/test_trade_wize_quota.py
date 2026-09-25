import asyncio
from backend_v611.trade_wize_quota import TradeWizeQuotaManager


class FakeTime:
    def __init__(self): self.now = 100.0; self.sleeps = []
    def clock(self): return self.now
    async def sleep(self, seconds): self.sleeps.append(seconds); self.now += seconds


async def main():
    ft = FakeTime()
    q = TradeWizeQuotaManager(1.0, clock=ft.clock, sleeper=ft.sleep)
    await q.acquire()
    await q.acquire()
    assert ft.sleeps == [1.0]

    q.apply_retry_after(3)
    await q.acquire()
    assert ft.sleeps[-1] == 3.0

    calls = 0
    gate = asyncio.Event()
    async def factory():
        nonlocal calls
        calls += 1
        await gate.wait()
        return "ok"
    tasks = [asyncio.create_task(q.singleflight("quote:X", factory)) for _ in range(20)]
    await asyncio.sleep(0)
    gate.set()
    results = await asyncio.gather(*tasks)
    assert calls == 1
    assert results == ["ok"] * 20
    print("3/3 TradeWize quota/cooldown/single-flight regression groups PASS")


if __name__ == "__main__": asyncio.run(main())
