package tr.borsatakip.v5.analysis.v540.backtest

import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.RiskPlan

data class V540BacktestSignal(
    val signalId: String,
    val symbol: String,
    val instrumentType: String,
    val timeframeMinutes: Int,
    val signalTimestamp: Long,
    val direction: String,
    val signalScore: Int,
    val confidence: Int,
    val riskScore: Int,
    val rankingScore: Int,
    /** Canonical signal/reference price. RiskPlan.entry must match this before execution. */
    val entryPrice: Double,
    val riskPlan: RiskPlan,
    val mtfScore: Int? = null,
    val marketRegime: String? = null,
    val futureCandles: List<Candle>,
    /** Optional execution context for adaptive costs. */
    val liquidityScore: Int? = null,
    val spreadPct: Double? = null,
    val volatilityPct: Double? = null,
    val orderSizeNotional: Double? = null,
    val averageDailyNotional: Double? = null,
    /** Optional lower-timeframe bars used only to resolve same-parent-bar hit ordering. */
    val intrabarCandles: List<Candle> = emptyList()
)

enum class V540EntryExecutionMode {
    /** Explicit close/signal-price assumption. No claim is made that this was executable after publication. */
    SIGNAL_PRICE,
    /** First future bar open. */
    NEXT_BAR_OPEN,
    /** Fill only if a future bar trades through the canonical signal price. */
    LIMIT_AT_ENTRY,
    /** First future bar open with adverse entry slippage from the execution policy. */
    NEXT_BAR_OPEN_WITH_SLIPPAGE
}

enum class V540RiskPlanRebaseMode {
    /** Reject the trade if actual execution price differs beyond tolerance. */
    REJECT_ON_MISMATCH,
    /** Shift stop/targets by the entry delta, preserving absolute risk/reward distances. */
    REBASE_BY_DELTA,
    /** Keep absolute stop/targets, but require them to remain geometrically valid around actual fill. */
    KEEP_ABSOLUTE_LEVELS
}

enum class V540IntrabarResolutionMode {
    /** Same parent-bar stop+target remains ambiguous unless intrabar evidence is supplied. */
    USE_INTRABAR_IF_AVAILABLE,
    /** Always keep same-bar collisions ambiguous. */
    OHLC_AMBIGUOUS,
    /** Stress-test mode: stop wins unresolved same-bar collisions. */
    CONSERVATIVE_STOP_FIRST,
    /** Upper-bound stress-test mode: target wins unresolved same-bar collisions. */
    OPTIMISTIC_TARGET_FIRST
}

/**
 * Explicit execution semantics. Target-1 is a full exit by default; callers that model
 * scale-out must opt in with a fraction below 1.0. Entry behavior is explicit as well.
 */
data class V540ExecutionPolicy(
    val target1ExitFraction: Double = 1.0,
    val closeRemainderAtHorizon: Boolean = false,
    val entryMode: V540EntryExecutionMode = V540EntryExecutionMode.SIGNAL_PRICE,
    val riskPlanRebaseMode: V540RiskPlanRebaseMode = V540RiskPlanRebaseMode.REBASE_BY_DELTA,
    val entryTolerancePct: Double = 0.001,
    val entrySlippageBpsOneWay: Double = 0.0,
    val maxEntryWaitBars: Int = 1,
    val intrabarResolutionMode: V540IntrabarResolutionMode = V540IntrabarResolutionMode.USE_INTRABAR_IF_AVAILABLE
) {
    fun validate(): List<String> = buildList {
        if (!target1ExitFraction.isFinite() || target1ExitFraction !in 0.0..1.0) add("TARGET1_EXIT_FRACTION_INVALID")
        if (target1ExitFraction <= 0.0) add("TARGET1_EXIT_FRACTION_MUST_BE_POSITIVE")
        if (!entryTolerancePct.isFinite() || entryTolerancePct < 0.0 || entryTolerancePct > 0.10) add("ENTRY_TOLERANCE_INVALID")
        if (!entrySlippageBpsOneWay.isFinite() || entrySlippageBpsOneWay < 0.0 || entrySlippageBpsOneWay > 10_000.0) add("ENTRY_SLIPPAGE_INVALID")
        if (maxEntryWaitBars <= 0 || maxEntryWaitBars > 10_000) add("ENTRY_WAIT_BARS_INVALID")
    }
}

data class V540ExecutionCostConfig(
    val commissionBpsRoundTrip: Double = 0.0,
    val spreadBpsRoundTrip: Double = 0.0,
    val slippageBpsRoundTrip: Double = 0.0,
    val marketImpactBpsRoundTrip: Double = 0.0
) {
    fun validate(): List<String> = buildList {
        if (!commissionBpsRoundTrip.isFinite() || commissionBpsRoundTrip < 0.0) add("COMMISSION_INVALID")
        if (!spreadBpsRoundTrip.isFinite() || spreadBpsRoundTrip < 0.0) add("SPREAD_INVALID")
        if (!slippageBpsRoundTrip.isFinite() || slippageBpsRoundTrip < 0.0) add("SLIPPAGE_INVALID")
        if (!marketImpactBpsRoundTrip.isFinite() || marketImpactBpsRoundTrip < 0.0) add("MARKET_IMPACT_INVALID")
    }

    fun totalCostPct(): Double =
        (commissionBpsRoundTrip + spreadBpsRoundTrip + slippageBpsRoundTrip + marketImpactBpsRoundTrip) / 100.0
}

data class V541CostContext(
    val symbol: String,
    val instrumentType: String,
    val timeframeMinutes: Int,
    val executionPrice: Double,
    val liquidityScore: Int?,
    val spreadPct: Double?,
    val volatilityPct: Double?,
    val orderSizeNotional: Double?,
    val averageDailyNotional: Double?
)

data class V541CostEstimate(
    val config: V540ExecutionCostConfig,
    val modelId: String,
    val evidence: String
)

fun interface V541ExecutionCostModel {
    fun estimate(context: V541CostContext): V541CostEstimate
}

class V541FixedCostModel(private val config: V540ExecutionCostConfig) : V541ExecutionCostModel {
    override fun estimate(context: V541CostContext): V541CostEstimate =
        V541CostEstimate(config, "FIXED_BPS", "SYNTHETIC_FIXED_COST_ASSUMPTION")
}

/**
 * Conservative adaptive model. It never invents missing market data: missing spread/liquidity/
 * volatility/ADV is reflected in the evidence string and only configured base assumptions apply.
 */
data class V541AdaptiveCostModel(
    val commissionBpsRoundTrip: Double,
    val baseSlippageBpsRoundTrip: Double = 0.0,
    val baseImpactBpsRoundTrip: Double = 0.0
) : V541ExecutionCostModel {
    override fun estimate(context: V541CostContext): V541CostEstimate {
        val spreadBps = context.spreadPct?.takeIf { it.isFinite() && it >= 0.0 }?.times(100.0) ?: 0.0
        val illiquidity = context.liquidityScore?.coerceIn(0, 100)?.let { (100 - it) / 100.0 } ?: 0.0
        val volatility = context.volatilityPct?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val timeframeMultiplier = when {
            context.timeframeMinutes <= 1 -> 1.50
            context.timeframeMinutes <= 5 -> 1.25
            context.timeframeMinutes <= 15 -> 1.10
            else -> 1.00
        }
        val slippage = (baseSlippageBpsRoundTrip + illiquidity * 10.0 + volatility * 2.0) * timeframeMultiplier
        val participation = if (
            context.orderSizeNotional != null && context.averageDailyNotional != null &&
            context.orderSizeNotional.isFinite() && context.averageDailyNotional.isFinite() &&
            context.orderSizeNotional >= 0.0 && context.averageDailyNotional > 0.0
        ) (context.orderSizeNotional / context.averageDailyNotional).coerceIn(0.0, 1.0) else 0.0
        val impact = baseImpactBpsRoundTrip + 50.0 * participation * participation
        val evidence = buildList {
            add("ADAPTIVE_COST_V1")
            if (context.spreadPct == null) add("SPREAD_MISSING")
            if (context.liquidityScore == null) add("LIQUIDITY_MISSING")
            if (context.volatilityPct == null) add("VOLATILITY_MISSING")
            if (context.orderSizeNotional == null || context.averageDailyNotional == null) add("PARTICIPATION_MISSING")
        }.joinToString("|")
        return V541CostEstimate(
            V540ExecutionCostConfig(commissionBpsRoundTrip, spreadBps, slippage, impact),
            "ADAPTIVE_MARKET_COST_V1",
            evidence
        )
    }
}

enum class V540BacktestEvent {
    STOP, TARGET1, TARGET1_THEN_STOP, TARGET1_THEN_HORIZON, TARGET2, HORIZON_EXIT,
    AMBIGUOUS, OPEN, NO_FILL, ENTRY_REJECTED, INVALID
}

data class V540BacktestTrade(
    val signalId: String,
    val symbol: String,
    val direction: String,
    val event: V540BacktestEvent,
    /** Actual execution time when filled; signalTimestamp when SIGNAL_PRICE mode is explicit. */
    val entryTime: Long,
    val exitTime: Long?,
    /** Actual execution price, not merely the signal/reference price. */
    val entryPrice: Double,
    val exitPrice: Double?,
    val grossReturnPct: Double?,
    val netReturnPct: Double?,
    val mfePct: Double?,
    val maePct: Double?,
    val barsObserved: Int,
    val signalScore: Int,
    val confidence: Int,
    val riskScore: Int,
    val rankingScore: Int,
    val timeframeMinutes: Int,
    val instrumentType: String,
    val marketRegime: String?,
    val verification: String,
    val signalTimestamp: Long = entryTime,
    val signalReferencePrice: Double = entryPrice,
    val executedRiskPlan: RiskPlan? = null,
    val costModelId: String = "UNKNOWN",
    val costEvidence: String = "UNKNOWN",
    val rr1: Double? = executedRiskPlan?.rr1,
    val rr2: Double? = executedRiskPlan?.rr2
)

data class V540BacktestMetrics(
    val sampleCount: Int,
    val closedCount: Int,
    val ambiguousCount: Int,
    val openCount: Int,
    val positiveCount: Int,
    val negativeCount: Int,
    val winRatePct: Double?,
    val averageNetReturnPct: Double?,
    /** Cost-adjusted arithmetic expectancy per closed trade; score is never interpreted as probability. */
    val expectancyPct: Double?,
    val medianNetReturnPct: Double?,
    val profitFactor: Double?,
    val maxDrawdownPct: Double?,
    /** Per-trade standardized ratio; not annualized Sharpe. */
    val sharpePerTrade: Double?,
    /** Per-trade downside standardized ratio; not annualized Sortino. */
    val sortinoPerTrade: Double?,
    val averageMfePct: Double?,
    val averageMaePct: Double?,
    val noFillCount: Int = 0,
    val invalidCount: Int = 0,
    val entryRejectedCount: Int = 0
)

data class V540ScoreBucketStats(
    val minInclusive: Int,
    val maxInclusive: Int,
    val sampleCount: Int,
    val averageNetReturnPct: Double?,
    /** Cost-adjusted arithmetic expectancy per closed trade; score is never interpreted as probability. */
    val expectancyPct: Double?,
    val medianNetReturnPct: Double?,
    val winRatePct: Double?
)

data class V540WalkForwardWindow(
    val trainStart: Long,
    val trainEndExclusive: Long,
    val validationEndExclusive: Long,
    val testEndExclusive: Long,
    val train: V540BacktestMetrics,
    val validation: V540BacktestMetrics,
    val outOfSample: V540BacktestMetrics
)

data class V540MonteCarloSummary(
    val iterations: Int,
    val sampleSize: Int,
    val medianCumulativeReturnPct: Double?,
    val p05CumulativeReturnPct: Double?,
    val p95CumulativeReturnPct: Double?,
    val bootstrapMode: String = "TRADE_IID"
)

data class V540SignalDecayPoint(
    val horizonBars: Int,
    val sampleCount: Int,
    val averageGrossReturnPct: Double?,
    val averageNetReturnPct: Double?,
    val medianNetReturnPct: Double?,
    val measurementType: String = "SIGNAL_DECAY_CLOSE_TO_CLOSE",
    val executionComparable: Boolean = false
)


data class V541BucketMetrics(
    val label: String,
    val metrics: V540BacktestMetrics
)
