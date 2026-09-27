package tr.borsatakip.v5.analysis.v540

import tr.borsatakip.v5.model.RankingStatus
import kotlin.math.roundToInt

/**
 * Ranking is prioritization, never probability. RR is deliberately a publication gate and
 * is not rewarded again here. MTF completeness scales prioritization transparently.
 */
object V540RankingEngine {
    const val EXPECTED_MTF_COUNT = 6
    const val MIN_MTF_COUNT_FOR_RANKING = 3

    data class Result(
        val score: Int,
        val status: RankingStatus,
        val mtfCompletenessPct: Int,
        val audit: List<String>
    )

    fun evaluate(
        finalSignalScore: Int,
        dataConfidence: Int,
        riskScore: Int,
        relativeVolume: Double?,
        freshnessScore: Int,
        rrGateCode: String,
        published: Boolean,
        mtfAvailableCount: Int,
        mtfExpectedCount: Int = EXPECTED_MTF_COUNT,
        marketRegimeAdjustment: Int = 0
    ): Result {
        if (!published) {
            val status = if (rrGateCode == "RR_UNAVAILABLE_BLOCK" || rrGateCode == "RR_BELOW_1_BLOCK") {
                RankingStatus.BLOCKED_RR
            } else RankingStatus.INSUFFICIENT_DATA
            return Result(0, status, completeness(mtfAvailableCount, mtfExpectedCount), listOf("RANKING_BLOCK=$status"))
        }
        if (mtfExpectedCount > 0 && mtfAvailableCount < MIN_MTF_COUNT_FOR_RANKING) {
            return Result(
                0,
                RankingStatus.INSUFFICIENT_DATA,
                completeness(mtfAvailableCount, mtfExpectedCount),
                listOf("RANKING_BLOCK=MTF_INSUFFICIENT", "MTF_AVAILABLE=$mtfAvailableCount/$mtfExpectedCount")
            )
        }

        val volumeActivityScore = relativeVolume
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { (it / (it + 1.0) * 100.0).coerceIn(0.0, 100.0) }
            ?: 0.0
        val raw = (
            finalSignalScore.coerceIn(0, 100) * 0.50 +
                dataConfidence.coerceIn(0, 100) * 0.25 +
                (100 - riskScore.coerceIn(0, 100)) * 0.15 +
                volumeActivityScore * 0.05 +
                freshnessScore.coerceIn(0, 100) * 0.05
            ) + marketRegimeAdjustment.coerceIn(-10, 10)

        val completenessPct = completeness(mtfAvailableCount, mtfExpectedCount)
        val completenessFactor = if (mtfExpectedCount <= 0) 1.0 else completenessPct / 100.0
        val score = (raw.coerceIn(0.0, 100.0) * completenessFactor).roundToInt().coerceIn(0, 100)
        return Result(
            score,
            RankingStatus.CALCULATED,
            completenessPct,
            listOf(
                "RANKING_RAW=${raw.roundToInt().coerceIn(0, 100)}",
                "MTF_COMPLETENESS=$completenessPct",
                "RR_USED_AS_GATE_ONLY=$rrGateCode"
            )
        )
    }

    private fun completeness(available: Int, expected: Int): Int {
        if (expected <= 0) return 100
        return (available.coerceIn(0, expected) * 100.0 / expected).roundToInt().coerceIn(0, 100)
    }
}
