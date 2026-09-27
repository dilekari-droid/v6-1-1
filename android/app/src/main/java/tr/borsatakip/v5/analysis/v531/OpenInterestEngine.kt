package tr.borsatakip.v5.analysis.v531

/**
 * OI is treated as participation evidence, never as a stand-alone claim about who opened/closed.
 * Directional contribution requires price change + OI change + usable relative-volume context.
 */
object OpenInterestEngine {
    fun evaluate(
        priceChangePct: Double?,
        openInterestChangePct: Double?,
        relativeVolume: Double? = null
    ): V531ComponentScore {
        val priceChange = priceChangePct?.takeIf { it.isFinite() }
        val oiChange = openInterestChangePct?.takeIf { it.isFinite() }
        val rvol = relativeVolume?.takeIf { it.isFinite() && it >= 0.0 }
        if (priceChange == null || oiChange == null) {
            return ScoreNormalizer.component("OPEN_INTEREST", ScoringConfig.OPEN_INTEREST_WEIGHT, null, listOf("OI değişimi N/A"))
        }
        if (rvol == null) {
            return ScoreNormalizer.component("OPEN_INTEREST", ScoringConfig.OPEN_INTEREST_WEIGHT, null, listOf("OI var; hacim bağlamı N/A"))
        }

        val priceDirection = when {
            priceChange > 0.0 -> 1.0
            priceChange < 0.0 -> -1.0
            else -> 0.0
        }
        val oiMagnitude = (kotlin.math.abs(oiChange) / 5.0).coerceIn(0.0, 1.0)
        // Relative volume is evidence quality, not liquidity. No direction is inferred from it.
        val participationConfidence = (rvol / (rvol + 1.0)).coerceIn(0.0, 1.0)
        val openingBias = if (oiChange > 0.0) 1.0 else 0.35
        val strength = priceDirection * oiMagnitude * openingBias * participationConfidence
        val interpretation = when {
            priceChange > 0.0 && oiChange > 0.0 -> "PRICE_UP_OI_UP_PARTICIPATION_EVIDENCE"
            priceChange < 0.0 && oiChange > 0.0 -> "PRICE_DOWN_OI_UP_PARTICIPATION_EVIDENCE"
            priceChange > 0.0 && oiChange < 0.0 -> "PRICE_UP_OI_DOWN_POSITION_REDUCTION_EVIDENCE"
            priceChange < 0.0 && oiChange < 0.0 -> "PRICE_DOWN_OI_DOWN_POSITION_REDUCTION_EVIDENCE"
            else -> "NEUTRAL"
        }
        return ScoreNormalizer.component(
            "OPEN_INTEREST",
            ScoringConfig.OPEN_INTEREST_WEIGHT,
            strength,
            listOf(
                "OI_CHANGE=${"%.2f".format(java.util.Locale.US, oiChange)}%",
                "RVOL=${"%.2f".format(java.util.Locale.US, rvol)}",
                interpretation,
                "OI_INTERPRETATION_IS_EVIDENCE_NOT_POSITION_IDENTITY"
            )
        )
    }
}
