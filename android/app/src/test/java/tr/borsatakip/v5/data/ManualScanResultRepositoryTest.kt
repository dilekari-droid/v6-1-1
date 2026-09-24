package tr.borsatakip.v5.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ManualScanResultRepositoryTest {
    @Before
    fun reset() = ManualScanResultRepository.resetForTests()

    @Test
    fun emptyPublishIsSharedAsOneState() {
        ManualScanResultRepository.publish(emptyList(), scanRunId = "run-empty", source = "TEST")
        val state = ManualScanResultRepository.snapshot()
        assertTrue(state.items.isEmpty())
        assertEquals("run-empty", state.scanRunId)
        assertEquals("TEST", state.source)
    }
}
