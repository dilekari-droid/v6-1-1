package tr.borsatakip.v5.data

import tr.borsatakip.v5.analysis.ChartTimeframe
import tr.borsatakip.v5.analysis.OhlcvResampler
import tr.borsatakip.v5.model.Candle
import java.util.concurrent.ConcurrentHashMap

enum class ChartHistoryFailureReason { NONE, API_RESPONSE_EMPTY, SYMBOL_NOT_FOUND, INSUFFICIENT_CANDLES, TIMEFRAME_UNSUPPORTED, PARSE_ERROR, PROVIDER_ERROR }

data class ChartHistoryLoadResult(
    val candles: List<Candle>,
    val reason: ChartHistoryFailureReason = ChartHistoryFailureReason.NONE,
    val detail: String = "",
    val firstTimestamp: Long? = candles.firstOrNull()?.timestamp,
    val lastTimestamp: Long? = candles.lastOrNull()?.timestamp
)

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

    suspend fun loadWithDiagnostics(symbol: String, timeframe: ChartTimeframe, fallbackDaily: List<Candle>): ChartHistoryLoadResult {
        return try {
            val candles = load(symbol, timeframe, fallbackDaily)
            if (candles.size < MIN_RENDER_CANDLES) {
                ChartHistoryLoadResult(candles, ChartHistoryFailureReason.INSUFFICIENT_CANDLES, "Yeterli mum yok: ${candles.size}/$MIN_RENDER_CANDLES")
            } else {
                ChartHistoryLoadResult(candles)
            }
        } catch (pe: ProviderException) {
            val reason = when (pe.code) {
                ProviderFailureCode.EMPTY_DATA -> if (pe.message?.contains("bulunamadı", ignoreCase = true) == true) ChartHistoryFailureReason.SYMBOL_NOT_FOUND else ChartHistoryFailureReason.API_RESPONSE_EMPTY
                ProviderFailureCode.BIST_HISTORY_ERROR -> ChartHistoryFailureReason.PARSE_ERROR
                else -> ChartHistoryFailureReason.PROVIDER_ERROR
            }
            ChartHistoryLoadResult(emptyList(), reason, pe.message ?: pe.code.name)
        } catch (iae: IllegalArgumentException) {
            ChartHistoryLoadResult(emptyList(), ChartHistoryFailureReason.TIMEFRAME_UNSUPPORTED, iae.message ?: "Timeframe desteklenmiyor")
        } catch (t: Throwable) {
            ChartHistoryLoadResult(emptyList(), ChartHistoryFailureReason.PARSE_ERROR, t.message ?: t.javaClass.simpleName)
        }
    }

    fun invalidate(symbol: String? = null) {
        if (symbol == null) cache.clear() else {
            val prefix = symbol.trim().uppercase() + ":"
            cache.keys.filter { it.startsWith(prefix) }.forEach(cache::remove)
        }
    }

    companion object { private const val CACHE_TTL_MS = 60_000L; const val MIN_RENDER_CANDLES = 2 }
}
