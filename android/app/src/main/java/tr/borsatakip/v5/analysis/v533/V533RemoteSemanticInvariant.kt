package tr.borsatakip.v5.analysis.v533

import tr.borsatakip.v5.analysis.v531.RemoteEngineContract
import tr.borsatakip.v5.analysis.v531.RemoteEngineMetadata
import tr.borsatakip.v5.analysis.v531.SignalDecisionEngine

data class V533InvariantReport(
    val accepted: Boolean,
    val violations: List<String>,
    val expectedFinalSignalScore: Int?,
    val expectedDominantDirection: String?,
    val expectedDirection: String?,
    val expectedConflictPenalty: Int?
)

object V533RemoteSemanticInvariant {
    fun evaluate(metadata: RemoteEngineMetadata): V533InvariantReport {
        val validation = RemoteEngineContract.validate(metadata)
        val expected = if (
            metadata.longScore in 0..100 && metadata.shortScore in 0..100 &&
            metadata.dataConfidenceScore in 0..100 && metadata.signalAvailableWeight in 0..100 &&
            (metadata.mtfConsensusScore == null || metadata.mtfConsensusScore in -100..100)
        ) SignalDecisionEngine.evaluate(
            metadata.longScore, metadata.shortScore, metadata.dataConfidenceScore,
            metadata.signalAvailableWeight, metadata.mtfConsensusScore
        ) else null
        return V533InvariantReport(
            validation.accepted,
            validation.violations,
            expected?.finalScore,
            expected?.dominantDirection?.name,
            expected?.decision?.name,
            expected?.conflictPenalty
        )
    }
}
