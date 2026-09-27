package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopOpportunity
import tr.borsatakip.v5.model.ViopPublishedSignalDirection
import kotlin.math.abs

/**
 * Fail-closed signal presentation rules used by the home screen.
 * A visual pulse is never allowed to create or upgrade a trading signal.
 */
object HomeRealtimeSignalPolicy {
    data class Marker(val direction: String, val strength: Int)

    fun bistMarker(item: Opportunity, nowMs: Long = System.currentTimeMillis()): Marker? {
        if (!item.isRealtime || item.dataMode != DataMode.REALTIME || item.signalValidity != SignalValidity.VALID) return null
        val age = item.dataAgeMs
        if (age != null && age !in 0..RealTimeIntegrityPolicy.MAX_DATA_AGE_MS) return null
        val exchangeAge = nowMs - item.exchangeTimestamp
        if (item.exchangeTimestamp <= 0L || exchangeAge !in 0..RealTimeIntegrityPolicy.MAX_DATA_AGE_MS) return null
        val direction = item.direction.trim().uppercase()
        if (direction !in setOf("LONG", "SHORT")) return null
        val strength = SignalVisualPolicy.qualityFor(direction, item.longScore, item.shortScore)
            ?: return null
        if (strength <= 0) return null
        return Marker(direction, strength.coerceIn(0, 100))
    }

    fun viopMarker(item: ViopOpportunity, nowMs: Long = System.currentTimeMillis()): Marker? {
        if (item.validity != SignalValidity.VALID) return null
        if (!item.quote.realtime || !item.quote.currentSessionIncluded) return null
        if (item.dataAgeMs !in 0..RealTimeIntegrityPolicy.MAX_DATA_AGE_MS) return null
        val exchangeAge = nowMs - item.quote.exchangeTimestamp
        if (item.quote.exchangeTimestamp <= 0L || exchangeAge !in 0..RealTimeIntegrityPolicy.MAX_DATA_AGE_MS) return null
        if (item.dataConfidenceScore < MIN_DATA_CONFIDENCE) return null
        val direction = when (item.publishedSignalDirection) {
            ViopPublishedSignalDirection.LONG -> "LONG"
            ViopPublishedSignalDirection.SHORT -> "SHORT"
            ViopPublishedSignalDirection.WATCH -> "NEUTRAL"
        }
        return Marker(direction, item.finalScore.coerceIn(0, 100))
    }

    fun isMeaningfulChange(previous: Marker?, current: Marker?): Boolean {
        if (current == null) return false
        if (previous == null) return current.direction in setOf("LONG", "SHORT")
        if (previous.direction != current.direction) {
            return current.direction in setOf("LONG", "SHORT")
        }
        return current.direction in setOf("LONG", "SHORT") &&
            abs(current.strength - previous.strength) >= MIN_STRENGTH_DELTA
    }

    const val MIN_STRENGTH_DELTA = 5
    const val MIN_DATA_CONFIDENCE = 60
}
