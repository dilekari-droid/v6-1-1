package tr.borsatakip.v5.ui

import tr.borsatakip.v5.analysis.OpportunityRankingPolicy
import tr.borsatakip.v5.analysis.ScanDirectionPolicy
import tr.borsatakip.v5.model.Opportunity

/** UI-only ordering policy. It never recalculates signal direction or scores. */
object ScanResultSortPolicy {
    private val directionalComparator: Comparator<Opportunity> =
        compareByDescending<Opportunity> { ScanDirectionPolicy.classify(it).strength }
            .thenByDescending { it.score }
            .thenByDescending { it.dataConfidenceScore }
            .thenBy { it.symbol }

    private val technicalComparator: Comparator<Opportunity> =
        compareByDescending<Opportunity> { it.score }
            .thenByDescending { it.finalSignalScore }
            .thenByDescending { it.dataConfidenceScore }
            .thenBy { it.symbol }

    fun directional(items: List<Opportunity>): List<Opportunity> = items.sortedWith(directionalComparator)
    fun technical(items: List<Opportunity>): List<Opportunity> = items.sortedWith(technicalComparator)
    fun default(items: List<Opportunity>): List<Opportunity> = OpportunityRankingPolicy.sort(items)
}
