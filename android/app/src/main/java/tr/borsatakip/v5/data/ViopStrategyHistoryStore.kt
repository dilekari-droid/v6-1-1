package tr.borsatakip.v5.data

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.analysis.StrategyComparisonMath
import tr.borsatakip.v5.analysis.StrategyProfileEngine
import tr.borsatakip.v5.model.ViopOpportunity
import java.io.File

/**
 * Minimal verified VİOP strategy history for V5.1.48.
 * Outcomes are written only from provider historical 1m candles; current quote is never used as a
 * historical substitute. The comparison horizon is fixed at 1 hour for dynamic weighting.
 */
class ViopStrategyHistoryStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "viop_strategy_history_v1.json")

    suspend fun record(items: List<ViopOpportunity>) = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            val root = readUnsafe()
            val records = root.optJSONArray("records") ?: JSONArray()
            val existing = HashSet<String>()
            for (i in 0 until records.length()) existing += records.optJSONObject(i)?.optString("id").orEmpty()
            items.forEach { v ->
                val generatedAt = v.quote.exchangeTimestamp.takeIf { it > 0L } ?: return@forEach
                val id = "${v.contract.symbol.uppercase()}|$generatedAt"
                if (id in existing) return@forEach
                val scores = StrategyProfileEngine.evaluate(v)
                records.put(JSONObject().apply {
                    put("id", id)
                    put("symbol", v.contract.symbol.uppercase())
                    put("direction", v.direction)
                    put("signalPrice", v.quote.price)
                    put("generatedAt", generatedAt)
                    put("marketRegime", v.marketRegime)
                    put("strategyIds", JSONArray().apply { scores.filter { it.active }.forEach { put(it.id.name) } })
                    put("strategyScores", JSONObject().apply { scores.forEach { put(it.id.name, it.score) } })
                    put("rankingScore", v.rankingScore)
                    put("ensembleScore", v.ensembleScore ?: JSONObject.NULL)
                    put("ensembleStatus", v.ensembleStatus)
                    put("weights", v.strategyWeightsLabel)
                    put("outcomeStatus", "PENDING")
                })
                existing += id
            }
            while (records.length() > MAX_RECORDS) records.remove(0)
            root.put("records", records)
            writeUnsafe(root)
        }
    }

    suspend fun updateDueOutcomes(backend: BackendProvider, now: Long = System.currentTimeMillis(), maxFetches: Int = 8) {
        data class Work(val id: String, val symbol: String, val generatedAt: Long, val price: Double, val direction: String)
        val work = withContext(Dispatchers.IO) {
            MUTEX.withLock {
                val records = readUnsafe().optJSONArray("records") ?: JSONArray()
                buildList {
                    for (i in 0 until records.length()) {
                        if (size >= maxFetches) break
                        val r = records.optJSONObject(i) ?: continue
                        if (r.optString("outcomeStatus") == "VERIFIED_HISTORICAL") continue
                        val generatedAt = r.optLong("generatedAt", 0L)
                        if (generatedAt <= 0L || generatedAt + HORIZON_MS > now) continue
                        if (now - generatedAt > MAX_LOOKBACK_MS) continue
                        val price = r.optDouble("signalPrice", Double.NaN)
                        if (!price.isFinite() || price <= 0.0) continue
                        add(Work(r.optString("id"), r.optString("symbol"), generatedAt, price, r.optString("direction")))
                    }
                }
            }
        }
        if (work.isEmpty()) return
        val updates = mutableMapOf<String, JSONObject>()
        for (w in work) {
            val candles = backend.loadViopHistoryFlexible(w.symbol, "7d", "1m", 0).getOrDefault(emptyList())
            val target = w.generatedAt + HORIZON_MS
            val candle = candles.firstOrNull { it.timestamp > w.generatedAt && it.timestamp >= target && it.timestamp <= target + MATCH_TOLERANCE_MS } ?: continue
            val ret = when (w.direction.uppercase()) {
                "LONG" -> (candle.close - w.price) / w.price * 100.0
                "SHORT" -> (w.price - candle.close) / w.price * 100.0
                else -> Double.NaN
            }
            if (!ret.isFinite()) continue
            updates[w.id] = JSONObject().apply {
                put("verification", "VERIFIED_HISTORICAL")
                put("targetTime", target)
                put("matchedMarketTime", candle.timestamp)
                put("price", candle.close)
                put("directionalReturnPct", ret)
                put("calculatedAt", now)
            }
        }
        if (updates.isEmpty()) return
        withContext(Dispatchers.IO) {
            MUTEX.withLock {
                val root = readUnsafe(); val records = root.optJSONArray("records") ?: return@withLock
                var changed = false
                for (i in 0 until records.length()) {
                    val r = records.optJSONObject(i) ?: continue
                    val outcome = updates[r.optString("id")] ?: continue
                    r.put("outcome1h", outcome); r.put("outcomeStatus", "VERIFIED_HISTORICAL"); changed = true
                }
                if (changed) writeUnsafe(root)
            }
        }
    }

    suspend fun performance(direction: String? = null, regime: String? = null, limit: Int = 100): List<StrategyPerformance> = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            val records = readUnsafe().optJSONArray("records") ?: JSONArray()
            val returns = StrategyProfileEngine.StrategyId.entries.associate { it.name to mutableListOf<Double>() }.toMutableMap()
            var accepted = 0
            for (i in records.length() - 1 downTo 0) {
                if (accepted >= limit) break
                val r = records.optJSONObject(i) ?: continue
                if (direction != null && !r.optString("direction").equals(direction, true)) continue
                if (regime != null && !r.optString("marketRegime", "VERİ YOK").equals(regime, true)) continue
                val outcome = r.optJSONObject("outcome1h") ?: continue
                if (outcome.optString("verification") != "VERIFIED_HISTORICAL") continue
                val ret = outcome.optDouble("directionalReturnPct", Double.NaN)
                if (!ret.isFinite()) continue
                val ids = r.optJSONArray("strategyIds") ?: continue
                accepted++
                for (j in 0 until ids.length()) returns[ids.optString(j)]?.add(ret)
            }
            StrategyProfileEngine.StrategyId.entries.map { id ->
                val vals = returns[id.name].orEmpty(); val positive = vals.count { it > 0.0 }; val negative = vals.count { it < 0.0 }
                StrategyPerformance(
                    strategyId = id.name,
                    strategyLabel = id.label,
                    totalVerified = vals.size,
                    positive = positive,
                    negative = negative,
                    successRatePct = vals.takeIf { it.isNotEmpty() }?.let { positive * 100.0 / it.size },
                    averageReturnPct = vals.takeIf { it.isNotEmpty() }?.average(),
                    profitFactor = StrategyComparisonMath.profitFactor(vals),
                    maxDrawdownPct = StrategyComparisonMath.maxDrawdownPct(vals),
                    target1Count = 0, target2Count = 0, stopCount = 0,
                    netPnl = null, netPnlSampleCount = 0,
                    confidenceLabel = StrategyComparisonMath.confidenceLabel(vals.size),
                    sampleSufficient = vals.size >= 20
                )
            }
        }
    }

    private fun readUnsafe(): JSONObject = runCatching {
        if (!file.exists()) JSONObject().put("records", JSONArray()) else JSONObject(file.readText())
    }.getOrElse { JSONObject().put("records", JSONArray()) }

    private fun writeUnsafe(root: JSONObject) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(root.toString().toByteArray(Charsets.UTF_8)); out.flush(); atomic.finishWrite(out)
        } catch (t: Throwable) {
            atomic.failWrite(out); throw t
        }
    }

    companion object {
        private val MUTEX = Mutex()
        private const val HORIZON_MS = 60L * 60L * 1000L
        private const val MATCH_TOLERANCE_MS = 15L * 60L * 1000L
        private const val MAX_LOOKBACK_MS = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_RECORDS = 1500
    }
}
