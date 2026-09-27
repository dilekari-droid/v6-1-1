package tr.borsatakip.v5.analysis

import kotlin.math.roundToInt

/**
 * V5.1.48 controlled ensemble / dynamic weight optimizer.
 *
 * Uses only VERIFIED_HISTORICAL performance summaries supplied by the caller. It does not
 * inspect future candles directly and it never changes weights when the minimum sample gate
 * is not satisfied. Weight movement is deliberately bounded to reduce over-fitting.
 */
object StrategyOptimizationEngine {
    data class Performance(
        val strategyId: StrategyProfileEngine.StrategyId,
        val totalVerified: Int,
        val successRatePct: Double?,
        val averageReturnPct: Double?,
        val profitFactor: Double?,
        val maxDrawdownPct: Double?
    )

    data class WeightResult(
        val eligible: Boolean,
        val weights: Map<StrategyProfileEngine.StrategyId, Double>,
        val reason: String,
        val eligibleStrategyCount: Int,
        val minimumVerifiedSamples: Int
    )

    const val MIN_SAMPLES_PER_STRATEGY = 20
    const val MIN_ELIGIBLE_STRATEGIES = 3
    const val MAX_WEIGHT = 0.50
    const val MIN_WEIGHT = 0.05
    const val MAX_STEP = 0.05
    const val MAX_RANKING_ADJUSTMENT = 8

    val BASE_WEIGHTS: Map<StrategyProfileEngine.StrategyId, Double> = linkedMapOf(
        StrategyProfileEngine.StrategyId.TREND to 0.14,
        StrategyProfileEngine.StrategyId.MOMENTUM to 0.14,
        StrategyProfileEngine.StrategyId.BREAKOUT to 0.14,
        StrategyProfileEngine.StrategyId.MEAN_REVERSION to 0.14,
        StrategyProfileEngine.StrategyId.MTF_REGIME to 0.14,
        StrategyProfileEngine.StrategyId.COMBINED to 0.30
    )

    fun optimize(
        performance: List<Performance>,
        previousWeights: Map<StrategyProfileEngine.StrategyId, Double>? = null
    ): WeightResult {
        val byId = performance.associateBy { it.strategyId }
        val eligible = StrategyProfileEngine.StrategyId.entries.filter { id ->
            (byId[id]?.totalVerified ?: 0) >= MIN_SAMPLES_PER_STRATEGY
        }
        val combinedCount = byId[StrategyProfileEngine.StrategyId.COMBINED]?.totalVerified ?: 0
        val minimum = eligible.minOfOrNull { byId[it]?.totalVerified ?: 0 } ?: 0

        if (combinedCount < MIN_SAMPLES_PER_STRATEGY || eligible.size < MIN_ELIGIBLE_STRATEGIES) {
            return WeightResult(
                eligible = false,
                weights = BASE_WEIGHTS,
                reason = "YETERSİZ DOĞRULANMIŞ FORWARD VERİ: Combined=$combinedCount, uygun strateji=${eligible.size}/$MIN_ELIGIBLE_STRATEGIES",
                eligibleStrategyCount = eligible.size,
                minimumVerifiedSamples = minimum
            )
        }

        val raw = linkedMapOf<StrategyProfileEngine.StrategyId, Double>()
        StrategyProfileEngine.StrategyId.entries.forEach { id ->
            val p = byId[id]
            raw[id] = if (p == null || p.totalVerified < MIN_SAMPLES_PER_STRATEGY) {
                20.0
            } else {
                quality(p)
            }
        }
        val target = normalizeBounded(raw)
        val previous = sanitizeWeights(previousWeights ?: BASE_WEIGHTS)
        val finalWeights = normalizeWithinStep(target, previous)
        return WeightResult(
            eligible = true,
            weights = finalWeights,
            reason = "DOĞRULANMIŞ VERİYLE KONTROLLÜ OPTİMİZASYON • tek adım ±${(MAX_STEP * 100).roundToInt()} puan • tavan %${(MAX_WEIGHT * 100).roundToInt()}",
            eligibleStrategyCount = eligible.size,
            minimumVerifiedSamples = minimum
        )
    }

    fun ensembleScore(
        scores: List<StrategyProfileEngine.Score>,
        weights: Map<StrategyProfileEngine.StrategyId, Double>
    ): Int {
        if (scores.isEmpty()) return 0
        val byId = scores.associateBy { it.id }
        var weighted = 0.0
        var totalWeight = 0.0
        StrategyProfileEngine.StrategyId.entries.forEach { id ->
            val score = byId[id]?.score ?: return@forEach
            val w = weights[id] ?: return@forEach
            weighted += score * w
            totalWeight += w
        }
        return if (totalWeight <= 0.0) 0 else (weighted / totalWeight).roundToInt().coerceIn(0, 100)
    }

    /** Keeps the ensemble influential but prevents one optimization cycle from dominating ranking. */
    fun adjustedRanking(baseRanking: Int, ensembleScore: Int): Int {
        val delta = ((ensembleScore - baseRanking) * 0.35).roundToInt()
            .coerceIn(-MAX_RANKING_ADJUSTMENT, MAX_RANKING_ADJUSTMENT)
        return (baseRanking + delta).coerceIn(0, 100)
    }

    fun label(weights: Map<StrategyProfileEngine.StrategyId, Double>): String =
        StrategyProfileEngine.StrategyId.entries.joinToString(" • ") { id ->
            "${id.label} %${((weights[id] ?: 0.0) * 100.0).roundToInt()}"
        }

    private fun quality(p: Performance): Double {
        val success = (p.successRatePct ?: 50.0).coerceIn(0.0, 100.0)
        val pf = when (val v = p.profitFactor) {
            null -> 50.0
            Double.POSITIVE_INFINITY -> 100.0
            else -> (v.coerceIn(0.0, 3.0) / 3.0) * 100.0
        }
        val avgReturn = (((p.averageReturnPct ?: 0.0).coerceIn(-2.0, 2.0) + 2.0) / 4.0) * 100.0
        val drawdown = p.maxDrawdownPct?.coerceAtLeast(0.0) ?: 10.0
        val drawdownScore = (100.0 - (drawdown * 4.0)).coerceIn(0.0, 100.0)
        val sampleScore = (p.totalVerified.coerceAtMost(100) / 100.0) * 100.0
        return success * 0.30 + pf * 0.25 + avgReturn * 0.15 + drawdownScore * 0.20 + sampleScore * 0.10
    }

    private fun sanitizeWeights(input: Map<StrategyProfileEngine.StrategyId, Double>): Map<StrategyProfileEngine.StrategyId, Double> {
        val positive = linkedMapOf<StrategyProfileEngine.StrategyId, Double>()
        StrategyProfileEngine.StrategyId.entries.forEach { id -> positive[id] = (input[id] ?: BASE_WEIGHTS.getValue(id)).coerceAtLeast(0.0) }
        return normalizeBounded(positive)
    }

    private fun normalizeWithinStep(
        target: Map<StrategyProfileEngine.StrategyId, Double>,
        previous: Map<StrategyProfileEngine.StrategyId, Double>
    ): Map<StrategyProfileEngine.StrategyId, Double> {
        val ids = StrategyProfileEngine.StrategyId.entries
        val lower = ids.associateWith { id -> maxOf(MIN_WEIGHT, previous.getValue(id) - MAX_STEP) }
        val upper = ids.associateWith { id -> minOf(MAX_WEIGHT, previous.getValue(id) + MAX_STEP) }
        val weights = ids.associateWith { id ->
            (target[id] ?: previous.getValue(id)).coerceIn(lower.getValue(id), upper.getValue(id))
        }.toMutableMap()

        // Previous vector sums to 1, therefore these per-id step bounds always contain
        // at least one feasible normalized solution. Fill/trim only through available slack;
        // never perform a final global divide that can violate ±MAX_STEP.
        repeat(32) {
            val diff = 1.0 - weights.values.sum()
            if (kotlin.math.abs(diff) <= 1e-12) return@repeat
            val adjustable = ids.filter { id ->
                if (diff > 0.0) weights.getValue(id) < upper.getValue(id) - 1e-12
                else weights.getValue(id) > lower.getValue(id) + 1e-12
            }
            if (adjustable.isEmpty()) return@repeat
            var remaining = diff
            for ((offset, id) in adjustable.withIndex()) {
                val slots = (adjustable.size - offset).coerceAtLeast(1)
                val share = remaining / slots
                val before = weights.getValue(id)
                val after = (before + share).coerceIn(lower.getValue(id), upper.getValue(id))
                weights[id] = after
                remaining -= after - before
            }
        }
        val total = weights.values.sum()
        check(kotlin.math.abs(total - 1.0) <= 1e-9) { "Bounded strategy weights cannot be normalized: total=$total" }
        ids.forEach { id ->
            val delta = kotlin.math.abs(weights.getValue(id) - previous.getValue(id))
            check(delta <= MAX_STEP + 1e-9) { "Strategy weight step exceeded for $id: delta=$delta" }
        }
        return ids.associateWith { weights.getValue(it) }
    }

    private fun normalizeBounded(raw: Map<StrategyProfileEngine.StrategyId, Double>): Map<StrategyProfileEngine.StrategyId, Double> {
        val ids = StrategyProfileEngine.StrategyId.entries
        val positive = ids.associateWith { (raw[it] ?: 0.0).coerceAtLeast(0.0) }
        val sum = positive.values.sum().takeIf { it > 0.0 } ?: 1.0
        val weights = ids.associateWith { (positive.getValue(it) / sum).coerceIn(MIN_WEIGHT, MAX_WEIGHT) }.toMutableMap()

        // Small iterative water-filling to keep the vector normalized after min/max clamping.
        repeat(12) {
            val total = weights.values.sum()
            val diff = 1.0 - total
            if (kotlin.math.abs(diff) < 1e-9) return@repeat
            val adjustable = ids.filter { id ->
                if (diff > 0) weights.getValue(id) < MAX_WEIGHT - 1e-9 else weights.getValue(id) > MIN_WEIGHT + 1e-9
            }
            if (adjustable.isEmpty()) return@repeat
            val share = diff / adjustable.size
            adjustable.forEach { id -> weights[id] = (weights.getValue(id) + share).coerceIn(MIN_WEIGHT, MAX_WEIGHT) }
        }
        val finalSum = weights.values.sum().takeIf { it > 0.0 } ?: 1.0
        // Rounding is intentionally not applied; normalize one last time while bounds are already stable.
        return ids.associateWith { weights.getValue(it) / finalSum }
    }
}
