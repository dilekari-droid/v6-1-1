package tr.borsatakip.v5.analysis.v531

object ConfidenceEngine {
    fun evaluate(input: V531DataQualityInput): V531ConfidenceResult {
        val breakdown = linkedMapOf<String, Int>()
        val reasons = mutableListOf<V531ReasonCode>()

        val freshness = when (input.dataAgeMs) {
            null -> 0
            in 0L..ScoringConfig.FRESH_AGE_MS -> 25
            in (ScoringConfig.FRESH_AGE_MS + 1)..ScoringConfig.ACCEPTABLE_AGE_MS -> 22
            in (ScoringConfig.ACCEPTABLE_AGE_MS + 1)..ScoringConfig.DEGRADED_AGE_MS -> 16
            in (ScoringConfig.DEGRADED_AGE_MS + 1)..ScoringConfig.STALE_AGE_MS -> 8
            else -> 0
        }
        breakdown["DATA_FRESHNESS"] = freshness
        if (freshness == 0) reasons += V531ReasonCode.STALE_DATA

        val candleIntegrity = when {
            !input.candlesValid -> 0
            input.candleCount >= ScoringConfig.EXPECTED_CLOSED_BARS -> 20
            input.candleCount >= 100 -> 14
            input.candleCount >= 50 -> 8
            else -> 0
        }
        breakdown["CANDLE_INTEGRITY"] = candleIntegrity
        if (candleIntegrity == 0) reasons += V531ReasonCode.MISSING_CANDLE

        breakdown["QUOTE_INTEGRITY"] = if (input.quoteValid) 15 else 0
        breakdown["PROVIDER_HEALTH"] = if (input.providerHealthy) 15 else 0
        breakdown["SYMBOL_RESOLUTION"] = if (input.symbolResolved) 15 else 0
        if (!input.providerHealthy) reasons += V531ReasonCode.PROVIDER_DEGRADED
        if (!input.symbolResolved) reasons += V531ReasonCode.CONTRACT_UNRESOLVED

        val availability = if (input.openInterestApplicable) {
            (if (input.volumeAvailable) 5 else 0) + (if (input.openInterestAvailable) 5 else 0)
        } else {
            if (input.volumeAvailable) 10 else 0
        }
        breakdown["VOLUME_OI_AVAILABILITY"] = availability
        if (!input.volumeAvailable) reasons += V531ReasonCode.MISSING_VOLUME
        if (input.openInterestApplicable && !input.openInterestAvailable) reasons += V531ReasonCode.MISSING_OI

        val score = breakdown.values.sum().coerceIn(0, 100)
        val band = bandForScore(score)
        return V531ConfidenceResult(score, band, breakdown, reasons.distinct())
    }

    internal fun bandForScore(score: Int): V531ConfidenceBand {
        val normalized = score.coerceIn(0, 100)
        return when {
            normalized >= 80 -> V531ConfidenceBand.NORMAL
            normalized >= 60 -> V531ConfidenceBand.DEGRADED
            normalized >= ScoringConfig.BLOCK_CONFIDENCE_BELOW -> V531ConfidenceBand.LOW
            else -> V531ConfidenceBand.BLOCKED
        }
    }
}
