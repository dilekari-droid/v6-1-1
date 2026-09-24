package tr.borsatakip.v5.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.ViopScanner
import tr.borsatakip.v5.analysis.ViopSignalPolicy
import tr.borsatakip.v5.scan.ScanOrchestrator
import tr.borsatakip.v5.analysis.ViopUnderlyingScanner
import tr.borsatakip.v5.data.BackendProvider
import tr.borsatakip.v5.data.BistIndexDataService
import tr.borsatakip.v5.data.MarketDataQuality
import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.AlertEventStore
import tr.borsatakip.v5.data.ProviderState
import tr.borsatakip.v5.data.ViopContractSelector
import tr.borsatakip.v5.data.ViopWatchlistStore
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopOpportunity
import tr.borsatakip.v5.model.ViopQuote
import tr.borsatakip.v5.model.ViopScanProgress
import tr.borsatakip.v5.model.ViopScanResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViopActivity : BaseActivity() {
    private lateinit var backend: BackendProvider
    private lateinit var scanner: ScanOrchestrator
    private lateinit var underlyingScanner: ViopUnderlyingScanner
    private lateinit var readiness: ProviderReadinessService
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var providerStatus: TextView
    private lateinit var scanButton: Button
    private var previewContract: ViopContract? = null
    private var previewQuote: ViopQuote? = null
    private var providerAutoTestInFlight = false
    private var lastProviderAutoTestAt = 0L
    private var underlyingScanInFlight = false
    private var underlyingScanCompleted = false
    private var productionItems: List<ViopOpportunity> = emptyList()
    private var underlyingItems: List<ViopUnderlyingScanner.Candidate> = emptyList()
    private var showingUnderlying = false
    private var uiQuery = ""
    private var uiCategory = ViopDashboardUiState.Category.ALL
    private var uiDirection = ViopDashboardUiState.Direction.ALL
    private var uiSort = ViopDashboardUiState.Sort.RANKING
    private var advancedFilterExpanded = false
    private val scanGuard = ViopScanGenerationGuard()
    private var scanJob: Job? = null
    private var lastProductionResult: ViopScanResult? = null
    private var lastUnderlyingCompletedAt: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viop)
        setupBottomNav()
        backend = BackendProvider(this)
        scanner = ScanOrchestrator(this)
        underlyingScanner = ViopUnderlyingScanner(ProviderRouter(this))
        readiness = ProviderReadinessService(this)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        providerStatus = findViewById(R.id.providerStatus)
        scanButton = findViewById(R.id.refresh)
        list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        findViewById<TextView>(R.id.openSettings).setOnClickListener { openViopSettings() }
        findViewById<TextView>(R.id.openFavorites).setOnClickListener { startActivity(Intent(this, FavoritesActivity::class.java)) }
        findViewById<TextView>(R.id.openNotifications).setOnClickListener { startActivity(Intent(this, NotificationsActivity::class.java)) }
        restoreDashboardState(savedInstanceState)
        bindDashboardFilters()
        findViewById<TextView>(R.id.navAnalysis)?.setOnClickListener { startActivity(Intent(this, TechnicalAnalysisActivity::class.java)) }
        findViewById<TextView>(R.id.navNews)?.setOnClickListener { startActivity(Intent(this, NewsActivity::class.java)) }
        findViewById<TextView>(R.id.modeProduction).setOnClickListener {
            if (readiness.localConfigState().state == ProviderState.PROVIDER_READY) runOpportunityScan()
            else status.text = "Gerçek VİOP taraması için doğrulanmış production veri servisi gerekir."
        }
        findViewById<TextView>(R.id.modeUnderlying).setOnClickListener { runUnderlyingScan("Dayanak ön tarama kullanıcı tarafından seçildi") }
        findViewById<Button>(R.id.openContracts).setOnClickListener { startActivity(Intent(this, ViopContractsActivity::class.java)) }
        findViewById<Button>(R.id.openExpiries).setOnClickListener { startActivity(Intent(this, ViopContractsActivity::class.java)) }
        findViewById<Button>(R.id.openAnalysis).setOnClickListener {
            previewContract?.let {
                AppSession.selectedViopOpportunity = null
                AppSession.selectedViopUnderlyingOpportunity = null
                AppSession.selectedViopContract = it
                startActivity(Intent(this, ViopDetailActivity::class.java))
            } ?: run { status.text = "Analiz için önce gerçek bir VİOP sözleşmesi yüklenmelidir." }
        }
        findViewById<Button>(R.id.openSignals).setOnClickListener { startOrValidateScan() }
        scanButton.setOnClickListener { startOrValidateScan() }
        refreshProviderState()
        recoverProviderAndLoad()
    }

    override fun onResume() {
        super.onResume()
        refreshProviderState()
        refreshUnderlyingMarketCards()
        if (::readiness.isInitialized) recoverProviderAndLoad()
    }


    private fun refreshUnderlyingMarketCards() {
        lifecycleScope.launch {
            val stocks = withContext(Dispatchers.IO) {
                BistIndexDataService(this@ViopActivity).loadMany(listOf("XU030", "XU100"))
            }
            bindUnderlyingIndex(stocks["XU030"], R.id.viopUnderlyingBist30Value, R.id.viopUnderlyingBist30Change, R.id.viopUnderlyingBist30Chart)
            bindUnderlyingIndex(stocks["XU100"], R.id.viopUnderlyingBist100Value, R.id.viopUnderlyingBist100Change, R.id.viopUnderlyingBist100Chart)
            // DOLAR/TL ve ALTIN için bu kaynak sürümünde doğrulanmış provider capability yok.
            // Kartlar veri uydurmak yerine açıkça kullanılabilir veri olmadığını gösterir.
            findViewById<TextView>(R.id.viopUnderlyingUsdTryValue).text = "—"
            findViewById<TextView>(R.id.viopUnderlyingUsdTryStatus).text = "Doğrulanmış kaynak yok"
            findViewById<TextView>(R.id.viopUnderlyingGoldValue).text = "—"
            findViewById<TextView>(R.id.viopUnderlyingGoldStatus).text = "Doğrulanmış kaynak yok"
        }
    }

    private fun bindUnderlyingIndex(stock: Stock?, valueId: Int, changeId: Int, chartId: Int) {
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
        val quality = MarketDataQuality.uiStatus(stock.marketDataMetadata)
        change.text = listOfNotNull(pct?.let { "%+.2f%%".format(Locale.getDefault(), it) }, quality.takeIf { it.isNotBlank() }).joinToString(" • ")
        change.setTextColor(when {
            pct == null -> getColor(R.color.text_secondary)
            pct > 0.0 -> getColor(R.color.green)
            pct < 0.0 -> getColor(R.color.red)
            else -> getColor(R.color.text_secondary)
        })
        chart.setCandles(stock.candles, pct)
    }

    private fun openViopSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_RETURN_TO_VIOP, true))
    }

    private fun recoverProviderAndLoad() {
        if (!::readiness.isInitialized || providerAutoTestInFlight) return
        when (readiness.localConfigState().state) {
            ProviderState.PROVIDER_READY -> if (previewQuote == null) loadDashboardPreview()
            ProviderState.PROVIDER_CONFIGURED, ProviderState.PROVIDER_STALE_READY, ProviderState.PROVIDER_ERROR -> {
                val now = System.currentTimeMillis()
                if (now - lastProviderAutoTestAt < AUTO_TEST_COOLDOWN_MS) return
                lastProviderAutoTestAt = now
                providerAutoTestInFlight = true
                status.text = "VİOP provider otomatik doğrulanıyor: HTTPS → Health → Authentication → Contracts → Quote → History"
                lifecycleScope.launch {
                    val result = readiness.testAll()
                    providerAutoTestInFlight = false
                    refreshProviderState()
                    if (result.state == ProviderState.PROVIDER_READY) loadDashboardPreview()
                    else status.text = "${result.failureCode} • ${result.message}"
                }
            }
            ProviderState.PROVIDER_NOT_CONFIGURED -> {
                clearPreview()
                if (!underlyingScanInFlight && !underlyingScanCompleted) {
                    runUnderlyingScan("Production VİOP backend bağlı değil")
                }
            }
            ProviderState.PROVIDER_TESTING -> Unit
        }
    }

    private fun startOrValidateScan() {
        when (val s = readiness.localConfigState()) {
            else -> when (s.state) {
                ProviderState.PROVIDER_NOT_CONFIGURED -> runUnderlyingScan("Production VİOP backend bağlı değil")
                ProviderState.PROVIDER_READY -> runOpportunityScan()
                ProviderState.PROVIDER_TESTING -> status.text = "Provider bağlantı testi devam ediyor..."
                ProviderState.PROVIDER_CONFIGURED, ProviderState.PROVIDER_STALE_READY, ProviderState.PROVIDER_ERROR -> testThenScan()
            }
        }
    }

    private fun testThenScan() {
        scanButton.isEnabled = false
        status.text = "Provider doğrulanıyor: HTTPS → Health → Authentication → VİOP Contracts → Quote → History"
        lifecycleScope.launch {
            val r = readiness.testAll()
            scanButton.isEnabled = true
            refreshProviderState()
            if (r.state == ProviderState.PROVIDER_READY) {
                loadDashboardPreview()
                runOpportunityScan()
            } else {
                clearPreview()
                showUnavailableSummary("VİOP provider doğrulanamadı")
                status.text = "VİOP provider doğrulanamadı • ${r.failureCode} • ${r.message}"
            }
        }
    }

    private fun refreshProviderState() {
        val s = readiness.localConfigState()
        val title = findViewById<TextView>(R.id.providerTitle)
        val dot = findViewById<TextView>(R.id.providerDot)
        val previewMetadata = previewQuote?.marketDataMetadata
        val live = s.state == ProviderState.PROVIDER_READY && previewMetadata != null &&
            previewMetadata.state == tr.borsatakip.v5.model.MarketDataState.LIVE &&
            !previewMetadata.isFallback && !previewMetadata.isOffline

        if (live) {
            title.text = "VİOP VERİ SERVİSİ BAĞLI"
            title.setTextColor(Color.parseColor("#56F1C1"))
            dot.setTextColor(Color.parseColor("#00E676"))
            providerStatus.text = MarketDataQuality.providerStatus(previewMetadata).compact() + " • son başarılı ${timeOf(previewMetadata?.lastSuccessfulUpdateAt ?: 0L)}"
        } else {
            title.text = "VİOP VERİ SERVİSİ BAĞLI DEĞİL"
            title.setTextColor(Color.parseColor("#FF6B72"))
            dot.setTextColor(Color.parseColor("#FF3B4D"))
            providerStatus.text = when (s.state) {
                ProviderState.PROVIDER_NOT_CONFIGURED -> "Production backend yapılandırılmamış • DAYANAK ÖN TARAMA kullanılabilir"
                ProviderState.PROVIDER_TESTING -> "Bağlantı doğrulanıyor…"
                ProviderState.PROVIDER_STALE_READY -> "Veri yeniden doğrulanmalı • kesin VİOP sinyali yayımlanmaz"
                else -> "${s.failureCode} • ${s.message}"
            }
        }

        findViewById<TextView>(R.id.modeProduction).apply {
            text = if (live) "GERÇEK VİOP TARAMASI\nVadeli kontratlar • Opsiyon yayını yok" else "GERÇEK VİOP TARAMASI\nVeri servisi gerekli"
            setTextColor(Color.parseColor(if (!showingUnderlying && live) "#FFFFFF" else "#8FA3B8"))
            alpha = if (!showingUnderlying && live) 1f else 0.78f
        }
        findViewById<TextView>(R.id.modeUnderlying).apply {
            text = "DAYANAK ÖN TARAMA\nTeknik eğilim • VİOP sinyali değildir"
            setTextColor(Color.parseColor(if (showingUnderlying) "#FFFFFF" else "#8FA3B8"))
            alpha = if (showingUnderlying) 1f else 0.78f
        }
        findViewById<TextView>(R.id.watchlistBadge).text = "★ İzleme ${ViopWatchlistStore(this).symbols().size}"

        scanButton.text = when {
            showingUnderlying -> if (underlyingScanCompleted) "DAYANAK TARAMASINI YENİLE" else "DAYANAK ÖN TARAMAYI BAŞLAT"
            s.state == ProviderState.PROVIDER_READY -> "VİOP TARAMASINI YENİLE"
            s.state == ProviderState.PROVIDER_TESTING -> "PROVIDER TEST EDİLİYOR"
            s.state == ProviderState.PROVIDER_CONFIGURED || s.state == ProviderState.PROVIDER_STALE_READY -> "BAĞLANTIYI DOĞRULA"
            else -> "DAYANAK ÖN TARAMAYI BAŞLAT"
        }
        scanButton.isEnabled = s.state != ProviderState.PROVIDER_TESTING
    }

    private fun loadDashboardPreview() {
        lifecycleScope.launch {
            status.text = "Gerçek VİOP sözleşme ve yakın vade verisi yükleniyor..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val contracts = backend.loadViop().getOrThrow()
                    val c = ViopContractSelector.candidates(contracts, allowWatch = false).firstOrNull()
                        ?: error("NO_CONTRACT: Doğrulanmış aktif VİOP sözleşmesi bulunamadı.")
                    val q = backend.loadViopQuote(c.symbol).getOrThrow()
                    val h = backend.loadViopHistory(c.symbol).getOrThrow()
                    require(h.isNotEmpty()) { "HISTORY_ERROR: VİOP history boş." }
                    Triple(c, q, h)
                }
            }
            result.onSuccess { (c, q, h) ->
                previewContract = c
                previewQuote = q
                AppSession.selectedViopOpportunity = null
                AppSession.selectedViopUnderlyingOpportunity = null
                AppSession.selectedViopContract = c
                renderPreview(c, q, h)
                refreshProviderState()
                status.text = "Gerçek VİOP verisi yüklendi • ${c.symbol} • ${q.source}"
            }.onFailure {
                previewContract = null
                previewQuote = null
                clearPreview()
                refreshProviderState()
                status.text = friendlyViopMessage(it.message)
            }
        }
    }

    private fun renderPreview(c: ViopContract, q: ViopQuote, candles: List<Candle>) {
        val last = candles.lastOrNull()
        findViewById<TextView>(R.id.heroTitle).text = c.underlying.takeIf { it.isNotBlank() && it != "-" } ?: "YAKIN VADE"
        findViewById<TextView>(R.id.heroContract).text = "${c.symbol} • ${c.expiry}"
        findViewById<TextView>(R.id.heroExpiry).text = c.lastTradingAt?.let { "Son işlem ${shortDate(it)}" } ?: "Vade ${c.expiry}"
        findViewById<TextView>(R.id.heroPrice).text = formatPrice(q.price)
        findViewById<TextView>(R.id.heroChange).apply {
            text = q.dailyChangePct?.let { "%+.2f%%".format(it) } ?: "Değişim verisi yok"
            setTextColor(when { q.dailyChangePct == null -> Color.parseColor("#8FA3B8"); q.dailyChangePct!! >= 0 -> Color.parseColor("#00E676"); else -> Color.parseColor("#FF3B4D") })
        }
        findViewById<ViopSparklineView>(R.id.heroSparkline).setCandles(candles)
        findViewById<TextView>(R.id.metricOpen).text = "Açılış\n${last?.open?.let(::formatPrice) ?: "—"}"
        findViewById<TextView>(R.id.metricHigh).text = "En Yüksek\n${last?.high?.let(::formatPrice) ?: "—"}"
        findViewById<TextView>(R.id.metricLow).text = "En Düşük\n${last?.low?.let(::formatPrice) ?: "—"}"
        findViewById<TextView>(R.id.metricBid).text = "Alış\n${q.bid?.let(::formatPrice) ?: "—"}"
        findViewById<TextView>(R.id.metricAsk).text = "Satış\n${q.ask?.let(::formatPrice) ?: "—"}"
        findViewById<TextView>(R.id.metricVolume).text = "Hacim  ${q.volume?.let(::formatCompact) ?: c.volume?.let(::formatCompact) ?: "—"}"
        findViewById<TextView>(R.id.metricOi).text = "Açık Poz.  ${q.openInterest ?: c.openInterest ?: "—"}"
        findViewById<TextView>(R.id.metricBasis).text = "Basis  —"
        findViewById<TextView>(R.id.providerLastUpdate).text = timeOf(q.exchangeTimestamp)
        val age = (System.currentTimeMillis() - q.exchangeTimestamp).coerceAtLeast(0L)
        findViewById<TextView>(R.id.providerDataAge).text = if (age < 1000) "$age ms" else "%.1f sn".format(age / 1000.0)
        findViewById<TextView>(R.id.providerLatency).text = q.delaySeconds?.let { "$it sn" } ?: "—"
        findViewById<TextView>(R.id.marketContracts).text = "Kontrat\n${c.symbol}"
        findViewById<TextView>(R.id.marketTrend).text = "Veri\n${if (q.realtime) "Canlı" else "Gecikmeli"}"
    }

    private fun clearPreview() {
        findViewById<TextView>(R.id.heroTitle).text = "YAKIN VADE"
        findViewById<TextView>(R.id.heroContract).text = "Canlı sözleşme bekleniyor"
        findViewById<TextView>(R.id.heroExpiry).text = "Vade —"
        findViewById<TextView>(R.id.heroPrice).text = "—"
        findViewById<TextView>(R.id.heroChange).text = "Gerçek fiyat verisi bekleniyor"
        findViewById<ViopSparklineView>(R.id.heroSparkline).setCandles(emptyList())
        listOf(R.id.metricOpen to "Açılış", R.id.metricHigh to "En Yüksek", R.id.metricLow to "En Düşük", R.id.metricBid to "Alış", R.id.metricAsk to "Satış").forEach { (id, label) -> findViewById<TextView>(id).text = "$label\n—" }
        findViewById<TextView>(R.id.metricVolume).text = "Hacim  —"
        findViewById<TextView>(R.id.metricOi).text = "Açık Poz.  —"
        findViewById<TextView>(R.id.metricBasis).text = "Basis  —"
        findViewById<TextView>(R.id.providerLastUpdate).text = "—"
        findViewById<TextView>(R.id.providerDataAge).text = "—"
        findViewById<TextView>(R.id.providerLatency).text = "—"
        if (lastProductionResult == null && !underlyingScanCompleted) {
            showUnavailableSummary("Veri bekleniyor")
        }
    }

    private fun showUnavailableSummary(reason: String) {
        findViewById<TextView>(R.id.summaryLong).text = "VİOP LONG\n—"
        findViewById<TextView>(R.id.summaryShort).text = "VİOP SHORT\n—"
        findViewById<TextView>(R.id.summaryWatch).text = "İZLE\n—"
        findViewById<TextView>(R.id.summaryTotal).text = "TARANAN\n—"
        findViewById<Button>(R.id.filterLong).text = "LONG"
        findViewById<Button>(R.id.filterShort).text = "SHORT"
        findViewById<Button>(R.id.filterWatch).text = "İZLE"
        findViewById<TextView>(R.id.signalSummary).text = "$reason • Veri yok = 0 değildir"
        findViewById<TextView>(R.id.signalLastUpdate).text = "Veri — • Alım — • Tarama —"
        findViewById<TextView>(R.id.marketSignals).text = "Yayın\n—"
        findViewById<TextView>(R.id.marketTrend).text = "Mod\nBEKLİYOR"
    }

    private fun runOpportunityScan() {
        if (readiness.localConfigState().state != ProviderState.PROVIDER_READY) {
            status.text = "Gerçek VİOP taraması engellendi • provider READY değil."
            return
        }
        scanJob?.cancel()
        val generation = scanGuard.next()
        scanButton.isEnabled = false
        showUnavailableSummary("VİOP analizi sürüyor")
        status.text = "GERÇEK VİOP TARAMASI • production kontrat evreninin bütün sayfaları alınıyor…"
        scanJob = lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    scanner.runViop { p -> runOnUiThread { if (scanGuard.accepts(generation)) status.text = progressText(p) } }
                }
                if (!scanGuard.accepts(generation)) return@launch
                lastProductionResult = r
                AppSession.lastViopOpportunities = r.opportunities
                AlertEventStore(this@ViopActivity).evaluateViop(r.opportunities)
                renderOpportunities(r.opportunities)
                status.text = buildString {
                    append("${r.status.name} • GERÇEK VİOP TARAMASI tamamlandı")
                    if (r.scanId.isNotBlank()) append(" • scanId ${r.scanId.take(8)}")
                    append("\nProvider toplamı ${r.providerTotal ?: "?"} • Alınan ${r.fetchedCount} • Benzersiz ${r.fetchedUniqueCount} • Aktif futures ${r.activeUniqueFutures}")
                    append(" • Evren ${r.universeCompleteness.name}")
                    append("\nİşlenen ${r.progress.total} • Analiz ${r.progress.analyzed} • Yetersiz ${r.progress.insufficient} • Red ${r.progress.rejected} • Veri yok ${r.progress.noData}")
                    if (r.progress.unsupportedOptions > 0) append(" • Opsiyon kapsam dışı ${r.progress.unsupportedOptions}")
                    append("\nNihai yayın ${r.progress.publishedCount}: LONG ${r.progress.longCount} • SHORT ${r.progress.shortCount} • İZLE ${r.progress.watchCount}")
                    if (r.progress.accountedTotal != r.progress.total) append(" • SAYIM UYUMSUZLUĞU")
                    if (r.universeCompleteness != tr.borsatakip.v5.model.ViopUniverseCompleteness.COMPLETE) append("\nUYARI: Kontrat evreninin eksiksiz olduğu doğrulanamadı.")
                    if (r.opportunities.isEmpty()) append("\nKalite kapılarını geçen kayıt yok; sahte sinyal üretilmedi.")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (scanGuard.accepts(generation)) status.text = friendlyViopMessage(t.message)
            } finally {
                if (scanGuard.accepts(generation)) {
                    scanButton.isEnabled = true
                    refreshProviderState()
                }
            }
        }
    }

    private fun friendlyViopMessage(raw: String?): String {
        val msg = raw.orEmpty()
        return when {
            msg.contains("VIOP_UPSTREAM_NOT_CONFIGURED", ignoreCase = true) ||
                (msg.contains("HTTP 503") && msg.contains("VİOP", ignoreCase = true)) ->
                "VİOP veri sağlayıcısı henüz yapılandırılmadı • BIST özellikleri çalışmaya devam eder • sahte VİOP verisi üretilmez"
            msg.contains("HTTP 401") || msg.contains("AUTH", ignoreCase = true) ->
                "VİOP kimlik doğrulaması başarısız • Ayarlar > API Anahtarları bölümünü kontrol edin"
            else -> "VİOP verisi kullanılamıyor • ${raw ?: "Bilinmeyen veri hatası"}"
        }
    }

    private fun runUnderlyingScan(reason: String) {
        scanJob?.cancel()
        val generation = scanGuard.next()
        underlyingScanInFlight = true
        scanButton.isEnabled = false
        showUnavailableSummary("Dayanak analizi sürüyor")
        status.text = "$reason • BIST dayanakları teknik olarak taranıyor..."
        scanJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { underlyingScanner.scan() }
                if (!scanGuard.accepts(generation)) return@launch
                underlyingItems = result.items.distinctBy { it.contract.underlying.trim().uppercase(Locale.ROOT) }
                productionItems = emptyList()
                lastProductionResult = null
                lastUnderlyingCompletedAt = System.currentTimeMillis()
                showingUnderlying = true
                applyDashboardFilter()
                status.text = buildString {
                    append("DAYANAK ÖN TARAMA TAMAMLANDI • VİOP fiyatı/sinyali üretilmedi\n")
                    append("Denenen ${result.attempted} • Analiz ${result.resolved} • Veri yok ${result.failed}\n")
                    append("Bunlar dayanak teknik eğilimleridir; gerçek VİOP işlem sinyali değildir.")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (scanGuard.accepts(generation)) status.text = "Dayanak taraması başarısız • ${t.message ?: "Beklenmeyen hata"}"
            } finally {
                if (scanGuard.accepts(generation)) {
                    underlyingScanInFlight = false
                    underlyingScanCompleted = true
                    scanButton.isEnabled = true
                    refreshProviderState()
                }
            }
        }
    }

    private var openingUnderlyingContract = false

    private fun openRealViopContract(candidate: ViopUnderlyingScanner.Candidate) {
        if (openingUnderlyingContract) return
        val local = readiness.localConfigState()
        if (local.state != ProviderState.PROVIDER_READY) {
            openUnderlyingFallback(candidate, when (local.state) {
                ProviderState.PROVIDER_NOT_CONFIGURED -> "VİOP veri servisi yapılandırılmamış • dayanak detayı açıldı."
                else -> "VİOP veri servisi hazır değil • ${local.failureCode} • dayanak detayı açıldı."
            })
            return
        }
        openingUnderlyingContract = true
        status.text = "${candidate.contract.underlying.uppercase()} için gerçek VİOP kontratı çözülüyor..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val underlying = candidate.contract.underlying.trim().uppercase()
                    val contracts = backend.loadViop().getOrThrow()
                    val candidates = ViopContractSelector.candidates(contracts, underlying = underlying, allowWatch = false)
                    var lastError: Throwable? = null
                    for (contract in candidates) {
                        val attempt = runCatching {
                            val quote = backend.loadViopQuote(contract.symbol).getOrThrow()
                            val history = backend.loadViopHistory(contract.symbol).getOrThrow()
                            require(history.size >= ViopScanner.MIN_HISTORY_BARS) { "INSUFFICIENT_HISTORY: ${history.size} mum; minimum ${ViopScanner.MIN_HISTORY_BARS}." }
                            Triple(contract, quote, history)
                        }
                        if (attempt.isSuccess) return@runCatching attempt.getOrThrow()
                        lastError = attempt.exceptionOrNull()
                    }
                    if (candidates.isEmpty()) error("NO_CONTRACT: $underlying için doğrulanmış aktif VİOP kontratı bulunamadı.")
                    throw (lastError ?: IllegalStateException("VİOP quote/history doğrulanamadı."))
                }
            }
            openingUnderlyingContract = false
            result.onSuccess { (contract, quote, history) ->
                AppSession.selectedViopOpportunity = null
                AppSession.selectedViopUnderlyingOpportunity = null
                AppSession.selectedViopContract = contract
                previewContract = contract
                previewQuote = quote
                renderPreview(contract, quote, history)
                status.text = "Gerçek VİOP kontratı doğrulandı • ${contract.symbol}"
                startActivity(Intent(this@ViopActivity, ViopDetailActivity::class.java))
            }.onFailure { t ->
                val msg = t.message.orEmpty()
                val reason = when {
                    msg.contains("NO_CONTRACT") -> "${candidate.contract.underlying.uppercase()} için geçerli VİOP kontratı bulunamadı • dayanak detayı açıldı."
                    msg.contains("QUOTE", true) -> "VİOP kontratı bulundu ancak güncel fiyat alınamadı • dayanak detayı açıldı."
                    msg.contains("HISTORY", true) || msg.contains("INSUFFICIENT_HISTORY", true) -> "Doğrulanmış VİOP geçmişi yok • dayanak detayı açıldı."
                    else -> "VİOP kontrat verisi doğrulanamadı • dayanak detayı açıldı."
                }
                openUnderlyingFallback(candidate, reason)
            }
        }
    }

    private fun openUnderlyingFallback(candidate: ViopUnderlyingScanner.Candidate, message: String) {
        AppSession.selectedViopOpportunity = null
        AppSession.selectedViopUnderlyingOpportunity = candidate.underlying
        AppSession.selectedViopContract = candidate.contract
        status.text = message
        startActivity(Intent(this, ViopDetailActivity::class.java))
    }

    private fun renderOpportunities(items: List<ViopOpportunity>) {
        productionItems = ViopScanner.sortOpportunities(items)
        underlyingItems = emptyList()
        showingUnderlying = false
        applyDashboardFilter()
    }

    private fun bindDashboardFilters() {
        findViewById<EditText>(R.id.viopSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                uiQuery = s?.toString().orEmpty().trim()
                persistDashboardState()
                applyDashboardFilter()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        findViewById<Button>(R.id.filterAll).setOnClickListener {
            uiCategory = ViopDashboardUiState.Category.ALL
            uiDirection = ViopDashboardUiState.Direction.ALL
            updateDashboardFilterColors()
            persistDashboardState()
            applyDashboardFilter()
        }
        listOf(
            R.id.filterIndex to ViopDashboardUiState.Category.INDEX,
            R.id.filterEquity to ViopDashboardUiState.Category.EQUITY,
            R.id.filterFx to ViopDashboardUiState.Category.FX,
            R.id.filterCommodity to ViopDashboardUiState.Category.COMMODITY,
            R.id.filterRate to ViopDashboardUiState.Category.RATE
        ).forEach { (id, category) ->
            findViewById<Button>(id).setOnClickListener {
                uiCategory = category
                updateDashboardFilterColors()
                persistDashboardState()
                applyDashboardFilter()
            }
        }
        findViewById<Button>(R.id.filterLong).setOnClickListener {
            uiDirection = if (uiDirection == ViopDashboardUiState.Direction.LONG) ViopDashboardUiState.Direction.ALL else ViopDashboardUiState.Direction.LONG
            updateDashboardFilterColors()
            persistDashboardState()
            applyDashboardFilter()
        }
        findViewById<Button>(R.id.filterShort).setOnClickListener {
            uiDirection = if (uiDirection == ViopDashboardUiState.Direction.SHORT) ViopDashboardUiState.Direction.ALL else ViopDashboardUiState.Direction.SHORT
            updateDashboardFilterColors()
            persistDashboardState()
            applyDashboardFilter()
        }
        findViewById<Button>(R.id.filterWatch).setOnClickListener {
            uiDirection = if (uiDirection == ViopDashboardUiState.Direction.WATCH) ViopDashboardUiState.Direction.ALL else ViopDashboardUiState.Direction.WATCH
            updateDashboardFilterColors()
            persistDashboardState()
            applyDashboardFilter()
        }
        findViewById<Button>(R.id.detailedFilter).setOnClickListener {
            advancedFilterExpanded = !advancedFilterExpanded
            applyAdvancedFilterVisibility()
            persistDashboardState()
        }
        findViewById<Button>(R.id.sortMode).setOnClickListener {
            uiSort = when (uiSort) {
                ViopDashboardUiState.Sort.RANKING -> ViopDashboardUiState.Sort.SIGNAL
                ViopDashboardUiState.Sort.SIGNAL -> ViopDashboardUiState.Sort.CONFIDENCE
                ViopDashboardUiState.Sort.CONFIDENCE -> ViopDashboardUiState.Sort.RANKING
            }
            updateSortLabel()
            persistDashboardState()
            applyDashboardFilter()
        }
        findViewById<EditText>(R.id.viopSearch).apply {
            if (text.toString() != uiQuery) setText(uiQuery)
            setSelection(text.length)
        }
        applyAdvancedFilterVisibility()
        updateSortLabel()
        updateDashboardFilterColors()
    }

    private fun applyAdvancedFilterVisibility() {
        findViewById<View>(R.id.advancedFilterRow).visibility = if (advancedFilterExpanded) View.VISIBLE else View.GONE
    }

    private fun currentDashboardState() = ViopDashboardUiState(
        query = uiQuery,
        category = uiCategory,
        direction = uiDirection,
        sort = uiSort,
        advancedExpanded = advancedFilterExpanded
    )

    private fun restoreDashboardState(savedInstanceState: Bundle?) {
        val raw = savedInstanceState?.getString(STATE_UI)
            ?: getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString(STATE_UI, null)
        val state = ViopDashboardUiState.decode(raw)
        uiQuery = state.query
        uiCategory = state.category
        uiDirection = state.direction
        uiSort = state.sort
        advancedFilterExpanded = state.advancedExpanded
    }

    private fun persistDashboardState() {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
            .putString(STATE_UI, currentDashboardState().encode())
            .apply()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_UI, currentDashboardState().encode())
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        persistDashboardState()
        super.onStop()
    }

    private fun applyDashboardFilter() {
        if (!::list.isInitialized) return
        val query = uiQuery.lowercase(Locale.getDefault())
        if (showingUnderlying) {
            val source = underlyingItems.distinctBy { it.contract.underlying.trim().uppercase(Locale.ROOT) }
            val filtered = source.asSequence()
                .filter { candidate ->
                    val contract = candidate.contract
                    val searchOk = query.isBlank() || listOf(contract.underlying, contract.expiry, candidate.underlying.companyName.orEmpty())
                        .any { it.lowercase(Locale.getDefault()).contains(query) }
                    val bias = ViopSignalPolicy.analysisBias(candidate.underlying)
                    val directionOk = when (uiDirection) {
                        ViopDashboardUiState.Direction.ALL -> true
                        ViopDashboardUiState.Direction.LONG -> bias == ViopSignalPolicy.AnalysisBias.LONG
                        ViopDashboardUiState.Direction.SHORT -> bias == ViopSignalPolicy.AnalysisBias.SHORT
                        ViopDashboardUiState.Direction.WATCH -> bias == ViopSignalPolicy.AnalysisBias.NEUTRAL
                    }
                    searchOk && categoryMatches(contract.underlying) && directionOk
                }
                .sortedWith(when (uiSort) {
                    ViopDashboardUiState.Sort.RANKING -> compareByDescending<ViopUnderlyingScanner.Candidate> { it.underlying.rankingScore }.thenByDescending { it.underlying.finalSignalScore }
                    ViopDashboardUiState.Sort.SIGNAL -> compareByDescending<ViopUnderlyingScanner.Candidate> { it.underlying.finalSignalScore }.thenByDescending { it.underlying.rankingScore }
                    ViopDashboardUiState.Sort.CONFIDENCE -> compareByDescending<ViopUnderlyingScanner.Candidate> { it.underlying.dataConfidenceScore }.thenByDescending { it.underlying.rankingScore }
                }.thenBy { it.contract.underlying.uppercase(Locale.ROOT) })
                .toList()
            list.adapter = ViopUnderlyingOpportunityAdapter(filtered) { candidate -> openRealViopContract(candidate) }
            updateUnderlyingSummary(source)
            findViewById<TextView>(R.id.emptySignals).visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.emptySignals).text = if (filtered.isEmpty()) "Bu filtrede dayanak eğilimi yok" else ""
        } else {
            val source = productionItems.distinctBy { it.contract.symbol.trim().uppercase(Locale.ROOT) }
            val filtered = source.asSequence()
                .filter { opportunity ->
                    val c = opportunity.contract
                    val searchOk = query.isBlank() || listOf(c.symbol, c.underlying, c.expiry)
                        .any { it.lowercase(Locale.getDefault()).contains(query) }
                    val published = ViopSignalPolicy.publishedDirection(opportunity)
                    val directionOk = when (uiDirection) {
                        ViopDashboardUiState.Direction.ALL -> true
                        ViopDashboardUiState.Direction.LONG -> published == ViopSignalPolicy.PublishedDirection.LONG
                        ViopDashboardUiState.Direction.SHORT -> published == ViopSignalPolicy.PublishedDirection.SHORT
                        ViopDashboardUiState.Direction.WATCH -> published == ViopSignalPolicy.PublishedDirection.WATCH
                    }
                    searchOk && categoryMatches(c.underlying) && directionOk
                }
                .sortedWith(when (uiSort) {
                    ViopDashboardUiState.Sort.RANKING -> compareByDescending<ViopOpportunity> { it.rankingScore }.thenByDescending { it.finalScore }
                    ViopDashboardUiState.Sort.SIGNAL -> compareByDescending<ViopOpportunity> { it.finalScore }.thenByDescending { it.rankingScore }
                    ViopDashboardUiState.Sort.CONFIDENCE -> compareByDescending<ViopOpportunity> { it.dataConfidenceScore }.thenByDescending { it.rankingScore }
                }.thenBy { it.riskScore }.thenBy { it.contract.symbol.uppercase(Locale.ROOT) })
                .toList()
            list.adapter = ViopOpportunityAdapter(filtered) { opportunity ->
                AppSession.selectedViopOpportunity = opportunity
                AppSession.selectedViopUnderlyingOpportunity = null
                AppSession.selectedViopContract = opportunity.contract
                startActivity(Intent(this, ViopDetailActivity::class.java))
            }
            updateProductionSummary(source)
            findViewById<TextView>(R.id.emptySignals).visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.emptySignals).text = if (filtered.isEmpty()) "Bu filtrede yayınlanmış VİOP sonucu yok" else ""
        }
        refreshProviderState()
    }

    private fun updateUnderlyingSummary(source: List<ViopUnderlyingScanner.Candidate>) {
        val longs = source.count { ViopSignalPolicy.analysisBias(it.underlying) == ViopSignalPolicy.AnalysisBias.LONG }
        val shorts = source.count { ViopSignalPolicy.analysisBias(it.underlying) == ViopSignalPolicy.AnalysisBias.SHORT }
        val neutral = source.size - longs - shorts
        val average = source.map { it.underlying.rankingScore.toDouble() }.takeIf { it.isNotEmpty() }?.average()
        findViewById<TextView>(R.id.summaryLong).text = "LONG EĞİLİMİ\n$longs"
        findViewById<TextView>(R.id.summaryShort).text = "SHORT EĞİLİMİ\n$shorts"
        findViewById<TextView>(R.id.summaryWatch).text = "NÖTR\n$neutral"
        findViewById<TextView>(R.id.summaryTotal).text = "TARANAN DAYANAK\n${source.size}"
        findViewById<Button>(R.id.filterLong).text = "LONG ($longs)"
        findViewById<Button>(R.id.filterShort).text = "SHORT ($shorts)"
        findViewById<Button>(R.id.filterWatch).text = "NÖTR ($neutral)"
        findViewById<TextView>(R.id.signalSummary).text = buildString {
            append("${source.size} dayanak • DAYANAK ÖN TARAMA")
            average?.let { append(" • Ortalama sıralama %${it.toInt().coerceIn(0, 100)}") }
        }
        val marketTs = source.maxOfOrNull { it.underlying.dataTimestamp } ?: 0L
        val receivedAt = source.maxOfOrNull { it.underlying.receivedAt } ?: 0L
        findViewById<TextView>(R.id.signalLastUpdate).text = "Veri ${timeOf(marketTs)} • Alım ${timeOf(receivedAt)} • Tarama ${timeOf(lastUnderlyingCompletedAt)}"
        findViewById<TextView>(R.id.marketSignals).text = "Dayanak\n${source.size}"
        findViewById<TextView>(R.id.marketTrend).text = "Mod\nÖN TARAMA"
    }

    private fun updateProductionSummary(source: List<ViopOpportunity>) {
        val longs = source.count { ViopSignalPolicy.publishedDirection(it) == ViopSignalPolicy.PublishedDirection.LONG }
        val shorts = source.count { ViopSignalPolicy.publishedDirection(it) == ViopSignalPolicy.PublishedDirection.SHORT }
        val watch = source.size - longs - shorts
        val average = source.map { it.rankingScore.toDouble() }.takeIf { it.isNotEmpty() }?.average()
        val result = lastProductionResult
        findViewById<TextView>(R.id.summaryLong).text = "VİOP LONG\n$longs"
        findViewById<TextView>(R.id.summaryShort).text = "VİOP SHORT\n$shorts"
        findViewById<TextView>(R.id.summaryWatch).text = "İZLE\n$watch"
        findViewById<TextView>(R.id.summaryTotal).text = if (result == null) {
            "TARANAN\n—"
        } else {
            "TARANAN\n${result.progress.accountedTotal}/${result.progress.total}"
        }
        findViewById<Button>(R.id.filterLong).text = "LONG ($longs)"
        findViewById<Button>(R.id.filterShort).text = "SHORT ($shorts)"
        findViewById<Button>(R.id.filterWatch).text = "İZLE ($watch)"
        findViewById<TextView>(R.id.signalSummary).text = buildString {
            if (result != null) {
                append("Evren ${result.progress.total} • Analiz ${result.progress.analyzed} • Yetersiz ${result.progress.insufficient} • Red ${result.progress.rejected} • Veri yok ${result.progress.noData}")
                if (result.progress.unsupportedOptions > 0) append(" • Opsiyon kapsam dışı ${result.progress.unsupportedOptions}")
                append("\nYayın ${source.size} = LONG $longs + SHORT $shorts + İZLE $watch")
            } else {
                append("${source.size} yayınlanmış vadeli kontrat sonucu")
            }
            average?.let { append(" • Ort. sıralama %${it.toInt().coerceIn(0, 100)}") }
        }
        findViewById<TextView>(R.id.signalLastUpdate).text = if (result == null) {
            "Veri — • Alım — • Tarama —"
        } else {
            "Veri ${timeOf(result.marketDataTimestamp)} • Alım ${timeOf(result.deviceReceivedAt)} • Tarama ${timeOf(result.completedAt)}"
        }
        findViewById<TextView>(R.id.marketSignals).text = "Yayın\n${source.size}"
        findViewById<TextView>(R.id.marketTrend).text = "Mod\nVADELİ"
    }

    private fun categoryMatches(underlyingRaw: String): Boolean {
        val underlying = underlyingRaw.trim().uppercase(Locale.ROOT)
        return when (uiCategory) {
            ViopDashboardUiState.Category.ALL -> true
            ViopDashboardUiState.Category.INDEX -> underlying.startsWith("XU") || underlying.contains("BIST")
            ViopDashboardUiState.Category.EQUITY -> !(underlying.startsWith("XU") || underlying.contains("BIST") || underlying.contains("USD") || underlying.contains("EUR") || underlying.contains("TRY") || listOf("GOLD", "SILVER", "BRENT", "XAU", "XAG").any { underlying.contains(it) } || underlying.contains("RATE") || underlying.contains("FAIZ") || underlying.contains("TLREF"))
            ViopDashboardUiState.Category.FX -> underlying.contains("USD") || underlying.contains("EUR") || underlying.contains("TRY")
            ViopDashboardUiState.Category.COMMODITY -> listOf("GOLD", "SILVER", "BRENT", "XAU", "XAG").any { underlying.contains(it) }
            ViopDashboardUiState.Category.RATE -> underlying.contains("RATE") || underlying.contains("FAIZ") || underlying.contains("TLREF")
        }
    }



    private fun updateDashboardFilterColors() {
        val allSelected = uiCategory == ViopDashboardUiState.Category.ALL && uiDirection == ViopDashboardUiState.Direction.ALL
        fun tint(buttonId: Int, selected: Boolean, selectedColor: Int) {
            val button = findViewById<Button>(buttonId)
            button.backgroundTintList = ColorStateList.valueOf(if (selected) selectedColor else getColor(R.color.chip_bg))
            button.setTextColor(if (selected) Color.WHITE else getColor(R.color.text_primary))
        }
        tint(R.id.filterAll, allSelected, getColor(R.color.green))
        tint(R.id.filterLong, uiDirection == ViopDashboardUiState.Direction.LONG, getColor(R.color.green))
        tint(R.id.filterShort, uiDirection == ViopDashboardUiState.Direction.SHORT, getColor(R.color.red))
        tint(R.id.filterWatch, uiDirection == ViopDashboardUiState.Direction.WATCH, Color.parseColor("#6E8FB2"))
        tint(R.id.filterIndex, uiCategory == ViopDashboardUiState.Category.INDEX, getColor(R.color.blue))
        tint(R.id.filterEquity, uiCategory == ViopDashboardUiState.Category.EQUITY, getColor(R.color.blue))
        tint(R.id.filterFx, uiCategory == ViopDashboardUiState.Category.FX, getColor(R.color.blue))
        tint(R.id.filterCommodity, uiCategory == ViopDashboardUiState.Category.COMMODITY, getColor(R.color.blue))
        tint(R.id.filterRate, uiCategory == ViopDashboardUiState.Category.RATE, getColor(R.color.blue))
    }

    private fun progressText(p: ViopScanProgress) = buildString {
        append("Provider ${p.providerTotal ?: "?"} • Alınan ${p.fetchedCount} • Benzersiz ${p.fetchedUniqueCount} • ${p.universeCompleteness.name}")
        append("\nİşlenen ${p.accountedTotal}/${p.total} • Quote ${p.quoteSuccess} • History ${p.historySuccess} • Analiz ${p.analyzed} • Yetersiz ${p.insufficient} • Red ${p.rejected} • Veri yok ${p.noData}")
        if (p.unsupportedOptions > 0) append(" • Opsiyon ${p.unsupportedOptions}")
    }
    private fun formatPrice(v: Double) = if (v >= 1000.0) "%,.2f".format(v) else "%.2f".format(v)
    private fun formatCompact(v: Double) = when { v >= 1_000_000 -> "%.1f M".format(v / 1_000_000.0); v >= 1_000 -> "%.1f K".format(v / 1_000.0); else -> "%.0f".format(v) }
    private fun timeOf(ms: Long) = if (ms <= 0) "—" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
    private fun shortDate(ms: Long) = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(ms))

    private fun updateSortLabel() {
        findViewById<Button>(R.id.sortMode).text = when (uiSort) {
            ViopDashboardUiState.Sort.RANKING -> "Sırala: Ranking"
            ViopDashboardUiState.Sort.SIGNAL -> "Sırala: Sinyal"
            ViopDashboardUiState.Sort.CONFIDENCE -> "Sırala: Güven"
        }
    }

    companion object {
        private const val AUTO_TEST_COOLDOWN_MS = 30_000L
        private const val UI_PREFS = "viop_dashboard_ui"
        private const val STATE_UI = "dashboard_state_v1"
    }

}
