package tr.borsatakip.v5.data.v536

import android.content.Context
import tr.borsatakip.v5.analysis.v536.V536RemoteEvent
import tr.borsatakip.v5.analysis.v536.V536RemoteEventType

/**
 * Persistent privacy-safe production diagnostics.
 * Stores only event counters, last event type/code/time and engine version.
 * It intentionally never stores symbols, prices, payloads, credentials or signatures.
 */
class V536ObservabilityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("v536_observability", Context.MODE_PRIVATE)

    @Synchronized
    fun record(event: V536RemoteEvent) {
        val countKey = "count_${event.type.name}"
        val next = prefs.getLong(countKey, 0L) + 1L
        prefs.edit()
            .putLong(countKey, next)
            .putString("last_type", event.type.name)
            .putString("last_code", sanitize(event.code))
            .putLong("last_at", event.atMillis)
            .putString("last_engine", event.engineVersion)
            .apply()
    }

    fun count(type: V536RemoteEventType): Long = prefs.getLong("count_${type.name}", 0L)

    private fun sanitize(code: String): String = code.trim().replace(Regex("[^A-Za-z0-9_.,|:-]"), "_").take(160)
}
