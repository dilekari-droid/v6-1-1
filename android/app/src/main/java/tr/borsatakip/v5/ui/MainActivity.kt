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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.BackendProvider
import tr.borsatakip.v5.data.BistIndexDataService
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.MarketCapabilityClient
import tr.borsatakip.v5.data.RealtimeScannerClient
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.ViopContractSelector
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.Stock
import tr.borsatakip.v5.worker.RealtimeAlertService
import tr.borsatakip.v5.worker.AutoScanScheduler
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : BaseActivity() {
    private enum class TodayMode { ALL, LONG, SHORT }
    private var todayMode: TodayMode = TodayMode.ALL
    private val trLocale = Locale("tr", "TR")
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000L)
        }
    }

    private data class TileViews(val value: TextView, val change: TextView, val spark: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupBottomNav()

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
        refreshMarketTiles()
        handleRealtimeAlertIntent(intent)
        val startupSettings = SettingsStore(this)
        if (startupSettings.notifications) RealtimeAlertService.start(this)
        if (startupSettings.autoScanEnabled) AutoScanScheduler.reconcile(this, allowForegroundStart = true)
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
        refreshMarketTiles()
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
        all.setOnClickListener { todayMode = TodayMode.ALL; updateTodayTabStyles(); renderTodayOpportunities() }
        long.setOnClickListener { todayMode = TodayMode.LONG; updateTodayTabStyles(); renderTodayOpportunities() }
        short.setOnClickListener { todayMode = TodayMode.SHORT; updateTodayTabStyles(); renderTodayOpportunities() }
        updateTodayTabStyles()
    }

    private fun updateTodayTabStyles() {
        listOf(
            Triple(R.id.todayAllTab, TodayMode.ALL, R.color.blue),
            Triple(R.id.todayLongTab, TodayMode.LONG, R.color.green),
            Triple(R.id.todayShortTab, TodayMode.SHORT, R.color.red)
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
    private fun refreshMarketTiles() {
        val strict = ProviderRouter(this)
        val settings = SettingsStore(this)
        lifecycleScope.launch {
            coroutineScope {
                val indexDeferred = async { BistIndexDataService(this@MainActivity).loadMany(listOf("XU100", "XU030")) }
                val bist100Deferred = async { indexDeferred.await()["XU100"] }
                val bist30Deferred = async { indexDeferred.await()["XU030"] }
                val usdDeferred = async {
                    strict.fetchExternalQuote("USDTRY", listOf("TRY=X")).stock
                }
                val goldDeferred = async {
                    // Spot XAU/USD tercih edilir; sağlayıcıda yoksa USD/ons cinsinden COMEX vadeli altın yalnız gösterim yedeğidir.
                    strict.fetchExternalQuote("XAUUSD", listOf("XAUUSD=X", "GC=F")).stock
                }
                val viop30Deferred = async {
                    if (!ProviderReadinessService.isValidHttps(settings.baseUrl)) return@async null
                    val backend = BackendProvider(this@MainActivity)
                    val contracts = backend.loadViop().getOrNull().orEmpty()
                    val contract = ViopContractSelector.candidates(contracts, underlying = "XU030", allowWatch = false)
                        .firstOrNull() ?: return@async null
                    backend.loadViopQuote(contract.symbol).getOrNull()
                }

                bindStockTile(
                    marketTile(R.id.marketBist100Value, R.id.marketBist100Change, R.id.marketBist100Spark),
                    bist100Deferred.await()
                )
                bindStockTile(
                    marketTile(R.id.marketBist30Value, R.id.marketBist30Change, R.id.marketBist30Spark),
                    bist30Deferred.await()
                )
                bindStockTile(
                    marketTile(R.id.marketUsdValue, R.id.marketUsdChange, R.id.marketUsdSpark),
                    usdDeferred.await()
                )
                bindStockTile(
                    marketTile(R.id.marketGoldValue, R.id.marketGoldChange, R.id.marketGoldSpark),
                    goldDeferred.await()
                )
                bindQuoteTile(
                    marketTile(R.id.marketViopValue, R.id.marketViopChange, R.id.marketViopSpark),
                    viop30Deferred.await()
                )
            }
        }
    }

    private fun bindStockTile(tile: TileViews, stock: Stock?) {
        if (stock == null) return
        val price = stock.quotePrice ?: stock.candles.lastOrNull()?.close ?: return
        val previous = stock.previousClose ?: stock.candles.dropLast(1).lastOrNull()?.close
        val change = previous?.takeIf { it > 0.0 }?.let { ((price - it) / it) * 100.0 }
        bindMarketTile(tile, price, change, stock.candles)
    }

    private fun bindQuoteTile(tile: TileViews, quote: tr.borsatakip.v5.model.ViopQuote?) {
        if (quote == null) {
            tile.value.text = "—"
            tile.change.text = "Dayanak tara"
            tile.change.setTextColor(getColor(R.color.text_muted))
            tile.spark.text = "→"
            tile.spark.setTextColor(getColor(R.color.text_muted))
            return
        }
        bindMarketTile(tile, quote.price, quote.dailyChangePct, emptyList())
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

        val filtered = when (todayMode) {
            TodayMode.ALL -> AppSession.lastOpportunities
            TodayMode.LONG -> AppSession.lastOpportunities.filter { it.direction.equals("LONG", true) }
            TodayMode.SHORT -> AppSession.lastOpportunities.filter { it.direction.equals("SHORT", true) }
        }
        val top = filtered
            .sortedWith(compareByDescending<Opportunity> { it.rankingScore }.thenBy { it.riskScore })
            .take(8)
        if (top.isEmpty()) {
            empty.text = when (todayMode) {
                TodayMode.ALL -> "Henüz gerçek tarama yapılmadı."
                TodayMode.LONG -> "Son taramada LONG yönlü hisse bulunmadı."
                TodayMode.SHORT -> "Son taramada SHORT yönlü hisse bulunmadı."
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

    private fun buildOpportunityRow(rank: Int, x: Opportunity, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(if (index % 2 == 0) Color.rgb(3, 29, 46) else Color.rgb(4, 37, 57))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AppSession.selected = x
                startActivity(Intent(this@MainActivity, StockDetailActivity::class.java).putExtra("opportunity", x))
            }
        }
        row.addView(tableText(rank.toString(), 38))
        row.addView(tableText(x.symbol.uppercase(Locale.ROOT), 84, true))
        row.addView(tableText("${x.rankingScore}/100", 78, true, getColor(R.color.yellow)))

        val dir = x.direction.uppercase(Locale.ROOT)
        val quality = if (dir == "SHORT") x.shortScore else x.longScore
        val signalColor = if (dir == "SHORT") getColor(R.color.red) else getColor(R.color.green)
        row.addView(tableText("${if (dir == "SHORT") "▼" else "▲"} $dir %$quality", 120, true, signalColor).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(44, Color.red(signalColor), Color.green(signalColor), Color.blue(signalColor)))
                setStroke(dp(1), Color.argb(130, Color.red(signalColor), Color.green(signalColor), Color.blue(signalColor)))
            }
            gravity = Gravity.CENTER
        })
        row.addView(tableText("${x.riskScore}/100", 72))
        row.addView(tableText(String.format(trLocale, "%.2f", x.price), 94))
        val change = x.dailyChangePct
        val up = change != null && change >= 0
        val movementText = change?.takeIf { it.isFinite() }?.let { value ->
            "${if (value >= 0) "▲" else "▼"} ${String.format(trLocale, "%.2f%%", kotlin.math.abs(value))}"
        } ?: "—"
        val movementColor = when {
            change == null || !change.isFinite() -> getColor(R.color.text_secondary)
            change >= 0 -> getColor(R.color.green)
            else -> getColor(R.color.red)
        }
        row.addView(tableText(movementText, 92, true, movementColor))
        row.addView(tableText(sparkline(x.candles.map { it.close }), 112, true, movementColor))
        return row
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
}
