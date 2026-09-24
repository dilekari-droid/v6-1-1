package tr.borsatakip.v5.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualScanSessionModelTest {
    @Test
    fun progressComesOnlyFromScannedAndTotalCounts() {
        val session = ManualScanSession(scannedCount = 428, totalCount = 630)
        assertEquals(67, session.progress)
    }

    @Test
    fun progressNeverExceedsHundred() {
        val session = ManualScanSession(scannedCount = 900, totalCount = 630)
        assertEquals(100, session.progress)
    }

    @Test
    fun activeStatesIncludeFinalizingButNotTerminalStates() {
        assertTrue(ManualScanSession(status = ManualScanStatus.PREFLIGHT).isActive)
        assertTrue(ManualScanSession(status = ManualScanStatus.RUNNING).isActive)
        assertTrue(ManualScanSession(status = ManualScanStatus.PAUSED).isActive)
        assertTrue(ManualScanSession(status = ManualScanStatus.FINALIZING).isActive)
        assertFalse(ManualScanSession(status = ManualScanStatus.COMPLETED).isActive)
        assertFalse(ManualScanSession(status = ManualScanStatus.PARTIAL).isActive)
        assertFalse(ManualScanSession(status = ManualScanStatus.INTERRUPTED).isActive)
    }

    @Test
    fun persistenceIsThrottledButTerminalCanForceImmediateWrite() {
        assertFalse(ManualScanPersistencePolicy.shouldPersist(false, false, 1000, 800, 5, 1))
        assertTrue(ManualScanPersistencePolicy.shouldPersist(false, false, 2000, 800, 5, 1))
        assertTrue(ManualScanPersistencePolicy.shouldPersist(false, false, 1000, 800, 11, 1))
        assertTrue(ManualScanPersistencePolicy.shouldPersist(true, false, 1000, 999, 2, 1))
    }

    @Test
    fun activePersistedSessionRecoversAsInterruptedNotCompleted() {
        val now = 123456789L
        val recovered = ManualScanRecoveryPolicy.recover(
            ManualScanSession(scanId = "scan-1", status = ManualScanStatus.RUNNING, scannedCount = 40, totalCount = 100),
            now
        )
        assertEquals(ManualScanStatus.INTERRUPTED, recovered.status)
        assertEquals(now, recovered.completedAt)
        assertEquals(40, recovered.scannedCount)
    }
}
