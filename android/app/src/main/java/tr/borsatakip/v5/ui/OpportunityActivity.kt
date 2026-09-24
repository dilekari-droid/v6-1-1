package tr.borsatakip.v5.ui

import android.Manifest
import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.BackendPreflightClient
import tr.borsatakip.v5.data.BistSessionClosePolicy
import tr.borsatakip.v5.data.BistIndexDataService
import tr.borsatakip.v5.data.MarketDataQuality
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.OpportunityDiscoveryPresentation
import tr.borsatakip.v5.analysis.OpportunityFilter
import tr.borsatakip.v5.analysis.OpportunityFilterPolicy
import tr.borsatakip.v5.data.LastSuccessfulScanStore
import tr.borsatakip.v5.data.ManualScanSessionRepository
import tr.borsatakip.v5.data.ManualScanProgressPolicy
import tr.borsatakip.v5.data.ManualScanStatus
import tr.borsatakip.v5.data.ManualDataQuality
import tr.borsatakip.v5.data.MarketCapabilityClient
import tr.borsatakip.v5.data.IntervalMarketDataProvider
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.ProviderFallbackPolicy
import tr.borsatakip.v5.data.RealtimeScannerClient
import tr.borsatakip.v5.data.RealtimeScannerSocket
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.ScanTimeframe
import tr.borsatakip.v5.data.SignalHistoryRecorder
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.Stock
import tr.borsatakip.v5.model.ScanMode
import tr.borsatakip.v5.model.ScanRun
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.scan.BistScanMode
import tr.borsatakip.v5.scan.BistScanner
import tr.borsatakip.v5.scan.ScanStatus
import tr.borsatakip.v5.worker.BistScanForegroundService
import tr.borsatakip.v5.worker.BistScanStartGate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DiscoveryFilter { ALL, LONG, SHORT, WATCH, STRONG_LONG, STRONG_SHORT, INSUFFICIENT, REJECTED, CATALYST, LOW_RISK, HIGH_VOLUME }

class OpportunityActivity : BaseActivity() {
    private var selectedFilter = DiscoveryFilter.ALL
    private var selectedSort = OpportunitySortPolicy.defaultSort
    private var lastSuccessfulRun: ScanRun? = null
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var historyStore: LastSuccessfulScanStore
    private lateinit var manualScanRepository: ManualScanSessionRepository
    private lateinit var scanButton: TextView
    private var opportunityUiDataState = OpportunityUiDataState.WAITING
    private var handledManualScanId: String? = null
    private var favoriteSymbols: Set<String> = emptySet()
    private lateinit var summary: TextView
    private lateinit var list: RecyclerView
    private lateinit var marketRegime: TextView
    private lateinit var watchedCount: TextView
    private lateinit var foundCount: TextView
    private lateinit var dataQuality: TextView
    private lateinit var realtimeSocket: RealtimeScannerSocket
    private val filterButtons = linkedMapOf<DiscoveryFilter, TextView>()
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr", "TR"))
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when (BistScanStartGate.startAfterPermission(this, granted)) {
            BistScanStartGate.Outcome.STARTED -> Unit
            BistScanStartGate.Outcome.PERMISSION_REQUESTED -> Unit
            BistScanStartGate.Outcome.PROVIDER_UNAVAILABLE -> {
                opportunityUiDataState = OpportunityUiDataState.UNAVAILABLE
                summary.text = "Veri sağlayıcı hazır değil • Ayarlar bölümünü kontrol edin"
                updateTopMetrics(AppSession.lastOpportunities)
            }
            BistScanStartGate.Outcome.FAILED -> {
                opportunityUiDataState = OpportunityUiDataState.ERROR
                summary.text = if (granted) "BIST tarama servisi başlatılamadı" else "Manuel tarama için bildirim izni gerekir"
                updateTopMetrics(AppSession.lastOpportunities)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_opportunity)
        setupBottomNav()
        setupOpportunityBottomNav()

        favoriteRepository = FavoriteRepository.get(this)
        historyStore = LastSuccessfulScanStore(this)
        manualScanRepository = ManualScanSessionRepository.get(this)
        summary = findViewById(R.id.txtSummary)
        list = findViewById(R.id.list)
        marketRegime = findViewById(R.id.txtMarketRegime)
        watchedCount = findViewById(R.id.txtWatchedCount)
        foundCount = findViewById(R.id.txtFoundCount)
        dataQuality = findViewById(R.id.txtDataQuality)
        realtimeSocket = RealtimeScannerSocket(this)
        list.layoutManager = LinearLayoutManager(this)
        val ids = listOf(
            DiscoveryFilter.ALL to R.id.btnFilterAll,
            DiscoveryFilter.LONG to R.id.btnFilterStrongBuy,
            DiscoveryFilter.SHORT to R.id.btnFilterStrongSell,
            DiscoveryFilter.WATCH to R.id.btnFilterWatch,
            DiscoveryFilter.STRONG_LONG to R.id.btnFilterStrongLong,
            DiscoveryFilter.STRONG_SHORT to R.id.btnFilterStrongShort,
            DiscoveryFilter.INSUFFICIENT to R.id.btnFilterInsufficient,
            DiscoveryFilter.REJECTED to R.id.btnFilterRejected,
            DiscoveryFilter.CATALYST to R.id.btnFilterCatalyst,
            DiscoveryFilter.LOW_RISK to R.id.btnFilterLowRisk,
            DiscoveryFilter.HIGH_VOLUME to R.id.btnFilterHighVolume
        )
        ids.forEach { (filter, id) ->
            val button = findViewById<TextView>(id)
            filterButtons[filter] = button
            button.setOnClickListener {
                selectedFilter = filter
                updateFilterVisuals()
                lifecycleScope.launch { applyDiscoveryFilter() }
            }
        }
        updateFilterVisuals()

        findViewById<TextView>(R.id.btnSortOpportunity).apply {
            text = "Sırala: ${OpportunitySortPolicy.label(selectedSort)}"
            contentDescription = "Aktif sıralama: ${OpportunitySortPolicy.label(selectedSort)}"
            setOnClickListener {
                selectedSort = OpportunitySortPolicy.next(selectedSort)
                text = "Sırala: ${OpportunitySortPolicy.label(selectedSort)}"
                contentDescription = "Aktif sıralama: ${OpportunitySortPolicy.label(selectedSort)}"
                Toast.makeText(this@OpportunityActivity, "Sıralama: ${OpportunitySortPolicy.label(selectedSort)}", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch { applyDiscoveryFilter() }
            }
        }

        findViewById<TextView>(R.id.btnSignalHistory).setOnClickListener {
            startActivity(Intent(this, SignalHistoryActivity::class.java))
        }

        lifecycleScope.launch {
            favoriteRepository.migrateLegacyIfNeeded()
            refreshFavoriteSymbols()
            if (AppSession.lastOpportunities.isEmpty()) {
                historyStore.load()?.let { (run, items) ->
                    lastSuccessfulRun = run
                    AppSession.lastOpportunities = OpportunityFilterPolicy.apply(items, OpportunityFilter.ALL)
                }
            }
            showExisting()
        }

        scanButton = findViewById(R.id.btnRealOpportunityScan)
        scanButton.setOnClickListener {
            val current = manualScanRepository.snapshot()
            if (current.isActive) {
                BistScanForegroundService.stop(this)
            } else {
                handledManualScanId = null
                opportunityUiDataState = OpportunityUiDataState.WAITING
                when (BistScanStartGate.startWithPermissionGate(this) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) {
                    BistScanStartGate.Outcome.STARTED, BistScanStartGate.Outcome.PERMISSION_REQUESTED -> Unit
                    BistScanStartGate.Outcome.PROVIDER_UNAVAILABLE -> {
                        opportunityUiDataState = OpportunityUiDataState.UNAVAILABLE
                        summary.text = "Veri sağlayıcı hazır değil • Ayarlar bölümünü kontrol edin"
                        updateTopMetrics(AppSession.lastOpportunities)
                    }
                    BistScanStartGate.Outcome.FAILED -> {
                        opportunityUiDataState = OpportunityUiDataState.ERROR
                        summary.text = "BIST tarama servisi başlatılamadı"
                        updateTopMetrics(AppSession.lastOpportunities)
                    }
                }
            }
        }

        lifecycleScope.launch {
            manualScanRepository.state.collect { session ->
                scanButton.text = if (session.isActive) "DURDUR" else "FIRSATLARI YENİLE"
                when (session.status) {
                    ManualScanStatus.PREFLIGHT -> {
                        opportunityUiDataState = OpportunityUiDataState.WAITING
                        val total = session.totalCount.takeIf { it > 0 }?.toString() ?: "?"
                        summary.text = "BIST verisi doğrulanıyor • ${session.scannedCount}/$total • ${session.currentSymbol ?: session.phase}"
                        updateTopMetrics(AppSession.lastOpportunities)
                    }
                    ManualScanStatus.RUNNING, ManualScanStatus.FINALIZING -> {
                        opportunityUiDataState = OpportunityUiDataState.ANALYZING
                        val total = session.totalCount.takeIf { it > 0 }?.toString() ?: "?"
                        summary.text = "Analiz ediliyor • ${session.scannedCount}/$total • %${ManualScanProgressPolicy.displayPercent(session)} • ${session.currentSymbol ?: session.phase}"
                        updateTopMetrics(AppSession.lastOpportunities)
                    }
                    ManualScanStatus.PAUSED -> {
                        opportunityUiDataState = OpportunityUiDataState.ANALYZING
                        val total = session.totalCount.takeIf { it > 0 }?.toString() ?: "?"
                        summary.text = "Tarama duraklatıldı • ağ bağlantısı bekleniyor • ${session.scannedCount}/$total • %${ManualScanProgressPolicy.displayPercent(session)}"
                        updateTopMetrics(AppSession.lastOpportunities)
                    }
                    ManualScanStatus.COMPLETED, ManualScanStatus.PARTIAL -> {
                        opportunityUiDataState = if (session.status == ManualScanStatus.PARTIAL || session.dataQuality == ManualDataQuality.PARTIAL) OpportunityUiDataState.PARTIAL else OpportunityUiDataState.READY
                        if (handledManualScanId != session.scanId) {
                            handledManualScanId = session.scanId
                            val prefix = if (opportunityUiDataState == OpportunityUiDataState.PARTIAL) "KISMİ TARAMA" else "TARAMA TAMAMLANDI"
                            applyDiscoveryFilter("$prefix • ${session.resultCount} analiz sonucu • ${session.providerStatus}")
                        }
                    }
                    ManualScanStatus.DATA_UNAVAILABLE -> {
                        opportunityUiDataState = OpportunityUiDataState.UNAVAILABLE
                        if (AppSession.lastOpportunities.isEmpty()) bindFiltered(emptyList(), "Veri yok / analiz bekleniyor • ${session.message.orEmpty()}")
                        else updateTopMetrics(AppSession.lastOpportunities)
                    }
                    ManualScanStatus.ERROR, ManualScanStatus.INTERRUPTED -> {
                        opportunityUiDataState = OpportunityUiDataState.ERROR
                        if (AppSession.lastOpportunities.isEmpty()) bindFiltered(emptyList(), "Analiz edilemedi • ${session.message.orEmpty()}")
                        else updateTopMetrics(AppSession.lastOpportunities)
                    }
                    ManualScanStatus.STOPPED -> {
                        opportunityUiDataState = OpportunityUiDataState.UNAVAILABLE
                        summary.text = "Tarama durduruldu • tamamlandı sayılmadı • son doğrulanmış sonuç korunuyor"
                        updateTopMetrics(AppSession.lastOpportunities)
                    }
                    ManualScanStatus.IDLE -> Unit
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val settings = SettingsStore(this)
        if (!productionBackendConfigured(settings)) {
            realtimeSocket.close()
            if (AppSession.lastOpportunities.isEmpty() && !manualScanRepository.snapshot().isActive) {
                summary.text = if (fallbackAllowed(settings)) {
                    "Yahoo Finance • YEDEK/GECİKMELİ hazır • FIRSATLARI YENİLE ile ortak BIST taramasını başlat"
                } else {
                    "Veri sağlayıcı hazır değil • Yahoo fallback kapalı"
                }
            }
            return
        }
        if (BistSessionClosePolicy.phase() != BistSessionClosePolicy.Phase.OPEN) {
            realtimeSocket.close()
            if (AppSession.lastOpportunities.isEmpty() && !manualScanRepository.snapshot().isActive) {
                summary.text = "BIST seansı kapalı • FIRSATLARI YENİLE ile son kapanmış ${ScanTimeframe.displayLabel(settings.analysisTimeframeMinutes)} OHLCV taranır"
            }
            return
        }

        lifecycleScope.launch {
            val capability = MarketCapabilityClient(this@OpportunityActivity).load().getOrNull()
            val realtimeEnabled = capability?.features?.realtimeScannerWebSocket == true && capability.features.attestationReady
            if (!realtimeEnabled) {
                realtimeSocket.close()
                if (AppSession.lastOpportunities.isEmpty() && !manualScanRepository.snapshot().isActive) {
                    summary.text = "Canlı scanner backend capability kapalı • FIRSATLARI YENİLE ile doğrulanmış BIST taramasını kullan"
                    opportunityUiDataState = OpportunityUiDataState.WAITING
                    updateTopMetrics(emptyList())
                }
                return@launch
            }
            connectRealtimeSocket(settings)
        }
    }

    private fun connectRealtimeSocket(settings: SettingsStore) {
        realtimeSocket.connect(
            minScore = 55,
            limit = 50,
            actionableOnly = false,
            minIntervalMs = 1500,
            analysisTimeframeMinutes = settings.analysisTimeframeMinutes,
            scanCadenceMinutes = settings.scanCadenceMinutes,
            scanMode = ScanMode.LIVE,
            listener = object : RealtimeScannerSocket.Listener {
                override fun onSubscribed() = runOnUiThread {
                    if (AppSession.lastOpportunities.isEmpty()) summary.text = "Canlı BIST scanner bağlandı • fırsat bekleniyor"
                    opportunityUiDataState = OpportunityUiDataState.WAITING
                    updateTopMetrics(AppSession.lastOpportunities)
                }

                override fun onSnapshot(snapshot: RealtimeScannerClient.Snapshot) = runOnUiThread {
                    AppSession.lastOpportunities = snapshot.opportunities
                    lifecycleScope.launch {
                        if (snapshot.readySymbols <= 0) {
                            opportunityUiDataState = OpportunityUiDataState.UNAVAILABLE
                            bindFiltered(emptyList(), "CANLI AKIŞ • ${snapshot.provider} • hazır sembol yok • veri yok fırsat 0 sayılmadı")
                            return@launch
                        }
                        opportunityUiDataState = OpportunityUiDataState.READY
                        val total = snapshot.expectedSymbols.takeIf { it > 0 } ?: snapshot.trackedSymbols
                        val coverage = snapshot.readyCoveragePct?.let { " • hazır kapsam %${"%.1f".format(it)}" } ?: ""
                        applyDiscoveryFilter(
                            "CANLI AKIŞ • ${snapshot.provider} • ${snapshot.readySymbols}/$total hazır$coverage" +
                                " • analiz ${ScanTimeframe.displayLabel(snapshot.analysisTimeframeMinutes)} • cadence ${snapshot.scanCadenceMinutes} DK"
                        )
                    }
                }

                override fun onDisconnected(reason: String) = runOnUiThread {
                    AppSession.lastOpportunities = emptyList()
                    opportunityUiDataState = OpportunityUiDataState.UNAVAILABLE
                    lifecycleScope.launch { bindFiltered(emptyList(), "Canlı scanner bağlantısı kapandı • $reason • veri yok fırsat 0 sayılmadı") }
                }

                override fun onError(message: String) = runOnUiThread {
                    AppSession.lastOpportunities = emptyList()
                    opportunityUiDataState = OpportunityUiDataState.ERROR
                    lifecycleScope.launch { bindFiltered(emptyList(), "Canlı scanner hatası • $message • veri yok fırsat 0 sayılmadı") }
                }
            }
        )
    }

    override fun onStop() {
        realtimeSocket.close()
        super.onStop()
    }

    private suspend fun applyDiscoveryFilter(prefix: String? = null) {
        val base = OpportunityFilterPolicy.apply(AppSession.lastOpportunities, OpportunityFilter.ALL)
        val filtered = base.filter { x ->
            val p = OpportunityDiscoveryPresentation.from(x)
            when (selectedFilter) {
                DiscoveryFilter.ALL,
                DiscoveryFilter.LONG,
                DiscoveryFilter.SHORT,
                DiscoveryFilter.WATCH,
                DiscoveryFilter.STRONG_LONG,
                DiscoveryFilter.STRONG_SHORT -> {
                    val core = when (selectedFilter) {
                        DiscoveryFilter.ALL -> CoreDiscoveryFilter.ALL
                        DiscoveryFilter.LONG -> CoreDiscoveryFilter.LONG
                        DiscoveryFilter.SHORT -> CoreDiscoveryFilter.SHORT
                        DiscoveryFilter.WATCH -> CoreDiscoveryFilter.WATCH
                        DiscoveryFilter.STRONG_LONG -> CoreDiscoveryFilter.STRONG_LONG
                        DiscoveryFilter.STRONG_SHORT -> CoreDiscoveryFilter.STRONG_SHORT
                        else -> CoreDiscoveryFilter.ALL
                    }
                    DiscoveryFilterSemantics.matches(
                        DiscoveryFilterInput(
                            symbol = x.symbol,
                            directionLabel = OpportunityUiPolicy.directionLabel(x),
                            strength = OpportunityUiPolicy.strength(x)
                        ),
                        core
                    )
                }
                DiscoveryFilter.INSUFFICIENT -> p.classification == "YETERSİZ VERİ"
                DiscoveryFilter.REJECTED -> p.classification == "REDDEDİLDİ"
                DiscoveryFilter.CATALYST -> p.catalystAvailable
                DiscoveryFilter.LOW_RISK -> p.lowRisk
                DiscoveryFilter.HIGH_VOLUME -> p.highVolume
            }
        }
        val sorted = OpportunitySortPolicy.sort(filtered, selectedSort)
        val baseText = prefix ?: lastSuccessfulRun?.let { "SON GÜNCELLEME • ${formatRunTime(it)}" } ?: "FIRSAT KONTROLÜ"
        val emptySuffix = if (sorted.isEmpty()) " • Bu filtrede aday yok" else ""
        bindFiltered(sorted, "$baseText • ${filterLabel(selectedFilter)} • ${sorted.size}/${base.size}$emptySuffix")
    }

    private suspend fun bindFiltered(items: List<Opportunity>, title: String) {
        refreshFavoriteSymbols()
        summary.text = title
        updateTopMetrics(AppSession.lastOpportunities)
        list.adapter = if (selectedFilter == DiscoveryFilter.ALL && OpportunityUiPolicy.isNumericState(opportunityUiDataState)) {
            val longs = items.filter { OpportunityUiPolicy.directionLabel(it) == "LONG" }
            val shorts = items.filter { OpportunityUiPolicy.directionLabel(it) == "SHORT" }
            val watch = items.filter { OpportunityUiPolicy.directionLabel(it) == "İZLE" }
            val other = items.filter { OpportunityUiPolicy.directionLabel(it) !in setOf("LONG", "SHORT", "İZLE") }
            val adapters = mutableListOf<RecyclerView.Adapter<out RecyclerView.ViewHolder>>()
            adapters += OpportunitySectionHeaderAdapter("LONG Fırsatları", longs.size, R.color.green)
            adapters += buildOpportunityAdapter(longs)
            adapters += OpportunitySectionHeaderAdapter("SHORT Fırsatları", shorts.size, R.color.red)
            adapters += buildOpportunityAdapter(shorts)
            adapters += OpportunitySectionHeaderAdapter("İzleme Listesi", watch.size, R.color.text_secondary)
            adapters += buildOpportunityAdapter(watch)
            if (other.isNotEmpty()) {
                adapters += OpportunitySectionHeaderAdapter("Diğer Sonuçlar", other.size, R.color.yellow)
                adapters += buildOpportunityAdapter(other)
            }
            ConcatAdapter(*adapters.toTypedArray())
        } else {
            buildOpportunityAdapter(items)
        }
    }

    private fun buildOpportunityAdapter(items: List<Opportunity>): OpportunityAdapter = OpportunityAdapter(
        items,
        favoriteSymbols,
        click = {
            AppSession.selected = it
            startActivity(Intent(this, StockDetailActivity::class.java).putExtra("opportunity", it))
        },
        toggleFavorite = { opportunity ->
            lifecycleScope.launch {
                val added = favoriteRepository.toggle(
                    opportunity.symbol,
                    opportunity.companyName,
                    analysisIntervalMinutes = opportunity.analysisTimeframeMinutes.takeIf { ScanTimeframe.isSupportedStored(it) }
                )
                Toast.makeText(
                    this@OpportunityActivity,
                    if (added) "${opportunity.symbol} favorilere eklendi" else "${opportunity.symbol} favorilerden çıkarıldı",
                    Toast.LENGTH_SHORT
                ).show()
                applyDiscoveryFilter()
            }
        }
    )

    private fun setupOpportunityBottomNav() {
        findViewById<TextView>(R.id.navViop)?.apply {
            text = "⌕\nBIST Tarama"
            setOnClickListener { startActivity(Intent(this@OpportunityActivity, BistScanActivity::class.java)) }
        }
        findViewById<TextView>(R.id.navFav)?.apply {
            text = "◎\nFırsat Kontrolü"
            setTextColor(getColor(R.color.blue))
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_nav_active_glow)
            setOnClickListener { }
        }
    }

    private fun refreshBenchmarkSummary() {
        lifecycleScope.launch {
            val stock = withContext(Dispatchers.IO) { BistIndexDataService(this@OpportunityActivity).load("XU100") }
            bindBenchmark(stock)
        }
    }

    private fun bindBenchmark(stock: Stock?) {
        val value = findViewById<TextView>(R.id.txtOpportunityBenchmarkValue)
        val change = findViewById<TextView>(R.id.txtOpportunityBenchmarkChange)
        val chart = findViewById<StockSparklineView>(R.id.opportunityBenchmarkChart)
        val price = stock?.quotePrice ?: stock?.candles?.lastOrNull()?.close
        val previous = stock?.previousClose ?: stock?.candles?.takeIf { it.size >= 2 }?.get(it.lastIndex - 1)?.close
        val pct = if (price != null && previous != null && price.isFinite() && previous.isFinite() && previous > 0.0) {
            ((price / previous) - 1.0) * 100.0
        } else null
        if (stock == null || price == null || !price.isFinite() || price <= 0.0) {
            value.text = "—"
            change.text = "Benchmark verisi alınamadı"
            change.setTextColor(getColor(R.color.text_secondary))
            chart.setCandles(emptyList(), null)
            return
        }
        value.text = if (price >= 1000.0) "%,.2f".format(Locale.getDefault(), price) else "%.2f".format(Locale.getDefault(), price)
        val status = MarketDataQuality.uiStatus(stock.marketDataMetadata)
        change.text = listOfNotNull(pct?.let { "%+.2f%%".format(Locale.getDefault(), it) }, status.takeIf { it.isNotBlank() }).joinToString(" • ")
        change.setTextColor(when {
            pct == null -> getColor(R.color.text_secondary)
            pct > 0.0 -> getColor(R.color.green)
            pct < 0.0 -> getColor(R.color.red)
            else -> getColor(R.color.text_secondary)
        })
        chart.setCandles(stock.candles, pct)
    }

    private fun updateTopMetrics(items: List<Opportunity>) {
        val regime = AppSession.lastScanState?.marketRegime
        if (regime != null && opportunityUiDataState in setOf(OpportunityUiDataState.READY, OpportunityUiDataState.PARTIAL, OpportunityUiDataState.ARCHIVE)) {
            marketRegime.text = "${regime.regime.label} • %${regime.confidence}"
            marketRegime.setTextColor(when (regime.regime.name) {
                "TREND_UP" -> getColor(R.color.green)
                "TREND_DOWN", "HIGH_VOLATILITY" -> getColor(R.color.red)
                else -> getColor(R.color.yellow)
            })
        } else {
            val fromItems = items.firstOrNull { it.marketRegime != "VERİ YOK" }
            marketRegime.text = if (fromItems != null && opportunityUiDataState in setOf(OpportunityUiDataState.READY, OpportunityUiDataState.PARTIAL, OpportunityUiDataState.ARCHIVE)) {
                "${fromItems.marketRegime} • %${fromItems.marketRegimeConfidence}"
            } else "Benchmark verisi alınamadı"
            marketRegime.setTextColor(getColor(R.color.yellow))
        }

        val watched = AppSession.lastScanState?.total?.takeIf { it > 0 } ?: lastSuccessfulRun?.count?.takeIf { it > 0 } ?: items.size
        val metrics = OpportunityUiPolicy.metrics(opportunityUiDataState, watched, items)
        watchedCount.text = metrics.watched
        foundCount.text = metrics.opportunities
        dataQuality.text = metrics.dataQuality
    }

    private fun updateFilterVisuals() {
        filterButtons.forEach { (filter, button) ->
            val active = filter == selectedFilter
            button.alpha = if (active) 1f else 0.72f
            button.setBackgroundResource(if (active) R.drawable.bg_chip_selected_blue else R.drawable.bg_chip)
            val clean = button.text.toString().removePrefix("✓ ")
            button.text = if (active) "✓ $clean" else clean
        }
    }

    private suspend fun refreshFavoriteSymbols() {
        favoriteSymbols = favoriteRepository.symbols()
    }

    private suspend fun showExisting() {
        val settings = SettingsStore(this)
        if (AppSession.lastOpportunities.isEmpty()) {
            val text = when {
                productionBackendConfigured(settings) -> "Kayıtlı fırsat yok • FIRSATLARI YENİLE ile Production verisini değerlendir"
                fallbackAllowed(settings) -> "Kayıtlı doğrulanmış fırsat yok • Yahoo YEDEK/GECİKMELİ analiz kullanılabilir"
                else -> "Kayıtlı doğrulanmış fırsat yok • veri sağlayıcı hazır değil"
            }
            opportunityUiDataState = OpportunityUiDataState.WAITING
            bindFiltered(emptyList(), text)
        } else {
            opportunityUiDataState = if (lastSuccessfulRun != null) OpportunityUiDataState.ARCHIVE else OpportunityUiDataState.READY
            applyDiscoveryFilter()
        }
    }

    private fun filterLabel(filter: DiscoveryFilter) = when (filter) {
        DiscoveryFilter.ALL -> "Tümü"
        DiscoveryFilter.LONG -> "LONG"
        DiscoveryFilter.SHORT -> "SHORT"
        DiscoveryFilter.WATCH -> "İzleme"
        DiscoveryFilter.STRONG_LONG -> "Güçlü LONG"
        DiscoveryFilter.STRONG_SHORT -> "Güçlü SHORT"
        DiscoveryFilter.INSUFFICIENT -> "Yetersiz Veri"
        DiscoveryFilter.REJECTED -> "Reddedildi"
        DiscoveryFilter.CATALYST -> "Katalizörlü"
        DiscoveryFilter.LOW_RISK -> "Düşük Risk"
        DiscoveryFilter.HIGH_VOLUME -> "Yüksek Hacim"
    }

    private fun formatRunTime(run: ScanRun): String {
        val ts = run.scanCompletedAt ?: run.scanStartedAt
        return if (ts > 0) dateFormat.format(Date(ts)) else "zaman bilinmiyor"
    }

    private fun productionBackendConfigured(settings: SettingsStore): Boolean =
        ProviderReadinessService.isValidHttps(settings.baseUrl) && settings.apiKey.isNotBlank()

    private fun fallbackAllowed(settings: SettingsStore): Boolean =
        ProviderFallbackPolicy.allowed(settings.experimentalProvidersEnabled, settings.yahooFallbackEnabled)

    override fun onResume() {
        super.onResume()
        refreshBenchmarkSummary()
        if (::favoriteRepository.isInitialized && ::list.isInitialized) {
            lifecycleScope.launch { showExisting() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object { const val EXTRA_SCAN_WARNING = "scan_warning" }
}
