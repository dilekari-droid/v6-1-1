package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.analysis.v531.V531Direction
import tr.borsatakip.v5.analysis.v539.V539RiskRewardGate
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.RiskPlan
import tr.borsatakip.v5.model.TechnicalSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V5.3.9 canonical downstream flow.
 *
 * Signal math is finalized first (including MTF/context). Only then are risk plan, RR gate,
 * timing, setup and ranking derived. RR is deliberately NOT mixed into V531 signalScore;
 * it is an explicit publication/ranking gate so signal score keeps one stable meaning.
 */
object V539FinalDecisionFlow {
    const val FLOW_VERSION = "V5.3.9-FINAL-FLOW-V1"

    data class Result(
        val engineDirection: V531Direction,
        val publishedDirection: V531Direction,
        val riskPlan: RiskPlan?,
        val rr1: Double?,
        val rr2: Double?,
        val rrGateCode: String,
        val timingStatus: String,
        val setupType: String,
        val qualityClass: String,
        val rankingScore: Int
    )

    fun finalize(
        engineDirection: V531Direction,
        finalSignalScore: Int,
        dataConfidence: Int,
        riskScore: Int,
        price: Double,
        technical: TechnicalSnapshot,
        candles: List<Candle>,
        freshnessScore: Int,
        marketRegimeAdjustment: Int = 0
    ): Result {
        val rawPlan = buildRiskPlan(engineDirection, price, technical.atr14, technical.support, technical.resistance, candles)
        val rr1 = rawPlan?.rr1
        val rr2 = rawPlan?.rr2
        val rrGate = V539RiskRewardGate.evaluate(engineDirection, rr1)
        val publishedDirection = rrGate.publishedDirection
        val gate = rrGate.code
        val finalPlan = if (publishedDirection == V531Direction.WATCH) null else rawPlan
        val timing = timingStatus(publishedDirection, price, technical, candles)
        val setup = setupType(publishedDirection, price, technical, candles)
        val baseRanking = OpportunityRankingPolicy.calculateFinal(
            finalSignalScore = finalSignalScore,
            dataConfidence = dataConfidence,
            riskScore = riskScore,
            rr = rr1,
            relativeVolume = technical.volumeRatio,
            freshnessScore = freshnessScore,
            marketRegimeAdjustment = marketRegimeAdjustment
        )
        // A publication-blocked WATCH must not outrank a publishable opportunity.
        val ranking = if (publishedDirection == V531Direction.WATCH) 0 else baseRanking
        val quality = when {
            publishedDirection == V531Direction.WATCH -> "İZLE"
            finalSignalScore >= 85 && dataConfidence >= 75 && (rr1 ?: 0.0) >= 2.0 && timing != "GEÇ KALINMIŞ" -> "A SINIFI"
            finalSignalScore >= 70 && dataConfidence >= 60 && timing != "GEÇ KALINMIŞ" -> "B SINIFI"
            finalSignalScore >= 55 -> "İZLE"
            else -> "RED"
        }
        return Result(engineDirection, publishedDirection, finalPlan, rr1, rr2, gate, timing, setup, quality, ranking)
    }

    fun buildRiskPlan(
        direction: V531Direction,
        price: Double,
        atrRaw: Double?,
        supportRaw: Double?,
        resistanceRaw: Double?,
        candles: List<Candle>
    ): RiskPlan? {
        if (direction == V531Direction.WATCH || !price.isFinite() || price <= 0.0) return null
        val atr = atrRaw?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val support = supportRaw?.takeIf { it.isFinite() && it > 0.0 }
        val resistance = resistanceRaw?.takeIf { it.isFinite() && it > 0.0 }
        val structuralWindow = candles.dropLast(1).takeLast(20)
        val swingLow = structuralWindow.minOfOrNull { it.low }
        val swingHigh = structuralWindow.maxOfOrNull { it.high }
        return when (direction) {
            V531Direction.LONG -> {
                val structuralStopLevel = listOfNotNull(support, swingLow).filter { it < price }.maxOrNull()
                val modelStop = price - 1.25 * atr
                val stop = min(modelStop, structuralStopLevel?.minus(0.35 * atr) ?: modelStop)
                if (!stop.isFinite() || stop >= price) return null
                val modelT1 = price + 1.5 * atr
                val modelT2 = price + 2.5 * atr
                val structuralTarget = listOfNotNull(resistance, swingHigh)
                    .filter { it > price }
                    .minOrNull()
                // Structural resistance may shorten a target, but may never inflate it beyond the model target.
                val t1 = structuralTarget?.let { min(modelT1, it) } ?: modelT1
                val t2 = structuralTarget?.let { min(modelT2, it) } ?: modelT2
                val target1 = max(t1, price + 0.25 * atr)
                val target2 = max(target1, max(t2, price + 0.25 * atr))
                val risk = price - stop
                if (risk <= 0.0) null else RiskPlan(price, stop, target1, target2, (target1 - price) / risk, (target2 - price) / risk)
            }
            V531Direction.SHORT -> {
                val structuralStopLevel = listOfNotNull(resistance, swingHigh).filter { it > price }.minOrNull()
                val modelStop = price + 1.25 * atr
                val stop = max(modelStop, structuralStopLevel?.plus(0.35 * atr) ?: modelStop)
                if (!stop.isFinite() || stop <= price) return null
                val modelT1 = price - 1.5 * atr
                val modelT2 = price - 2.5 * atr
                val structuralTarget = listOfNotNull(support, swingLow)
                    .filter { it < price }
                    .maxOrNull()
                // Structural support may shorten a target, but may never inflate it beyond the model target.
                val t1 = structuralTarget?.let { max(modelT1, it) } ?: modelT1
                val t2 = structuralTarget?.let { max(modelT2, it) } ?: modelT2
                val target1 = min(t1, price - 0.25 * atr)
                val target2 = min(target1, min(t2, price - 0.25 * atr))
                val risk = stop - price
                if (risk <= 0.0) null else RiskPlan(price, stop, target1, target2, (price - target1) / risk, (price - target2) / risk)
            }
            V531Direction.WATCH -> null
        }
    }

    private fun timingStatus(direction: V531Direction, price: Double, t: TechnicalSnapshot, candles: List<Candle>): String {
        if (direction == V531Direction.WATCH) return "DEĞERLENDİRİLMEDİ"
        val atr = t.atr14?.takeIf { it.isFinite() && it > 0.0 }
        val ema20 = t.ema20?.takeIf { it.isFinite() }
        val ret5 = if (candles.size >= 6) pct(candles[candles.lastIndex - 5].close, candles.last().close) else null
        return when {
            atr != null && ema20 != null && direction == V531Direction.LONG && price > ema20 + 2.2 * atr -> "GEÇ KALINMIŞ"
            atr != null && ema20 != null && direction == V531Direction.SHORT && price < ema20 - 2.2 * atr -> "GEÇ KALINMIŞ"
            ret5 != null && abs(ret5) >= 10.0 -> "GEÇ KALINMIŞ"
            ret5 != null && abs(ret5) >= 7.0 -> "SINIRDA"
            else -> "ZAMANINDA"
        }
    }

    private fun setupType(direction: V531Direction, price: Double, t: TechnicalSnapshot, candles: List<Candle>): String {
        if (direction == V531Direction.WATCH) return "TEKNİK İZLEME"
        val atr = t.atr14?.takeIf { it.isFinite() && it > 0.0 }
        val volumeRatio = t.volumeRatio?.takeIf { it.isFinite() && it >= 0.0 }
        val breakout = BreakoutVolumeEngine.evaluate(candles, volumeRatio, price, atr, 20, 0.0015, 0.12)
        if (direction == V531Direction.LONG && breakout.state == "YUKARI KIRILIM") return if (breakout.confirmed) "KIRILIM" else "KIRILIM • TEYİT BEKLİYOR"
        if (direction == V531Direction.SHORT && breakout.state == "AŞAĞI KIRILIM") return if (breakout.confirmed) "KIRILIM" else "KIRILIM • TEYİT BEKLİYOR"
        val ema20 = t.ema20
        val ema50 = t.ema50
        val ema200 = t.ema200
        val emaLong = ema20 != null && ema50 != null && ema200 != null && price > ema20 && ema20 > ema50 && ema50 > ema200
        val emaShort = ema20 != null && ema50 != null && ema200 != null && price < ema20 && ema20 < ema50 && ema50 < ema200
        if ((direction == V531Direction.LONG && emaLong) || (direction == V531Direction.SHORT && emaShort)) return "TREND"
        if (breakout.confirmed) return "HACİM DESTEKLİ MOMENTUM"
        return "TEKNİK İZLEME"
    }

    private fun pct(from: Double, to: Double): Double? = if (from.isFinite() && to.isFinite() && from > 0.0) ((to / from) - 1.0) * 100.0 else null
}
