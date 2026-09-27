package tr.borsatakip.v5.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.model.ScanRunStatus

class BistScanServiceLifecyclePolicyTest {
    @Test
    fun normalShutdownNeverBecomesInterruptedEvenIfCoroutineIsStillActive() {
        assertFalse(BistScanServiceLifecyclePolicy.shouldMarkInterrupted(
            normalShutdown = true,
            terminalStatePublished = true,
            jobActive = true,
            stopRequested = false
        ))
    }

    @Test
    fun externalDestroyOfActiveScanIsInterrupted() {
        assertTrue(BistScanServiceLifecyclePolicy.shouldMarkInterrupted(
            normalShutdown = false,
            terminalStatePublished = false,
            jobActive = true,
            stopRequested = false
        ))
    }

    @Test
    fun userStopIsNotReportedAsOsInterruption() {
        assertFalse(BistScanServiceLifecyclePolicy.shouldMarkInterrupted(
            normalShutdown = false,
            terminalStatePublished = false,
            jobActive = true,
            stopRequested = true
        ))
    }

    @Test
    fun completedRequiresDurableVerifiedResultPersistence() {
        assertTrue(BistScanServiceLifecyclePolicy.canPublishCompleted(ScanRunStatus.COMPLETE, persistenceSucceeded = true))
        assertFalse(BistScanServiceLifecyclePolicy.canPublishCompleted(ScanRunStatus.COMPLETE, persistenceSucceeded = false))
        assertFalse(BistScanServiceLifecyclePolicy.canPublishCompleted(ScanRunStatus.PARTIAL, persistenceSucceeded = true))
    }
}
