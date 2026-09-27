package tr.borsatakip.v5.data

import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import kotlin.math.max

/**
 * Tek timeframe readiness sözleşmesi.
 * Teknik hesaplarda açık son mum kapalı geçmişe karıştırılmaz.
 */
object CandleReadinessPolicy {
    const val LONGEST_INDICATOR_LOOKBACK = 200
    const val STRATEGY_LOOKBACK = 20
    const val PRICE_ACTION_LOOKBACK = 20
    const val WARMUP_BARS = 20

    data class Result(
        val requiredClosedBars: Int,
        val availableClosedBars: Int,
        val historyCoveragePct: Int,
        val timeframeReady: Boolean,
        val lastBarClosed: Boolean?,
        val reason: String
    )

    fun requiredClosedBars(): Int =
        max(LONGEST_INDICATOR_LOOKBACK, max(STRATEGY_LOOKBACK, PRICE_ACTION_LOOKBACK)) + WARMUP_BARS

    fun lastBarClosed(stock: Stock, nowWall: Long = System.currentTimeMillis()): Boolean? {
        stock.lastBarClosed?.let { return it }
        val lastTime = stock.candles.lastOrNull()?.timestamp?.takeIf { it > 0L } ?: return null
        val frameMs = intervalMillis(stock.interval) ?: return null
        return nowWall >= lastTime + frameMs
    }

    fun closedCandles(stock: Stock, nowWall: Long = System.currentTimeMillis()): List<Candle> {
        val ordered = stock.candles.distinctBy { it.timestamp }.sortedBy { it.timestamp }
        if (ordered.isEmpty()) return ordered
        return when (lastBarClosed(stock.copy(candles = ordered), nowWall)) {
            true -> ordered
            false, null -> ordered.dropLast(1)
        }
    }

    fun evaluate(stock: Stock, nowWall: Long = System.currentTimeMillis()): Result {
        val required = requiredClosedBars()
        val closed = closedCandles(stock, nowWall)
        val available = closed.size
        val pct = if (required <= 0) 100 else ((available * 100L) / required).toInt().coerceIn(0, 100)
        val ready = available >= required
        val closedState = lastBarClosed(stock, nowWall)
        val reason = if (ready) {
            "TIMEFRAME_READY • kapalı mum $available/$required"
        } else {
            "TIMEFRAME_NOT_READY • en az $required kapalı OHLCV mumu gerekli; $available mevcut."
        }
        return Result(required, available, pct, ready, closedState, reason)
    }

    private fun intervalMillis(interval: String): Long? {
        val normalized = interval.trim().lowercase()
        if (normalized == "1d" || normalized == "d" || normalized == "day") return 24L * 60L * 60L * 1000L
        val minutes = normalized.removeSuffix("m").toIntOrNull()?.takeIf { it in 1..239 } ?: return null
        return minutes * 60_000L
    }
}
