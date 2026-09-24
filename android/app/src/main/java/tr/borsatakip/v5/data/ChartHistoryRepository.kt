package tr.borsatakip.v5.data

import tr.borsatakip.v5.analysis.ChartTimeframe
import tr.borsatakip.v5.analysis.OhlcvResampler
import tr.borsatakip.v5.model.Candle
import java.util.concurrent.ConcurrentHashMap

class ChartHistoryRepository(private val provider: MarketDataProvider) {
    private data class CacheEntry(val candles: List<Candle>, val loadedAt: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun load(symbol: String, timeframe: ChartTimeframe, fallbackDaily: List<Candle>): List<Candle> {
        val normalized = symbol.trim().uppercase()
        val fallback = OhlcvResampler.sanitize(fallbackDaily)
        val newest = fallback.lastOrNull()?.timestamp ?: 0L
        val key = "$normalized:${timeframe.name}:$newest"
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.loadedAt <= CACHE_TTL_MS }?.let { return it.candles }

        val raw = when {
            timeframe.daily -> provider.fetchDailyHistory(normalized, timeframe.maximumRange).ifEmpty { fallback }
            else -> {
                val to = now
                val from = to - requireNotNull(timeframe.lookbackMs)
                provider.fetchHistory(normalized, from, to, requireNotNull(timeframe.requestIntervalMinutes))
            }
        }
        val clean = OhlcvResampler.sanitize(raw)
        val result = timeframe.aggregateMinutes?.let { OhlcvResampler.aggregate(clean, requireNotNull(timeframe.requestIntervalMinutes), it) } ?: clean
        cache[key] = CacheEntry(result, now)
        return result
    }

    fun invalidate(symbol: String? = null) {
        if (symbol == null) cache.clear() else {
            val prefix = symbol.trim().uppercase() + ":"
            cache.keys.filter { it.startsWith(prefix) }.forEach(cache::remove)
        }
    }

    companion object { private const val CACHE_TTL_MS = 60_000L }
}
