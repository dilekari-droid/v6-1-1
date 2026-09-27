package tr.borsatakip.v5.data

import tr.borsatakip.v5.model.Candle

import kotlin.math.max

/** Strict server-response contract for VIOP history. No silent repair is allowed. */
object ViopHistoryIntegrityPolicy {
    data class Metadata(
        val symbol: String,
        val interval: String,
        val lastBarClosed: Boolean,
        val exchangeTimestamp: Long
    )

    fun validateMetadata(
        requestedSymbol: String,
        requestedInterval: String,
        responseSymbol: String,
        responseInterval: String,
        lastBarClosed: Boolean,
        exchangeTimestamp: Long
    ): String? {
        val expectedSymbol = requestedSymbol.trim().uppercase()
        val actualSymbol = responseSymbol.trim().uppercase()
        if (actualSymbol.isBlank()) return "Yanıtta sembol kimliği bulunmuyor."
        if (actualSymbol != expectedSymbol) return "Sembol kimliği uyuşmuyor."
        if (responseInterval.trim().lowercase() != requestedInterval.trim().lowercase()) return "History interval kimliği uyuşmuyor."
        if (!lastBarClosed) return "History son mumu kapalı değil."
        if (exchangeTimestamp <= 0L) return "History piyasa veri zamanı eksik/geçersiz."
        return null
    }

    fun validateExchangeFreshness(exchangeTimestamp: Long, nowMs: Long, interval: String): String? {
        if (exchangeTimestamp <= 0L) return "History piyasa veri zamanı eksik/geçersiz."
        if (exchangeTimestamp > nowMs + MAX_FUTURE_SKEW_MS) return "History piyasa veri zamanı gelecekte."
        val age = (nowMs - exchangeTimestamp).coerceAtLeast(0L)
        val key = interval.trim().lowercase()
        val limit = when (key) {
            "1d" -> 4L * DAY_MS
            else -> {
                val frameMs = key.removeSuffix("m").toLongOrNull()?.let { max(1L, it) * MINUTE_MS }
                    ?: return "History interval freshness için tanımsız."
                max(15L * MINUTE_MS, frameMs * 2L + 2L * MINUTE_MS)
            }
        }
        return if (age > limit) "History piyasa verisi stale: ${age} ms > $limit ms." else null
    }

    /** Independently verifies that the last candle has completed from its timestamp and interval.
     * Backend lastBarClosed=true is accepted only when this time-based check also passes.
     */
    fun validateLastBarClosed(candles: List<Candle>, nowMs: Long, interval: String): String? {
        val last = candles.lastOrNull() ?: return "History son mum bulunmuyor."
        val frameMs = intervalMillis(interval) ?: return "History son mum kapanış kontrolü için interval tanımsız."
        if (last.timestamp > nowMs + MAX_FUTURE_SKEW_MS) return "History son mum zamanı gelecekte."
        if (nowMs < last.timestamp + frameMs) return "History son mumu zaman tabanlı olarak henüz kapanmamış."
        return null
    }

    fun validateCandles(candles: List<Candle>): String? {
        if (candles.isEmpty()) return "History mum listesi boş."
        var previous = Long.MIN_VALUE
        val seen = HashSet<Long>(candles.size)
        candles.forEachIndexed { index, c ->
            if (c.timestamp <= 0L) return "candles[$index] zaman damgası geçersiz."
            if (!seen.add(c.timestamp)) return "candles[$index] yinelenen zaman damgası içeriyor."
            if (index > 0 && c.timestamp <= previous) return "History mumları kronolojik olarak artan sırada değil."
            previous = c.timestamp
        }
        return null
    }
    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 24L * 60L * MINUTE_MS
    private const val MAX_FUTURE_SKEW_MS = 30_000L

    private fun intervalMillis(interval: String): Long? {
        val key = interval.trim().lowercase()
        if (key == "1d") return DAY_MS
        val minutes = key.removeSuffix("m").toLongOrNull()?.takeIf { it > 0L } ?: return null
        return minutes * MINUTE_MS
    }
}
