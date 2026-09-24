package tr.borsatakip.v5.worker

import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.data.ProviderReadinessService
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tr.borsatakip.v5.data.MarketCapabilityClient
import tr.borsatakip.v5.data.RealtimeScannerClient
import tr.borsatakip.v5.data.RealtimeScannerSocket
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ScanMode
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.ui.MainActivity
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-visible foreground monitor for realtime BIST opportunities.
 *
 * It consumes only /v1/scanner/live. TradingView alarms are not market data and do not trigger
 * these notifications. A periodic WorkManager job remains as a backup when the live service is
 * unavailable or stopped by the OS.
 */
class RealtimeAlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var scannerSocket: RealtimeScannerSocket
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var connectivity: ConnectivityManager
    @Volatile private var stopping = false
    @Volatile private var connected = false
    @Volatile private var networkAvailable = false
    @Volatile private var callbackRegistered = false
    @Volatile private var reconnectAttempt = 0

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkAvailable = true
            val settings = SettingsStore(this@RealtimeAlertService)
            settings.backgroundNetworkAvailable = true
            settings.backgroundLastNetworkAvailableAt = System.currentTimeMillis()
            if (!stopping && settings.notifications && !connected) scheduleReconnect(immediate = true)
        }

        override fun onLost(network: Network) {
            networkAvailable = hasUsableNetwork()
            val settings = SettingsStore(this@RealtimeAlertService)
            settings.backgroundNetworkAvailable = networkAvailable
            if (!networkAvailable) {
                connected = false
                settings.realtimeServiceState = "WAITING_FOR_NETWORK"
                updateForeground("Ağ yok • canlı izleme bağlantıyı bekliyor")
                if (::scannerSocket.isInitialized) scannerSocket.close()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // A service started with startForegroundService() must promote itself immediately.
        // Keep all network/socket initialization strictly after this point.
        createChannels()
        startRequested.set(true)
        runningInProcess.set(true)
        promoteToForeground()
        scannerSocket = RealtimeScannerSocket(this)
        acquireWakeLock()
        registerNetworkMonitor()
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = SettingsStore(this)
        if (!settings.notifications || !ProviderReadinessService.isValidHttps(settings.baseUrl) || settings.apiKey.isBlank()) {
            startRequested.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        stopping = false
        scope.launch {
            val capability = MarketCapabilityClient(this@RealtimeAlertService).load().getOrNull()
            val enabled = capability?.features?.realtimeScannerWebSocket == true && capability.features.attestationReady
            if (!enabled) {
                settings.realtimeServiceState = "FEATURE_DISABLED"
                updateForeground("Canlı scanner backend capability tarafından devre dışı")
                stopping = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return@launch
            }
            connect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        startRequested.set(false)
        runningInProcess.set(false)
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        if (::scannerSocket.isInitialized) scannerSocket.close()
        if (callbackRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        callbackRegistered = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        SettingsStore(this).realtimeServiceState = "STOPPED"
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Defensive cleanup for any platform-enforced foreground-service timeout.
        stopping = true
        startRequested.set(false)
        runningInProcess.set(false)
        if (::scannerSocket.isInitialized) scannerSocket.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun connect() {
        if (stopping) return
        val settings = SettingsStore(this)
        scannerSocket.connect(
            minScore = ALERT_MIN_SCORE,
            limit = 20,
            actionableOnly = true,
            minIntervalMs = 1000,
            analysisTimeframeMinutes = settings.analysisTimeframeMinutes,
            scanCadenceMinutes = settings.scanCadenceMinutes,
            scanMode = ScanMode.LIVE,
            listener = object : RealtimeScannerSocket.Listener {
                override fun onSubscribed() {
                    connected = true
                    reconnectAttempt = 0
                    val s = SettingsStore(this@RealtimeAlertService)
                    s.realtimeServiceState = "CONNECTED"
                    s.realtimeServiceHeartbeatAt = System.currentTimeMillis()
                    updateForeground("Canlı BIST scanner bağlı • ekran kilidinde izleme aktif")
                }

                override fun onSnapshot(snapshot: RealtimeScannerClient.Snapshot) {
                    connected = true
                    reconnectAttempt = 0
                    val runtime = SettingsStore(this@RealtimeAlertService)
                    runtime.realtimeServiceState = "CONNECTED"
                    runtime.realtimeServiceHeartbeatAt = System.currentTimeMillis()
                    val total = snapshot.expectedSymbols.takeIf { it > 0 } ?: snapshot.trackedSymbols
                    val coverage = snapshot.readyCoveragePct?.let { " • hazır %${"%.0f".format(it)}" } ?: ""
                    updateForeground("${snapshot.provider} • ${snapshot.readySymbols}/$total hazır$coverage")
                    snapshot.opportunities
                        .filter(::isActionableRealtime)
                        .take(5)
                        .forEach(::maybeNotify)
                }

                override fun onDisconnected(reason: String) {
                    connected = false
                    SettingsStore(this@RealtimeAlertService).realtimeServiceState = "RECONNECTING"
                    updateForeground("Canlı scanner bağlantısı kapandı • yeniden bağlanıyor")
                    scheduleReconnect()
                }

                override fun onError(message: String) {
                    connected = false
                    val runtime = SettingsStore(this@RealtimeAlertService)
                    if (message.contains("FEATURE_DISABLED")) {
                        runtime.realtimeServiceState = "FEATURE_DISABLED"
                        updateForeground("Canlı scanner backend capability tarafından devre dışı")
                        stopping = true
                        scannerSocket.close()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return
                    }
                    runtime.realtimeServiceState = "RECONNECTING"
                    updateForeground("Canlı scanner kesildi • yeniden bağlanıyor")
                    scheduleReconnect()
                }
            }
        )
    }

    private fun isActionableRealtime(x: Opportunity): Boolean =
        x.isRealtime &&
            x.dataMode == DataMode.REALTIME &&
            x.signalValidity == SignalValidity.VALID &&
            x.direction in setOf("LONG", "SHORT") &&
            x.finalSignalScore >= ALERT_MIN_SCORE &&
            (x.dataAgeMs == null || x.dataAgeMs in 0..60_000L)

    private fun scheduleReconnect(immediate: Boolean = false) {
        if (stopping || reconnectJob?.isActive == true || !networkAvailable) return
        val attempt = reconnectAttempt.coerceAtMost(MAX_RECONNECT_ATTEMPTS)
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            SettingsStore(this).realtimeServiceState = "RECONNECT_LIMIT"
            updateForeground("Canlı bağlantı yeniden deneme sınırına ulaştı • ağ değişimi bekleniyor")
            return
        }
        val waitMs = if (immediate) 0L else (RECONNECT_BASE_MS shl attempt).coerceAtMost(RECONNECT_MAX_MS)
        reconnectAttempt = attempt + 1
        reconnectJob = scope.launch {
            delay(waitMs)
            if (!stopping && networkAvailable && SettingsStore(this@RealtimeAlertService).notifications) connect()
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (!stopping) {
                val settings = SettingsStore(this@RealtimeAlertService)
                if (!settings.notifications) break
                settings.realtimeServiceHeartbeatAt = System.currentTimeMillis()
                settings.backgroundNetworkAvailable = networkAvailable
                if (connected) settings.realtimeServiceState = "CONNECTED"
                delay(HEARTBEAT_MS)
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:V544Realtime").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun registerNetworkMonitor() {
        connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkAvailable = hasUsableNetwork()
        SettingsStore(this).backgroundNetworkAvailable = networkAvailable
        runCatching {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
        }
    }

    private fun hasUsableNetwork(): Boolean {
        val cm = runCatching { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }.getOrNull() ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (SettingsStore(this).notifications) {
            SettingsStore(this).realtimeServiceState = "BACKGROUND_SERVICE_ACTIVE"
            SettingsStore(this).realtimeServiceHeartbeatAt = System.currentTimeMillis()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun maybeNotify(x: Opportunity) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("realtime_alert_cooldown", Context.MODE_PRIVATE)
        val key = "${x.symbol}:${x.direction}"
        val now = System.currentTimeMillis()
        val last = prefs.getLong(key, 0L)
        if (now - last < ALERT_COOLDOWN_MS) return
        prefs.edit().putLong(key, now).apply()

        val isShort = x.direction == "SHORT"
        val action = if (isShort) "SAT ADAYI" else "AL ADAYI"
        val arrow = if (isShort) "▼" else "▲"
        val title = "${x.symbol} • $arrow $action • ${x.finalSignalScore}/100"
        val change = x.dailyChangePct?.takeIf { it.isFinite() }?.let { "%+.2f%%".format(it) } ?: "—"
        val body = "Fiyat %.2f TL • Günlük %s • Risk %d/100 • Canlı veri doğrulandı".format(
            x.price, change, x.riskScore
        )
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ALERT_SYMBOL, x.symbol)
        }
        val pending = PendingIntent.getActivity(
            this,
            abs((x.symbol + x.direction).hashCode()),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(ALERT_ID_BASE + abs(x.symbol.hashCode() % 20_000), notification)
    }


    private fun promoteToForeground() {
        val serviceType = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            FOREGROUND_ID,
            monitorNotification("Canlı BIST fırsatları izleniyor • bağlantı kuruluyor"),
            serviceType
        )
    }

    private fun updateForeground(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(FOREGROUND_ID, monitorNotification(text))
    }

    private fun monitorNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            7001,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        return NotificationCompat.Builder(this, MONITOR_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("BorsaTakip ${BuildConfig.VERSION_NAME}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(MONITOR_CHANNEL, "BorsaTakip Canlı İzleme", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Anlık Fırsat Uyarıları", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun immutableFlag(): Int = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        const val EXTRA_ALERT_SYMBOL = "realtime_alert_symbol"
        private const val MONITOR_CHANNEL = "realtime_bist_monitor"
        private const val ALERT_CHANNEL = "realtime_bist_alerts"
        private const val FOREGROUND_ID = 7400
        private const val ALERT_ID_BASE = 7500
        private const val ALERT_MIN_SCORE = 80
        private const val ALERT_COOLDOWN_MS = 15 * 60 * 1000L
        private const val HEARTBEAT_MS = 30_000L
        private const val RECONNECT_BASE_MS = 2_000L
        private const val RECONNECT_MAX_MS = 60_000L
        private const val MAX_RECONNECT_ATTEMPTS = 8

        private val startRequested = AtomicBoolean(false)
        private val runningInProcess = AtomicBoolean(false)

        fun isRunningInProcess(): Boolean = runningInProcess.get()

        fun start(context: Context): Boolean {
            val appContext = context.applicationContext
            val settings = SettingsStore(appContext)
            if (!settings.notifications || !ProviderReadinessService.isValidHttps(settings.baseUrl) || settings.apiKey.isBlank()) {
                return false
            }
            if (!startRequested.compareAndSet(false, true)) return true

            return runCatching {
                val intent = Intent(appContext, RealtimeAlertService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(appContext, intent)
                else appContext.startService(intent)
                true
            }.getOrElse {
                startRequested.set(false)
                false
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            val stopped = appContext.stopService(Intent(appContext, RealtimeAlertService::class.java))
            if (!stopped) startRequested.set(false)
        }
    }
}
