package tr.borsatakip.v5.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.model.Candle

class ChartTimeframeContractTest {
    @Test fun requested_mobile_timeframes_are_available() {
        val labels = ChartTimeframe.values().map { it.label }.toSet()
        assertTrue(labels.containsAll(setOf("5 DK", "15 DK", "30 DK", "1 SA", "4 SA", "1 GÜN")))
    }

    @Test fun four_hour_uses_real_hourly_source_and_deterministic_aggregation() {
        assertEquals(60, ChartTimeframe.FOUR_HOUR.requestIntervalMinutes)
        assertEquals(240, ChartTimeframe.FOUR_HOUR.aggregateMinutes)
    }

    @Test fun thirty_minute_is_requested_as_thirty_minute_ohlcv() {
        assertEquals(30, ChartTimeframe.THIRTY_MIN.requestIntervalMinutes)
        assertEquals(null, ChartTimeframe.THIRTY_MIN.aggregateMinutes)
    }

    @Test fun four_hour_resampler_preserves_real_ohlcv_semantics() {
        val hour = 60L * 60L * 1000L
        val base = 4L * hour
        val source = listOf(
            Candle(base, 100.0, 102.0, 99.0, 101.0, 10.0),
            Candle(base + hour, 101.0, 104.0, 100.0, 103.0, 20.0),
            Candle(base + 2 * hour, 103.0, 105.0, 102.0, 104.0, 30.0),
            Candle(base + 3 * hour, 104.0, 106.0, 103.0, 105.0, 40.0)
        )
        val result = OhlcvResampler.aggregate(source, sourceIntervalMinutes = 60, targetIntervalMinutes = 240)
        assertEquals(1, result.size)
        with(result.single()) {
            assertEquals(base, timestamp)
            assertEquals(100.0, open, 0.0)
            assertEquals(106.0, high, 0.0)
            assertEquals(99.0, low, 0.0)
            assertEquals(105.0, close, 0.0)
            assertEquals(100.0, volume, 0.0)
        }
    }
}
