package tr.borsatakip.v5.analysis.v531

object V531ContextEngine {
    fun applyMtf(
        longScore: Int,
        shortScore: Int,
        dataConfidence: Int,
        riskScore: Int,
        availableSignalWeight: Int,
        mtfConsensusScore: Int
    ): V531ContextResult {
        val mtf = MultiTimeframeEngine.evaluate(mtfConsensusScore)
        val contextualLong = (longScore + mtf.longContribution).coerceIn(0, 100)
        val contextualShort = (shortScore + mtf.shortContribution).coerceIn(0, 100)
        val contextualWeight = (availableSignalWeight + if (mtf.available) mtf.weight else 0).coerceIn(0, ScoringConfig.TOTAL_SIGNAL_WEIGHT)
        val decision = SignalDecisionEngine.evaluate(contextualLong, contextualShort, dataConfidence, contextualWeight, mtfConsensusScore)
        val conflictRisk = (kotlin.math.abs(mtfConsensusScore).coerceIn(0, 100) / 10)
        val contextualRisk = if (decision.conflictPenalty > 0) (riskScore + conflictRisk).coerceIn(0, 100) else riskScore.coerceIn(0, 100)
        return V531ContextResult(
            longScore = decision.longScore,
            shortScore = decision.shortScore,
            finalSignalScore = decision.finalScore,
            direction = decision.decision,
            riskScore = contextualRisk,
            availableSignalWeight = contextualWeight,
            conflictPenalty = decision.conflictPenalty,
            reasonCodes = decision.reasonCodes
        )
    }
}
