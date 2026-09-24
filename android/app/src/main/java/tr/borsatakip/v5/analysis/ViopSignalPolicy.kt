package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopOpportunity
import tr.borsatakip.v5.model.ViopPublishedSignalDirection
import tr.borsatakip.v5.model.ViopAnalysisBias

/**
 * VİOP ekranında yayınlanmış işlem sinyali ile analitik/dayanak eğilimini ayırır.
 * Production filtre ve sayaçları yalnız publishedDirection() kullanır.
 * analysisBias() hiçbir zaman kendi başına VİOP işlem sinyali sayılmaz.
 */
object ViopSignalPolicy {
    enum class PublishedDirection { LONG, SHORT, WATCH }
    enum class AnalysisBias { LONG, SHORT, NEUTRAL }

    fun publishedDirection(item: ViopOpportunity): PublishedDirection {
        if (item.contract.validity != SignalValidity.VALID ||
            item.validity != SignalValidity.VALID ||
            item.decisionState != DecisionState.VERIFIED_OPPORTUNITY
        ) {
            return PublishedDirection.WATCH
        }
        return when (item.publishedSignalDirection) {
            ViopPublishedSignalDirection.LONG -> PublishedDirection.LONG
            ViopPublishedSignalDirection.SHORT -> PublishedDirection.SHORT
            ViopPublishedSignalDirection.WATCH -> PublishedDirection.WATCH
        }
    }

    fun analysisBias(item: ViopOpportunity): AnalysisBias = when (item.analysisBias) {
        ViopAnalysisBias.LONG -> AnalysisBias.LONG
        ViopAnalysisBias.SHORT -> AnalysisBias.SHORT
        ViopAnalysisBias.NEUTRAL -> AnalysisBias.NEUTRAL
    }

    /** Dayanak analizi hiçbir koşulda gerçek VİOP publication sayılmaz. */
    @Suppress("UNUSED_PARAMETER")
    fun publishedDirection(item: Opportunity): PublishedDirection = PublishedDirection.WATCH

    fun analysisBias(item: Opportunity): AnalysisBias = when (OpportunityDirectionalFilterPolicy.effectiveDirection(item)) {
        OpportunityDirectionalFilterPolicy.Direction.LONG -> AnalysisBias.LONG
        OpportunityDirectionalFilterPolicy.Direction.SHORT -> AnalysisBias.SHORT
        OpportunityDirectionalFilterPolicy.Direction.NEUTRAL -> AnalysisBias.NEUTRAL
    }

    fun underlyingBiasScore(item: Opportunity): Int {
        val direction = OpportunityDirectionalFilterPolicy.effectiveDirection(item)
        return OpportunityDirectionalFilterPolicy.scoreFor(item, direction).coerceIn(0, 100)
    }

    private fun parseBias(raw: String): AnalysisBias = when (raw.trim().uppercase()) {
        "LONG" -> AnalysisBias.LONG
        "SHORT" -> AnalysisBias.SHORT
        else -> AnalysisBias.NEUTRAL
    }
}
