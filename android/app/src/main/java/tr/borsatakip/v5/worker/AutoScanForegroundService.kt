package tr.borsatakip.v5.worker

import tr.borsatakip.v5.BuildConfig

import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.ProviderFallbackPolicy
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tr.borsatakip.v5.data.ScanTimeframe
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.ui.BistScanActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kullanıcı tarafından etkinleştirilen 15 dakikanın altındaki otomatik taramayı görünür
 * foreground servis içinde yürütür. V5.4.5 ekran kilidi/arka plan dayanıklılığı:
 * - PARTIAL_WAKE_LOCK ile CPU'nun tarama döngüsü sırasında uykuya alınmasını engeller.
 * - Servis heartbeat'i tutar; WorkManager watchdog stale servisi fark edebilir.
 * - Ağ geri geldiğinde, son tarama yeterince eskiyse kontrollü bir recovery taraması yapar.
 * - Analiz timeframe'i ile tarama cadence'i kesin olarak ayrı tutulur.
 */
class AutoScanForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var heartbeatJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var connectivity: ConnectivityManager
    private val scanInFlight = AtomicBoolean(false)
    @Volatile private var networkAvailable: Boolean = false
    @Volatile private var callbackRegistered: Boolean = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkAvailable = true
            val settings = SettingsStore(this@AutoScanForegroundService)
            val now = System.currentTimeMillis()
            settings.backgroundNetworkAvailable = true
            settings.backgroundLastNetworkAvailableAt = now
            settings.autoScanServiceHeartbeatAt = now
            if (settings.autoScanEnabled && now - settings.autoScanLastRunAt > NETWORK_RECOVERY_MIN_AGE_MS) {
                scope.launch { performScan("NETWORK_RECOVERED") }
            }
        }

        override fun onLost(network: Network) {
            networkAvailable = hasUsableNetwork()
            val settings = SettingsStore(this@AutoScanForegroundService)
            settings.backgroundNetworkAvailable = networkAvailable
            if (!networkAvailable && settings.autoScanEnabled) {
                settings.autoScanLastStatus = "WAITING_FOR_NETWORK"
                settings.autoScanLastMessage = "Ekran kilidi/arka plan taraması aktif; ağ bağlantısının geri gelmesi bekleniyor."
                updateForeground("Ağ yok • arka plan taraması bağlantıyı bekliyor")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startRequested.set(true)
        promoteToForeground("${BuildConfig.VERSION_NAME} • otomatik tarama hazırlanıyor")
        acquireWakeLock()
        registerNetworkMonitor()
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SettingsStore(this).autoScanEnabled = false
            stopSelfSafely()
            return START_NOT_STICKY
        }
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            val settings = SettingsStore(this)
            if (!settings.autoScanEnabled) break
            val timeframe = ScanTimeframe.fromStored(settings.analysisTimeframeMinutes)
            val cadence = settings.scanCadenceMinutes.coerceIn(1, 1440)
            if (cadence >= AutoScanScheduler.WORK_MANAGER_MINUTES) break
            val sourceMode = when {
                ProviderReadinessService.isValidHttps(settings.baseUrl) && settings.apiKey.isNotBlank() -> "Production Backend"
                ProviderFallbackPolicy.allowed(settings.experimentalProvidersEnabled, settings.yahooFallbackEnabled) -> "Yahoo YEDEK/GECİKMELİ"
                else -> "VERİ SAĞLAYICI HAZIR DEĞİL"
            }
            updateForeground("${timeframe.label} analiz • ${cadence} DK tarama • $sourceMode")
            performScan("CADENCE")
            if (!SettingsStore(this).autoScanEnabled) break
            delay(cadence * 60_000L)
        }
        stopSelfSafely()
    }

    private suspend fun performScan(trigger: String) {
        if (!scanInFlight.compareAndSet(false, true)) return
        try {
            val settings = SettingsStore(this)
            if (!settings.autoScanEnabled) return
            val now = System.currentTimeMillis()
            settings.autoScanServiceHeartbeatAt = now
            settings.backgroundNetworkAvailable = networkAvailable
            if (!networkAvailable) {
                settings.autoScanLastStatus = "WAITING_FOR_NETWORK"
                settings.autoScanLastMessage = "$trigger • Ağ yok; veri uydurulmadan bağlantı bekleniyor."
                return
            }
            val result = AutomaticScanRunner.runOnce(this)
            updateForeground("${settings.scanCadenceMinutes} DK • ${result.message.take(90)}")
        } finally {
            scanInFlight.set(false)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                val settings = SettingsStore(this@AutoScanForegroundService)
                if (!settings.autoScanEnabled) break
                val now = System.currentTimeMillis()
                settings.autoScanServiceHeartbeatAt = now
                settings.backgroundNetworkAvailable = networkAvailable
                if (networkAvailable && settings.autoScanLastStatus == "WAITING_FOR_NETWORK") {
                    settings.autoScanLastStatus = "BACKGROUND_SERVICE_ACTIVE"
                    settings.autoScanLastMessage = "Ekran kilidi/arka plan foreground servisi aktif; ağ geri geldi."
                }
                delay(HEARTBEAT_MS)
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:V544AutoScan").apply {
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
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val settings = SettingsStore(this)
        if (settings.autoScanEnabled) {
            settings.autoScanLastStatus = "BACKGROUND_SERVICE_ACTIVE"
            settings.autoScanLastMessage = "Uygulama son uygulamalar ekranından kaldırıldı; foreground tarama servisi çalışmayı sürdürüyor."
            settings.autoScanServiceHeartbeatAt = System.currentTimeMillis()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        startRequested.set(false)
        loopJob?.cancel()
        heartbeatJob?.cancel()
        if (callbackRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        callbackRegistered = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopSelfSafely() {
        startRequested.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(text: String) {
        val serviceType = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), serviceType)
    }

    private fun updateForeground(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            7810,
            Intent(this, BistScanActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        val stop = PendingIntent.getService(
            this,
            7811,
            Intent(this, AutoScanForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("BorsaTakip ${BuildConfig.VERSION_NAME}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Durdur", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "BorsaTakip Arka Plan Tarama", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun immutableFlag(): Int = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        private const val CHANNEL_ID = "automatic_bist_scan"
        private const val NOTIFICATION_ID = 7800
        private const val ACTION_STOP = "tr.borsatakip.v5.AUTO_SCAN_STOP"
        private const val HEARTBEAT_MS = 30_000L
        private const val NETWORK_RECOVERY_MIN_AGE_MS = 60_000L
        private val startRequested = AtomicBoolean(false)

        fun start(context: Context): Boolean {
            val app = context.applicationContext
            val settings = SettingsStore(app)
            if (!settings.autoScanEnabled) return false
            if (!startRequested.compareAndSet(false, true)) return true
            return runCatching {
                val intent = Intent(app, AutoScanForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(app, intent) else app.startService(intent)
                true
            }.getOrElse {
                startRequested.set(false)
                false
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            val stopped = app.stopService(Intent(app, AutoScanForegroundService::class.java))
            if (!stopped) startRequested.set(false)
        }
    }
}
