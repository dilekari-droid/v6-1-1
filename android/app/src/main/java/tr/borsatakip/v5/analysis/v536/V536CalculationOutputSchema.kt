package tr.borsatakip.v5.analysis.v536

/**
 * Canonical calculation-output schema. This deliberately does not claim to be
 * a cryptographic digest of the entire Android Opportunity presentation model.
 * It covers server-controlled calculation/decision/risk/presentation fields
 * that can affect downstream interpretation of a REMOTE signal.
 */
object V536CalculationOutputSchema {
    const val SCHEMA = "V536_REMOTE_OUTPUT_V1"

    fun validatePresentation(
        qualityClass: String,
        timingStatus: String,
        marketRegime: String,
        marketRegimeConfidence: Int,
        breakoutDirection: String
    ): List<String> = buildList {
        if (qualityClass.isBlank()) add("OUTPUT_QUALITY_CLASS_REQUIRED")
        if (timingStatus.isBlank()) add("OUTPUT_TIMING_STATUS_REQUIRED")
        if (marketRegime.isBlank()) add("OUTPUT_MARKET_REGIME_REQUIRED")
        if (marketRegimeConfidence !in 0..100) add("OUTPUT_MARKET_REGIME_CONFIDENCE_RANGE")
        if (breakoutDirection.trim().uppercase() !in setOf("UP", "DOWN", "NONE")) add("OUTPUT_BREAKOUT_DIRECTION_INVALID")
        if (marketRegime.trim().uppercase() == "UNKNOWN" && marketRegimeConfidence != 0) add("UNKNOWN_REGIME_CONFIDENCE_MUST_BE_ZERO")
    }
}
