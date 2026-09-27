package tr.borsatakip.v5.ui

import tr.borsatakip.v5.data.ProviderReadinessService
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.BackendProvider
import tr.borsatakip.v5.data.BistIndexDataService
import tr.borsatakip.v5.data.ExternalMarketQuoteService
import tr.borsatakip.v5.data.MarketCapabilityClient
import tr.borsatakip.v5.data.ManualScanResultRepository
import tr.borsatakip.v5.data.DynamicMarketScanResultRepository
import tr.borsatakip.v5.data.RealtimeScannerClient
import tr.borsatakip.v5.data.RealtimeScannerSocket
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.ViopContractSelector
import tr.borsatakip.v5.analysis.HomeRealtimeSignalPolicy
import tr.borsatakip.v5.analysis.HomeOpportunityFilterPolicy
import tr.borsatakip.v5.analysis.HomeOpportunityFeedItem
import tr.borsatakip.v5.analysis.HomeOpportunityFeedPolicy
import tr.borsatakip.v5.analysis.HomeOpportunitySnapshotStore
import tr.borsatakip.v5.analysis.HomeOpportunityFeedStatus
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopQuote
import tr.borsatakip.v5.model.Stock
import tr.borsatakip.v5.model.ScanMode
import tr.borsatakip.v5.scan.ScanOrchestrator
import tr.borsatakip.v5.worker.RealtimeAlertService
import tr.borsatakip.v5.worker.AutoScanScheduler
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : BaseActivity() {
    private var todayMode: HomeOpportunityFilterPolicy.Filter = HomeOpportunityFilterPolicy.Filter.ALL
    private val trLocale = Locale("tr", "TR")
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000L)
        }
    }

    private data class TileViews(val value: TextView, val change: TextView, val spark: TextView)

    private val homeRealtimeSocket by lazy { RealtimeScannerSocket(this) }
    private var observedOpportunityState = false
    private var lastBistMarkers: Map<String, HomeRealtimeSignalPolicy.Marker> = emptyMap()
    private val pendingBlinkSymbols = linkedSetOf<String>()
    private var lastViopMarker: HomeRealtimeSignalPolicy.Marker? = null
    private var viop30Contract: ViopContract? = null
    private var viop30ContractResolvedAt: Long = 0L
    private var lastViopQuote: ViopQuote? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupBottomNav()
        todayMode = HomeOpportunityFilterPolicy.Filter.restore(savedInstanceState?.getString(STATE_TODAY_FILTER))

        findViewById<TextView>(R.id.navHome)?.apply {
            setTextColor(getColor(R.color.blue))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        findViewById<TextView>(R.id.txtVersionBadge).text = "V${BuildConfig.VERSION_NAME}"
        findViewById<View>(R.id.actionBist).setOnClickListener { startActivity(Intent(this, BistScanActivity::class.java)) }
        findViewById<View>(R.id.actionOpportunity).setOnClickListener { startActivity(Intent(this, DynamicMarketScanActivity::class.java)) }
        findViewById<View>(R.id.actionSignal).setOnClickListener { startActivity(Intent(this, SignalHistoryActivity::class.java)) }
        findViewById<View>(R.id.actionViop).setOnClickListener { startActivity(Intent(this, ViopActivity::class.java)) }
        findViewById<View>(R.id.actionWatchlist).setOnClickListener { startActivity(Intent(this, FavoritesActivity::class.java)) }
        findViewById<View>(R.id.actionNotifications).setOnClickListener { startActivity(Intent(this, NotificationsActivity::class.java)) }
        findViewById<View>(R.id.btnHeaderSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.btnMarketDetails).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.btnSeeAllOpportunities).setOnClickListener { startActivity(Intent(this, OpportunityActivity::class.java)) }
        bindMarketTileClicks()
        bindTodayTabs()

        updateClock()
        refreshMarketSummary()
        renderTodayOpportunities()
        resetMarketTiles()
        setupHomeLiveUpdates()
        handleRealtimeAlertIntent(intent)
        val startupSettings = SettingsStore(this)
        if (startupSettings.notifications) RealtimeAlertService.start(this)
        if (startupSettings.autoScanEnabled) AutoScanScheduler.reconcile(this, allowForegroundStart = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TODAY_FILTER, todayMode.name)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRealtimeAlertIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        clockHandler.removeCallbacks(clockTick)
        clockHandler.post(clockTick)
        refreshMarketSummary()
        renderTodayOpportunities()
    }

    override fun onStart() {
        super.onStart()
        val settings = SettingsStore(this)
        if (!settings.notifications) startHomeRealtimeSocket(settings)
    }

    override fun onStop() {
        homeRealtimeSocket.close()
        super.onStop()
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockTick)
        super.onPause()
    }

    private fun handleRealtimeAlertIntent(sourceIntent: Intent?) {
        val symbol = sourceIntent?.getStringExtra(RealtimeAlertService.EXTRA_ALERT_SYMBOL)
            ?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (symbol.isBlank()) return
        sourceIntent?.removeExtra(RealtimeAlertService.EXTRA_ALERT_SYMBOL)
        lifecycleScope.launch {
            val capability = MarketCapabilityClient(this@MainActivity).load().getOrNull()
            val enabled = capability?.features?.realtimeScannerRest == true && capability.features.attestationReady
            if (!enabled) return@launch
            val snapshot = RealtimeScannerClient(this@MainActivity).load(limit = 1, symbols = listOf(symbol)).getOrNull()
            val opportunity = snapshot?.opportunities?.firstOrNull { it.symbol.equals(symbol, true) }
            if (opportunity != null) {
                AppSession.selected = opportunity
                startActivity(Intent(this@MainActivity, StockDetailActivity::class.java).putExtra("opportunity", opportunity))
            }
        }
    }

    private fun bindMarketTileClicks() {
        listOf(
            R.id.marketBist100Card to "BIST100",
            R.id.marketBist30Card to "BIST30",
            R.id.marketUsdCard to "USDTRY",
            R.id.marketGoldCard to "XAUUSD",
            R.id.marketViopCard to "VIOP30"
        ).forEach { (viewId, instrument) ->
            findViewById<View>(viewId).setOnClickListener {
                startActivity(Intent(this, MarketInstrumentDetailActivity::class.java).putExtra(MarketInstrumentDetailActivity.EXTRA_INSTRUMENT, instrument))
            }
        }
    }

    private fun bindTodayTabs() {
        val all = findViewById<TextView>(R.id.todayAllTab)
        val long = findViewById<TextView>(R.id.todayLongTab)
        val short = findViewById<TextView>(R.id.todayShortTab)
        all.setOnClickListener { selectTodayFilter(HomeOpportunityFilterPolicy.Filter.ALL) }
        long.setOnClickListener { selectTodayFilter(HomeOpportunityFilterPolicy.Filter.LONG) }
        short.setOnClickListener { selectTodayFilter(HomeOpportunityFilterPolicy.Filter.SHORT) }
        updateTodayTabStyles()
    }

    private fun selectTodayFilter(filter: HomeOpportunityFilterPolicy.Filter) {
        if (todayMode == filter) return
        todayMode = filter
        updateTodayTabStyles()
        renderTodayOpportunities()
    }

    private fun updateTodayTabStyles() {
        listOf(
            Triple(R.id.todayAllTab, HomeOpportunityFilterPolicy.Filter.ALL, R.color.blue),
            Triple(R.id.todayLongTab, HomeOpportunityFilterPolicy.Filter.LONG, R.color.green),
            Triple(R.id.todayShortTab, HomeOpportunityFilterPolicy.Filter.SHORT, R.color.red)
        ).forEach { (id, mode, colorRes) ->
            findViewById<TextView>(id).apply {
                val selected = todayMode == mode
                setTextColor(getColor(if (selected) colorRes else R.color.text_secondary))
                background = getDrawable(if (selected) R.drawable.bg_chip_selected_blue else R.drawable.bg_chip_outline)
            }
        }
    }

    private fun updateClock() {
        val df = SimpleDateFormat("dd MMM yyyy • HH:mm", trLocale)
        findViewById<TextView>(R.id.txtHeaderDateTime).text = df.format(Date())
    }

    private fun refreshMarketSummary() {
        val s = SettingsStore(this)
        val label = s.lastProviderLabel.takeIf { it.isNotBlank() } ?: "Kaynak doğrulanmadı"
        val timeText = if (s.lastProviderTimestamp > 0L) {
            SimpleDateFormat("HH:mm:ss", trLocale).format(Date(s.lastProviderTimestamp))
        } else {
            "veri zamanı yok"
        }
        findViewById<TextView>(R.id.txtMarketSummary).text = "$label • $timeText • gecikme sonucu ilgili ekranda doğrulanır"
    }

    private fun marketTile(valueId: Int, changeId: Int, sparkId: Int) = TileViews(
        findViewById(valueId), findViewById(changeId), findViewById(sparkId)
    )

    private fun resetMarketTiles() {
        listOf(
            marketTile(R.id.marketBist100Value, R.id.marketBist100Change, R.id.marketBist100Spark),
            marketTile(R.id.marketBist30Value, R.id.marketBist30Change, R.id.marketBist30Spark),
            marketTile(R.id.marketUsdValue, R.id.marketUsdChange, R.id.marketUsdSpark),
            marketTile(R.id.marketGoldValue, R.id.marketGoldChange, R.id.marketGoldSpark),
            marketTile(R.id.marketViopValue, R.id.marketViopChange, R.id.marketViopSpark)
        ).forEach {
            it.value.text = "—"
            it.change.text = "Veri bekleniyor"
            it.change.setTextColor(getColor(R.color.text_muted))
            it.spark.text = "—"
            it.spark.setTextColor(getColor(R.color.text_muted))
        }
    }

    /**
     * Home cards never invent a quote. A tile is populated only when the configured provider returns
     * a validated result. Missing provider/data deliberately stays as an em dash.
     */
    private fun setupHomeLiveUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ManualScanResultRepository.state.collect { state ->
                        val current = state.items.mapNotNull { item ->
                            HomeRealtimeSignalPolicy.bistMarker(item)?.let { marker ->
                                item.symbol.trim().uppercase(Locale.ROOT) to marker
                            }
                        }.toMap()
                        if (observedOpportunityState) {
                            current.forEach { (symbol, marker) ->
                                if (HomeRealtimeSignalPolicy.isMeaningfulChange(lastBistMarkers[symbol], marker)) {
                                    pendingBlinkSymbols += symbol
                                }
                            }
                        } else {
                            observedOpportunityState = true
                        }
                        lastBistMarkers = current
                        renderTodayOpportunities()
                    }
                }
                launch {
                    DynamicMarketScanResultRepository.state.collect {
                        // Dynamic BIST scan is an independent validated source for Home opportunities.
                        renderTodayOpportunities()
                    }
                }
                launch {
                    while (true) {
                        refreshMarketTilesOnce()
                        delay(HOME_MARKET_REFRESH_MS)
                    }
                }
                launch {
                    while (true) {
                        refreshViop30Signal()
                        val cadenceMs = SettingsStore(this@MainActivity).scanCadenceMinutes
                            .coerceIn(1, 1440) * 60_000L
                        delay(cadenceMs.coerceAtLeast(VIOP_SIGNAL_MIN_REFRESH_MS))
                    }
                }
            }
        }
    }

    private fun startHomeRealtimeSocket(settings: SettingsStore) {
        lifecycleScope.launch {
            val capability = MarketCapabilityClient(this@MainActivity).load().getOrNull()
            val enabled = capability?.features?.realtimeScannerWebSocket == true && capability.features.attestationReady
            if (!enabled || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            homeRealtimeSocket.connect(
                minScore = 55,
                limit = 50,
                actionableOnly = false,
                minIntervalMs = 1000,
                analysisTimeframeMinutes = settings.analysisTimeframeMinutes,
                scanCadenceMinutes = settings.scanCadenceMinutes,
                scanMode = ScanMode.LIVE,
                listener = object : RealtimeScannerSocket.Listener {
                    override fun onSubscribed() = Unit

                    override fun onSnapshot(snapshot: RealtimeScannerClient.Snapshot) {
                        if (snapshot.readySymbols <= 0) {
                            if (ManualScanResultRepository.snapshot().source.startsWith("HOME_REALTIME"))
                                ManualScanResultRepository.clear(source = "HOME_REALTIME_NO_DATA")
                        } else {
                            ManualScanResultRepository.publish(snapshot.opportunities, source = "HOME_REALTIME")
                        }
                    }

                    override fun onDisconnected(reason: String) {
                        if (ManualScanResultRepository.snapshot().source.startsWith("HOME_REALTIME"))
                            ManualScanResultRepository.clear(source = "HOME_REALTIME_DISCONNECTED")
                    }

                    override fun onError(message: String) {
                        if (ManualScanResultRepository.snapshot().source.startsWith("HOME_REALTIME"))
                            ManualScanResultRepository.clear(source = "HOME_REALTIME_ERROR")
                    }
                }
            )
        }
    }

    /**
     * Home cards never invent a quote. A tile is populated only when the configured provider returns
     * a validated result. The refresh loop runs only while MainActivity is STARTED.
     */
    private suspend fun refreshMarketTilesOnce() = coroutineScope {
        val external = ExternalMarketQuoteService(this@MainActivity)
        val settings = SettingsStore(this@MainActivity)
        val backend = BackendProvider(this@MainActivity)
        val indexDeferred = async { BistIndexDataService(this@MainActivity).loadMany(listOf("XU100", "XU030")) }
        val bist100Deferred = async { indexDeferred.await()["XU100"] }
        val bist30Deferred = async { indexDeferred.await()["XU030"] }
        val usdDeferred = async { external.load("USDTRY", listOf("TRY=X")) }
        val goldDeferred = async { external.load("XAUUSD", listOf("XAUUSD=X", "GC=F")) }
        val viop30Deferred = async { loadViop30Quote(settings, backend) }

        bindStockTile(
            marketTile(R.id.marketBist100Value, R.id.marketBist100Change, R.id.marketBist100Spark),
            bist100Deferred.await()
        )
        bindStockTile(
            marketTile(R.id.marketBist30Value, R.id.marketBist30Change, R.id.marketBist30Spark),
            bist30Deferred.await()
        )
        bindExternalMarketTile(
            marketTile(R.id.marketUsdValue, R.id.marketUsdChange, R.id.marketUsdSpark),
            usdDeferred.await()
        )
        bindExternalMarketTile(
            marketTile(R.id.marketGoldValue, R.id.marketGoldChange, R.id.marketGoldSpark),
            goldDeferred.await()
        )
        val quote = viop30Deferred.await()
        lastViopQuote = quote
        val viopMarker = quote?.let { q ->
            AppSession.lastViopOpportunities
                .firstOrNull { it.quote.symbol.equals(q.symbol, true) }
                ?.let { HomeRealtimeSignalPolicy.viopMarker(it) }
        }
        bindQuoteTile(
            marketTile(R.id.marketViopValue, R.id.marketViopChange, R.id.marketViopSpark),
            quote,
            viopMarker
        )
    }

    private suspend fun resolveViop30Contract(settings: SettingsStore, backend: BackendProvider): ViopContract? {
        if (!ProviderReadinessService.isValidHttps(settings.baseUrl)) return null
        val now = System.currentTimeMillis()
        val cached = viop30Contract
        if (cached != null && now - viop30ContractResolvedAt <= VIOP_CONTRACT_CACHE_MS) return cached
        val contracts = backend.loadViop().getOrNull().orEmpty()
        val resolved = ViopContractSelector.candidates(contracts, underlying = "XU030", allowWatch = false)
            .firstOrNull()
        viop30Contract = resolved
        viop30ContractResolvedAt = now
        return resolved
    }

    private suspend fun loadViop30Quote(settings: SettingsStore, backend: BackendProvider): ViopQuote? {
        val contract = resolveViop30Contract(settings, backend) ?: return null
        return backend.loadViopQuote(contract.symbol).getOrNull()
    }

    private suspend fun refreshViop30Signal() {
        val settings = SettingsStore(this@MainActivity)
        val backend = BackendProvider(this@MainActivity)
        val contract = resolveViop30Contract(settings, backend) ?: run {
            AppSession.lastViopOpportunities = AppSession.lastViopOpportunities
                .filterNot { it.contract.underlying.equals("XU030", true) }
            bindQuoteTile(
                marketTile(R.id.marketViopValue, R.id.marketViopChange, R.id.marketViopSpark),
                lastViopQuote,
                null
            )
            return
        }
        val opportunity = runCatching {
            ScanOrchestrator(this@MainActivity).runViopContract(contract)
        }.getOrNull()
        val others = AppSession.lastViopOpportunities
            .filterNot { it.contract.symbol.equals(contract.symbol, true) }
        AppSession.lastViopOpportunities = if (opportunity == null) others else others + opportunity
        val marker = opportunity?.let { HomeRealtimeSignalPolicy.viopMarker(it) }
        bindQuoteTile(
            marketTile(R.id.marketViopValue, R.id.marketViopChange, R.id.marketViopSpark),
            lastViopQuote ?: opportunity?.quote,
            marker
        )
    }

    private fun bindStockTile(tile: TileViews, stock: Stock?) {
        if (stock == null) {
            setTileUnavailable(tile, "VERİ YOK")
            return
        }
        val price = stock.quotePrice ?: stock.candles.lastOrNull()?.close ?: run {
            setTileUnavailable(tile, "VERİ YOK")
            return
        }
        val previous = stock.previousClose ?: stock.candles.dropLast(1).lastOrNull()?.close
        val change = previous?.takeIf { it > 0.0 }?.let { ((price - it) / it) * 100.0 }
        bindMarketTile(tile, price, change, stock.candles)
    }

    private fun setTileUnavailable(tile: TileViews, label: String) {
        tile.value.text = "—"
        tile.change.text = label
        tile.change.setTextColor(getColor(R.color.text_muted))
        tile.spark.text = "—"
        tile.spark.setTextColor(getColor(R.color.text_muted))
    }

    private fun bindExternalMarketTile(tile: TileViews, result: ExternalMarketQuoteService.Result) {
        val stock = result.stock
        if (stock == null) {
            tile.value.text = "—"
            tile.change.text = when {
                result.fallback.state == ExternalMarketQuoteService.StageState.DISABLED -> "Yedek kapalı"
                result.fallback.state == ExternalMarketQuoteService.StageState.FAILED -> "Veri alınamadı"
                else -> "Veri yok"
            }
            tile.change.setTextColor(getColor(R.color.text_muted))
            tile.spark.text = "—"
            tile.spark.setTextColor(getColor(R.color.text_muted))
            return
        }
        val price = stock.quotePrice ?: stock.candles.lastOrNull()?.close ?: return
        tile.value.text = NumberFormat.getNumberInstance(trLocale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(price)
        if (result.isCached) {
            val time = if (stock.dataTimestamp > 0L) SimpleDateFormat("HH:mm", trLocale).format(Date(stock.dataTimestamp)) else "—"
            tile.change.text = "SON BAŞARILI • $time"
            tile.change.setTextColor(getColor(R.color.text_muted))
            tile.spark.text = "CACHE"
            tile.spark.setTextColor(getColor(R.color.text_muted))
            return
        }
        val previous = stock.previousClose ?: stock.candles.dropLast(1).lastOrNull()?.close
        val change = previous?.takeIf { it > 0.0 }?.let { ((price - it) / it) * 100.0 }
        if (change == null || !change.isFinite()) {
            tile.change.text = "Yedek • Değişim —"
            tile.change.setTextColor(getColor(R.color.text_muted))
        } else {
            val up = change >= 0
            tile.change.text = "${if (up) "▲" else "▼"} ${String.format(trLocale, "%.2f%%", kotlin.math.abs(change))}"
            tile.change.setTextColor(getColor(if (up) R.color.green else R.color.red))
        }
        tile.spark.text = "YEDEK"
        tile.spark.setTextColor(getColor(R.color.text_muted))
    }

    private fun bindQuoteTile(
        tile: TileViews,
        quote: tr.borsatakip.v5.model.ViopQuote?,
        marker: HomeRealtimeSignalPolicy.Marker?
    ) {
        if (quote == null) {
            tile.value.text = "—"
            tile.change.text = "VERİ YOK"
            tile.change.setTextColor(getColor(R.color.text_muted))
            tile.spark.text = "SİNYAL —"
            tile.spark.setTextColor(getColor(R.color.text_muted))
            lastViopMarker = null
            return
        }
        bindMarketTile(tile, quote.price, quote.dailyChangePct, emptyList())
        tile.spark.text = when (marker?.direction) {
            "LONG" -> "▲ LONG ${marker.strength}/100"
            "SHORT" -> "▼ SHORT ${marker.strength}/100"
            "NEUTRAL" -> "NEUTRAL"
            else -> "SİNYAL —"
        }
        tile.spark.setTextColor(
            getColor(when (marker?.direction) {
                "LONG" -> R.color.green
                "SHORT" -> R.color.red
                else -> R.color.text_muted
            })
        )
        if (HomeRealtimeSignalPolicy.isMeaningfulChange(lastViopMarker, marker)) {
            pulseSignal(findViewById(R.id.marketViopCard))
        }
        lastViopMarker = marker
    }

    private fun bindMarketTile(tile: TileViews, price: Double, changePct: Double?, candles: List<Candle>) {
        tile.value.text = NumberFormat.getNumberInstance(trLocale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(price)

        if (changePct == null || !changePct.isFinite()) {
            tile.change.text = "Değişim —"
            tile.change.setTextColor(getColor(R.color.text_muted))
        } else {
            val up = changePct >= 0
            tile.change.text = "${if (up) "▲" else "▼"} ${String.format(trLocale, "%.2f%%", kotlin.math.abs(changePct))}"
            tile.change.setTextColor(getColor(if (up) R.color.green else R.color.red))
            tile.spark.setTextColor(getColor(if (up) R.color.green else R.color.red))
        }
        tile.spark.text = sparkline(candles.map { it.close })
    }

    private fun renderTodayOpportunities() {
        val empty = findViewById<TextView>(R.id.todayEmpty)
        val horizontal = findViewById<HorizontalScrollView>(R.id.todayHorizontal)
        val container = findViewById<LinearLayout>(R.id.todayOpportunityRows)
        container.removeAllViews()

        val dynamicState = DynamicMarketScanResultRepository.snapshot()
        val manualState = ManualScanResultRepository.snapshot()
        val currentFeed = HomeOpportunityFeedPolicy.resolve(
            dynamic = dynamicState,
            manual = manualState,
            selected = todayMode
        )
        val canRecoverProcessSnapshot = dynamicState.result == null &&
            manualState.items.isEmpty() && manualState.source in setOf("NONE", "CLEARED")
        val recovered = if (canRecoverProcessSnapshot && currentFeed.items.isEmpty()) {
            HomeOpportunitySnapshotStore(this).load()?.let { cached ->
                val filtered = when (todayMode) {
                    HomeOpportunityFilterPolicy.Filter.ALL -> cached.items
                    HomeOpportunityFilterPolicy.Filter.LONG -> cached.items.filter { it.trendDirection == "LONG" }
                    HomeOpportunityFilterPolicy.Filter.SHORT -> cached.items.filter { it.trendDirection == "SHORT" }
                }.sortedByDescending { it.finalStrength }
                cached.copy(items = filtered)
            }
        } else null
        val feed = recovered ?: currentFeed
        val top = feed.items.take(8)
        if (top.isEmpty()) {
            empty.text = when (feed.status) {
                HomeOpportunityFeedStatus.NO_DATA -> "VERİ YOK • Canlı veri doğrulanamadı."
                HomeOpportunityFeedStatus.ANALYSIS_UNAVAILABLE -> "ANALİZ DOĞRULANAMADI."
                HomeOpportunityFeedStatus.STALE -> "VERİ YOK • Son doğrulanmış tarama güncel değil."
                HomeOpportunityFeedStatus.NO_SIGNAL -> "Doğrulanmış fırsat bulunamadı."
                HomeOpportunityFeedStatus.READY,
                HomeOpportunityFeedStatus.PARTIAL -> when (todayMode) {
                    HomeOpportunityFilterPolicy.Filter.ALL -> "Doğrulanmış fırsat bulunamadı."
                    HomeOpportunityFilterPolicy.Filter.LONG -> "Doğrulanmış LONG fırsatı bulunamadı."
                    HomeOpportunityFilterPolicy.Filter.SHORT -> "Doğrulanmış SHORT fırsatı bulunamadı."
                }
            }
            empty.visibility = View.VISIBLE
            horizontal.visibility = View.GONE
            return
        }

        empty.visibility = View.GONE
        horizontal.visibility = View.VISIBLE
        container.addView(buildOpportunityHeader())
        top.forEachIndexed { index, opportunity -> container.addView(buildOpportunityRow(index + 1, opportunity, index)) }
    }

    private fun buildOpportunityHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(Color.rgb(3, 34, 53))
        }
        listOf(
            "#" to 38, "Hisse" to 84, "Puan" to 78, "Sinyal" to 120,
            "Risk" to 72, "Son Fiyat" to 94, "Değişim" to 92, "Mini Grafik" to 112
        ).forEach { (label, width) -> row.addView(tableText(label, width, true)) }
        return row
    }

    private fun buildOpportunityRow(rank: Int, x: HomeOpportunityFeedItem, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(if (index % 2 == 0) Color.rgb(3, 29, 46) else Color.rgb(4, 37, 57))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val detail = x.opportunity
                if (detail != null) {
                    AppSession.selected = detail
                    startActivity(Intent(this@MainActivity, StockDetailActivity::class.java).putExtra("opportunity", detail))
                } else {
                    // Dynamic scan rows contain validated signal metadata but not the full candle snapshot.
                    // Open the canonical scan result instead of fabricating missing detail fields.
                    startActivity(Intent(this@MainActivity, DynamicMarketScanActivity::class.java))
                }
            }
        }
        row.addView(tableText(rank.toString(), 38))
        row.addView(tableText(x.symbol, 84, true))
        row.addView(tableText("${x.finalStrength}/100", 78, true, getColor(R.color.yellow)))

        val signalColor = when (x.trendDirection) {
            "LONG" -> getColor(R.color.green)
            "SHORT" -> getColor(R.color.red)
            else -> getColor(R.color.text_secondary)
        }
        val signalText = when (x.trendDirection) {
            "LONG" -> "▲ LONG %${x.finalStrength}"
            "SHORT" -> "▼ SHORT %${x.finalStrength}"
            else -> "—"
        }
        row.addView(tableText(signalText, 120, true, signalColor).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(44, Color.red(signalColor), Color.green(signalColor), Color.blue(signalColor)))
                setStroke(dp(1), Color.argb(130, Color.red(signalColor), Color.green(signalColor), Color.blue(signalColor)))
            }
            gravity = Gravity.CENTER
        })
        row.addView(tableText(x.riskScore?.let { "$it/100" } ?: "—", 72))
        row.addView(tableText(String.format(trLocale, "%.2f", x.price), 94))
        val change = x.dailyChangePct
        val movementText = change?.takeIf { it.isFinite() }?.let { value ->
            "${if (value >= 0) "▲" else "▼"} ${String.format(trLocale, "%.2f%%", kotlin.math.abs(value))}"
        } ?: "—"
        val movementColor = when {
            change == null || !change.isFinite() -> getColor(R.color.text_secondary)
            change >= 0 -> getColor(R.color.green)
            else -> getColor(R.color.red)
        }
        row.addView(tableText(movementText, 92, true, movementColor))
        row.addView(tableText(sparkline(x.sparkValues), 112, true, movementColor))
        val normalizedSymbol = x.symbol.trim().uppercase(Locale.ROOT)
        if (pendingBlinkSymbols.remove(normalizedSymbol)) {
            row.post { pulseSignal(row) }
        }
        return row
    }

    private fun pulseSignal(view: View, transitions: Int = 6) {
        view.animate().cancel()
        view.alpha = 1f
        fun step(remaining: Int, dim: Boolean) {
            if (remaining <= 0 || isFinishing || isDestroyed) {
                view.alpha = 1f
                return
            }
            view.animate()
                .alpha(if (dim) 0.42f else 1f)
                .setDuration(220L)
                .withEndAction { step(remaining - 1, !dim) }
                .start()
        }
        step(transitions, true)
    }

    private fun tableText(text: String, widthDp: Int, bold: Boolean = false, color: Int = getColor(R.color.text_primary)): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), LinearLayout.LayoutParams.WRAP_CONTENT)
            this.text = text
            setTextColor(color)
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            setPadding(dp(4), 0, dp(4), 0)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun sparkline(raw: List<Double>): String {
        val values = raw.filter { it.isFinite() }.takeLast(14)
        if (values.size < 2) return "—"
        val min = values.minOrNull() ?: return "—"
        val max = values.maxOrNull() ?: return "—"
        if (max <= min) return "▅".repeat(values.size)
        val chars = charArrayOf('▁', '▂', '▃', '▄', '▅', '▆', '▇', '█')
        return values.joinToString("") { value ->
            val i = (((value - min) / (max - min)) * (chars.size - 1)).roundToInt().coerceIn(0, chars.lastIndex)
            chars[i].toString()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        // The backend contract accepts data up to 60 s old; refresh at half that window while Home is visible.
        private const val HOME_MARKET_REFRESH_MS = 30_000L
        private const val VIOP_SIGNAL_MIN_REFRESH_MS = 60_000L
        private const val VIOP_CONTRACT_CACHE_MS = 15 * 60_000L
        private const val STATE_TODAY_FILTER = "home_today_filter"
    }

}
