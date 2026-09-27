package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.RankingStatus
import tr.borsatakip.v5.model.SignalValidity

/**
 * Canonical home-screen filter policy for "Günün Fırsatları".
 *
 * It does not create or upgrade signals. ALL/LONG/SHORT are all derived from
 * the same fail-closed realtime marker; WATCH/NEUTRAL rows are not Home
 * opportunities. Rows whose data/analysis is unavailable are excluded instead
 * of being rendered as a fake 0/100 opportunity.
 */
object HomeOpportunityFilterPolicy {
    enum class Filter { ALL, LONG, SHORT;
        companion object {
            fun restore(raw: String?): Filter = entries.firstOrNull { it.name == raw } ?: ALL
        }
    }

    fun hasRenderableAnalysis(item: Opportunity): Boolean {
        if (item.symbol.isBlank()) return false
        if (!item.price.isFinite() || item.price <= 0.0) return false
        if (item.dataMode == DataMode.UNVERIFIED) return false
        if (item.signalValidity == SignalValidity.INSUFFICIENT || item.signalValidity == SignalValidity.REJECTED) return false
        if (item.decisionState == DecisionState.INSUFFICIENT_DATA || item.decisionState == DecisionState.REJECTED) return false
        if (item.rankingStatus == RankingStatus.INSUFFICIENT_DATA || item.rankingStatus == RankingStatus.INVALID) return false
        return true
    }

    fun filter(
        items: List<Opportunity>,
        selected: Filter,
        nowMs: Long = System.currentTimeMillis()
    ): List<Opportunity> {
        val actionable = items.filter(::hasRenderableAnalysis).mapNotNull { item ->
            HomeRealtimeSignalPolicy.bistMarker(item, nowMs)?.let { marker -> item to marker }
        }
        return when (selected) {
            Filter.ALL -> actionable.map { it.first }
            Filter.LONG -> actionable.filter { it.second.direction == "LONG" }.map { it.first }
            Filter.SHORT -> actionable.filter { it.second.direction == "SHORT" }.map { it.first }
        }
    }

    fun filterAndSort(
        items: List<Opportunity>,
        selected: Filter,
        nowMs: Long = System.currentTimeMillis()
    ): List<Opportunity> = TodayOpportunitySortPolicy.sort(
        filter(items, selected, nowMs),
        when (selected) {
            Filter.ALL -> TodayOpportunitySortPolicy.Mode.ALL
            Filter.LONG -> TodayOpportunitySortPolicy.Mode.LONG
            Filter.SHORT -> TodayOpportunitySortPolicy.Mode.SHORT
        }
    )
}
