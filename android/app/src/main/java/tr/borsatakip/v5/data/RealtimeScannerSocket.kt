package tr.borsatakip.v5.data

import android.content.Context
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import tr.borsatakip.v5.model.ScanMode
import java.util.concurrent.TimeUnit
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Realtime scanner WebSocket. REST and WebSocket share the same analysis/cadence/mode contract. */
class RealtimeScannerSocket(context: Context) {
    interface Listener {
        fun onSubscribed() {}
        fun onSnapshot(snapshot: RealtimeScannerClient.Snapshot)
        fun onDisconnected(reason: String) {}
        fun onError(message: String) {}
    }

    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)
    private val parser = RealtimeScannerClient(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null

    fun connect(
        minScore: Int = 55,
        limit: Int = 50,
        actionableOnly: Boolean = false,
        minIntervalMs: Int = 1500,
        analysisTimeframeMinutes: Int = settings.analysisTimeframeMinutes,
        scanCadenceMinutes: Int = settings.scanCadenceMinutes,
        scanMode: ScanMode = ScanMode.LIVE,
        listener: Listener
    ) {
        close()
        val base = settings.baseUrl.trim().trimEnd('/')
        val key = settings.apiKey.trim()
        if (!ProviderReadinessService.isValidHttps(base) || key.isBlank()) {
            listener.onError("Canlı scanner için HTTPS backend ve API anahtarı gerekli.")
            return
        }
        val wsBase = "wss://" + base.removePrefix("https://")
        val request = BackendRequestSecurity.apply(
            Request.Builder().url("$wsBase/v1/scanner/live"),
            settings
        ).build()
        if (analysisTimeframeMinutes !in 1..239) {
            listener.onError("Realtime scanner 1 GÜN periyodunu 240 DK olarak taklit etmez. 1 GÜN için BIST Tarama ekranındaki manuel gerçek günlük OHLCV taramasını kullanın.")
            return
        }
        val requestedTimeframe = analysisTimeframeMinutes
        val requestedCadence = scanCadenceMinutes.coerceIn(1, 1440)
        var handshakeWallMillis = System.currentTimeMillis()
        var handshakeElapsedMillis = SystemClock.elapsedRealtime()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handshakeElapsedMillis = SystemClock.elapsedRealtime()
                val httpDate = parseHttpDateMillis(response.header("Date"))
                if (httpDate == null) {
                    listener.onError("WebSocket HTTPS Date başlığı olmadan trusted-time bootstrap yapılamaz.")
                    webSocket.close(1008, "trusted-time-bootstrap-missing")
                    return
                }
                handshakeWallMillis = httpDate
                val subscribe = JSONObject()
                    .put("action", "subscribe")
                    .put("minScore", minScore.coerceIn(0, 100))
                    .put("limit", limit.coerceIn(1, 50))
                    .put("actionableOnly", actionableOnly)
                    .put("minIntervalMs", minIntervalMs.coerceIn(250, 5000))
                    .put("analysisTimeframeMinutes", requestedTimeframe)
                    .put("scanCadenceMinutes", requestedCadence)
                    .put("scanMode", scanMode.name)
                webSocket.send(subscribe.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    when (root.optString("type")) {
                        "scanner_subscribed" -> {
                            val echoedTimeframe = root.optInt("analysisTimeframeMinutes", root.optInt("intervalMinutes", 0))
                            val echoedCadence = root.optInt("scanCadenceMinutes", 0)
                            val echoedMode = root.optString("scanMode")
                            require(echoedTimeframe == requestedTimeframe && echoedCadence == requestedCadence && echoedMode == scanMode.name) {
                                "WebSocket tarama sözleşmesi backend ile uyuşmuyor."
                            }
                            listener.onSubscribed()
                        }
                        "scanner_snapshot" -> {
                            val elapsedNow = SystemClock.elapsedRealtime()
                            val bootstrapTrustedNow = handshakeWallMillis + (elapsedNow - handshakeElapsedMillis).coerceAtLeast(0L)
                            val snapshot = parser.validateReplay(parser.parse(root, bootstrapTrustedNow, elapsedNow))
                            require(snapshot.analysisTimeframeMinutes == requestedTimeframe)
                            require(snapshot.scanCadenceMinutes == requestedCadence)
                            require(snapshot.scanMode == scanMode)
                            listener.onSnapshot(snapshot)
                        }
                        "error" -> {
                            val code = root.optString("code").ifBlank { "BACKEND_ERROR" }
                            val detail = root.optString("detail").ifBlank { "Backend realtime scanner hatası." }
                            listener.onError("$code • $detail")
                        }
                    }
                }.onFailure { listener.onError(it.message ?: "Canlı scanner yanıtı çözümlenemedi.") }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                listener.onDisconnected("$code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "Canlı scanner bağlantısı kesildi.")
            }
        })
    }

    fun close() {
        socket?.close(1000, "client-close")
        socket = null
    }
    private fun parseHttpDateMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }

}
