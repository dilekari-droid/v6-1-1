package tr.borsatakip.v5.analysis.v539

import tr.borsatakip.v5.analysis.v531.V531Direction

/** V5.3.9 publication gate. Signal score remains untouched; publication may be downgraded to WATCH. */
object V539RiskRewardGate {
    const val BLOCK_BELOW = 1.0

    data class Result(
        val engineDirection: V531Direction,
        val publishedDirection: V531Direction,
        val code: String
    )

    fun evaluate(engineDirection: V531Direction, rr1: Double?): Result {
        val published = when {
            engineDirection == V531Direction.WATCH -> V531Direction.WATCH
            rr1 == null || !rr1.isFinite() -> V531Direction.WATCH
            rr1 < BLOCK_BELOW -> V531Direction.WATCH
            else -> engineDirection
        }
        val code = when {
            engineDirection == V531Direction.WATCH -> "RR_NOT_APPLICABLE"
            rr1 == null || !rr1.isFinite() -> "RR_UNAVAILABLE_BLOCK"
            rr1 < BLOCK_BELOW -> "RR_BELOW_1_BLOCK"
            rr1 < 1.5 -> "RR_1_0_1_5_PENALIZED"
            rr1 < 2.0 -> "RR_1_5_2_0_NEUTRAL"
            else -> "RR_2_PLUS_POSITIVE"
        }
        return Result(engineDirection, published, code)
    }
}
