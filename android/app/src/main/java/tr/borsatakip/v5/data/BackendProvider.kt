package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopContractType
import tr.borsatakip.v5.model.ViopUniversePage
import tr.borsatakip.v5.model.ViopUniverseSnapshot
import tr.borsatakip.v5.model.ViopQuote
import tr.borsatakip.v5.model.NewsItem
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import java.time.YearMonth

internal suspend fun Call.awaitBody(label: String): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!continuation.isActive) return
                if (!response.isSuccessful) {
                    val errorBody = try { response.body?.string().orEmpty() } catch (_: Exception) { "" }
                    val detail = runCatching {
                        val j = JSONObject(errorBody)
                        listOf(j.optString("code"), j.optString("message")).filter { it.isNotBlank() }.joinToString(" • ")
                    }.getOrDefault("")
                    val suffix = if (detail.isBlank()) "" else " • $detail"
                    continuation.resumeWith(Result.failure(IllegalArgumentException("$label HTTP ${response.code}$suffix")))
                    return
                }
                val body = try {
                    response.body?.string().orEmpty()
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    return
                }
                if (!continuation.isActive) return
                if (body.isBlank()) {
                    continuation.resumeWith(Result.failure(IllegalArgumentException("$label boş yanıt döndürdü.")))
                } else {
                    continuation.resumeWith(Result.success(body))
                }
            }
        }
    })
}

class BackendProvider(context: Context) {
    private val appContext = context.applicationContext
    private val s = SettingsStore(appContext)
    private val capabilities = MarketCapabilityClient(appContext)
    fun mtfCacheSourceKey(): String = "viop_backend|${s.backendOrigin().ifBlank { "unconfigured" }}"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val CONTRACT_PAGE_LIMIT = 200
        const val MAX_UNIVERSE_PAGES = 100
    }

    private suspend fun readJson(path: String, label: String): JSONObject {
        val base = s.baseUrl.trim().trimEnd('/')
        require(ProviderReadinessService.isValidHttps(base)) { "HTTPS veri sağlayıcı adresi Ayarlar bölümünde tanımlanmalıdır." }
        fun request(): Request = BackendRequestSecurity.apply(
            Request.Builder().url(base + path).header("Accept", "application/json"),
            s
        ).build()
        val body = try {
            client.newCall(request()).awaitBody(label)
        } catch (e: IllegalArgumentException) {
            val authFailure = e.message?.contains("HTTP 401") == true || e.message?.contains("HTTP 403") == true
            if (!authFailure || s.backendSessionToken.isBlank()) throw e
            s.clearBackendSessionToken()
            BackendSessionClient(appContext).refreshIfNeeded(enabled = true).getOrElse { throw e }
            client.newCall(request()).awaitBody(label)
        }
        return JSONObject(body)
    }

    private suspend fun <T> providerResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun loadViop(): Result<List<ViopContract>> = loadViopUniverse().map { it.contracts }

    suspend fun loadViopUniverse(): Result<ViopUniverseSnapshot> = withContext(Dispatchers.IO) {
        providerResult {
            val capability = capabilities.load().getOrThrow()
            require(capability.features.viopContractsReady) { "VİOP_CONTRACT_METADATA_UNAVAILABLE: Backend doğrulanmış kontrat metadata capability'sini etkin ilan etmiyor." }
            val all = mutableListOf<ViopContract>()
            var cursor: String? = null
            var pageNumber = 0
            var providerTotal: Int? = null
            var universeAsOf: Long? = null
            var providerHasMore = false
            val seenCursors = mutableSetOf<String>()

            do {
                pageNumber += 1
                require(pageNumber <= MAX_UNIVERSE_PAGES) { "UNIVERSE_INCOMPLETE: Sayfa sınırı aşıldı." }
                val query = buildString {
                    append("?limit=").append(CONTRACT_PAGE_LIMIT)
                    cursor?.let { append("&cursor=").append(URLEncoder.encode(it, "UTF-8")) }
                }
                val root = readJson("/v1/viop/contracts$query", "VİOP veri sağlayıcısı")
                val deviceReceivedAt = System.currentTimeMillis()
                val page = parseViopUniversePage(root, pageNumber, deviceReceivedAt)
                all += page.items

                if (page.totalCount != null) {
                    if (providerTotal != null && providerTotal != page.totalCount) {
                        error("UNIVERSE_INCOMPLETE: totalCount sayfalar arasında değişti: $providerTotal -> ${page.totalCount}")
                    }
                    providerTotal = page.totalCount
                }
                page.universeAsOf?.let { value ->
                    if (universeAsOf != null && universeAsOf != value) {
                        error("UNIVERSE_INCOMPLETE: universeAsOf sayfalar arasında değişti.")
                    }
                    universeAsOf = value
                }

                providerHasMore = page.hasMore
                val next = page.nextCursor
                if (providerHasMore) {
                    require(!next.isNullOrBlank()) { "UNIVERSE_INCOMPLETE: hasMore=true fakat nextCursor yok." }
                    require(seenCursors.add(next)) { "UNIVERSE_INCOMPLETE: cursor döngüsü algılandı." }
                    cursor = next
                } else {
                    cursor = null
                }
            } while (cursor != null)

            val unique = all.distinctBy { it.symbol.trim().uppercase() }
            val assessment = ViopUniverseIntegrity.assess(providerTotal, unique.size, providerHasMore)
            ViopUniverseSnapshot(
                contracts = unique,
                providerTotal = providerTotal,
                fetchedCount = all.size,
                fetchedUniqueCount = unique.size,
                activeUniqueFutures = unique.count {
                    it.contractType == ViopContractType.FUTURE && !it.isManual &&
                        it.lastTradingAt?.let { at -> at > System.currentTimeMillis() } != false &&
                        it.expiryAt?.let { at -> at > System.currentTimeMillis() } != false
                },
                universeAsOf = universeAsOf,
                pageCount = pageNumber,
                completeness = assessment.completeness,
                completenessReason = assessment.reason
            )
        }
    }

    private fun parseViopUniversePage(root: JSONObject, pageNumber: Int, deviceReceivedAt: Long): ViopUniversePage {
        val array = root.optJSONArray("items") ?: org.json.JSONArray()
        val items = buildList {
            for (i in 0 until array.length()) {
                val x = array.optJSONObject(i) ?: continue
                val symbol = x.optString("symbol").trim().uppercase()
                if (symbol.isBlank()) continue
                val underlying = x.optString("underlying").trim().uppercase()
                val expiry = x.optString("expiry").trim()
                val tick = x.optDouble("tickSize", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                val multiplier = x.optDouble("multiplier", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                val dataTimestamp = x.optLong("dataTimestamp", 0L)
                val realtime = x.optBoolean("realtime", false)
                val delay = if (x.has("delaySeconds") && !x.isNull("delaySeconds")) x.optInt("delaySeconds") else null
                val currentSession = x.optBoolean("currentSessionIncluded", false)
                val lastTradingAt = x.optLong("lastTradingAt", 0L).takeIf { it > 0L }
                val expiryAt = x.optLong("expiryAt", 0L).takeIf { it > 0L }
                val mode = when {
                    realtime && currentSession && delay != null && delay in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS -> DataMode.REALTIME
                    delay != null && delay > RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS -> DataMode.DELAYED
                    else -> DataMode.UNVERIFIED
                }
                val expiryOk = runCatching { YearMonth.parse(expiry) >= YearMonth.now() }.getOrDefault(false)
                val validity = when {
                    !expiryOk || (lastTradingAt != null && lastTradingAt <= deviceReceivedAt) -> SignalValidity.REJECTED
                    underlying.isBlank() || tick == null || multiplier == null || lastTradingAt == null -> SignalValidity.INSUFFICIENT
                    dataTimestamp <= 0L -> SignalValidity.REJECTED
                    mode != DataMode.REALTIME -> SignalValidity.WATCH
                    else -> SignalValidity.VALID
                }
                val reason = when (validity) {
                    SignalValidity.VALID -> "Vade, son işlem zamanı, dayanak, tickSize, multiplier ve veri kökeni doğrulandı."
                    SignalValidity.WATCH -> "Sözleşme parametreleri mevcut ancak gerçek zamanlı veri modu doğrulanmadı."
                    SignalValidity.INSUFFICIENT -> if (lastTradingAt == null) "Gerçek son işlem zamanı (lastTradingAt) eksik." else "Dayanak, tickSize veya multiplier zorunlu alanlarından biri eksik."
                    SignalValidity.REJECTED -> if (!expiryOk || (lastTradingAt != null && lastTradingAt <= deviceReceivedAt)) "Vade/son işlem zamanı geçersiz veya sona ermiş." else "Piyasa veri zamanı eksik/geçersiz."
                }
                add(ViopContract(
                    symbol = symbol,
                    underlying = underlying.ifBlank { "-" },
                    expiry = expiry.ifBlank { "-" },
                    contractType = ViopContractType.parse(x.optString("contractType")),
                    lastPrice = x.optDouble("lastPrice", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                    bid = x.optDouble("bid", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                    ask = x.optDouble("ask", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                    dailyChangePct = x.optDouble("dailyChangePct", Double.NaN).takeIf { it.isFinite() },
                    tickSize = tick,
                    multiplier = multiplier,
                    openInterest = x.optLong("openInterest", -1L).takeIf { it >= 0L },
                    volume = x.optDouble("volume", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                    liquidity = x.optString("liquidity").takeIf { it.isNotBlank() },
                    rollover = x.optString("rollover").takeIf { it.isNotBlank() },
                    providerId = "backend",
                    providerLabel = x.optString("source").ifBlank { "Ana Backend" },
                    isManual = false,
                    currency = x.optString("currency").takeIf { it.isNotBlank() },
                    status = when (validity) {
                        SignalValidity.VALID -> "Doğrulanmış sözleşme"
                        SignalValidity.WATCH -> "İzleme"
                        SignalValidity.INSUFFICIENT -> "Yetersiz veri"
                        SignalValidity.REJECTED -> "Reddedildi"
                    },
                    dataTimestamp = dataTimestamp,
                    isRealtime = realtime,
                    delaySeconds = delay,
                    currentSessionIncluded = currentSession,
                    receivedAt = deviceReceivedAt,
                    dataMode = mode,
                    validity = validity,
                    validityReason = reason,
                    lastTradingAt = lastTradingAt,
                    expiryAt = expiryAt,
                    exchangeTimezone = x.optString("exchangeTimezone").takeIf { it.isNotBlank() },
                    settlementType = x.optString("settlementType").takeIf { it.isNotBlank() },
                    marketDataMetadata = MarketDataQuality.metadata(
                        providerId = "backend_viop",
                        source = x.optString("source").ifBlank { "Ana Backend" },
                        marketTimestamp = dataTimestamp,
                        receivedAt = deviceReceivedAt,
                        isLive = mode == DataMode.REALTIME,
                        delaySeconds = delay,
                        lastSuccessfulUpdateAt = deviceReceivedAt,
                        fallback = false,
                        reason = reason
                    )
                ))
            }
        }
        val nextCursor = root.optString("nextCursor").trim().takeIf { it.isNotBlank() }
        val hasMore = when {
            root.has("hasMore") && !root.isNull("hasMore") -> root.optBoolean("hasMore", false)
            nextCursor != null -> true
            else -> false
        }
        val totalCount = root.optInt("totalCount", -1).takeIf { it >= 0 }
        val universeAsOf = root.optLong("universeAsOf", 0L).takeIf { it > 0L }
        return ViopUniversePage(items, nextCursor, hasMore, totalCount, universeAsOf, pageNumber)
    }

    suspend fun loadViopQuote(symbol: String): Result<ViopQuote> = withContext(Dispatchers.IO) {
        providerResult {
            val safe = URLEncoder.encode(symbol.uppercase(), "UTF-8")
            val x = readJson("/v1/viop/quote/$safe", "VİOP quote")
            val price = x.optDouble("price", Double.NaN)
            require(price.isFinite() && price > 0.0) { "QUOTE_ERROR: Geçerli son fiyat yok." }
            val exchangeTs = x.optLong("exchangeTimestamp", 0L)
            require(exchangeTs > 0L) { "STALE_DATA: Piyasa veri zamanı eksik." }
            val realtime = x.optBoolean("realtime", false)
            val delay = if (x.has("delaySeconds") && !x.isNull("delaySeconds")) x.optInt("delaySeconds") else null
            val currentSession = x.optBoolean("currentSessionIncluded", false)
            val now = System.currentTimeMillis()
            require(realtime && currentSession) { "STALE_DATA: Gerçek zamanlı/seans verisi doğrulanmadı." }
            require(delay != null && delay in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS) { "STALE_DATA: Gecikme eşiği aşıldı veya bildirilmedi." }
            require(exchangeTs <= now + RealTimeIntegrityPolicy.MAX_FUTURE_CLOCK_SKEW_MS) { "STALE_DATA: Piyasa zamanı gelecekte." }
            require(now - exchangeTs <= RealTimeIntegrityPolicy.MAX_DATA_AGE_MS) { "STALE_DATA: Quote güncel değil." }
            val responseSymbol = x.optString("symbol").trim().uppercase()
            require(responseSymbol.isNotBlank()) { "QUOTE_ERROR: Yanıtta sembol kimliği bulunmuyor." }
            require(responseSymbol == symbol.trim().uppercase()) { "QUOTE_ERROR: Sembol kimliği uyuşmuyor." }
            val oiChange = x.optDouble("openInterestChangePct", Double.NaN).takeIf { it.isFinite() }
            val oiAsOf = if (x.has("openInterestAsOfTimestamp") && !x.isNull("openInterestAsOfTimestamp")) x.optLong("openInterestAsOfTimestamp", 0L) else null
            require(oiChange == null || (oiAsOf != null && oiAsOf > 0L && oiAsOf <= exchangeTs)) {
                "QUOTE_ERROR: OI değişimi için geçerli point-in-time zaman damgası gerekli."
            }
            val providerReceivedAt = x.optLong("receivedAt", 0L).takeIf { it > 0L }
            val quoteReceivedAt = System.currentTimeMillis()
            ViopQuote(
                symbol=responseSymbol, price=price,
                bid=x.optDouble("bid", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                ask=x.optDouble("ask", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                dailyChangePct=x.optDouble("dailyChangePct", Double.NaN).takeIf { it.isFinite() },
                volume=x.optDouble("volume", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                openInterest=x.optLong("openInterest", -1L).takeIf { it >= 0L },
                exchangeTimestamp=exchangeTs, receivedAt=quoteReceivedAt, providerReceivedAt=providerReceivedAt,
                source=x.optString("source").ifBlank { "Ana Backend" }, realtime=realtime,
                delaySeconds=delay, currentSessionIncluded=currentSession,
                openInterestChangePct=oiChange, openInterestAsOfTimestamp=oiAsOf,
                marketDataMetadata=MarketDataQuality.metadata(
                    providerId = "backend_viop",
                    source = x.optString("source").ifBlank { "Ana Backend" },
                    marketTimestamp = exchangeTs,
                    receivedAt = quoteReceivedAt,
                    isLive = realtime && currentSession,
                    delaySeconds = delay,
                    lastSuccessfulUpdateAt = quoteReceivedAt,
                    fallback = false,
                    reason = "VİOP quote sembol/fiyat/timestamp/seans doğrulaması geçti."
                )
            )
        }
    }

    suspend fun loadViopHistory(symbol: String): Result<List<Candle>> = loadViopHistoryFlexible(symbol, "1y", "1d", 220)

    suspend fun loadViopHistoryFlexible(symbol: String, range: String, interval: String, minimumBars: Int = 0): Result<List<Candle>> = withContext(Dispatchers.IO) {
        providerResult {
            require(range.matches(Regex("[0-9]+[dmy]"))) { "HISTORY_ERROR: Geçersiz range." }
            require(interval in setOf("1m","3m","5m","15m","60m","1d")) { "HISTORY_ERROR: Geçersiz interval." }
            val safe = URLEncoder.encode(symbol.uppercase(), "UTF-8")
            val root = readJson("/v1/viop/history/$safe?range=$range&interval=$interval", "VİOP history")
            val array = root.optJSONArray("candles") ?: error("HISTORY_ERROR: candles alanı yok.")
            val responseSymbol = root.optString("symbol")
            val responseInterval = root.optString("interval")
            val lastBarClosed = root.has("lastBarClosed") && !root.isNull("lastBarClosed") && root.optBoolean("lastBarClosed", false)
            val exchangeTimestamp = root.optLong("exchangeTimestamp", root.optLong("lastExchangeTimestamp", 0L))
            ViopHistoryIntegrityPolicy.validateMetadata(
                requestedSymbol = symbol,
                requestedInterval = interval,
                responseSymbol = responseSymbol,
                responseInterval = responseInterval,
                lastBarClosed = lastBarClosed,
                exchangeTimestamp = exchangeTimestamp
            )?.let { error("HISTORY_ERROR: $it") }
            ViopHistoryIntegrityPolicy.validateExchangeFreshness(
                exchangeTimestamp = exchangeTimestamp,
                nowMs = System.currentTimeMillis(),
                interval = interval
            )?.let { error("HISTORY_ERROR: $it") }
            val candles = buildList {
                for (i in 0 until array.length()) {
                    val x=array.optJSONObject(i) ?: error("HISTORY_ERROR: candles[$i] nesne değil.")
                    val c=Candle(x.optLong("timestamp",0L),x.optDouble("open",Double.NaN),x.optDouble("high",Double.NaN),x.optDouble("low",Double.NaN),x.optDouble("close",Double.NaN),x.optDouble("volume",Double.NaN))
                    require(c.timestamp > 0L) { "HISTORY_ERROR: candles[$i] zaman damgası geçersiz." }
                    require(listOf(c.open,c.high,c.low,c.close,c.volume).all { it.isFinite() }) { "HISTORY_ERROR: candles[$i] sonlu olmayan değer içeriyor." }
                    require(c.open > 0.0 && c.high > 0.0 && c.low > 0.0 && c.close > 0.0 && c.volume >= 0.0) { "HISTORY_ERROR: candles[$i] fiyat/hacim kurallarına uymuyor." }
                    require(c.low <= c.high && c.open in c.low..c.high && c.close in c.low..c.high) { "HISTORY_ERROR: candles[$i] OHLC aralığı tutarsız." }
                    add(c)
                }
            }
            ViopHistoryIntegrityPolicy.validateCandles(candles)?.let { error("HISTORY_ERROR: $it") }
            ViopHistoryIntegrityPolicy.validateLastBarClosed(
                candles = candles,
                nowMs = System.currentTimeMillis(),
                interval = interval
            )?.let { error("HISTORY_ERROR: $it") }
            RealTimeIntegrityPolicy.validateCandles(candles)?.let { error("HISTORY_ERROR: $it") }
            if(minimumBars>0) require(candles.size>=minimumBars){"INSUFFICIENT_HISTORY: ${candles.size} mum; minimum $minimumBars."}
            candles
        }
    }

    suspend fun loadNews(symbol: String? = null, category: String = "ALL"): Result<List<NewsItem>> = withContext(Dispatchers.IO) {
        providerResult {
            val symbolPart = symbol?.takeIf { it.isNotBlank() }?.let { "&symbol=" + URLEncoder.encode(it.uppercase(), "UTF-8") }.orEmpty()
            val cat = URLEncoder.encode(category.uppercase(), "UTF-8")
            val root = readJson("/v1/news?category=$cat$symbolPart", "Haber sağlayıcısı")
            val array = root.optJSONArray("items") ?: return@providerResult emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val x = array.optJSONObject(i) ?: continue
                    val title = x.optString("title").trim(); val source = x.optString("source").trim(); val published = x.optLong("publishedAt", 0L)
                    if (title.isBlank() || source.isBlank() || published <= 0L) continue
                    add(NewsItem(
                        id=x.optString("id").ifBlank { "$source:$published:$i" },
                        symbol=x.optString("symbol").takeIf { it.isNotBlank() }?.uppercase(),
                        category=x.optString("category").ifBlank { "GENEL" }.uppercase(),
                        title=title, summary=x.optString("summary").takeIf { it.isNotBlank() }, source=source, publishedAt=published,
                        url=x.optString("url").takeIf { it.startsWith("https://") }, verified=x.optBoolean("verified", false),
                        receivedAt=x.optLong("receivedAt", 0L).takeIf { it > 0L },
                        availableAt=x.optLong("availableAt", 0L).takeIf { it > 0L },
                        eventTime=x.optLong("eventTime", 0L).takeIf { it > 0L },
                        claimKey=x.optString("claimKey").takeIf { it.isNotBlank() },
                        eventKey=x.optString("eventKey").takeIf { it.isNotBlank() },
                        sourceType=x.optString("sourceType").takeIf { it.isNotBlank() },
                        originSourceId=x.optString("originSourceId").takeIf { it.isNotBlank() },
                        supportsClaim=x.optBoolean("supportsClaim", true)
                    ))
                }
            }.sortedByDescending { it.publishedAt }
        }
    }

}
