package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity

/**
 * Yayın güvenliği katmanı.
 * Gerçek zaman bütünlüğünden geçmeyen teknik analizler işlem sinyali gibi yayınlanmaz.
 */
object OpportunityPublicationPolicy {
    fun delayedObservation(opportunity: Opportunity, integrityReason: String): Opportunity {
        val note = "GECİKMELİ/GÜNLÜK VERİ • AL/SAT SİNYALİ ÜRETİLMEDİ. $integrityReason"
        return opportunity.copy(
            direction = "İZLEME",
            finalSignalScore = 0,
            longScore = 0,
            shortScore = 0,
            decisionState = DecisionState.WATCH,
            signalValidity = SignalValidity.WATCH,
            signalValidityReason = note,
            setupType = "GECİKMELİ TEKNİK ANALİZ",
            qualityClass = "İZLEME",
            riskPlan = null,
            snapshot = opportunity.snapshot?.copy(finalSignalScore = 0),
            scoreBreakdown = buildList {
                add("MODE=DELAYED_ANALYSIS")
                add("NO_TRADE_SIGNAL=true")
                add("TECH_LONG_SCORE=${opportunity.analysisLongScore.coerceIn(0, 100)}")
                add("TECH_SHORT_SCORE=${opportunity.analysisShortScore.coerceIn(0, 100)}")
                add("RISK_NOTE=$note")
                addAll(
                    opportunity.scoreBreakdown.filterNot {
                        it.startsWith("DECISION_STATE=") ||
                            it.startsWith("LONG_SCORE=") ||
                            it.startsWith("SHORT_SCORE=") ||
                            it.startsWith("CLASS=") ||
                            it.startsWith("SETUP=") ||
                            it.startsWith("Toplam:")
                    }
                )
            }
        )
    }
}
