package tr.borsatakip.v5.analysis.v531

import kotlin.math.roundToInt

object ScoreNormalizer {
    fun signed(value: Double): Double = value.coerceIn(-1.0, 1.0)

    fun component(id: String, weight: Int, strength: Double?, evidence: List<String> = emptyList()): V531ComponentScore {
        val normalized = strength?.takeIf { it.isFinite() }?.let(::signed)
        val long = normalized?.takeIf { it > 0.0 }?.let { (it * weight).roundToInt() } ?: 0
        val short = normalized?.takeIf { it < 0.0 }?.let { (-it * weight).roundToInt() } ?: 0
        return V531ComponentScore(id, weight, normalized, long, short, evidence)
    }

    fun pct(from: Double, to: Double): Double? {
        if (!from.isFinite() || !to.isFinite() || from <= 0.0) return null
        return ((to / from) - 1.0) * 100.0
    }

    fun weightedAverage(values: List<Pair<Double, Double>>): Double? {
        val usable = values.filter { (value, weight) -> value.isFinite() && weight > 0.0 }
        val denominator = usable.sumOf { it.second }
        if (denominator <= 0.0) return null
        return signed(usable.sumOf { it.first * it.second } / denominator)
    }
}
