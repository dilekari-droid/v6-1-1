package tr.borsatakip.v5.analysis.v531

import tr.borsatakip.v5.model.TechnicalSnapshot

object TrendEngine {
    fun evaluate(price: Double, technical: TechnicalSnapshot): V531ComponentScore {
        if (!price.isFinite() || price <= 0.0) {
            return ScoreNormalizer.component("TREND", ScoringConfig.TREND_WEIGHT, null, listOf("Geçerli fiyat yok"))
        }
        val votes = mutableListOf<Double>()
        val evidence = mutableListOf<String>()
        technical.ema20?.takeIf { it.isFinite() && it > 0.0 }?.let {
            votes += compare(price, it)
            evidence += "Fiyat/EMA20"
        }
        if (technical.ema20 != null && technical.ema50 != null && technical.ema20.isFinite() && technical.ema50.isFinite()) {
            votes += compare(technical.ema20, technical.ema50)
            evidence += "EMA20/EMA50"
        }
        if (technical.ema50 != null && technical.ema200 != null && technical.ema50.isFinite() && technical.ema200.isFinite()) {
            votes += compare(technical.ema50, technical.ema200)
            evidence += "EMA50/EMA200"
        }
        val strength = votes.takeIf { it.isNotEmpty() }?.average()
        return ScoreNormalizer.component("TREND", ScoringConfig.TREND_WEIGHT, strength, evidence)
    }

    private fun compare(a: Double, b: Double): Double = when {
        a > b -> 1.0
        a < b -> -1.0
        else -> 0.0
    }
}
