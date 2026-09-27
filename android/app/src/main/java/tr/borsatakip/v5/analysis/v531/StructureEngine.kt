package tr.borsatakip.v5.analysis.v531

import tr.borsatakip.v5.model.Candle

object StructureEngine {
    fun evaluate(candles: List<Candle>): V531ComponentScore {
        val c = candles.filter { it.high.isFinite() && it.low.isFinite() && it.close.isFinite() && it.high >= it.low && it.close > 0.0 }
        if (c.size < 22) return ScoreNormalizer.component("STRUCTURE", ScoringConfig.STRUCTURE_WEIGHT, null, listOf("En az 22 mum gerekli"))

        val signal = c.last()
        val prior20 = c.dropLast(1).takeLast(20)
        val firstHalf = prior20.take(10)
        val secondHalf = prior20.takeLast(10)
        val priorHigh = prior20.maxOf { it.high }
        val priorLow = prior20.minOf { it.low }

        var strength = 0.0
        val evidence = mutableListOf<String>()
        when {
            signal.close > priorHigh -> {
                strength += 0.50
                evidence += "BREAKOUT"
            }
            signal.close < priorLow -> {
                strength -= 0.50
                evidence += "BREAKDOWN"
            }
        }

        val higherHigh = secondHalf.maxOf { it.high } > firstHalf.maxOf { it.high }
        val higherLow = secondHalf.minOf { it.low } > firstHalf.minOf { it.low }
        val lowerHigh = secondHalf.maxOf { it.high } < firstHalf.maxOf { it.high }
        val lowerLow = secondHalf.minOf { it.low } < firstHalf.minOf { it.low }
        if (higherHigh) { strength += 0.25; evidence += "HH" }
        if (higherLow) { strength += 0.25; evidence += "HL" }
        if (lowerHigh) { strength -= 0.25; evidence += "LH" }
        if (lowerLow) { strength -= 0.25; evidence += "LL" }

        return ScoreNormalizer.component("STRUCTURE", ScoringConfig.STRUCTURE_WEIGHT, strength.coerceIn(-1.0, 1.0), evidence)
    }
}
