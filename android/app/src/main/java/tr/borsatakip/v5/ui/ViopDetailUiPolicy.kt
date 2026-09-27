package tr.borsatakip.v5.ui

import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopOpportunity
import tr.borsatakip.v5.model.ViopQuote

enum class ViopDetailDataState(val label: String) {
    LIVE("CANLI"),
    DELAYED("GECİKMELİ"),
    STALE("ESKİ VERİ"),
    UNAVAILABLE("VERİ YOK")
}

enum class ViopDetailTimeframe(
    val shortLabel: String,
    val displayLabel: String,
    val range: String,
    val requestInterval: String,
    val sourceMinutes: Int,
    val aggregateMinutes: Int? = null
) {
    ONE_MIN("1D", "1 DAKİKA", "2d", "1m", 1),
    THREE_MIN("3D", "3 DAKİKA", "6d", "3m", 3),
    FIVE_MIN("5D", "5 DAKİKA", "12d", "5m", 5),
    FIFTEEN_MIN("15D", "15 DAKİKA", "30d", "15m", 15),
    ONE_HOUR("1S", "1 SAAT", "120d", "60m", 60),
    FOUR_HOUR("4S", "4 SAAT", "365d", "60m", 60, aggregateMinutes = 240),
    ONE_DAY("1G", "1 GÜN", "1y", "1d", 1440)
}

object ViopDetailPresentationPolicy {
    fun dataState(quote: ViopQuote?, nowMs: Long = System.currentTimeMillis()): ViopDetailDataState {
        quote ?: return ViopDetailDataState.UNAVAILABLE
        if (quote.exchangeTimestamp <= 0L) return ViopDetailDataState.UNAVAILABLE
        val age = nowMs - quote.exchangeTimestamp
        if (age < -RealTimeIntegrityPolicy.MAX_FUTURE_CLOCK_SKEW_MS || age > RealTimeIntegrityPolicy.MAX_DATA_AGE_MS) {
            return ViopDetailDataState.STALE
        }
        val delay = quote.delaySeconds
        return if (
            quote.realtime &&
            quote.currentSessionIncluded &&
            delay != null &&
            delay in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS
        ) {
            ViopDetailDataState.LIVE
        } else {
            ViopDetailDataState.DELAYED
        }
    }

    fun verifiedDirection(opportunity: ViopOpportunity?): String? {
        opportunity ?: return null
        if (opportunity.validity != SignalValidity.VALID || opportunity.decisionState != DecisionState.VERIFIED_OPPORTUNITY) return null
        return opportunity.direction.uppercase().takeIf { it == "LONG" || it == "SHORT" }
    }
}
