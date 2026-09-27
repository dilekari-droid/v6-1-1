package tr.borsatakip.v5.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.TechnicalSnapshot

class ScanResultSortPolicyTest {
    private val technical = TechnicalSnapshot(
        ema20 = null, ema50 = null, ema200 = null, rsi14 = null,
        macd = null, macdSignal = null, bbUpper = null, bbLower = null,
        atr14 = null, vwap = null, volumeRatio = null, support = null, resistance = null
    )

    private fun row(symbol: String, strength: Int, technicalScore: Int, confidence: Int) = Opportunity(
        symbol = symbol,
        companyName = null,
        price = 100.0,
        dailyChangePct = 0.0,
        score = technicalScore,
        riskScore = 0,
        direction = "LONG",
        technicalLabel = "TEST",
        volumeLabel = "TEST",
        kapLabel = "TEST",
        liquidityLabel = "TEST",
        support = null,
        resistance = null,
        source = "TEST",
        dataTimestamp = 1L,
        candles = emptyList(),
        technical = technical,
        dataConfidenceScore = confidence,
        finalSignalScore = strength,
        isRealtime = true,
        dataMode = DataMode.REALTIME,
        signalValidity = SignalValidity.VALID,
        analysisLongScore = strength,
        analysisShortScore = 0,
        analysisDirection = "LONG"
    )

    @Test fun directional_sort_uses_strength_then_technical_then_confidence_then_symbol() {
        val rows = listOf(
            row("A", 80, 99, 99),
            row("B", 90, 70, 60),
            row("C", 90, 80, 70),
            row("D", 90, 80, 80)
        )
        assertEquals(listOf("D", "C", "B", "A"), ScanResultSortPolicy.directional(rows).map { it.symbol })
    }
}
