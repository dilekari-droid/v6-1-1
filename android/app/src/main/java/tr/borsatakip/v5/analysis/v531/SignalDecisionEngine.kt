package tr.borsatakip.v5.analysis.v531

import kotlin.math.abs
import kotlin.math.roundToInt

object SignalDecisionEngine {
    data class Result(
        val longScore: Int,
        val shortScore: Int,
        val rawScore: Int,
        val finalScore: Int,
        val dominantDirection: V531Direction,
        val decision: V531Direction,
        val conflictPenalty: Int,
        val reasonCodes: List<V531ReasonCode>
    )

    fun evaluate(
        longScore: Int,
        shortScore: Int,
        dataConfidence: Int,
        availableSignalWeight: Int,
        mtfConsensusScore: Int?
    ): Result {
        val long = longScore.coerceIn(0, 100)
        val short = shortScore.coerceIn(0, 100)
        val margin = abs(long - short)
        val dominant = when {
            long > short -> V531Direction.LONG
            short > long -> V531Direction.SHORT
            else -> V531Direction.WATCH
        }
        val raw = maxOf(long, short)
        val mtf = mtfConsensusScore?.coerceIn(-100, 100)
        val opposing = when (dominant) {
            V531Direction.LONG -> mtf?.takeIf { it < 0 }?.let { -it }
            V531Direction.SHORT -> mtf?.takeIf { it > 0 }
            V531Direction.WATCH -> null
        } ?: 0
        val conflictPenalty = if (opposing > 0) {
            (opposing / 100.0 * ScoringConfig.MTF_CONFLICT_PENALTY_MAX).roundToInt().coerceIn(1, ScoringConfig.MTF_CONFLICT_PENALTY_MAX)
        } else 0
        val final = (raw - conflictPenalty).coerceIn(0, 100)
        val reasons = mutableListOf<V531ReasonCode>()
        if (conflictPenalty > 0) reasons += V531ReasonCode.TIMEFRAME_CONFLICT

        val decision = when {
            dataConfidence < ScoringConfig.BLOCK_CONFIDENCE_BELOW -> {
                reasons += V531ReasonCode.DATA_CONFIDENCE_BLOCKED
                V531Direction.WATCH
            }
            dataConfidence < ScoringConfig.MIN_CONFIDENCE -> {
                reasons += V531ReasonCode.LOW_CONFIDENCE
                V531Direction.WATCH
            }
            availableSignalWeight < ScoringConfig.MIN_AVAILABLE_SIGNAL_WEIGHT -> {
                reasons += V531ReasonCode.INSUFFICIENT_SIGNAL_COVERAGE
                V531Direction.WATCH
            }
            margin < ScoringConfig.MIN_DIRECTION_MARGIN -> {
                reasons += V531ReasonCode.DIRECTION_CONFLICT
                V531Direction.WATCH
            }
            opposing >= ScoringConfig.SEVERE_MTF_CONFLICT -> {
                reasons += V531ReasonCode.TIMEFRAME_CONFLICT
                V531Direction.WATCH
            }
            final < if (dataConfidence < 80) ScoringConfig.DEGRADED_SIGNAL_THRESHOLD else ScoringConfig.SIGNAL_THRESHOLD -> {
                reasons += V531ReasonCode.LOW_SCORE
                V531Direction.WATCH
            }
            dominant == V531Direction.LONG -> {
                reasons += V531ReasonCode.LONG_SIGNAL
                V531Direction.LONG
            }
            dominant == V531Direction.SHORT -> {
                reasons += V531ReasonCode.SHORT_SIGNAL
                V531Direction.SHORT
            }
            else -> V531Direction.WATCH
        }
        return Result(long, short, raw, final, dominant, decision, conflictPenalty, reasons.distinct())
    }
}
