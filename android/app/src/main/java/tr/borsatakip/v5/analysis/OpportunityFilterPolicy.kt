package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity

enum class OpportunityFilter {
    ALL, A_CLASS, BREAKOUT, REVERSAL, SQUEEZE, PULLBACK, VOLUME, TREND,
    LONG, SHORT, HIGH_POWER
}

object OpportunityFilterPolicy {
    fun apply(items: List<Opportunity>, filter: OpportunityFilter): List<Opportunity> {
        val sorted = items.sortedWith(
            compareByDescending<Opportunity> { it.signalValidity == SignalValidity.VALID || it.signalValidity == SignalValidity.WATCH }
                .thenByDescending { Opportunity2Presentation.from(it).klass == "A SINIFI" }
                .thenByDescending { it.rankingScore }
                .thenByDescending { it.finalSignalScore }
                .thenByDescending { it.dataConfidenceScore }
                .thenByDescending { Opportunity2Presentation.from(it).riskReward ?: -1.0 }
                .thenBy { it.riskScore }
                .thenBy { it.symbol }
        )
        val actionable = sorted.filter { it.signalValidity == SignalValidity.VALID || it.signalValidity == SignalValidity.WATCH }
        return when (filter) {
            OpportunityFilter.ALL -> sorted
            OpportunityFilter.A_CLASS -> actionable.filter { Opportunity2Presentation.from(it).klass == "A SINIFI" }
            OpportunityFilter.BREAKOUT -> actionable.filter { Opportunity2Presentation.from(it).setup.contains("KIRILIM") }
            OpportunityFilter.REVERSAL -> actionable.filter { Opportunity2Presentation.from(it).setup.contains("DİPTEN DÖNÜŞ") || Opportunity2Presentation.from(it).setup.contains("DESTEKTE TEPKİ") }
            OpportunityFilter.SQUEEZE -> actionable.filter { Opportunity2Presentation.from(it).setup.contains("SIKIŞMA") }
            OpportunityFilter.PULLBACK -> actionable.filter { Opportunity2Presentation.from(it).setup.contains("GERİ ÇEKİLME") }
            OpportunityFilter.VOLUME -> actionable.filter { (it.technical.volumeRatio ?: 0.0) >= 1.5 }
            OpportunityFilter.TREND -> actionable.filter {
                val t = it.technical
                val p = it.price
                (t.ema20 != null && t.ema50 != null && t.ema200 != null) &&
                    ((p > t.ema20 && t.ema20 > t.ema50 && t.ema50 > t.ema200) ||
                     (p < t.ema20 && t.ema20 < t.ema50 && t.ema50 < t.ema200))
            }
            OpportunityFilter.LONG -> actionable.filter { it.direction.equals("LONG", true) }
            OpportunityFilter.SHORT -> actionable.filter { it.direction.equals("SHORT", true) }
            OpportunityFilter.HIGH_POWER -> actionable.filter { it.finalSignalScore >= 85 }
        }
    }
}
