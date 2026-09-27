package tr.borsatakip.v5.worker

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import tr.borsatakip.v5.data.LastSuccessfulScanStore
import tr.borsatakip.v5.data.ManualScanRecoveryPolicy
import tr.borsatakip.v5.data.ManualScanSessionStore
import tr.borsatakip.v5.data.ManualScanSession
import tr.borsatakip.v5.data.ManualScanStatus
import tr.borsatakip.v5.data.ManualScanSessionRepository
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.model.ScanRun
import tr.borsatakip.v5.model.ScanRunStatus

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ManualScanLifecycleInstrumentationTest {
    @Test
    fun normalServiceFinishCannotBeReclassifiedAsExternalKill() {
        assertFalse(BistScanServiceLifecyclePolicy.shouldMarkInterrupted(
            normalShutdown = true,
            terminalStatePublished = true,
            jobActive = true,
            stopRequested = false
        ))
    }

    @Test
    fun externalActiveServiceDestroyIsClassifiedAsInterrupted() {
        assertTrue(BistScanServiceLifecyclePolicy.shouldMarkInterrupted(
            normalShutdown = false,
            terminalStatePublished = false,
            jobActive = true,
            stopRequested = false
        ))
    }

    @Test
    fun processRecoveryNeverPromotesRunningStateToCompleted() {
        val recovered = ManualScanRecoveryPolicy.recover(
            ManualScanSession(scanId = "instrumented", status = ManualScanStatus.RUNNING, scannedCount = 12, totalCount = 100),
            nowMs = 42L
        )
        assertEquals(ManualScanStatus.INTERRUPTED, recovered.status)
        assertEquals(42L, recovered.completedAt)
    }

    @Test
    fun failedResultPersistenceCannotPublishCompleted() {
        assertFalse(BistScanServiceLifecyclePolicy.canPublishCompleted(ScanRunStatus.COMPLETE, persistenceSucceeded = false))
        assertTrue(BistScanServiceLifecyclePolicy.canPublishCompleted(ScanRunStatus.COMPLETE, persistenceSucceeded = true))
    }

    @Test
    fun synchronousSessionStoreRoundTripPreservesTerminalState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("manual_scan_session_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ManualScanSessionStore(context)
        val terminal = ManualScanSession(
            scanId = "terminal-round-trip",
            status = ManualScanStatus.STOPPED,
            scannedCount = 17,
            totalCount = 100,
            completedAt = 1234L,
            message = "user stop"
        )
        assertTrue(store.save(terminal, synchronous = true))
        val restored = store.load()
        assertEquals(ManualScanStatus.STOPPED, restored.status)
        assertEquals(17, restored.scannedCount)
        assertEquals(1234L, restored.completedAt)
        assertFalse(ManualScanRecoveryPolicy.recover(restored, nowMs = 9999L).isActive)
    }

    @Test
    fun atomicResultArchiveRoundTripPrecedesCompletedPublicationPolicy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LastSuccessfulScanStore(context)
        val run = ScanRun(
            scanRunId = "atomic-round-trip",
            scanStartedAt = 1000L,
            scanCompletedAt = 2000L,
            providerId = "instrumentation",
            status = ScanRunStatus.COMPLETE,
            count = 0,
            errorCount = 0
        )
        store.save(run, emptyList())
        val restored = store.load()
        assertEquals("atomic-round-trip", restored?.first?.scanRunId)
        assertEquals(0, restored?.second?.size)
        assertTrue(BistScanServiceLifecyclePolicy.canPublishCompleted(restored?.first?.status, persistenceSucceeded = restored != null))
    }

    /**
     * This test intentionally runs last. It starts a real foreground service that persists to
     * manual_scan_session_v1 and tears itself down asynchronously; running it before the direct
     * store round-trip test can race that independent persistence assertion on slower API 35 VMs.
     */
    @Test
    fun zzRealForegroundServiceNormalUnavailableExitDoesNotBecomeErrorOrCompleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
                .close()
        }
        val settings = SettingsStore(context)
        settings.apiKey = ""
        settings.experimentalProvidersEnabled = false
        settings.yahooFallbackEnabled = false
        val repository = ManualScanSessionRepository.get(context)
        assertTrue(BistScanForegroundService.start(context))

        // Service startup is asynchronous: first wait until the repository leaves the initial IDLE snapshot.
        val startDeadline = System.currentTimeMillis() + 8_000L
        var terminal = repository.snapshot()
        while (terminal.status == ManualScanStatus.IDLE && System.currentTimeMillis() < startDeadline) {
            Thread.sleep(50L)
            terminal = repository.snapshot()
        }
        assertFalse("service never left the initial IDLE state", terminal.status == ManualScanStatus.IDLE)

        val terminalDeadline = System.currentTimeMillis() + 8_000L
        while (terminal.isActive && System.currentTimeMillis() < terminalDeadline) {
            Thread.sleep(50L)
            terminal = repository.snapshot()
        }
        assertFalse("service did not reach a terminal state", terminal.isActive)
        assertEquals(ManualScanStatus.DATA_UNAVAILABLE, terminal.status)
        assertFalse(terminal.status == ManualScanStatus.COMPLETED)
        assertFalse(terminal.status == ManualScanStatus.ERROR)
    }
}
