package tr.borsatakip.v5.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderHealthRegistryTest {
    @Before fun reset() = ProviderHealthRegistry.resetForTests()

    @Test fun repeatedPayloadTrackingIsPerSymbol() {
        val provider = "test"
        assertTrue(ProviderHealthRegistry.recordSymbolSuccess(provider, "AKBNK", "sig-a", realtime = true, nowMs = 1000L))
        assertTrue(ProviderHealthRegistry.recordSymbolSuccess(provider, "THYAO", "sig-t", realtime = true, nowMs = 1001L))
        assertTrue(ProviderHealthRegistry.recordSymbolSuccess(provider, "AKBNK", "sig-a", realtime = true, nowMs = 1002L))
        assertEquals(1, ProviderHealthRegistry.repeatedPayloadCount(provider, "AKBNK"))
        assertEquals(0, ProviderHealthRegistry.repeatedPayloadCount(provider, "THYAO"))
    }
}
