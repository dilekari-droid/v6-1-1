package tr.borsatakip.v5.analysis.v540.backtest

import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.RiskPlan
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Point-in-time backtest utility hardened in V5.4.5.
 *
 * Invariants:
 * - signal.entryPrice is the canonical signal/reference price and must equal RiskPlan.entry;
 * - actual execution price is resolved explicitly by V540EntryExecutionMode;
 * - if actual fill differs, risk geometry is explicitly rebased/revalidated;
 * - stop/target geometry is directionally validated before simulation;
 * - OHLC same-bar ordering is never guessed unless an explicit stress-test policy is selected;
 * - MFE/MAE use actual execution price.
 */
object V540BacktestEngine {
    private const val RR_TOLERANCE = 1e-6

    fun evaluate(
        signal: V540BacktestSignal,
        costs: V540ExecutionCostConfig,
        policy: V540ExecutionPolicy = V540ExecutionPolicy()
    ): V540BacktestTrade = evaluate(signal, V541FixedCostModel(costs), policy)

    fun evaluate(
        signal: V540BacktestSignal,
        costModel: V541ExecutionCostModel,
        policy: V540ExecutionPolicy = V540ExecutionPolicy()
    ): V540BacktestTrade {
        if (policy.validate().isNotEmpty() || !validSignalAndCanonicalPlan(signal, policy.entryTolerancePct)) return invalid(signal)
        val future = sanitizeFuture(signal.futureCandles, signal.signalTimestamp)
        if (future.isEmpty()) return noFillOrOpen(signal, V540BacktestEvent.OPEN, "NO_FUTURE_BARS")

        val fill = resolveEntry(signal, future, policy) ?: return noFillOrOpen(signal, V540BacktestEvent.NO_FILL, "ENTRY_NOT_FILLED")
        val executedPlan = executedRiskPlan(signal, fill.price, policy) ?: return entryRejected(signal, fill, "EXECUTED_RISK_PLAN_INVALID")
        if (!validPlanGeometry(signal.direction, executedPlan)) return entryRejected(signal, fill, "RISK_PLAN_GEOMETRY_INVALID")

        val estimate = costModel.estimate(
            V541CostContext(
                symbol = signal.symbol,
                instrumentType = signal.instrumentType,
                timeframeMinutes = signal.timeframeMinutes,
                executionPrice = fill.price,
                liquidityScore = signal.liquidityScore,
                spreadPct = signal.spreadPct,
                volatilityPct = signal.volatilityPct,
                orderSizeNotional = signal.orderSizeNotional,
                averageDailyNotional = signal.averageDailyNotional
            )
        )
        if (estimate.config.validate().isNotEmpty()) return entryRejected(signal, fill, "COST_MODEL_INVALID")

        val stop = executedPlan.stop ?: return entryRejected(signal, fill, "STOP_MISSING")
        val t1 = executedPlan.target1 ?: return entryRejected(signal, fill, "TARGET1_MISSING")
        val t2 = executedPlan.target2
        val isLong = signal.direction.equals("LONG", true)
        var target1Seen = false
        var mfe = 0.0
        var mae = 0.0
        var observed = 0

        for (index in fill.startIndex until future.size) {
            val c = future[index]
            observed++
            val isLimitFillBar = policy.entryMode == V540EntryExecutionMode.LIMIT_AT_ENTRY && index == fill.startIndex
            val hasIntrabar = intrabarsFor(signal, c).isNotEmpty()

            // A limit can be touched after the bar's earlier extreme. Without intrabar evidence we must
            // not attribute the whole fill-bar excursion to the executed trade.
            if (!isLimitFillBar || hasIntrabar) {
                val excursion = excursionForBar(signal, c, fill.price, isLong, isLimitFillBar)
                if (excursion != null) {
                    mfe = maxOf(mfe, excursion.first)
                    mae = minOf(mae, excursion.second)
                }
            }

            val stopHit = if (isLong) c.low <= stop else c.high >= stop
            val t1Hit = if (isLong) c.high >= t1 else c.low <= t1
            val t2Hit = t2 != null && if (isLong) c.high >= t2 else c.low <= t2
            val anyExitHit = stopHit || t1Hit || t2Hit

            if (isLimitFillBar && anyExitHit && !hasIntrabar) {
                return baseTrade(
                    signal, fill, executedPlan, V540BacktestEvent.AMBIGUOUS, c.timestamp, null,
                    null, null, mfe, mae, observed, estimate, "LIMIT_FILL_BAR_ORDER_UNKNOWN"
                )
            }

            val unresolvedTargetHit = if (!target1Seen) (t1Hit || t2Hit) else t2Hit
            if (stopHit && unresolvedTargetHit) {
                val resolution = resolveSameBarCollision(signal, c, executedPlan, target1Seen, policy)
                when (resolution.kind) {
                    CollisionKind.AMBIGUOUS -> return baseTrade(
                        signal, fill, executedPlan, V540BacktestEvent.AMBIGUOUS, c.timestamp, null,
                        null, null, mfe, mae, observed, estimate, resolution.verification
                    )
                    CollisionKind.STOP_FIRST -> {
                        if (target1Seen && policy.target1ExitFraction < 1.0) {
                            return closedBlended(signal, fill, executedPlan, V540BacktestEvent.TARGET1_THEN_STOP, c.timestamp,
                                t1, policy.target1ExitFraction, stop, 1.0 - policy.target1ExitFraction,
                                mfe, mae, observed, estimate, resolution.verification)
                        }
                        return closed(signal, fill, executedPlan, V540BacktestEvent.STOP, c.timestamp, stop, mfe, mae, observed, estimate, resolution.verification)
                    }
                    CollisionKind.TARGET1_THEN_STOP -> {
                        if (policy.target1ExitFraction >= 1.0) {
                            return closed(signal, fill, executedPlan, V540BacktestEvent.TARGET1, c.timestamp, t1, mfe, mae, observed, estimate, resolution.verification)
                        }
                        return closedBlended(signal, fill, executedPlan, V540BacktestEvent.TARGET1_THEN_STOP, c.timestamp,
                            t1, policy.target1ExitFraction, stop, 1.0 - policy.target1ExitFraction,
                            mfe, mae, observed, estimate, resolution.verification)
                    }
                    CollisionKind.TARGET2_FIRST -> {
                        val exit = t2 ?: t1
                        return if (policy.target1ExitFraction < 1.0 && t2 != null) {
                            closedBlended(signal, fill, executedPlan, V540BacktestEvent.TARGET2, c.timestamp,
                                t1, policy.target1ExitFraction, t2, 1.0 - policy.target1ExitFraction,
                                mfe, mae, observed, estimate, resolution.verification)
                        } else closed(signal, fill, executedPlan, if (t2 != null) V540BacktestEvent.TARGET2 else V540BacktestEvent.TARGET1,
                            c.timestamp, exit, mfe, mae, observed, estimate, resolution.verification)
                    }
                }
            }

            if (stopHit) {
                if (target1Seen && policy.target1ExitFraction < 1.0) {
                    return closedBlended(signal, fill, executedPlan, V540BacktestEvent.TARGET1_THEN_STOP, c.timestamp,
                        t1, policy.target1ExitFraction, stop, 1.0 - policy.target1ExitFraction,
                        mfe, mae, observed, estimate, "OHLC_ORDER_UNAMBIGUOUS")
                }
                return closed(signal, fill, executedPlan, V540BacktestEvent.STOP, c.timestamp, stop, mfe, mae, observed, estimate, "OHLC_ORDER_UNAMBIGUOUS")
            }
            if (t2Hit) {
                return if (target1Seen && policy.target1ExitFraction < 1.0) {
                    closedBlended(signal, fill, executedPlan, V540BacktestEvent.TARGET2, c.timestamp,
                        t1, policy.target1ExitFraction, t2, 1.0 - policy.target1ExitFraction,
                        mfe, mae, observed, estimate, "OHLC_ORDER_UNAMBIGUOUS")
                } else {
                    closed(signal, fill, executedPlan, V540BacktestEvent.TARGET2, c.timestamp, t2, mfe, mae, observed, estimate, "OHLC_ORDER_UNAMBIGUOUS")
                }
            }
            if (t1Hit && !target1Seen) {
                target1Seen = true
                if (policy.target1ExitFraction >= 1.0 || t2 == null || almostEqual(t2, t1)) {
                    return closed(signal, fill, executedPlan, V540BacktestEvent.TARGET1, c.timestamp, t1, mfe, mae, observed, estimate, "OHLC_ORDER_UNAMBIGUOUS")
                }
            }
        }

        if (policy.closeRemainderAtHorizon) {
            val last = future.last()
            if (target1Seen && policy.target1ExitFraction < 1.0) {
                return closedBlended(signal, fill, executedPlan, V540BacktestEvent.TARGET1_THEN_HORIZON, last.timestamp,
                    t1, policy.target1ExitFraction, last.close, 1.0 - policy.target1ExitFraction,
                    mfe, mae, observed, estimate, "HORIZON_POLICY")
            }
            return closed(signal, fill, executedPlan, V540BacktestEvent.HORIZON_EXIT, last.timestamp, last.close, mfe, mae, observed, estimate, "HORIZON_POLICY")
        }
        return baseTrade(signal, fill, executedPlan, V540BacktestEvent.OPEN, null, null, null, null, mfe, mae, observed, estimate, "OPEN_AT_HORIZON")
    }

    fun metrics(trades: List<V540BacktestTrade>): V540BacktestMetrics {
        // Drawdown is path-dependent: enforce chronology internally, never trust caller order.
        val ordered = trades.sortedWith(compareBy<V540BacktestTrade> { it.entryTime }.thenBy { it.signalId })
        val closed = ordered.filter { it.netReturnPct != null && it.event !in setOf(
            V540BacktestEvent.AMBIGUOUS, V540BacktestEvent.OPEN, V540BacktestEvent.NO_FILL,
            V540BacktestEvent.ENTRY_REJECTED, V540BacktestEvent.INVALID
        ) }
        val returns = closed.mapNotNull { it.netReturnPct }
        val positive = returns.filter { it > 0.0 }
        val negative = returns.filter { it < 0.0 }
        val grossProfit = positive.sum()
        val grossLoss = -negative.sum()
        val curve = cumulativeCompounded(returns)
        return V540BacktestMetrics(
            sampleCount = ordered.size,
            closedCount = closed.size,
            ambiguousCount = ordered.count { it.event == V540BacktestEvent.AMBIGUOUS },
            openCount = ordered.count { it.event == V540BacktestEvent.OPEN },
            positiveCount = positive.size,
            negativeCount = negative.size,
            winRatePct = returns.takeIf { it.isNotEmpty() }?.let { positive.size * 100.0 / it.size },
            averageNetReturnPct = returns.takeIf { it.isNotEmpty() }?.average(),
            expectancyPct = returns.takeIf { it.isNotEmpty() }?.average(),
            medianNetReturnPct = median(returns),
            profitFactor = when {
                grossLoss > 0.0 -> grossProfit / grossLoss
                grossProfit > 0.0 -> Double.POSITIVE_INFINITY
                else -> null
            },
            maxDrawdownPct = maxDrawdown(curve),
            sharpePerTrade = sharpe(returns),
            sortinoPerTrade = sortino(returns),
            averageMfePct = closed.mapNotNull { it.mfePct }.takeIf { it.isNotEmpty() }?.average(),
            averageMaePct = closed.mapNotNull { it.maePct }.takeIf { it.isNotEmpty() }?.average(),
            noFillCount = ordered.count { it.event == V540BacktestEvent.NO_FILL },
            invalidCount = ordered.count { it.event == V540BacktestEvent.INVALID },
            entryRejectedCount = ordered.count { it.event == V540BacktestEvent.ENTRY_REJECTED }
        )
    }

    fun scoreBuckets(trades: List<V540BacktestTrade>): List<V540ScoreBucketStats> {
        val bands = listOf(55..59, 60..64, 65..69, 70..74, 75..79, 80..84, 85..89, 90..94, 95..100)
        return bands.map { band ->
            val values = trades.filter { it.signalScore in band }.mapNotNull { it.netReturnPct }
            V540ScoreBucketStats(
                band.first, band.last, values.size,
                values.takeIf { it.isNotEmpty() }?.average(),
                values.takeIf { it.isNotEmpty() }?.average(),
                median(values),
                values.takeIf { it.isNotEmpty() }?.let { xs -> xs.count { it > 0.0 } * 100.0 / xs.size }
            )
        }
    }

    fun metricsByDirection(trades: List<V540BacktestTrade>): Map<String, V540BacktestMetrics> =
        trades.groupBy { it.direction.uppercase() }.mapValues { metrics(it.value) }
    fun metricsByInstrument(trades: List<V540BacktestTrade>): Map<String, V540BacktestMetrics> =
        trades.groupBy { it.instrumentType.uppercase() }.mapValues { metrics(it.value) }
    fun metricsByTimeframe(trades: List<V540BacktestTrade>): Map<Int, V540BacktestMetrics> =
        trades.groupBy { it.timeframeMinutes }.mapValues { metrics(it.value) }
    fun metricsByRegime(trades: List<V540BacktestTrade>): Map<String, V540BacktestMetrics> =
        trades.groupBy { it.marketRegime?.uppercase() ?: "UNKNOWN" }.mapValues { metrics(it.value) }
    fun metricsBySymbol(trades: List<V540BacktestTrade>): Map<String, V540BacktestMetrics> =
        trades.groupBy { it.symbol.uppercase() }.mapValues { metrics(it.value) }

    fun rrBuckets(trades: List<V540BacktestTrade>): List<V541BucketMetrics> {
        val buckets = listOf(
            "RR<1" to { x: Double -> x < 1.0 },
            "RR1.0-1.49" to { x: Double -> x >= 1.0 && x < 1.5 },
            "RR1.5-1.99" to { x: Double -> x >= 1.5 && x < 2.0 },
            "RR2.0-2.99" to { x: Double -> x >= 2.0 && x < 3.0 },
            "RR>=3" to { x: Double -> x >= 3.0 }
        )
        return buckets.map { (label,predicate) -> V541BucketMetrics(label, metrics(trades.filter { it.rr1?.let(predicate) == true })) }
    }

    fun riskBuckets(trades: List<V540BacktestTrade>): List<V541BucketMetrics> =
        listOf(0..19,20..39,40..59,60..79,80..100).map { band ->
            V541BucketMetrics("${band.first}-${band.last}", metrics(trades.filter { it.riskScore in band }))
        }

    private data class EntryFill(val price: Double, val time: Long, val startIndex: Int)
    private enum class CollisionKind { AMBIGUOUS, STOP_FIRST, TARGET1_THEN_STOP, TARGET2_FIRST }
    private data class CollisionResolution(val kind: CollisionKind, val verification: String)

    private fun resolveEntry(signal: V540BacktestSignal, future: List<Candle>, policy: V540ExecutionPolicy): EntryFill? = when (policy.entryMode) {
        V540EntryExecutionMode.SIGNAL_PRICE -> EntryFill(signal.entryPrice, signal.signalTimestamp, 0)
        V540EntryExecutionMode.NEXT_BAR_OPEN -> future.firstOrNull()?.let { EntryFill(it.open, it.timestamp, 0) }
        V540EntryExecutionMode.NEXT_BAR_OPEN_WITH_SLIPPAGE -> future.firstOrNull()?.let {
            val adverse = policy.entrySlippageBpsOneWay / 10_000.0
            val px = if (signal.direction.equals("SHORT", true)) it.open * (1.0 - adverse) else it.open * (1.0 + adverse)
            EntryFill(px, it.timestamp, 0)
        }
        V540EntryExecutionMode.LIMIT_AT_ENTRY -> future.take(policy.maxEntryWaitBars).withIndex().firstOrNull { (_, c) ->
            signal.entryPrice >= c.low && signal.entryPrice <= c.high
        }?.let { EntryFill(signal.entryPrice, it.value.timestamp, it.index) }
    }

    private fun executedRiskPlan(signal: V540BacktestSignal, actualEntry: Double, policy: V540ExecutionPolicy): RiskPlan? {
        val original = signal.riskPlan
        val relativeDiff = abs(actualEntry - signal.entryPrice) / signal.entryPrice
        if (relativeDiff <= policy.entryTolerancePct) return original.copy(entry = actualEntry).recomputeRr(signal.direction)
        return when (policy.riskPlanRebaseMode) {
            V540RiskPlanRebaseMode.REJECT_ON_MISMATCH -> null
            V540RiskPlanRebaseMode.KEEP_ABSOLUTE_LEVELS -> original.copy(entry = actualEntry).recomputeRr(signal.direction)
            V540RiskPlanRebaseMode.REBASE_BY_DELTA -> {
                val d = actualEntry - original.entry
                RiskPlan(
                    entry = actualEntry,
                    stop = original.stop?.plus(d),
                    target1 = original.target1?.plus(d),
                    target2 = original.target2?.plus(d),
                    rr1 = null,
                    rr2 = null
                ).recomputeRr(signal.direction)
            }
        }
    }

    private fun RiskPlan.recomputeRr(direction: String): RiskPlan {
        val s = stop ?: return copy(rr1 = null, rr2 = null)
        val isLong = direction.equals("LONG", true)
        val risk = if (isLong) entry - s else s - entry
        if (!risk.isFinite() || risk <= 0.0) return copy(rr1 = null, rr2 = null)
        fun rr(t: Double?): Double? = t?.let { target ->
            val reward = if (isLong) target - entry else entry - target
            reward.takeIf { it.isFinite() && it > 0.0 }?.div(risk)
        }
        return copy(rr1 = rr(target1), rr2 = rr(target2))
    }

    private fun validSignalAndCanonicalPlan(s: V540BacktestSignal, tolerancePct: Double): Boolean {
        if (s.signalId.isBlank() || s.symbol.isBlank() || s.signalTimestamp <= 0L || s.timeframeMinutes <= 0) return false
        if (s.direction.uppercase() !in setOf("LONG", "SHORT") || s.signalScore !in 0..100 || s.confidence !in 0..100 || s.riskScore !in 0..100 || s.rankingScore !in 0..100) return false
        if (!s.entryPrice.isFinite() || s.entryPrice <= 0.0 || !s.riskPlan.entry.isFinite() || s.riskPlan.entry <= 0.0) return false
        val diff = abs(s.entryPrice - s.riskPlan.entry) / s.entryPrice
        if (diff > tolerancePct) return false
        if (!validPlanGeometry(s.direction, s.riskPlan)) return false
        val recomputed = s.riskPlan.recomputeRr(s.direction)
        if (!sameNullable(recomputed.rr1, s.riskPlan.rr1) || !sameNullable(recomputed.rr2, s.riskPlan.rr2)) return false
        return true
    }

    private fun validPlanGeometry(direction: String, p: RiskPlan): Boolean {
        val stop = p.stop ?: return false
        val t1 = p.target1 ?: return false
        val t2 = p.target2
        if (listOf(p.entry, stop, t1).any { !it.isFinite() || it <= 0.0 }) return false
        if (t2 != null && (!t2.isFinite() || t2 <= 0.0)) return false
        val long = direction.equals("LONG", true)
        val geometry = if (long) {
            stop < p.entry && p.entry < t1 && (t2 == null || t1 <= t2)
        } else {
            p.entry < stop && t1 < p.entry && (t2 == null || t2 <= t1)
        }
        if (!geometry) return false
        val rr1 = p.rr1 ?: return false
        if (!rr1.isFinite() || rr1 <= 0.0) return false
        if (t2 != null) {
            val rr2 = p.rr2 ?: return false
            if (!rr2.isFinite() || rr2 + RR_TOLERANCE < rr1) return false
        }
        return true
    }

    private fun sameNullable(a: Double?, b: Double?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> abs(a - b) <= RR_TOLERANCE * maxOf(1.0, abs(a), abs(b))
    }

    private fun resolveSameBarCollision(
        signal: V540BacktestSignal,
        parent: Candle,
        plan: RiskPlan,
        target1AlreadySeen: Boolean,
        policy: V540ExecutionPolicy
    ): CollisionResolution {
        return when (policy.intrabarResolutionMode) {
            V540IntrabarResolutionMode.OHLC_AMBIGUOUS -> CollisionResolution(CollisionKind.AMBIGUOUS, "OHLC_SAME_BAR_ORDER_UNKNOWN")
            V540IntrabarResolutionMode.CONSERVATIVE_STOP_FIRST -> CollisionResolution(CollisionKind.STOP_FIRST, "CONSERVATIVE_STOP_FIRST_STRESS")
            V540IntrabarResolutionMode.OPTIMISTIC_TARGET_FIRST -> CollisionResolution(CollisionKind.TARGET2_FIRST, "OPTIMISTIC_TARGET_FIRST_STRESS")
            V540IntrabarResolutionMode.USE_INTRABAR_IF_AVAILABLE -> resolveWithIntrabars(signal, parent, plan, target1AlreadySeen)
                ?: CollisionResolution(CollisionKind.AMBIGUOUS, "OHLC_SAME_BAR_ORDER_UNKNOWN_NO_INTRABAR")
        }
    }

    private fun resolveWithIntrabars(signal: V540BacktestSignal, parent: Candle, plan: RiskPlan, target1AlreadySeen: Boolean): CollisionResolution? {
        val bars = intrabarsFor(signal, parent)
        if (bars.isEmpty()) return null
        val long = signal.direction.equals("LONG", true)
        val stop = plan.stop ?: return null
        val t1 = plan.target1 ?: return null
        val t2 = plan.target2
        var t1Seen = target1AlreadySeen
        for (c in bars) {
            val stopHit = if (long) c.low <= stop else c.high >= stop
            val t1Hit = if (long) c.high >= t1 else c.low <= t1
            val t2Hit = t2 != null && if (long) c.high >= t2 else c.low <= t2
            val targetHit = if (!t1Seen) (t1Hit || t2Hit) else t2Hit
            if (stopHit && targetHit) return CollisionResolution(CollisionKind.AMBIGUOUS, "INTRABAR_SUBBAR_COLLISION")
            if (stopHit) return CollisionResolution(if (t1Seen) CollisionKind.TARGET1_THEN_STOP else CollisionKind.STOP_FIRST, "INTRABAR_ORDER_RESOLVED")
            if (t2Hit) return CollisionResolution(CollisionKind.TARGET2_FIRST, "INTRABAR_ORDER_RESOLVED")
            if (t1Hit && !t1Seen) t1Seen = true
        }
        return null
    }

    private fun intrabarsFor(signal: V540BacktestSignal, parent: Candle): List<Candle> {
        if (signal.intrabarCandles.isEmpty()) return emptyList()
        val end = parent.timestamp + signal.timeframeMinutes * 60_000L
        return sanitizeFuture(signal.intrabarCandles, parent.timestamp - 1L)
            .filter { it.timestamp >= parent.timestamp && it.timestamp < end }
    }

    private fun excursionForBar(signal: V540BacktestSignal, c: Candle, entry: Double, isLong: Boolean, limitFillBar: Boolean): Pair<Double, Double>? {
        if (limitFillBar) {
            val sub = intrabarsFor(signal, c)
            val fillIndex = sub.indexOfFirst { entry >= it.low && entry <= it.high }
            if (fillIndex < 0) return null
            var mfe = 0.0
            var mae = 0.0
            for (b in sub.drop(fillIndex)) {
                val fav = if (isLong) (b.high / entry - 1.0) * 100.0 else (entry / b.low - 1.0) * 100.0
                val adv = if (isLong) (b.low / entry - 1.0) * 100.0 else -((b.high / entry - 1.0) * 100.0)
                mfe = maxOf(mfe, fav); mae = minOf(mae, adv)
            }
            return mfe to mae
        }
        val favorable = if (isLong) (c.high / entry - 1.0) * 100.0 else (entry / c.low - 1.0) * 100.0
        val adverse = if (isLong) (c.low / entry - 1.0) * 100.0 else -((c.high / entry - 1.0) * 100.0)
        return favorable to adverse
    }

    private fun closedBlended(
        signal: V540BacktestSignal, fill: EntryFill, plan: RiskPlan, event: V540BacktestEvent, exitTime: Long,
        firstExitPrice: Double, firstFraction: Double, secondExitPrice: Double, secondFraction: Double,
        mfe: Double, mae: Double, bars: Int, estimate: V541CostEstimate, verification: String
    ): V540BacktestTrade {
        val gross = directionalReturn(signal.direction, fill.price, firstExitPrice) * firstFraction +
            directionalReturn(signal.direction, fill.price, secondExitPrice) * secondFraction
        val net = gross - estimate.config.totalCostPct()
        val weightedExit = firstExitPrice * firstFraction + secondExitPrice * secondFraction
        return baseTrade(signal, fill, plan, event, exitTime, weightedExit, gross, net, mfe, mae, bars, estimate, verification)
    }

    private fun closed(
        signal: V540BacktestSignal, fill: EntryFill, plan: RiskPlan, event: V540BacktestEvent, exitTime: Long,
        exitPrice: Double, mfe: Double, mae: Double, bars: Int, estimate: V541CostEstimate, verification: String
    ): V540BacktestTrade {
        val gross = directionalReturn(signal.direction, fill.price, exitPrice)
        val net = gross - estimate.config.totalCostPct()
        return baseTrade(signal, fill, plan, event, exitTime, exitPrice, gross, net, mfe, mae, bars, estimate, verification)
    }

    private fun baseTrade(
        s: V540BacktestSignal, fill: EntryFill, plan: RiskPlan?, event: V540BacktestEvent, exitTime: Long?, exitPrice: Double?,
        gross: Double?, net: Double?, mfe: Double?, mae: Double?, bars: Int, estimate: V541CostEstimate?, verification: String
    ) = V540BacktestTrade(
        signalId = s.signalId, symbol = s.symbol, direction = s.direction.uppercase(), event = event,
        entryTime = fill.time, exitTime = exitTime, entryPrice = fill.price, exitPrice = exitPrice,
        grossReturnPct = gross, netReturnPct = net, mfePct = mfe, maePct = mae, barsObserved = bars,
        signalScore = s.signalScore, confidence = s.confidence, riskScore = s.riskScore, rankingScore = s.rankingScore,
        timeframeMinutes = s.timeframeMinutes, instrumentType = s.instrumentType, marketRegime = s.marketRegime,
        verification = verification, signalTimestamp = s.signalTimestamp, signalReferencePrice = s.entryPrice,
        executedRiskPlan = plan, costModelId = estimate?.modelId ?: "NONE", costEvidence = estimate?.evidence ?: "NONE",
        rr1 = plan?.rr1, rr2 = plan?.rr2
    )

    private fun noFillOrOpen(s: V540BacktestSignal, event: V540BacktestEvent, verification: String): V540BacktestTrade =
        baseTrade(s, EntryFill(s.entryPrice, s.signalTimestamp, 0), s.riskPlan, event, null, null, null, null, null, null, 0, null, verification)

    private fun entryRejected(s: V540BacktestSignal, fill: EntryFill, verification: String): V540BacktestTrade =
        baseTrade(s, fill, null, V540BacktestEvent.ENTRY_REJECTED, null, null, null, null, null, null, 0, null, verification)

    private fun invalid(s: V540BacktestSignal): V540BacktestTrade =
        baseTrade(s, EntryFill(s.entryPrice, s.signalTimestamp, 0), null, V540BacktestEvent.INVALID, null, null, null, null, null, null, 0, null, "SIGNAL_OR_RISK_PLAN_INVALID")

    private fun sanitizeFuture(candles: List<Candle>, signalTime: Long): List<Candle> = candles.asSequence()
        .filter { it.timestamp > signalTime }
        .filter { it.timestamp > 0L && it.open > 0.0 && it.high > 0.0 && it.low > 0.0 && it.close > 0.0 && it.volume >= 0.0 }
        .filter { listOf(it.open, it.high, it.low, it.close, it.volume).all(Double::isFinite) }
        .filter { it.low <= it.open && it.open <= it.high && it.low <= it.close && it.close <= it.high }
        .distinctBy { it.timestamp }
        .sortedBy { it.timestamp }
        .toList()

    private fun directionalReturn(direction: String, entry: Double, exit: Double): Double {
        val raw = (exit / entry - 1.0) * 100.0
        return if (direction.equals("SHORT", true)) -raw else raw
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun sharpe(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        val variance = values.sumOf { (it - mean).pow(2) } / (values.size - 1)
        val sd = sqrt(variance)
        return if (sd > 0.0) mean / sd else null
    }

    private fun sortino(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        val downside = values.filter { it < 0.0 }
        if (downside.isEmpty()) return if (mean > 0.0) Double.POSITIVE_INFINITY else null
        val downsideDeviation = sqrt(downside.sumOf { it.pow(2) } / downside.size)
        return if (downsideDeviation > 0.0) mean / downsideDeviation else null
    }

    private fun cumulativeCompounded(values: List<Double>): List<Double> {
        var equity = 100.0
        return values.map { r -> equity *= (1.0 + r / 100.0); equity }
    }

    private fun maxDrawdown(curve: List<Double>): Double? {
        if (curve.isEmpty()) return null
        var peak = curve.first(); var maxDd = 0.0
        for (v in curve) { if (v > peak) peak = v; if (peak > 0.0) maxDd = maxOf(maxDd, (peak - v) / peak * 100.0) }
        return maxDd
    }

    private fun almostEqual(a: Double, b: Double): Boolean = abs(a - b) <= 1e-9 * maxOf(1.0, abs(a), abs(b))
}
