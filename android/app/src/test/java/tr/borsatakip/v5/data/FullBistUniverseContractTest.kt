package tr.borsatakip.v5.data

import org.junit.Assert.assertTrue
import org.junit.Test

class FullBistUniverseContractTest {
    @Test fun legacyFortySymbolUniverseIsBelowProductionFloor() {
        assertTrue(40 < MobileMarketDataProvider.MIN_PRODUCTION_BIST_UNIVERSE)
    }

    @Test fun productionFloorAcceptsCurrentValidatedUniverseScale() {
        assertTrue(630 >= MobileMarketDataProvider.MIN_PRODUCTION_BIST_UNIVERSE)
    }
}
