package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TradingViewSignal(
    val id: String,
    val symbol: String,
    val action: String,
    val price: Double?,
    val signalTime: Long,
    val receivedAt: Long,
    val interval: String?,
    val strategy: String?,
    val message: String?,
    val rsi: Double?,
    val macd: Double?,
    val macdSignal: Double?,
    val macdHistogram: Double?,
    val emaFast: Double?,
    val emaSlow: Double?,
    val atr: Double?,
    val volume: Double?,
    val volumeRatio: Double?,
    val score: Double?,
    val trend: String?,
    val barConfirmed: Boolean?,
    val signalAgeMs: Long,
    val freshSignal: Boolean,
    val source: String,
    val marketDataRealtime: Boolean
)

class TradingViewSignalClient(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)
    private val capabilities = MarketCapabilityClient(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun loadSignals(limit: Int = 50): Result<List<TradingViewSignal>> = withContext(Dispatchers.IO) {
        runCatching {
            val capability = capabilities.load().getOrThrow()
            require(capability.features.tradingViewSignals) { "TradingView webhook sinyal özelliği backend capability tarafından etkin değil." }
            val base = settings.baseUrl.trim().trimEnd('/')
            require(ProviderReadinessService.isValidHttps(base)) { "TradingView sinyalleri için Production Backend yapılandırılmalı." }
            require(settings.apiKey.isNotBlank()) { "Backend API erişim anahtarı eksik." }
            val safeLimit = limit.coerceIn(1, 200)
            val request = BackendRequestSecurity.apply(
                Request.Builder()
                    .url("$base/v1/tradingview/signals?limit=$safeLimit")
                    .header("Accept", "application/json"),
                settings
            ).build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "TradingView sinyal servisi HTTP ${response.code} döndürdü." }
                val body = response.body?.string().orEmpty()
                require(body.isNotBlank()) { "TradingView sinyal servisi boş yanıt döndürdü." }
                val root = JSONObject(body)
                val items = root.optJSONArray("items") ?: return@use emptyList()
                buildList {
                    for (i in 0 until items.length()) {
                        val x = items.optJSONObject(i) ?: continue
                        val id = x.optString("id").trim()
                        val symbol = x.optString("symbol").trim().uppercase()
                        val action = x.optString("action").trim().uppercase()
                        val signalTime = x.optLong("signalTime", 0L)
                        val receivedAt = x.optLong("receivedAt", 0L)
                        if (id.isBlank() || symbol.isBlank() || action.isBlank() || signalTime <= 0L || receivedAt <= 0L) continue
                        add(
                            TradingViewSignal(
                                id = id,
                                symbol = symbol,
                                action = action,
                                price = x.optDouble("price", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                                signalTime = signalTime,
                                receivedAt = receivedAt,
                                interval = x.optString("interval").takeIf { it.isNotBlank() },
                                strategy = x.optString("strategy").takeIf { it.isNotBlank() },
                                message = x.optString("message").takeIf { it.isNotBlank() },
                                rsi = x.optDouble("rsi", Double.NaN).takeIf { it.isFinite() },
                                macd = x.optDouble("macd", Double.NaN).takeIf { it.isFinite() },
                                macdSignal = x.optDouble("macdSignal", Double.NaN).takeIf { it.isFinite() },
                                macdHistogram = x.optDouble("macdHistogram", Double.NaN).takeIf { it.isFinite() },
                                emaFast = x.optDouble("emaFast", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                                emaSlow = x.optDouble("emaSlow", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                                atr = x.optDouble("atr", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                                volume = x.optDouble("volume", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                                volumeRatio = x.optDouble("volumeRatio", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                                score = x.optDouble("score", Double.NaN).takeIf { it.isFinite() && it in 0.0..100.0 },
                                trend = x.optString("trend").takeIf { it.isNotBlank() },
                                barConfirmed = if (x.has("barConfirmed") && !x.isNull("barConfirmed")) x.optBoolean("barConfirmed") else null,
                                signalAgeMs = x.optLong("signalAgeMs", 0L).coerceAtLeast(0L),
                                freshSignal = x.optBoolean("freshSignal", false),
                                source = x.optString("source").ifBlank { "TRADINGVIEW_WEBHOOK" },
                                marketDataRealtime = x.optBoolean("marketDataRealtime", false)
                            )
                        )
                    }
                }
            }
        }
    }
}
