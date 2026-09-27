package tr.borsatakip.v5.analysis.v531

object ScoringConfig {
    const val ENGINE_VERSION = "V5.4.5"

    const val TREND_WEIGHT = 25
    const val MOMENTUM_WEIGHT = 20
    const val STRUCTURE_WEIGHT = 15
    const val VOLUME_WEIGHT = 15
    const val OPEN_INTEREST_WEIGHT = 10
    const val VOLATILITY_WEIGHT = 10
    const val MULTI_TIMEFRAME_WEIGHT = 5
    const val TOTAL_SIGNAL_WEIGHT = 100

    const val SIGNAL_THRESHOLD = 70
    const val DEGRADED_SIGNAL_THRESHOLD = 75
    const val MIN_CONFIDENCE = 60
    const val BLOCK_CONFIDENCE_BELOW = 40
    const val MIN_DIRECTION_MARGIN = 6
    const val MIN_AVAILABLE_SIGNAL_WEIGHT = 65

    const val MTF_CONFLICT_PENALTY_MAX = 8
    const val SEVERE_MTF_CONFLICT = 55

    const val EXPECTED_CLOSED_BARS = 220
    const val FRESH_AGE_MS = 5_000L
    const val ACCEPTABLE_AGE_MS = 30_000L
    const val DEGRADED_AGE_MS = 120_000L
    const val STALE_AGE_MS = 300_000L

    init {
        check(
            TREND_WEIGHT + MOMENTUM_WEIGHT + STRUCTURE_WEIGHT + VOLUME_WEIGHT +
                OPEN_INTEREST_WEIGHT + VOLATILITY_WEIGHT + MULTI_TIMEFRAME_WEIGHT == TOTAL_SIGNAL_WEIGHT
        )
    }
}
