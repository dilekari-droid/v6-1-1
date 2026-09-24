package tr.borsatakip.v5.data

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tr.borsatakip.v5.analysis.OhlcvResampler
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * Seçilen timeframe'i taramanın gerçek OHLCV periyoduna bağlar.
 *
 * - 1/5/15/30/60 DK: sağlayıcı doğrudan destekliyorsa doğrudan alınır.
 * - 3/10 DK ve özel dakikalar: gerçek 1 DK mumlardan yeniden örneklenir.
 * - 1 GÜN: yalnız gerçek günlük (`1d`) geçmiş kullanılır; 240 DK günlük yerine geçmez.
 *
 * Bir sembolün seçilen periyot verisi yoksa o sembol sonuç üretmez.
 */
class IntervalMarketDataProvider(
    private val delegate: MarketDataProvider,
    intervalMinutes: Int,
    private val minimumBars: Int = RealTimeIntegrityPolicy.MIN_HISTORY_BARS,
    private val sessionCloseMode: Boolean = false,
    private val delayedObservationMode: Boolean = false
) : MarketDataProvider, ScanDiagnosticsSource, ScanProgressMetadataSource {
    val timeframe: ScanTimeframe = ScanTimeframe.fromStored(intervalMinutes)
    val intervalMinutes: Int get() = timeframe.storedMinutes

    init {
        (delegate as? ScanBatchTimeframeConfigurable)?.configureScanBatchTimeframe(timeframe.storedMinutes, minimumBars)
    }

    @Volatile private var diagnostics = ProviderScanDiagnostics()
    @Volatile private var lastScanSymbol: String? = null

    override fun currentScanSymbol(): String? = lastScanSymbol

    override val id: String get() = "${delegate.id}_${timeframe.cacheKey.lowercase()}"
    override val displayName: String get() = "${delegate.displayName} • ${timeframe.label}"
    override fun mtfCacheSourceKey(): String = "${delegate.mtfCacheSourceKey()}|scan_tf=${timeframe.cacheKey.lowercase()}"

    override fun scanDiagnostics(): ProviderScanDiagnostics {
        val own = diagnostics
        val base = (delegate as? ScanDiagnosticsSource)?.scanDiagnostics() ?: ProviderScanDiagnostics()
        return ProviderScanDiagnostics(
            noDataSymbols = (base.noDataSymbols + own.noDataSymbols).distinct(),
            errorSymbols = (base.errorSymbols + own.errorSymbols).distinct(),
            messages = base.messages + own.messages
        )
    }

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> {
        diagnostics = ProviderScanDiagnostics()
        if (sessionCloseMode) {
            return scanSessionClose(onProgress)
        }
        if (delayedObservationMode) {
            return scanDelayedObservation(onProgress)
        }
        val delayedFastPath = delegate.id == "yahoo_fallback" ||
            (delegate as? ProviderRouter)?.shouldUseDelayedFallbackFastPath() == true
        if (!timeframe.isDaily && delayedFastPath) {
            return scanDelayedObservation(onProgress)
        }
        // Delegate ilerlemesi temel quote/history hazırlığıdır. Kullanıcıya gösterilen nihai ilerleme,
        // seçilen timeframe OHLCV'sinin gerçekten işlendiği aşamadan hesaplanır. Delegate'de veri
        // alınamayan semboller de "işlenmiş ama başarısız" kabul edilerek toplam evrenden düşmez.
        var delegateProcessed = 0
        var delegateTotal = 0
        val base = delegate.scan { done, total ->
            delegateProcessed = done.coerceAtLeast(0)
            delegateTotal = total.coerceAtLeast(0)
            lastScanSymbol = (delegate as? ScanProgressMetadataSource)?.currentScanSymbol() ?: lastScanSymbol
        }
        if (base.isEmpty()) {
            if (delegateTotal > 0) onProgress(delegateProcessed.coerceAtMost(delegateTotal), delegateTotal)
            return emptyList()
        }

        val semaphore = Semaphore(MAX_CONCURRENCY)
        val noData = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val messages = linkedMapOf<String, String>()
        val completed = AtomicInteger(0)
        val total = delegateTotal.takeIf { it > 0 } ?: base.size
        val baseMissing = (total - base.size).coerceAtLeast(0)
        onProgress(baseMissing, total)

        val rows = supervisorScope {
            base.map { stock ->
                async(Dispatchers.IO) {
                    try {
                        semaphore.withPermit {
                            ManualScanPauseGate.awaitIfPaused()
                            reframe(stock)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (pe: ProviderException) {
                        synchronized(messages) {
                            val msg = pe.message ?: pe.code.name
                            messages[stock.symbol] = msg
                            if (pe.code == ProviderFailureCode.EMPTY_DATA || pe.code == ProviderFailureCode.STALE_DATA || pe.code == ProviderFailureCode.BIST_HISTORY_ERROR) {
                                noData += stock.symbol
                            } else {
                                errors += stock.symbol
                            }
                        }
                        logSymbolFailure(stock.symbol, pe.code.name, pe.message)
                        null
                    } catch (t: Throwable) {
                        synchronized(messages) {
                            errors += stock.symbol
                            messages[stock.symbol] = t.message ?: t.javaClass.simpleName
                        }
                        logSymbolFailure(stock.symbol, "DATA_ERROR", t.message)
                        null
                    } finally {
                        lastScanSymbol = stock.symbol
                        val done = (baseMissing + completed.incrementAndGet()).coerceAtMost(total)
                        onProgress(done, total)
                    }
                }
            }.awaitAll().filterNotNull()
        }

        diagnostics = ProviderScanDiagnostics(noData.distinct(), errors.distinct(), messages.toMap())
        if (rows.isEmpty()) {
            val detail = messages.values.firstOrNull() ?: "Seçilen periyot için yeterli OHLCV alınamadı."
            throw ProviderException(ProviderFailureCode.EMPTY_DATA, "${timeframe.label} tarama yapılamadı. $detail")
        }
        return rows
    }

    /**
     * Production backend seans dışındayken canlı quote zorunluluğuna takılmadan yalnız kapanmış
     * OHLCV mumlarını kullanır. Seans sonrası aynı güne ait kapanış yoksa veri eskiymiş gibi
     * sessizce kabul edilmez; sembol sonuç üretmez.
     */
    private suspend fun scanSessionClose(onProgress: (done: Int, total: Int) -> Unit): List<Stock> {
        val symbols = delegate.listSymbols()
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
        if (symbols.isEmpty()) return emptyList()

        val semaphore = Semaphore(MAX_CONCURRENCY)
        val noData = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val messages = linkedMapOf<String, String>()
        val completed = AtomicInteger(0)
        val total = symbols.size
        onProgress(0, total)

        val rows = supervisorScope {
            symbols.map { symbol ->
                async(Dispatchers.IO) {
                    try {
                        semaphore.withPermit {
                            ManualScanPauseGate.awaitIfPaused()
                            buildSessionCloseStock(symbol)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (pe: ProviderException) {
                        synchronized(messages) {
                            val msg = pe.message ?: pe.code.name
                            messages[symbol] = msg
                            if (pe.code == ProviderFailureCode.EMPTY_DATA || pe.code == ProviderFailureCode.STALE_DATA || pe.code == ProviderFailureCode.BIST_HISTORY_ERROR) {
                                noData += symbol
                            } else {
                                errors += symbol
                            }
                        }
                        logSymbolFailure(symbol, pe.code.name, pe.message, "session-close/${timeframe.apiInterval}")
                        null
                    } catch (t: Throwable) {
                        synchronized(messages) {
                            errors += symbol
                            messages[symbol] = t.message ?: t.javaClass.simpleName
                        }
                        logSymbolFailure(symbol, "DATA_ERROR", t.message, "session-close/${timeframe.apiInterval}")
                        null
                    } finally {
                        lastScanSymbol = symbol
                        onProgress(completed.incrementAndGet().coerceAtMost(total), total)
                    }
                }
            }.awaitAll().filterNotNull()
        }

        diagnostics = ProviderScanDiagnostics(noData.distinct(), errors.distinct(), messages.toMap())
        if (rows.isEmpty()) {
            val detail = messages.values.firstOrNull() ?: "Son tamamlanmış seans için ${timeframe.label} OHLCV alınamadı."
            throw ProviderException(ProviderFailureCode.EMPTY_DATA, "${timeframe.label} kapanış taraması yapılamadı. $detail")
        }
        return rows
    }

    private suspend fun buildSessionCloseStock(symbol: String): Stock {
        val now = System.currentTimeMillis()
        val selected = if (timeframe.isDaily) {
            OhlcvResampler.sanitize(fetchDailyWithRetry(symbol))
        } else {
            val fromTime = now - lookbackMs(timeframe.storedMinutes, minimumBars)
            if (timeframe.storedMinutes in DIRECT_INTERVALS) {
                OhlcvResampler.sanitize(fetchHistoryWithRetry(symbol, fromTime, now, timeframe.storedMinutes))
            } else {
                val oneMinute = OhlcvResampler.sanitize(fetchHistoryWithRetry(symbol, fromTime, now, 1))
                OhlcvResampler.aggregate(oneMinute, 1, timeframe.storedMinutes)
            }
        }
        if (selected.size < minimumBars) {
            throw ProviderException(
                ProviderFailureCode.EMPTY_DATA,
                "$symbol için ${timeframe.label} periyotta en az $minimumBars kapanmış mum gerekli; ${selected.size} alındı."
            )
        }

        val candles = selected.takeLast(MAX_ANALYSIS_BARS)
        val last = candles.last()
        val frameMs = if (timeframe.isDaily) 1L else timeframe.storedMinutes * 60_000L
        val closeVerdict = BistSessionClosePolicy.validateLastClosedBar(last.timestamp, frameMs, now)
        if (!closeVerdict.accepted) {
            throw ProviderException(ProviderFailureCode.STALE_DATA, "$symbol • ${closeVerdict.reason}")
        }
        val receivedAt = System.currentTimeMillis()
        val sessionDate = closeVerdict.sessionDate
        val previousSessionClose = sessionDate?.let { currentDate ->
            candles.asReversed()
                .firstOrNull { candle ->
                    java.time.Instant.ofEpochMilli(candle.timestamp)
                        .atZone(java.time.ZoneId.of("Europe/Istanbul"))
                        .toLocalDate() < currentDate
                }
                ?.close
        }
        val providerLabel = delegate.displayName.ifBlank { "Veri sağlayıcı" }
        val sourceLabel = "$providerLabel • ${timeframe.label} • KAPANIŞ"
        return Stock(
            symbol = symbol,
            companyName = null,
            candles = candles,
            source = sourceLabel,
            dataTimestamp = last.timestamp,
            isRealtime = false,
            delaySeconds = null,
            currentSessionIncluded = false,
            receivedAt = receivedAt,
            receivedElapsedRealtime = SystemClock.elapsedRealtime(),
            quotePrice = last.close,
            market = "BIST",
            historySymbol = symbol,
            interval = timeframe.apiInterval,
            exchangeTimezone = "Europe/Istanbul",
            sessionId = closeVerdict.sessionDate?.toString(),
            lastBarTime = last.timestamp,
            lastBarClosed = true,
            previousClose = previousSessionClose,
            marketDataMetadata = MarketDataQuality.metadata(
                providerId = delegate.id,
                source = sourceLabel,
                marketTimestamp = last.timestamp,
                receivedAt = receivedAt,
                isLive = false,
                delaySeconds = null,
                lastSuccessfulUpdateAt = receivedAt,
                fallback = false,
                reason = "Seans dışı kapanış taraması • ${closeVerdict.reason}"
            )
        )
    }

    /**
     * Yahoo yedek kaynağında intraday tarama günlük bootstrap fiyatlarını indirmeden doğrudan
     * seçili OHLCV periyodunu alır. Sonuç kasıtlı olarak isRealtime=false kalır.
     */
    private suspend fun scanDelayedObservation(onProgress: (done: Int, total: Int) -> Unit): List<Stock> {
        val symbols = delegate.listSymbols()
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
        if (symbols.isEmpty()) return emptyList()

        val semaphore = Semaphore(MAX_CONCURRENCY)
        val noData = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val messages = linkedMapOf<String, String>()
        val completed = AtomicInteger(0)
        val total = symbols.size
        onProgress(0, total)

        val rows = supervisorScope {
            symbols.map { symbol ->
                async(Dispatchers.IO) {
                    try {
                        semaphore.withPermit {
                            ManualScanPauseGate.awaitIfPaused()
                            buildDelayedObservationStock(symbol)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (pe: ProviderException) {
                        synchronized(messages) {
                            val msg = pe.message ?: pe.code.name
                            messages[symbol] = msg
                            if (pe.code == ProviderFailureCode.EMPTY_DATA || pe.code == ProviderFailureCode.STALE_DATA || pe.code == ProviderFailureCode.BIST_HISTORY_ERROR) {
                                noData += symbol
                            } else {
                                errors += symbol
                            }
                        }
                        logSymbolFailure(symbol, pe.code.name, pe.message, "yahoo-delayed/${timeframe.apiInterval}")
                        null
                    } catch (t: Throwable) {
                        synchronized(messages) {
                            errors += symbol
                            messages[symbol] = t.message ?: t.javaClass.simpleName
                        }
                        logSymbolFailure(symbol, "DATA_ERROR", t.message, "yahoo-delayed/${timeframe.apiInterval}")
                        null
                    } finally {
                        lastScanSymbol = symbol
                        onProgress(completed.incrementAndGet().coerceAtMost(total), total)
                    }
                }
            }.awaitAll().filterNotNull()
        }

        diagnostics = ProviderScanDiagnostics(noData.distinct(), errors.distinct(), messages.toMap())
        if (rows.isEmpty()) {
            val detail = messages.values.firstOrNull() ?: "Yahoo Finance yedek kaynağından seçilen periyot OHLCV alınamadı."
            throw ProviderException(ProviderFailureCode.EMPTY_DATA, "${timeframe.label} tarama yapılamadı. $detail")
        }
        return rows
    }

    private suspend fun buildDelayedObservationStock(symbol: String): Stock {
        val now = System.currentTimeMillis()
        val selected = if (timeframe.isDaily) {
            val daily = OhlcvResampler.sanitize(fetchDailyWithRetry(symbol))
            if (BistSessionClosePolicy.phase(now) == BistSessionClosePolicy.Phase.OPEN) {
                val today = BistSessionClosePolicy.localDate(now)
                daily.filter { BistSessionClosePolicy.localDate(it.timestamp) != today }
            } else daily
        } else {
            val fromTime = now - lookbackMs(timeframe.storedMinutes, minimumBars)
            val raw = if (timeframe.storedMinutes in DIRECT_INTERVALS) {
                OhlcvResampler.sanitize(fetchHistoryWithRetry(symbol, fromTime, now, timeframe.storedMinutes))
            } else {
                val oneMinute = OhlcvResampler.sanitize(fetchHistoryWithRetry(symbol, fromTime, now, 1))
                OhlcvResampler.aggregate(oneMinute, 1, timeframe.storedMinutes)
            }
            RealtimeClosedBarPolicy.selectClosedIntraday(raw, timeframe.storedMinutes)
        }
        if (selected.size < minimumBars) {
            throw ProviderException(ProviderFailureCode.EMPTY_DATA, "$symbol için ${timeframe.label} periyotta en az $minimumBars kapanmış mum gerekli; ${selected.size} alındı.")
        }
        val candles = selected.takeLast(MAX_ANALYSIS_BARS)
        val last = candles.last()
        val receivedAt = System.currentTimeMillis()
        val sourceLabel = "${delegate.displayName} • ${timeframe.label} • GECİKMELİ GÖZLEM"
        return Stock(
            symbol = symbol, companyName = null, candles = candles, source = sourceLabel,
            dataTimestamp = last.timestamp, isRealtime = false, delaySeconds = null, currentSessionIncluded = false,
            receivedAt = receivedAt, receivedElapsedRealtime = SystemClock.elapsedRealtime(),
            quotePrice = last.close, market = "BIST", historySymbol = symbol, interval = timeframe.apiInterval,
            exchangeTimezone = "Europe/Istanbul", lastBarTime = last.timestamp, lastBarClosed = true,
            marketDataMetadata = MarketDataQuality.metadata(
                providerId = delegate.id, source = sourceLabel, marketTimestamp = last.timestamp, receivedAt = receivedAt,
                isLive = false, delaySeconds = null, lastSuccessfulUpdateAt = receivedAt, fallback = false,
                reason = "Canlı quote doğrulanmadı; yalnız kapanmış ${timeframe.label} OHLCV ile gözlem analizi"
            )
        )
    }

    override suspend fun fetchOne(symbol: String): Stock? = when {
        sessionCloseMode -> buildSessionCloseStock(symbol.trim().uppercase())
        delayedObservationMode -> buildDelayedObservationStock(symbol.trim().uppercase())
        else -> delegate.fetchOne(symbol)?.let { reframe(it) }
    }
    override suspend fun listSymbols(): List<String> = delegate.listSymbols()

    override suspend fun fetchHistory(symbol: String, fromTime: Long, toTime: Long, intervalMinutes: Int): List<Candle> =
        delegate.fetchHistory(symbol, fromTime, toTime, intervalMinutes)

    override suspend fun fetchDailyHistory(symbol: String, maximumRange: Boolean): List<Candle> =
        delegate.fetchDailyHistory(symbol, maximumRange)

    private suspend fun reframe(stock: Stock): Stock {
        val selected = if (timeframe.isDaily) {
            val existing = OhlcvResampler.sanitize(stock.candles)
            if (stock.interval.equals("1d", true) && existing.size >= minimumBars) existing
            else OhlcvResampler.sanitize(fetchDailyWithRetry(stock.symbol))
        } else {
            val existing = OhlcvResampler.sanitize(stock.candles)
            val existingInterval = stock.interval.trim().lowercase()
            val directPrefetched = timeframe.storedMinutes in DIRECT_INTERVALS &&
                existingInterval == timeframe.apiInterval.lowercase() && existing.size >= minimumBars
            val oneMinutePrefetched = timeframe.storedMinutes !in DIRECT_INTERVALS &&
                existingInterval == "1m" && existing.size >= minimumBars
            when {
                directPrefetched -> existing
                oneMinutePrefetched -> OhlcvResampler.aggregate(existing, 1, timeframe.storedMinutes)
                else -> {
                    val toTime = maxOf(System.currentTimeMillis(), stock.exchangeTimestamp)
                    val fromTime = toTime - lookbackMs(timeframe.storedMinutes, minimumBars)
                    if (timeframe.storedMinutes in DIRECT_INTERVALS) {
                        OhlcvResampler.sanitize(fetchHistoryWithRetry(stock.symbol, fromTime, toTime, timeframe.storedMinutes))
                    } else {
                        val oneMinute = OhlcvResampler.sanitize(fetchHistoryWithRetry(stock.symbol, fromTime, toTime, 1))
                        OhlcvResampler.aggregate(oneMinute, 1, timeframe.storedMinutes)
                    }
                }
            }
        }

        val usableSelected = if (!timeframe.isDaily && stock.isRealtime) {
            // A provider may include the currently-forming candle. Keep the quote live, but feed
            // indicators only completed candles so a mid-candle scan is deterministic and safe.
            RealtimeClosedBarPolicy.selectClosedIntraday(selected, timeframe.storedMinutes)
        } else selected

        if (usableSelected.size < minimumBars) {
            throw ProviderException(
                ProviderFailureCode.EMPTY_DATA,
                "${stock.symbol} için ${timeframe.label} periyotta en az $minimumBars kapanmış mum gerekli; ${usableSelected.size} alındı."
            )
        }

        val candles = usableSelected.takeLast(MAX_ANALYSIS_BARS)
        val last = candles.last()
        if (!timeframe.isDaily && stock.isRealtime) {
            val barAgeMs = stock.exchangeTimestamp - last.timestamp
            val maxAllowedBarAgeMs = RealtimeClosedBarPolicy.maximumClosedBarAgeMs(timeframe.storedMinutes)
            if (barAgeMs < -RealTimeIntegrityPolicy.MAX_FUTURE_CLOCK_SKEW_MS || barAgeMs > maxAllowedBarAgeMs) {
                throw ProviderException(
                    ProviderFailureCode.STALE_DATA,
                    "${stock.symbol} ${timeframe.label} OHLCV güncel seansı temsil etmiyor; son mum yaşı ${barAgeMs.coerceAtLeast(0L) / 1000L} sn."
                )
            }
        }

        return stock.copy(
            candles = candles,
            // Gecikmeli intraday kaynağın fiyat/zamanı seçili OHLCV'nin son mumundan gelir;
            // realtime bayrakları değiştirilmez ve kaynak hiçbir zaman anlıkmış gibi yükseltilmez.
            dataTimestamp = if (!timeframe.isDaily && !stock.isRealtime) last.timestamp else stock.dataTimestamp,
            quotePrice = if (!timeframe.isDaily && !stock.isRealtime) last.close else stock.quotePrice,
            interval = timeframe.apiInterval,
            lastBarTime = last.timestamp,
            // Seçilen timeframe için kapanış durumu explicit tutulur. Böylece açık mum,
            // doğrulanmış realtime sinyal kapısını geçemez.
            lastBarClosed = if (timeframe.isDaily) {
                stock.lastBarClosed ?: isBarClosed(last.timestamp, 24L * 60L * 60L * 1000L)
            } else if (stock.isRealtime) {
                true
            } else {
                isBarClosed(last.timestamp, timeframe.storedMinutes * 60_000L)
            }
        )
    }

    private fun isBarClosed(barTimestamp: Long, frameMs: Long, nowWall: Long = System.currentTimeMillis()): Boolean =
        barTimestamp > 0L && frameMs > 0L && nowWall >= barTimestamp + frameMs

    private suspend fun fetchHistoryWithRetry(symbol: String, fromTime: Long, toTime: Long, intervalMinutes: Int): List<Candle> {
        var last: Throwable? = null
        repeat(MAX_FETCH_ATTEMPTS) { index ->
            try {
                return delegate.fetchHistory(symbol, fromTime, toTime, intervalMinutes)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                last = t
                val retryable = (t as? ProviderException)?.code in RETRYABLE_CODES
                if (!retryable || index == MAX_FETCH_ATTEMPTS - 1) {
                    val code = (t as? ProviderException)?.code ?: ProviderFailureCode.BIST_HISTORY_ERROR
                    logSymbolFailure(symbol, code.name, t.message, "history-window/${intervalMinutes}m")
                    throw if (t is ProviderException) t else ProviderException(
                        ProviderFailureCode.BIST_HISTORY_ERROR,
                        "$symbol ${intervalMinutes} DK geçmiş verisi alınamadı: ${t.message ?: t.javaClass.simpleName}",
                        t
                    )
                }
                delay(RETRY_BASE_DELAY_MS * (index + 1))
            }
        }
        throw ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR, "$symbol geçmiş verisi alınamadı.", last)
    }

    private suspend fun fetchDailyWithRetry(symbol: String): List<Candle> {
        var last: Throwable? = null
        repeat(MAX_FETCH_ATTEMPTS) { index ->
            try {
                return delegate.fetchDailyHistory(symbol, maximumRange = false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                last = t
                val retryable = (t as? ProviderException)?.code in RETRYABLE_CODES
                if (!retryable || index == MAX_FETCH_ATTEMPTS - 1) {
                    val code = (t as? ProviderException)?.code ?: ProviderFailureCode.BIST_HISTORY_ERROR
                    logSymbolFailure(symbol, code.name, t.message, "daily-history/1d")
                    throw if (t is ProviderException) t else ProviderException(
                        ProviderFailureCode.BIST_HISTORY_ERROR,
                        "$symbol gerçek 1 GÜN geçmiş verisi alınamadı: ${t.message ?: t.javaClass.simpleName}",
                        t
                    )
                }
                delay(RETRY_BASE_DELAY_MS * (index + 1))
            }
        }
        throw ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR, "$symbol günlük geçmiş verisi alınamadı.", last)
    }

    private fun logSymbolFailure(symbol: String, code: String, message: String?, endpoint: String = "selected-timeframe") {
        Log.w(
            TAG,
            "SYMBOL=$symbol TIMEFRAME=${timeframe.apiInterval} DATA_SOURCE=${delegate.id} ENDPOINT=$endpoint " +
                "ERROR_CODE=$code ERROR_MESSAGE=${message ?: "unknown"} TIMESTAMP=${System.currentTimeMillis()}"
        )
    }

    companion object {
        private const val TAG = "SCAN_INTERVAL"
        private const val MAX_CONCURRENCY = 6
        private const val MAX_FETCH_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 450L
        private const val MAX_ANALYSIS_BARS = 800
        private const val TRADING_MINUTES_PER_DAY = 480.0
        private val DIRECT_INTERVALS = setOf(1, 5, 15, 30, 60)
        private val RETRYABLE_CODES = setOf(
            ProviderFailureCode.NETWORK_TIMEOUT, ProviderFailureCode.RATE_LIMIT,
            ProviderFailureCode.SERVER_ERROR, ProviderFailureCode.NETWORK_ERROR
        )

        /** Hafta sonu/tatil payı eklenmiş güvenli takvim penceresi. */
        fun lookbackMs(intervalMinutes: Int, bars: Int = RealTimeIntegrityPolicy.MIN_HISTORY_BARS): Long {
            val safeMinutes = intervalMinutes.coerceIn(1, 239)
            val tradingDays = (bars.coerceAtLeast(1) * safeMinutes) / TRADING_MINUTES_PER_DAY
            val calendarDays = ceil(tradingDays * 1.9 + 7.0).toLong().coerceIn(10L, 365L)
            return calendarDays * 24L * 60L * 60L * 1000L
        }
    }
}
