package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ViopOpportunity
import kotlin.math.abs

/**
 * V5.1.47 strategy comparison layer.
 *
 * The engine never reads forward outcomes/future candles. It only classifies the already-produced
 * Opportunity snapshot so the same real signal can later be compared under multiple transparent
 * strategy profiles. This is comparison infrastructure, not an auto-trading/auto-optimization layer.
 */
object StrategyProfileEngine {
    enum class StrategyId(val label: String) {
        TREND("Trend"),
        MOMENTUM("Momentum"),
        BREAKOUT("Breakout"),
        MEAN_REVERSION("Mean Reversion"),
        MTF_REGIME("MTF + Rejim"),
        COMBINED("Mevcut Birleşik")
    }

    data class Score(
        val id: StrategyId,
        val score: Int,
        val active: Boolean,
        val reason: String
    )

    const val ACTIVE_THRESHOLD = 55

    fun evaluate(o: Opportunity): List<Score> {
        if (o.direction != "LONG" && o.direction != "SHORT") {
            return StrategyId.entries.map { Score(it, 0, false, "Nötr yön; strateji örneğine alınmadı.") }
        }
        val long = o.direction == "LONG"
        val t = o.technical
        val price = o.price

        val trendAligned = when {
            long && listOf(t.ema20, t.ema50, t.ema200).all { it != null } ->
                price > t.ema20!! && t.ema20!! > t.ema50!! && t.ema50!! > t.ema200!!
            !long && listOf(t.ema20, t.ema50, t.ema200).all { it != null } ->
                price < t.ema20!! && t.ema20!! < t.ema50!! && t.ema50!! < t.ema200!!
            else -> false
        }
        val trendScore = (if (trendAligned) 78 else 30) + when {
            o.setupType.contains("TREND", true) -> 12
            o.setupType.contains("GERİ ÇEKİLME", true) -> 8
            else -> 0
        }

        val macdBull = t.macd != null && t.macdSignal != null && t.macd!! > t.macdSignal!!
        val macdBear = t.macd != null && t.macdSignal != null && t.macd!! < t.macdSignal!!
        val macdAligned = if (long) macdBull else macdBear
        val rsi = t.rsi14
        val rsiAligned = when {
            rsi == null -> false
            long -> rsi in 48.0..72.0
            else -> rsi in 28.0..52.0
        }
        val volume = t.volumeRatio ?: 0.0
        val momentumScore = (
            25 +
                (if (macdAligned) 28 else 0) +
                (if (rsiAligned) 20 else 0) +
                when { volume >= 2.0 -> 22; volume >= 1.5 -> 17; volume >= 1.15 -> 10; else -> 0 }
            )

        val isBreakout = o.setupType.contains("KIRILIM", true)
        val breakoutScore = (
            if (isBreakout) 72 else 20
            ) + when {
                volume >= 2.0 -> 20
                volume >= 1.5 -> 14
                volume >= 1.15 -> 7
                else -> 0
            }

        val reversal = o.setupType.contains("DİPTEN DÖNÜŞ", true)
        val pullback = o.setupType.contains("GERİ ÇEKİLME", true)
        val nearLevel = when {
            long && o.support != null -> abs(price - o.support) / price <= 0.03
            !long && o.resistance != null -> abs(o.resistance - price) / price <= 0.03
            else -> false
        }
        val meanReversionScore = (
            when { reversal -> 78; pullback -> 65; nearLevel -> 52; else -> 20 }
            ) + when {
                rsi == null -> 0
                long && rsi <= 40.0 -> 12
                !long && rsi >= 60.0 -> 12
                else -> 0
            }

        val mtf = o.mtfConsensusScore
        val mtfAligned = mtf != null && if (long) mtf >= 20 else mtf <= -20
        val mtfStrong = mtf != null && if (long) mtf >= 55 else mtf <= -55
        val regimeAligned = when {
            long && o.marketRegime.equals("TREND YUKARI", true) -> true
            !long && o.marketRegime.equals("TREND AŞAĞI", true) -> true
            else -> false
        }
        val mtfRegimeScore = if (mtf == null || o.marketRegime.equals("VERİ YOK", true)) {
            0
        } else {
            25 + (if (mtfStrong) 40 else if (mtfAligned) 25 else 0) + (if (regimeAligned) 25 else 0) +
                (o.marketRegimeConfidence.coerceIn(0, 100) / 10)
        }

        val combinedScore = o.rankingScore.coerceIn(0, 100)

        return listOf(
            make(StrategyId.TREND, trendScore, "EMA trend hizası + setup"),
            make(StrategyId.MOMENTUM, momentumScore, "MACD + RSI + hacim"),
            make(StrategyId.BREAKOUT, breakoutScore, "Kırılım + hacim teyidi"),
            make(StrategyId.MEAN_REVERSION, meanReversionScore, "Dönüş/geri çekilme + seviye + RSI"),
            make(StrategyId.MTF_REGIME, mtfRegimeScore, "Çoklu zaman dilimi + piyasa rejimi"),
            make(StrategyId.COMBINED, combinedScore, "Mevcut rankingScore")
        )
    }

    fun evaluate(v: ViopOpportunity): List<Score> {
        if (v.direction != "LONG" && v.direction != "SHORT") {
            return StrategyId.entries.map { Score(it, 0, false, "Nötr yön; strateji örneğine alınmadı.") }
        }
        val long = v.direction == "LONG"
        val t = v.technical
        val price = v.decisionPrice
        val trendAligned = when {
            long && listOf(t.ema20, t.ema50, t.ema200).all { it != null } ->
                price > t.ema20!! && t.ema20!! > t.ema50!! && t.ema50!! > t.ema200!!
            !long && listOf(t.ema20, t.ema50, t.ema200).all { it != null } ->
                price < t.ema20!! && t.ema20!! < t.ema50!! && t.ema50!! < t.ema200!!
            else -> false
        }
        val trendScore = if (trendAligned) 82 else 30
        val macdAligned = when {
            t.macd == null || t.macdSignal == null -> false
            long -> t.macd!! > t.macdSignal!!
            else -> t.macd!! < t.macdSignal!!
        }
        val rsi = t.rsi14
        val rsiAligned = when {
            rsi == null -> false
            long -> rsi in 48.0..72.0
            else -> rsi in 28.0..52.0
        }
        val volumeRatio = t.volumeRatio ?: 0.0
        val momentumScore = 25 + (if (macdAligned) 28 else 0) + (if (rsiAligned) 20 else 0) + when {
            volumeRatio >= 2.0 -> 22
            volumeRatio >= 1.5 -> 17
            volumeRatio >= 1.15 -> 10
            else -> 0
        }
        val breakoutScore = (if (v.breakoutState != "YOK") 70 else 20) + if (v.breakoutConfirmed) 20 else 0
        val nearRiskLevel = v.riskPlan?.let { plan ->
            val ref = if (long) plan.stop else plan.stop
            ref?.let { kotlin.math.abs(price - it) / price <= 0.04 } ?: false
        } ?: false
        val meanReversionScore = 20 + (if (nearRiskLevel) 30 else 0) + when {
            rsi == null -> 0
            long && rsi <= 40.0 -> 28
            !long && rsi >= 60.0 -> 28
            else -> 0
        }
        val mtf = v.mtfConsensusScore
        val mtfAligned = mtf != null && if (long) mtf >= 20 else mtf <= -20
        val mtfStrong = mtf != null && if (long) mtf >= 55 else mtf <= -55
        val regimeAligned = when {
            long && v.marketRegime.equals("TREND YUKARI", true) -> true
            !long && v.marketRegime.equals("TREND AŞAĞI", true) -> true
            else -> false
        }
        val mtfRegimeScore = if (mtf == null || v.marketRegime.equals("VERİ YOK", true)) 0 else
            25 + (if (mtfStrong) 40 else if (mtfAligned) 25 else 0) + (if (regimeAligned) 25 else 0) + (v.marketRegimeConfidence.coerceIn(0, 100) / 10)
        return listOf(
            make(StrategyId.TREND, trendScore, "EMA trend hizası"),
            make(StrategyId.MOMENTUM, momentumScore, "MACD + RSI + hacim"),
            make(StrategyId.BREAKOUT, breakoutScore, "Kırılım + hacim teyidi"),
            make(StrategyId.MEAN_REVERSION, meanReversionScore, "RSI + risk seviyesi yakınlığı"),
            make(StrategyId.MTF_REGIME, mtfRegimeScore, "Çoklu zaman dilimi + piyasa rejimi"),
            make(StrategyId.COMBINED, v.rankingScore.coerceIn(0, 100), "Mevcut VİOP rankingScore")
        )
    }

    private fun make(id: StrategyId, rawScore: Int, reason: String): Score {
        val score = rawScore.coerceIn(0, 100)
        return Score(id, score, score >= ACTIVE_THRESHOLD, reason)
    }
}
