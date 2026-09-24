package tr.borsatakip.v5.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Deneysel yedek/gecikmeli kaynaktır. Hiçbir zaman REALTIME olarak etiketlenmez.
 *
 * Üretim backend'i yokken yalnız kullanıcı deneysel sağlayıcıyı açıkça etkinleştirmişse çalışır.
 * Sembol evreni cache'de yoksa doviz.com BIST hisse listesinden best-effort olarak yenilenir.
 * Bu yol üretim/lisanslı BIST veri sağlayıcısının yerine geçmez.
 */
class YahooFallbackProvider(context: Context) : MarketDataProvider {
    private val settings = SettingsStore(context)
    override val id = "yahoo_fallback"
    override val displayName = "Yahoo Finance • YEDEK / GECİKMELİ"

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> = coroutineScope {
        val symbols = loadExperimentalSymbols()
        require(symbols.isNotEmpty()) {
            "Deneysel BIST sembol evreni alınamadı. Production backend yapılandırın veya ağ bağlantısını kontrol edip tekrar deneyin."
        }

        val semaphore = Semaphore(6)
        var done = 0
        symbols.map { symbol ->
            async(Dispatchers.IO) {
                val stock = semaphore.withPermit {
                    ManualScanPauseGate.awaitIfPaused()
                    fetch(symbol)
                }
                synchronized(this@YahooFallbackProvider) {
                    done++
                    onProgress(done, symbols.size)
                }
                stock
            }
        }.awaitAll().filterNotNull()
    }

    data class DisplayQuoteResult(val stock: Stock?, val error: String? = null)

    /**
     * Takip/favori ekranı için yalnız son quote'u alır. Günlük 220 mum şartına bağlı değildir.
     * Bu yol karar/tarama verisi değildir; yalnız görüntüleme içindir.
     */
    suspend fun fetchDisplayQuoteOnly(symbol: String): DisplayQuoteResult = withContext(Dispatchers.IO) {
        val normalized = symbol.trim().uppercase()
        if (!normalized.matches(SYMBOL_REGEX)) return@withContext DisplayQuoteResult(null, "Geçersiz sembol")
        val attempt = fetchDelayedQuoteResult("$normalized.IS")
        val q = attempt.quote ?: return@withContext DisplayQuoteResult(null, attempt.error ?: "Yahoo quote alınamadı")
        DisplayQuoteResult(quoteOnlyStock(normalized, q), null)
    }

    /** Piyasa özet kartları için Yahoo'nun ham ticker adlarını dener (ör. TRY=X). */
    suspend fun fetchExternalQuote(symbolLabel: String, providerTickers: List<String>): DisplayQuoteResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        for (ticker in providerTickers.map { it.trim() }.filter { it.isNotBlank() }.distinct()) {
            val attempt = fetchDelayedQuoteResult(ticker)
            val q = attempt.quote
            if (q != null) return@withContext DisplayQuoteResult(quoteOnlyStock(symbolLabel.trim().uppercase(), q), null)
            attempt.error?.let { errors += "$ticker: $it" }
        }
        DisplayQuoteResult(null, errors.joinToString(" • ").ifBlank { "Yahoo quote alınamadı" })
    }

    /**
     * Genel fetchOne önce quote'u alır. Günlük history başarısız olsa bile geçerli quote kaybolmaz.
     * Böylece takip ekranı için fiyat, analiz history'sine yanlışlıkla bağımlı olmaz.
     */
    override suspend fun fetchOne(symbol: String): Stock? = withContext(Dispatchers.IO) {
        val normalized = symbol.trim().uppercase()
        val quoteResult = fetchDisplayQuoteOnly(normalized)
        val quoteStock = quoteResult.stock ?: return@withContext null
        val daily = fetch(normalized) ?: return@withContext quoteStock
        daily.copy(
            quotePrice = quoteStock.quotePrice,
            previousClose = quoteStock.previousClose ?: daily.previousClose,
            dataTimestamp = quoteStock.dataTimestamp,
            receivedAt = quoteStock.receivedAt,
            receivedElapsedRealtime = quoteStock.receivedElapsedRealtime,
            companyName = quoteStock.companyName ?: daily.companyName,
            bid = null,
            ask = null
        )
    }

    override suspend fun listSymbols(): List<String> = loadExperimentalSymbols()

    override suspend fun fetchHistory(symbol: String, fromTime: Long, toTime: Long, intervalMinutes: Int): List<Candle> = withContext(Dispatchers.IO) {
        if (fromTime <= 0L || toTime <= fromTime) return@withContext emptyList()
        val interval = when {
            intervalMinutes <= 1 -> "1m"
            intervalMinutes <= 5 -> "5m"
            intervalMinutes <= 15 -> "15m"
            intervalMinutes <= 30 -> "30m"
            else -> "60m"
        }
        val encoded = URLEncoder.encode("${symbol.trim().uppercase()}.IS", "UTF-8")
        val windows = if (interval == "1m") {
            boundedHistoryWindows(fromTime, toTime, ONE_MINUTE_HISTORY_CHUNK_MS)
        } else {
            listOf(fromTime to toTime)
        }
        windows
            .flatMap { (windowStart, windowEnd) -> fetchHistoryWindow(encoded, windowStart, windowEnd, interval) }
            .asSequence()
            .filter { it.timestamp in fromTime..toTime }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
            .toList()
    }

    private fun fetchHistoryWindow(encodedSymbol: String, fromTime: Long, toTime: Long, interval: String): List<Candle> {
        val period1 = fromTime / 1000L
        val period2 = toTime / 1000L
        var con: HttpURLConnection? = null
        return try {
            con = URL("https://query1.finance.yahoo.com/v8/finance/chart/$encodedSymbol?period1=$period1&period2=$period2&interval=$interval&events=history")
                .openConnection() as HttpURLConnection
            con.connectTimeout = 7_000
            con.readTimeout = 7_000
            con.setRequestProperty("User-Agent", "Mozilla/5.0 BorsaTakip/${BuildConfig.VERSION_NAME} Android")
            if (con.responseCode !in 200..299) return emptyList()
            val body = con.inputStream.bufferedReader().use { it.readText() }
            parseCandlesOnly(body)
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { con?.disconnect() }
        }
    }

    private fun boundedHistoryWindows(fromTime: Long, toTime: Long, maxWindowMs: Long): List<Pair<Long, Long>> {
        if (fromTime <= 0L || toTime <= fromTime || maxWindowMs <= 0L) return emptyList()
        val out = mutableListOf<Pair<Long, Long>>()
        var cursor = fromTime
        while (cursor < toTime) {
            val end = minOf(toTime, cursor + maxWindowMs)
            out += cursor to end
            if (end >= toTime) break
            cursor = end + 1L
        }
        return out
    }

    override suspend fun fetchDailyHistory(symbol: String, maximumRange: Boolean): List<Candle> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val from = if (maximumRange) 1L else now - 370L * 24 * 60 * 60 * 1000
        if (now <= from) return@withContext emptyList()
        val encoded = URLEncoder.encode("${symbol.trim().uppercase()}.IS", "UTF-8")
        val period1 = from / 1000L
        val period2 = now / 1000L
        var con: HttpURLConnection? = null
        try {
            con = URL("https://query1.finance.yahoo.com/v8/finance/chart/$encoded?period1=$period1&period2=$period2&interval=1d&events=history")
                .openConnection() as HttpURLConnection
            con.connectTimeout = 7_000
            con.readTimeout = 7_000
            con.setRequestProperty("User-Agent", "Mozilla/5.0 BorsaTakip/${BuildConfig.VERSION_NAME} Android")
            if (con.responseCode !in 200..299) return@withContext emptyList()
            val body = con.inputStream.bufferedReader().use { it.readText() }
            parseCandlesOnly(body).sortedBy { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        } finally { runCatching { con?.disconnect() } }
    }

    private suspend fun loadExperimentalSymbols(): List<String> = withContext(Dispatchers.IO) {
        val cached = settings.cachedBistSymbols
            .map { it.trim().uppercase() }
            .filter { it.matches(SYMBOL_REGEX) }
            .distinct()
            .sorted()
        if (cached.isNotEmpty()) return@withContext cached

        val discovered = discoverBistSymbolsFromPublicList()
        if (discovered.isNotEmpty()) {
            settings.cachedBistSymbols = discovered.toSet()
            settings.cachedBistSymbolCount = discovered.size
            settings.cachedBistSymbolsFetchedAt = System.currentTimeMillis()
            settings.cachedBistSymbolsProviderId = "doviz.com:bist-universe-experimental"
        }
        discovered
    }

    /**
     * Experimental bootstrap only. No price/quote is taken from this page; it is used only to discover
     * publicly listed BIST ticker codes when there is no production symbol cache yet.
     */
    private fun discoverBistSymbolsFromPublicList(): List<String> {
        var con: HttpURLConnection? = null
        return try {
            con = URL("https://m.doviz.com/borsa/hisseler").openConnection() as HttpURLConnection
            con.requestMethod = "GET"
            con.connectTimeout = 8_000
            con.readTimeout = 12_000
            con.instanceFollowRedirects = true
            con.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            con.setRequestProperty("User-Agent", "Mozilla/5.0 BorsaTakip/${BuildConfig.VERSION_NAME} Android")
            if (con.responseCode !in 200..299) return emptyList()

            val html = con.inputStream.bufferedReader().use { it.readText() }
            if (html.isBlank()) return emptyList()

            val regex = Regex("(?:https://borsa\\.doviz\\.com)?/hisseler/([a-z0-9_]{3,12})-", RegexOption.IGNORE_CASE)
            regex.findAll(html)
                .mapNotNull { match ->
                    match.groupValues.getOrNull(1)
                        ?.trim()
                        ?.uppercase()
                        ?.takeIf { it.matches(SYMBOL_REGEX) }
                }
                .distinct()
                .sorted()
                .toList()
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { con?.disconnect() }
        }
    }


    private data class DelayedQuote(
        val price: Double,
        val previousClose: Double?,
        val timestamp: Long,
        val companyName: String? = null,
        val currency: String? = null
    )

    private data class DelayedQuoteAttempt(
        val quote: DelayedQuote?,
        val error: String? = null,
        val retryable: Boolean = false
    )

    private fun quoteOnlyStock(symbol: String, q: DelayedQuote): Stock = Stock(
        symbol = symbol,
        companyName = q.companyName,
        candles = emptyList(),
        source = displayName,
        dataTimestamp = q.timestamp,
        isRealtime = false,
        delaySeconds = null,
        currentSessionIncluded = true,
        receivedAt = System.currentTimeMillis(),
        receivedElapsedRealtime = SystemClock.elapsedRealtime(),
        quotePrice = q.price,
        previousClose = q.previousClose,
        currency = q.currency,
        market = "DISPLAY_ONLY",
        marketDataMetadata = MarketDataQuality.metadata(
            providerId = id, source = displayName, marketTimestamp = q.timestamp,
            receivedAt = System.currentTimeMillis(), isLive = false, delaySeconds = null,
            lastSuccessfulUpdateAt = System.currentTimeMillis(), fallback = true,
            reason = "Yahoo fallback hiçbir koşulda LIVE kabul edilmez."
        )
    )

    /** Quote isteğinde 429/5xx ve geçici ağ hataları için sınırlı retry/backoff uygular. */
    private suspend fun fetchDelayedQuoteResult(providerTicker: String): DelayedQuoteAttempt {
        var last = DelayedQuoteAttempt(null, "Yahoo quote alınamadı")
        repeat(MAX_QUOTE_ATTEMPTS) { index ->
            last = fetchDelayedQuoteOnce(providerTicker)
            if (last.quote != null || !last.retryable) return last
            if (index < MAX_QUOTE_ATTEMPTS - 1) delay(QUOTE_RETRY_BASE_MS * (index + 1))
        }
        return last
    }

    private fun fetchDelayedQuoteOnce(providerTicker: String): DelayedQuoteAttempt {
        val encoded = URLEncoder.encode(providerTicker, "UTF-8")
        var con: HttpURLConnection? = null
        return try {
            con = URL("https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=5d&interval=1m&events=history")
                .openConnection() as HttpURLConnection
            con.connectTimeout = 7_000
            con.readTimeout = 7_000
            con.setRequestProperty("User-Agent", "Mozilla/5.0 BorsaTakip/${BuildConfig.VERSION_NAME} Android")
            val code = con.responseCode
            if (code !in 200..299) {
                return DelayedQuoteAttempt(null, "Yahoo HTTP $code", retryable = code == 429 || code in 500..599)
            }
            val body = con.inputStream.bufferedReader().use { it.readText() }
            val r = JSONObject(body).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)
                ?: return DelayedQuoteAttempt(null, "Yahoo cevap yapısı geçersiz")
            val meta = r.optJSONObject("meta")
            val timestamps = r.optJSONArray("timestamp")
                ?: return DelayedQuoteAttempt(null, "Yahoo timestamp yok")
            val quote = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
                ?: return DelayedQuoteAttempt(null, "Yahoo quote dizisi yok")
            val closes = quote.optJSONArray("close")
                ?: return DelayedQuoteAttempt(null, "Yahoo kapanış dizisi yok")
            var lastIndex = -1
            for (i in minOf(timestamps.length(), closes.length()) - 1 downTo 0) {
                if (!closes.isNull(i)) { lastIndex = i; break }
            }
            if (lastIndex < 0) return DelayedQuoteAttempt(null, "Yahoo geçerli son fiyat yok")
            val close = closes.optDouble(lastIndex, Double.NaN)
            val metaPrice = meta?.optDouble("regularMarketPrice", Double.NaN)
            val price = metaPrice?.takeIf { it.isFinite() && it > 0.0 }
                ?: close.takeIf { it.isFinite() && it > 0.0 }
                ?: return DelayedQuoteAttempt(null, "Yahoo fiyat geçersiz")
            val previousClose = listOf(
                meta?.optDouble("chartPreviousClose", Double.NaN),
                meta?.optDouble("previousClose", Double.NaN)
            ).firstOrNull { it != null && it.isFinite() && it > 0.0 }
            val timestamp = timestamps.optLong(lastIndex, 0L) * 1000L
            if (timestamp <= 0L) return DelayedQuoteAttempt(null, "Yahoo veri zamanı geçersiz")
            val companyName = listOf(meta?.optString("longName"), meta?.optString("shortName"))
                .firstOrNull { !it.isNullOrBlank() }
            DelayedQuoteAttempt(
                DelayedQuote(price, previousClose, timestamp, companyName, meta?.optString("currency")?.takeIf { it.isNotBlank() }),
                null
            )
        } catch (e: Exception) {
            DelayedQuoteAttempt(null, "Yahoo ${e.javaClass.simpleName}: ${e.message ?: "ağ hatası"}", retryable = true)
        } finally {
            runCatching { con?.disconnect() }
        }
    }

    private fun fetch(symbol: String): Stock? {
        val encoded = URLEncoder.encode("$symbol.IS", "UTF-8")
        val con = URL("https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=1y&interval=1d&events=history")
            .openConnection() as HttpURLConnection
        con.connectTimeout = 7_000
        con.readTimeout = 7_000
        con.setRequestProperty("User-Agent", "Mozilla/5.0 BorsaTakip/${BuildConfig.VERSION_NAME} Android")
        return try {
            if (con.responseCode !in 200..299) return null
            val body = con.inputStream.bufferedReader().use { it.readText() }
            val receivedAt = System.currentTimeMillis()
            val receivedElapsed = SystemClock.elapsedRealtime()
            parse(body, symbol, receivedAt, receivedElapsed)
        } catch (_: Exception) {
            null
        } finally {
            con.disconnect()
        }
    }


    private fun parseCandlesOnly(json: String): List<Candle> {
        val r = JSONObject(json).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) ?: return emptyList()
        val ts = r.optJSONArray("timestamp") ?: return emptyList()
        val q = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return emptyList()
        val o = q.optJSONArray("open") ?: return emptyList()
        val h = q.optJSONArray("high") ?: return emptyList()
        val l = q.optJSONArray("low") ?: return emptyList()
        val c = q.optJSONArray("close") ?: return emptyList()
        val v = q.optJSONArray("volume") ?: return emptyList()
        val out = mutableListOf<Candle>()
        for (i in 0 until ts.length()) {
            if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i) || v.isNull(i)) continue
            val candle = Candle(ts.getLong(i) * 1000L, o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i), v.getDouble(i))
            if (listOf(candle.open, candle.high, candle.low, candle.close, candle.volume).any { !it.isFinite() }) continue
            if (candle.high < candle.low || candle.close <= 0.0 || candle.volume < 0.0) continue
            out += candle
        }
        return out
    }

    private fun parse(json: String, fallback: String, receivedAt: Long, receivedElapsed: Long): Stock? {
        val r = JSONObject(json).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val ts = r.optJSONArray("timestamp") ?: return null
        val q = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return null
        val o = q.optJSONArray("open") ?: return null
        val h = q.optJSONArray("high") ?: return null
        val l = q.optJSONArray("low") ?: return null
        val c = q.optJSONArray("close") ?: return null
        val v = q.optJSONArray("volume") ?: return null
        val candles = mutableListOf<Candle>()
        for (i in 0 until ts.length()) {
            if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i) || v.isNull(i)) continue
            val candle = Candle(ts.getLong(i) * 1000, o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i), v.getDouble(i))
            if (listOf(candle.open, candle.high, candle.low, candle.close, candle.volume).any { !it.isFinite() }) continue
            if (candle.high < candle.low || candle.close <= 0.0 || candle.volume < 0.0) continue
            candles += candle
        }
        if (candles.size < 220) return null
        val meta = r.optJSONObject("meta")
        val previousClose = listOf(
            meta?.optDouble("chartPreviousClose", Double.NaN),
            meta?.optDouble("previousClose", Double.NaN)
        ).firstOrNull { it != null && it.isFinite() && it > 0.0 }
        return Stock(
            symbol = fallback,
            companyName = meta?.optString("longName")?.takeIf { it.isNotBlank() },
            candles = candles.sortedBy { it.timestamp },
            source = displayName,
            dataTimestamp = candles.maxOf { it.timestamp },
            isRealtime = false,
            delaySeconds = null,
            currentSessionIncluded = false,
            receivedAt = receivedAt,
            receivedElapsedRealtime = receivedElapsed,
            quotePrice = meta?.optDouble("regularMarketPrice", Double.NaN)?.takeIf { it.isFinite() && it > 0.0 },
            previousClose = previousClose,
            marketDataMetadata = MarketDataQuality.metadata(
                providerId = id, source = displayName, marketTimestamp = candles.maxOf { it.timestamp },
                receivedAt = receivedAt, isLive = false, delaySeconds = null,
                lastSuccessfulUpdateAt = receivedAt, fallback = true,
                reason = "Yahoo fallback gecikmeli/yedek kaynaktır."
            )
        )
    }

    companion object {
        private val SYMBOL_REGEX = Regex("[A-Z0-9_]{3,12}")
        private const val ONE_MINUTE_HISTORY_CHUNK_MS = 5L * 24 * 60 * 60 * 1000
        private const val MAX_QUOTE_ATTEMPTS = 3
        private const val QUOTE_RETRY_BASE_MS = 350L
    }
}
