package tr.borsatakip.v5.analysis.v540

import kotlin.math.roundToInt

/** Continuous freshness score. No 1 ms discontinuities at bucket boundaries. */
object V540FreshnessPolicy {
    fun score(ageMs: Long?, maxAcceptedAgeMs: Long = 60_000L): Int {
        val age = ageMs ?: return 0
        if (age < 0L || maxAcceptedAgeMs <= 0L || age > maxAcceptedAgeMs) return 0
        if (age <= 5_000L) return 100

        val secondKnot = minOf(30_000L, maxAcceptedAgeMs)
        if (age <= secondKnot) {
            if (secondKnot <= 5_000L) return 80
            return lerp(age, 5_000L, secondKnot, 100.0, 80.0)
        }
        return lerp(age, secondKnot, maxAcceptedAgeMs, 80.0, 0.0)
    }

    private fun lerp(x: Long, x0: Long, x1: Long, y0: Double, y1: Double): Int {
        if (x1 <= x0) return y1.roundToInt().coerceIn(0, 100)
        val t = ((x - x0).toDouble() / (x1 - x0).toDouble()).coerceIn(0.0, 1.0)
        return (y0 + (y1 - y0) * t).roundToInt().coerceIn(0, 100)
    }
}
