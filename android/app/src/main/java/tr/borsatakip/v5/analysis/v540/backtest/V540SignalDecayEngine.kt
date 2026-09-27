package tr.borsatakip.v5.analysis.v540.backtest

import tr.borsatakip.v5.model.Candle

/**
 * Point-in-time signal-decay measurement at fixed future-bar closes.
 * This is intentionally NOT the same metric as an executed trade outcome.
 */
object V540SignalDecayEngine {
    val DEFAULT_HORIZONS = listOf(1, 3, 5, 10, 20)

    fun evaluate(
        signals: List<V540BacktestSignal>,
        costs: V540ExecutionCostConfig,
        horizons: List<Int> = DEFAULT_HORIZONS
    ): List<V540SignalDecayPoint> {
        if (costs.validate().isNotEmpty()) return horizons.distinct().sorted().map {
            V540SignalDecayPoint(it, 0, null, null, null, "SIGNAL_DECAY_CLOSE_TO_CLOSE", false)
        }
        return horizons.filter { it > 0 }.distinct().sorted().map { horizon ->
            val gross = signals.mapNotNull { directionalSignalDecayAt(it, horizon) }
            // This is only a hypothetical cost-adjusted decay reference. It is not an executed-trade return.
            val net = gross.map { it - costs.totalCostPct() }
            V540SignalDecayPoint(
                horizonBars = horizon,
                sampleCount = net.size,
                averageGrossReturnPct = gross.takeIf { it.isNotEmpty() }?.average(),
                averageNetReturnPct = net.takeIf { it.isNotEmpty() }?.average(),
                medianNetReturnPct = median(net),
                measurementType = "SIGNAL_DECAY_CLOSE_TO_CLOSE",
                executionComparable = false
            )
        }
    }

    private fun directionalSignalDecayAt(signal: V540BacktestSignal, horizon: Int): Double? {
        if (signal.direction.uppercase() !in setOf("LONG", "SHORT") || signal.entryPrice <= 0.0 || !signal.entryPrice.isFinite()) return null
        val future = sanitizeFuture(signal.futureCandles, signal.signalTimestamp)
        if (future.size < horizon) return null
        val exit = future[horizon - 1].close
        val raw = (exit / signal.entryPrice - 1.0) * 100.0
        return if (signal.direction.equals("SHORT", true)) -raw else raw
    }

    private fun sanitizeFuture(candles: List<Candle>, signalTime: Long): List<Candle> = candles.asSequence()
        .filter { it.timestamp > signalTime }
        .filter { it.timestamp > 0L && it.open > 0.0 && it.high > 0.0 && it.low > 0.0 && it.close > 0.0 && it.volume >= 0.0 }
        .filter { listOf(it.open, it.high, it.low, it.close, it.volume).all(Double::isFinite) }
        .filter { it.low <= it.open && it.open <= it.high && it.low <= it.close && it.close <= it.high }
        .distinctBy { it.timestamp }
        .sortedBy { it.timestamp }
        .toList()

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }
}
