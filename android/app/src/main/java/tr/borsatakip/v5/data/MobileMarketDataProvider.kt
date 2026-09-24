package tr.borsatakip.v5.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ProviderException(
    val code: ProviderFailureCode,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

class MobileMarketDataProvider(context: Context) : MarketDataProvider, ScanDiagnosticsSource, ScanBatchTimeframeConfigurable {
    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)
    override val id = "mobile_backend"
    override val displayName = "Üretim canlı veri servisi"
    override fun mtfCacheSourceKey(): String = "mobile_backend|${settings.backendOrigin().ifBlank { "unconfigured" }}"
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private suspend fun loadBatchPolicy(): SnapshotBatchPolicy {
        val remote = MarketCapabilityClient(appContext).load().getOrNull()?.batchPolicy
        return SnapshotBatchPolicy.resolve(
            maxSymbols = remote?.maxSymbols,
            readTimeoutMs = remote?.readTimeoutMs,
            callTimeoutMs = remote?.callTimeoutMs,
            outerTimeoutMs = remote?.outerTimeoutMs
        )
    }

    private fun batchClient(policy: SnapshotBatchPolicy): OkHttpClient = client.newBuilder()
        .readTimeout(policy.readTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(policy.callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var diagnostics = ProviderScanDiagnostics()
    @Volatile private var scanBatchIntervalMinutes: Int? = null
    @Volatile private var scanBatchMinimumBars: Int = RealTimeIntegrityPolicy.MIN_HISTORY_BARS

    override fun scanDiagnostics(): ProviderScanDiagnostics = diagnostics

    override fun configureScanBatchTimeframe(intervalMinutes: Int, minimumBars: Int) {
        scanBatchIntervalMinutes = intervalMinutes.coerceIn(1, 240)
        scanBatchMinimumBars = minimumBars.coerceAtLeast(1)
    }

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> = withContext(Dispatchers.IO) {
        ensureConfigured()
        diagnostics = ProviderScanDiagnostics()
        val batchPolicy = loadBatchPolicy()
        val scanBatchClient = batchClient(batchPolicy)
        val symbols = loadSymbols()
        if (symbols.isEmpty()) throw ProviderException(ProviderFailureCode.BIST_SYMBOLS_ERROR, "BIST HİSSE LİSTESİ ALINAMADI.")

        val noData = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val messages = linkedMapOf<String, String>()
        fun remember(symbol: String, code: ProviderFailureCode, message: String?) {
            val msg = message ?: code.name
            messages[symbol] = msg
            if (code in NO_DATA_CODES) noData += symbol else errors += symbol
            logDataError(symbol, "base", code.name, msg)
        }

        Log.i(TAG, "[BIST_SCAN] TOTAL=${symbols.size} batchSize=${batchPolicy.maxSymbols} readTimeoutMs=${batchPolicy.readTimeoutMs} callTimeoutMs=${batchPolicy.callTimeoutMs}")
        val out = mutableListOf<Stock>()
        var done = 0
        onProgress(0, symbols.size)
        val batches = symbols.chunked(batchPolicy.maxSymbols)
        try {
            for ((batchIndex, batch) in batches.withIndex()) {
                ManualScanPauseGate.awaitIfPaused()
                val batchResult = try {
                    loadBatchWithRetry(batch, batchIndex + 1, batches.size, scanBatchClient, batchPolicy.outerTimeoutMs)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (pe: ProviderException) {
                    Log.w(TAG, "[BIST_SCAN] BATCH_FAILED index=${batchIndex + 1} size=${batch.size} code=${pe.code} message=${pe.message}")
                    if (pe.code in FATAL_FAILURES) throw pe
                    batch.forEach { remember(it, pe.code, pe.message) }
                    done = (done + batch.size).coerceAtMost(symbols.size)
                    onProgress(done, symbols.size)
                    continue
                }

                val items = batchResult.optJSONArray("items")
                    ?: throw ProviderException(ProviderFailureCode.EMPTY_DATA, "Batch tarama yanıtında items alanı yok.")
                val expected = batch.toSet()
                val returned = mutableSetOf<String>()
                for (i in 0 until items.length()) {
                    val row = items.optJSONObject(i)
                        ?: throw ProviderException(ProviderFailureCode.EMPTY_DATA, "Batch öğesi nesne değil.")
                    val requested = row.optString("symbol").trim().uppercase()
                    if (requested !in expected || !returned.add(requested)) {
                        throw ProviderException(ProviderFailureCode.EMPTY_DATA, "Batch sözleşmesi bozuk: beklenmeyen veya tekrarlanan sembol $requested")
                    }
                    if (row.has("error")) {
                        val errorObj = row.optJSONObject("error")
                        val rawCode = errorObj?.optString("code").orEmpty().uppercase()
                        val mapped = mapProviderCode(rawCode)
                        val msg = errorObj?.optString("message").ifNullOrBlank("Batch provider error: $rawCode")
                        if (mapped in FATAL_FAILURES) throw ProviderException(mapped, msg)
                        remember(requested, mapped, msg)
                        continue
                    }
                    val q = row.optJSONObject("quote")
                    val h = row.optJSONObject("history")
                    if (q == null || h == null) {
                        remember(requested, ProviderFailureCode.EMPTY_DATA, "$requested quote/history alanı eksik.")
                        continue
                    }
                    try {
                        out += parseStockPair(requested, q, h)
                    } catch (pe: ProviderException) {
                        if (pe.code in FATAL_FAILURES) throw pe
                        remember(requested, pe.code, pe.message)
                    } catch (t: Throwable) {
                        remember(requested, ProviderFailureCode.UNKNOWN_ERROR, t.message ?: t.javaClass.simpleName)
                    }
                }
                if (returned != expected || items.length() != batch.size) {
                    throw ProviderException(
                        ProviderFailureCode.EMPTY_DATA,
                        "Batch count/symbol mismatch: requested=${batch.size} returned=${items.length()} unique=${returned.size}"
                    )
                }
                done = (done + batch.size).coerceAtMost(symbols.size)
                onProgress(done, symbols.size)
            }
            out
        } finally {
            diagnostics = ProviderScanDiagnostics(noData.distinct(), errors.distinct(), messages.toMap())
        }
    }

    private suspend fun loadBatchWithRetry(
        batch: List<String>,
        batchIndex: Int,
        batchCount: Int,
        httpClient: OkHttpClient,
        outerTimeoutMs: Long
    ): JSONObject {
        val encoded = URLEncoder.encode(batch.joinToString(","), "UTF-8")
        val configured = scanBatchIntervalMinutes
        val timeframeQuery = if (configured == null) {
            ""
        } else if (configured >= 240) {
            "&interval=1d&range=1y"
        } else {
            val upstreamMinutes = if (configured in DIRECT_BATCH_INTERVALS) configured else 1
            val now = System.currentTimeMillis()
            val from = now - IntervalMarketDataProvider.lookbackMs(upstreamMinutes, scanBatchMinimumBars + 20)
            "&interval=${upstreamMinutes}m&from=$from&to=$now"
        }
        val requestId = "bist-batch-${UUID.randomUUID()}"
        var last: ProviderException? = null
        for (attempt in 1..MAX_BATCH_ATTEMPTS) {
            try {
                return withTimeoutOrNull(outerTimeoutMs) {
                    getJson("/v1/bist/snapshot-batch?symbols=$encoded$timeframeQuery", httpClient, requestId)
                } ?: throw ProviderException(
                    ProviderFailureCode.NETWORK_TIMEOUT,
                    "Batch $batchIndex/$batchCount zaman aşımına uğradı."
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (pe: ProviderException) {
                last = pe
                if (pe.code in FATAL_FAILURES) throw pe
                if (pe.code !in RETRYABLE_FAILURES || attempt >= MAX_BATCH_ATTEMPTS) break
                val waitMs = (RETRY_BASE_DELAY_MS * attempt).coerceAtMost(2_000L)
                Log.w(TAG, "[BIST_SCAN] BATCH_RETRY index=$batchIndex/$batchCount attempt=$attempt code=${pe.code} waitMs=$waitMs")
                delay(waitMs)
            }
        }
        throw last ?: ProviderException(ProviderFailureCode.UNKNOWN_ERROR, "Batch $batchIndex/$batchCount başarısız.")
    }

    override suspend fun fetchOne(symbol: String): Stock? = withContext(Dispatchers.IO) {
        ensureConfigured()
        withTimeout(20_000) { fetchStock(normalizeSymbol(symbol)) }
    }

    override suspend fun fetchHistory(symbol: String, fromTime: Long, toTime: Long, intervalMinutes: Int): List<Candle> = withContext(Dispatchers.IO) {
        ensureConfigured()
        if (fromTime <= 0L || toTime <= fromTime) return@withContext emptyList()
        val requested = normalizeSymbol(symbol)
        val safeInterval = intervalMinutes.coerceIn(1, 60)
        val encoded = URLEncoder.encode(requested, "UTF-8")
        val json = getJson("/v1/bist/history-window/$encoded?from=$fromTime&to=$toTime&interval=${safeInterval}m")
        requireMatchingSymbol(requested, json.optString("symbol"), "history-window")
        val candles = parseCandlesStrict(json.optJSONArray("candles") ?: JSONArray(), minimum = 1)
        candles.filter { it.timestamp in fromTime..toTime }.sortedBy { it.timestamp }
    }


    override suspend fun fetchDailyHistory(symbol: String, maximumRange: Boolean): List<Candle> = withContext(Dispatchers.IO) {
        ensureConfigured()
        val requested = normalizeSymbol(symbol)
        val encoded = URLEncoder.encode(requested, "UTF-8")
        val range = if (maximumRange) "max" else "1y"
        val json = getJson("/v1/bist/history/$encoded?range=$range&interval=1d")
        requireMatchingSymbol(requested, json.optString("symbol"), "daily-history")
        parseCandlesStrict(json.optJSONArray("candles") ?: JSONArray(), minimum = 1).sortedBy { it.timestamp }
    }

    override suspend fun listSymbols(): List<String> = loadSymbols()

    private suspend fun loadSymbols(): List<String> {
        val json = getJson("/v1/bist/symbols")
        val items = json.optJSONArray("items") ?: throw ProviderException(ProviderFailureCode.EMPTY_DATA, "BIST sembol yanıtı boş.")
        val declaredCount = json.optInt("count", items.length())
        if (declaredCount != items.length()) {
            throw ProviderException(
                ProviderFailureCode.BIST_SYMBOLS_ERROR,
                "BIST evreni sözleşmesi bozuk: backend count=$declaredCount, items=${items.length()}."
            )
        }
        val symbols = (0 until items.length()).mapNotNull { i ->
            items.optString(i).trim().uppercase().takeIf { it.matches(Regex("[A-Z0-9_]{3,12}")) }
        }.distinct()
        if (symbols.size != items.length()) {
            throw ProviderException(
                ProviderFailureCode.BIST_SYMBOLS_ERROR,
                "BIST evreninde geçersiz veya tekrarlanan sembol var: items=${items.length()}, unique=${symbols.size}."
            )
        }
        if (symbols.size < MIN_PRODUCTION_BIST_UNIVERSE) {
            throw ProviderException(
                ProviderFailureCode.BIST_SYMBOLS_ERROR,
                "BIST evreni eksik: yalnız ${symbols.size} sembol geldi; en az $MIN_PRODUCTION_BIST_UNIVERSE bekleniyor. Eksik evrenle tarama başlatılmadı."
            )
        }
        if (symbols.isNotEmpty()) {
            settings.cachedBistSymbols = symbols.toSet()
            settings.cachedBistSymbolCount = symbols.size
            settings.cachedBistSymbolsFetchedAt = System.currentTimeMillis()
            settings.cachedBistSymbolsProviderId = json.optString("source").ifBlank { displayName }
        }
        return symbols
    }

    private suspend fun fetchStock(symbol: String): Stock {
        val requested = normalizeSymbol(symbol)
        val encoded = URLEncoder.encode(requested, "UTF-8")
        val quoteJson = getJson("/v1/bist/quote/$encoded")
        val historyJson = getJson("/v1/bist/history/$encoded?range=1y&interval=1d")
        return parseStockPair(requested, quoteJson, historyJson)
    }

    private fun parseStockPair(requestedRaw: String, quoteJson: JSONObject, historyJson: JSONObject): Stock {
        val requested = normalizeSymbol(requestedRaw)
        val quoteSymbol = quoteJson.optString("symbol").trim().uppercase()
        val historySymbol = historyJson.optString("symbol").trim().uppercase()
        requireMatchingSymbol(requested, quoteSymbol, "quote"); requireMatchingSymbol(requested, historySymbol, "history")
        if (quoteSymbol != historySymbol) throw ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR, "Quote/history sembol kimliği uyuşmuyor: $quoteSymbol/$historySymbol")
        if (quoteJson.optString("market", "BIST").uppercase() != "BIST" || historyJson.optString("market", "BIST").uppercase() != "BIST") throw ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR, "Piyasa kimliği BIST ile eşleşmiyor.")
        val quotePrice=quoteJson.optDouble("price",Double.NaN); val exchangeTimestamp=quoteJson.optLong("exchangeTimestamp",0L)
        val delaySeconds=if(quoteJson.has("delaySeconds")&&!quoteJson.isNull("delaySeconds")) quoteJson.optInt("delaySeconds") else null
        val realtime=quoteJson.optBoolean("realtime",false); val currentSession=quoteJson.optBoolean("currentSessionIncluded",false) || historyJson.optBoolean("currentSessionIncluded",false)
        if(!quotePrice.isFinite()||quotePrice<=0.0||exchangeTimestamp<=0L) throw ProviderException(ProviderFailureCode.BIST_QUOTE_ERROR,"$requested quote verisi geçersiz.")
        val candles=parseCandlesStrict(historyJson.optJSONArray("candles")?:JSONArray(),minimum=RealTimeIntegrityPolicy.MIN_HISTORY_BARS)
        val lastBarTime=historyJson.optLong("lastBarTime",candles.last().timestamp)
        if(lastBarTime!=candles.last().timestamp) throw ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR,"History lastBarTime son mumla eşleşmiyor.")
        val interval=historyJson.optString("interval","1d"); val timezone=historyJson.optString("exchangeTimezone").ifBlank{null}?:throw ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR,"History exchangeTimezone alanı eksik.")
        val previousClose=quoteJson.optDouble("previousClose",Double.NaN).takeIf{it.isFinite()&&it>0.0}?:historyJson.optDouble("previousClose",Double.NaN).takeIf{it.isFinite()&&it>0.0}
        val stock=Stock(symbol=requested,companyName=historyJson.optString("name").takeIf{it.isNotBlank()},candles=candles,source=quoteJson.optString("source").ifBlank{displayName},dataTimestamp=exchangeTimestamp,isRealtime=realtime,delaySeconds=delaySeconds,currentSessionIncluded=currentSession,receivedAt=System.currentTimeMillis(),receivedElapsedRealtime=SystemClock.elapsedRealtime(),quotePrice=quotePrice,currency=quoteJson.optString("currency").takeIf{it.isNotBlank()},market="BIST",historySymbol=historySymbol,interval=interval,exchangeTimezone=timezone,sessionId=historyJson.optString("sessionId").takeIf{it.isNotBlank()},lastBarTime=lastBarTime,lastBarClosed=if(historyJson.has("lastBarClosed"))historyJson.optBoolean("lastBarClosed")else null,previousClose=previousClose,bid=quoteJson.optDouble("bid",Double.NaN).takeIf{it.isFinite()&&it>0.0},ask=quoteJson.optDouble("ask",Double.NaN).takeIf{it.isFinite()&&it>0.0})
        val receivedNow = stock.receivedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val withMetadata = stock.copy(marketDataMetadata = MarketDataQuality.metadata(
            providerId = id, source = stock.source, marketTimestamp = stock.exchangeTimestamp,
            receivedAt = receivedNow, isLive = stock.isRealtime, delaySeconds = stock.delaySeconds,
            lastSuccessfulUpdateAt = receivedNow, fallback = false,
            reason = "Production provider semantik doğrulamasından geçti."
        ))
        // Base snapshot transports current quote + structural history. The daily candle may still be open
        // during the session; selected-timeframe closed-bar validation is applied after reframe().
        val semantic = MarketDataQuality.validateStock(requested, withMetadata); if(!semantic.accepted) throw ProviderException(ProviderFailureCode.BIST_QUOTE_ERROR, semantic.reason)
        return withMetadata
    }

    private fun parseCandlesStrict(candlesArray: JSONArray, minimum: Int): List<Candle> {
        val candles = ArrayList<Candle>(candlesArray.length())
        for (i in 0 until candlesArray.length()) {
            val x = candlesArray.optJSONObject(i) ?: throw ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR, "OHLCV[$i] nesne değil.")
            val c = Candle(
                timestamp = x.optLong("timestamp", 0L),
                open = x.optDouble("open", Double.NaN),
                high = x.optDouble("high", Double.NaN),
                low = x.optDouble("low", Double.NaN),
                close = x.optDouble("close", Double.NaN),
                volume = x.optDouble("volume", Double.NaN)
            )
            candles += c
        }
        return BistHistoryIntegrityPolicy.requireStrict(candles, minimum)
    }

    private fun normalizeSymbol(symbol: String): String {
        val s = symbol.trim().uppercase()
        if (!s.matches(Regex("[A-Z0-9_]{3,12}"))) throw ProviderException(ProviderFailureCode.EMPTY_DATA, "Geçersiz BIST sembolü: $symbol")
        return s
    }

    private fun requireMatchingSymbol(requested: String, actualRaw: String, source: String) {
        val actual = actualRaw.trim().uppercase()
        if (actual.isBlank() || actual != requested) throw ProviderException(ProviderFailureCode.BIST_HISTORY_ERROR, "$source sembolü istekle eşleşmiyor: requested=$requested actual=${actual.ifBlank { "<empty>" }}")
    }

    private fun ensureConfigured() {
        if (!ProviderReadinessService.isValidHttps(settings.baseUrl)) throw ProviderException(ProviderFailureCode.BACKEND_URL_MISSING, "Production backend yapılandırılmamış.")
        if (settings.apiKey.isBlank()) throw ProviderException(ProviderFailureCode.API_KEY_MISSING, "Production backend API anahtarı yapılandırılmamış.")
    }

    private suspend fun getJson(path: String): JSONObject = getJson(path, client, null)

    private suspend fun getJson(path: String, httpClient: OkHttpClient, requestId: String? = null): JSONObject {
        val base = settings.baseUrl.trim().removeSuffix("/")
        if (!ProviderReadinessService.isValidHttps(base)) throw ProviderException(ProviderFailureCode.INVALID_HTTPS, "Yalnız HTTPS Production backend kullanılabilir.")
        fun request(): Request {
            val builder = Request.Builder().url(base + path).get().header("Accept", "application/json")
            if (!requestId.isNullOrBlank()) builder.header("X-Request-ID", requestId)
            return BackendRequestSecurity.apply(builder, settings).build()
        }
        val body = try {
            executeCancellable(httpClient.newCall(request()))
        } catch (pe: ProviderException) {
            if (pe.code != ProviderFailureCode.AUTH_ERROR || settings.backendSessionToken.isBlank()) throw pe
            settings.clearBackendSessionToken()
            BackendSessionClient(appContext).refreshIfNeeded(enabled = true).getOrElse { throw pe }
            executeCancellable(httpClient.newCall(request()))
        }
        return try { JSONObject(body) } catch (t: Throwable) { throw ProviderException(ProviderFailureCode.EMPTY_DATA, "Production backend geçersiz JSON döndürdü.", t) }
    }

    private suspend fun executeCancellable(call: Call): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isActive) return
                val ex = when (e) {
                    is SocketTimeoutException -> ProviderException(ProviderFailureCode.NETWORK_TIMEOUT, "Backend isteği zaman aşımına uğradı.", e)
                    is UnknownHostException -> ProviderException(ProviderFailureCode.DNS_ERROR, "Backend alan adı çözümlenemedi.", e)
                    is SSLException -> ProviderException(ProviderFailureCode.TLS_ERROR, "TLS bağlantısı kurulamadı.", e)
                    else -> ProviderException(ProviderFailureCode.NETWORK_ERROR, "Backend ağ isteği başarısız: ${e.message ?: e.javaClass.simpleName}", e)
                }
                cont.resumeWithException(ex)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!cont.isActive) return
                    val code = it.code
                    val text = it.body?.string().orEmpty()
                    val error = when {
                        code == 401 || code == 403 -> ProviderException(ProviderFailureCode.AUTH_ERROR, "Production backend kimlik doğrulaması başarısız (HTTP $code).")
                        code == 404 || code == 422 -> ProviderException(ProviderFailureCode.EMPTY_DATA, "İstenen piyasa verisi bulunamadı (HTTP $code).")
                        code == 429 -> ProviderException(ProviderFailureCode.RATE_LIMIT, "Production backend istek sınırı aşıldı (HTTP 429). Kısa süre sonra yeniden deneyin.")
                        code >= 500 -> ProviderException(ProviderFailureCode.SERVER_ERROR, "Production backend sunucu hatası (HTTP $code).")
                        code !in 200..299 -> ProviderException(ProviderFailureCode.SERVER_ERROR, "Production backend HTTP $code döndürdü.")
                        text.isBlank() -> ProviderException(ProviderFailureCode.EMPTY_DATA, "Production backend boş yanıt döndürdü.")
                        else -> null
                    }
                    if (error != null) cont.resumeWithException(error) else cont.resume(text)
                }
            }
        })
    }

    private fun String?.ifNullOrBlank(fallback: String): String = if (this.isNullOrBlank()) fallback else this

    private fun mapProviderCode(raw: String): ProviderFailureCode = when (raw.uppercase()) {
        "AUTH_ERROR", "AUTH_NOT_CONFIGURED" -> ProviderFailureCode.AUTH_ERROR
        "TLS_ERROR" -> ProviderFailureCode.TLS_ERROR
        "DNS_ERROR" -> ProviderFailureCode.DNS_ERROR
        "NETWORK_TIMEOUT", "BATCH_DEADLINE_EXCEEDED" -> ProviderFailureCode.NETWORK_TIMEOUT
        "RATE_LIMIT" -> ProviderFailureCode.RATE_LIMIT
        "NOT_CONFIGURED" -> ProviderFailureCode.BACKEND_URL_MISSING
        "NO_CANDLES", "EMPTY_DATA", "INSUFFICIENT_HISTORY", "HISTORY_ERROR", "STALE_DATA" -> ProviderFailureCode.EMPTY_DATA
        "QUOTE_ERROR" -> ProviderFailureCode.BIST_QUOTE_ERROR
        "SERVER_ERROR", "HTTP_ERROR" -> ProviderFailureCode.SERVER_ERROR
        else -> ProviderFailureCode.UNKNOWN_ERROR
    }

    private fun logDataError(symbol: String, timeframe: String, code: String, message: String) {
        Log.w(
            TAG,
            "SYMBOL=$symbol TIMEFRAME=$timeframe DATA_SOURCE=$id ERROR_CODE=$code " +
                "ERROR_MESSAGE=$message TIMESTAMP=${System.currentTimeMillis()}"
        )
    }

    companion object {
        private const val TAG = "BIST_SCAN"
        private const val DEFAULT_BATCH_SIZE = 20
        const val MIN_PRODUCTION_BIST_UNIVERSE = 300
        private const val DEFAULT_BATCH_READ_TIMEOUT_MS = 175_000L
        private const val DEFAULT_BATCH_CALL_TIMEOUT_MS = 180_000L
        private const val DEFAULT_BATCH_OUTER_TIMEOUT_MS = 185_000L
        private const val MAX_BATCH_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 400L
        private val DIRECT_BATCH_INTERVALS = setOf(1, 5, 15, 30, 60)
        private val FATAL_FAILURES = setOf(
            ProviderFailureCode.AUTH_ERROR, ProviderFailureCode.TLS_ERROR, ProviderFailureCode.DNS_ERROR,
            ProviderFailureCode.BACKEND_URL_MISSING, ProviderFailureCode.API_KEY_MISSING, ProviderFailureCode.INVALID_HTTPS
        )
        private val RETRYABLE_FAILURES = setOf(
            ProviderFailureCode.NETWORK_TIMEOUT, ProviderFailureCode.RATE_LIMIT,
            ProviderFailureCode.SERVER_ERROR, ProviderFailureCode.NETWORK_ERROR
        )
        private val NO_DATA_CODES = setOf(
            ProviderFailureCode.EMPTY_DATA, ProviderFailureCode.BIST_HISTORY_ERROR,
            ProviderFailureCode.STALE_DATA, ProviderFailureCode.BIST_QUOTE_ERROR
        )
    }
}
