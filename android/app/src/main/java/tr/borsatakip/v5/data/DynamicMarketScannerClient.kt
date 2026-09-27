package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class DynamicMarketScannerClient(context: Context) {
    data class Item(
        val symbol:String,
        val name:String,
        val market:String,
        val assetType:String,
        val signal:String,
        val technicalSignal:String,
        val verificationStatus:String,
        val publicationMode:String,
        val analysisTimeframe:String,
        val score:Int,
        val confidence:Int,
        val confidenceBand:String,
        val price:Double?,
        val dailyChangePct:Double?,
        val volume:Double?,
        val rsi14:Double?,
        val ema20:Double?,
        val ema50:Double?,
        val atrPct:Double?,
        val delaySeconds:Int?,
        val realtime:Boolean,
        val timestamp:Long?,
        val source:String,
        val trendDirection:String = signal,
        val trendStrength:Int = score,
        val signalStrength:Int = score,
        val finalStrength:Int = score,
        val signalType:String = signal,
        val dataValidated:Boolean = realtime && (verificationStatus.equals("VERIFIED", ignoreCase = true) || verificationStatus.equals("VALID", ignoreCase = true)),
        val analysisValidated:Boolean = verificationStatus.equals("VERIFIED", ignoreCase = true) || verificationStatus.equals("VALID", ignoreCase = true),
        val providerStatus:String = if (realtime) "READY" else "UNAVAILABLE"
    )

    data class Failure(val symbol:String?, val market:String?, val code:String, val message:String)

    data class ScanProgress(
        val scannedSymbols:Int,
        val universeCount:Int,
        val successfulCount:Int,
        val failedCount:Int
    ) {
        val percent:Int get() = if (universeCount <= 0) 0 else ((scannedSymbols.toLong() * 100L) / universeCount.toLong()).toInt().coerceIn(0,100)
    }

    data class Response(
        val providerReady:Boolean,
        val globalProviderReady:Boolean,
        val multiMarketReady:Boolean,
        val realtimeReady:Boolean,
        val analysisReady:Boolean,
        val analysisMode:String,
        val timeframe:String,
        val partial:Boolean,
        val engineVersion:String,
        val source:String,
        val scannedSymbols:Int,
        val successfulCount:Int,
        val failedCount:Int,
        val universeCount:Int,
        val remainingSymbols:Int,
        val nextOffset:Int,
        val coverageComplete:Boolean,
        val items:List<Item>,
        val failures:List<Failure>,
        val batchSize:Int,
        val concurrency:Int,
        val pacingMs:Int,
        val cacheTtlMs:Long
    )

    private val settings=SettingsStore(context)
    private val client=OkHttpClient.Builder()
        .connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(180,TimeUnit.SECONDS)
        .callTimeout(200,TimeUnit.SECONDS)
        .build()

    suspend fun loadPage(
        market:String,
        assetType:String="ALL",
        timeframe:String="5m",
        offset:Int=0,
        limit:Int=SERVER_PAGE_REQUEST,
        minScore:Int=0,
        includeWatch:Boolean=true
    ):Result<Response> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedTimeframe = timeframe.trim().lowercase()
            require(isDynamicTimeframeSupported(normalizedTimeframe)) {
                "Dinamik tarama özel timeframe desteklemiyor: $timeframe. 1/3/5/10/15/30/60 DK veya 1 GÜN seçin."
            }
            val base=settings.baseUrl.trim().trimEnd('/')
            require(ProviderReadinessService.isValidHttps(base)) { "HTTPS backend adresi gerekli." }
            require(settings.apiKey.isNotBlank()) { "Backend API anahtarı gerekli." }
            val url=base.toHttpUrl().newBuilder()
                .addPathSegments("v1/scanner/opportunities")
                .addQueryParameter("market",market.uppercase())
                .addQueryParameter("assetType",assetType.uppercase())
                .addQueryParameter("timeframe",normalizedTimeframe)
                .addQueryParameter("offset",offset.coerceAtLeast(0).toString())
                .addQueryParameter("limit",limit.coerceIn(1,SERVER_PAGE_REQUEST).toString())
                .addQueryParameter("minScore",minScore.coerceIn(0,100).toString())
                .addQueryParameter("includeWatch",includeWatch.toString())
                .build()
            val request = BackendRequestSecurity.apply(Request.Builder().url(url), settings)
                .header("Accept", "application/json").get().build()
            client.newCall(request).execute().use { response ->
                val body=response.body?.string().orEmpty()
                require(body.isNotBlank()) { "Tarama yanıtı boş." }
                val json=JSONObject(body)
                if (!response.isSuccessful) {
                    val detail=json.optString("detail")
                    val code=json.optString("code","HTTP_${response.code}")
                    val message=json.optString("message",detail.ifBlank { json.optJSONArray("failures")?.optJSONObject(0)?.optString("message") ?: "Tarama kullanılamıyor" })
                    throw IllegalStateException("$code • $message")
                }
                parse(json)
            }
        }
    }

    suspend fun loadAll(
        market:String,
        assetType:String="ALL",
        timeframe:String="5m",
        minScore:Int=0,
        includeWatch:Boolean=true,
        onProgress:(suspend (ScanProgress)->Unit)?=null
    ):Result<Response> = withContext(Dispatchers.IO) {
        runCatching {
            var offset=0
            var expectedUniverse:Int?=null
            var providerReady=true
            var globalProviderReady=true
            var multiMarketReady=true
            var realtimeReady=true
            var analysisReady=false
            var partial=false
            var analysisMode="UNAVAILABLE"
            var engineVersion="unknown"
            var source="Provider bilinmiyor"
            var successful=0
            var failed=0
            var scanned=0
            var remaining=Int.MAX_VALUE
            var nextOffset=0
            var batchSize=0
            var concurrency=0
            var pacingMs=0
            var cacheTtlMs=0L
            val itemMap=linkedMapOf<String,Item>()
            val failures=mutableListOf<Failure>()
            var pages=0

            while (true) {
                coroutineContext.ensureActive()
                require(pages < MAX_PAGES) { "Tarama sayfalaması güvenlik sınırını aştı." }
                val page=loadPage(market,assetType,timeframe,offset,SERVER_PAGE_REQUEST,minScore,includeWatch).getOrThrow()
                pages++

                if (expectedUniverse==null) expectedUniverse=page.universeCount
                require(page.universeCount==expectedUniverse) {
                    "Tarama sırasında varlık evreni değişti (${expectedUniverse} → ${page.universeCount}); güvenli tam tarama için yeniden başlatın."
                }
                require(page.nextOffset>=offset) { "Backend nextOffset geriye gitti." }
                if (!page.coverageComplete) {
                    require(page.scannedSymbols>0 && page.nextOffset>offset) { "Backend coverageComplete=false döndürdü ancak sayfalama ilerlemedi." }
                }

                providerReady = providerReady && page.providerReady
                globalProviderReady = globalProviderReady && page.globalProviderReady
                multiMarketReady = multiMarketReady && page.multiMarketReady
                realtimeReady = realtimeReady && page.realtimeReady
                analysisReady = analysisReady || page.analysisReady
                partial = partial || page.partial || page.failedCount > 0 || page.failures.isNotEmpty()
                analysisMode = when {
                    analysisMode=="REALTIME" && page.analysisMode=="REALTIME" -> "REALTIME"
                    page.analysisMode.contains("DELAYED",ignoreCase=true) || analysisMode.contains("DELAYED",ignoreCase=true) -> "DELAYED_ANALYSIS"
                    page.analysisMode=="REALTIME" -> "REALTIME"
                    else -> page.analysisMode
                }
                engineVersion=page.engineVersion
                source=page.source
                successful+=page.successfulCount
                failed+=page.failedCount
                scanned+=page.scannedSymbols
                remaining=page.remainingSymbols
                nextOffset=page.nextOffset
                batchSize=page.batchSize
                concurrency=page.concurrency
                pacingMs=page.pacingMs
                cacheTtlMs=page.cacheTtlMs
                page.items.forEach { itemMap["${it.market}:${it.symbol}"]=it }
                failures+=page.failures
                onProgress?.invoke(
                    ScanProgress(
                        scannedSymbols = scanned,
                        universeCount = expectedUniverse ?: page.universeCount,
                        successfulCount = successful,
                        failedCount = failed
                    )
                )

                if (page.coverageComplete) break
                offset=page.nextOffset
            }

            val universe=expectedUniverse ?: 0
            require(scanned>=universe || universe==0) { "Backend tam kapsama bildirdi fakat $scanned/$universe sembol işlendi." }
            Response(
                providerReady=providerReady,
                globalProviderReady=globalProviderReady,
                multiMarketReady=multiMarketReady,
                realtimeReady=realtimeReady,
                analysisReady=analysisReady,
                analysisMode=analysisMode,
                timeframe=timeframe.lowercase(),
                partial=partial,
                engineVersion=engineVersion,
                source=source,
                scannedSymbols=scanned,
                successfulCount=successful,
                failedCount=failed,
                universeCount=universe,
                remainingSymbols=remaining.coerceAtLeast(0),
                nextOffset=nextOffset,
                coverageComplete=true,
                items=itemMap.values.toList(),
                failures=failures,
                batchSize=batchSize,
                concurrency=concurrency,
                pacingMs=pacingMs,
                cacheTtlMs=cacheTtlMs
            )
        }
    }

    companion object {
        private const val SERVER_PAGE_REQUEST=60
        private const val MAX_PAGES=40
        private val DYNAMIC_TIMEFRAMES=setOf("1m","3m","5m","10m","15m","30m","60m","1d")

        internal fun isDynamicTimeframeSupported(value:String):Boolean = value.trim().lowercase() in DYNAMIC_TIMEFRAMES

        private fun JSONObject.doubleOrNull(key:String):Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null
        private fun JSONObject.longOrNull(key:String):Long? = if (has(key) && !isNull(key)) optLong(key) else null

        internal fun parse(root:JSONObject):Response {
            require(root.optBoolean("success",false)) { root.optString("code",root.optString("detail","SCAN_FAILED")) }
            val rootProviderReady = root.optBoolean("providerReady", false)
            val rootRealtimeReady = root.optBoolean("realtimeReady", rootProviderReady)
            val rootAnalysisReady = root.optBoolean("analysisReady", false)
            val itemsJson=root.optJSONArray("items")
            val items=buildList {
                if (itemsJson!=null) for (i in 0 until itemsJson.length()) {
                    val x=itemsJson.optJSONObject(i) ?: continue
                    add(Item(
                        symbol=x.optString("symbol"), name=x.optString("name",x.optString("symbol")),
                        market=x.optString("market"), assetType=x.optString("assetType"),
                        signal=x.optString("signal","WATCH"), technicalSignal=x.optString("technicalSignal",x.optString("signal","WATCH")), verificationStatus=x.optString("verificationStatus","INVALID"),
                        publicationMode=x.optString("publicationMode","OBSERVATION_ONLY"), analysisTimeframe=x.optString("analysisTimeframe",root.optString("timeframe","unknown")),
                        score=x.optInt("score",0), confidence=x.optInt("dataConfidence",0), confidenceBand=x.optString("dataConfidenceBand","LOW"),
                        price=x.doubleOrNull("currentPrice") ?: x.doubleOrNull("price"), dailyChangePct=x.doubleOrNull("dailyChangePct"),
                        volume=x.doubleOrNull("volume"), rsi14=x.doubleOrNull("rsi14"), ema20=x.doubleOrNull("ema20"), ema50=x.doubleOrNull("ema50"),
                        atrPct=x.doubleOrNull("atrPct"), delaySeconds=if (x.has("delaySeconds")&&!x.isNull("delaySeconds")) x.optInt("delaySeconds") else null,
                        realtime=if (x.has("realtime")) x.optBoolean("realtime",false) else rootRealtimeReady, timestamp=x.longOrNull("exchangeTimestamp") ?: x.longOrNull("dataTimestamp"),
                        source=x.optString("source",root.optString("source","Provider bilinmiyor")),
                        trendDirection=x.optString("trendDirection").takeIf { it.isNotBlank() } ?: x.optString("technicalSignal").takeIf { it.isNotBlank() } ?: x.optString("signal","WATCH"),
                        trendStrength=x.optInt("trendStrength",x.optInt("score",0)),
                        signalStrength=x.optInt("signalStrength",x.optInt("score",0)),
                        finalStrength=x.optInt("finalStrength",x.optInt("finalSignalScore",x.optInt("score",0))),
                        signalType=x.optString("signalType",x.optString("signal","WATCH")),
                        dataValidated=if (x.has("dataValidated")) x.optBoolean("dataValidated",false) else (rootRealtimeReady && x.optString("verificationStatus").uppercase() in setOf("VERIFIED","VALID")),
                        analysisValidated=if (x.has("analysisValidated")) x.optBoolean("analysisValidated",false) else (rootAnalysisReady && x.optString("verificationStatus").uppercase() in setOf("VERIFIED","VALID")),
                        providerStatus=x.optString("providerStatus").takeIf { it.isNotBlank() } ?: if(rootProviderReady && rootRealtimeReady) "READY" else "UNAVAILABLE"
                    ))
                }
            }
            val failuresJson=root.optJSONArray("failures")
            val failures=buildList {
                if (failuresJson!=null) for (i in 0 until failuresJson.length()) {
                    val x=failuresJson.optJSONObject(i) ?: continue
                    add(Failure(x.optString("symbol").takeIf { it.isNotBlank() },x.optString("market").takeIf { it.isNotBlank() },x.optString("code","provider_error"),x.optString("message","Provider hatası")))
                }
            }
            val policy=root.optJSONObject("scanPolicy") ?: JSONObject()
            val scanned=root.optInt("scannedSymbols",0)
            val universe=root.optInt("universeCount",scanned)
            val remaining=root.optInt("remainingSymbols",(universe-scanned).coerceAtLeast(0))
            val next=root.optInt("nextOffset",scanned)
            val complete=root.optBoolean("coverageComplete",remaining==0)
            require(complete || remaining>0) { "coverageComplete=false fakat remainingSymbols geçersiz." }
            return Response(
                providerReady=root.optBoolean("providerReady",false),
                globalProviderReady=root.optBoolean("globalProviderReady",root.optBoolean("providerReady",false)),
                multiMarketReady=root.optBoolean("multiMarketReady",false),
                realtimeReady=root.optBoolean("realtimeReady",root.optBoolean("providerReady",false)),
                analysisReady=root.optBoolean("analysisReady",false),
                analysisMode=root.optString("analysisMode","UNAVAILABLE"),
                timeframe=root.optString("timeframe",root.optString("requestedTimeframe","unknown")),
                partial=root.optBoolean("partial",!complete),
                engineVersion=root.optString("engineVersion","unknown"), source=root.optString("source","Provider bilinmiyor"),
                scannedSymbols=scanned, successfulCount=root.optInt("successfulCount",items.size), failedCount=root.optInt("failedCount",failures.size),
                universeCount=universe, remainingSymbols=remaining, nextOffset=next, coverageComplete=complete,
                items=items, failures=failures,
                batchSize=policy.optInt("batchSize",0), concurrency=policy.optInt("concurrency",0), pacingMs=policy.optInt("pacingMs",0), cacheTtlMs=policy.optLong("cacheTtlMs",0L)
            )
        }
    }
}
