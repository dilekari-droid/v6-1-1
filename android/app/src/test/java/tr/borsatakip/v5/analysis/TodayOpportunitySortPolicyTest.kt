package tr.borsatakip.v5.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.TechnicalSnapshot

class TodayOpportunitySortPolicyTest {
    @Test
    fun long_orders_by_analysisLongScore_before_general_ranking() {
        val a = row("A", long = 91, short = 20, final = 91, confidence = 80, ranking = 82)
        val b = row("B", long = 86, short = 10, final = 86, confidence = 90, ranking = 95)

        val sorted = TodayOpportunitySortPolicy.sort(listOf(b, a), TodayOpportunitySortPolicy.Mode.LONG)

        assertEquals(listOf("A", "B"), sorted.map { it.symbol })
    }

    @Test
    fun short_orders_by_analysisShortScore_before_general_ranking() {
        val x = row("X", long = 10, short = 95, final = 95, confidence = 80, ranking = 80)
        val y = row("Y", long = 20, short = 89, final = 89, confidence = 95, ranking = 99)

        val sorted = TodayOpportunitySortPolicy.sort(listOf(y, x), TodayOpportunitySortPolicy.Mode.SHORT)

        assertEquals(listOf("X", "Y"), sorted.map { it.symbol })
    }

    @Test
    fun long_tie_uses_final_then_confidence() {
        val a = row("A", long = 90, short = 10, final = 88, confidence = 80)
        val b = row("B", long = 90, short = 10, final = 92, confidence = 60)
        val c = row("C", long = 90, short = 10, final = 92, confidence = 90)

        val sorted = TodayOpportunitySortPolicy.sort(listOf(a, b, c), TodayOpportunitySortPolicy.Mode.LONG)

        assertEquals(listOf("C", "B", "A"), sorted.map { it.symbol })
    }

    @Test
    fun short_tie_uses_final_then_confidence() {
        val a = row("A", long = 10, short = 90, final = 88, confidence = 80)
        val b = row("B", long = 10, short = 90, final = 92, confidence = 60)
        val c = row("C", long = 10, short = 90, final = 92, confidence = 90)

        val sorted = TodayOpportunitySortPolicy.sort(listOf(a, b, c), TodayOpportunitySortPolicy.Mode.SHORT)

        assertEquals(listOf("C", "B", "A"), sorted.map { it.symbol })
    }

    private fun row(
        symbol: String,
        long: Int,
        short: Int,
        final: Int,
        confidence: Int,
        ranking: Int = final,
        risk: Int = 40
    ): Opportunity = Opportunity(
        symbol = symbol,
        companyName = null,
        price = 100.0,
        dailyChangePct = 0.0,
        score = final,
        riskScore = risk,
        direction = if (long >= short) "LONG" else "SHORT",
        technicalLabel = "",
        volumeLabel = "",
        kapLabel = "",
        liquidityLabel = "",
        support = null,
        resistance = null,
        source = "TEST",
        dataTimestamp = 1L,
        candles = emptyList<Candle>(),
        technical = TechnicalSnapshot(
            ema20 = null, ema50 = null, ema200 = null, rsi14 = null,
            macd = null, macdSignal = null, bbUpper = null, bbLower = null,
            atr14 = null, vwap = null, volumeRatio = null, support = null, resistance = null
        ),
        dataConfidenceScore = confidence,
        finalSignalScore = final,
        longScore = long,
        shortScore = short,
        analysisLongScore = long,
        analysisShortScore = short,
        rankingScore = ranking
    )
}
