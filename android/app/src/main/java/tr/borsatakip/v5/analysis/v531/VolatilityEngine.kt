package tr.borsatakip.v5.analysis.v531

import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.TechnicalSnapshot
import kotlin.math.abs

object VolatilityEngine {
    fun evaluate(candles: List<Candle>, technical: TechnicalSnapshot, price: Double): V531ComponentScore {
        val atr = technical.atr14?.takeIf { it.isFinite() && it > 0.0 }
        if (atr == null || !price.isFinite() || price <= 0.0 || candles.size < 6) {
            return ScoreNormalizer.component("VOLATILITY", ScoringConfig.VOLATILITY_WEIGHT, null, listOf("ATR/price N/A"))
        }
        val atrPct = atr / price * 100.0
        val ret5 = ScoreNormalizer.pct(candles[candles.lastIndex - 5].close, candles.last().close) ?: 0.0
        val direction = when {
            ret5 > 0.0 -> 1.0
            ret5 < 0.0 -> -1.0
            else -> 0.0
        }
        val efficiency = if (atrPct > 1e-9) (abs(ret5) / (atrPct * 1.5)).coerceIn(0.0, 1.0) else 0.0
        val health = when {
            atrPct <= 0.4 -> 0.20
            atrPct <= 3.5 -> 1.00
            atrPct <= 5.0 -> 0.65
            else -> 0.35
        }
        val strength = direction * efficiency * health
        return ScoreNormalizer.component(
            "VOLATILITY",
            ScoringConfig.VOLATILITY_WEIGHT,
            strength,
            listOf("ATR_PCT=${"%.2f".format(java.util.Locale.US, atrPct)}", "RET5=${"%.2f".format(java.util.Locale.US, ret5)}%")
        )
    }
}
