package tr.borsatakip.v5.analysis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalVisualPolicyTest {
    @Test
    fun reportStrengthBucketsAreAppliedExactly() {
        assertEquals(SignalIntensity.VERY_LOW, SignalVisualPolicy.resolve("LONG", 20, 0)?.intensity)
        assertEquals(SignalIntensity.LOW, SignalVisualPolicy.resolve("LONG", 21, 0)?.intensity)
        assertEquals(SignalIntensity.MEDIUM, SignalVisualPolicy.resolve("LONG", 41, 0)?.intensity)
        assertEquals(SignalIntensity.HIGH, SignalVisualPolicy.resolve("LONG", 61, 0)?.intensity)
        assertEquals(SignalIntensity.MAXIMUM, SignalVisualPolicy.resolve("LONG", 81, 0)?.intensity)
    }

    @Test
    fun strongestDirectionsUseReportPalette() {
        val long = SignalVisualPolicy.resolve("LONG", 100, 0)!!
        val short = SignalVisualPolicy.resolve("SHORT", 0, 100)!!
        assertArrayEquals(intArrayOf(0, 255, 136), intArrayOf(long.red, long.green, long.blue))
        assertArrayEquals(intArrayOf(255, 23, 68), intArrayOf(short.red, short.green, short.blue))
        assertEquals("▲", long.arrow)
        assertEquals("▼", short.arrow)
    }

    @Test
    fun neutralDoesNotInventDirectionalColor() {
        assertNull(SignalVisualPolicy.resolve("NEUTRAL", 50, 50))
    }
}
