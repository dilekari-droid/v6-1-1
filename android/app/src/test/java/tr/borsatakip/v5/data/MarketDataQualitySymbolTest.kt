package tr.borsatakip.v5.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tr.borsatakip.v5.model.Stock

class MarketDataQualitySymbolTest {
    @Test fun expectedAndActualSymbolMustMatch() {
        val now = 1_700_000_000_000L
        val stock = Stock(
            symbol = "THYAO",
            companyName = null,
            candles = emptyList(),
            source = "test",
            dataTimestamp = now,
            isRealtime = true,
            delaySeconds = 0,
            currentSessionIncluded = true,
            receivedAt = now,
            quotePrice = 100.0
        )
        val verdict = MarketDataQuality.validateStock("AKBNK", stock, now)
        assertFalse(verdict.accepted)
        assertEquals("SYMBOL_MISMATCH", verdict.code)
    }
}
