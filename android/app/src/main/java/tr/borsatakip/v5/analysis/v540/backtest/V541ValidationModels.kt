package tr.borsatakip.v5.analysis.v540.backtest

enum class V541BootstrapMode { TRADE_IID, DAY_BLOCK, SESSION_BLOCK, SYMBOL_BLOCK, REGIME_BLOCK }

enum class V541ValidationStatus { VALIDATED, INSUFFICIENT_EVIDENCE, NOT_VALIDATED, FAILED }

data class V541ModelCandidate(val modelId: String, val trades: List<V540BacktestTrade>)

data class V541WalkForwardSelection(
    val trainStart: Long,
    val trainEndExclusive: Long,
    val validationEndExclusive: Long,
    val testEndExclusive: Long,
    val selectedModelId: String?,
    val train: V540BacktestMetrics,
    val validation: V540BacktestMetrics,
    val outOfSample: V540BacktestMetrics,
    val status: V541ValidationStatus,
    val reason: String
)

data class V541EvidenceRequirements(
    val minClosedTrades: Int = 100,
    val minOosWindows: Int = 3,
    val requirePointInTimeData: Boolean = true,
    val requireObservedCostInputs: Boolean = true
)

data class V541EvidenceReport(
    val status: V541ValidationStatus,
    val closedTrades: Int,
    val oosWindows: Int,
    val pointInTimeDataVerified: Boolean,
    val observedCostInputsVerified: Boolean,
    val reasons: List<String>
)

data class V541ScoreCalibrationReport(
    val status: V541ValidationStatus,
    val qualifyingBuckets: Int,
    val minSamplesPerBucket: Int,
    val buckets: List<V540ScoreBucketStats>,
    val note: String = "SCORE_IS_NOT_PROBABILITY"
)
