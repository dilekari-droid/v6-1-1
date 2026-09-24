package tr.borsatakip.v5.data

import android.content.Context
import tr.borsatakip.v5.analysis.StrategyOptimizationEngine
import tr.borsatakip.v5.analysis.StrategyProfileEngine
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.scan.HistoryRecordResult
import tr.borsatakip.v5.scan.HistoryRecorder
import tr.borsatakip.v5.scan.ScanState
import tr.borsatakip.v5.scan.ScanStatus

/** Merkezi tarama -> history + kontrollü ensemble köprüsü. */
class SignalHistoryRecorder(context: Context) : HistoryRecorder {
    private val store = SignalHistoryStore(context.applicationContext)
    private val weightStore = StrategyWeightStore(context.applicationContext)

    override suspend fun applyStrategyOptimization(items: List<Opportunity>): List<Opportunity> {
        if (items.isEmpty()) return items
        val cache = mutableMapOf<String, StrategyOptimizationEngine.WeightResult>()
        return items.map { opportunity ->
            if (opportunity.rankingStatus != tr.borsatakip.v5.model.RankingStatus.CALCULATED) return@map opportunity
            val regime = opportunity.marketRegime.ifBlank { "VERİ YOK" }
            val cacheKey = "${opportunity.direction}|$regime"
            val result = cache.getOrPut(cacheKey) {
                val stats = store.strategyPerformance(
                    horizon = "1h",
                    direction = opportunity.direction,
                    regime = regime,
                    limit = 100
                )
                val performance = stats.mapNotNull { s ->
                    val id = runCatching { StrategyProfileEngine.StrategyId.valueOf(s.strategyId) }.getOrNull() ?: return@mapNotNull null
                    StrategyOptimizationEngine.Performance(
                        strategyId = id,
                        totalVerified = s.totalVerified,
                        successRatePct = s.successRatePct,
                        averageReturnPct = s.averageReturnPct,
                        profitFactor = s.profitFactor,
                        maxDrawdownPct = s.maxDrawdownPct
                    )
                }
                StrategyOptimizationEngine.optimize(
                    performance,
                    weightStore.load("BIST", opportunity.direction, regime)
                ).also { optimized ->
                    if (optimized.eligible) weightStore.save("BIST", opportunity.direction, regime, optimized.weights)
                }
            }
            if (!result.eligible) {
                opportunity.copy(
                    ensembleScore = opportunity.rankingScore,
                    ensembleStatus = "FALLBACK_COMBINED • ${result.reason}",
                    strategyWeightsLabel = "Combined fallback • minimum ${StrategyOptimizationEngine.MIN_SAMPLES_PER_STRATEGY} doğrulanmış örnek"
                )
            } else {
                val scores = StrategyProfileEngine.evaluate(opportunity)
                val ensemble = StrategyOptimizationEngine.ensembleScore(scores, result.weights)
                val adjusted = StrategyOptimizationEngine.adjustedRanking(opportunity.rankingScore, ensemble)
                opportunity.copy(
                    rankingScore = adjusted,
                    ensembleScore = ensemble,
                    ensembleStatus = "DYNAMIC_ENSEMBLE • ${result.reason}",
                    strategyWeightsLabel = StrategyOptimizationEngine.label(result.weights)
                )
            }
        }
    }

    override suspend fun record(state: ScanState): HistoryRecordResult {
        val run = state.scanRun ?: return HistoryRecordResult()
        val eligible = state.status == ScanStatus.COMPLETED &&
            state.successful > 0 &&
            (run.status == ScanRunStatus.COMPLETE || run.status == ScanRunStatus.PARTIAL)
        if (!eligible) return HistoryRecordResult()

        return try {
            HistoryRecordResult(inserted = store.recordScan(run, state.results))
        } catch (t: Throwable) {
            HistoryRecordResult(errorMessage = t.message ?: "Sinyal geçmişi kaydı başarısız")
        }
    }
}
