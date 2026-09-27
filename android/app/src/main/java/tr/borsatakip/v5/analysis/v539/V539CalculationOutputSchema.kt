package tr.borsatakip.v5.analysis.v539

import tr.borsatakip.v5.analysis.v536.V536CalculationOutputSchema

/** V5.3.9 canonical REMOTE output schema. RR is part of the signed/hashed publication input. */
object V539CalculationOutputSchema {
    const val SCHEMA = "V539_REMOTE_OUTPUT_V1"

    fun validate(
        qualityClass: String,
        timingStatus: String,
        marketRegime: String,
        marketRegimeConfidence: Int,
        breakoutDirection: String,
        rr1: Double?,
        rr2: Double?
    ): List<String> = buildList {
        addAll(V536CalculationOutputSchema.validatePresentation(qualityClass, timingStatus, marketRegime, marketRegimeConfidence, breakoutDirection))
        if (rr1 != null && (!rr1.isFinite() || rr1 < 0.0 || rr1 > 20.0)) add("OUTPUT_RR1_RANGE")
        if (rr2 != null && (!rr2.isFinite() || rr2 < 0.0 || rr2 > 30.0)) add("OUTPUT_RR2_RANGE")
    }
}
