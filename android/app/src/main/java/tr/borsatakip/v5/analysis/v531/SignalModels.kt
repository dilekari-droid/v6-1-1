package tr.borsatakip.v5.analysis.v531

import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.TechnicalSnapshot

enum class V531Direction { LONG, SHORT, WATCH }
enum class V531RiskLevel { LOW, MEDIUM, HIGH, UNKNOWN }
enum class V531ConfidenceBand { NORMAL, DEGRADED, LOW, BLOCKED }

enum class V531ReasonCode {
    LONG_SIGNAL,
    SHORT_SIGNAL,
    LOW_SCORE,
    LOW_CONFIDENCE,
    DATA_CONFIDENCE_BLOCKED,
    STALE_DATA,
    MISSING_CANDLE,
    MISSING_VOLUME,
    MISSING_OI,
    TIMEFRAME_CONFLICT,
    CONTRACT_UNRESOLVED,
    HIGH_VOLATILITY,
    PROVIDER_DEGRADED,
    TEMPORAL_CAUSALITY_VIOLATION,
    INSUFFICIENT_SIGNAL_COVERAGE,
    DIRECTION_CONFLICT,
    INVALID_PRICE
}

data class V531ComponentScore(
    val id: String,
    val weight: Int,
    val signedStrength: Double?,
    val longContribution: Int,
    val shortContribution: Int,
    val evidence: List<String> = emptyList()
) {
    val available: Boolean get() = signedStrength != null
}

data class V531DataQualityInput(
    val dataAgeMs: Long?,
    val candleCount: Int,
    val candlesValid: Boolean,
    val quoteValid: Boolean,
    val providerHealthy: Boolean,
    val symbolResolved: Boolean,
    val volumeAvailable: Boolean,
    val openInterestApplicable: Boolean,
    val openInterestAvailable: Boolean
)

data class V531ConfidenceResult(
    val score: Int,
    val band: V531ConfidenceBand,
    val breakdown: Map<String, Int>,
    val reasonCodes: List<V531ReasonCode>
)

data class V531RiskResult(
    val score: Int,
    val level: V531RiskLevel,
    val coveragePercent: Int,
    val breakdown: Map<String, Int>,
    val reasonCodes: List<V531ReasonCode>
)

data class V531CalculationInput(
    val candles: List<Candle>,
    val technical: TechnicalSnapshot,
    val currentPrice: Double,
    val dataQuality: V531DataQualityInput,
    val openInterestChangePct: Double? = null,
    val mtfConsensusScore: Int? = null,
    val spreadPct: Double? = null,
    val liquidityScore: Int? = null,
    val slippageSensitivity: Double? = null,
    val structureDistanceAtr: Double? = null,
    val contractRiskPct: Int? = null,
    val evaluationTimestamp: Long? = null,
    val technicalAsOfTimestamp: Long? = null,
    val openInterestAsOfTimestamp: Long? = null,
    val mtfAsOfTimestamp: Long? = null
)

data class V531SignalResult(
    val engineVersion: String,
    val longScore: Int,
    val shortScore: Int,
    val finalSignalScore: Int,
    val rawSignalScore: Int,
    val dominantDirection: V531Direction,
    val decision: V531Direction,
    val dataConfidence: Int,
    val confidenceBand: V531ConfidenceBand,
    val riskScore: Int,
    val riskLevel: V531RiskLevel,
    val riskCoveragePercent: Int,
    val availableSignalWeight: Int,
    val conflictPenalty: Int,
    val components: List<V531ComponentScore>,
    val reasonCodes: List<V531ReasonCode>,
    val auditLines: List<String>
)

data class V531ContextResult(
    val longScore: Int,
    val shortScore: Int,
    val finalSignalScore: Int,
    val direction: V531Direction,
    val riskScore: Int,
    val availableSignalWeight: Int,
    val conflictPenalty: Int,
    val reasonCodes: List<V531ReasonCode>
)
