package tr.borsatakip.v5.analysis.v540

import tr.borsatakip.v5.analysis.v539.V539CalculationOutputSchema

/** V5.4.0 REMOTE output schema. MTF completeness is hash-bound. */
object V540CalculationOutputSchema {
    const val SCHEMA = "V540_REMOTE_OUTPUT_V1"
    const val EXPECTED_MTF_COUNT = 6

    fun validate(
        qualityClass: String,
        timingStatus: String,
        marketRegime: String,
        marketRegimeConfidence: Int,
        breakoutDirection: String,
        rr1: Double?,
        rr2: Double?,
        mtfConsensusScore: Int?,
        mtfAvailableCount: Int,
        mtfExpectedCount: Int
    ): List<String> = buildList {
        addAll(V539CalculationOutputSchema.validate(qualityClass, timingStatus, marketRegime, marketRegimeConfidence, breakoutDirection, rr1, rr2))
        if (mtfExpectedCount != EXPECTED_MTF_COUNT) add("OUTPUT_MTF_EXPECTED_COUNT")
        if (mtfAvailableCount !in 0..mtfExpectedCount.coerceAtLeast(0)) add("OUTPUT_MTF_AVAILABLE_COUNT")
        if (mtfAvailableCount >= 3 && mtfConsensusScore == null) add("OUTPUT_MTF_SCORE_MISSING")
        if (mtfAvailableCount < 3 && mtfConsensusScore != null) add("OUTPUT_MTF_SCORE_WITH_INSUFFICIENT_DATA")
        if (mtfConsensusScore != null && mtfConsensusScore !in -100..100) add("OUTPUT_MTF_SCORE_RANGE")
    }
}
