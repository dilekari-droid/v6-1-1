package tr.borsatakip.v5.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalMarketQuotePolicyTest {
    @Test fun fallbackRequiresBothUserFlags() {
        assertFalse(ExternalMarketQuotePolicy.fallbackAllowed(false, false))
        assertFalse(ExternalMarketQuotePolicy.fallbackAllowed(true, false))
        assertFalse(ExternalMarketQuotePolicy.fallbackAllowed(false, true))
        assertTrue(ExternalMarketQuotePolicy.fallbackAllowed(true, true))
    }

    @Test fun sourcePriorityIsFallbackThenCacheThenNone() {
        assertEquals(ExternalMarketQuotePolicy.SourceChoice.FALLBACK, ExternalMarketQuotePolicy.chooseSource(true, true))
        assertEquals(ExternalMarketQuotePolicy.SourceChoice.CACHE, ExternalMarketQuotePolicy.chooseSource(false, true))
        assertEquals(ExternalMarketQuotePolicy.SourceChoice.NONE, ExternalMarketQuotePolicy.chooseSource(false, false))
    }

    @Test fun marketCapabilityMappingIsExplicit() {
        assertEquals("FX", ExternalMarketQuotePolicy.capabilityMarket("USDTRY"))
        assertEquals("COMMODITY", ExternalMarketQuotePolicy.capabilityMarket("XAUUSD"))
        assertEquals(null, ExternalMarketQuotePolicy.capabilityMarket("XU100"))
    }

    @Test fun providerErrorsAreClassifiedWithoutSecrets() {
        assertEquals("RATE_LIMIT", ExternalMarketQuotePolicy.classifyProviderError("Yahoo HTTP 429"))
        assertEquals("NETWORK_ERROR", ExternalMarketQuotePolicy.classifyProviderError("UnknownHostException"))
        assertEquals("PARSE_ERROR", ExternalMarketQuotePolicy.classifyProviderError("Yahoo cevap yapısı geçersiz"))
    }
}
