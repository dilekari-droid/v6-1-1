package tr.borsatakip.v5.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.AlertEventStore
import tr.borsatakip.v5.data.BackendPreflightClient
import tr.borsatakip.v5.data.IntervalMarketDataProvider
import tr.borsatakip.v5.data.MtfHistoryCache
import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.ProviderFailureCode
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.ProviderState
import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.data.RuntimeReadinessDiagnostics
import tr.borsatakip.v5.data.ScanTimeframe
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.worker.AutoScanScheduler
import tr.borsatakip.v5.worker.RealtimeAlertService
import tr.borsatakip.v5.worker.WorkScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : BaseActivity() {
    private lateinit var settings: SettingsStore
    private lateinit var readiness: ProviderReadinessService

    private val detailLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        refreshDashboard()
        if (result.resultCode == RESULT_OK && result.data?.getBooleanExtra(EXTRA_PROVIDER_CONFIG_CHANGED, false) == true) {
            autoTestAfterSave()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setupBottomNav()
        findViewById<TextView>(R.id.navSettings)?.setTextColor(ContextCompat.getColor(this, R.color.blue))

        settings = SettingsStore(this)
        readiness = ProviderReadinessService(this)
        settings.purgeLegacyTradingViewState()

        bindNavigation()
        bindQuickActions()
        refreshDashboard()
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) refreshDashboard()
    }

    private fun bindNavigation() {
        findViewById<TextView>(R.id.searchButton).setOnClickListener {
            startActivity(Intent(this, SettingsSearchActivity::class.java))
        }
        findViewById<TextView>(R.id.notificationButton).setOnClickListener {
            openDetail(SettingsDetailActivity.MODE_NOTIFICATION_HISTORY)
        }
        findViewById<TextView>(R.id.moreButton).setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add("Uygulama Hakkında")
                menu.add("Varsayılanlara dön")
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        "Uygulama Hakkında" -> openDetail(SettingsDetailActivity.MODE_ABOUT)
                        "Varsayılanlara dön" -> confirmReset()
                    }
                    true
                }
                show()
            }
        }

        mapOf(
            R.id.systemStatusCard to SettingsDetailActivity.MODE_SYSTEM_STATUS,
            R.id.dataConnectionCard to SettingsDetailActivity.MODE_DATA_CONNECTION,
            R.id.apiKeysCard to SettingsDetailActivity.MODE_API_KEYS,
            R.id.scanSettingsCard to SettingsDetailActivity.MODE_SCAN,
            R.id.tradeSimulationCard to SettingsDetailActivity.MODE_SIMULATION,
            R.id.notificationsCard to SettingsDetailActivity.MODE_NOTIFICATIONS,
            R.id.notificationHistoryCard to SettingsDetailActivity.MODE_NOTIFICATION_HISTORY,
            R.id.appearanceCard to SettingsDetailActivity.MODE_APPEARANCE,
            R.id.localeCard to SettingsDetailActivity.MODE_LOCALE,
            R.id.diagnosticsCard to SettingsDetailActivity.MODE_DIAGNOSTICS,
            R.id.aboutCard to SettingsDetailActivity.MODE_ABOUT,
            R.id.workModeCard to SettingsDetailActivity.MODE_WORK_MODE
        ).forEach { (id, mode) ->
            findViewById<android.view.View>(id).setOnClickListener { openDetail(mode) }
        }
    }

    private fun openDetail(mode: String) {
        detailLauncher.launch(
            Intent(this, SettingsDetailActivity::class.java)
                .putExtra(SettingsDetailActivity.EXTRA_MODE, mode)
        )
    }

    private fun bindQuickActions() {
        findViewById<Button>(R.id.clearCacheButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Önbellek temizlensin mi?")
                .setMessage("Yalnız geçici BIST sembol, MTF mum ve haber önbelleği temizlenecek. API anahtarı, ayarlar, favoriler, takip listeleri ve sinyal geçmişi silinmez.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Temizle") { _, _ ->
                    val cleared = settings.clearTransientCaches()
                    refreshDashboard()
                    Toast.makeText(this, if (cleared) "Geçici önbellek temizlendi." else "Temizlenecek önbellek bulunamadı.", Toast.LENGTH_LONG).show()
                }
                .show()
        }

        findViewById<Button>(R.id.refreshDataButton).setOnClickListener { view ->
            val button = view as Button
            val local = readiness.localConfigState()
            if (local.state == ProviderState.PROVIDER_NOT_CONFIGURED) {
                Toast.makeText(this, local.message, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            button.isEnabled = false
            button.text = "YENİLENİYOR..."
            MtfHistoryCache.clear()
            lifecycleScope.launch {
                val result = readiness.test()
                button.isEnabled = true
                button.text = "VERİYİ YENİLE"
                refreshDashboard()
                Toast.makeText(
                    this@SettingsActivity,
                    if (result.state == ProviderState.PROVIDER_READY || (result.state == ProviderState.PROVIDER_STALE_READY && result.failureCode == ProviderFailureCode.NONE)) "Provider bağlantısı doğrulandı: ${result.message}" else "Veri yenilenemedi: ${result.failureCode} • ${result.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        findViewById<Button>(R.id.resetSettingsButton).setOnClickListener { confirmReset() }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Tüm ayarlar varsayılanlara dönsün mü?")
            .setMessage(
                "Sıfırlanacaklar:\n• tarama/yenileme ayarları\n• bildirim tercihi\n• simülasyon ayarları\n• deneysel/yedek provider tercihi\n\n" +
                    "Korunacaklar:\n• Production Backend URL\n• API anahtarı\n• favoriler ve takip listeleri\n• sinyal/bildirim geçmişi\n• çalışma sayaçları"
            )
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Sıfırla") { _, _ ->
                settings.resetOperationalSettingsPreservingCredentials()
                WorkScheduler.reconcile(this, false, settings.refreshMinutes)
                RealtimeAlertService.stop(this)
                AutoScanScheduler.reconcile(this, allowForegroundStart = false)
                refreshDashboard()
                Toast.makeText(this, "Ayarlar varsayılan değerlere döndürüldü. Kimlik bilgileri ve kullanıcı verileri korundu.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun refreshDashboard() {
        val snapshot = readiness.localConfigState()
        findViewById<TextView>(R.id.versionBadge).text = "B${BuildConfig.VERSION_CODE} • ${BuildConfig.VERSION_NAME}"

        val statusBadge = findViewById<TextView>(R.id.systemStatusBadge)
        val statusSummary = findViewById<TextView>(R.id.systemStatusSummary)
        when (snapshot.state) {
            ProviderState.PROVIDER_READY -> {
                if (snapshot.message.contains("KAPANIŞ", ignoreCase = true)) setBadge(statusBadge, "KAPANIŞ HAZIR", R.color.yellow)
                else setBadge(statusBadge, "HAZIR", R.color.green)
            }
            ProviderState.PROVIDER_STALE_READY -> when {
                snapshot.message.contains("GECİKMELİ", ignoreCase = true) -> setBadge(statusBadge, "GECİKMELİ ANALİZ", R.color.yellow)
                snapshot.message.contains("KAPANIŞ", ignoreCase = true) -> setBadge(statusBadge, "KAPANIŞ HAZIR", R.color.yellow)
                else -> setBadge(statusBadge, "YENİDEN TEST", R.color.yellow)
            }
            ProviderState.PROVIDER_ERROR -> setBadge(statusBadge, "HATA", R.color.red)
            ProviderState.PROVIDER_TESTING -> setBadge(statusBadge, "TEST EDİLİYOR", R.color.blue)
            ProviderState.PROVIDER_CONFIGURED -> setBadge(statusBadge, "TEST GEREKLİ", R.color.yellow)
            ProviderState.PROVIDER_NOT_CONFIGURED -> setBadge(statusBadge, "YAPILANDIRILMAMIŞ", R.color.text_muted)
        }
        statusSummary.text = buildString {
            append(snapshot.message)
            if (snapshot.testedAt > 0L) append(" • Son kontrol ${formatTime(snapshot.testedAt)}")
        }

        val connectionBadge = findViewById<TextView>(R.id.connectionBadge)
        when (snapshot.state) {
            ProviderState.PROVIDER_READY -> setBadge(connectionBadge, "BAĞLI", R.color.green)
            ProviderState.PROVIDER_ERROR -> setBadge(connectionBadge, "HATA", R.color.red)
            ProviderState.PROVIDER_NOT_CONFIGURED -> setBadge(connectionBadge, "EKSİK", R.color.text_muted)
            ProviderState.PROVIDER_STALE_READY -> if (snapshot.message.contains("GECİKMELİ", true) || snapshot.message.contains("KAPANIŞ", true)) setBadge(connectionBadge, "BAĞLI • GECİKMELİ", R.color.yellow) else setBadge(connectionBadge, "KONTROL GEREKLİ", R.color.yellow)
            else -> setBadge(connectionBadge, "KONTROL GEREKLİ", R.color.yellow)
        }

        val scanBadge = findViewById<TextView>(R.id.scanBadge)
        val freshestHeartbeat = maxOf(settings.autoScanLastHeartbeatAt, settings.autoScanServiceHeartbeatAt)
        val heartbeatAge = if (freshestHeartbeat > 0L) System.currentTimeMillis() - freshestHeartbeat else Long.MAX_VALUE
        val heartbeatFreshLimit = maxOf(5L, settings.scanCadenceMinutes.toLong() * 3L) * 60_000L
        when {
            !settings.autoScanEnabled -> setBadge(scanBadge, "KAPALI", R.color.text_muted)
            heartbeatAge <= heartbeatFreshLimit -> setBadge(scanBadge, "ARKA PLAN AKTİF", R.color.green)
            else -> setBadge(scanBadge, "HEARTBEAT BEKLİYOR", R.color.yellow)
        }

        val permission = notificationPermissionGranted()
        val serviceRunning = RealtimeAlertService.isRunningInProcess()
        val notificationLabel = when {
            !settings.notifications -> "KAPALI"
            !permission -> "İZİN YOK"
            serviceRunning -> "AKTİF"
            else -> "SERVİS BEKLİYOR"
        }
        setBadge(
            findViewById(R.id.notificationsBadge),
            notificationLabel,
            if (serviceRunning && settings.notifications && permission) R.color.green else if (settings.notifications) R.color.yellow else R.color.text_muted
        )

        val unread = AlertEventStore(this).unreadCount()
        findViewById<TextView>(R.id.notificationUnreadBadge).apply {
            text = unread.coerceAtMost(99).toString()
            visibility = if (unread > 0) android.view.View.VISIBLE else android.view.View.GONE
        }

        val workBadge = findViewById<TextView>(R.id.workModeBadge)
        when {
            snapshot.state == ProviderState.PROVIDER_READY && snapshot.message.contains("KAPANIŞ", ignoreCase = true) ->
                setBadge(workBadge, "KAPANIŞ VERİSİ", R.color.yellow)
            snapshot.state == ProviderState.PROVIDER_READY -> setBadge(workBadge, "CANLI VERİ", R.color.green)
            snapshot.state == ProviderState.PROVIDER_STALE_READY && snapshot.message.contains("KAPANIŞ", true) -> setBadge(workBadge, "KAPANIŞ VERİSİ", R.color.yellow)
            snapshot.state == ProviderState.PROVIDER_STALE_READY && snapshot.message.contains("GECİKMELİ", true) -> setBadge(workBadge, "GECİKMELİ ANALİZ", R.color.yellow)
            settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled -> setBadge(workBadge, "GECİKMELİ YEDEK", R.color.yellow)
            else -> setBadge(workBadge, "YAPILANDIRILMAMIŞ", R.color.text_muted)
        }
    }

    private fun setBadge(view: TextView, label: String, colorRes: Int) {
        view.text = label
        view.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun formatTime(epoch: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR")).format(Date(epoch))

    /**
     * B122 provider-recovery contract: provider ayarı kaydedildikten sonra gerçek preflight testi
     * otomatik çalışır. VİOP ekranından gelinmişse yalnız PROVIDER_READY sonucunda geri dönülür.
     */
    private fun autoTestAfterSave() {
        val local = readiness.localConfigState()
        if (local.state == ProviderState.PROVIDER_NOT_CONFIGURED) {
            Toast.makeText(this, local.message, Toast.LENGTH_LONG).show()
            refreshDashboard()
            return
        }
        lifecycleScope.launch {
            val returnToViop = intent.getBooleanExtra(EXTRA_RETURN_TO_VIOP, false)
            val result = if (returnToViop) readiness.testAll() else readiness.testBist()
            refreshDashboard()
            if (result.state == ProviderState.PROVIDER_READY || (!returnToViop && result.state == ProviderState.PROVIDER_STALE_READY && result.failureCode == ProviderFailureCode.NONE)) {
                val label = if (returnToViop) "VİOP BAĞLANTISI HAZIR" else result.message
                Toast.makeText(this@SettingsActivity, label, Toast.LENGTH_LONG).show()
                if (returnToViop) finish()
            } else {
                val prefix = if (returnToViop) "VİOP bağlantısı doğrulanamadı" else "BIST bağlantısı doğrulanamadı"
                Toast.makeText(this@SettingsActivity, "$prefix: ${result.failureCode} • ${result.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Mevcut production-readiness sözleşmesini koruyan gerçek cihaz/ağ denetimi. */
    @Suppress("unused")
    private suspend fun runProductionReadinessAudit(): String {
        val s = SettingsStore(this)
        val local = RuntimeReadinessDiagnostics.collect(this)
        val tf = ScanTimeframe.fromStored(s.analysisTimeframeMinutes)
        val lines = mutableListOf<String>()
        fun flag(ok: Boolean, label: String, fail: String = label) { lines += if (ok) "✓ $label" else "✗ $fail" }

        lines += "CİHAZ / PRODUCTION KABUL TANISI"
        lines += "Sürüm: ${local.appVersion} • ${local.androidVersion}"
        lines += "Backend: ${local.backendOrigin}"
        lines += "TradingView • BIST/VİOP veri kaynağı: HAYIR"
        flag(local.apiKeyConfigured, "API anahtarı yapılandırıldı", "API anahtarı yapılandırılmadı")
        flag(local.notificationPermission, "Bildirim izni hazır", "Bildirim izni yok")

        if (!ProviderReadinessService.isValidHttps(s.baseUrl) || s.apiKey.isBlank()) {
            lines += "✗ CANLI UÇTAN UCA TEST YAPILMADI: Production Backend HTTPS adresi ve API anahtarı eksik."
            return lines.joinToString("\n")
        }

        val preflight = BackendPreflightClient(this).checkBist()
        flag(preflight.ok, "Backend preflight başarılı", "Backend preflight başarısız: ${preflight.failureKind} • ${preflight.message}")
        if (!preflight.ok) return lines.joinToString("\n")
        val sample = preflight.sampleSymbol
        flag(!sample.isNullOrBlank(), "BIST örnek sembolü alındı: ${sample ?: ""}", "BIST örnek sembolü alınamadı")
        if (sample.isNullOrBlank()) return lines.joinToString("\n")

        val provider = IntervalMarketDataProvider(ProviderRouter(this), tf.storedMinutes)
        val stock = try {
            provider.fetchOne(sample)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
        val bars = stock?.candles?.size ?: 0
        val enough = bars >= RealTimeIntegrityPolicy.MIN_HISTORY_BARS
        flag(enough, "${tf.label} OHLCV probe başarılı: $sample • $bars mum", "${tf.label} OHLCV probe başarısız/yetersiz: $sample • $bars mum")
        lines += "Not: Bu ekran 24/48/72 saatlik stabilite testini anlık test gibi raporlamaz; sayaçlar gerçek çalışmalardan birikir."
        return lines.joinToString("\n")
    }

    companion object {
        const val EXTRA_RETURN_TO_VIOP = "return_to_viop_after_provider_test"
        const val EXTRA_PROVIDER_CONFIG_CHANGED = "provider_config_changed"
    }
}
