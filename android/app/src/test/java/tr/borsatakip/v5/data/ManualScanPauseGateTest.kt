package tr.borsatakip.v5.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualScanPauseGateTest {
    @Test
    fun paused_gate_blocks_until_resumed() = runTest {
        ManualScanPauseGate.pause()
        val waiter = async { ManualScanPauseGate.awaitIfPaused(50L); true }
        advanceTimeBy(200L)
        assertFalse(waiter.isCompleted)
        ManualScanPauseGate.resume()
        advanceTimeBy(60L)
        assertTrue(waiter.await())
        assertFalse(ManualScanPauseGate.isPaused())
    }
}
