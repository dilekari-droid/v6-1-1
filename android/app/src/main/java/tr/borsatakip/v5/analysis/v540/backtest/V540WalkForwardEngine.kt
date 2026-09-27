package tr.borsatakip.v5.analysis.v540.backtest

import kotlin.random.Random

/**
 * Walk-forward utilities. The optimizer never reads OOS data while selecting a model.
 */
object V540WalkForwardEngine {
    fun evaluate(
        trades: List<V540BacktestTrade>,
        trainMs: Long,
        validationMs: Long,
        testMs: Long,
        stepMs: Long = testMs
    ): List<V540WalkForwardWindow> {
        require(trainMs > 0 && validationMs > 0 && testMs > 0 && stepMs > 0)
        val ordered = trades.filter { it.entryTime > 0L }.sortedBy { it.entryTime }
        if (ordered.isEmpty()) return emptyList()
        val first = ordered.first().entryTime
        val last = ordered.last().entryTime
        val out = mutableListOf<V540WalkForwardWindow>()
        var start = first
        while (start + trainMs + validationMs + testMs <= last + 1L) {
            val trainEnd = start + trainMs
            val validationEnd = trainEnd + validationMs
            val testEnd = validationEnd + testMs
            fun slice(a: Long, b: Long) = ordered.filter { it.entryTime >= a && it.entryTime < b }
            out += V540WalkForwardWindow(
                start, trainEnd, validationEnd, testEnd,
                V540BacktestEngine.metrics(slice(start, trainEnd)),
                V540BacktestEngine.metrics(slice(trainEnd, validationEnd)),
                V540BacktestEngine.metrics(slice(validationEnd, testEnd))
            )
            start += stepMs
        }
        return out
    }

    /**
     * Train-only model selection, validation gate, frozen OOS evaluation.
     * Changing OOS returns cannot change selectedModelId.
     */
    fun optimizeAndEvaluate(
        candidates: List<V541ModelCandidate>,
        trainMs: Long,
        validationMs: Long,
        testMs: Long,
        stepMs: Long = testMs,
        minTrainClosed: Int = 20,
        minValidationClosed: Int = 10
    ): List<V541WalkForwardSelection> {
        require(trainMs > 0 && validationMs > 0 && testMs > 0 && stepMs > 0)
        require(minTrainClosed > 0 && minValidationClosed > 0)
        val allTimes = candidates.flatMap { it.trades }.map { it.entryTime }.filter { it > 0L }.sorted()
        if (allTimes.isEmpty()) return emptyList()
        val out = mutableListOf<V541WalkForwardSelection>()
        var start = allTimes.first()
        val last = allTimes.last()
        while (start + trainMs + validationMs + testMs <= last + 1L) {
            val trainEnd = start + trainMs
            val validationEnd = trainEnd + validationMs
            val testEnd = validationEnd + testMs
            fun V541ModelCandidate.slice(a: Long, b: Long) = trades.filter { it.entryTime >= a && it.entryTime < b }
            val eligible = candidates.map { c -> c to V540BacktestEngine.metrics(c.slice(start, trainEnd)) }
                .filter { it.second.closedCount >= minTrainClosed }
            val selected = eligible.maxWithOrNull(
                compareBy<Pair<V541ModelCandidate, V540BacktestMetrics>> { it.second.expectancyPct ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.second.closedCount }
                    .thenBy { it.first.modelId }
            )
            if (selected == null) {
                out += V541WalkForwardSelection(start, trainEnd, validationEnd, testEnd, null,
                    V540BacktestEngine.metrics(emptyList()), V540BacktestEngine.metrics(emptyList()), V540BacktestEngine.metrics(emptyList()),
                    V541ValidationStatus.INSUFFICIENT_EVIDENCE, "NO_TRAIN_CANDIDATE_WITH_MIN_SAMPLE")
            } else {
                val candidate = selected.first
                val train = selected.second
                val validation = V540BacktestEngine.metrics(candidate.slice(trainEnd, validationEnd))
                val oos = V540BacktestEngine.metrics(candidate.slice(validationEnd, testEnd))
                val status = if (validation.closedCount >= minValidationClosed) V541ValidationStatus.VALIDATED else V541ValidationStatus.INSUFFICIENT_EVIDENCE
                val reason = if (status == V541ValidationStatus.VALIDATED) "MODEL_FROZEN_BEFORE_OOS" else "VALIDATION_SAMPLE_TOO_SMALL"
                out += V541WalkForwardSelection(start, trainEnd, validationEnd, testEnd, candidate.modelId, train, validation, oos, status, reason)
            }
            start += stepMs
        }
        return out
    }

    fun monteCarlo(
        trades: List<V540BacktestTrade>,
        iterations: Int = 1_000,
        sampleSize: Int = trades.size,
        seed: Int = 540
    ): V540MonteCarloSummary = monteCarloBlock(trades, V541BootstrapMode.TRADE_IID, iterations, sampleSize, seed)

    /** Cluster-aware bootstrap for correlated signals. */
    fun monteCarloBlock(
        trades: List<V540BacktestTrade>,
        mode: V541BootstrapMode,
        iterations: Int = 1_000,
        sampleSize: Int = trades.size,
        seed: Int = 541
    ): V540MonteCarloSummary {
        val closed = trades.filter { it.netReturnPct != null }.sortedBy { it.entryTime }
        if (closed.isEmpty() || iterations <= 0 || sampleSize <= 0) return V540MonteCarloSummary(0, 0, null, null, null, mode.name)
        val clusters: List<List<V540BacktestTrade>> = when (mode) {
            V541BootstrapMode.TRADE_IID -> closed.map { listOf(it) }
            V541BootstrapMode.DAY_BLOCK -> closed.groupBy { it.entryTime / 86_400_000L }.values.toList()
            V541BootstrapMode.SESSION_BLOCK -> closed.groupBy { (it.entryTime / (4L * 60L * 60L * 1000L)) }.values.toList()
            V541BootstrapMode.SYMBOL_BLOCK -> closed.groupBy { it.symbol.uppercase() }.values.toList()
            V541BootstrapMode.REGIME_BLOCK -> closed.groupBy { it.marketRegime?.uppercase() ?: "UNKNOWN" }.values.toList()
        }.filter { it.isNotEmpty() }
        if (clusters.isEmpty()) return V540MonteCarloSummary(0, 0, null, null, null, mode.name)
        val rng = Random(seed)
        val totals = MutableList(iterations) {
            val sampled = mutableListOf<Double>()
            while (sampled.size < sampleSize) {
                val cluster = clusters[rng.nextInt(clusters.size)]
                sampled += cluster.mapNotNull { it.netReturnPct }
            }
            var equity = 100.0
            for (r in sampled.take(sampleSize)) equity *= (1.0 + r / 100.0)
            (equity / 100.0 - 1.0) * 100.0
        }.sorted()
        fun pct(p: Double): Double = totals[((totals.lastIndex * p).toInt()).coerceIn(0, totals.lastIndex)]
        return V540MonteCarloSummary(iterations, sampleSize, pct(0.50), pct(0.05), pct(0.95), mode.name)
    }
}

object V541EvidenceGate {
    fun assess(
        trades: List<V540BacktestTrade>,
        windows: List<V541WalkForwardSelection>,
        pointInTimeDataVerified: Boolean,
        observedCostInputsVerified: Boolean,
        requirements: V541EvidenceRequirements = V541EvidenceRequirements()
    ): V541EvidenceReport {
        val metrics = V540BacktestEngine.metrics(trades)
        val validatedOos = windows.count { it.status == V541ValidationStatus.VALIDATED && it.outOfSample.closedCount > 0 }
        val reasons = buildList {
            if (metrics.closedCount < requirements.minClosedTrades) add("CLOSED_SAMPLE_BELOW_MIN")
            if (validatedOos < requirements.minOosWindows) add("OOS_WINDOWS_BELOW_MIN")
            if (requirements.requirePointInTimeData && !pointInTimeDataVerified) add("POINT_IN_TIME_DATA_NOT_VERIFIED")
            if (requirements.requireObservedCostInputs && !observedCostInputsVerified) add("OBSERVED_COST_INPUTS_NOT_VERIFIED")
        }
        val status = if (reasons.isEmpty()) V541ValidationStatus.VALIDATED else V541ValidationStatus.INSUFFICIENT_EVIDENCE
        return V541EvidenceReport(status, metrics.closedCount, validatedOos, pointInTimeDataVerified, observedCostInputsVerified, reasons)
    }

    fun scoreCalibration(
        trades: List<V540BacktestTrade>,
        minSamplesPerBucket: Int = 30,
        minQualifyingBuckets: Int = 3
    ): V541ScoreCalibrationReport {
        val buckets = V540BacktestEngine.scoreBuckets(trades)
        val qualifying = buckets.count { it.sampleCount >= minSamplesPerBucket }
        val status = if (qualifying >= minQualifyingBuckets) V541ValidationStatus.VALIDATED else V541ValidationStatus.INSUFFICIENT_EVIDENCE
        return V541ScoreCalibrationReport(status, qualifying, minSamplesPerBucket, buckets)
    }
}
