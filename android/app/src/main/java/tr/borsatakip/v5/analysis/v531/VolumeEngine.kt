package tr.borsatakip.v5.analysis.v531

import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.TechnicalSnapshot

object VolumeEngine {
    fun evaluate(candles: List<Candle>, technical: TechnicalSnapshot): V531ComponentScore {
        val c = candles.filter { it.close.isFinite() && it.close > 0.0 && it.volume.isFinite() && it.volume >= 0.0 }
        if (c.size < 4) return ScoreNormalizer.component("VOLUME", ScoringConfig.VOLUME_WEIGHT, null, listOf("Hacim serisi yetersiz"))
        val rvol = technical.volumeRatio?.takeIf { it.isFinite() && it >= 0.0 }
            ?: relativeVolume(c)
            ?: return ScoreNormalizer.component("VOLUME", ScoringConfig.VOLUME_WEIGHT, null, listOf("RVOL hesaplanamadı"))

        val ret3 = ScoreNormalizer.pct(c[c.lastIndex - 3].close, c.last().close) ?: 0.0
        val candleDirection = when {
            c.last().close > c.last().open -> 1.0
            c.last().close < c.last().open -> -1.0
            ret3 > 0.0 -> 1.0
            ret3 < 0.0 -> -1.0
            else -> 0.0
        }
        val directionalAgreement = when {
            ret3 > 0.0 && candleDirection > 0.0 -> 1.0
            ret3 < 0.0 && candleDirection < 0.0 -> -1.0
            else -> candleDirection * 0.5
        }
        val participation = ((rvol - 1.0) / 1.0).coerceIn(0.0, 1.0)
        val strength = directionalAgreement * participation
        return ScoreNormalizer.component(
            "VOLUME",
            ScoringConfig.VOLUME_WEIGHT,
            strength,
            listOf("RVOL=${"%.2f".format(java.util.Locale.US, rvol)}", "RET3=${"%.2f".format(java.util.Locale.US, ret3)}%")
        )
    }

    private fun relativeVolume(candles: List<Candle>): Double? {
        if (candles.size < 21) return null
        val base = candles.dropLast(1).takeLast(20).map { it.volume }.average()
        if (!base.isFinite() || base <= 0.0) return null
        return (candles.last().volume / base).takeIf { it.isFinite() }
    }
}
