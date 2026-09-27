package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFallbackPolicyTest {
    @Test fun fallbackRequiresBothUserSettings() {
        assertFalse(ProviderFallbackPolicy.allowed(false, false))
        assertFalse(ProviderFallbackPolicy.allowed(true, false))
        assertFalse(ProviderFallbackPolicy.allowed(false, true))
        assertTrue(ProviderFallbackPolicy.allowed(true, true))
    }
}
