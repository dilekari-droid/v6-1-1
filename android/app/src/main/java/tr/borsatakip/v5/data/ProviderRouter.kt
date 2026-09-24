package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import java.util.concurrent.atomic.AtomicLong

/**
 * Fail-closed provider router.
 * HTTP success alone is insufficient: symbol, price, timestamp, freshness and repeated-payload semantics are validated.
 * Fallback is explicit, user-controlled and never reported as LIVE.
 */
class ProviderRouter(context: Context) : MarketDataProvider, ScanDiagnosticsSource, ScanBatchTimeframeConfigurable {
    data class RoutingStatus(
        val activeProviderId: String = "none",
        val activeProviderLabel: String = "Veri alınmadı",
        val dataState: String = "DOĞRULANMADI",
        val transitionReason: String = "",
        val lastSuccessfulUpdateAt: Long = 0L,
        val fallbackActive: Boolean = false,
        val circuitOpen: Boolean = false
    )

    companion object {
        private val mtfSourceGeneration = AtomicLong(0L)
        @Volatile private var lastRoutingStatus = RoutingStatus()
        fun routingStatus(): RoutingStatus = lastRoutingStatus
        private const val MAX_ATTEMPTS = 3
        private const val BACKOFF_BASE_MS = 350L
    }

    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)
    private val primary = MobileMarketDataProvider(appContext)
    private val fallback = YahooFallbackProvider(appContext)
    private val cache = MarketDataCache(appContext)
    @Volatile private var diagnostics = ProviderScanDiagnostics()

    override fun scanDiagnostics(): ProviderScanDiagnostics = diagnostics
    override fun configureScanBatchTimeframe(intervalMinutes: Int, minimumBars: Int) {
        primary.configureScanBatchTimeframe(intervalMinutes, minimumBars)
    }
    override val id: String get() = "provider_router"
    override val displayName: String get() = settings.lastProviderLabel

    /** Exposed only for IntervalMarketDataProvider optimization; still honors user fallback policy. */
    fun delayedFallbackEnabled(): Boolean = allowExperimentalFallback()
    fun shouldUseDelayedFallbackFastPath(): Boolean = !primaryConfigured() && allowExperimentalFallback()
    suspend fun delayedFallbackSymbols(): List<String> = fallback.listSymbols()
    suspend fun delayedFallbackHistory(symbol: String, fromTime: Long, toTime: Long, intervalMinutes: Int): List<Candle> =
        fallback.fetchHistory(symbol, fromTime, toTime, intervalMinutes)

    override fun mtfCacheSourceKey(): String = buildString {
        append("provider_router|")
        append(settings.lastProviderId.ifBlank { "none" })
        append('|')
        append(settings.backendOrigin().ifBlank { "unconfigured" })
        append("|fallback=")
        append(allowExperimentalFallback())
        append("|gen=")
        append(mtfSourceGeneration.get())
    }

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> {
        diagnostics = ProviderScanDiagnostics()
        var primaryFailure: Throwable? = null
        if (primaryConfigured() && ProviderHealthRegistry.circuitAllows(primary.id)) {
            try {
                val result = withRetry(primary.id) { primary.scan(onProgress) }
                diagnostics = primary.scanDiagnostics()
                val valid = result.mapNotNull {
                    // scan() already receives per-symbol rows from the provider; fetchOne() below performs the true caller-vs-response check.
                    validateAndDecorate(primary.id, primary.displayName, it.symbol, it, fallbackUsed = false)
                }
                if (valid.isNotEmpty()) {
                    valid.forEach(cache::save)
                    mark(primary.id, primary.displayName, "CANLI/DOĞRULANMIŞ", "Ana provider semantik doğrulamadan geçti.", false)
                    return valid
                }
                primaryFailure = ProviderException(ProviderFailureCode.EMPTY_DATA, "Ana provider semantik olarak geçerli veri döndürmedi.")
                recordFailureOnce(primary.id, primaryFailure)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                primaryFailure = t
                recordFailureOnce(primary.id, t)
            }
        } else if (primaryConfigured()) {
            primaryFailure = IllegalStateException("Ana provider circuit-breaker cooldown süresinde.")
        }

        if (!allowExperimentalFallback()) {
            throw primaryFailure ?: ProviderException(ProviderFailureCode.BACKEND_URL_MISSING, "Üretim provider kullanılamıyor ve fallback kapalı.")
        }
        val fallbackItems = try {
            withRetry(fallback.id) { fallback.scan(onProgress) }
                .mapNotNull { validateAndDecorate(fallback.id, fallback.displayName, it.symbol, it, fallbackUsed = true) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            recordFailureOnce(fallback.id, t)
            throw t
        }
        if (fallbackItems.isEmpty()) {
            val e = ProviderException(ProviderFailureCode.EMPTY_DATA, "Yedek provider semantik olarak geçerli veri döndürmedi.")
            recordFailureOnce(fallback.id, e)
            throw e
        }
        fallbackItems.forEach(cache::save)
        mark(
            fallback.id,
            fallback.displayName,
            "YEDEK KAYNAK / GECİKMELİ",
            "Ana provider başarısız: ${primaryFailure?.message ?: "kullanılamıyor"}. Fallback açıkça etkinleştirildi.",
            true
        )
        return fallbackItems
    }

    override suspend fun fetchOne(symbol: String): Stock? {
        val normalized = symbol.trim().uppercase()
        require(normalized.isNotBlank()) { "Sembol boş olamaz." }
        var primaryFailure: Throwable? = null

        if (primaryConfigured() && ProviderHealthRegistry.circuitAllows(primary.id)) {
            try {
                val item = withRetry(primary.id) { primary.fetchOne(normalized) }
                val valid = item?.let {
                    validateAndDecorate(
                        providerId = primary.id,
                        label = primary.displayName,
                        expectedSymbol = normalized,
                        stock = it,
                        fallbackUsed = false
                    )
                }
                if (valid != null) {
                    cache.save(valid)
                    mark(primary.id, primary.displayName, MarketDataQuality.uiStatus(valid.marketDataMetadata), "Ana provider doğrulandı.", false)
                    return valid
                }
                if (item != null) {
                    primaryFailure = ProviderException(ProviderFailureCode.BIST_QUOTE_ERROR, "$normalized quote semantik doğrulamadan geçmedi.")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                primaryFailure = t
                recordFailureOnce(primary.id, t)
            }
        }

        if (allowExperimentalFallback()) {
            try {
                val item = withRetry(fallback.id) { fallback.fetchOne(normalized) }
                val valid = item?.let {
                    validateAndDecorate(
                        providerId = fallback.id,
                        label = fallback.displayName,
                        expectedSymbol = normalized,
                        stock = it,
                        fallbackUsed = true
                    )
                }
                if (valid != null) {
                    cache.save(valid)
                    mark(fallback.id, fallback.displayName, "YEDEK KAYNAK / GECİKMELİ", "Ana provider başarısız: ${primaryFailure?.message ?: "veri yok"}", true)
                    return valid
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                recordFailureOnce(fallback.id, t)
            }
        }

        val cached = cache.load(normalized)
        if (cached != null) {
            mark("cache", "Son geçerli cache", "ÇEVRİMDIŞI / ESKİ VERİ", "Ağ/provider verisi alınamadı; cache yalnız görüntüleme için kullanılıyor.", true)
        }
        return cached
    }

    override suspend fun listSymbols(): List<String> {
        if (primaryConfigured() && ProviderHealthRegistry.circuitAllows(primary.id)) {
            try {
                val symbols = withRetry(primary.id) { primary.listSymbols() }
                    .map { it.trim().uppercase() }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (symbols.isNotEmpty()) return symbols
                throw ProviderException(ProviderFailureCode.BIST_SYMBOLS_ERROR, "BIST sembol evreni boş döndü.")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                recordFailureOnce(primary.id, t)
                if (!allowExperimentalFallback()) throw t
            }
        }
        if (!allowExperimentalFallback()) {
            throw ProviderException(ProviderFailureCode.BIST_SYMBOLS_ERROR, "BIST sembol evreni alınamadı; fallback kapalı.")
        }
        return try {
            withRetry(fallback.id) { fallback.listSymbols() }
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
                .distinct()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            recordFailureOnce(fallback.id, t)
            throw t
        }
    }

    override suspend fun fetchHistory(symbol: String, fromTime: Long, toTime: Long, intervalMinutes: Int): List<Candle> {
        val normalized = symbol.trim().uppercase()
        var primaryFailure: Throwable? = null
        if (primaryConfigured() && ProviderHealthRegistry.circuitAllows(primary.id)) {
            try {
                val result = withRetry(primary.id) { primary.fetchHistory(normalized, fromTime, toTime, intervalMinutes) }
                requireValidHistory(normalized, result)
                mark(primary.id, primary.displayName, "DOĞRULANMIŞ", "Ana provider history doğrulandı.", false)
                return result
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                primaryFailure = t
                recordFailureOnce(primary.id, t)
                if (!allowExperimentalFallback()) throw t
            }
        }
        if (!allowExperimentalFallback()) {
            throw primaryFailure ?: ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR, "$normalized history alınamadı; fallback kapalı.")
        }
        return try {
            val result = withRetry(fallback.id) { fallback.fetchHistory(normalized, fromTime, toTime, intervalMinutes) }
            requireValidHistory(normalized, result)
            mark(fallback.id, fallback.displayName, "YEDEK KAYNAK / GECİKMELİ", "History fallback'e geçti.", true)
            result
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            recordFailureOnce(fallback.id, t)
            throw t
        }
    }

    override suspend fun fetchDailyHistory(symbol: String, maximumRange: Boolean): List<Candle> {
        val normalized = symbol.trim().uppercase()
        var primaryFailure: Throwable? = null
        if (primaryConfigured() && ProviderHealthRegistry.circuitAllows(primary.id)) {
            try {
                val result = withRetry(primary.id) { primary.fetchDailyHistory(normalized, maximumRange) }
                requireValidHistory(normalized, result)
                return result
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                primaryFailure = t
                recordFailureOnce(primary.id, t)
                if (!allowExperimentalFallback()) throw t
            }
        }
        if (!allowExperimentalFallback()) {
            throw primaryFailure ?: ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR, "$normalized günlük history alınamadı; fallback kapalı.")
        }
        return try {
            val result = withRetry(fallback.id) { fallback.fetchDailyHistory(normalized, maximumRange) }
            requireValidHistory(normalized, result)
            result
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            recordFailureOnce(fallback.id, t)
            throw t
        }
    }

    private fun validateAndDecorate(
        providerId: String,
        label: String,
        expectedSymbol: String,
        stock: Stock,
        fallbackUsed: Boolean
    ): Stock? {
        val verdict = MarketDataQuality.validateStock(expectedSymbol, stock)
        if (!verdict.accepted) {
            ProviderHealthRegistry.recordFailure(providerId, "${verdict.code}: ${verdict.reason}")
            return null
        }
        val signature = "${stock.exchangeTimestamp}|${stock.quotePrice ?: stock.candles.lastOrNull()?.close}|${stock.lastBarTime}"
        if (!ProviderHealthRegistry.recordSymbolSuccess(providerId, expectedSymbol, signature, stock.isRealtime)) return null
        val lastSuccess = ProviderHealthRegistry.lastSuccessfulUpdateAt(providerId)
        val received = stock.receivedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        return stock.copy(
            isRealtime = stock.isRealtime && !fallbackUsed,
            marketDataMetadata = MarketDataQuality.metadata(
                providerId = providerId,
                source = label,
                marketTimestamp = stock.exchangeTimestamp,
                receivedAt = received,
                isLive = stock.isRealtime && !fallbackUsed,
                delaySeconds = stock.delaySeconds,
                lastSuccessfulUpdateAt = lastSuccess,
                fallback = fallbackUsed,
                reason = if (fallbackUsed) "Fallback/yedek veri; LIVE değildir." else verdict.reason
            )
        )
    }

    private fun requireValidHistory(symbol: String, items: List<Candle>) {
        val reason = RealTimeIntegrityPolicy.validateCandles(items)
        if (reason != null) {
            throw ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR, "$symbol history bütünlük hatası: $reason")
        }
    }

    /**
     * Retry only performs retry/backoff. Health failure accounting belongs to the request boundary,
     * so one logical request cannot be counted multiple times by nested layers.
     */
    private suspend fun <T> withRetry(providerId: String, block: suspend () -> T): T {
        var last: Throwable? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (!ProviderHealthRegistry.circuitAllows(providerId)) {
                throw IllegalStateException("$providerId circuit-breaker açık.")
            }
            try {
                return block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                last = t
                val retryable = ProviderRetryPolicy.isRetryable(t)
                val hasNext = attempt < MAX_ATTEMPTS - 1
                if (!retryable || !hasNext) break
                delay(BACKOFF_BASE_MS * (1L shl attempt))
            }
        }
        throw last ?: IllegalStateException("$providerId bilinmeyen provider hatası.")
    }

    private fun recordFailureOnce(providerId: String, t: Throwable) {
        ProviderHealthRegistry.recordFailure(providerId, t.message ?: t.javaClass.simpleName)
    }

    /** Display-only external Yahoo access is centralized here and still obeys user fallback settings. */
    suspend fun fetchExternalQuote(symbolLabel: String, providerTickers: List<String>): YahooFallbackProvider.DisplayQuoteResult {
        if (!allowExperimentalFallback()) {
            return YahooFallbackProvider.DisplayQuoteResult(null, "Yedek sağlayıcı kullanıcı ayarlarında kapalı.")
        }
        return fallback.fetchExternalQuote(symbolLabel, providerTickers)
    }

    suspend fun fetchDisplayQuoteOnly(symbol: String): YahooFallbackProvider.DisplayQuoteResult {
        if (!allowExperimentalFallback()) {
            return YahooFallbackProvider.DisplayQuoteResult(null, "Yedek sağlayıcı kullanıcı ayarlarında kapalı.")
        }
        return fallback.fetchDisplayQuoteOnly(symbol)
    }

    private fun allowExperimentalFallback(): Boolean =
        ProviderFallbackPolicy.allowed(settings.experimentalProvidersEnabled, settings.yahooFallbackEnabled)

    private fun primaryConfigured(): Boolean =
        ProviderReadinessService.isValidHttps(settings.baseUrl) && settings.apiKey.isNotBlank()

    private fun mark(providerId: String, label: String, state: String, reason: String, fallbackActive: Boolean) {
        if (settings.lastProviderId != providerId) {
            mtfSourceGeneration.incrementAndGet()
            MtfHistoryCache.clear()
        }
        val now = System.currentTimeMillis()
        settings.lastProviderId = providerId
        settings.lastProviderLabel = label
        settings.lastProviderTimestamp = now
        settings.lastProviderMessage = "$state • $reason"
        val health = ProviderHealthRegistry.snapshot(providerId)
        lastRoutingStatus = RoutingStatus(
            activeProviderId = providerId,
            activeProviderLabel = label,
            dataState = state,
            transitionReason = reason,
            lastSuccessfulUpdateAt = health.lastSuccessfulUpdateAt,
            fallbackActive = fallbackActive,
            circuitOpen = health.circuitOpenUntil > now
        )
    }
}
