package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Candle

enum class ChartTimeframe(
    val label: String,
    val requestIntervalMinutes: Int?,
    val lookbackMs: Long?,
    val aggregateMinutes: Int? = null,
    val daily: Boolean = false,
    val maximumRange: Boolean = false
) {
    ONE_MIN("1 DK", 1, 2L * 24 * 60 * 60 * 1000),
    THREE_MIN("3 DK", 1, 6L * 24 * 60 * 60 * 1000, aggregateMinutes = 3),
    FIVE_MIN("5 DK", 5, 12L * 24 * 60 * 60 * 1000),
    FIFTEEN_MIN("15 DK", 5, 30L * 24 * 60 * 60 * 1000, aggregateMinutes = 15),
    ONE_HOUR("1 SAAT", 60, 120L * 24 * 60 * 60 * 1000),
    ONE_DAY("1 GÜN", null, null, daily = true),
    ALL_TIME("TÜM ZAMANLAR", null, null, daily = true, maximumRange = true)
}

object OhlcvResampler {
    /**
     * Deterministic OHLCV aggregation. The source interval is part of the contract and
     * is never inferred from observed timestamp gaps.
     */
    fun aggregate(input: List<Candle>, sourceIntervalMinutes: Int, targetIntervalMinutes: Int): List<Candle> {
        val clean = sanitize(input)
        if (sourceIntervalMinutes <= 0 || targetIntervalMinutes <= 0) return emptyList()
        if (targetIntervalMinutes < sourceIntervalMinutes || targetIntervalMinutes % sourceIntervalMinutes != 0) return emptyList()
        if (targetIntervalMinutes == sourceIntervalMinutes) return clean
        val expectedRows = targetIntervalMinutes / sourceIntervalMinutes
        val sourceMs = sourceIntervalMinutes * 60_000L
        val bucketMs = targetIntervalMinutes * 60_000L
        return clean
            .groupBy { it.timestamp - (it.timestamp % bucketMs) }
            .toSortedMap()
            .mapNotNull { (bucket, rows) ->
                val ordered = rows.sortedBy { it.timestamp }
                if (ordered.size != expectedRows) return@mapNotNull null
                val expectedTimestamps = (0 until expectedRows).map { bucket + it * sourceMs }
                if (ordered.map { it.timestamp } != expectedTimestamps) return@mapNotNull null
                Candle(
                    timestamp = bucket,
                    open = ordered.first().open,
                    high = ordered.maxOf { it.high },
                    low = ordered.minOf { it.low },
                    close = ordered.last().close,
                    volume = ordered.sumOf { it.volume }
                )
            }
    }

    fun sanitize(input: List<Candle>): List<Candle> = input
        .asSequence()
        .filter { c ->
            c.timestamp > 0L &&
                listOf(c.open, c.high, c.low, c.close, c.volume).all { it.isFinite() } &&
                c.open > 0.0 && c.high > 0.0 && c.low > 0.0 && c.close > 0.0 &&
                c.high >= maxOf(c.open, c.close, c.low) &&
                c.low <= minOf(c.open, c.close, c.high) &&
                c.volume >= 0.0
        }
        .distinctBy { it.timestamp }
        .sortedBy { it.timestamp }
        .toList()
}
