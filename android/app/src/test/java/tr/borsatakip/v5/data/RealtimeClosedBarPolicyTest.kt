package tr.borsatakip.v5.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.model.Candle

class RealtimeClosedBarPolicyTest {
    private fun candle(ts: Long) = Candle(ts, 10.0, 11.0, 9.0, 10.5, 1000.0)

    @Test fun dropsOnlyTrailingOpenFiveMinuteBar() {
        val frame = 5 * 60_000L
        val now = 1_000_000L
        val closed = now - frame - 1L
        val open = now - frame + 1L
        val result = RealtimeClosedBarPolicy.selectClosedIntraday(listOf(candle(closed), candle(open)), 5, now)
        assertEquals(1, result.size)
        assertEquals(closed, result.last().timestamp)
    }

    @Test fun keepsAllBarsWhenNewestIsClosed() {
        val frame = 5 * 60_000L
        val now = 2_000_000L
        val a = now - frame * 2
        val b = now - frame
        val result = RealtimeClosedBarPolicy.selectClosedIntraday(listOf(candle(a), candle(b)), 5, now)
        assertEquals(2, result.size)
    }

    @Test fun ageWindowCoversBarStartTimestampUntilNextClose() {
        assertEquals(12 * 60_000L, RealtimeClosedBarPolicy.maximumClosedBarAgeMs(5))
        assertTrue(9 * 60_000L < RealtimeClosedBarPolicy.maximumClosedBarAgeMs(5))
    }
}
