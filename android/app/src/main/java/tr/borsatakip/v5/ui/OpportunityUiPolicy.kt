package tr.borsatakip.v5.ui

import tr.borsatakip.v5.analysis.SignalVisualPolicy
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.Opportunity

enum class OpportunityUiDataState { WAITING, ANALYZING, READY, PARTIAL, ARCHIVE, UNAVAILABLE, ERROR }

data class OpportunityTopMetrics(
    val watched: String,
    val opportunities: String,
    val dataQuality: String
)

/** Shared truth rules for Fırsat Kontrolü. Data absence is never converted to zero. */
object OpportunityUiPolicy {
    fun isNumericState(state: OpportunityUiDataState): Boolean =
        state in setOf(OpportunityUiDataState.READY, OpportunityUiDataState.PARTIAL, OpportunityUiDataState.ARCHIVE)

    fun directionLabel(item: Opportunity): String = when {
        item.decisionState == DecisionState.INSUFFICIENT_DATA -> "YETERSİZ VERİ"
        item.decisionState == DecisionState.REJECTED -> "REDDEDİLDİ"
        item.decisionState == DecisionState.VERIFIED_OPPORTUNITY && item.direction.equals("LONG", true) -> "LONG"
        item.decisionState == DecisionState.VERIFIED_OPPORTUNITY && item.direction.equals("SHORT", true) -> "SHORT"
        else -> "İZLE"
    }

    fun strength(item: Opportunity): Int? =
        SignalVisualPolicy.qualityFor(item.direction, item.longScore, item.shortScore)

    fun isStrong(item: Opportunity): Boolean = (strength(item) ?: -1) >= 81

    fun actionableCount(items: List<Opportunity>): Int = items.count {
        it.decisionState == DecisionState.VERIFIED_OPPORTUNITY &&
            (it.direction.equals("LONG", true) || it.direction.equals("SHORT", true))
    }

    fun metrics(state: OpportunityUiDataState, watchedCount: Int, items: List<Opportunity>): OpportunityTopMetrics {
        if (!isNumericState(state)) {
            return OpportunityTopMetrics(
                watched = "—",
                opportunities = "—",
                dataQuality = when (state) {
                    OpportunityUiDataState.WAITING -> "Bekleniyor"
                    OpportunityUiDataState.ANALYZING -> "Analiz ediliyor"
                    OpportunityUiDataState.UNAVAILABLE -> "Veri yok"
                    OpportunityUiDataState.ERROR -> "Hata"
                    else -> "—"
                }
            )
        }
        return OpportunityTopMetrics(
            watched = watchedCount.coerceAtLeast(0).toString(),
            opportunities = actionableCount(items).toString(),
            dataQuality = when (state) {
                OpportunityUiDataState.READY -> "Hazır"
                OpportunityUiDataState.PARTIAL -> "Kısmi"
                OpportunityUiDataState.ARCHIVE -> "Arşiv"
                else -> "—"
            }
        )
    }
}
