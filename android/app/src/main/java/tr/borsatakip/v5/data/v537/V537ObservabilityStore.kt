package tr.borsatakip.v5.data.v537

import android.content.Context
import tr.borsatakip.v5.analysis.v536.V536RemoteEvent
import tr.borsatakip.v5.analysis.v536.V536RemoteEventType

/**
 * Process-atomic, privacy-safe observability counters.
 * No symbol, price, payload, credential, signature or nonce is persisted.
 */
class V537ObservabilityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("v537_observability", Context.MODE_PRIVATE)

    fun record(event: V536RemoteEvent): Boolean = synchronized(OBSERVABILITY_LOCK) {
        val countKey = "count_${event.type.name}"
        val next = prefs.getLong(countKey, 0L) + 1L
        prefs.edit()
            .putLong(countKey, next)
            .putString("last_type", event.type.name)
            .putString("last_code", sanitize(event.code))
            .putLong("last_at", event.atMillis)
            .putString("last_engine", event.engineVersion)
            .commit()
    }

    fun count(type: V536RemoteEventType): Long = synchronized(OBSERVABILITY_LOCK) {
        prefs.getLong("count_${type.name}", 0L)
    }

    private fun sanitize(code: String): String = code.trim().replace(Regex("[^A-Za-z0-9_.,|:-]"), "_").take(160)

    private companion object {
        val OBSERVABILITY_LOCK = Any()
    }
}
