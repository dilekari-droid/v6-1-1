package tr.borsatakip.v5.analysis.v531

import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.TechnicalSnapshot
import kotlin.math.abs

object MomentumEngine {
    fun evaluate(candles: List<Candle>, technical: TechnicalSnapshot): V531ComponentScore {
        val c = candles.filter { it.close.isFinite() && it.close > 0.0 }
        if (c.size < 6) return ScoreNormalizer.component("MOMENTUM", ScoringConfig.MOMENTUM_WEIGHT, null, listOf("En az 6 kapanış gerekli"))

        val factors = mutableListOf<Pair<Double, Double>>()
        val evidence = mutableListOf<String>()
        technical.rsi14?.takeIf { it.isFinite() }?.let {
            factors += (((it - 50.0) / 25.0).coerceIn(-1.0, 1.0)) to 0.30
            evidence += "RSI14=${"%.1f".format(java.util.Locale.US, it)}"
        }
        ScoreNormalizer.pct(c[c.lastIndex - 5].close, c.last().close)?.let {
            factors += (it / 3.0).coerceIn(-1.0, 1.0) to 0.25
            evidence += "ROC5=${"%.2f".format(java.util.Locale.US, it)}%"
        }
        if (c.size >= 21) {
            ScoreNormalizer.pct(c[c.lastIndex - 20].close, c.last().close)?.let {
                factors += (it / 8.0).coerceIn(-1.0, 1.0) to 0.20
                evidence += "ROC20=${"%.2f".format(java.util.Locale.US, it)}%"
            }
        }
        val macd = technical.macd
        val signal = technical.macdSignal
        if (macd != null && signal != null && macd.isFinite() && signal.isFinite()) {
            val scale = technical.atr14?.takeIf { it.isFinite() && it > 1e-9 }
                ?: c.last().close.takeIf { it > 1e-9 }?.times(0.01)
            if (scale != null && scale > 1e-9) {
                val normalized = ((macd - signal) / scale).coerceIn(-1.0, 1.0)
                factors += normalized to 0.25
                evidence += "MACD_SPREAD=${"%.4f".format(java.util.Locale.US, macd - signal)}"
            }
        }
        val strength = ScoreNormalizer.weightedAverage(factors)
        return ScoreNormalizer.component("MOMENTUM", ScoringConfig.MOMENTUM_WEIGHT, strength, evidence)
    }
}
