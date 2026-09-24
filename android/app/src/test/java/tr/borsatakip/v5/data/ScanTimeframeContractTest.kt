package tr.borsatakip.v5.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTimeframeContractTest {
    @Test fun androidWireIntervalsMatchBackendCanonicalSet() {
        val expected=setOf("1m","3m","5m","10m","15m","30m","60m","1d")
        val actual=ScanTimeframe.quick().map { it.apiInterval.lowercase() }.toSet()
        assertEquals(expected,actual)
        assertFalse("240m" in actual)
        assertTrue("10m" in actual)
        assertTrue("1d" in actual)
    }

    @Test fun customClassicTimeframesAreNotDynamicWireIntervals() {
        val custom=ScanTimeframe.minute(7)
        assertTrue(custom.isCustom)
        assertEquals("7m",custom.apiInterval)
        assertFalse(DynamicMarketScannerClient.isDynamicTimeframeSupported(custom.apiInterval))
    }
}
