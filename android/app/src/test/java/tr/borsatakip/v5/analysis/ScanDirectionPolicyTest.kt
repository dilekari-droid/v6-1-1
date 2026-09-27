package tr.borsatakip.v5.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.TechnicalSnapshot

class ScanDirectionPolicyTest {
    private val technical = TechnicalSnapshot(
        ema20 = null, ema50 = null, ema200 = null, rsi14 = null,
        macd = null, macdSignal = null, bbUpper = null, bbLower = null,
        atr14 = null, vwap = null, volumeRatio = null, support = null, resistance = null
    )

    private fun row(
        direction: String = "NEUTRAL",
        analysisDirection: String = "NEUTRAL",
        longScore: Int = 0,
        shortScore: Int = 0,
        mode: DataMode = DataMode.REALTIME,
        realtime: Boolean = true,
        validity: SignalValidity = SignalValidity.VALID,
        confidence: Int = 90
    ) = Opportunity(
        symbol = "TEST", companyName = null, price = 100.0, dailyChangePct = 1.0,
        score = 80, riskScore = 20, direction = direction, technicalLabel = "TEST",
        volumeLabel = "TEST", kapLabel = "TEST", liquidityLabel = "TEST",
        support = null, resistance = null, source = "TEST", dataTimestamp = 1L,
        candles = emptyList(), technical = technical, dataConfidenceScore = confidence,
        finalSignalScore = 88, isRealtime = realtime, dataMode = mode, signalValidity = validity,
        analysisLongScore = longScore, analysisShortScore = shortScore, analysisDirection = analysisDirection
    )

    @Test fun trend_alone_cannot_create_long() {
        val x = row(direction = "NEUTRAL", analysisDirection = "NEUTRAL", longScore = 90, shortScore = 20)
        assertEquals(OpportunityDirectionalFilterPolicy.Direction.NEUTRAL, ScanDirectionPolicy.classify(x).direction)
    }

    @Test fun verified_realtime_long_is_published_signal() {
        val x = row(direction = "LONG", analysisDirection = "LONG", longScore = 90, shortScore = 30)
        val c = ScanDirectionPolicy.classify(x)
        assertEquals(OpportunityDirectionalFilterPolicy.Direction.LONG, c.direction)
        assertTrue(c.publishedSignal)
        assertEquals(88, c.strength)
    }

    @Test fun delayed_long_is_technical_direction_not_trade_signal() {
        val x = row(direction = "NEUTRAL", analysisDirection = "LONG", longScore = 86, shortScore = 30, mode = DataMode.DELAYED, realtime = false, validity = SignalValidity.WATCH)
        val c = ScanDirectionPolicy.classify(x)
        assertEquals(OpportunityDirectionalFilterPolicy.Direction.LONG, c.direction)
        assertFalse(c.publishedSignal)
        assertEquals(86, c.strength)
    }

    @Test fun insufficient_or_unverified_data_stays_neutral() {
        val insufficient = row(analysisDirection = "SHORT", longScore = 20, shortScore = 90, validity = SignalValidity.INSUFFICIENT)
        val unverified = row(analysisDirection = "SHORT", longScore = 20, shortScore = 90, mode = DataMode.UNVERIFIED)
        assertEquals(OpportunityDirectionalFilterPolicy.Direction.NEUTRAL, ScanDirectionPolicy.classify(insufficient).direction)
        assertEquals(OpportunityDirectionalFilterPolicy.Direction.NEUTRAL, ScanDirectionPolicy.classify(unverified).direction)
    }
}
