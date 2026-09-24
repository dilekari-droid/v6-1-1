package tr.borsatakip.v5.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ViopOpportunity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Kalıcı, tekrarı sınırlı fırsat olay geçmişi. Bildirim izninden bağımsız olarak olay kaydı tutar. */
class AlertEventStore(context: Context) {
    private val prefs = context.getSharedPreferences("alert_event_store_v1", Context.MODE_PRIVATE)

    enum class Type { NEW_STRONG_SIGNAL, SCORE_UP, SCORE_DOWN, STOP_NEAR, TARGET1_REACHED, TARGET2_REACHED, DATA_STALE }

    data class Event(val type: Type, val symbol: String, val message: String, val score: Int, val createdAt: Long)

    @Synchronized
    fun evaluate(items: List<Opportunity>, now: Long = System.currentTimeMillis()): List<Event> {
        val emitted = mutableListOf<Event>()
        val edit = prefs.edit()
        items.forEach { x ->
            val key = x.symbol.uppercase()
            val previous = prefs.getInt("score:$key", Int.MIN_VALUE)
            if (previous == Int.MIN_VALUE && x.finalSignalScore >= 80) emitted += event(Type.NEW_STRONG_SIGNAL, x, "Yeni güçlü ${x.direction} sinyali", now)
            if (previous != Int.MIN_VALUE) {
                val delta = x.finalSignalScore - previous
                if (delta >= 5) emitted += event(Type.SCORE_UP, x, "Skor $previous → ${x.finalSignalScore}", now)
                if (delta <= -5) emitted += event(Type.SCORE_DOWN, x, "Skor $previous → ${x.finalSignalScore}", now)
            }
            x.riskPlan?.let { p ->
                val stop = p.stop
                if (stop != null) {
                    val riskDistance = abs(p.entry - stop).takeIf { it > 1e-9 }
                    if (riskDistance != null && abs(x.price - stop) <= riskDistance * 0.30) {
                        emitted += event(Type.STOP_NEAR, x, "Stop seviyesine yaklaştı", now)
                    }
                }
                if (p.target2 != null && if (x.direction.equals("LONG", true)) x.price >= p.target2 else x.price <= p.target2) {
                    emitted += event(Type.TARGET2_REACHED, x, "Hedef 2 seviyesi görüldü", now)
                } else if (p.target1 != null && if (x.direction.equals("LONG", true)) x.price >= p.target1 else x.price <= p.target1) {
                    emitted += event(Type.TARGET1_REACHED, x, "Hedef 1 seviyesi görüldü", now)
                }
            }
            if ((x.dataAgeMs ?: 0L) > STALE_EVENT_MS) emitted += event(Type.DATA_STALE, x, "Piyasa verisi eskidi", now)
            edit.putInt("score:$key", x.finalSignalScore)
        }
        edit.apply()
        val accepted = emitted.filter { shouldRecord(it, now) }
        if (accepted.isNotEmpty()) append(accepted)
        return accepted
    }


    @Synchronized
    fun evaluateViop(items: List<ViopOpportunity>, now: Long = System.currentTimeMillis()): List<Event> {
        val emitted = mutableListOf<Event>()
        val edit = prefs.edit()
        items.forEach { x ->
            val key = x.contract.symbol.uppercase()
            val previous = prefs.getInt("score:$key", Int.MIN_VALUE)
            if (previous == Int.MIN_VALUE && x.finalScore >= 80) emitted += Event(Type.NEW_STRONG_SIGNAL, key, "Yeni güçlü ${x.direction} VİOP sinyali", x.finalScore, now)
            if (previous != Int.MIN_VALUE) {
                val delta = x.finalScore - previous
                if (delta >= 5) emitted += Event(Type.SCORE_UP, key, "VİOP skor $previous → ${x.finalScore}", x.finalScore, now)
                if (delta <= -5) emitted += Event(Type.SCORE_DOWN, key, "VİOP skor $previous → ${x.finalScore}", x.finalScore, now)
            }
            x.riskPlan?.let { p ->
                val stop = p.stop
                if (stop != null) {
                    val riskDistance = abs(p.entry - stop).takeIf { it > 1e-9 }
                    if (riskDistance != null && abs(x.quote.price - stop) <= riskDistance * 0.30)
                        emitted += Event(Type.STOP_NEAR, key, "VİOP stop seviyesine yaklaştı", x.finalScore, now)
                }
                if (p.target2 != null && if (x.direction.equals("LONG", true)) x.quote.price >= p.target2 else x.quote.price <= p.target2)
                    emitted += Event(Type.TARGET2_REACHED, key, "VİOP Hedef 2 seviyesi görüldü", x.finalScore, now)
                else if (p.target1 != null && if (x.direction.equals("LONG", true)) x.quote.price >= p.target1 else x.quote.price <= p.target1)
                    emitted += Event(Type.TARGET1_REACHED, key, "VİOP Hedef 1 seviyesi görüldü", x.finalScore, now)
            }
            if (x.dataAgeMs > STALE_EVENT_MS) emitted += Event(Type.DATA_STALE, key, "VİOP piyasa verisi eskidi", x.finalScore, now)
            edit.putInt("score:$key", x.finalScore)
        }
        edit.apply()
        val accepted = emitted.filter { shouldRecord(it, now) }
        if (accepted.isNotEmpty()) append(accepted)
        return accepted
    }

    @Synchronized
    fun recentEvents(limit: Int = 100): List<Event> {
        val a = readArray()
        return (a.length() - 1 downTo 0).mapNotNull { i ->
            if (a.length() - 1 - i >= limit.coerceAtLeast(1)) return@mapNotNull null
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val type = runCatching { Type.valueOf(o.optString("type")) }.getOrNull() ?: return@mapNotNull null
            Event(
                type = type,
                symbol = o.optString("symbol"),
                message = o.optString("message"),
                score = o.optInt("score"),
                createdAt = o.optLong("createdAt", 0L)
            )
        }
    }

    @Synchronized
    fun unreadCount(): Int {
        val lastReadAt = prefs.getLong("last_read_at", 0L)
        return recentEvents(MAX_EVENTS).count { it.createdAt > lastReadAt }
    }

    @Synchronized
    fun markAllRead(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("last_read_at", now.coerceAtLeast(0L)).apply()
    }

    @Synchronized
    fun recentLines(limit: Int = 40): List<String> {
        val a = readArray()
        val df = SimpleDateFormat("dd.MM HH:mm", Locale("tr", "TR"))
        return (a.length() - 1 downTo 0).mapNotNull { i ->
            if (a.length() - 1 - i >= limit) return@mapNotNull null
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val at = o.optLong("createdAt", 0L)
            "${if (at > 0) df.format(Date(at)) else "?"} • ${o.optString("symbol")} • ${o.optString("type")}\n${o.optString("message")} • Skor ${o.optInt("score")}/100"
        }
    }

    private fun event(type: Type, x: Opportunity, message: String, now: Long) = Event(type, x.symbol, message, x.finalSignalScore, now)

    private fun shouldRecord(e: Event, now: Long): Boolean {
        val key = "event:${e.type.name}:${e.symbol.uppercase()}"
        val last = prefs.getLong(key, 0L)
        if (now - last < EVENT_COOLDOWN_MS) return false
        prefs.edit().putLong(key, now).apply()
        return true
    }

    private fun append(events: List<Event>) {
        val a = readArray()
        events.forEach { e -> a.put(JSONObject().apply {
            put("type", e.type.name); put("symbol", e.symbol); put("message", e.message); put("score", e.score); put("createdAt", e.createdAt)
        }) }
        val out = JSONArray()
        val start = (a.length() - MAX_EVENTS).coerceAtLeast(0)
        for (i in start until a.length()) out.put(a.get(i))
        prefs.edit().putString("events", out.toString()).apply()
    }

    private fun readArray(): JSONArray = runCatching { JSONArray(prefs.getString("events", "[]") ?: "[]") }.getOrDefault(JSONArray())

    companion object {
        private const val MAX_EVENTS = 200
        private const val EVENT_COOLDOWN_MS = 60L * 60L * 1000L
        private const val STALE_EVENT_MS = 5L * 60L * 1000L
    }
}
