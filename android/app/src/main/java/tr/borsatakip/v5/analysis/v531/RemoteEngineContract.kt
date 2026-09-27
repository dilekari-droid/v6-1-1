package tr.borsatakip.v5.analysis.v531

import kotlin.math.abs
import tr.borsatakip.v5.analysis.v533.V533AuditHash
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.analysis.v540.V540CalculationOutputSchema

data class RemoteEngineMetadata(
    val calculationEngineVersion: String,
    val engineMode: String,
    val finalSignalScore: Int,
    val longScore: Int,
    val shortScore: Int,
    val dataConfidenceScore: Int,
    val riskScore: Int,
    val conflictPenalty: Int,
    val signalAvailableWeight: Int,
    val mtfConsensusScore: Int?,
    val mtfAvailableCount: Int = 0,
    val mtfExpectedCount: Int = V540CalculationOutputSchema.EXPECTED_MTF_COUNT,
    val dominantDirection: String,
    val direction: String,
    val decisionState: String,
    val signalValidity: String,
    val reasonCodes: List<String>,
    val scoreBreakdown: List<String>,
    val calculationInputHash: String,
    val calculationOutputHash: String,
    val calculationOutputSchema: String = V540CalculationOutputSchema.SCHEMA,
    val qualityClass: String = "UNKNOWN",
    val timingStatus: String = "UNKNOWN",
    val marketRegime: String = "UNKNOWN",
    val marketRegimeConfidence: Int = 0,
    val breakoutDirection: String = "NONE",
    val openInterestChangePct: Double? = null,
    val spreadPct: Double? = null,
    val liquidityScore: Int? = null,
    val slippageSensitivity: Double? = null,
    val structureDistanceAtr: Double? = null,
    val contractRiskPct: Int? = null,
    val mtfConsensusLabel: String = "MTF VERİ YOK",
    /** V5.3.9 publication-gate inputs; hashed so client publication cannot be changed silently. */
    val riskReward1: Double? = null,
    val riskReward2: Double? = null
)

data class RemoteInvariantResult(
    val accepted: Boolean,
    val violations: List<String>
)

/**
 * V5.3.5 fail-closed semantic contract for server-side calculations.
 *
 * Indicators are not recalculated on Android. Instead, the client verifies that
 * the remote output obeys the same score, direction, decision and audit
 * invariants as the local decision engine.
 */
object RemoteEngineContract {
    fun isAccepted(metadata: RemoteEngineMetadata): Boolean = validate(metadata).accepted

    fun validate(metadata: RemoteEngineMetadata): RemoteInvariantResult {
        val violations = mutableListOf<String>()
        fun reject(code: String) { violations += code }

        if (metadata.calculationEngineVersion != ScoringConfig.ENGINE_VERSION) reject("ENGINE_VERSION_MISMATCH")
        if (metadata.engineMode.trim().uppercase() != "REMOTE") reject("ENGINE_MODE_NOT_REMOTE")
        if (metadata.finalSignalScore !in 0..100) reject("FINAL_SCORE_RANGE")
        if (metadata.longScore !in 0..100) reject("LONG_SCORE_RANGE")
        if (metadata.shortScore !in 0..100) reject("SHORT_SCORE_RANGE")
        if (metadata.dataConfidenceScore !in 0..100) reject("CONFIDENCE_RANGE")
        if (metadata.riskScore !in 0..100) reject("RISK_RANGE")
        if (metadata.conflictPenalty !in 0..ScoringConfig.MTF_CONFLICT_PENALTY_MAX) reject("PENALTY_RANGE")
        if (metadata.signalAvailableWeight !in 0..ScoringConfig.TOTAL_SIGNAL_WEIGHT) reject("AVAILABLE_WEIGHT_RANGE")
        if (metadata.mtfConsensusScore != null && metadata.mtfConsensusScore !in -100..100) reject("MTF_RANGE")
        if (metadata.mtfExpectedCount != V540CalculationOutputSchema.EXPECTED_MTF_COUNT) reject("MTF_EXPECTED_COUNT_MISMATCH")
        if (metadata.mtfAvailableCount !in 0..metadata.mtfExpectedCount.coerceAtLeast(0)) reject("MTF_AVAILABLE_COUNT_RANGE")
        if (metadata.mtfAvailableCount >= 3 && metadata.mtfConsensusScore == null) reject("MTF_SCORE_MISSING")
        if (metadata.mtfAvailableCount < 3 && metadata.mtfConsensusScore != null) reject("MTF_SCORE_WITH_INSUFFICIENT_DATA")

        val direction = metadata.direction.trim().uppercase()
        val dominantDirection = metadata.dominantDirection.trim().uppercase()
        if (direction !in setOf("LONG", "SHORT", "WATCH")) reject("DIRECTION_INVALID")
        if (dominantDirection !in setOf("LONG", "SHORT", "WATCH")) reject("DOMINANT_DIRECTION_INVALID")

        val decision = runCatching { DecisionState.valueOf(metadata.decisionState.trim().uppercase()) }.getOrNull()
        if (decision == null) reject("DECISION_STATE_INVALID")
        val validity = runCatching { SignalValidity.valueOf(metadata.signalValidity.trim().uppercase()) }.getOrNull()
        if (validity == null) reject("SIGNAL_VALIDITY_INVALID")

        if (metadata.longScore in 0..100 && metadata.shortScore in 0..100 &&
            metadata.dataConfidenceScore in 0..100 && metadata.signalAvailableWeight in 0..100 &&
            (metadata.mtfConsensusScore == null || metadata.mtfConsensusScore in -100..100)
        ) {
            val expected = SignalDecisionEngine.evaluate(
                longScore = metadata.longScore,
                shortScore = metadata.shortScore,
                dataConfidence = metadata.dataConfidenceScore,
                availableSignalWeight = metadata.signalAvailableWeight,
                mtfConsensusScore = metadata.mtfConsensusScore
            )
            if (metadata.conflictPenalty != expected.conflictPenalty) reject("CONFLICT_PENALTY_MISMATCH")
            if (metadata.finalSignalScore != expected.finalScore) reject("FINAL_SCORE_MISMATCH")
            if (dominantDirection != expected.dominantDirection.name) reject("DOMINANT_DIRECTION_MISMATCH")
            if (direction != expected.decision.name) reject("DECISION_DIRECTION_MISMATCH")

            val expectedReasons = expected.reasonCodes.map { it.name }.toSet()
            val normalizedReasonList = metadata.reasonCodes.map { it.trim().uppercase() }.filter { it.isNotBlank() }
            val actualReasons = normalizedReasonList.toSet()
            if (normalizedReasonList.size != actualReasons.size) reject("DUPLICATE_REASON_CODE")
            if (actualReasons != expectedReasons) reject("DECISION_REASON_CODES_MISMATCH")
        }

        if (metadata.reasonCodes.any { it.isBlank() }) reject("BLANK_REASON_CODE")
        val knownReasonCodes = V531ReasonCode.entries.map { it.name }.toSet()
        if (metadata.reasonCodes.map { it.trim().uppercase() }.filter { it.isNotBlank() }.any { it !in knownReasonCodes }) {
            reject("UNKNOWN_REASON_CODE")
        }
        if (metadata.scoreBreakdown.any { it.isBlank() }) reject("BLANK_SCORE_BREAKDOWN")

        if (decision != null && validity != null) {
            if (decision == DecisionState.VERIFIED_OPPORTUNITY) {
                if (validity != SignalValidity.VALID) reject("VERIFIED_MUST_BE_VALID")
                if (direction !in setOf("LONG", "SHORT")) reject("VERIFIED_DIRECTION_INVALID")
                if (metadata.signalAvailableWeight < ScoringConfig.MIN_AVAILABLE_SIGNAL_WEIGHT) reject("VERIFIED_COVERAGE_TOO_LOW")
                if (metadata.mtfAvailableCount < 3) reject("VERIFIED_MTF_INSUFFICIENT")
                if (metadata.dataConfidenceScore < ScoringConfig.MIN_CONFIDENCE) reject("VERIFIED_CONFIDENCE_TOO_LOW")
                if (abs(metadata.longScore - metadata.shortScore) < ScoringConfig.MIN_DIRECTION_MARGIN) reject("VERIFIED_MARGIN_TOO_LOW")
                val minimumScore = if (metadata.dataConfidenceScore < 80) ScoringConfig.DEGRADED_SIGNAL_THRESHOLD else ScoringConfig.SIGNAL_THRESHOLD
                if (metadata.finalSignalScore < minimumScore) reject("VERIFIED_SCORE_TOO_LOW")
                if (metadata.reasonCodes.isEmpty()) reject("VERIFIED_REASON_CODES_EMPTY")
                if (metadata.scoreBreakdown.isEmpty()) reject("VERIFIED_SCORE_BREAKDOWN_EMPTY")
            }
            if (decision == DecisionState.WATCH && validity == SignalValidity.VALID) reject("WATCH_CANNOT_BE_VALID")
            if (validity == SignalValidity.VALID && decision != DecisionState.VERIFIED_OPPORTUNITY) reject("VALID_MUST_BE_VERIFIED")
        }

        if (metadata.scoreBreakdown.isEmpty()) reject("SCORE_BREAKDOWN_EMPTY")
        val parsedBreakdown = linkedMapOf<String, String>()
        metadata.scoreBreakdown.forEach { raw ->
            val line = raw.trim()
            val idx = line.indexOf('=')
            if (idx <= 0 || idx == line.lastIndex) {
                reject("BREAKDOWN_FORMAT_INVALID")
            } else {
                val key = line.substring(0, idx).trim().uppercase()
                val value = line.substring(idx + 1).trim().uppercase()
                if (key.isBlank() || value.isBlank()) reject("BREAKDOWN_FORMAT_INVALID")
                else if (parsedBreakdown.containsKey(key)) reject("BREAKDOWN_DUPLICATE_KEY_$key")
                else parsedBreakdown[key] = value
            }
        }
        val requiredBreakdown = linkedMapOf(
            "ENGINE" to metadata.calculationEngineVersion.trim().uppercase(),
            "RAW_LONG" to metadata.longScore.toString(),
            "RAW_SHORT" to metadata.shortScore.toString(),
            "CONFLICT_PENALTY" to metadata.conflictPenalty.toString(),
            "FINAL_SCORE" to metadata.finalSignalScore.toString(),
            "CONFIDENCE" to metadata.dataConfidenceScore.toString(),
            "DECISION" to direction
        )
        requiredBreakdown.forEach { (field, expectedValue) ->
            if (parsedBreakdown[field] != expectedValue) reject("BREAKDOWN_${field}_MISMATCH")
        }

        if (metadata.calculationOutputSchema != V540CalculationOutputSchema.SCHEMA) reject("OUTPUT_SCHEMA_MISMATCH")
        V540CalculationOutputSchema.validate(
            metadata.qualityClass,
            metadata.timingStatus,
            metadata.marketRegime,
            metadata.marketRegimeConfidence,
            metadata.breakoutDirection,
            metadata.riskReward1,
            metadata.riskReward2,
            metadata.mtfConsensusScore,
            metadata.mtfAvailableCount,
            metadata.mtfExpectedCount
        ).forEach(::reject)
        if (metadata.liquidityScore != null && metadata.liquidityScore !in 0..100) reject("OUTPUT_LIQUIDITY_RANGE")
        if (metadata.contractRiskPct != null && metadata.contractRiskPct !in 0..100) reject("OUTPUT_CONTRACT_RISK_RANGE")
        if (metadata.riskReward1 != null && (!metadata.riskReward1.isFinite() || metadata.riskReward1 < 0.0 || metadata.riskReward1 > 20.0)) reject("OUTPUT_RR1_RANGE")
        if (metadata.riskReward2 != null && (!metadata.riskReward2.isFinite() || metadata.riskReward2 < 0.0 || metadata.riskReward2 > 30.0)) reject("OUTPUT_RR2_RANGE")
        if (!V533AuditHash.isSha256Hex(metadata.calculationInputHash)) reject("INPUT_HASH_INVALID")
        if (!V533AuditHash.isSha256Hex(metadata.calculationOutputHash)) reject("OUTPUT_HASH_INVALID")
        if (metadata.calculationInputHash.equals(metadata.calculationOutputHash, ignoreCase = true)) reject("INPUT_OUTPUT_HASH_COLLISION")
        if (V533AuditHash.isSha256Hex(metadata.calculationOutputHash)) {
            val expectedOutputHash = V533AuditHash.calculationOutputHash(metadata)
            if (!metadata.calculationOutputHash.equals(expectedOutputHash, ignoreCase = true)) reject("OUTPUT_HASH_MISMATCH")
        }

        return RemoteInvariantResult(violations.isEmpty(), violations.distinct())
    }
}
