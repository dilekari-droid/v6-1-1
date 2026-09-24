package tr.borsatakip.v5.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.TechnicalSnapshot

class OpportunitySortPolicyTest {
    private fun item(
        symbol: String,
        price: Double,
        change: Double?,
        volume: Double,
        direction: String,
        longScore: Int = 0,
        shortScore: Int = 0
    ) = Opportunity(
        symbol = symbol,
        companyName = symbol,
        price = price,
        dailyChangePct = change,
        score = 0,
        riskScore = 0,
        direction = direction,
        technicalLabel = "",
        volumeLabel = "",
        kapLabel = "",
        liquidityLabel = "",
        support = null,
        resistance = null,
        source = "test",
        dataTimestamp = 1L,
        candles = listOf(Candle(1L, price, price, price, price, volume)),
        technical = TechnicalSnapshot(null, null, null, null, null, null, null, null, null, null, null, null, null),
        decisionState = DecisionState.VERIFIED_OPPORTUNITY,
        longScore = longScore,
        shortScore = shortScore
    )

    private val rows = listOf(
        item("AAA", 10.0, 1.0, 100.0, "LONG", longScore = 60),
        item("BBB", 30.0, -2.0, 300.0, "SHORT", shortScore = 90),
        item("CCC", 20.0, 4.0, 200.0, "LONG", longScore = 80)
    )

    @Test
    fun default_sort_is_explicitly_trend_strength() {
        assertEquals(DiscoverySort.TREND_STRENGTH, OpportunitySortPolicy.defaultSort)
        assertEquals(listOf("BBB", "CCC", "AAA"), OpportunitySortPolicy.sort(rows, DiscoverySort.TREND_STRENGTH).map { it.symbol })
    }

    @Test
    fun report_sort_modes_use_real_model_fields() {
        assertEquals(listOf("CCC", "AAA", "BBB"), OpportunitySortPolicy.sort(rows, DiscoverySort.CHANGE).map { it.symbol })
        assertEquals(listOf("BBB", "CCC", "AAA"), OpportunitySortPolicy.sort(rows, DiscoverySort.VOLUME).map { it.symbol })
        assertEquals(listOf("BBB", "CCC", "AAA"), OpportunitySortPolicy.sort(rows, DiscoverySort.PRICE).map { it.symbol })
    }

    @Test
    fun sort_cycle_is_deterministic() {
        assertEquals(DiscoverySort.CHANGE, OpportunitySortPolicy.next(DiscoverySort.TREND_STRENGTH))
        assertEquals(DiscoverySort.VOLUME, OpportunitySortPolicy.next(DiscoverySort.CHANGE))
        assertEquals(DiscoverySort.PRICE, OpportunitySortPolicy.next(DiscoverySort.VOLUME))
        assertEquals(DiscoverySort.TREND_STRENGTH, OpportunitySortPolicy.next(DiscoverySort.PRICE))
    }
}
