package tr.borsatakip.v5.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViopScanGenerationGuardTest {
    @Test fun olderScanCanNeverOverwriteNewerScan() {
        val guard = ViopScanGenerationGuard()
        val oldScan = guard.next()
        assertTrue(guard.accepts(oldScan))
        val newScan = guard.next()
        assertFalse(guard.accepts(oldScan))
        assertTrue(guard.accepts(newScan))
    }
}
