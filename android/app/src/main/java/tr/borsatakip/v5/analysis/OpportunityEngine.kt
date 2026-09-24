package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.data.CandleReadinessPolicy
import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.model.CalculationEngineMode
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.DataSnapshot
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.OpportunitySnapshot
import tr.borsatakip.v5.model.RiskPlan
import tr.borsatakip.v5.model.TechnicalSnapshot
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.Stock
import tr.borsatakip.v5.analysis.v531.V531CalculationEngine
import tr.borsatakip.v5.analysis.v540.V540FreshnessPolicy
import tr.borsatakip.v5.analysis.v540.V540LiquidityEngine
import tr.borsatakip.v5.analysis.v531.V531CalculationInput
import tr.borsatakip.v5.analysis.v531.V531DataQualityInput
import tr.borsatakip.v5.analysis.v531.V531Direction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Fırsat Tarama 2.0
 * Gerçek OHLCV + teknik yapı + hacim + kırılım + risk/ödül + veri güveni üzerinden puanlar.
 * Çoklu zaman dilimi, benchmark/göreli güç ve piyasa rejimi verisi bu Stock sözleşmesinde yoksa
 * puana sahte katkı verilmez; bu eksikler UI'da açıkça belirtilir.
 */
object OpportunityEngine {
    object Weights {
        const val TREND = 22
        const val MOMENTUM = 14
        const val VOLUME = 15
        const val BREAKOUT = 16
        const val PULLBACK_OR_REVERSAL = 10
        const val RISK_REWARD = 13
        const val DATA_QUALITY = 10
    }

    fun score(stock: Stock, kapLabel: String = "Veri yok"): Opportunity? {
        val rawCandles = stock.candles
        val hasMalformedCandle = rawCandles.any { x ->
            x.timestamp <= 0L ||
                listOf(x.open, x.high, x.low, x.close, x.volume).any { !it.isFinite() } ||
                x.open <= 0.0 || x.high <= 0.0 || x.low <= 0.0 || x.close <= 0.0 ||
                x.high < max(x.open, x.close) || x.low > min(x.open, x.close) || x.volume < 0.0
        }
        // Invalid OHLCV must never be silently removed and converted into a seemingly usable signal.
        if (hasMalformedCandle) return null

        val c = CandleReadinessPolicy.closedCandles(stock)
        val requiredBars = CandleReadinessPolicy.requiredClosedBars()
        if (c.size < requiredBars) {
            // A structured provider snapshot may still be shown explicitly as INSUFFICIENT_DATA.
            // Bare/incomplete candle-only inputs are rejected because their quote/session identity is unverified.
            val hasStructuredQuote = stock.quotePrice?.let { it.isFinite() && it > 0.0 } == true && stock.lastBarTime > 0L
            return if (hasStructuredQuote) insufficientDataOpportunity(stock, c) else null
        }

        // Confirmed opportunity math must use the same closed candle for price, volume and indicators.
        // Live/delayed quote is a display value and must not mutate a closed-bar decision before candle close.
        val price = c.last().close
        val prev = stock.previousClose?.takeIf { it.isFinite() && it > 0.0 }
            ?: c.getOrNull(c.lastIndex - 1)?.close?.takeIf { it.isFinite() && it > 0.0 }
            ?: c.last().close
        if (!price.isFinite() || price <= 0.0 || !prev.isFinite() || prev <= 0.0) return null

        val t = TechnicalAnalyzer.analyze(c)
        val prevT = TechnicalAnalyzer.analyze(c.dropLast(1))
        val atr = t.atr14?.takeIf { it.isFinite() && it > 0.0 }
        val atrPct = atr?.let { it / price * 100.0 } ?: 99.0
        val volumeRatio = t.volumeRatio?.takeIf { it.isFinite() && it >= 0.0 }
        val rsi = t.rsi14?.takeIf { it.isFinite() }
        val prevRsi = prevT.rsi14?.takeIf { it.isFinite() }
        val macdDiff = if (t.macd?.isFinite() == true && t.macdSignal?.isFinite() == true) t.macd - t.macdSignal else null
        val prevMacdDiff = if (prevT.macd?.isFinite() == true && prevT.macdSignal?.isFinite() == true) prevT.macd - prevT.macdSignal else null

        val emaLong = t.ema20 != null && t.ema50 != null && t.ema200 != null && price > t.ema20 && t.ema20 > t.ema50 && t.ema50 > t.ema200
        val emaShort = t.ema20 != null && t.ema50 != null && t.ema200 != null && price < t.ema20 && t.ema20 < t.ema50 && t.ema50 < t.ema200

        val breakout = BreakoutVolumeEngine.evaluate(c, volumeRatio, price, atr, 20, 0.0015, 0.12)
        val breakoutUp = breakout.state == "YUKARI KIRILIM"
        val breakoutDown = breakout.state == "AŞAĞI KIRILIM"
        val volumeConfirmed = breakout.confirmed
        val strongVolume = volumeRatio != null && volumeRatio >= 2.0

        val return20 = pct(c[c.lastIndex - 20].close, price)
        val return5 = pct(c[c.lastIndex - 5].close, price)
        val return3 = pct(c[c.lastIndex - 3].close, price)

        val nearEma20 = atr != null && t.ema20 != null && abs(price - t.ema20) <= atr
        val nearSupport = atr != null && t.support != null && abs(price - t.support) <= atr * 0.8
        val nearResistance = atr != null && t.resistance != null && abs(t.resistance - price) <= atr * 0.8
        val rsiTurningUp = rsi != null && prevRsi != null && rsi > prevRsi && rsi < 55.0
        val rsiTurningDown = rsi != null && prevRsi != null && rsi < prevRsi && rsi > 45.0
        val macdTurningUp = macdDiff != null && prevMacdDiff != null && macdDiff > prevMacdDiff
        val macdTurningDown = macdDiff != null && prevMacdDiff != null && macdDiff < prevMacdDiff

        val squeeze = bollingerWidthPct(t.bbUpper, t.bbLower, price)?.let { it < 5.5 } == true && atrPct < 3.5
        val pullbackLong = emaLong && nearEma20 && return5 <= 4.0 && (rsi ?: 50.0) in 40.0..65.0
        val pullbackShort = emaShort && nearEma20 && return5 >= -4.0 && (rsi ?: 50.0) in 35.0..60.0
        val reversalLong = nearSupport && rsiTurningUp && macdTurningUp
        val reversalShort = nearResistance && rsiTurningDown && macdTurningDown

        val longParts = mutableListOf<Pair<String, Int>>()
        val shortParts = mutableListOf<Pair<String, Int>>()
        fun long(label: String, p: Int) { longParts += label to p }
        fun short(label: String, p: Int) { shortParts += label to p }

        if (emaLong) long("EMA trend uyumu", Weights.TREND)
        if (emaShort) short("EMA trend uyumu", Weights.TREND)

        if (return20 >= 2.0 && (macdDiff ?: 0.0) > 0.0) long("Momentum", Weights.MOMENTUM)
        if (return20 <= -2.0 && (macdDiff ?: 0.0) < 0.0) short("Momentum", Weights.MOMENTUM)

        when {
            strongVolume -> { long("Hacim teyidi", Weights.VOLUME); short("Hacim teyidi", Weights.VOLUME) }
            volumeConfirmed -> { long("Hacim teyidi", 11); short("Hacim teyidi", 11) }
            volumeRatio != null && volumeRatio >= 1.15 -> { long("Hacim", 6); short("Hacim", 6) }
        }

        if (breakoutUp) long("Teyitli direnç kırılımı", if (volumeConfirmed) Weights.BREAKOUT else 9)
        if (breakoutDown) short("Teyitli destek kırılımı", if (volumeConfirmed) Weights.BREAKOUT else 9)
        if (pullbackLong || reversalLong) long(if (pullbackLong) "Trend içinde geri çekilme" else "Destekte tepki", Weights.PULLBACK_OR_REVERSAL)
        if (pullbackShort || reversalShort) short(if (pullbackShort) "Trend içinde geri çekilme" else "Dirençte tepki", Weights.PULLBACK_OR_REVERSAL)
        if (squeeze && return3 > 0.0) long("Sıkışma", 6)
        if (squeeze && return3 < 0.0) short("Sıkışma", 6)

        // Legacy evidence labels are explanatory only. They must not drive final direction or RR.
        val support = t.support?.takeIf { it.isFinite() && it > 0.0 }
        val resistance = t.resistance?.takeIf { it.isFinite() && it > 0.0 }

        val sourceLower = stock.source.lowercase()
        val metadataState = stock.marketDataMetadata?.state
        val dataMode = when (metadataState) {
            tr.borsatakip.v5.model.MarketDataState.LIVE -> DataMode.REALTIME
            tr.borsatakip.v5.model.MarketDataState.DELAYED,
            tr.borsatakip.v5.model.MarketDataState.FALLBACK -> DataMode.DELAYED
            tr.borsatakip.v5.model.MarketDataState.STALE,
            tr.borsatakip.v5.model.MarketDataState.OFFLINE,
            tr.borsatakip.v5.model.MarketDataState.UNKNOWN -> DataMode.UNVERIFIED
            null -> when {
                stock.isRealtime && stock.currentSessionIncluded && stock.delaySeconds != null && stock.delaySeconds in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS -> DataMode.REALTIME
                sourceLower.contains("yahoo") || sourceLower.contains("gecik") || (stock.delaySeconds ?: 0) > RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS -> DataMode.DELAYED
                sourceLower.contains("eod") || sourceLower.contains("gün son") -> DataMode.EOD
                else -> DataMode.UNVERIFIED
            }
        }
        val integrity = RealTimeIntegrityPolicy.validate(stock)
        val dataAgeMs = integrity.measuredAgeMs ?: run {
            if (stock.receivedAt > 0L && stock.exchangeTimestamp > 0L) (stock.receivedAt - stock.exchangeTimestamp).coerceAtLeast(0L) else null
        }
        val spreadPct = if (stock.bid != null && stock.ask != null && stock.bid > 0.0 && stock.ask >= stock.bid) {
            val mid = (stock.bid + stock.ask) / 2.0
            if (mid > 0.0) ((stock.ask - stock.bid) / mid) * 100.0 else null
        } else null
        val liquidityScore = V540LiquidityEngine.score(
            volumeRatio, spreadPct, c.lastOrNull()?.volume, c.dropLast(1).map { it.volume }
        )
        val structureDistanceAtr = if (atr != null && atr > 0.0) {
            listOfNotNull(support, resistance).minOfOrNull { abs(price - it) }?.div(atr)
        } else null
        val v531 = V531CalculationEngine.calculate(
            V531CalculationInput(
                candles = c,
                technical = t,
                currentPrice = price,
                dataQuality = V531DataQualityInput(
                    dataAgeMs = dataAgeMs,
                    candleCount = c.size,
                    candlesValid = true,
                    quoteValid = stock.quotePrice?.let { it.isFinite() && it > 0.0 } == true || stock.exchangeTimestamp > 0L,
                    providerHealthy = stock.marketDataMetadata?.let { !it.isOffline && it.marketDataTimestamp > 0L && it.source.isNotBlank() } ?: stock.source.isNotBlank(),
                    symbolResolved = stock.symbol.isNotBlank() && (stock.historySymbol.isNullOrBlank() || stock.historySymbol.equals(stock.symbol, true)),
                    volumeAvailable = c.lastOrNull()?.volume?.let { it.isFinite() && it > 0.0 } == true,
                    openInterestApplicable = false,
                    openInterestAvailable = false
                ),
                spreadPct = spreadPct,
                liquidityScore = liquidityScore,
                structureDistanceAtr = structureDistanceAtr,
                evaluationTimestamp = c.lastOrNull()?.timestamp,
                technicalAsOfTimestamp = c.lastOrNull()?.timestamp
            )
        )
        val longScore = v531.longScore
        val shortScore = v531.shortScore
        val analysisDirection = OpportunityDirectionalFilterPolicy.fromScores(longScore, shortScore).name
        val score = v531.finalSignalScore
        val confidence = v531.dataConfidence
        val risk = v531.riskScore
        val freshnessScore = V540FreshnessPolicy.score(integrity.measuredAgeMs, RealTimeIntegrityPolicy.MAX_DATA_AGE_MS)
        val finalized = V540FinalDecisionFlow.finalize(
            engineDirection = v531.decision,
            finalSignalScore = score,
            dataConfidence = confidence,
            riskScore = risk,
            price = price,
            technical = t,
            candles = c,
            freshnessScore = freshnessScore,
            mtfAvailableCount = 0,
            mtfExpectedCount = 0
        )
        val outputDirection = when (finalized.publishedDirection) {
            V531Direction.LONG -> "LONG"
            V531Direction.SHORT -> "SHORT"
            V531Direction.WATCH -> "NEUTRAL"
        }
        val riskPlan = finalized.riskPlan
        val rr = finalized.rr1
        val timing = finalized.timingStatus
        val setup = finalized.setupType
        val klass = finalized.qualityClass
        val rankingScore = finalized.rankingScore
        val lowerReason = integrity.reason.lowercase()
        val failClosedState = stock.marketDataMetadata?.state
        val validity = when {
            failClosedState == tr.borsatakip.v5.model.MarketDataState.OFFLINE ||
                failClosedState == tr.borsatakip.v5.model.MarketDataState.STALE ||
                failClosedState == tr.borsatakip.v5.model.MarketDataState.UNKNOWN -> SignalValidity.REJECTED
            failClosedState == tr.borsatakip.v5.model.MarketDataState.FALLBACK ||
                failClosedState == tr.borsatakip.v5.model.MarketDataState.DELAYED -> SignalValidity.WATCH
            integrity.accepted && confidence >= 60 -> SignalValidity.VALID
            integrity.accepted -> SignalValidity.WATCH
            lowerReason.contains("ohlcv") || lowerReason.contains("en az 220") -> SignalValidity.INSUFFICIENT
            dataMode == DataMode.DELAYED || dataMode == DataMode.EOD || lowerReason.contains("güncel değil") || lowerReason.contains("gerçek zamanlı") -> SignalValidity.WATCH
            else -> SignalValidity.REJECTED
        }
        val validityReason = when (validity) {
            SignalValidity.VALID -> "Fiyat, kaynak, zaman ve zorunlu veri bütünlüğü doğrulandı."
            SignalValidity.WATCH -> if (integrity.accepted) "Teknik eğilim hesaplandı; kesin işlem sinyali için veri teyidi yetersiz." else integrity.reason
            SignalValidity.INSUFFICIENT -> integrity.reason
            SignalValidity.REJECTED -> stock.marketDataMetadata?.qualityReason?.takeIf { it.isNotBlank() } ?: integrity.reason
        }
        val publishedDirection = if (validity == SignalValidity.VALID) outputDirection else "NEUTRAL"

        val reasons = mutableListOf<String>()
        val chosen = if (outputDirection == "LONG") longParts else if (outputDirection == "SHORT") shortParts else emptyList()
        chosen.sortedByDescending { it.second }.take(5).forEach { reasons += "${it.first} (+${it.second})" }
        if (volumeRatio != null) reasons += "Hacim ${"%.2f".format(volumeRatio)}x"
        if (rsi != null) reasons += "RSI ${"%.1f".format(rsi)}"
        rr?.let { reasons += "Risk/Ödül RR1 1:${"%.2f".format(it)}" }
        finalized.rr2?.let { reasons += "Risk/Ödül RR2 1:${"%.2f".format(it)}" }

        val riskNotes = mutableListOf<String>()
        if (timing != "ZAMANINDA") riskNotes += "Hareket zamanlaması: $timing"
        if ((volumeRatio ?: 0.0) < 1.0) riskNotes += "Hacim teyidi zayıf"
        when {
            finalized.rrGateCode == "RR_UNAVAILABLE_BLOCK" -> riskNotes += "Risk/ödül hesaplanamadığı için yayın kapısı WATCH"
            finalized.rrGateCode == "RR_BELOW_1_BLOCK" -> riskNotes += "Risk/ödül 1.0 altında; yayın kapısı WATCH"
            rr != null && rr < 1.5 -> riskNotes += "Risk/ödül 1.5 altında; ranking düşük RR'yi cezalandırır"
            rr != null && rr < 2.0 -> riskNotes += "Risk/ödül 2.0 altında"
        }
        if (dataMode != DataMode.REALTIME) riskNotes += "Veri modu: ${dataMode.name}"
        if (stock.interval != "1d") riskNotes += "Tarama periyodu: ${stock.interval}"
        if (rsi != null && (rsi > 75 || rsi < 25)) riskNotes += "RSI uç bölgede"

        val decisionState = when {
            validity == SignalValidity.INSUFFICIENT -> DecisionState.INSUFFICIENT_DATA
            validity == SignalValidity.REJECTED -> DecisionState.REJECTED
            validity == SignalValidity.VALID && finalized.publishedDirection != V531Direction.WATCH -> DecisionState.VERIFIED_OPPORTUNITY
            else -> DecisionState.WATCH
        }
        val rsiRiskMessage = when {
            rsi == null -> "RSI hesaplanamadı; momentum riski doğrulanamadı."
            rsi >= 75.0 -> "RSI ${"%.1f".format(rsi)}: aşırı alım; yeni LONG girişinde geri çekilme riski yüksek."
            rsi >= 70.0 -> "RSI ${"%.1f".format(rsi)}: aşırı alım bölgesine yakın; giriş zamanlaması dikkatle izlenmeli."
            rsi <= 25.0 -> "RSI ${"%.1f".format(rsi)}: aşırı satım; yeni SHORT girişinde tepki yükselişi riski yüksek."
            rsi <= 30.0 -> "RSI ${"%.1f".format(rsi)}: aşırı satım bölgesine yakın; tepki hareketi riski izlenmeli."
            else -> null
        }
        val volumeAnomalyPct = breakout.volumeAnomalyPct

        val breakdown = buildList {
            add("CLASS=$klass")
            add("SETUP=$setup")
            add("RR1=${rr?.let { "%.4f".format(java.util.Locale.US, it) } ?: ""}")
            finalized.rr2?.let { add("RR2=${"%.4f".format(java.util.Locale.US, it)}") }
            riskPlan?.let {
                add("FINAL_STOP=${it.stop?.let { v -> "%.4f".format(java.util.Locale.US, v) } ?: ""}")
                add("FINAL_TARGET_1=${it.target1?.let { v -> "%.4f".format(java.util.Locale.US, v) } ?: ""}")
                add("FINAL_TARGET_2=${it.target2?.let { v -> "%.4f".format(java.util.Locale.US, v) } ?: ""}")
            }
            add("RR_GATE=${finalized.rrGateCode}")
            add("FINAL_FLOW=${V540FinalDecisionFlow.FLOW_VERSION}")
            add("TIMING=$timing")
            add("LONG_SCORE=$longScore")
            add("SHORT_SCORE=$shortScore")
            add("V531_ENGINE=${v531.engineVersion}")
            add("V531_AVAILABLE_WEIGHT=${v531.availableSignalWeight}")
            add("V531_CONFLICT_PENALTY=${v531.conflictPenalty}")
            v531.reasonCodes.forEach { add("V531_REASON=${it.name}") }
            v531.auditLines.forEach { add("V531_AUDIT=$it") }
            add("DECISION_STATE=${decisionState.name}")
            add("RANKING_SCORE=$rankingScore")
            integrity.measuredAgeMs?.let { add("DATA_AGE_MS=$it") }
            volumeAnomalyPct?.let { add("VOLUME_ANOMALY_PCT=${"%.2f".format(java.util.Locale.US, it)}") }
            rsiRiskMessage?.let { add("RISK_NOTE=$it") }
            reasons.forEach { add("WHY=$it") }
            riskNotes.forEach { add("RISK_NOTE=$it") }
            chosen.forEach { add("${it.first}: +${it.second}") }
            add("Risk: $risk/100 (fırsat puanından ayrı)")
            add("Veri Güveni: $confidence/100")
            add("Toplam: $score/100 • $publishedDirection • Teknik eğilim: $analysisDirection")
        }

        val confidenceLabel = when { confidence >= 80 -> "Yüksek"; confidence >= 60 -> "Orta"; else -> "Düşük" }
        val liquidity = when {
            volumeRatio == null -> "Veri yok"
            volumeRatio >= 1.5 -> "Hacim aktivitesi yüksek"
            volumeRatio >= 0.8 -> "Hacim aktivitesi orta"
            else -> "Hacim aktivitesi düşük"
        }
        val technicalLabel = when { score >= 85 -> "Çok Güçlü"; score >= 70 -> "Güçlü"; score >= 55 -> "Orta"; else -> "Zayıf" }
        val volumeLabel = volumeRatio?.let { "%.2fx".format(it) } ?: "Veri yok"
        val change = pct(prev, price)
        if (!change.isFinite()) return null

        val dataSnapshot = DataSnapshot(
            stock.source, stock.symbol, price, stock.exchangeTimestamp, stock.receivedAt,
            dataMode, stock.delaySeconds, stock.currentSessionIncluded, stock.marketDataMetadata
        )
        val snapshot = OpportunitySnapshot(stock.source, stock.symbol, price, stock.exchangeTimestamp, stock.receivedAt, dataMode, confidence, t, score, dataSnapshot = dataSnapshot)

        return Opportunity(
            symbol = stock.symbol, companyName = stock.companyName, price = price, dailyChangePct = change,
            score = score, riskScore = risk, direction = publishedDirection, technicalLabel = technicalLabel, volumeLabel = volumeLabel,
            kapLabel = kapLabel, liquidityLabel = liquidity, support = support, resistance = resistance,
            source = stock.source, dataTimestamp = stock.dataTimestamp, candles = c, technical = t, scoreBreakdown = breakdown,
            dataConfidenceScore = confidence, dataConfidenceLabel = confidenceLabel, finalSignalScore = score,
            isRealtime = stock.isRealtime, delaySeconds = stock.delaySeconds, currentSessionIncluded = stock.currentSessionIncluded,
            exchangeTimestamp = stock.exchangeTimestamp, receivedAt = stock.receivedAt, receivedElapsedRealtime = stock.receivedElapsedRealtime,
            signalGeneratedAt = System.currentTimeMillis(), dataMode = dataMode, signalValidity = validity,
            signalValidityReason = validityReason, snapshot = snapshot,
            longScore = longScore, shortScore = shortScore,
            analysisLongScore = longScore, analysisShortScore = shortScore, analysisDirection = analysisDirection,
            scoreExplanation = v531.auditLines.filter { it.startsWith("COMPONENT=") } + listOf(
                "DATA_QUALITY=$confidence", "RISK=$risk", "DIRECTION_EDGE=${kotlin.math.abs(longScore - shortScore)}"
            ),
            marketDataMetadata = stock.marketDataMetadata,
            decisionState = decisionState,
            dataAgeMs = integrity.measuredAgeMs, rsiRiskMessage = rsiRiskMessage, setupType = setup,
            qualityClass = klass, timingStatus = timing,
            volumeAnomalyPct = volumeAnomalyPct, riskPlan = riskPlan, signalScore = score, rankingScore = rankingScore,
            rankingStatus = finalized.rankingStatus, mtfAvailableCount = finalized.mtfAvailableCount,
            mtfExpectedCount = finalized.mtfExpectedCount, mtfCompletenessPct = finalized.mtfCompletenessPct,
            analysisTimeframeMinutes = stockIntervalMinutes(stock),
            calculationEngineVersion = v531.engineVersion, calculationEngineMode = CalculationEngineMode.LOCAL,
            signalAvailableWeight = v531.availableSignalWeight,
            signalReasonCodes = v531.reasonCodes.map { it.name }, signalConflictPenalty = v531.conflictPenalty
        )
    }


    private fun insufficientDataOpportunity(stock: Stock, c: List<tr.borsatakip.v5.model.Candle>): Opportunity? {
        val price = stock.quotePrice?.takeIf { it.isFinite() && it > 0.0 }
            ?: c.lastOrNull()?.close?.takeIf { it.isFinite() && it > 0.0 }
            ?: return null
        val prev = stock.previousClose?.takeIf { it.isFinite() && it > 0.0 }
            ?: c.dropLast(1).lastOrNull()?.close?.takeIf { it.isFinite() && it > 0.0 }
        val change = prev?.let { pct(it, price).takeIf { value -> value.isFinite() } }
        val integrity = RealTimeIntegrityPolicy.validate(stock)
        val dataMode = when {
            stock.isRealtime && stock.currentSessionIncluded && stock.delaySeconds != null &&
                stock.delaySeconds in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS -> DataMode.REALTIME
            stock.delaySeconds != null -> DataMode.DELAYED
            stock.interval == "1d" -> DataMode.EOD
            else -> DataMode.UNVERIFIED
        }
        val technical = TechnicalSnapshot(
            ema20 = null, ema50 = null, ema200 = null, rsi14 = null,
            macd = null, macdSignal = null, bbUpper = null, bbLower = null,
            atr14 = null, vwap = null, volumeRatio = null, support = null, resistance = null
        )
        val reason = "Teknik analiz için en az ${CandleReadinessPolicy.requiredClosedBars()} kapalı OHLCV mumu gerekli; mevcut ${c.size}."
        val breakdown = listOf(
            "CLASS=YETERSİZ VERİ",
            "SETUP=VERİ TAMAMLAMA BEKLENİYOR",
            "DECISION_STATE=${DecisionState.INSUFFICIENT_DATA.name}",
            "RANKING_SCORE=0",
            "RISK_NOTE=$reason"
        )
        return Opportunity(
            symbol = stock.symbol,
            companyName = stock.companyName,
            price = price,
            dailyChangePct = change,
            score = 0,
            riskScore = 100,
            direction = "NEUTRAL",
            technicalLabel = "Yetersiz Veri",
            volumeLabel = "Veri yok",
            kapLabel = "Veri yok",
            liquidityLabel = "Yetersiz veri",
            support = null,
            resistance = null,
            source = stock.source,
            dataTimestamp = stock.dataTimestamp,
            candles = c,
            technical = technical,
            scoreBreakdown = breakdown,
            dataConfidenceScore = 0,
            dataConfidenceLabel = "Yetersiz",
            finalSignalScore = 0,
            isRealtime = stock.isRealtime,
            delaySeconds = stock.delaySeconds,
            currentSessionIncluded = stock.currentSessionIncluded,
            exchangeTimestamp = stock.exchangeTimestamp,
            receivedAt = stock.receivedAt,
            receivedElapsedRealtime = stock.receivedElapsedRealtime,
            signalGeneratedAt = System.currentTimeMillis(),
            dataMode = dataMode,
            signalValidity = SignalValidity.INSUFFICIENT,
            signalValidityReason = if (integrity.reason.contains("TIMEFRAME_NOT_READY") || integrity.reason.contains("kapalı OHLCV")) integrity.reason else reason,
            longScore = 0,
            shortScore = 0,
            analysisLongScore = 0, analysisShortScore = 0, analysisDirection = "NEUTRAL",
            scoreExplanation = listOf("INSUFFICIENT_DATA: $reason"),
            marketDataMetadata = stock.marketDataMetadata,
            decisionState = DecisionState.INSUFFICIENT_DATA,
            dataAgeMs = integrity.measuredAgeMs,
            setupType = "VERİ TAMAMLAMA BEKLENİYOR",
            qualityClass = "YETERSİZ VERİ", timingStatus = "VERİ BEKLENİYOR",
            rankingScore = 0,
            analysisTimeframeMinutes = stockIntervalMinutes(stock)
        )
    }

    private fun stockIntervalMinutes(stock: Stock): Int {
        val raw = stock.interval.trim().lowercase()
        if (raw == "1d") return tr.borsatakip.v5.data.ScanTimeframe.DAILY_STORED_MINUTES
        return raw.removeSuffix("m").toIntOrNull()?.takeIf { it in 1..239 } ?: 0
    }

    private fun pct(from: Double, to: Double): Double = if (from > 0.0) ((to / from) - 1.0) * 100.0 else Double.NaN

    private fun bollingerWidthPct(upper: Double?, lower: Double?, price: Double): Double? =
        if (upper != null && lower != null && upper.isFinite() && lower.isFinite() && price > 0.0) ((upper - lower) / price) * 100.0 else null

    private fun calculateDataConfidence(
        price: Double, candleCount: Int, volumeRatio: Double?, support: Double?, resistance: Double?, vwap: Double?,
        ema20: Double?, ema50: Double?, ema200: Double?, rsi: Double?, macd: Double?, macdSignal: Double?, kapLabel: String
    ): Int {
        var value = 0
        if (price.isFinite() && price > 0.0) value += 20
        if (candleCount >= 220) value += 25
        if (volumeRatio?.isFinite() == true) value += 15
        if (support?.isFinite() == true && resistance?.isFinite() == true) value += 10
        if (vwap?.isFinite() == true) value += 10
        if (ema20?.isFinite() == true && ema50?.isFinite() == true && ema200?.isFinite() == true) value += 10
        if (rsi?.isFinite() == true) value += 5
        if (macd?.isFinite() == true && macdSignal?.isFinite() == true) value += 3
        if (!kapLabel.equals("Veri yok", true) && kapLabel.isNotBlank()) value += 2
        return value.coerceIn(0, 100)
    }
}
