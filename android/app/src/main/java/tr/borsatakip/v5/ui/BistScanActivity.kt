package tr.borsatakip.v5.ui

import android.Manifest
import tr.borsatakip.v5.data.ProviderReadinessService
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.OpportunityRankingPolicy
import tr.borsatakip.v5.data.ProviderFallbackPolicy
import tr.borsatakip.v5.data.BistIndexDataService
import tr.borsatakip.v5.data.MarketDataQuality
import tr.borsatakip.v5.data.ProviderFailureCode
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.ManualScanSession
import tr.borsatakip.v5.data.ManualScanSessionRepository
import tr.borsatakip.v5.data.ManualScanStatus
import tr.borsatakip.v5.data.ScanTimeframe
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.Stock
import tr.borsatakip.v5.model.ScanMode
import tr.borsatakip.v5.worker.AutoScanScheduler
import tr.borsatakip.v5.worker.BistScanForegroundService
import tr.borsatakip.v5.worker.BistScanStartGate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BistScanActivity : BaseActivity() {
    private enum class QuickMode { POPULAR, FAVORITES, CUSTOM }

    private lateinit var source: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var statusText: TextView
    private lateinit var startScanButton: Button
    private lateinit var stopScanButton: Button
    private lateinit var currentSymbolText: TextView
    private lateinit var providerStatusText: TextView
    private lateinit var lastUpdateText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var scanRepository: ManualScanSessionRepository
    private var completionHandledScanId: String? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("tr", "TR"))
    private lateinit var settings: SettingsStore
    private lateinit var search: EditText
    private lateinit var heroSubtitle: TextView
    private lateinit var detailPanel: View
    private lateinit var quickPopular: TextView
    private lateinit var quickFavorites: TextView
    private lateinit var quickCustom: TextView
    private lateinit var scanTimeButton: View
    private lateinit var scanTimeArrow: TextView
    private lateinit var activeScanTime: TextView
    private lateinit var autoScanSwitch: Switch
    private lateinit var autoScanStatus: TextView
    private var suppressAutoScanListener = false
    private var scanTimePopup: PopupWindow? = null
    private var quickMode = QuickMode.POPULAR
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when (BistScanStartGate.startAfterPermission(this, granted)) {
            BistScanStartGate.Outcome.STARTED -> Unit
            BistScanStartGate.Outcome.PERMISSION_REQUESTED -> Unit
            BistScanStartGate.Outcome.PROVIDER_UNAVAILABLE -> startActivity(Intent(this, SettingsActivity::class.java))
            BistScanStartGate.Outcome.FAILED -> Toast.makeText(this, if (granted) "Manuel BIST tarama servisi başlatılamadı." else "Arka planda manuel tarama için bildirim izni gerekir.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bist_scan)
        setupBottomNav()
        setupScanBottomNav()

        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.txtProgress)
        statusText = findViewById(R.id.txtStatus)
        startScanButton = findViewById(R.id.btnStartScan)
        stopScanButton = findViewById(R.id.btnStopScan)
        currentSymbolText = findViewById(R.id.txtCurrentSymbol)
        providerStatusText = findViewById(R.id.txtProviderStatus)
        lastUpdateText = findViewById(R.id.txtLastUpdate)
        elapsedText = findViewById(R.id.txtElapsed)
        source = findViewById(R.id.txtSource)
        val debug = findViewById<TextView>(R.id.txtDebugState)
        search = findViewById(R.id.scanSearch)
        heroSubtitle = findViewById(R.id.txtScanHeroSubtitle)
        detailPanel = findViewById(R.id.scanDetailPanel)
        quickPopular = findViewById(R.id.quickPopular)
        quickFavorites = findViewById(R.id.quickFavorites)
        quickCustom = findViewById(R.id.quickCustom)
        scanTimeButton = findViewById(R.id.btnScanTime)
        scanTimeArrow = findViewById(R.id.txtScanTimeArrow)
        activeScanTime = findViewById(R.id.txtActiveScanTime)
        autoScanSwitch = findViewById(R.id.switchAutoScan)
        autoScanStatus = findViewById(R.id.txtAutoScanStatus)
        settings = SettingsStore(this)

        refreshSourceLabel()
        refreshScanTimeUi()
        refreshAutoScanUi()
        scanTimeButton.setOnClickListener { toggleScanTimeMenu() }
        autoScanSwitch.setOnCheckedChangeListener { _, enabled ->
            if (suppressAutoScanListener) return@setOnCheckedChangeListener
            settings.autoScanEnabled = enabled
            if (enabled && settings.autoScanMonitoringStartedAt <= 0L) settings.autoScanMonitoringStartedAt = System.currentTimeMillis()
            val selected = ScanTimeframe.fromStored(settings.analysisTimeframeMinutes)
            settings.scanCadenceMinutes = selected.storedMinutes.coerceIn(1, 1440)
            AutoScanScheduler.reconcile(this, allowForegroundStart = true)
            if (!productionBackendConfigured() && enabled) {
                if (fallbackAllowed()) {
                    statusText.text = "YEDEK/GECİKMELİ KAYNAK"
                    heroSubtitle.text = "${selected.label} otomatik tarama AÇIK • Yahoo Finance yedek/gecikmeli kaynak kullanılacak."
                } else {
                    statusText.text = "VERİ SAĞLAYICI HAZIR DEĞİL"
                    heroSubtitle.text = "Production Backend hazır değil ve Yahoo fallback kullanıcı ayarlarında kapalı."
                }
            }
            refreshAutoScanUi()
        }
        scanRepository = ManualScanSessionRepository.get(this)
        val restoredSession = scanRepository.snapshot()
        if (!restoredSession.isActive) completionHandledScanId = restoredSession.scanId
        startScanButton.text = "ARA"
        statusText.text = "HAZIR"
        progressText.text = "TARAMA BAŞLAMADI"
        heroSubtitle.text = initialHeroMessage()
        progress.progress = 0
        progress.visibility = View.GONE
        debug.text = "Tarama state'i foreground service üzerinden gerçek işlenen kayıtlarla güncellenir."
        refreshMarketSummary()

        findViewById<View>(R.id.btnBistNotifications).setOnClickListener { startActivity(Intent(this, NotificationsActivity::class.java)) }
        findViewById<View>(R.id.btnBistSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.btnBistTheme).setOnClickListener {
            startActivity(
                Intent(this, SettingsDetailActivity::class.java)
                    .putExtra(SettingsDetailActivity.EXTRA_MODE, SettingsDetailActivity.MODE_APPEARANCE)
            )
        }
        findViewById<View>(R.id.btnStatusDetail).setOnClickListener { toggleDetails() }
        findViewById<View>(R.id.btnSearchFocus).setOnClickListener {
            selectQuickMode(QuickMode.CUSTOM)
            focusSearch()
        }
        findViewById<View>(R.id.btnScanFilter).setOnClickListener {
            selectQuickMode(QuickMode.CUSTOM)
            focusSearch()
        }
        quickPopular.setOnClickListener { selectQuickMode(QuickMode.POPULAR) }
        quickFavorites.setOnClickListener { selectQuickMode(QuickMode.FAVORITES) }
        quickCustom.setOnClickListener {
            selectQuickMode(QuickMode.CUSTOM)
            focusSearch()
        }

        findViewById<View>(R.id.quickSectors).setOnClickListener { startActivity(Intent(this, StocksActivity::class.java)) }
        findViewById<View>(R.id.quickCriteria).setOnClickListener {
            selectQuickMode(QuickMode.CUSTOM)
            focusSearch()
        }
        findViewById<View>(R.id.quickHistory).setOnClickListener { startActivity(Intent(this, SignalHistoryActivity::class.java)) }
        findViewById<View>(R.id.quickSaved).setOnClickListener { startActivity(Intent(this, FavoritesActivity::class.java)) }
        findViewById<View>(R.id.btnAllTools).setOnClickListener { startActivity(Intent(this, OpportunityActivity::class.java)) }

        startScanButton.setOnClickListener { startManualScanWithNotificationGate() }

        stopScanButton.setOnClickListener {
            BistScanForegroundService.stop(this)
        }

        lifecycleScope.launch {
            scanRepository.state.collect { session -> renderManualScanSession(session) }
        }
    }

    private fun startManualScanWithNotificationGate() {
        completionHandledScanId = null
        settings.scanMode = ScanMode.MANUAL
        when (BistScanStartGate.startWithPermissionGate(this) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }) {
            BistScanStartGate.Outcome.STARTED, BistScanStartGate.Outcome.PERMISSION_REQUESTED -> Unit
            BistScanStartGate.Outcome.PROVIDER_UNAVAILABLE -> startActivity(Intent(this, SettingsActivity::class.java))
            BistScanStartGate.Outcome.FAILED -> Toast.makeText(this, "Manuel BIST tarama servisi başlatılamadı.", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderManualScanSession(session: ManualScanSession) {
        val total = session.totalCount
        val processed = if (total > 0) session.scannedCount.coerceIn(0, total) else session.scannedCount.coerceAtLeast(0)
        progress.visibility = if (session.status == ManualScanStatus.IDLE && total <= 0) View.GONE else View.VISIBLE
        progress.progress = BistScanUiPolicy.progressPercent(session)
        progressText.text = BistScanUiPolicy.progressLabel(session)
        statusText.text = when (session.status) {
            ManualScanStatus.IDLE -> "HAZIR"
            ManualScanStatus.PREFLIGHT -> "DOĞRULANIYOR"
            ManualScanStatus.RUNNING -> "SCANNING"
            ManualScanStatus.PAUSED -> "PAUSED"
            ManualScanStatus.FINALIZING -> "SONUÇ KAYDEDİLİYOR"
            ManualScanStatus.COMPLETED -> "COMPLETED"
            ManualScanStatus.PARTIAL -> "KISMİ TAMAMLANDI"
            ManualScanStatus.STOPPED -> "STOPPED"
            ManualScanStatus.ERROR -> "ERROR"
            ManualScanStatus.DATA_UNAVAILABLE -> "VERİ YOK"
            ManualScanStatus.INTERRUPTED -> "KESİLDİ"
        }
        heroSubtitle.text = session.message ?: initialHeroMessage()
        currentSymbolText.text = "İşlenen: ${session.currentSymbol ?: "—"}"
        providerStatusText.text = "Kaynak: ${session.providerStatus} • ${session.dataQuality.name}"
        lastUpdateText.text = "Son güncelleme: ${session.lastUpdate.takeIf { it > 0L }?.let { timeFormat.format(Date(it)) } ?: "—"}"
        elapsedText.text = BistScanUiPolicy.elapsedLabel(session)
        val active = session.isActive
        startScanButton.isEnabled = !active
        startScanButton.alpha = if (active) 0.55f else 1f
        stopScanButton.isEnabled = active
        stopScanButton.alpha = if (active) 1f else 0.45f
        startScanButton.text = "ARA"
        findViewById<TextView>(R.id.txtDebugState).text = buildString {
            append("State=${session.status} • Phase=${session.phase} • ")
            append("İşlenen=$processed/${if (total > 0) total else "?"} • ")
            append("LONG=${session.longCount} • SHORT=${session.shortCount} • İZLE=${session.watchCount}")
            session.runStatus?.let { append(" • Run=$it") }
        }
        if (session.status in setOf(ManualScanStatus.COMPLETED, ManualScanStatus.PARTIAL) &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            handleCompletedSession(session)
        }
    }

    private fun handleCompletedSession(session: ManualScanSession) {
        if (session.scanId.isBlank() || completionHandledScanId == session.scanId) return
        val finalState = AppSession.lastScanState ?: return
        completionHandledScanId = session.scanId
        lifecycleScope.launch {
            val visibleResults = filterResults(OpportunityRankingPolicy.sort(finalState.results))
            AppSession.lastOpportunities = visibleResults
            if (visibleResults.isNotEmpty()) startActivity(Intent(this@BistScanActivity, ScanResultsActivity::class.java))
        }
    }

    private fun toggleScanTimeMenu() {
        if (scanTimePopup?.isShowing == true) {
            scanTimePopup?.dismiss()
        } else {
            showScanTimeMenu()
        }
    }

    private fun showScanTimeMenu() {
        val selected = settings.analysisTimeframeMinutes
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setBackgroundResource(R.drawable.bg_card)
        }

        QUICK_TIMEFRAMES.forEach { tf ->
            panel.addView(scanTimeRow(tf.label, tf.storedMinutes == selected) {
                applyScanInterval(tf.storedMinutes)
                scanTimePopup?.dismiss()
            })
        }

        val customSelected = selected !in QUICK_TIMEFRAMES.map { it.storedMinutes }
        val customLabel = if (customSelected) "ÖZEL  •  ${ScanTimeframe.displayLabel(selected)}" else "ÖZEL  ›"
        panel.addView(scanTimeRow(customLabel, customSelected) {
            scanTimePopup?.dismiss()
            showCustomScanTimeDialog()
        })

        val popup = PopupWindow(
            panel,
            scanTimeButton.width.coerceAtLeast(dp(240)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(12).toFloat()
            setOnDismissListener {
                scanTimeArrow.text = "˅"
                scanTimePopup = null
            }
        }
        scanTimeArrow.text = "˄"
        scanTimePopup = popup
        popup.showAsDropDown(scanTimeButton, 0, dp(4))
    }

    private fun scanTimeRow(label: String, selected: Boolean, click: () -> Unit): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(2)
        }
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        text = if (selected) "$label    ✓" else label
        textSize = 13f
        setTextColor(getColor(if (selected) R.color.white else R.color.text_primary))
        setBackgroundResource(if (selected) R.drawable.bg_chip_selected_blue else R.drawable.bg_chip_outline)
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
    }

    private fun showCustomScanTimeDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 - 239"
            setText(settings.analysisTimeframeMinutes.toString())
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("ÖZEL TARAMA ZAMANI")
            .setMessage("Dakika değerini 1 ile 239 arasında girin. 1 GÜN ayrı seçenektir.")
            .setView(input)
            .setNegativeButton("İPTAL", null)
            .setPositiveButton("UYGULA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString()?.trim()?.toIntOrNull()
                if (value == null || value !in 1..239) {
                    input.error = "1 ile 239 arasında tam sayı girin. 240 DK kullanılmaz."
                    return@setOnClickListener
                }
                applyScanInterval(value)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun applyScanInterval(minutes: Int) {
        if (::scanRepository.isInitialized && scanRepository.snapshot().isActive) {
            BistScanForegroundService.stop(this)
        }
        settings.analysisTimeframeMinutes = ScanTimeframe.normalizeStoredMinutes(minutes)
        val tf = ScanTimeframe.fromStored(settings.analysisTimeframeMinutes)
        // Kullanıcı otomatik taramayı açtığında Tarama Zamanı hem OHLCV periyodu hem de tekrar cadence'idir.
        settings.scanCadenceMinutes = tf.storedMinutes.coerceIn(1, 1440)
        if (settings.autoScanEnabled) AutoScanScheduler.reconcile(this, allowForegroundStart = true)
        AppSession.lastOpportunities = emptyList()
        refreshScanTimeUi()
        refreshAutoScanUi()
        heroSubtitle.text = "${tf.label} seçildi. Eski timeframe sonuçları kullanılmayacak; yeni taramada ${tf.label} OHLCV baştan alınacak."
        findViewById<TextView>(R.id.txtProgress)?.text = "TARAMA BAŞLAMADI"
        findViewById<ProgressBar>(R.id.progress)?.apply { progress = 0; visibility = View.GONE }
    }

    private fun refreshScanTimeUi() {
        if (!::settings.isInitialized || !::activeScanTime.isInitialized) return
        val tf = ScanTimeframe.fromStored(settings.analysisTimeframeMinutes)
        activeScanTime.text = "AKTİF TARAMA: ${tf.label}"
        activeScanTime.contentDescription = if (settings.autoScanEnabled) {
            "Aktif teknik analiz periyodu ve otomatik tarama cadence ${tf.label}"
        } else {
            "Aktif teknik analiz periyodu ${tf.label}; otomatik tarama kapalı"
        }
        val autoLine = if (settings.autoScanEnabled) "\nOtomatik tarama: AÇIK • Her ${tf.label}" else "\nOtomatik tarama: KAPALI"
        findViewById<TextView>(R.id.txtActiveTimeframeDetails)?.text =
            "BIST hisseleri ${tf.label} verileri üzerinden taranacak\n" +
            "OHLCV: ${tf.label}   •   Teknik analiz: ${tf.label}\n" +
            "Trend: ${tf.label}   •   Momentum: ${tf.label}\n" +
            "Hacim: ${tf.label}   •   Kırılım: ${tf.label}" + autoLine
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setupScanBottomNav() {
        findViewById<TextView>(R.id.navViop)?.apply {
            text = "⌕\nBIST Tarama"
            setTextColor(getColor(R.color.blue))
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_nav_active_glow)
            setOnClickListener { }
        }
        findViewById<TextView>(R.id.navFav)?.apply {
            text = "◎\nFırsat Kontrolü"
            setOnClickListener { startActivity(Intent(this@BistScanActivity, OpportunityActivity::class.java)) }
        }
    }

    private fun selectQuickMode(mode: QuickMode) {
        quickMode = mode
        val pairs = listOf(
            quickPopular to QuickMode.POPULAR,
            quickFavorites to QuickMode.FAVORITES,
            quickCustom to QuickMode.CUSTOM
        )
        pairs.forEach { (view, itemMode) ->
            view.setBackgroundResource(if (mode == itemMode) R.drawable.bg_chip_selected_blue else R.drawable.bg_chip_outline)
            view.setTextColor(getColor(if (mode == itemMode) R.color.white else R.color.text_primary))
        }
        heroSubtitle.text = when (mode) {
            QuickMode.POPULAR -> "Tüm doğrulanmış BIST sonuçları sıralanır."
            QuickMode.FAVORITES -> "Tarama tamamlandığında yalnız favorileriniz gösterilir."
            QuickMode.CUSTOM -> "Arama metni sembol, şirket ve mevcut analiz etiketlerinde uygulanır."
        }
    }

    private fun focusSearch() {
        search.requestFocus()
        search.post {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private suspend fun filterResults(items: List<Opportunity>): List<Opportunity> {
        val query = search.text?.toString().orEmpty().trim().uppercase(Locale("tr", "TR"))
        val queryFiltered = if (query.isBlank()) items else items.filter { x ->
            listOf(
                x.symbol,
                x.companyName.orEmpty(),
                x.direction,
                x.technicalLabel,
                x.volumeLabel,
                x.kapLabel,
                x.setupType,
                x.marketRegime,
                x.mtfConsensusLabel
            ).any { it.uppercase(Locale("tr", "TR")).contains(query) }
        }
        if (quickMode != QuickMode.FAVORITES) return queryFiltered
        val favoriteSymbols = FavoriteRepository.get(this).getAll()
            .filter { it.market.equals("BIST", true) }
            .map { it.symbol.uppercase(Locale.ROOT) }
            .toSet()
        return queryFiltered.filter { it.symbol.uppercase(Locale.ROOT) in favoriteSymbols }
    }

    private fun resultModeSummary(allResults: Int, visibleResults: Int): String = when (quickMode) {
        QuickMode.POPULAR -> "$allResults analiz sonucu üretildi."
        QuickMode.FAVORITES -> "$allResults analiz içinden $visibleResults favori sonucu gösteriliyor."
        QuickMode.CUSTOM -> "$allResults analiz içinden $visibleResults arama/kriter sonucu gösteriliyor."
    }

    private fun showHowItWorks() {
        AlertDialog.Builder(this)
            .setTitle("BIST Tarama nasıl çalışır?")
            .setMessage(
                "1. Veri sağlayıcısı ve gerçek sembol evreni doğrulanır.\n\n" +
                    "2. Tarama Zamanı seçilen gerçek OHLCV periyodudur. Otomatik Tarama açılırsa aynı seçim tekrar cadence'i olur: 5 DK = 5 DK OHLCV + her 5 DK yeni tarama; 1 GÜN = gerçek 1d OHLCV + yaklaşık 24 saatte bir arka plan taraması.\n\n" +
                    "3. Quote + OHLCV verileri teknik analiz, MTF, piyasa rejimi ve ensemble katmanlarından geçirilir.\n\n" +
                    "4. İlerleme yalnız gerçek işlenen sembol sayısından hesaplanır.\n\n" +
                    "5. Provider/veri doğrulanamazsa sahte sonuç üretilmez."
            )
            .setPositiveButton("Kapat", null)
            .show()
    }

    private fun toggleDetails() {
        val show = detailPanel.visibility != View.VISIBLE
        detailPanel.visibility = if (show) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.btnStatusDetail).text = if (show) "Detay  ˄" else "Detay  ˅"
    }


    private fun refreshMarketSummary() {
        lifecycleScope.launch {
            val stocks = withContext(Dispatchers.IO) {
                BistIndexDataService(this@BistScanActivity).loadMany(listOf("XU100", "XU030", "XUTUM"))
            }
            bindMarketIndex(stocks["XU100"], R.id.scanIndex100Value, R.id.scanIndex100Change, R.id.scanIndex100Chart)
            bindMarketIndex(stocks["XU030"], R.id.scanIndex30Value, R.id.scanIndex30Change, R.id.scanIndex30Chart)
            bindMarketIndex(stocks["XUTUM"], R.id.scanIndexAllValue, R.id.scanIndexAllChange, R.id.scanIndexAllChart)
            bindMarketVolume(stocks["XUTUM"])
        }
    }

    private fun bindMarketIndex(stock: Stock?, valueId: Int, changeId: Int, chartId: Int) {
        val value = findViewById<TextView>(valueId)
        val change = findViewById<TextView>(changeId)
        val chart = findViewById<StockSparklineView>(chartId)
        val price = stock?.quotePrice ?: stock?.candles?.lastOrNull()?.close
        val previous = stock?.previousClose ?: stock?.candles?.takeIf { it.size >= 2 }?.get(it.lastIndex - 1)?.close
        val pct = if (price != null && previous != null && price.isFinite() && previous.isFinite() && previous > 0.0) {
            ((price / previous) - 1.0) * 100.0
        } else null
        if (stock == null || price == null || !price.isFinite() || price <= 0.0) {
            value.text = "—"
            change.text = "Veri yok"
            change.setTextColor(getColor(R.color.text_secondary))
            chart.setCandles(emptyList(), null)
            return
        }
        value.text = if (price >= 1000.0) "%,.2f".format(Locale.getDefault(), price) else "%.2f".format(Locale.getDefault(), price)
        val status = MarketDataQuality.uiStatus(stock.marketDataMetadata)
        change.text = listOfNotNull(pct?.let { "%+.2f%%".format(Locale.getDefault(), it) }, status.takeIf { it.isNotBlank() }).joinToString(" • ")
        change.setTextColor(when {
            stock.marketDataMetadata?.isOffline == true -> getColor(R.color.text_secondary)
            pct == null -> getColor(R.color.text_secondary)
            pct > 0.0 -> getColor(R.color.green)
            pct < 0.0 -> getColor(R.color.red)
            else -> getColor(R.color.text_secondary)
        })
        chart.setCandles(stock.candles, pct)
    }

    private fun bindMarketVolume(stock: Stock?) {
        val value = findViewById<TextView>(R.id.scanMarketVolumeValue)
        val status = findViewById<TextView>(R.id.scanMarketVolumeStatus)
        val latest = stock?.candles?.lastOrNull()
        val volume = latest?.volume?.takeIf { it.isFinite() && it > 0.0 }
        if (volume == null) {
            value.text = "—"
            status.text = "XUTUM 5 DK mum hacmi alınamadı"
            status.setTextColor(getColor(R.color.text_secondary))
            return
        }
        value.text = formatCompactVolume(volume)
        status.text = "Provider XUTUM • son kapanmış 5 DK mum"
        status.setTextColor(getColor(R.color.text_secondary))
    }

    private fun formatCompactVolume(value: Double): String = when {
        value >= 1_000_000_000.0 -> "%.1f Mr".format(Locale.getDefault(), value / 1_000_000_000.0)
        value >= 1_000_000.0 -> "%.1f Mn".format(Locale.getDefault(), value / 1_000_000.0)
        value >= 1_000.0 -> "%.1f B".format(Locale.getDefault(), value / 1_000.0)
        else -> "%.0f".format(Locale.getDefault(), value)
    }

    private fun productionBackendConfigured(): Boolean =
        ProviderReadinessService.isValidHttps(settings.baseUrl) && settings.apiKey.isNotBlank()

    private fun fallbackAllowed(): Boolean =
        ProviderFallbackPolicy.allowed(settings.experimentalProvidersEnabled, settings.yahooFallbackEnabled)

    private fun refreshAutoScanUi() {
        if (!::settings.isInitialized || !::autoScanSwitch.isInitialized || !::autoScanStatus.isInitialized) return
        val tf = ScanTimeframe.fromStored(settings.analysisTimeframeMinutes)
        suppressAutoScanListener = true
        autoScanSwitch.isChecked = settings.autoScanEnabled
        suppressAutoScanListener = false
        autoScanStatus.text = when {
            !settings.autoScanEnabled -> "Kapalı • manuel tarama"
            !productionBackendConfigured() && fallbackAllowed() -> "Açık • ${tf.label} • Yahoo YEDEK/GECİKMELİ"
            !productionBackendConfigured() -> "Açık • ${tf.label} • veri sağlayıcı hazır değil"
            tf.storedMinutes < AutoScanScheduler.WORK_MANAGER_MINUTES -> "Açık • ${tf.label} OHLCV • her ${tf.label} • foreground servis"
            tf.isDaily -> "Açık • gerçek 1 GÜN OHLCV • yaklaşık 24 saatte bir"
            else -> "Açık • ${tf.label} OHLCV • her ${tf.label} • WorkManager"
        }
    }

    private fun providerStatusLabel(code: ProviderFailureCode, timeframe: ScanTimeframe): String = when (code) {
        ProviderFailureCode.BACKEND_URL_MISSING, ProviderFailureCode.API_KEY_MISSING, ProviderFailureCode.INVALID_HTTPS -> "VERİ SERVİSİ YAPILANDIRILMAMIŞ"
        ProviderFailureCode.AUTH_ERROR -> "KİMLİK DOĞRULAMA HATASI"
        ProviderFailureCode.RATE_LIMIT -> "İSTEK SINIRI"
        ProviderFailureCode.NETWORK_TIMEOUT, ProviderFailureCode.NETWORK_ERROR, ProviderFailureCode.DNS_ERROR, ProviderFailureCode.TLS_ERROR, ProviderFailureCode.SERVER_ERROR -> "VERİ SERVİSİNE ULAŞILAMIYOR"
        ProviderFailureCode.EMPTY_DATA, ProviderFailureCode.BIST_HISTORY_ERROR, ProviderFailureCode.STALE_DATA -> "${timeframe.label} VERİ SERVİSİ HAZIR DEĞİL"
        else -> "VERİ ALINAMADI"
    }

    private fun providerUserMessage(code: ProviderFailureCode, timeframe: ScanTimeframe, detail: String?): String = when (code) {
        ProviderFailureCode.BACKEND_URL_MISSING, ProviderFailureCode.API_KEY_MISSING, ProviderFailureCode.INVALID_HTTPS -> "${timeframe.label} taraması için Production Backend bağlantısı gerekli."
        ProviderFailureCode.AUTH_ERROR -> "Production Backend kimlik doğrulaması başarısız. API anahtarını kontrol edin."
        ProviderFailureCode.RATE_LIMIT -> "${timeframe.label} verisi için istek sınırı aşıldı. Tarama sahte sonuç üretmeden durduruldu."
        ProviderFailureCode.EMPTY_DATA, ProviderFailureCode.BIST_HISTORY_ERROR, ProviderFailureCode.STALE_DATA -> "${timeframe.label} periyodu seçildi ancak gerçek OHLCV hazır değil. ${detail.orEmpty()}"
        else -> "${timeframe.label} veri servisine ulaşılamadı. ${detail.orEmpty()}"
    }

    private fun refreshSourceLabel() {
        source.text = when {
            productionBackendConfigured() -> "Kaynak: Production Backend • gerçek BIST OHLCV • TradingView veri kaynağı değildir"
            fallbackAllowed() -> "Kaynak: Yahoo Finance • YEDEK/GECİKMELİ • seçili timeframe OHLCV • doğrulanmış AL/SAT sinyali değildir"
            else -> "Kaynak: veri sağlayıcı hazır değil • Yahoo fallback kapalı"
        }
    }

    private fun defaultScanButtonLabel(): String {
        val tf = if (::settings.isInitialized) ScanTimeframe.fromStored(settings.analysisTimeframeMinutes) else ScanTimeframe.minute(5, false)
        return when {
            productionBackendConfigured() -> "▶  TARAMAYI BAŞLAT  ›"
            fallbackAllowed() -> "▶  YEDEK/GECİKMELİ ${tf.label} ANALİZİ BAŞLAT  ›"
            else -> "▶  VERİ SAĞLAYICIYI YAPILANDIR  ›"
        }
    }

    private fun initialHeroMessage(): String {
        if (!::settings.isInitialized) return "Kriterlerinizi seçip taramayı başlatın."
        val tf = ScanTimeframe.fromStored(settings.analysisTimeframeMinutes)
        return when {
            productionBackendConfigured() -> "${tf.label} gerçek OHLCV ile manuel tarama hazır. Otomatik tarama ${if (settings.autoScanEnabled) "açık" else "kapalı"}. TradingView piyasa veri kaynağı değildir."
            fallbackAllowed() -> "Yahoo Finance ${tf.label} yedek/gecikmeli teknik analiz hazır • doğrulanmış AL/SAT sinyali üretilmez."
            else -> "Production Backend hazır değil ve Yahoo fallback kapalı. Ayarlar bölümünden veri sağlayıcı yapılandırın."
        }
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized && ::source.isInitialized) {
            refreshSourceLabel()
            refreshScanTimeUi()
            refreshAutoScanUi()
            if (settings.autoScanEnabled) {
                AutoScanScheduler.reconcile(this, allowForegroundStart = true)
            }
            if (::scanRepository.isInitialized) {
                val current = scanRepository.snapshot()
                renderManualScanSession(current)
                if (current.status == ManualScanStatus.IDLE) heroSubtitle.text = initialHeroMessage()
                if (current.status in setOf(ManualScanStatus.COMPLETED, ManualScanStatus.PARTIAL)) handleCompletedSession(current)
            }
        }
    }

    companion object {
        private val QUICK_TIMEFRAMES = ScanTimeframe.quick()
    }

    override fun onDestroy() {
        scanTimePopup?.dismiss()
        scanTimePopup = null
        super.onDestroy()
    }
}
