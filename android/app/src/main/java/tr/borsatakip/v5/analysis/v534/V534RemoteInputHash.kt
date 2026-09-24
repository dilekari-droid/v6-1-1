package tr.borsatakip.v5.analysis.v534

import java.math.BigDecimal
import tr.borsatakip.v5.analysis.v533.V533AuditHash

/**
 * Canonical REMOTE calculation input contract for V5.3.4.
 *
 * The backend and Android client MUST hash the same normalized feature payload.
 * A formatted hash string by itself is not sufficient proof of input integrity.
 */
data class V534RemoteCalculationInput(
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

object V534RemoteInputHash {
    const val SCHEMA = "V534_REMOTE_INPUT_V1"

    fun validate(input: V534RemoteCalculationInput): List<String> = buildList {
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
    }

    fun canonical(input: V534RemoteCalculationInput): String = buildString {
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

    fun hash(input: V534RemoteCalculationInput): String = V533AuditHash.sha256Hex(canonical(input))

    private fun number(value: Double?): String = when {
        value == null -> "NA"
        !value.isFinite() -> "INVALID"
        value == 0.0 -> "0"
        else -> BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}
