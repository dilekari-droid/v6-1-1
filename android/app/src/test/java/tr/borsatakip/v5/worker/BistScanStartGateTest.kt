package tr.borsatakip.v5.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BistScanStartGateTest {
    @Test
    fun notificationPermissionIsRequiredOnlyFromAndroid13() {
        assertFalse(BistScanStartGate.requiresNotificationPermission(32, granted = false))
        assertFalse(BistScanStartGate.requiresNotificationPermission(33, granted = true))
        assertTrue(BistScanStartGate.requiresNotificationPermission(33, granted = false))
        assertTrue(BistScanStartGate.requiresNotificationPermission(35, granted = false))
    }

    @Test
    fun providerGateRequiresProductionPairOrExplicitFallback() {
        assertTrue(BistScanStartGate.providerConfigured(true, true, false))
        assertTrue(BistScanStartGate.providerConfigured(false, false, true))
        assertFalse(BistScanStartGate.providerConfigured(true, false, false))
        assertFalse(BistScanStartGate.providerConfigured(false, true, false))
    }
}
