package tr.borsatakip.v5.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tr.borsatakip.v5.model.Candle

/**
 * Fresh-only MTF history cache.
 *
 * Stale entries are never returned as live/fresh data. Cache identity includes provider,
 * symbol and timeframe; every accepted entry carries load and exchange timestamps.
 */
object MtfHistoryCache {
    class Stats {
        private val hitCount = AtomicInteger(0)
        private val missCount = AtomicInteger(0)
        val hits: Int get() = hitCount.get()
        val misses: Int get() = missCount.get()
        internal fun hit() { hitCount.incrementAndGet() }
        internal fun miss() { missCount.incrementAndGet() }
    }
    data class Entry(
        val candles: List<Candle>,
        val loadedAt: Long,
        val lastExchangeTimestamp: Long,
        val sourceKey: String,
        val symbol: String,
        val timeframe: String
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun loadFresh(
        sourceKey: String,
        symbol: String,
        timeframe: String,
        ttlMs: Long,
        nowMs: () -> Long = { System.currentTimeMillis() },
        loader: suspend () -> List<Candle>,
        stats: Stats? = null,
        sourceKeyNow: (() -> String)? = null
    ): List<Candle> {
        require(ttlMs > 0L) { "MTF cache TTL pozitif olmalı." }
        val normalizedSymbol = symbol.trim().uppercase()
        val normalizedTimeframe = timeframe.trim().lowercase()
        val key = "${sourceKey.trim()}|$normalizedSymbol|$normalizedTimeframe"
        val now = nowMs()
        entries[key]?.takeIf { isFresh(it, ttlMs, now) }?.let {
            stats?.hit()
            return it.candles
        }

        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            val lockedNow = nowMs()
            entries[key]?.takeIf { isFresh(it, ttlMs, lockedNow) }?.let {
                stats?.hit()
                return@withLock it.candles
            }
            stats?.miss()

            val raw = loader()
            require(raw.isNotEmpty()) { "MTF cache için boş history kabul edilemez." }
            require(raw.zipWithNext().all { (a, b) -> a.timestamp > 0L && b.timestamp > a.timestamp }) {
                "MTF cache history kronolojik ve benzersiz sırada olmalıdır."
            }
            require(raw.all { c ->
                c.timestamp > 0L &&
                    c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite() && c.volume.isFinite() &&
                    c.open > 0.0 && c.high > 0.0 && c.low > 0.0 && c.close > 0.0 && c.volume >= 0.0 &&
                    c.low <= c.high && c.open in c.low..c.high && c.close in c.low..c.high
            }) { "MTF cache history bozuk OHLCV içeriyor; sessiz temizleme yapılmadı." }
            val clean = raw.toList()
            val lastExchangeTimestamp = clean.last().timestamp
            require(lastExchangeTimestamp > 0L && lastExchangeTimestamp <= lockedNow + MAX_FUTURE_CLOCK_SKEW_MS) { "MTF cache exchange timestamp geçersiz." }
            val exchangeAge = (lockedNow - lastExchangeTimestamp).coerceAtLeast(0L)
            require(exchangeAge <= exchangeFreshnessLimit(timeframe, ttlMs)) { "MTF cache exchange verisi stale." }
            val resolvedSourceKey = sourceKeyNow?.invoke()?.trim()
            if (resolvedSourceKey == null || resolvedSourceKey == sourceKey.trim()) {
                entries[key] = Entry(
                    candles = clean,
                    loadedAt = lockedNow,
                    lastExchangeTimestamp = lastExchangeTimestamp,
                    sourceKey = sourceKey,
                    symbol = normalizedSymbol,
                    timeframe = timeframe
                )
            }
            clean
        }
    }

    /** Compatibility overload for call sites that use a trailing loader lambda. */
    suspend fun loadFresh(
        sourceKey: String,
        symbol: String,
        timeframe: String,
        ttlMs: Long,
        loader: suspend () -> List<Candle>
    ): List<Candle> = loadFresh(
        sourceKey, symbol, timeframe, ttlMs,
        { System.currentTimeMillis() }, loader, null, null
    )

    /** Yardımcı MTF verisi ana taramayı düşürmez; iptal semantiği korunur. */
    suspend fun loadOptionalFresh(
        sourceKey: String,
        symbol: String,
        timeframe: String,
        ttlMs: Long,
        nowMs: () -> Long = { System.currentTimeMillis() },
        loader: suspend () -> List<Candle>,
        stats: Stats? = null,
        sourceKeyNow: (() -> String)? = null
    ): List<Candle> = try {
        loadFresh(sourceKey, symbol, timeframe, ttlMs, nowMs, loader, stats, sourceKeyNow)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        emptyList()
    }

    private fun isFresh(entry: Entry, ttlMs: Long, nowMs: Long): Boolean {
        if (nowMs < entry.loadedAt || nowMs - entry.loadedAt > ttlMs) return false
        if (entry.lastExchangeTimestamp <= 0L || entry.lastExchangeTimestamp > nowMs + MAX_FUTURE_CLOCK_SKEW_MS) return false
        return nowMs - entry.lastExchangeTimestamp <= exchangeFreshnessLimit(entry.timeframe, ttlMs)
    }

    private fun exchangeFreshnessLimit(timeframe: String, ttlMs: Long): Long {
        val frameMs = when (timeframe.trim().lowercase()) {
            "1d", "d", "day" -> 4L * 24L * 60L * 60L * 1000L
            else -> timeframe.trim().lowercase().removeSuffix("m").toLongOrNull()?.coerceAtLeast(1L)?.times(60_000L) ?: ttlMs
        }
        return maxOf(ttlMs, frameMs + ttlMs)
    }

    private const val MAX_FUTURE_CLOCK_SKEW_MS = 30_000L

    fun peek(sourceKey: String, symbol: String, timeframe: String): Entry? {
        val key = "${sourceKey.trim()}|${symbol.trim().uppercase()}|${timeframe.trim().lowercase()}"
        return entries[key]
    }

    fun hasEntries(): Boolean = entries.isNotEmpty()

    fun clear() {
        entries.clear()
        locks.clear()
    }
}
