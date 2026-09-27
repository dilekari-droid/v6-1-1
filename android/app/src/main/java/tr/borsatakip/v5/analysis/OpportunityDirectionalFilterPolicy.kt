package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopOpportunity

/** One canonical LONG/SHORT filter policy used by both stock and VIOP screens. */
object OpportunityDirectionalFilterPolicy {
    enum class Direction { LONG, SHORT, NEUTRAL }

    fun fromScores(longScore: Int, shortScore: Int): Direction = when {
        longScore >= MIN_DIRECTION_SCORE && longScore - shortScore >= MIN_SCORE_EDGE -> Direction.LONG
        shortScore >= MIN_DIRECTION_SCORE && shortScore - longScore >= MIN_SCORE_EDGE -> Direction.SHORT
        else -> Direction.NEUTRAL
    }

    fun effectiveDirection(item: Opportunity): Direction {
        val published = item.direction.trim().uppercase()
        if (item.signalValidity == SignalValidity.VALID) {
            if (published == "LONG") return Direction.LONG
            if (published == "SHORT") return Direction.SHORT
        }
        return fromScores(item.analysisLongScore, item.analysisShortScore)
    }

    fun effectiveDirection(item: ViopOpportunity): Direction {
        val published = item.direction.trim().uppercase()
        if (item.validity == SignalValidity.VALID) {
            if (published == "LONG") return Direction.LONG
            if (published == "SHORT") return Direction.SHORT
        }
        return fromScores(item.analysisLongScore, item.analysisShortScore)
    }

    fun scoreFor(item: Opportunity, direction: Direction): Int = when (direction) {
        Direction.LONG -> item.analysisLongScore
        Direction.SHORT -> item.analysisShortScore
        Direction.NEUTRAL -> item.rankingScore
    }

    fun scoreFor(item: ViopOpportunity, direction: Direction): Int = when (direction) {
        Direction.LONG -> item.analysisLongScore
        Direction.SHORT -> item.analysisShortScore
        Direction.NEUTRAL -> item.rankingScore
    }

    const val MIN_DIRECTION_SCORE = 20
    const val MIN_SCORE_EDGE = 4
}
