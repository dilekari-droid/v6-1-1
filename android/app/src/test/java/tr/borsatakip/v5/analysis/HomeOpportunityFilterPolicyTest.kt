package tr.borsatakip.v5.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.RankingStatus
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.TechnicalSnapshot

class HomeOpportunityFilterPolicyTest {
    private val now = 1_700_000_000_000L

    @Test
    fun all_long_short_use_one_canonical_result_set() {
        val rows = listOf(
            row("L1", "LONG", 92, 10),
            row("L2", "LONG", 85, 20),
            row("L3", "LONG", 80, 25),
            row("S1", "SHORT", 15, 94),
            row("S2", "SHORT", 20, 88)
        )

        assertEquals(5, HomeOpportunityFilterPolicy.filter(rows, HomeOpportunityFilterPolicy.Filter.ALL, now).size)
        assertEquals(3, HomeOpportunityFilterPolicy.filter(rows, HomeOpportunityFilterPolicy.Filter.LONG, now).size)
        assertEquals(2, HomeOpportunityFilterPolicy.filter(rows, HomeOpportunityFilterPolicy.Filter.SHORT, now).size)
    }

    @Test
    fun long_and_short_never_fall_back_to_all_when_empty() {
        val onlyShort = listOf(row("S1", "SHORT", 10, 90))
        assertTrue(HomeOpportunityFilterPolicy.filter(onlyShort, HomeOpportunityFilterPolicy.Filter.LONG, now).isEmpty())

        val onlyLong = listOf(row("L1", "LONG", 90, 10))
        assertTrue(HomeOpportunityFilterPolicy.filter(onlyLong, HomeOpportunityFilterPolicy.Filter.SHORT, now).isEmpty())
    }

    @Test
    fun watch_is_not_a_home_opportunity_in_any_tab() {
        val watch = row("W", "NEUTRAL", 40, 40, validity = SignalValidity.WATCH, decision = DecisionState.WATCH)
        assertTrue(HomeOpportunityFilterPolicy.filter(listOf(watch), HomeOpportunityFilterPolicy.Filter.ALL, now).isEmpty())
        assertTrue(HomeOpportunityFilterPolicy.filter(listOf(watch), HomeOpportunityFilterPolicy.Filter.LONG, now).isEmpty())
        assertTrue(HomeOpportunityFilterPolicy.filter(listOf(watch), HomeOpportunityFilterPolicy.Filter.SHORT, now).isEmpty())
    }

    @Test
    fun unavailable_analysis_is_not_rendered_as_zero_score_opportunity() {
        val insufficient = row(
            "NO_DATA", "NEUTRAL", 0, 0,
            validity = SignalValidity.INSUFFICIENT,
            decision = DecisionState.INSUFFICIENT_DATA,
            mode = DataMode.DELAYED,
            ranking = 0
        )
        val unverified = row("UNVERIFIED", "NEUTRAL", 0, 0, mode = DataMode.UNVERIFIED, ranking = 0)

        assertFalse(HomeOpportunityFilterPolicy.hasRenderableAnalysis(insufficient))
        assertFalse(HomeOpportunityFilterPolicy.hasRenderableAnalysis(unverified))
        assertTrue(HomeOpportunityFilterPolicy.filter(listOf(insufficient, unverified), HomeOpportunityFilterPolicy.Filter.ALL, now).isEmpty())
    }

    @Test
    fun analyzed_zero_watch_is_not_promoted_to_home_opportunity() {
        val analyzedZero = row(
            "ZERO", "NEUTRAL", 0, 0,
            validity = SignalValidity.WATCH,
            decision = DecisionState.WATCH,
            mode = DataMode.DELAYED,
            ranking = 0
        )
        assertTrue(HomeOpportunityFilterPolicy.hasRenderableAnalysis(analyzedZero))
        assertTrue(HomeOpportunityFilterPolicy.filter(listOf(analyzedZero), HomeOpportunityFilterPolicy.Filter.ALL, now).isEmpty())
    }

    @Test
    fun direction_sort_is_applied_after_filtering() {
        val weak = row("WEAK", "LONG", 81, 10, ranking = 99)
        val strong = row("STRONG", "LONG", 94, 5, ranking = 70)
        val sorted = HomeOpportunityFilterPolicy.filterAndSort(
            listOf(weak, strong), HomeOpportunityFilterPolicy.Filter.LONG, now
        )
        assertEquals(listOf("STRONG", "WEAK"), sorted.map { it.symbol })
    }

    @Test
    fun saved_filter_restore_is_deterministic() {
        assertEquals(HomeOpportunityFilterPolicy.Filter.LONG, HomeOpportunityFilterPolicy.Filter.restore("LONG"))
        assertEquals(HomeOpportunityFilterPolicy.Filter.SHORT, HomeOpportunityFilterPolicy.Filter.restore("SHORT"))
        assertEquals(HomeOpportunityFilterPolicy.Filter.ALL, HomeOpportunityFilterPolicy.Filter.restore(null))
        assertEquals(HomeOpportunityFilterPolicy.Filter.ALL, HomeOpportunityFilterPolicy.Filter.restore("UNKNOWN"))
    }

    private fun row(
        symbol: String,
        direction: String,
        longScore: Int,
        shortScore: Int,
        validity: SignalValidity = SignalValidity.VALID,
        decision: DecisionState = DecisionState.VERIFIED_OPPORTUNITY,
        mode: DataMode = DataMode.REALTIME,
        ranking: Int = maxOf(longScore, shortScore)
    ): Opportunity = Opportunity(
        symbol = symbol,
        companyName = symbol,
        price = 100.0,
        dailyChangePct = 1.0,
        score = maxOf(longScore, shortScore),
        riskScore = 30,
        direction = direction,
        technicalLabel = "TEST",
        volumeLabel = "TEST",
        kapLabel = "TEST",
        liquidityLabel = "TEST",
        support = null,
        resistance = null,
        source = "TEST",
        dataTimestamp = now,
        candles = emptyList<Candle>(),
        technical = TechnicalSnapshot(null, null, null, null, null, null, null, null, null, null, null, null, null),
        dataConfidenceScore = 90,
        finalSignalScore = maxOf(longScore, shortScore),
        isRealtime = mode == DataMode.REALTIME,
        delaySeconds = if (mode == DataMode.REALTIME) 0 else 900,
        currentSessionIncluded = mode == DataMode.REALTIME,
        exchangeTimestamp = now,
        receivedAt = now,
        signalGeneratedAt = now,
        dataMode = mode,
        signalValidity = validity,
        longScore = longScore,
        shortScore = shortScore,
        analysisLongScore = longScore,
        analysisShortScore = shortScore,
        analysisDirection = direction,
        decisionState = decision,
        dataAgeMs = 0L,
        rankingScore = ranking,
        rankingStatus = RankingStatus.CALCULATED
    )
}
