package tr.borsatakip.v5.analysis.v535

import java.math.BigDecimal
import kotlin.math.abs
import tr.borsatakip.v5.analysis.v533.V533AuditHash

/**
 * V5.3.5 canonical REMOTE input schema.
 *
 * The schema is intentionally strict: calculationInputs must contain exactly
 * [REQUIRED_FIELDS]. Unknown/missing fields are contract violations so a
 * backend cannot silently change calculation semantics without a schema bump.
 */
data class V535RemoteCalculationInput(
    val symbol: String,
    val analysisTimeframeMinutes: Int,
    val evaluationTimestamp: Long,
    val price: Double,
    val stockTrend: String,
    val momentum: String,
    val relativeVolume: Double?,
    val rsi14: Double?,
    val macd: Double?,
    val macdSignal: Double?,
    val atr14: Double?,
    val sessionVwap: Double?,
    val ema20: Double?,
    val ema50: Double?,
    val ema200: Double?,
    val openInterestChangePct: Double?,
    val mtfConsensusScore: Int?,
    val spreadPct: Double?,
    val liquidityScore: Int?,
    val slippageSensitivity: Double?,
    val structureDistanceAtr: Double?,
    val contractRiskPct: Int?
)

object V535RemoteInputHash {
    const val SCHEMA = "V535_REMOTE_INPUT_V1"

    val REQUIRED_FIELDS: Set<String> = linkedSetOf(
        "symbol", "analysisTimeframeMinutes", "evaluationTimestamp", "price", "stockTrend", "momentum",
        "relativeVolume", "rsi14", "macd", "macdSignal", "atr14", "sessionVwap", "ema20", "ema50", "ema200",
        "openInterestChangePct", "mtfConsensusScore", "spreadPct", "liquidityScore", "slippageSensitivity",
        "structureDistanceAtr", "contractRiskPct"
    )

    fun validate(input: V535RemoteCalculationInput): List<String> = buildList {
        if (input.symbol.isBlank()) add("INPUT_SYMBOL_REQUIRED")
        if (input.analysisTimeframeMinutes !in 1..239) add("INPUT_TIMEFRAME_RANGE")
        if (input.evaluationTimestamp <= 0L) add("INPUT_EVALUATION_TIME_INVALID")
        if (!input.price.isFinite() || input.price <= 0.0) add("INPUT_PRICE_INVALID")
        if (input.stockTrend.isBlank()) add("INPUT_STOCK_TREND_REQUIRED")
        if (input.momentum.isBlank()) add("INPUT_MOMENTUM_REQUIRED")
        listOf(
            "RELATIVE_VOLUME" to input.relativeVolume,
            "RSI14" to input.rsi14,
            "MACD" to input.macd,
            "MACD_SIGNAL" to input.macdSignal,
            "ATR14" to input.atr14,
            "SESSION_VWAP" to input.sessionVwap,
            "EMA20" to input.ema20,
            "EMA50" to input.ema50,
            "EMA200" to input.ema200,
            "OI_CHANGE" to input.openInterestChangePct,
            "SPREAD" to input.spreadPct,
            "SLIPPAGE" to input.slippageSensitivity,
            "STRUCTURE_DISTANCE_ATR" to input.structureDistanceAtr
        ).forEach { (name, value) -> if (value != null && !value.isFinite()) add("INPUT_${name}_NONFINITE") }
        if (input.mtfConsensusScore != null && input.mtfConsensusScore !in -100..100) add("INPUT_MTF_RANGE")
        if (input.liquidityScore != null && input.liquidityScore !in 0..100) add("INPUT_LIQUIDITY_RANGE")
        if (input.contractRiskPct != null && input.contractRiskPct !in 0..100) add("INPUT_CONTRACT_RISK_RANGE")
        if (input.spreadPct != null && input.spreadPct < 0.0) add("INPUT_SPREAD_NEGATIVE")
        if (input.slippageSensitivity != null && input.slippageSensitivity < 0.0) add("INPUT_SLIPPAGE_NEGATIVE")
        if (input.structureDistanceAtr != null && input.structureDistanceAtr < 0.0) add("INPUT_STRUCTURE_DISTANCE_NEGATIVE")
        if (input.relativeVolume != null && input.relativeVolume < 0.0) add("INPUT_RVOL_NEGATIVE")
        if (input.atr14 != null && input.atr14 < 0.0) add("INPUT_ATR_NEGATIVE")
    }

    fun canonical(input: V535RemoteCalculationInput): String = buildString {
        append("schema=").append(SCHEMA).append('\n')
        append("symbol=").append(input.symbol.trim().uppercase()).append('\n')
        append("timeframe=").append(input.analysisTimeframeMinutes).append('\n')
        append("evaluationTimestamp=").append(input.evaluationTimestamp).append('\n')
        append("price=").append(number(input.price)).append('\n')
        append("stockTrend=").append(input.stockTrend.trim().uppercase()).append('\n')
        append("momentum=").append(input.momentum.trim().uppercase()).append('\n')
        append("relativeVolume=").append(number(input.relativeVolume)).append('\n')
        append("rsi14=").append(number(input.rsi14)).append('\n')
        append("macd=").append(number(input.macd)).append('\n')
        append("macdSignal=").append(number(input.macdSignal)).append('\n')
        append("atr14=").append(number(input.atr14)).append('\n')
        append("sessionVwap=").append(number(input.sessionVwap)).append('\n')
        append("ema20=").append(number(input.ema20)).append('\n')
        append("ema50=").append(number(input.ema50)).append('\n')
        append("ema200=").append(number(input.ema200)).append('\n')
        append("openInterestChangePct=").append(number(input.openInterestChangePct)).append('\n')
        append("mtfConsensusScore=").append(input.mtfConsensusScore?.toString() ?: "NA").append('\n')
        append("spreadPct=").append(number(input.spreadPct)).append('\n')
        append("liquidityScore=").append(input.liquidityScore?.toString() ?: "NA").append('\n')
        append("slippageSensitivity=").append(number(input.slippageSensitivity)).append('\n')
        append("structureDistanceAtr=").append(number(input.structureDistanceAtr)).append('\n')
        append("contractRiskPct=").append(input.contractRiskPct?.toString() ?: "NA")
    }

    fun hash(input: V535RemoteCalculationInput): String = V533AuditHash.sha256Hex(canonical(input))

    private fun number(value: Double?): String = when {
        value == null -> "NA"
        !value.isFinite() -> "INVALID"
        value == 0.0 -> "0"
        else -> BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}

/** Top-level representation of every calculation input that may be consumed/displayed by Android. */
data class V535RemoteItemMirror(
    val symbol: String,
    val analysisTimeframeMinutes: Int,
    val evaluationTimestamp: Long,
    val price: Double,
    val stockTrend: String,
    val momentum: String,
    val relativeVolume: Double?,
    val rsi14: Double?,
    val macd: Double?,
    val macdSignal: Double?,
    val atr14: Double?,
    val sessionVwap: Double?,
    val ema20: Double?,
    val ema50: Double?,
    val ema200: Double?,
    val openInterestChangePct: Double?,
    val mtfConsensusScore: Int?,
    val spreadPct: Double?,
    val liquidityScore: Int?,
    val slippageSensitivity: Double?,
    val structureDistanceAtr: Double?,
    val contractRiskPct: Int?
)

data class V535ConsistencyReport(val accepted: Boolean, val violations: List<String>)

/**
 * Prevents two representations of the same REMOTE calculation from diverging.
 * Hash validation is not enough if Android later reads a top-level field.
 */
object V535RemoteInputConsistency {
    fun validate(input: V535RemoteCalculationInput, mirror: V535RemoteItemMirror): V535ConsistencyReport {
        val v = mutableListOf<String>()
        fun mismatch(condition: Boolean, code: String) { if (condition) v += code }
        fun eq(a: Double?, b: Double?): Boolean = when {
            a == null && b == null -> true
            a == null || b == null -> false
            else -> a == b
        }
        mismatch(input.symbol.trim().uppercase() != mirror.symbol.trim().uppercase(), "SYMBOL_MISMATCH")
        mismatch(input.analysisTimeframeMinutes != mirror.analysisTimeframeMinutes, "TIMEFRAME_MISMATCH")
        mismatch(input.evaluationTimestamp != mirror.evaluationTimestamp, "EVALUATION_TIMESTAMP_MISMATCH")
        mismatch(!eq(input.price, mirror.price), "PRICE_MISMATCH")
        mismatch(input.stockTrend.trim().uppercase() != mirror.stockTrend.trim().uppercase(), "STOCK_TREND_MISMATCH")
        mismatch(input.momentum.trim().uppercase() != mirror.momentum.trim().uppercase(), "MOMENTUM_MISMATCH")
        mismatch(!eq(input.relativeVolume, mirror.relativeVolume), "RVOL_MISMATCH")
        mismatch(!eq(input.rsi14, mirror.rsi14), "RSI_MISMATCH")
        mismatch(!eq(input.macd, mirror.macd), "MACD_MISMATCH")
        mismatch(!eq(input.macdSignal, mirror.macdSignal), "MACD_SIGNAL_MISMATCH")
        mismatch(!eq(input.atr14, mirror.atr14), "ATR_MISMATCH")
        mismatch(!eq(input.sessionVwap, mirror.sessionVwap), "VWAP_MISMATCH")
        mismatch(!eq(input.ema20, mirror.ema20), "EMA20_MISMATCH")
        mismatch(!eq(input.ema50, mirror.ema50), "EMA50_MISMATCH")
        mismatch(!eq(input.ema200, mirror.ema200), "EMA200_MISMATCH")
        mismatch(!eq(input.openInterestChangePct, mirror.openInterestChangePct), "OI_CHANGE_MISMATCH")
        mismatch(input.mtfConsensusScore != mirror.mtfConsensusScore, "MTF_MISMATCH")
        mismatch(!eq(input.spreadPct, mirror.spreadPct), "SPREAD_MISMATCH")
        mismatch(input.liquidityScore != mirror.liquidityScore, "LIQUIDITY_MISMATCH")
        mismatch(!eq(input.slippageSensitivity, mirror.slippageSensitivity), "SLIPPAGE_MISMATCH")
        mismatch(!eq(input.structureDistanceAtr, mirror.structureDistanceAtr), "STRUCTURE_DISTANCE_MISMATCH")
        mismatch(input.contractRiskPct != mirror.contractRiskPct, "CONTRACT_RISK_MISMATCH")
        return V535ConsistencyReport(v.isEmpty(), v.distinct())
    }
}

object V535MtfSemantics {
    fun labelFor(score: Int?): String = when {
        score == null -> "MTF VERİ YOK"
        score >= 55 -> "MTF LONG UYUM"
        score <= -55 -> "MTF SHORT UYUM"
        else -> "MTF KARIŞIK"
    }

    fun isConsistent(score: Int?, label: String): Boolean = label.trim().uppercase() == labelFor(score)
}

data class V535ClockEvidence(
    val serverTime: Long,
    val generatedAt: Long,
    val exchangeTimestamp: Long,
    val dataTimestamp: Long,
    val receivedAt: Long,
    val declaredDataAgeMs: Long
)

data class V535ClockReport(
    val accepted: Boolean,
    val violations: List<String>,
    val measuredDataAgeMs: Long?
)

/** Server-authoritative wall-clock contract. Client wall clock is audit metadata only. */
object V535ClockIntegrity {
    const val FUTURE_TOLERANCE_MS = 15_000L
    const val DATA_AGE_TOLERANCE_MS = 15_000L
    const val MAX_DATA_AGE_MS = 60_000L
    const val MAX_GENERATION_AGE_MS = 60_000L

    fun validateSnapshot(serverTime: Long, generatedAt: Long): V535ClockReport {
        val v = mutableListOf<String>()
        if (serverTime <= 0L) v += "SERVER_TIME_INVALID"
        if (generatedAt <= 0L) v += "GENERATED_AT_INVALID"
        if (serverTime > 0L && generatedAt > serverTime + FUTURE_TOLERANCE_MS) v += "GENERATED_IN_FUTURE"
        if (serverTime > 0L && generatedAt > 0L && serverTime - generatedAt > MAX_GENERATION_AGE_MS) v += "GENERATED_TOO_OLD"
        return V535ClockReport(v.isEmpty(), v.distinct(), null)
    }

    fun validateItem(e: V535ClockEvidence): V535ClockReport {
        val v = mutableListOf<String>()
        val snapshot = validateSnapshot(e.serverTime, e.generatedAt)
        if (!snapshot.accepted) v += snapshot.violations
        if (e.exchangeTimestamp <= 0L) v += "EXCHANGE_TIME_INVALID"
        if (e.dataTimestamp <= 0L) v += "DATA_TIME_INVALID"
        if (e.receivedAt <= 0L) v += "RECEIVED_AT_INVALID"
        if (e.dataTimestamp != e.exchangeTimestamp) v += "DATA_EXCHANGE_TIME_MISMATCH"
        if (e.declaredDataAgeMs !in 0..MAX_DATA_AGE_MS) v += "DECLARED_DATA_AGE_RANGE"

        val measured = if (e.serverTime > 0L && e.exchangeTimestamp > 0L) e.serverTime - e.exchangeTimestamp else null
        if (measured != null) {
            if (measured < -FUTURE_TOLERANCE_MS) v += "EXCHANGE_IN_FUTURE"
            if (measured > MAX_DATA_AGE_MS) v += "EXCHANGE_TOO_OLD"
            if (abs(e.declaredDataAgeMs - measured.coerceAtLeast(0L)) > DATA_AGE_TOLERANCE_MS) v += "DATA_AGE_MISMATCH"
        }
        if (e.receivedAt > 0L && e.serverTime > 0L && e.receivedAt > e.serverTime + FUTURE_TOLERANCE_MS) v += "RECEIVED_AFTER_SERVER"
        if (e.receivedAt > 0L && e.exchangeTimestamp > 0L && e.receivedAt + FUTURE_TOLERANCE_MS < e.exchangeTimestamp) v += "RECEIVED_BEFORE_EXCHANGE"
        if (e.generatedAt > 0L && e.exchangeTimestamp > 0L && e.exchangeTimestamp > e.generatedAt + FUTURE_TOLERANCE_MS) v += "EXCHANGE_AFTER_GENERATION"
        return V535ClockReport(v.isEmpty(), v.distinct(), measured?.coerceAtLeast(0L))
    }
}
