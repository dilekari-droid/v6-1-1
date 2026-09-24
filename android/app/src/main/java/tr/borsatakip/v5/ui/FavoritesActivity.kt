package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.OpportunityEngine
import tr.borsatakip.v5.analysis.OpportunityPublicationPolicy
import tr.borsatakip.v5.analysis.TechnicalAnalyzer
import tr.borsatakip.v5.data.BistBootstrapCatalog
import tr.borsatakip.v5.data.BistSymbolMatcher
import tr.borsatakip.v5.data.WatchlistDataResolver
import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.favorites.BistWatchlistStore
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.Stock

class FavoritesActivity : BaseActivity() {
    private lateinit var repository: FavoriteRepository
    private lateinit var watchlistStore: BistWatchlistStore
    private lateinit var resolver: WatchlistDataResolver
    private lateinit var favoriteList: RecyclerView
    private lateinit var watchlist: RecyclerView
    private lateinit var favoriteSummary: TextView
    private lateinit var favoriteCount: TextView
    private lateinit var watchlistCount: TextView
    private lateinit var watchlistStatus: TextView
    private lateinit var favoriteEmptyState: View
    private lateinit var favoritesPanel: View
    private lateinit var bistPanel: View
    private lateinit var search: EditText
    private lateinit var suggestions: LinearLayout
    private lateinit var tabFavorites: TextView
    private lateinit var tabBistList: TextView

    private var symbolUniverse: List<String> = emptyList()
    private val knownNames = mutableMapOf<String, String>()
    private var activeTab = Tab.FAVORITES

    enum class Tab { FAVORITES, BIST_LIST }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)
        setupBottomNav()

        repository = FavoriteRepository.get(this)
        watchlistStore = BistWatchlistStore(this)
        resolver = WatchlistDataResolver(this)

        favoriteList = findViewById(R.id.favoriteList)
        watchlist = findViewById(R.id.bistWatchlist)
        favoriteSummary = findViewById(R.id.favoriteSummary)
        favoriteCount = findViewById(R.id.favoriteCount)
        watchlistCount = findViewById(R.id.watchlistCount)
        watchlistStatus = findViewById(R.id.watchlistStatus)
        favoriteEmptyState = findViewById(R.id.favoriteEmptyState)
        favoritesPanel = findViewById(R.id.favoritesPanel)
        bistPanel = findViewById(R.id.bistPanel)
        search = findViewById(R.id.bistSearch)
        suggestions = findViewById(R.id.bistSuggestions)
        tabFavorites = findViewById(R.id.tabFavorites)
        tabBistList = findViewById(R.id.tabBistList)

        favoriteList.layoutManager = LinearLayoutManager(this)
        watchlist.layoutManager = LinearLayoutManager(this)

        AppSession.lastOpportunities.forEach { opportunity ->
            opportunity.companyName?.takeIf { it.isNotBlank() }?.let { knownNames[FavoriteRepository.normalizeSymbol(opportunity.symbol)] = it }
        }

        tabFavorites.setOnClickListener { switchTab(Tab.FAVORITES) }
        tabBistList.setOnClickListener { switchTab(Tab.BIST_LIST) }
        findViewById<TextView>(R.id.openStocksSource).setOnClickListener { startActivity(Intent(this, StocksActivity::class.java)) }
        findViewById<TextView>(R.id.openOpportunities).setOnClickListener { startActivity(Intent(this, OpportunityActivity::class.java)) }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderSuggestions(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })

        switchTab(Tab.FAVORITES)
        loadSymbolUniverse()
    }

    override fun onResume() {
        super.onResume()
        if (!::repository.isInitialized) return
        lifecycleScope.launch {
            repository.migrateLegacyIfNeeded()
            refreshFavorites()
            refreshWatchlist()
        }
    }

    private fun switchTab(tab: Tab) {
        activeTab = tab
        favoritesPanel.visibility = if (tab == Tab.FAVORITES) View.VISIBLE else View.GONE
        bistPanel.visibility = if (tab == Tab.BIST_LIST) View.VISIBLE else View.GONE
        tabFavorites.alpha = if (tab == Tab.FAVORITES) 1f else 0.55f
        tabBistList.alpha = if (tab == Tab.BIST_LIST) 1f else 0.55f
        tabFavorites.setBackgroundColor(getColor(if (tab == Tab.FAVORITES) R.color.blue else R.color.chip_bg))
        tabBistList.setBackgroundColor(getColor(if (tab == Tab.BIST_LIST) R.color.blue else R.color.chip_bg))
        favoriteSummary.text = if (tab == Tab.FAVORITES) {
            "Hisse Taraması ve Fırsatlar ekranlarından seçtiğin favoriler."
        } else {
            "BIST'ten kendin seç: en fazla 20 hisse, otomatik sembol önerisi."
        }
        if (tab == Tab.BIST_LIST) lifecycleScope.launch { refreshWatchlist() }
    }

    private fun loadSymbolUniverse() {
        lifecycleScope.launch {
            val result = resolver.symbolUniverse()
            symbolUniverse = result.symbols
            val favoriteSeed = repository.getAll()
                .filter { it.market == "BIST" }
                .map { it.symbol }
                .filter { it in symbolUniverse }
            val automaticSeed = if (favoriteSeed.isNotEmpty()) {
                favoriteSeed
            } else {
                BistBootstrapCatalog.defaultWatchlist.filter { it in symbolUniverse }
            }
            val recovered = watchlistStore.seedIfEmpty(automaticSeed)
            watchlistStatus.text = if (symbolUniverse.isEmpty()) {
                "BIST sembol dizini kullanılamıyor."
            } else {
                buildString {
                    append("${symbolUniverse.size} sembol aramaya hazır • ${result.sourceLabel}")
                    if (recovered > 0) {
                        append(if (favoriteSeed.isNotEmpty()) " • $recovered favori geri alındı" else " • $recovered başlangıç hissesi otomatik eklendi")
                    }
                }
            }
            renderSuggestions(search.text?.toString().orEmpty())
            refreshWatchlist()
        }
    }

    private suspend fun refreshFavorites() {
        val favorites = repository.getAll().filter { it.market == "BIST" }
        val latestBySymbol = AppSession.lastOpportunities.associateBy { FavoriteRepository.normalizeSymbol(it.symbol) }
        val rows = coroutineScope {
            favorites.map { favorite ->
                async {
                    val opportunity = latestBySymbol[favorite.symbol]?.takeIf { latest ->
                        favorite.analysisIntervalMinutes == null ||
                            latest.analysisTimeframeMinutes == favorite.analysisIntervalMinutes
                    }
                    val fetch = runCatching { resolver.fetchDisplayStockResult(favorite.symbol) }.getOrNull()
                    val stock = fetch?.stock
                    stock?.companyName?.takeIf { it.isNotBlank() }?.let { knownNames[favorite.symbol] = it }
                    FavoriteAdapter.FavoriteRow(favorite, opportunity, stock, fetch?.error)
                }
            }.awaitAll()
        }

        favoriteCount.text = "${rows.size} favori"
        val isEmpty = rows.isEmpty()
        favoriteEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        favoriteList.visibility = if (isEmpty) View.GONE else View.VISIBLE
        favoriteList.adapter = FavoriteAdapter(
            items = rows,
            onRemove = { favorite ->
                lifecycleScope.launch {
                    repository.remove(favorite.symbol, favorite.market)
                    Toast.makeText(this@FavoritesActivity, "${favorite.symbol} favorilerden çıkarıldı", Toast.LENGTH_SHORT).show()
                    refreshFavorites()
                }
            },
            onOpen = { row -> openStock(row.favorite.symbol, row.opportunity, row.stock) }
        )
    }

    private suspend fun refreshWatchlist() {
        val symbols = watchlistStore.symbols()
        watchlistCount.text = "${symbols.size}/${BistWatchlistStore.MAX_ITEMS} seçili"
        if (symbols.isEmpty()) {
            watchlistStatus.text = if (symbolUniverse.isEmpty()) "BIST sembol dizini bekleniyor." else "Arama kutusundan hisse ekleyin."
            watchlist.adapter = BistWatchlistAdapter(emptyList(), {}, {})
            return
        }
        watchlistStatus.text = "Takip listesi piyasa verileriyle güncelleniyor..."
        val rows = coroutineScope {
            symbols.map { symbol ->
                async {
                    val fetch = runCatching { resolver.fetchDisplayStockResult(symbol) }.getOrNull()
                    val stock = fetch?.stock
                    stock?.companyName?.takeIf { it.isNotBlank() }?.let { knownNames[symbol] = it }
                    val note = if (stock == null) {
                        val near = BistSymbolMatcher.closest(symbolUniverse, symbol)
                        val suggestion = if (near.isEmpty()) null else "Öneri: ${near.joinToString("/")}"
                        listOfNotNull(fetch?.error ?: "Sembol/veri doğrulanamadı", suggestion).joinToString(" • ")
                    } else null
                    BistWatchlistAdapter.Row(symbol, stock, note)
                }
            }.awaitAll()
        }
        val ok = rows.count { it.stock != null }
        watchlistStatus.text = "$ok/${rows.size} hisse için veri alındı. Yedek kaynak gecikmeli olabilir; Alış/Satış yoksa '—' gösterilir."
        watchlist.adapter = BistWatchlistAdapter(
            rows,
            onOpen = { row -> openStock(row.symbol, null, row.stock) },
            onRemove = { symbol ->
                watchlistStore.remove(symbol)
                lifecycleScope.launch { refreshWatchlist() }
            }
        )
    }

    private fun renderSuggestions(raw: String) {
        val query = raw.trim().uppercase()
        suggestions.removeAllViews()
        if (query.isBlank()) {
            suggestions.visibility = View.GONE
            return
        }
        val selected = watchlistStore.symbols().toSet()
        val matches = BistSymbolMatcher.matches(
            symbols = symbolUniverse,
            names = knownNames,
            rawQuery = query,
            selected = selected,
            limit = 8
        )
        if (matches.isEmpty()) {
            suggestions.visibility = View.GONE
            return
        }
        suggestions.visibility = View.VISIBLE
        matches.forEach { symbol -> suggestions.addView(buildSuggestionView(symbol)) }
    }

    private fun buildSuggestionView(symbol: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(4), dp(4))
        }
        val label = TextView(this).apply {
            text = buildString {
                append(symbol)
                knownNames[symbol]?.let { append("  •  ").append(it) }
            }
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL }
            gravity = Gravity.CENTER_VERTICAL
        }
        val add = Button(this).apply {
            text = "+"
            textSize = 20f
            minWidth = 0
            minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(48))
            setOnClickListener { addToWatchlist(symbol) }
        }
        row.addView(label)
        row.addView(add)
        row.setOnClickListener { addToWatchlist(symbol) }
        return row
    }

    private fun addToWatchlist(symbol: String) {
        when (watchlistStore.add(symbol)) {
            BistWatchlistStore.AddResult.ADDED -> {
                search.setText("")
                Toast.makeText(this, "$symbol takip listesine eklendi", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch { refreshWatchlist() }
            }
            BistWatchlistStore.AddResult.ALREADY_EXISTS -> Toast.makeText(this, "$symbol zaten listede", Toast.LENGTH_SHORT).show()
            BistWatchlistStore.AddResult.LIMIT_REACHED -> Toast.makeText(this, "Takip listesi en fazla 20 hisse olabilir", Toast.LENGTH_LONG).show()
            BistWatchlistStore.AddResult.INVALID -> Toast.makeText(this, "Geçersiz hisse sembolü", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openStock(symbol: String, existing: Opportunity?, prefetched: Stock?) {
        Toast.makeText(this, "$symbol fiyatı yenileniyor...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val stock = prefetched ?: runCatching { resolver.fetchDisplayStockResult(symbol).stock }.getOrNull()
            if (existing != null) {
                // Existing opportunity is the decision snapshot; latest Stock is passed separately only for display.
                openOpportunity(existing, stock)
                return@launch
            }
            if (stock == null) {
                Toast.makeText(this@FavoritesActivity, "$symbol için piyasa verisi alınamadı", Toast.LENGTH_LONG).show()
                return@launch
            }
            val integrity = RealTimeIntegrityPolicy.validate(stock)
            val scored = OpportunityEngine.score(stock)
            val opportunity = when {
                scored == null -> fallbackOpportunity(stock)
                integrity.accepted -> scored
                else -> OpportunityPublicationPolicy.delayedObservation(scored, integrity.reason)
            }
            openOpportunity(opportunity, stock)
        }
    }

    private fun openOpportunity(opportunity: Opportunity, displayStock: Stock? = null) {
        AppSession.selected = opportunity
        val intent = Intent(this, StockDetailActivity::class.java).putExtra("opportunity", opportunity)
        displayStock?.let { stock ->
            val displayPrice = stock.quotePrice ?: stock.candles.lastOrNull()?.close
            val displayPrev = stock.previousClose
            val displayChange = if (displayPrice != null && displayPrev != null && displayPrice.isFinite() && displayPrev.isFinite() && displayPrice > 0.0 && displayPrev > 0.0) {
                (displayPrice / displayPrev - 1.0) * 100.0
            } else Double.NaN
            displayPrice?.takeIf { it.isFinite() && it > 0.0 }?.let { intent.putExtra("displayPrice", it) }
            if (displayChange.isFinite()) intent.putExtra("displayChangePct", displayChange)
            intent.putExtra("displaySource", stock.source)
            intent.putExtra("displayRealtime", stock.isRealtime)
            intent.putExtra("displayTimestamp", stock.dataTimestamp)
        }
        startActivity(intent)
    }

    private fun fallbackOpportunity(stock: Stock): Opportunity {
        val candles = stock.candles.sortedBy { it.timestamp }
        val price = stock.quotePrice ?: candles.lastOrNull()?.close ?: 0.0
        val prev = stock.previousClose
        val change = if (prev != null && prev > 0.0 && price > 0.0) (price / prev - 1.0) * 100.0 else 0.0
        val t = TechnicalAnalyzer.analyze(candles)
        val sourceLower = stock.source.lowercase()
        val mode = when {
            stock.isRealtime -> DataMode.REALTIME
            sourceLower.contains("yahoo") || sourceLower.contains("gecik") -> DataMode.DELAYED
            else -> DataMode.UNVERIFIED
        }
        return Opportunity(
            symbol = stock.symbol,
            companyName = stock.companyName,
            price = price,
            dailyChangePct = change,
            score = 0,
            riskScore = 0,
            direction = "NEUTRAL",
            technicalLabel = "Detay görünümü",
            volumeLabel = t.volumeRatio?.let { "%.2fx".format(it) } ?: "Veri yok",
            kapLabel = "Veri yok",
            liquidityLabel = "Detay için açıldı",
            support = t.support,
            resistance = t.resistance,
            source = stock.source,
            dataTimestamp = stock.dataTimestamp,
            candles = candles,
            technical = t,
            dataConfidenceScore = if (candles.size >= 200) 70 else 45,
            dataConfidenceLabel = if (candles.size >= 200) "Orta" else "Sınırlı",
            finalSignalScore = 0,
            isRealtime = stock.isRealtime,
            delaySeconds = stock.delaySeconds,
            currentSessionIncluded = stock.currentSessionIncluded,
            exchangeTimestamp = stock.exchangeTimestamp,
            receivedAt = stock.receivedAt,
            receivedElapsedRealtime = stock.receivedElapsedRealtime,
            signalGeneratedAt = System.currentTimeMillis(),
            dataMode = mode,
            signalValidity = SignalValidity.WATCH,
            signalValidityReason = "Manuel takip listesinden açıldı; fırsat sinyali üretilmedi. Gerçek OHLCV ve teknik göstergeler gösteriliyor."
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
