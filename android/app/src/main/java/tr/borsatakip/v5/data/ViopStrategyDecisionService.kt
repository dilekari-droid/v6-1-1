package tr.borsatakip.v5.data

import android.content.Context
import tr.borsatakip.v5.analysis.StrategyOptimizationEngine
import tr.borsatakip.v5.analysis.StrategyProfileEngine
import tr.borsatakip.v5.model.ViopOpportunity

/** VİOP parity layer for V5.1.48: verified 1h outcomes -> bounded dynamic ensemble. */
class ViopStrategyDecisionService(context: Context, private val backend: BackendProvider) {
    private val history = ViopStrategyHistoryStore(context.applicationContext)
    private val weights = StrategyWeightStore(context.applicationContext)

    suspend fun refreshOutcomes() {
        runCatching { history.updateDueOutcomes(backend) }
    }

    suspend fun apply(items: List<ViopOpportunity>): List<ViopOpportunity> {
        if (items.isEmpty()) return items
        val cache = mutableMapOf<String, StrategyOptimizationEngine.WeightResult>()
        return items.map { v ->
            if (v.rankingStatus != tr.borsatakip.v5.model.RankingStatus.CALCULATED) return@map v
            val regime = v.marketRegime.ifBlank { "VERİ YOK" }
            val key = "${v.direction}|$regime"
            val optimized = cache.getOrPut(key) {
                val perf = history.performance(v.direction, regime, 100).mapNotNull { s ->
                    val id = runCatching { StrategyProfileEngine.StrategyId.valueOf(s.strategyId) }.getOrNull() ?: return@mapNotNull null
                    StrategyOptimizationEngine.Performance(id, s.totalVerified, s.successRatePct, s.averageReturnPct, s.profitFactor, s.maxDrawdownPct)
                }
                StrategyOptimizationEngine.optimize(perf, weights.load("VIOP", v.direction, regime)).also { r ->
                    if (r.eligible) weights.save("VIOP", v.direction, regime, r.weights)
                }
            }
            if (!optimized.eligible) {
                v.copy(
                    ensembleScore = v.rankingScore,
                    ensembleStatus = "FALLBACK_COMBINED • ${optimized.reason}",
                    strategyWeightsLabel = "VİOP Combined fallback"
                )
            } else {
                val score = StrategyOptimizationEngine.ensembleScore(StrategyProfileEngine.evaluate(v), optimized.weights)
                v.copy(
                    rankingScore = StrategyOptimizationEngine.adjustedRanking(v.rankingScore, score),
                    ensembleScore = score,
                    ensembleStatus = "DYNAMIC_ENSEMBLE • ${optimized.reason}",
                    strategyWeightsLabel = StrategyOptimizationEngine.label(optimized.weights)
                )
            }
        }
    }

    suspend fun record(items: List<ViopOpportunity>) {
        runCatching { history.record(items) }
    }
}
