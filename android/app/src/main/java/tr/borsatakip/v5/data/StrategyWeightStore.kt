package tr.borsatakip.v5.data

import android.content.Context
import org.json.JSONObject
import tr.borsatakip.v5.analysis.StrategyProfileEngine

/** Persisted, context-specific strategy weights. Contexts are isolated by market/direction/regime. */
class StrategyWeightStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("strategy_weights_v1", Context.MODE_PRIVATE)

    fun load(market: String, direction: String, regime: String): Map<StrategyProfileEngine.StrategyId, Double>? {
        val raw = prefs.getString(key(market, direction, regime), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            StrategyProfileEngine.StrategyId.entries.associateWith { id -> o.optDouble(id.name, Double.NaN) }
                .filterValues { it.isFinite() && it >= 0.0 }
                .takeIf { it.size == StrategyProfileEngine.StrategyId.entries.size }
        }.getOrNull()
    }

    fun save(market: String, direction: String, regime: String, weights: Map<StrategyProfileEngine.StrategyId, Double>) {
        val json = JSONObject().apply { StrategyProfileEngine.StrategyId.entries.forEach { id -> put(id.name, weights[id] ?: 0.0) } }
        prefs.edit().putString(key(market, direction, regime), json.toString()).apply()
    }

    private fun key(market: String, direction: String, regime: String): String =
        listOf(market, direction, regime.ifBlank { "UNKNOWN" }).joinToString("|") { it.trim().uppercase() }
}
