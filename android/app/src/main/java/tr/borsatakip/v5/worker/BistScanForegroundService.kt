package tr.borsatakip.v5.worker

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.OpportunityRankingPolicy
import tr.borsatakip.v5.data.BackendPreflightClient
import tr.borsatakip.v5.data.IntervalMarketDataProvider
import tr.borsatakip.v5.data.LastSuccessfulScanStore
import tr.borsatakip.v5.data.ManualDataQuality
import tr.borsatakip.v5.data.ManualScanSession
import tr.borsatakip.v5.data.ManualScanSessionRepository
import tr.borsatakip.v5.data.ManualScanPauseGate
import tr.borsatakip.v5.data.ManualScanProgressPolicy
import tr.borsatakip.v5.data.ProviderFallbackPolicy
import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.ScanTimeframe
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.SignalHistoryRecorder
import tr.borsatakip.v5.model.ScanMode
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.scan.BistScanMode
import tr.borsatakip.v5.scan.BistScanner
import tr.borsatakip.v5.scan.ScanStatus
import tr.borsatakip.v5.ui.AppSession
import tr.borsatakip.v5.ui.BistScanActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kullanıcının BIST Tarama ekranından açıkça başlattığı manuel taramayı Activity lifecycle'ından ayırır.
 * Servis yalnız kullanıcı eylemiyle başlatılır; 24/7 çalışma garantisi vermez ve OS/OEM kesintilerini
 * kalıcı scan-session state'inde dürüstçe raporlar.
 */
class BistScanForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: ManualScanSessionRepository
    private lateinit var connectivity: ConnectivityManager
    private var scanJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var callbackRegistered = false
    private var stopRequested = false
    @Volatile private var normalShutdown = false
    @Volatile private var terminalStatePublished = false
    private var lastNotificationAt = 0L
    private var lastNotificationProcessed = -1
    private var lastNotificationPhase = ""

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (::repository.isInitialized) {
                ManualScanPauseGate.resume()
                repository.markNetworkState(true)
                repository.markResumedFromPause()
                updateForeground(repository.snapshot(), force = true)
            }
        }

        override fun onLost(network: Network) {
            if (!hasUsableNetwork() && ::repository.isInitialized) {
                ManualScanPauseGate.pause()
                repository.markNetworkState(false)
                repository.markPaused()
                updateForeground(repository.snapshot(), force = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = ManualScanSessionRepository.get(this)
        connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        ManualScanPauseGate.resume()
        createChannel()
        promoteToForeground(repository.snapshot().takeIf { it.isActive } ?: ManualScanSession(message = "Manuel BIST taraması hazırlanıyor."))
        acquireWakeLock()
        registerNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                scanJob?.cancel(CancellationException("Kullanıcı bildirimin Durdur eylemini kullandı."))
                if (scanJob?.isActive != true) {
                    repository.markStopped()
                    terminalStatePublished = true
                    normalShutdown = true
                    publishTerminalNotification(repository.snapshot())
                    stopSelfSafely()
                }
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                if (scanJob?.isActive != true) {
                    stopRequested = false
                    normalShutdown = false
                    terminalStatePublished = false
                    scanJob = scope.launch { runManualScan() }
                }
            }
        }
        // Manuel kullanıcı taraması OS tarafından öldürülürse kendiliğinden yeni tarama başlatılmaz.
        return START_NOT_STICKY
    }

    private suspend fun runManualScan() {
        val settings = SettingsStore(this)
        settings.scanMode = ScanMode.MANUAL
        val timeframe = ScanTimeframe.fromStored(settings.analysisTimeframeMinutes)
        repository.begin(timeframe.storedMinutes)
        updateForeground(repository.snapshot(), force = true)

        try {
            if (!hasUsableNetwork()) {
                repository.markUnavailable("Ağ bağlantısı yok. Tarama başlatılmadı; veri/progress uydurulmadı.", "AĞ YOK")
                return
            }

            val productionConfigured = ProviderReadinessService.isValidHttps(settings.baseUrl) && settings.apiKey.isNotBlank()
            val fallbackAllowed = ProviderFallbackPolicy.allowed(settings.experimentalProvidersEnabled, settings.yahooFallbackEnabled)
            if (!productionConfigured && !fallbackAllowed) {
                repository.markUnavailable(
                    "Production Backend hazır değil ve Yahoo fallback kapalı. Ayarlar bölümünden veri sağlayıcı yapılandırın.",
                    "VERİ SAĞLAYICI HAZIR DEĞİL"
                )
                return
            }

            var announcedTotal = 0
            var sampleSymbol: String? = null
            var sessionCloseMode = false
            var delayedAnalysisMode = false

            if (productionConfigured) {
                val preflight = BackendPreflightClient(this).checkBist()
                if (!preflight.ok) {
                    repository.markUnavailable(preflight.message, "${preflight.failureKind} • PROVIDER HAZIR DEĞİL")
                    return
                }
                announcedTotal = preflight.symbolCount
                sampleSymbol = preflight.sampleSymbol
                sessionCloseMode = preflight.bistAvailabilityMode == BackendPreflightClient.BistAvailabilityMode.SESSION_CLOSE
                delayedAnalysisMode = preflight.bistAvailabilityMode == BackendPreflightClient.BistAvailabilityMode.DELAYED_ANALYSIS
            }

            val scanMode = when {
                productionConfigured && sessionCloseMode -> BistScanMode.SESSION_CLOSE_ANALYSIS
                productionConfigured && delayedAnalysisMode -> BistScanMode.DELAYED_ANALYSIS
                productionConfigured -> BistScanMode.REALTIME_ONLY
                else -> BistScanMode.DELAYED_ANALYSIS
            }
            repository.setPreflight(
                total = announcedTotal,
                providerStatus = when {
                    scanMode == BistScanMode.REALTIME_ONLY -> "Production Backend • CANLI/DOĞRULANMIŞ"
                    productionConfigured -> "Production Backend • GECİKMELİ/KAPANIŞ ANALİZİ"
                    else -> "Yahoo Finance • YEDEK/GECİKMELİ"
                },
                scanMode = scanMode,
                message = when (scanMode) {
                    BistScanMode.REALTIME_ONLY -> "BIST sembol evreni doğrulandı; ${timeframe.label} canlı tarama başlıyor."
                    BistScanMode.SESSION_CLOSE_ANALYSIS -> "BIST sembol evreni doğrulandı; ${timeframe.label} son kapanmış seans taraması başlıyor."
                    BistScanMode.DELAYED_ANALYSIS -> "${timeframe.label} kapanmış OHLCV ile gecikmeli gözlem analizi başlıyor."
                }
            )
            updateForeground(repository.snapshot(), force = true)

            val baseProvider = ProviderRouter(this)
            val scanProvider = IntervalMarketDataProvider(
                baseProvider,
                timeframe.storedMinutes,
                sessionCloseMode = sessionCloseMode,
                delayedObservationMode = delayedAnalysisMode
            )

            // Production yolunda seçili timeframe'i tüm evrene geçmeden gerçek örnek sembolle doğrula.
            if (productionConfigured) {
                val sample = sampleSymbol
                if (sample.isNullOrBlank()) {
                    repository.markUnavailable("BIST sembol evreni örnek sembol döndürmedi.", "BIST SYMBOLS ERROR")
                    return
                }
                val sampleStock = runCatching { scanProvider.fetchOne(sample) }.getOrElse { throwable ->
                    repository.markUnavailable(
                        "${timeframe.label} preflight başarısız: ${throwable.message ?: throwable.javaClass.simpleName}",
                        "${timeframe.label} VERİ SERVİSİ HAZIR DEĞİL"
                    )
                    return
                }
                if (sampleStock == null || sampleStock.candles.size < tr.borsatakip.v5.data.RealTimeIntegrityPolicy.MIN_HISTORY_BARS) {
                    repository.markUnavailable(
                        "$sample için yeterli ${timeframe.label} OHLCV alınamadı. Tüm BIST taraması başlatılmadı.",
                        "${timeframe.label} VERİ SERVİSİ HAZIR DEĞİL"
                    )
                    return
                }
            }

            val scanner = BistScanner(scanProvider, SignalHistoryRecorder(this), scanMode)
            val finalState = scanner.scan { state ->
                AppSession.lastScanState = state
                val routing = ProviderRouter.routingStatus()
                val providerText = buildString {
                    append(routing.activeProviderLabel.ifBlank { scanProvider.displayName })
                    if (routing.dataState.isNotBlank()) append(" • ${routing.dataState}")
                }
                // COMPLETED is deliberately deferred until durable result persistence succeeds.
                val session = repository.updateFromScan(state, providerText, publishCompletion = false)
                updateForeground(session)
            }

            val sorted = OpportunityRankingPolicy.sort(finalState.results)
            val routing = ProviderRouter.routingStatus()
            val providerText = buildString {
                append(routing.activeProviderLabel.ifBlank { scanProvider.displayName })
                if (routing.dataState.isNotBlank()) append(" • ${routing.dataState}")
            }
            val run = finalState.scanRun
            if (finalState.status == ScanStatus.COMPLETED && run?.status == ScanRunStatus.COMPLETE) {
                val store = LastSuccessfulScanStore(this)
                try {
                    store.save(run, sorted)
                    val persisted = checkNotNull(store.load()) { "Atomik sonuç kaydı doğrulanamadı." }
                    check(persisted.first.scanRunId == run.scanRunId) { "Atomik sonuç kaydı scanRunId doğrulaması başarısız." }
                    check(persisted.second.size == sorted.size) { "Atomik sonuç kaydı adet doğrulaması başarısız." }
                    check(BistScanServiceLifecyclePolicy.canPublishCompleted(run.status, persistenceSucceeded = true)) {
                        "COMPLETED yayın politikası atomik kayıt sonrası izin vermedi."
                    }
                } catch (t: Throwable) {
                    repository.markError("Tarama sonuçları atomik olarak kaydedilemedi; COMPLETED yayımlanmadı: ${t.message ?: t.javaClass.simpleName}")
                    terminalStatePublished = true
                    return
                }
            }

            AppSession.lastScanState = finalState
            AppSession.lastOpportunities = sorted
            val terminalSession = repository.updateFromScan(finalState, providerText, publishCompletion = true)
            terminalStatePublished = !terminalSession.isActive
            updateForeground(terminalSession, force = true)
        } catch (ce: CancellationException) {
            if (stopRequested) repository.markStopped() else repository.markError("Tarama coroutine'i kesildi; sahte COMPLETED üretilmedi.")
            terminalStatePublished = true
        } catch (t: Throwable) {
            repository.markError(t.message ?: "Beklenmeyen tarama hatası")
            terminalStatePublished = true
        } finally {
            ManualScanPauseGate.resume()
            val terminal = repository.snapshot()
            if (!terminal.isActive) {
                terminalStatePublished = true
                publishTerminalNotification(terminal)
            }
            normalShutdown = true
            stopSelfSafely()
        }
    }

    private fun hasUsableNetwork(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun registerNetworkMonitor() {
        runCatching {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
        }
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ManualBistScan").apply {
            setReferenceCounted(false)
            // Manuel tarama için sınırlı wake lock; 24/7 çalışma varsayımı yapılmaz.
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun updateForeground(session: ManualScanSession, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val phaseChanged = lastNotificationPhase != session.phase
        val progressed = session.scannedCount != lastNotificationProcessed
        if (!force && !phaseChanged && (!progressed || now - lastNotificationAt < NOTIFICATION_MIN_INTERVAL_MS)) return
        lastNotificationAt = now
        lastNotificationProcessed = session.scannedCount
        lastNotificationPhase = session.phase
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(session, ongoing = true))
    }

    private fun promoteToForeground(session: ManualScanSession) {
        val serviceType = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(session, ongoing = true), serviceType)
    }

    private fun publishTerminalNotification(session: ManualScanSession) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(session, ongoing = false))
    }

    private fun notification(session: ManualScanSession, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            7910,
            Intent(this, BistScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        val stop = PendingIntent.getService(
            this,
            7911,
            Intent(this, BistScanForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        val total = session.totalCount
        val displayPercent = ManualScanProgressPolicy.displayPercent(session)
        val countText = if (total > 0) "${session.scannedCount.coerceAtMost(total)} / $total • %$displayPercent" else session.phase
        val symbolText = session.currentSymbol?.let { " • Son: $it" }.orEmpty()
        val title = when {
            session.status == tr.borsatakip.v5.data.ManualScanStatus.PAUSED -> "BIST Tarama duraklatıldı"
            session.isActive -> "BIST Tarama devam ediyor"
            else -> when (session.status) {
            tr.borsatakip.v5.data.ManualScanStatus.COMPLETED -> "BIST Tarama tamamlandı"
            tr.borsatakip.v5.data.ManualScanStatus.PARTIAL -> "BIST Tarama kısmi tamamlandı"
            tr.borsatakip.v5.data.ManualScanStatus.STOPPED -> "BIST Tarama durduruldu"
            else -> "BIST Tarama durumu"
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText("$countText$symbolText • ${session.providerStatus}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$countText$symbolText\n${session.providerStatus}\n${session.message.orEmpty()}"))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, displayPercent, total <= 0 && session.isActive)
            .apply { if (ongoing) addAction(android.R.drawable.ic_media_pause, "Taramayı Durdur", stop) }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "BIST Manuel Tarama", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Kullanıcı tarafından başlatılan BIST taramasının gerçek ilerlemesi"
            }
        )
    }

    private fun stopSelfSafely() {
        if (callbackRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        callbackRegistered = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        if (BistScanServiceLifecyclePolicy.shouldMarkInterrupted(
                normalShutdown = normalShutdown,
                terminalStatePublished = terminalStatePublished,
                jobActive = scanJob?.isActive == true,
                stopRequested = stopRequested
            )) {
            repository.markError("Foreground service OS/OEM tarafından sonlandırıldı; sahte COMPLETED üretilmedi.")
            terminalStatePublished = true
        }
        scanJob?.cancel()
        if (callbackRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        callbackRegistered = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        running.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun immutableFlag(): Int = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        private const val CHANNEL_ID = "manual_bist_scan"
        private const val NOTIFICATION_ID = 7900
        private const val ACTION_START = "tr.borsatakip.v5.MANUAL_BIST_SCAN_START"
        private const val ACTION_STOP = "tr.borsatakip.v5.MANUAL_BIST_SCAN_STOP"
        private const val NOTIFICATION_MIN_INTERVAL_MS = 750L
        private const val MAX_WAKE_LOCK_MS = 2L * 60 * 60 * 1000
        private val running = AtomicBoolean(false)

        fun start(context: Context): Boolean {
            val app = context.applicationContext
            if (!running.compareAndSet(false, true)) return true
            return runCatching {
                val intent = Intent(app, BistScanForegroundService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(app, intent) else app.startService(intent)
                true
            }.getOrElse {
                running.set(false)
                ManualScanSessionRepository.get(app).markError("Foreground service başlatılamadı: ${it.message ?: it.javaClass.simpleName}")
                false
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, BistScanForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { app.startService(intent) }
        }
    }
}
