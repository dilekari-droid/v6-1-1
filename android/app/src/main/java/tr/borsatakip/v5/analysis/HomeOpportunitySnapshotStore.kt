package tr.borsatakip.v5.analysis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.data.RealTimeIntegrityPolicy

/**
 * Very short-lived recovery snapshot for Home -> Günün Fırsatları.
 *
 * It is not an offline signal cache: only already-validated rows are written and every read
 * re-checks the exchange timestamp against the same realtime freshness policy. A provider
 * failure/no-data state must take precedence over this store; callers should use it only when
 * process-memory repositories are empty after recreation/process restart.
 */
class HomeOpportunitySnapshotStore(context: Context) {
    private val prefs = context.getSharedPreferences("home_opportunity_snapshot_v1", Context.MODE_PRIVATE)

    fun save(feed: HomeOpportunityFeed) {
        if (feed.items.isEmpty() || feed.status !in setOf(HomeOpportunityFeedStatus.READY, HomeOpportunityFeedStatus.PARTIAL)) {
            clear()
            return
        }
        val now = System.currentTimeMillis()
        val rows = feed.items.filter { isFresh(it.timestamp, now) && it.dataValidated && it.analysisValidated }
        if (rows.isEmpty()) {
            clear()
            return
        }
        val root = JSONObject().apply {
            put("updatedAt", feed.updatedAt.takeIf { it > 0L } ?: now)
            put("source", feed.source)
            put("items", JSONArray().apply { rows.forEach { put(toJson(it)) } })
        }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    fun load(nowMs: Long = System.currentTimeMillis()): HomeOpportunityFeed? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val a = root.optJSONArray("items") ?: return@runCatching null
            val items = buildList {
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    val item = fromJson(x) ?: continue
                    if (isFresh(item.timestamp, nowMs) && item.dataValidated && item.analysisValidated) add(item)
                }
            }
            if (items.isEmpty()) {
                clear()
                null
            } else HomeOpportunityFeed(
                status = HomeOpportunityFeedStatus.READY,
                items = items,
                source = "PROCESS_RECOVERY:${root.optString("source", "DYNAMIC_BIST_SCAN")}",
                updatedAt = root.optLong("updatedAt", nowMs)
            )
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun isFresh(timestamp: Long, nowMs: Long): Boolean {
        if (timestamp <= 0L) return false
        val age = nowMs - timestamp
        return age in -RealTimeIntegrityPolicy.MAX_FUTURE_CLOCK_SKEW_MS..RealTimeIntegrityPolicy.MAX_DATA_AGE_MS
    }

    private fun toJson(x: HomeOpportunityFeedItem) = JSONObject().apply {
        put("symbol", x.symbol)
        put("market", x.market)
        put("price", x.price)
        put("timestamp", x.timestamp)
        put("trendDirection", x.trendDirection)
        put("trendStrength", x.trendStrength)
        put("signalStrength", x.signalStrength)
        put("finalStrength", x.finalStrength)
        put("signalType", x.signalType)
        put("dataValidated", x.dataValidated)
        put("analysisValidated", x.analysisValidated)
        put("providerStatus", x.providerStatus)
        put("source", x.source)
        if (x.dailyChangePct != null) put("dailyChangePct", x.dailyChangePct)
        if (x.confidence != null) put("confidence", x.confidence)
        if (x.riskScore != null) put("riskScore", x.riskScore)
    }

    private fun fromJson(x: JSONObject): HomeOpportunityFeedItem? {
        val price = x.optDouble("price", Double.NaN).takeIf { it.isFinite() && it > 0.0 } ?: return null
        val ts = x.optLong("timestamp", 0L).takeIf { it > 0L } ?: return null
        val direction = x.optString("trendDirection").uppercase().takeIf { it in setOf("LONG", "SHORT") } ?: return null
        val provider = x.optString("providerStatus").uppercase()
        if (provider !in setOf("READY", "CONNECTED", "REALTIME", "OK", "AVAILABLE")) return null
        return HomeOpportunityFeedItem(
            symbol = x.optString("symbol").trim().uppercase().takeIf { it.isNotBlank() } ?: return null,
            market = x.optString("market", "BIST").trim().uppercase(),
            price = price,
            timestamp = ts,
            trendDirection = direction,
            trendStrength = x.optInt("trendStrength", 0).coerceIn(0, 100),
            signalStrength = x.optInt("signalStrength", 0).coerceIn(0, 100),
            finalStrength = x.optInt("finalStrength", 0).coerceIn(0, 100),
            signalType = x.optString("signalType", direction),
            dataValidated = x.optBoolean("dataValidated", false),
            analysisValidated = x.optBoolean("analysisValidated", false),
            providerStatus = provider,
            source = x.optString("source", "DYNAMIC_BIST_SCAN"),
            dailyChangePct = x.optDouble("dailyChangePct", Double.NaN).takeIf { it.isFinite() },
            confidence = x.optInt("confidence", -1).takeIf { it in 0..100 },
            riskScore = x.optInt("riskScore", -1).takeIf { it in 0..100 }
        ).takeIf { it.trendStrength > 0 && it.signalStrength > 0 && it.finalStrength > 0 }
    }

    companion object { private const val KEY = "snapshot" }
}
