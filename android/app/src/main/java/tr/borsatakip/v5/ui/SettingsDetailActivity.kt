package tr.borsatakip.v5.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
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
import tr.borsatakip.v5.analysis.TradeForwardEngine
import tr.borsatakip.v5.data.AlertEventStore
import tr.borsatakip.v5.data.BackendPreflightClient
import tr.borsatakip.v5.data.IntervalMarketDataProvider
import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.ProviderFailureCode
import tr.borsatakip.v5.data.ProviderReadinessSnapshot
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.ProviderState
import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.data.RuntimeReadinessDiagnostics
import tr.borsatakip.v5.data.ScanTimeframe
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.worker.AutoScanScheduler
import tr.borsatakip.v5.worker.RealtimeAlertService
import tr.borsatakip.v5.worker.WorkScheduler
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SettingsDetailActivity : BaseActivity() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var readiness: ProviderReadinessService
    private lateinit var content: LinearLayout
    private var pendingNotificationEnable = false
    private var notificationToggle: Switch? = null

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!pendingNotificationEnable) return@registerForActivityResult
        pendingNotificationEnable = false
        applyNotificationSetting(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_detail)
        setupBottomNav()
        findViewById<TextView>(R.id.navSettings)?.setTextColor(ContextCompat.getColor(this, R.color.blue))

        settingsStore = SettingsStore(this)
        readiness = ProviderReadinessService(this)
        content = findViewById(R.id.detailContent)
        findViewById<TextView>(R.id.detailBack).setOnClickListener { finish() }

        when (intent.getStringExtra(EXTRA_MODE) ?: MODE_SYSTEM_STATUS) {
            MODE_SYSTEM_STATUS -> renderSystemStatus()
            MODE_DATA_CONNECTION -> renderDataConnection()
            MODE_API_KEYS -> renderApiKeys()
            MODE_SCAN -> renderScan()
            MODE_SIMULATION -> renderSimulation()
            MODE_NOTIFICATIONS -> renderNotifications()
            MODE_NOTIFICATION_HISTORY -> renderNotificationHistory()
            MODE_APPEARANCE -> renderAppearance()
            MODE_LOCALE -> renderLocale()
            MODE_DIAGNOSTICS -> renderDiagnostics()
            MODE_ABOUT -> renderAbout()
            MODE_WORK_MODE -> renderWorkMode()
            else -> renderSystemStatus()
        }
    }

    private fun setHeader(title: String, subtitle: String) {
        findViewById<TextView>(R.id.detailTitle).text = title
        findViewById<TextView>(R.id.detailSubtitle).text = subtitle
    }

    private fun renderSystemStatus() {
        setHeader("Sistem Durumu", "Gerçek provider, tarama ve bildirim yapılandırmasının son bilinen durumu")
        val status = addStatus("Son kayıtlı durum yükleniyor…")
        showLocalSystemStatus(status)
        addButton("ŞİMDİ TEKRAR TEST ET") { button ->
            buttonBusy(button, "TEST EDİLİYOR...")
            lifecycleScope.launch {
                val result = readiness.test()
                status.text = detailedProviderResult(result)
                buttonReady(button, "ŞİMDİ TEKRAR TEST ET")
            }
        }
    }

    private fun showLocalSystemStatus(status: TextView) {
        val snapshot = readiness.localConfigState()
        val permission = notificationPermissionGranted()
        status.text = buildString {
            append("Provider: ${snapshot.state}\n")
            append("Son kontrol: ${formatTime(snapshot.testedAt)}\n")
            append("Hata kodu: ${snapshot.failureCode}\n")
            append("Açıklama: ${snapshot.message}\n\n")
            append("Otomatik tarama tercihi: ${if (settingsStore.autoScanEnabled) "AÇIK" else "KAPALI"}\n")
            append("Son tarama: ${formatTime(settingsStore.autoScanLastRunAt)} • ${settingsStore.autoScanLastStatus}\n")
            append("Bildirim tercihi: ${if (settingsStore.notifications) "AÇIK" else "KAPALI"}\n")
            append("Android bildirim izni: ${if (permission) "VERİLDİ" else "VERİLMEDİ"}\n")
            append("WebSocket/canlı servis: ayrı bağımsız health probe mevcut değil; servis durumu burada uydurulmaz.\n\n")
            append("HTTPS/Auth/BIST/VİOP alt adımları için 'Şimdi tekrar test et' gerçek preflight çağrısı çalıştırır.")
        }
    }

    private fun renderDataConnection() {
        setHeader("Veri Bağlantısı", "Production Backend ve mevcut ProviderRouter bağlantısı")
        val url = addEdit("Production Backend URL", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, settingsStore.baseUrl)
        val status = addStatus(providerSummary())
        addButton("KAYITLI BAĞLANTIYI TEST ET") { button ->
            val typed = url.text.toString().trim().removeSuffix("/")
            if (typed != settingsStore.baseUrl) {
                status.text = "Formdaki URL kayıtlı URL'den farklı. Kalıcı ayarı değiştirmeden test yapılmaz; önce Kaydet'i kullanın."
                return@addButton
            }
            buttonBusy(button, "TEST EDİLİYOR...")
            lifecycleScope.launch {
                val result = readiness.test()
                status.text = detailedProviderResult(result)
                buttonReady(button, "KAYITLI BAĞLANTIYI TEST ET")
            }
        }
        addButton("KAYDET") {
            val typed = url.text.toString().trim().removeSuffix("/")
            if (typed.isNotBlank() && !ProviderReadinessService.isValidHttps(typed)) {
                status.text = "BAŞARISIZ • INVALID_HTTPS\nGeçerli HTTPS URL, geçerli host, userInfo/fragment içermeyen ve localhost olmayan adres gerekir."
                return@addButton
            }
            if (settingsStore.willClearApiKeyOnBackendChange(typed)) {
                AlertDialog.Builder(this)
                    .setTitle("Backend sunucusu değişiyor")
                    .setMessage("Backend sunucusu değiştiği için mevcut API anahtarı güvenlik amacıyla kaldırılacaktır. Yeni sunucu için API anahtarını yeniden girmeniz gerekir.")
                    .setNegativeButton("Vazgeç", null)
                    .setPositiveButton("Devam et") { _, _ -> saveBackendUrlAndClose(typed) }
                    .show()
            } else {
                saveBackendUrlAndClose(typed)
            }
        }
        addStatus("Provider etiketi: ${settingsStore.lastProviderLabel}\nSon provider zamanı: ${formatTime(settingsStore.lastProviderTimestamp)}\nSon hata: ${settingsStore.lastProviderFailureCode.ifBlank { "yok" }}")
    }

    private fun saveBackendUrlAndClose(typed: String) {
        settingsStore.baseUrl = typed
        setResult(RESULT_OK, Intent().putExtra(SettingsActivity.EXTRA_PROVIDER_CONFIG_CHANGED, true))
        Toast.makeText(this, "Backend adresi kaydedildi. Ana Ayarlar ekranında gerçek provider testi otomatik çalışacak.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun renderApiKeys() {
        setHeader("API Anahtarları", "Yalnız uygulamanın gerçekten kullandığı API erişim anahtarı gösterilir")
        addStatus(if (settingsStore.apiKey.isBlank()) "API anahtarı: YAPILANDIRILMAMIŞ" else "API anahtarı: YAPILANDIRILDI • mevcut değer güvenlik nedeniyle ekrana yüklenmez")
        val key = addEdit("Yeni API Key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "")
        key.hint = if (settingsStore.apiKey.isBlank()) "API erişim anahtarı" else "Değiştirmek için yeni anahtar girin"
        val status = addStatus("Anahtar Android Keystore ile şifreli saklanır. Diagnostics/log/bildirim çıktısına yazılmaz.")
        addButton("KAYITLI KİMLİK BİLGİSİYLE BAĞLANTIYI TEST ET") { button ->
            buttonBusy(button, "TEST EDİLİYOR...")
            lifecycleScope.launch {
                val result = readiness.test()
                status.text = detailedProviderResult(result)
                buttonReady(button, "KAYITLI KİMLİK BİLGİSİYLE BAĞLANTIYI TEST ET")
            }
        }
        addButton("YENİ ANAHTARI KAYDET") {
            val newKey = key.text.toString().trim()
            if (newKey.isBlank()) {
                status.text = "Yeni anahtar boş. Mevcut anahtar değiştirilmedi."
                return@addButton
            }
            settingsStore.apiKey = newKey
            key.setText("")
            setResult(RESULT_OK, Intent().putExtra(SettingsActivity.EXTRA_PROVIDER_CONFIG_CHANGED, true))
            Toast.makeText(this, "API anahtarı güvenli depoya kaydedildi. Provider readiness geçersizleştirildi ve yeniden doğrulanacak.", Toast.LENGTH_LONG).show()
            finish()
        }
        addButton("API ANAHTARINI SİL") {
            AlertDialog.Builder(this)
                .setTitle("API anahtarı silinsin mi?")
                .setMessage("Bu işlem yalnız güvenli depodaki API anahtarını kaldırır. Favoriler, geçmiş ve diğer kullanıcı verileri silinmez.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil") { _, _ ->
                    settingsStore.clearApiKey()
                    setResult(RESULT_OK, Intent().putExtra(SettingsActivity.EXTRA_PROVIDER_CONFIG_CHANGED, true))
                    finish()
                }.show()
        }
    }

    private fun renderScan() {
        setHeader("Tarama", "Mevcut tarama motorunun gerçek parametreleri")
        val timeframe = addEdit("Analiz timeframe (1..239 dk veya 1440=1 gün)", InputType.TYPE_CLASS_NUMBER, settingsStore.analysisTimeframeMinutes.toString())
        val refresh = addEdit("Bildirim/yenileme süresi (en az 15 dk)", InputType.TYPE_CLASS_NUMBER, settingsStore.refreshMinutes.toString())
        val auto = addSwitch("Otomatik tarama", settingsStore.autoScanEnabled)
        val forward = addSwitch("Forward outcome takibi", settingsStore.forwardOutcomeEnabled)
        val status = addStatus(
            "Mevcut scanMode: ${settingsStore.scanMode}\n" +
                "Mevcut scheduler cadence: ${settingsStore.scanCadenceMinutes} dk\n" +
                "Not: AutoScanScheduler cadence'i seçilen analiz timeframe'i ile uzlaştırır; ayrı sahte cadence uygulanmaz."
        )
        addButton("TARAMA AYARLARINI KAYDET") {
            val tf = timeframe.text.toString().toIntOrNull()
            if (tf == null || !ScanTimeframe.isSupportedStored(tf)) {
                status.text = "Geçersiz timeframe. 1..239 dakika veya 1440 (1 gün) kullanın."
                return@addButton
            }
            settingsStore.analysisTimeframeMinutes = tf
            settingsStore.refreshMinutes = (refresh.text.toString().toIntOrNull() ?: settingsStore.refreshMinutes).coerceAtLeast(15)
            settingsStore.autoScanEnabled = auto.isChecked
            settingsStore.forwardOutcomeEnabled = forward.isChecked
            AutoScanScheduler.reconcile(this, allowForegroundStart = settingsStore.autoScanEnabled)
            WorkScheduler.reconcile(this, settingsStore.notifications, settingsStore.refreshMinutes)
            status.text = "Kaydedildi • timeframe=${ScanTimeframe.displayLabel(settingsStore.analysisTimeframeMinutes)} • scheduler cadence=${settingsStore.scanCadenceMinutes} dk • autoScan=${settingsStore.autoScanEnabled} • forward=${settingsStore.forwardOutcomeEnabled}"
        }
    }

    private fun renderSimulation() {
        setHeader("İşlem Simülasyonu", "Gerçek emir göndermez; mevcut TradeForwardEngine maliyet formülünü kullanır")
        val quantity = addEdit("Miktar", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, displayDouble(settingsStore.tradeSimulationQuantity))
        val commission = addEdit("Tek yön komisyon", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, displayDouble(settingsStore.tradeCommissionPerSide))
        val slippage = addEdit("Tek yön slippage (bps)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, displayDouble(settingsStore.tradeSlippageBpsPerSide))
        val direction = addSpinner("Yön", listOf("LONG", "SHORT"))
        val entry = addEdit("Giriş fiyatı", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "")
        val exit = addEdit("Çıkış fiyatı", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "")
        val resultView = addStatus("Hesaplama yapılmadı.")
        addButton("SİMÜLASYON AYARLARINI KAYDET") {
            val qty = parseDoubleOrNull(quantity)
            val comm = parseDoubleOrNull(commission)
            val slip = parseDoubleOrNull(slippage)
            if (qty == null || comm == null || slip == null || qty < 0.0 || comm < 0.0 || slip < 0.0) {
                resultView.text = "Miktar/komisyon/slippage geçerli ve negatif olmayan sayılar olmalıdır."
                return@addButton
            }
            settingsStore.tradeSimulationQuantity = qty
            settingsStore.tradeCommissionPerSide = comm
            settingsStore.tradeSlippageBpsPerSide = slip
            resultView.text = "Simülasyon maliyet ayarları kaydedildi."
        }
        addButton("HESAPLA") {
            val qty = parseDoubleOrNull(quantity) ?: Double.NaN
            val comm = parseDoubleOrNull(commission) ?: Double.NaN
            val slip = parseDoubleOrNull(slippage) ?: Double.NaN
            val e = parseDoubleOrNull(entry) ?: Double.NaN
            val x = parseDoubleOrNull(exit) ?: Double.NaN
            val estimate = TradeForwardEngine.estimateRoundTrip(
                direction = direction.selectedItem.toString(),
                entry = e,
                exit = x,
                costs = TradeForwardEngine.CostConfig(qty, 1.0, comm, slip)
            )
            resultView.text = estimate.fold(
                onSuccess = { r ->
                    "Yön: ${direction.selectedItem}\nGiriş: ${fmt(r.entry)}\nÇıkış: ${fmt(r.exit)}\nMiktar: ${fmt(qty)}\nBrüt P/L: ${fmt(r.grossPnl)}\nKomisyon + kayma: ${fmt(r.totalCosts)}\nNet P/L: ${fmt(r.netPnl)}\nNet getiri: ${fmt(r.netReturnPct)}%\n\nBu sonuç simülasyondur; emir gönderilmedi."
                },
                onFailure = { "Hesaplama başarısız: ${it.message ?: "geçersiz değer"}" }
            )
        }
    }

    private fun renderNotifications() {
        setHeader("Bildirimler", "Android izni, SettingsStore, WorkScheduler ve RealtimeAlertService birlikte uzlaştırılır")
        val toggle = addSwitch("Fırsat bildirimleri", settingsStore.notifications)
        notificationToggle = toggle
        val status = addStatus(notificationStatusText())
        addButton("BİLDİRİM AYARINI UYGULA") {
            if (!toggle.isChecked) {
                settingsStore.notifications = false
                WorkScheduler.reconcile(this, false, settingsStore.refreshMinutes)
                RealtimeAlertService.stop(this)
                status.text = notificationStatusText()
                return@addButton
            }
            if (!notificationPermissionGranted()) {
                pendingNotificationEnable = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                applyNotificationSetting(true)
                status.text = notificationStatusText()
            }
        }
    }

    private fun applyNotificationSetting(permissionGranted: Boolean) {
        settingsStore.notifications = permissionGranted
        notificationToggle?.isChecked = settingsStore.notifications
        WorkScheduler.reconcile(this, settingsStore.notifications, settingsStore.refreshMinutes)
        val serviceRequested = if (settingsStore.notifications) RealtimeAlertService.start(this) else {
            RealtimeAlertService.stop(this)
            false
        }
        Toast.makeText(
            this,
            when {
                !permissionGranted -> "Bildirim izni verilmedi; bildirim tercihi kapalı tutuldu."
                serviceRequested -> "Bildirim tercihi ve mevcut benzersiz scheduler/service akışı uzlaştırıldı."
                else -> "Bildirim tercihi kaydedildi ancak realtime servis başlatılamadı; provider yapılandırmasını kontrol edin."
            },
            Toast.LENGTH_LONG
        ).show()
        content.findViewWithTag<TextView>(TAG_NOTIFICATION_STATUS)?.text = notificationStatusText()
    }

    private fun notificationStatusText(): String {
        val permission = notificationPermissionGranted()
        val provider = readiness.localConfigState()
        val running = RealtimeAlertService.isRunningInProcess()
        return buildString {
            append("Bildirim tercihi: ${if (settingsStore.notifications) "AÇIK" else "KAPALI"}\n")
            append("Android izni: ${if (permission) "VERİLDİ" else "VERİLMEDİ"}\n")
            append("Provider readiness: ${provider.state}\n")
            append("Realtime servis (bu uygulama süreci): ${if (running) "ÇALIŞIYOR" else "ÇALIŞMIYOR / BAŞLATILMADI"}\n")
            append("Realtime servis için gerekli yapılandırma: ${if (ProviderReadinessService.isValidHttps(settingsStore.baseUrl) && settingsStore.apiKey.isNotBlank()) "MEVCUT" else "EKSİK"}")
        }
    }

    private fun renderNotificationHistory() {
        setHeader("Bildirim Geçmişi", "AlertEventStore tarafından gerçek tarama sonuçlarından üretilen kalıcı olaylar")
        val store = AlertEventStore(this)
        val events = store.recentEvents(100)
        val body = if (events.isEmpty()) {
            "Henüz bildirim bulunmuyor. Sahte örnek kayıt oluşturulmadı."
        } else {
            val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR"))
            events.joinToString("\n\n") { e ->
                "${df.format(Date(e.createdAt))} • ${e.symbol}\n${e.type.name} • ${e.message}\nSkor: ${e.score}/100"
            }
        }
        addStatus(body)
        store.markAllRead()
    }

    private fun renderAppearance() {
        setHeader("Görünüm", "Yalnız gerçekten desteklenen görünüm seçenekleri gösterilir")
        addStatus(
            "Tema: Koyu\n\nBu kaynak sürümünde renk/drawable yapısı koyu tema için tasarlanmıştır. Açık/Sistem tema desteği bütün ekranlarda doğrulanmış bir altyapı olarak bulunmadığı için sahte tema seçeneği sunulmamıştır."
        )
    }

    private fun renderLocale() {
        setHeader("Dil ve Bölge", "Uygulamanın gerçek kaynak ve cihaz bölge bilgileri")
        val tr = Locale("tr", "TR")
        val sampleNumber = NumberFormat.getNumberInstance(tr).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(10642.35)
        val sampleDate = SimpleDateFormat("dd.MM.yyyy", tr).format(Date())
        addStatus(
            "Dil: Türkçe (TR)\n" +
                "Saat dilimi: ${TimeZone.getDefault().id}\n" +
                "Sayı örneği: $sampleNumber\n" +
                "Tarih biçimi: $sampleDate\n\n" +
                "Kaynaklarda doğrulanmış ek uygulama dili bulunmadığı için sahte dil seçeneği gösterilmez."
        )
    }

    private fun renderDiagnostics() {
        setHeader("Sistem Tanılama", "Gerçek cihaz durumu + HTTPS/Auth/BIST/VİOP/OHLCV preflight")
        val status = addStatus("Henüz bu ekranda canlı tanılama çalıştırılmadı.")
        addButton("TANILAMAYI ÇALIŞTIR") { button ->
            buttonBusy(button, "TEST EDİLİYOR...")
            lifecycleScope.launch {
                status.text = runFullDiagnostics()
                buttonReady(button, "TANILAMAYI ÇALIŞTIR")
            }
        }
        addButton("PİL OPTİMİZASYONU AYARLARINI AÇ") {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { Toast.makeText(this, "Pil optimizasyonu ayarları bu cihazda açılamadı.", Toast.LENGTH_LONG).show() }
        }
    }

    private suspend fun runFullDiagnostics(): String {
        val local = RuntimeReadinessDiagnostics.collect(this)
        val lines = mutableListOf<String>()
        lines += "CİHAZ / RUNTIME"
        lines += "Sürüm: ${local.appVersion} • ${local.androidVersion}"
        lines += "Cihaz: ${local.device}"
        lines += "Backend origin: ${local.backendOrigin}"
        lines += "API key: ${if (local.apiKeyConfigured) "✓ yapılandırıldı" else "— yapılandırılmadı"}"
        lines += "Bildirim izni: ${if (local.notificationPermission) "✓ verildi" else "! verilmedi"}"
        lines += "Pil optimizasyonu: ${if (local.batteryOptimizationExempt) "✓ muaf" else "! muaf değil/doğrulanmadı"}"
        lines += "Otomatik tarama: ${if (local.autoScanEnabled) "açık" else "kapalı"} • ${local.autoScanCadenceMinutes} dk • ${local.schedulerMode}"
        lines += ""

        if (!ProviderReadinessService.isValidHttps(settingsStore.baseUrl) || settingsStore.apiKey.isBlank()) {
            lines += "— CANLI PROVIDER TESTİ: yapılandırılmamış"
            return lines.joinToString("\n")
        }

        val providerResult = readiness.test()
        lines += detailedProviderResult(providerResult)
        val analysisReady = providerResult.state == ProviderState.PROVIDER_READY || (providerResult.state == ProviderState.PROVIDER_STALE_READY && providerResult.failureCode == ProviderFailureCode.NONE)
        if (!analysisReady) return lines.joinToString("\n")

        val preflight = BackendPreflightClient(this).checkBist()
        if (!preflight.ok || preflight.sampleSymbol.isNullOrBlank()) {
            lines += "✕ Seçili timeframe OHLCV probe başlatılamadı: ${preflight.failureKind} • ${preflight.message}"
            return lines.joinToString("\n")
        }
        val tf = ScanTimeframe.fromStored(settingsStore.analysisTimeframeMinutes)
        val delayedMode = preflight.bistAvailabilityMode == BackendPreflightClient.BistAvailabilityMode.DELAYED_ANALYSIS
        val closeMode = preflight.bistAvailabilityMode == BackendPreflightClient.BistAvailabilityMode.SESSION_CLOSE
        val provider = IntervalMarketDataProvider(ProviderRouter(this), tf.storedMinutes, sessionCloseMode = closeMode, delayedObservationMode = delayedMode)
        val stock = try {
            provider.fetchOne(requireNotNull(preflight.sampleSymbol))
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
        val bars = stock?.candles?.size ?: 0
        lines += if (bars >= RealTimeIntegrityPolicy.MIN_HISTORY_BARS) "✓ ${tf.label} OHLCV: $bars mum" else "✕ ${tf.label} OHLCV: yetersiz ($bars mum)"
        lines += "— WebSocket: ayrı bağımsız health endpoint/probe yok; sonuç uydurulmadı."
        return lines.joinToString("\n")
    }

    private fun renderAbout() {
        setHeader("Uygulama Hakkında", "Derleme bilgileri doğrudan BuildConfig ve paket kaynaklarından")
        addStatus(
            "Uygulama: ${getString(R.string.app_name)}\n" +
                "Sürüm: ${BuildConfig.VERSION_NAME}\n" +
                "Build: ${BuildConfig.VERSION_CODE}\n" +
                "Application ID: ${BuildConfig.APPLICATION_ID}\n" +
                "Geliştirici: Paket metadatasında ayrı geliştirici alanı tanımlı değil\n" +
                "Lisans: Paket metadatasında ayrı lisans belgesi tanımlı değil\n" +
                "Gizlilik: Paket metadatasında ayrı gizlilik URL'si tanımlı değil\n\n" +
                "Kullanılan servisler: Production HTTPS backend, mevcut ProviderRouter, Android WorkManager, isteğe bağlı realtime alert servisi ve yalnız görüntüleme amaçlı harici TradingView bağlantısı."
        )
    }

    private fun renderWorkMode() {
        setHeader("Çalışma Modu", "Yalnız kaynakta gerçekten bulunan çalışma yolu gösterilir")
        val snapshot = readiness.localConfigState()
        val mode = when {
            snapshot.state == ProviderState.PROVIDER_READY -> "CANLI VERİ"
            snapshot.state == ProviderState.PROVIDER_STALE_READY && snapshot.message.contains("GECİKMELİ", true) -> "GECİKMELİ ANALİZ"
            snapshot.state == ProviderState.PROVIDER_STALE_READY && snapshot.message.contains("KAPANIŞ", true) -> "KAPANIŞ ANALİZİ"
            settingsStore.experimentalProvidersEnabled && settingsStore.yahooFallbackEnabled -> "GECİKMELİ YEDEK ANALİZ"
            else -> "YAPILANDIRILMAMIŞ"
        }
        addStatus(
            "Geçerli çalışma modu: $mode\n" +
                "Provider: ${snapshot.state}\n" +
                "ScanMode son durumu: ${settingsStore.scanMode}\n\n" +
                "Ayrı bir 'gerçek emir' modu yoktur. İşlem Simülasyonu yalnız hesaplama yapar ve emir göndermez."
        )
    }

    private fun detailedProviderResult(result: ProviderReadinessSnapshot): String = buildString {
        append("Genel: ${result.state}\n")
        append("Son kontrol: ${formatTime(result.testedAt)}\n")
        append("Hata: ${result.failureCode}\n")
        append("Açıklama: ${result.message}\n\n")
        if (result.testedAt <= 0L) {
            append("Alt adımlar: henüz canlı test edilmedi")
        } else {
            append("HTTPS/Health: ${flag(result.healthOk)}\n")
            append("Authentication: ${flag(result.authOk)}\n")
            append("BIST Symbols: ${flag(result.bistSymbolsOk)} (${result.bistSymbolCount})\n")
            if (result.state == ProviderState.PROVIDER_STALE_READY && !result.bistQuoteOk && result.message.contains("KAPANIŞ", ignoreCase = true)) {
                append("BIST Quote: — Seans kapalı; canlı quote kapanış taraması için zorunlu değil\n")
            } else if (result.state == ProviderState.PROVIDER_STALE_READY && !result.bistQuoteOk && result.message.contains("GECİKMELİ", ignoreCase = true)) {
                append("BIST Quote: ! Canlı doğrulanmadı; gecikmeli gözlem modu\n")
            } else {
                append("BIST Quote: ${flag(result.bistQuoteOk)}\n")
            }
            append("BIST History: ${flag(result.bistHistoryOk)}\n")
            val bistOnly = result.message.startsWith("BIST ", ignoreCase = true)
            if (bistOnly) {
                append("VİOP: Bu BIST bağlantı testinde değerlendirilmedi; VİOP ekranından ayrı doğrulanır.")
            } else {
                append("VİOP Contracts: ${flag(result.viopContractsOk)} (${result.viopContractCount})\n")
                append("VİOP Quote: ${flag(result.viopQuoteOk)}\n")
                append("VİOP History: ${flag(result.viopHistoryOk)}")
            }
        }
    }

    private fun providerSummary(): String {
        val x = readiness.localConfigState()
        return "Provider: ${x.state}\nSon kontrol: ${formatTime(x.testedAt)}\nSon hata: ${x.failureCode}\n${x.message}"
    }

    private fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun flag(ok: Boolean): String = if (ok) "✓ Başarılı" else "✕ Başarısız / doğrulanmadı"

    private fun formatTime(epoch: Long): String = if (epoch > 0L) {
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr", "TR")).format(Date(epoch))
    } else "yok"

    private fun addLabel(text: String): TextView = TextView(this).also { v ->
        v.text = text
        v.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        v.setTypeface(v.typeface, Typeface.BOLD)
        v.textSize = 13f
        v.setPadding(2.dp(), 8.dp(), 2.dp(), 5.dp())
        content.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun addEdit(label: String, inputType: Int, value: String): EditText {
        addLabel(label)
        return EditText(this).also { v ->
            v.inputType = inputType
            v.setText(value)
            v.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            v.setHintTextColor(ContextCompat.getColor(this, R.color.text_muted))
            v.setBackgroundResource(R.drawable.bg_card)
            v.setPadding(12.dp(), 0, 12.dp(), 0)
            content.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 52.dp()).apply { bottomMargin = 6.dp() })
        }
    }

    private fun addSwitch(label: String, checked: Boolean): Switch = Switch(this).also { v ->
        v.text = label
        v.isChecked = checked
        v.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        v.setBackgroundResource(R.drawable.bg_card)
        v.setPadding(12.dp(), 0, 12.dp(), 0)
        content.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 54.dp()).apply { bottomMargin = 7.dp() })
    }

    private fun addSpinner(label: String, items: List<String>): Spinner {
        addLabel(label)
        return Spinner(this).also { v ->
            v.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
            v.setBackgroundResource(R.drawable.bg_card)
            content.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 52.dp()).apply { bottomMargin = 7.dp() })
        }
    }

    private fun addStatus(text: String): TextView = TextView(this).also { v ->
        v.text = text
        v.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        v.textSize = 12f
        v.setLineSpacing(0f, 1.12f)
        v.setBackgroundResource(R.drawable.bg_card)
        v.setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
        content.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dp() })
        if (intent.getStringExtra(EXTRA_MODE) == MODE_NOTIFICATIONS) v.tag = TAG_NOTIFICATION_STATUS
    }

    private fun addButton(label: String, action: (Button) -> Unit): Button = Button(this).also { v ->
        v.text = label
        v.isAllCaps = false
        v.setTextColor(ContextCompat.getColor(this, R.color.white))
        v.setBackgroundResource(R.drawable.bg_card_blue)
        v.setOnClickListener { action(v) }
        content.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 50.dp()).apply { bottomMargin = 8.dp() })
    }

    private fun buttonBusy(button: Button, label: String) {
        button.isEnabled = false
        button.text = label
    }

    private fun buttonReady(button: Button, label: String) {
        button.text = label
        button.isEnabled = true
    }

    private fun parseDoubleOrNull(edit: EditText): Double? = edit.text.toString().trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun displayDouble(v: Double): String = if (v == 0.0) "0" else v.toString()
    private fun fmt(v: Double): String = NumberFormat.getNumberInstance(Locale("tr", "TR")).apply { maximumFractionDigits = 4 }.format(v)
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MODE = "settings_detail_mode"
        const val MODE_SYSTEM_STATUS = "system_status"
        const val MODE_DATA_CONNECTION = "data_connection"
        const val MODE_API_KEYS = "api_keys"
        const val MODE_SCAN = "scan"
        const val MODE_SIMULATION = "simulation"
        const val MODE_NOTIFICATIONS = "notifications"
        const val MODE_NOTIFICATION_HISTORY = "notification_history"
        const val MODE_APPEARANCE = "appearance"
        const val MODE_LOCALE = "locale"
        const val MODE_DIAGNOSTICS = "diagnostics"
        const val MODE_ABOUT = "about"
        const val MODE_WORK_MODE = "work_mode"
        private const val TAG_NOTIFICATION_STATUS = "notification_status"
    }
}
