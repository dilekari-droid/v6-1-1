package tr.borsatakip.v5.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.TechnicalSnapshot

class OpportunityUiPolicyTest {
    private fun opportunity(
        direction: String,
        decision: DecisionState,
        longScore: Int = 0,
        shortScore: Int = 0
    ) = Opportunity(
        symbol = "TEST",
        companyName = "Test",
        price = 10.0,
        dailyChangePct = 1.0,
        score = 70,
        riskScore = 20,
        direction = direction,
        technicalLabel = "OK",
        volumeLabel = "OK",
        kapLabel = "Veri yok",
        liquidityLabel = "OK",
        support = null,
        resistance = null,
        source = "test",
        dataTimestamp = 1L,
        candles = emptyList(),
        technical = TechnicalSnapshot(null, null, null, null, null, null, null, null, null, null, null, null, null),
        decisionState = decision,
        longScore = longScore,
        shortScore = shortScore
    )

    @Test
    fun data_absence_is_not_rendered_as_zero_opportunity() {
        val metrics = OpportunityUiPolicy.metrics(OpportunityUiDataState.UNAVAILABLE, 630, emptyList())
        assertEquals("—", metrics.watched)
        assertEquals("—", metrics.opportunities)
        assertEquals("Veri yok", metrics.dataQuality)
    }


    @Test
    fun analyzing_state_is_not_rendered_as_numeric_result() {
        val metrics = OpportunityUiPolicy.metrics(OpportunityUiDataState.ANALYZING, 630, emptyList())
        assertEquals("—", metrics.watched)
        assertEquals("—", metrics.opportunities)
        assertEquals("Analiz ediliyor", metrics.dataQuality)
    }

    @Test
    fun ready_state_counts_only_verified_long_short_as_opportunity() {
        val items = listOf(
            opportunity("LONG", DecisionState.VERIFIED_OPPORTUNITY, longScore = 92),
            opportunity("SHORT", DecisionState.VERIFIED_OPPORTUNITY, shortScore = 88),
            opportunity("LONG", DecisionState.WATCH, longScore = 95),
            opportunity("NEUTRAL", DecisionState.INSUFFICIENT_DATA)
        )
        val metrics = OpportunityUiPolicy.metrics(OpportunityUiDataState.READY, 630, items)
        assertEquals("630", metrics.watched)
        assertEquals("2", metrics.opportunities)
        assertEquals("Hazır", metrics.dataQuality)
    }

    @Test
    fun direction_and_strength_follow_long_short_contract() {
        val strongLong = opportunity("LONG", DecisionState.VERIFIED_OPPORTUNITY, longScore = 92)
        val weakShort = opportunity("SHORT", DecisionState.VERIFIED_OPPORTUNITY, shortScore = 60)
        assertEquals("LONG", OpportunityUiPolicy.directionLabel(strongLong))
        assertTrue(OpportunityUiPolicy.isStrong(strongLong))
        assertEquals("SHORT", OpportunityUiPolicy.directionLabel(weakShort))
        assertFalse(OpportunityUiPolicy.isStrong(weakShort))
    }
}
