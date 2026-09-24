package tr.borsatakip.v5.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.MarketDataMetadata
import tr.borsatakip.v5.model.MarketDataState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.TechnicalSnapshot
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopContractType
import tr.borsatakip.v5.model.ViopOpportunity
import tr.borsatakip.v5.model.ViopQuote
import tr.borsatakip.v5.model.ViopPublishedSignalDirection

class ViopProductionPolicyTest {
    private val technical = TechnicalSnapshot(
        ema20 = null, ema50 = null, ema200 = null, rsi14 = null,
        macd = null, macdSignal = null, bbUpper = null, bbLower = null,
        atr14 = null, vwap = null, volumeRatio = null, support = null, resistance = null
    )

    private fun contract(validity: SignalValidity = SignalValidity.VALID, type: ViopContractType = ViopContractType.FUTURE) = ViopContract(
        symbol = "F_XU0300926", underlying = "XU030", expiry = "2026-09",
        contractType = type, tickSize = 0.25, multiplier = 10.0,
        providerId = "backend", providerLabel = "backend", dataTimestamp = 1_700_000_000_000L,
        isRealtime = true, delaySeconds = 0, currentSessionIncluded = true,
        receivedAt = 1_700_000_000_100L, dataMode = DataMode.REALTIME,
        validity = validity, validityReason = validity.name,
        lastTradingAt = 2_000_000_000_000L, expiryAt = 2_000_000_000_000L
    )

    private fun metadata(ts: Long) = MarketDataMetadata(
        providerId = "backend_viop", source = "backend", marketDataTimestamp = ts,
        deviceReceivedAt = ts + 100L, isLive = true, isDelayed = false,
        delayDurationMs = 0L, lastSuccessfulUpdateAt = ts + 100L,
        state = MarketDataState.LIVE, isFallback = false, isOffline = false
    )

    private fun quote(ts: Long, symbol: String = "F_XU0300926") = ViopQuote(
        symbol = symbol, price = 10_000.0, bid = 9_999.0, ask = 10_001.0,
        dailyChangePct = 1.0, volume = 1000.0, openInterest = 5000L,
        exchangeTimestamp = ts, receivedAt = ts + 100L, source = "backend",
        realtime = true, delaySeconds = 0, currentSessionIncluded = true,
        marketDataMetadata = metadata(ts)
    )

    private fun opportunity(direction: String): ViopOpportunity {
        val c = contract()
        val q = quote(1_700_000_000_000L)
        return ViopOpportunity(
            contract = c, quote = q, candles = emptyList(), technical = technical,
            technicalScore = 80, riskScore = 20, liquidityScore = 90, expiryRisk = 20,
            longScore = if (direction == "LONG") 80 else 20,
            shortScore = if (direction == "SHORT") 80 else 20,
            finalScore = 80, direction = direction, signalReason = "test",
            validity = SignalValidity.VALID, dataAgeMs = 1000L, historyCandleCount = 220,
            decisionState = if (direction == "WATCH") DecisionState.WATCH else DecisionState.VERIFIED_OPPORTUNITY,
            publishedSignalDirection = when (direction) { "LONG" -> ViopPublishedSignalDirection.LONG; "SHORT" -> ViopPublishedSignalDirection.SHORT; else -> ViopPublishedSignalDirection.WATCH }
        )
    }

    @Test
    fun insufficientContractCannotEnterAnalysisOrPublish() {
        val insufficient = contract(SignalValidity.INSUFFICIENT)
        assertEquals(
            ViopProductionPolicy.ContractDisposition.INSUFFICIENT_DATA,
            ViopProductionPolicy.contractDisposition(insufficient)
        )
        val item = opportunity("LONG").copy(contract = insufficient)
        assertEquals(ViopSignalPolicy.PublishedDirection.WATCH, ViopSignalPolicy.publishedDirection(item))
    }

    @Test
    fun staleQuoteIsRejected() {
        val now = 1_700_000_100_000L
        val stale = quote(now - ViopScanner.MAX_DATA_AGE_MS - 1L)
        assertEquals("STALE_DATA", ViopProductionPolicy.quoteRejectionCode(contract(), stale, now))
    }

    @Test
    fun publishedPlusExcludedAlwaysMatchesUniverse() {
        val items = listOf(opportunity("LONG"), opportunity("SHORT"), opportunity("WATCH"))
        val accounting = ViopProductionPolicy.accounting(
            universeTotal = 6, opportunities = items, insufficient = 1, rejected = 1, noData = 1
        )
        assertEquals(3, accounting.published)
        assertEquals(1, accounting.longCount)
        assertEquals(1, accounting.shortCount)
        assertEquals(1, accounting.watchCount)
        assertEquals(6, accounting.accountedTotal)
        assertTrue(accounting.consistent)
    }

    @Test
    fun underlyingBiasCanNeverBecomePublishedViopSignal() {
        val item = Opportunity(
            symbol = "THYAO", companyName = null, price = 100.0, dailyChangePct = 1.0,
            score = 80, riskScore = 20, direction = "LONG", technicalLabel = "LONG",
            volumeLabel = "OK", kapLabel = "-", liquidityLabel = "OK", support = null,
            resistance = null, source = "test", dataTimestamp = 1L, candles = emptyList(), technical = technical,
            analysisLongScore = 80, analysisShortScore = 20, analysisDirection = "LONG"
        )
        assertEquals(ViopSignalPolicy.AnalysisBias.LONG, ViopSignalPolicy.analysisBias(item))
        assertEquals(ViopSignalPolicy.PublishedDirection.WATCH, ViopSignalPolicy.publishedDirection(item))
    }

    @Test
    fun optionContractIsExplicitlyOutOfScope() {
        val option = contract(type = ViopContractType.parse("Opsiyon"))
        assertEquals(ViopProductionPolicy.ContractDisposition.UNSUPPORTED_OPTION, ViopProductionPolicy.contractDisposition(option))
        assertFalse(ViopProductionPolicy.contractDisposition(option) == ViopProductionPolicy.ContractDisposition.ANALYZE)
    }
    @Test
    fun symbolMismatchIsRejected() {
        val now = 1_700_000_000_500L
        assertEquals("SYMBOL_MISMATCH", ViopProductionPolicy.quoteRejectionCode(contract(), quote(1_700_000_000_000L, "F_WRONG"), now))
    }

    @Test
    fun futureTimestampIsRejected() {
        val now = 1_700_000_000_000L
        val future = quote(now + tr.borsatakip.v5.data.RealTimeIntegrityPolicy.MAX_FUTURE_CLOCK_SKEW_MS + 1L)
        assertEquals("FUTURE_TIMESTAMP", ViopProductionPolicy.quoteRejectionCode(contract(), future, now))
    }

    @Test
    fun missingMetadataIsRejected() {
        val now = 1_700_000_000_500L
        val q = quote(1_700_000_000_000L).copy(marketDataMetadata = null)
        assertEquals("METADATA_MISSING", ViopProductionPolicy.quoteRejectionCode(contract(), q, now))
    }

    @Test
    fun nonLiveMetadataIsRejected() {
        val now = 1_700_000_000_500L
        val q = quote(1_700_000_000_000L).copy(
            marketDataMetadata = metadata(1_700_000_000_000L).copy(state = MarketDataState.DELAYED, isLive = false, isDelayed = true)
        )
        assertEquals("DATA_NOT_LIVE", ViopProductionPolicy.quoteRejectionCode(contract(), q, now))
    }

    @Test
    fun unknownContractTypeCannotEnterAnalysis() {
        val unknown = contract(type = ViopContractType.UNKNOWN)
        assertEquals(ViopProductionPolicy.ContractDisposition.REJECTED, ViopProductionPolicy.contractDisposition(unknown))
    }

}
