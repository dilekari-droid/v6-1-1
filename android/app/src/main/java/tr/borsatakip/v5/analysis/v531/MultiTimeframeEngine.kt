package tr.borsatakip.v5.analysis.v531

object MultiTimeframeEngine {
    fun evaluate(consensusScore: Int?): V531ComponentScore {
        val score = consensusScore?.coerceIn(-100, 100)
            ?: return ScoreNormalizer.component("MULTI_TIMEFRAME", ScoringConfig.MULTI_TIMEFRAME_WEIGHT, null, listOf("MTF N/A"))
        return ScoreNormalizer.component(
            "MULTI_TIMEFRAME",
            ScoringConfig.MULTI_TIMEFRAME_WEIGHT,
            score / 100.0,
            listOf("MTF_SCORE=$score")
        )
    }
}
