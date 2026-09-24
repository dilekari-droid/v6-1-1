package tr.borsatakip.v5.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LiveMarketSocket(context: Context) {
    private val settings = SettingsStore(context)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastSequenceBySymbol = mutableMapOf<String, Long>()
    private val lastTimestampBySymbol = mutableMapOf<String, Long>()
    private val lastCandleTimestampByKey = mutableMapOf<String, Long>()
    private val seenSignalIds = LinkedHashSet<String>()
    @Volatile private var lastMessageAt: Long = 0L
    @Volatile private var reconnectAttempt: Int = 0
    @Volatile private var manuallyClosed = true
    @Volatile private var lifecyclePaused = false
    private var desiredSubscription: Subscription? = null

    private data class Subscription(
        val symbols: List<String>,
        val listener: Listener,
        val includeSignals: Boolean,
        val includeCandles: Boolean,
        val candleInterval: String
    )

    private val watchdog = object : Runnable {
        override fun run() {
            val subscription = desiredSubscription
            if (!manuallyClosed && !lifecyclePaused && subscription != null && socket != null) {
                val age = System.currentTimeMillis() - lastMessageAt
                if (lastMessageAt > 0L && age > STALE_SOCKET_MS) {
                    ProviderHealthRegistry.recordFailure("backend_ws", "Uzun süre yeni tick/mesaj gelmedi: ${age}ms")
                    subscription.listener.onError("Canlı bağlantı eski: ${age / 1000}s yeni veri gelmedi; yeniden bağlanılıyor.")
                    closeTransport("stale_socket")
                    scheduleReconnect("stale_socket")
                }
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    init {
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        (context as? LifecycleOwner)?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                lifecyclePaused = true
                closeTransport("lifecycle_stop")
            }

            override fun onStart(owner: LifecycleOwner) {
                val shouldReconnect = lifecyclePaused && !manuallyClosed && desiredSubscription != null
                lifecyclePaused = false
                if (shouldReconnect) openDesiredSubscription()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                close()
                mainHandler.removeCallbacks(watchdog)
            }
        })
    }

    data class Tick(
        val symbol: String,
        val price: Double,
        val bid: Double?,
        val ask: Double?,
        val changePct: Double?,
        val volume: Double?,
        val previousClose: Double?,
        val timestamp: Long,
        val market: String,
        val source: String?,
        val providerId: String?,
        val sequence: Long,
        val delaySeconds: Int,
        val currentSessionIncluded: Boolean,
        val marketDataMetadata: tr.borsatakip.v5.model.MarketDataMetadata? = null
    )

    data class CandleUpdate(
        val symbol: String,
        val interval: String,
        val timestamp: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
        val source: String?,
        val lastBarClosed: Boolean?
    )

    data class TradingViewSignalEvent(
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
        val macdHistogram: Double?,
        val volumeRatio: Double?,
        val score: Double?,
        val trend: String?,
        val freshSignal: Boolean,
        val source: String
    )

    interface Listener {
        fun onConnected()
        fun onTick(tick: Tick)
        fun onDisconnected(reason: String)
        fun onError(message: String)
        fun onSubscribed(message: String) {}
        fun onCandle(candle: CandleUpdate) {}
        fun onTradingViewSignal(signal: TradingViewSignalEvent) {}
    }

    fun connect(
        symbols: List<String>,
        listener: Listener,
        includeSignals: Boolean = true,
        includeCandles: Boolean = false,
        candleInterval: String = "1m"
    ) {
        val normalizedSymbols = symbols.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        if (normalizedSymbols.isEmpty()) {
            listener.onError("Canlı veri için en az bir geçerli sembol gerekli.")
            return
        }
        manuallyClosed = false
        lifecyclePaused = false
        reconnectAttempt = 0
        lastSequenceBySymbol.clear()
        lastTimestampBySymbol.clear()
        lastCandleTimestampByKey.clear()
        seenSignalIds.clear()
        desiredSubscription = Subscription(normalizedSymbols, listener, includeSignals, includeCandles, candleInterval)
        closeTransport("replace_subscription")
        openDesiredSubscription()
    }

    private fun openDesiredSubscription() {
        if (manuallyClosed || lifecyclePaused || socket != null) return
        val subscription = desiredSubscription ?: return
        val base = settings.baseUrl.trim().removeSuffix("/")
        if (!ProviderReadinessService.isValidHttps(base)) {
            subscription.listener.onError("Canlı veri için HTTPS backend adresi gerekli.")
            return
        }
        if (settings.apiKey.isBlank()) {
            subscription.listener.onError("Canlı veri için backend API anahtarı gerekli.")
            return
        }
        val subscribed = subscription.symbols.toSet()
        val wsUrl = "wss://" + base.removePrefix("https://") + "/v1/live"
        val request = BackendRequestSecurity.apply(Request.Builder().url(wsUrl), settings).build()
        lastMessageAt = System.currentTimeMillis()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== socket || manuallyClosed || lifecyclePaused) return
                reconnectAttempt = 0
                lastMessageAt = System.currentTimeMillis()
                ProviderHealthRegistry.recordSuccess("backend_ws", "open:${subscription.symbols.joinToString(",")}:${lastMessageAt}", realtime = true)
                subscription.listener.onConnected()
                val payload = JSONObject()
                    .put("action", "subscribe")
                    .put("symbols", subscription.symbols)
                    .put("includeSignals", subscription.includeSignals)
                    .put("includeCandles", subscription.includeCandles)
                    .put("candleInterval", subscription.candleInterval)
                    .put("signalsAfter", System.currentTimeMillis())
                webSocket.send(payload.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket || manuallyClosed || lifecyclePaused) return
                lastMessageAt = System.currentTimeMillis()
                try {
                    val x = JSONObject(text)
                    when (x.optString("type")) {
                        "subscribed" -> subscription.listener.onSubscribed(x.optString("message").ifBlank { "Canlı kanal hazır." })
                        "tick" -> parseTick(x, subscribed)?.let(subscription.listener::onTick)
                        "candle" -> parseCandle(x, subscribed)?.let(subscription.listener::onCandle)
                        "tradingview_signal" -> parseTradingViewSignal(x, subscribed)?.let(subscription.listener::onTradingViewSignal)
                        "error" -> {
                            val backendError = parseBackendError(x)
                            subscription.listener.onError(backendError)
                            if (x.optString("code") == "FEATURE_DISABLED") {
                                manuallyClosed = true
                                desiredSubscription = null
                                webSocket.close(1000, "feature-disabled")
                            }
                        }
                    }
                } catch (e: Exception) {
                    subscription.listener.onError(e.message ?: "Canlı veri mesajı çözümlenemedi.")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!manuallyClosed) subscription.listener.onDisconnected(reason.ifBlank { "Bağlantı kapatılıyor" })
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === socket) socket = null
                if (!manuallyClosed && !lifecyclePaused) {
                    ProviderHealthRegistry.recordFailure("backend_ws", "WebSocket kapandı: $code ${reason.ifBlank { "neden yok" }}")
                    subscription.listener.onDisconnected(reason.ifBlank { "Bağlantı kapandı" })
                    scheduleReconnect("closed_$code")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === socket) socket = null
                if (!manuallyClosed && !lifecyclePaused) {
                    val http = response?.code?.let { " HTTP $it" }.orEmpty()
                    ProviderHealthRegistry.recordFailure("backend_ws", "${t.message ?: t.javaClass.simpleName}$http")
                    subscription.listener.onError((t.message ?: "Canlı veri bağlantısı kurulamadı.") + http)
                    scheduleReconnect("failure")
                }
            }
        })
    }

    private fun scheduleReconnect(reason: String) {
        if (manuallyClosed || lifecyclePaused || desiredSubscription == null || socket != null) return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            desiredSubscription?.listener?.onError("Canlı bağlantı $MAX_RECONNECT_ATTEMPTS denemeden sonra kurulamadı; otomatik retry durduruldu ($reason).")
            return
        }
        val delayMs = (RECONNECT_BASE_MS shl reconnectAttempt).coerceAtMost(RECONNECT_MAX_MS)
        reconnectAttempt += 1
        mainHandler.postDelayed({
            if (!manuallyClosed && !lifecyclePaused && socket == null) openDesiredSubscription()
        }, delayMs)
    }

    private fun closeTransport(reason: String) {
        val existing = socket
        socket = null
        existing?.cancel()
        if (existing != null && reason != "replace_subscription" && reason != "lifecycle_stop") {
            desiredSubscription?.listener?.onDisconnected(reason)
        }
    }

    private fun parseTick(x: JSONObject, subscribed: Set<String>): Tick? {
        val price = x.optDouble("price", Double.NaN)
        val symbol = x.optString("symbol").trim().uppercase()
        val timestamp = x.optLong("timestamp", 0L)
        val delay = x.optInt("delaySeconds", -1)
        val sequence = x.optLong("sequence", -1L)
        val market = x.optString("market").ifBlank { "BIST" }.uppercase()
        val now = System.currentTimeMillis()
        val previousSequence = lastSequenceBySymbol[symbol]
        val previousTimestamp = lastTimestampBySymbol[symbol]
        val fingerprint = "$symbol|$price|$timestamp|$sequence"
        if (symbol !in subscribed || !price.isFinite() || price <= 0.0 || timestamp <= 0L ||
            timestamp > now + RealTimeIntegrityPolicy.MAX_FUTURE_CLOCK_SKEW_MS || now - timestamp > RealTimeIntegrityPolicy.MAX_DATA_AGE_MS ||
            delay !in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS || !x.optBoolean("realtime", false) || !x.optBoolean("currentSessionIncluded", false) ||
            sequence < 0L || market != "BIST" || (previousSequence != null && sequence <= previousSequence) ||
            (previousTimestamp != null && timestamp <= previousTimestamp) ||
            !ProviderHealthRegistry.recordSuccess("backend_ws_tick", fingerprint, realtime = true)) return null
        lastSequenceBySymbol[symbol] = sequence
        lastTimestampBySymbol[symbol] = timestamp
        val receivedAt = System.currentTimeMillis()
        val provider = x.optString("providerId").ifBlank { "backend_ws" }
        return Tick(
            symbol = symbol,
            price = price,
            bid = x.optFinitePositive("bid"),
            ask = x.optFinitePositive("ask"),
            changePct = x.optFinite("changePct"),
            volume = x.optFiniteNonNegative("volume"),
            previousClose = x.optFinitePositive("previousClose"),
            timestamp = timestamp,
            market = market,
            source = x.optString("source").takeIf { it.isNotBlank() },
            providerId = x.optString("providerId").takeIf { it.isNotBlank() },
            sequence = sequence,
            delaySeconds = delay,
            currentSessionIncluded = true,
            marketDataMetadata = MarketDataQuality.metadata(
                providerId = provider,
                source = x.optString("source").ifBlank { "Production WebSocket" },
                marketTimestamp = timestamp,
                receivedAt = receivedAt,
                isLive = true,
                delaySeconds = delay,
                lastSuccessfulUpdateAt = receivedAt,
                fallback = false,
                reason = "Semantik WebSocket doğrulaması geçti."
            )
        )
    }

    private fun parseCandle(x: JSONObject, subscribed: Set<String>): CandleUpdate? {
        val symbol = x.optString("symbol").trim().uppercase()
        val timestamp = x.optLong("timestamp", 0L)
        val interval = x.optString("interval").ifBlank { "1m" }
        val candleKey = "$symbol|$interval"
        val previousTimestamp = lastCandleTimestampByKey[candleKey]
        if (symbol !in subscribed || timestamp <= 0L || !x.optBoolean("currentSessionIncluded", false) ||
            (previousTimestamp != null && timestamp <= previousTimestamp)) return null
        val open = x.optDouble("open", Double.NaN)
        val high = x.optDouble("high", Double.NaN)
        val low = x.optDouble("low", Double.NaN)
        val close = x.optDouble("close", Double.NaN)
        val volume = x.optDouble("volume", Double.NaN)
        if (!listOf(open, high, low, close, volume).all { it.isFinite() } || open <= 0 || high <= 0 || low <= 0 || close <= 0 || volume < 0 || high < low || open !in low..high || close !in low..high) return null
        lastCandleTimestampByKey[candleKey] = timestamp
        return CandleUpdate(
            symbol = symbol,
            interval = interval,
            timestamp = timestamp,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            source = x.optString("source").takeIf { it.isNotBlank() },
            lastBarClosed = if (x.has("lastBarClosed") && !x.isNull("lastBarClosed")) x.optBoolean("lastBarClosed") else null
        )
    }

    private fun parseTradingViewSignal(x: JSONObject, subscribed: Set<String>): TradingViewSignalEvent? {
        val id = x.optString("id").trim()
        val symbol = x.optString("symbol").trim().uppercase()
        val action = x.optString("action").trim().uppercase()
        val signalTime = x.optLong("signalTime", 0L)
        val receivedAt = x.optLong("receivedAt", 0L)
        if (id.isBlank() || symbol !in subscribed || action !in setOf("BUY", "SELL", "LONG", "SHORT", "EXIT", "WATCH") || signalTime <= 0L || receivedAt <= 0L) return null
        synchronized(seenSignalIds) {
            if (!seenSignalIds.add(id)) return null
            while (seenSignalIds.size > MAX_SIGNAL_IDS) seenSignalIds.remove(seenSignalIds.first())
        }
        // TradingView olayı yalnız sinyal kanalıdır. Piyasa verisi olarak işaretlenirse kabul etmeyiz.
        if (x.optBoolean("marketDataRealtime", false)) return null
        return TradingViewSignalEvent(
            id = id,
            symbol = symbol,
            action = action,
            price = x.optFinitePositive("price"),
            signalTime = signalTime,
            receivedAt = receivedAt,
            interval = x.optString("interval").takeIf { it.isNotBlank() },
            strategy = x.optString("strategy").takeIf { it.isNotBlank() },
            message = x.optString("message").takeIf { it.isNotBlank() },
            rsi = x.optFinite("rsi"),
            macdHistogram = x.optFinite("macdHistogram"),
            volumeRatio = x.optFiniteNonNegative("volumeRatio"),
            score = x.optFinite("score")?.takeIf { it in 0.0..100.0 },
            trend = x.optString("trend").takeIf { it.isNotBlank() },
            freshSignal = x.optBoolean("freshSignal", false),
            source = x.optString("source").ifBlank { "TRADINGVIEW_WEBHOOK" }
        )
    }

    private fun parseBackendError(x: JSONObject): String {
        val channel = x.optString("channel").takeIf { it.isNotBlank() }
        val symbol = x.optString("symbol").takeIf { it.isNotBlank() }
        val detail = x.opt("detail")?.toString()?.takeIf { it.isNotBlank() } ?: "Backend hata döndürdü."
        return listOfNotNull(channel, symbol, detail).joinToString(" • ")
    }

    private fun JSONObject.optFinite(name: String): Double? = optDouble(name, Double.NaN).takeIf { it.isFinite() }
    private fun JSONObject.optFinitePositive(name: String): Double? = optFinite(name)?.takeIf { it > 0.0 }
    private fun JSONObject.optFiniteNonNegative(name: String): Double? = optFinite(name)?.takeIf { it >= 0.0 }

    fun close() {
        manuallyClosed = true
        desiredSubscription = null
        reconnectAttempt = 0
        closeTransport("client_close")
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val STALE_SOCKET_MS = 45_000L
        private const val MAX_RECONNECT_ATTEMPTS = 6
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L
        private const val MAX_SIGNAL_IDS = 512
    }
}
