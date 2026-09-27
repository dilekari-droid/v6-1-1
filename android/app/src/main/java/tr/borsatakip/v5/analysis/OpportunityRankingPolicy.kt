package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Opportunity

/** Canonical default ranking. UI must not silently replace engine rankingScore with another score. */
object OpportunityRankingPolicy {
    val comparator: Comparator<Opportunity> =
        compareByDescending<Opportunity> { it.rankingStatus == tr.borsatakip.v5.model.RankingStatus.CALCULATED }
            .thenByDescending { it.rankingScore }
            .thenByDescending { it.finalSignalScore }
            .thenByDescending { it.dataConfidenceScore }
            .thenBy { it.riskScore }
            .thenBy { it.symbol }

    fun sort(items: List<Opportunity>): List<Opportunity> = items.sortedWith(comparator)

    fun calculate(
        technicalScore: Int,
        dataConfidence: Int,
        riskScore: Int,
        rr: Double?,
        relativeVolume: Double?,
        freshnessScore: Int
    ): Int = calculateFinal(
        finalSignalScore = technicalScore,
        dataConfidence = dataConfidence,
        riskScore = riskScore,
        rr = rr,
        relativeVolume = relativeVolume,
        freshnessScore = freshnessScore
    )

    fun calculateFinal(
        finalSignalScore: Int,
        dataConfidence: Int,
        riskScore: Int,
        rr: Double?,
        relativeVolume: Double?,
        freshnessScore: Int,
        marketRegimeAdjustment: Int = 0
    ): Int {
        // RR is intentionally NOT rewarded here. V5.4.0 treats RR as an economic
        // publication gate; rewarding it again would double-count an uncalibrated heuristic.
        @Suppress("UNUSED_VARIABLE") val rrGateOnly = rr
        // Relative activity, not a claim of order-book liquidity/depth.
        val volumeActivityScore = relativeVolume
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { (it / (it + 1.0) * 100.0).coerceIn(0.0, 100.0) }
            ?: 0.0
        return (
            finalSignalScore.coerceIn(0, 100) * 0.50 +
                dataConfidence.coerceIn(0, 100) * 0.25 +
                (100 - riskScore.coerceIn(0, 100)) * 0.15 +
                volumeActivityScore * 0.05 +
                freshnessScore.coerceIn(0, 100) * 0.05
            ).toInt().plus(marketRegimeAdjustment.coerceIn(-10, 10)).coerceIn(0, 100)
    }

}
