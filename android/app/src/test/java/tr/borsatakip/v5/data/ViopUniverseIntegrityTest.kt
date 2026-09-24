package tr.borsatakip.v5.data

import org.junit.Assert.assertEquals
import org.junit.Test
import tr.borsatakip.v5.model.ViopUniverseCompleteness

class ViopUniverseIntegrityTest {
    @Test
    fun totalCountMismatchIsIncomplete() {
        assertEquals(
            ViopUniverseCompleteness.INCOMPLETE,
            ViopUniverseIntegrity.assess(providerTotal = 327, fetchedUniqueCount = 200, hasMore = false).completeness
        )
    }

    @Test
    fun missingTotalCountIsUnverifiedNotComplete() {
        assertEquals(
            ViopUniverseCompleteness.UNVERIFIED,
            ViopUniverseIntegrity.assess(providerTotal = null, fetchedUniqueCount = 200, hasMore = false).completeness
        )
    }

    @Test
    fun exactTotalIsComplete() {
        assertEquals(
            ViopUniverseCompleteness.COMPLETE,
            ViopUniverseIntegrity.assess(providerTotal = 327, fetchedUniqueCount = 327, hasMore = false).completeness
        )
    }
}
