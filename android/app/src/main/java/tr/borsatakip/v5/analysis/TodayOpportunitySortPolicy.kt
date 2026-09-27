package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.ui.OpportunityUiPolicy

/**
 * Home "Günün Fırsatları" ordering only.
 *
 * ALL deliberately preserves the pre-existing home ordering. LONG/SHORT only
 * change ordering inside their already fail-closed realtime filtered lists.
 * Signal generation, publication gates and filtering are not modified here.
 */
object TodayOpportunitySortPolicy {
    enum class Mode { ALL, LONG, SHORT }

    private val allComparator: Comparator<Opportunity> =
        compareByDescending<Opportunity> {
            HomeRealtimeSignalPolicy.bistMarker(it)?.strength
                ?: OpportunityUiPolicy.strength(it)
                ?: it.rankingScore
        }.thenBy { it.riskScore }

    private val longComparator: Comparator<Opportunity> =
        compareByDescending<Opportunity> { it.analysisLongScore }
            .thenByDescending { it.finalSignalScore }
            .thenByDescending { it.dataConfidenceScore }
            .thenBy { it.riskScore }
            .thenBy { it.symbol }

    private val shortComparator: Comparator<Opportunity> =
        compareByDescending<Opportunity> { it.analysisShortScore }
            .thenByDescending { it.finalSignalScore }
            .thenByDescending { it.dataConfidenceScore }
            .thenBy { it.riskScore }
            .thenBy { it.symbol }

    fun sort(items: List<Opportunity>, mode: Mode): List<Opportunity> = when (mode) {
        Mode.ALL -> items.sortedWith(allComparator)
        Mode.LONG -> items.sortedWith(longComparator)
        Mode.SHORT -> items.sortedWith(shortComparator)
    }
}
