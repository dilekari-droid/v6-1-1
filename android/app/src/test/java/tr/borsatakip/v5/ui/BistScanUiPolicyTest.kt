package tr.borsatakip.v5.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tr.borsatakip.v5.data.ManualScanSession
import tr.borsatakip.v5.data.ManualScanStatus

class BistScanUiPolicyTest {
    @Test
    fun active_scan_never_renders_fake_100_percent() {
        val session = ManualScanSession(
            status = ManualScanStatus.FINALIZING,
            scannedCount = 630,
            totalCount = 630,
            startedAt = 1_000,
            lastUpdate = 61_000
        )
        assertEquals(99, BistScanUiPolicy.progressPercent(session))
        assertEquals("630 / 630  •  %99", BistScanUiPolicy.progressLabel(session))
    }

    @Test
    fun completed_scan_may_render_real_100_percent() {
        val session = ManualScanSession(
            status = ManualScanStatus.COMPLETED,
            scannedCount = 630,
            totalCount = 630,
            startedAt = 1_000,
            completedAt = 61_000,
            lastUpdate = 61_000
        )
        assertEquals(100, BistScanUiPolicy.progressPercent(session))
        assertEquals("Süre: 1 dk 0 sn", BistScanUiPolicy.elapsedLabel(session, nowMs = 61_000))
    }
}
