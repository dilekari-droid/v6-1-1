package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MarketCapabilityClient(context: Context) {
    private val appContext = context.applicationContext
    data class BatchPolicy(
        val maxSymbols: Int,
        val readTimeoutMs: Long,
        val callTimeoutMs: Long,
        val outerTimeoutMs: Long
    )

    data class MarketCapability(
        val ready: Boolean,
        val supported: Boolean,
        val discovery: Boolean,
        val marketData: Boolean,
        val historicalData: Boolean,
        val realtime: Boolean,
        val realtimeReady: Boolean,
        val analysisReady: Boolean,
        val symbolCount: Int,
        val provider: String?,
        val reasonCode: String?,
        val message: String?
    )

    data class FeatureCapability(
        val bistSnapshotBatch: Boolean,
        val dynamicScanner: Boolean,
        val realtimeScannerRest: Boolean,
        val liveMarketWebSocket: Boolean,
        val realtimeScannerWebSocket: Boolean,
        val attestationReady: Boolean,
        val viopContractsReady: Boolean,
        val tradingViewSignals: Boolean,
        val researchFoundation: Boolean,
        val allTimeHistory: Boolean,
        val barHistoryCache: Boolean,
        val ingressRateLimit: Boolean,
        val shortLivedSessionAuth: Boolean,
        val fullBistFiveMinuteSla: Boolean
    )

    data class Snapshot(
        val version: String,
        val providerReady: Boolean,
        val multiMarketReady: Boolean,
        val primaryProvider: String,
        val tradeWizeState: String,
        val tradeWizeAdapterVerified: Boolean,
        val features: FeatureCapability,
        val batchPolicy: BatchPolicy,
        val markets: Map<String, MarketCapability>
    ) {
        fun market(name: String): MarketCapability? = markets[name.trim().uppercase()]
    }

    private val settings = SettingsStore(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun load(): Result<Snapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val base=settings.baseUrl.trim().trimEnd('/')
            require(ProviderReadinessService.isValidHttps(base)) { "HTTPS backend adresi gerekli." }
            require(settings.apiKey.isNotBlank()) { "Backend API anahtarı gerekli." }
            val url=base.toHttpUrl().newBuilder().addPathSegments("v1/provider/capabilities").build()
            fun request() = BackendRequestSecurity.apply(Request.Builder().url(url), settings)
                .header("Accept", "application/json").get().build()
            var response = client.newCall(request()).execute()
            if (response.code == 401 && settings.backendSessionToken.isNotBlank()) {
                response.close()
                settings.clearBackendSessionToken()
                response = client.newCall(request()).execute()
            }
            response.use { finalResponse ->
                val body=finalResponse.body?.string().orEmpty()
                require(finalResponse.isSuccessful) { "Provider capability HTTP ${finalResponse.code}: ${body.take(240)}" }
                require(body.isNotBlank()) { "Provider capability yanıtı boş." }
                val snapshot = parse(JSONObject(body))
                if (snapshot.features.shortLivedSessionAuth) {
                    BackendSessionClient(appContext).refreshIfNeeded(enabled = true).getOrThrow()
                } else {
                    settings.clearBackendSessionToken()
                }
                snapshot
            }
        }
    }

    companion object {
        internal fun parse(root: JSONObject): Snapshot {
            require(root.optBoolean("ok",false)) { "Provider capability ok=false" }
            val marketsJson=root.optJSONObject("markets") ?: JSONObject()
            val markets=linkedMapOf<String,MarketCapability>()
            listOf("BIST","VIOP","COMMODITY","FX","INDEX").forEach { key ->
                val x=marketsJson.optJSONObject(key) ?: return@forEach
                markets[key]=MarketCapability(
                    ready=x.optBoolean("ready",false),
                    supported=x.optBoolean("supported",false),
                    discovery=x.optBoolean("discovery",false),
                    marketData=x.optBoolean("marketData",false),
                    historicalData=x.optBoolean("historicalData",false),
                    realtime=x.optBoolean("realtime",false),
                    realtimeReady=x.optBoolean("realtimeReady",x.optBoolean("ready",false)),
                    analysisReady=x.optBoolean("analysisReady",x.optBoolean("historicalData",false) && x.optBoolean("discovery",false)),
                    symbolCount=x.optInt("symbolCount",0),
                    provider=x.optString("provider").takeIf { it.isNotBlank() },
                    reasonCode=x.optString("reasonCode").takeIf { it.isNotBlank() },
                    message=x.optString("message").takeIf { it.isNotBlank() }
                )
            }
            val tw=root.optJSONObject("tradeWize") ?: JSONObject()
            val featuresJson=root.optJSONObject("features") ?: JSONObject()
            val scanPolicy=root.optJSONObject("scanPolicy") ?: JSONObject()
            val batchPolicy=BatchPolicy(
                maxSymbols=scanPolicy.optInt("snapshotBatchMaxSymbols",20).coerceIn(1,100),
                readTimeoutMs=scanPolicy.optLong("snapshotBatchReadTimeoutMs",175_000L).coerceIn(30_000L,600_000L),
                callTimeoutMs=scanPolicy.optLong("snapshotBatchCallTimeoutMs",180_000L).coerceIn(30_000L,600_000L),
                outerTimeoutMs=scanPolicy.optLong("snapshotBatchOuterTimeoutMs",185_000L).coerceIn(30_000L,650_000L)
            )
            val features=FeatureCapability(
                bistSnapshotBatch=featuresJson.optBoolean("bistSnapshotBatch",false),
                dynamicScanner=featuresJson.optBoolean("dynamicScanner",false),
                realtimeScannerRest=featuresJson.optBoolean("realtimeScannerRest",false),
                liveMarketWebSocket=featuresJson.optBoolean("liveMarketWebSocket",false),
                realtimeScannerWebSocket=featuresJson.optBoolean("realtimeScannerWebSocket",false),
                attestationReady=featuresJson.optBoolean("attestationReady",false),
                viopContractsReady=featuresJson.optBoolean("viopContractsReady",false),
                tradingViewSignals=featuresJson.optBoolean("tradingViewSignals",false),
                researchFoundation=featuresJson.optBoolean("researchFoundation",false),
                allTimeHistory=featuresJson.optBoolean("allTimeHistory",false),
                barHistoryCache=featuresJson.optBoolean("barHistoryCache",false),
                ingressRateLimit=featuresJson.optBoolean("ingressRateLimit",false),
                shortLivedSessionAuth=featuresJson.optBoolean("shortLivedSessionAuth",false),
                fullBistFiveMinuteSla=featuresJson.optBoolean("fullBistFiveMinuteSla",false)
            )
            return Snapshot(
                version=root.optString("version","unknown"),
                providerReady=root.optBoolean("providerReady",false),
                multiMarketReady=root.optBoolean("multiMarketReady",false),
                primaryProvider=root.optString("primaryConfiguredProvider","Provider bilinmiyor"),
                tradeWizeState=tw.optString("state","UNKNOWN"),
                tradeWizeAdapterVerified=tw.optBoolean("adapterVerified",false),
                features=features,
                batchPolicy=batchPolicy,
                markets=markets
            )
        }
    }
}
