package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity

/**
 * Tarama Sonuçları için fail-closed yön sınıflandırması.
 * Trend etiketi bu karara hiçbir zaman girdi değildir. LONG/SHORT yalnız analiz yönü,
 * yön puanları ve veri geçerliliği birlikte yeterliyse üretilir.
 */
object ScanDirectionPolicy {
    data class Classification(
        val direction: OpportunityDirectionalFilterPolicy.Direction,
        val strength: Int,
        val publishedSignal: Boolean
    )

    fun classify(item: Opportunity): Classification {
        if (item.signalValidity == SignalValidity.INSUFFICIENT ||
            item.signalValidity == SignalValidity.REJECTED ||
            item.dataMode == DataMode.UNVERIFIED ||
            item.dataConfidenceScore < MIN_DATA_CONFIDENCE
        ) {
            return neutral(item)
        }

        val declared = parse(item.analysisDirection)
        val scored = OpportunityDirectionalFilterPolicy.fromScores(
            item.analysisLongScore,
            item.analysisShortScore
        )
        // Hem motorun analiz yönü hem puan kapısı aynı yönü söylemek zorunda.
        val technicalDirection = if (declared != OpportunityDirectionalFilterPolicy.Direction.NEUTRAL && declared == scored) {
            declared
        } else {
            OpportunityDirectionalFilterPolicy.Direction.NEUTRAL
        }
        if (technicalDirection == OpportunityDirectionalFilterPolicy.Direction.NEUTRAL) return neutral(item)

        val published = parse(item.direction)
        val isPublishedRealtimeSignal =
            item.signalValidity == SignalValidity.VALID &&
            item.dataMode == DataMode.REALTIME &&
            item.isRealtime &&
            published == technicalDirection

        val strength = if (isPublishedRealtimeSignal) {
            item.finalSignalScore.coerceIn(0, 100)
        } else {
            OpportunityDirectionalFilterPolicy.scoreFor(item, technicalDirection).coerceIn(0, 100)
        }
        return Classification(technicalDirection, strength, isPublishedRealtimeSignal)
    }

    private fun parse(value: String): OpportunityDirectionalFilterPolicy.Direction = when (value.trim().uppercase()) {
        "LONG" -> OpportunityDirectionalFilterPolicy.Direction.LONG
        "SHORT" -> OpportunityDirectionalFilterPolicy.Direction.SHORT
        else -> OpportunityDirectionalFilterPolicy.Direction.NEUTRAL
    }

    private fun neutral(item: Opportunity) = Classification(
        OpportunityDirectionalFilterPolicy.Direction.NEUTRAL,
        item.score.coerceIn(0, 100),
        publishedSignal = false
    )

    const val MIN_DATA_CONFIDENCE = 60
}
