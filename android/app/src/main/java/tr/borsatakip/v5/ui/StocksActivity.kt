package tr.borsatakip.v5.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.OpportunityDirectionalFilterPolicy
import tr.borsatakip.v5.data.MarketDataQuality
import tr.borsatakip.v5.data.BistIndexDataService
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.Stock
import java.util.Locale

class StocksActivity : BaseActivity() {
    private lateinit var repo: FavoriteRepository
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private var query: String = ""
    private var filter: UiFilter = UiFilter.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stocks)
        setupBottomNav()

        repo = FavoriteRepository.get(this)
        list = findViewById(R.id.stocksList)
        status = findViewById(R.id.stocksStatus)
        list.layoutManager = LinearLayoutManager(this)

        findViewById<TextView>(R.id.stocksFavorites).setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }
        findViewById<TextView>(R.id.stocksSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.stocksNotifications).setOnClickListener {
            startActivity(Intent(this, NotificationsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.stocksScanCard).setOnClickListener {
            startActivity(Intent(this, BistScanActivity::class.java))
        }
        findViewById<TextView>(R.id.stocksScanAction).setOnClickListener {
            startActivity(Intent(this, BistScanActivity::class.java))
        }

        bindSearch()
        bindFilters()
        renderIndexCards(AppSession.lastOpportunities)

        lifecycleScope.launch {
            repo.migrateLegacyIfNeeded()
            bindList()
            refreshIndexCards()
        }
    }

    private fun bindSearch() {
        findViewById<EditText>(R.id.stocksSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty().trim()
                lifecycleScope.launch { bindList() }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun bindFilters() {
        listOf(
            R.id.stocksFilterAll to UiFilter.ALL,
            R.id.stocksFilterLong to UiFilter.LONG,
            R.id.stocksFilterShort to UiFilter.SHORT
        ).forEach { (id, selectedFilter) ->
            findViewById<Button>(id).setOnClickListener {
                filter = selectedFilter
                updateFilterColors()
                lifecycleScope.launch { bindList() }
            }
        }

        val unsupported = listOf(
            R.id.stocksFilterBist30 to "BIST 30",
            R.id.stocksFilterBank to "Bankacılık",
            R.id.stocksFilterIndustry to "Sanayi",
            R.id.stocksFilterTech to "Teknoloji"
        )
        unsupported.forEach { (id, label) ->
            findViewById<Button>(id).setOnClickListener {
                status.text = "$label üyelik/sınıflandırma verisi mevcut veri modelinde doğrulanmadığı için filtre uygulanmadı."
            }
        }
        updateFilterColors()
    }

    private fun updateFilterColors() {
        val buttons = listOf(
            UiFilter.ALL to findViewById<Button>(R.id.stocksFilterAll),
            UiFilter.LONG to findViewById<Button>(R.id.stocksFilterLong),
            UiFilter.SHORT to findViewById<Button>(R.id.stocksFilterShort)
        )
        buttons.forEach { (kind, button) ->
            val selected = kind == filter
            val color = when {
                !selected -> getColor(R.color.chip_bg)
                kind == UiFilter.SHORT -> getColor(R.color.red)
                else -> getColor(R.color.green)
            }
            button.backgroundTintList = ColorStateList.valueOf(color)
            button.setTextColor(if (selected) Color.WHITE else getColor(R.color.text_primary))
        }
    }

    private suspend fun bindList() {
        if (!::repo.isInitialized || !::list.isInitialized) return
        val source = AppSession.lastOpportunities
        val normalizedQuery = query.lowercase(Locale.getDefault())
        val requestedDirection = when (filter) {
            UiFilter.LONG -> OpportunityDirectionalFilterPolicy.Direction.LONG
            UiFilter.SHORT -> OpportunityDirectionalFilterPolicy.Direction.SHORT
            UiFilter.ALL -> OpportunityDirectionalFilterPolicy.Direction.NEUTRAL
        }
        val filtered = source.asSequence().filter { item ->
            val searchMatch = normalizedQuery.isBlank() || item.symbol.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
                item.companyName.orEmpty().lowercase(Locale.getDefault()).contains(normalizedQuery)
            val effective = OpportunityDirectionalFilterPolicy.effectiveDirection(item)
            val filterMatch = when (filter) {
                UiFilter.ALL -> true
                UiFilter.LONG -> effective == OpportunityDirectionalFilterPolicy.Direction.LONG
                UiFilter.SHORT -> effective == OpportunityDirectionalFilterPolicy.Direction.SHORT
            }
            searchMatch && filterMatch
        }.sortedWith(
            compareByDescending<Opportunity> {
                if (filter == UiFilter.ALL) it.rankingScore else OpportunityDirectionalFilterPolicy.scoreFor(it, requestedDirection)
            }.thenBy { it.symbol.uppercase(Locale.ROOT) }
        ).toList()
        val favorites = repo.symbols()
        list.adapter = StocksCompactAdapter(
            filtered,
            favorites,
            click = { item ->
                AppSession.selected = item
                startActivity(Intent(this, StockDetailActivity::class.java).putExtra("opportunity", item))
            },
            toggleFavorite = { item ->
                lifecycleScope.launch {
                    repo.toggle(item.symbol, item.companyName)
                    bindList()
                }
            }
        )

        val route = ProviderRouter.routingStatus()
        val providerText = if (route.activeProviderId == "none") "Provider: doğrulanmadı" else
            "Provider: ${route.activeProviderLabel} • ${route.dataState}"
        status.text = when {
            source.isEmpty() -> "Veri yok • Gerçek BIST taraması çalıştırıldığında sonuçlar burada gösterilir. • $providerText"
            filtered.isEmpty() -> "${source.size} analiz sonucu içinde bu arama/filtreye uyan LONG/SHORT eğilimi yok. • $providerText"
            else -> "${filtered.size} sonuç • ${filter.label} • skor yüksekten düşüğe • $providerText"
        }
        renderIndexCards(source)
    }

    private suspend fun refreshIndexCards() {
        val stocks = withContext(Dispatchers.IO) {
            BistIndexDataService(this@StocksActivity).loadMany(listOf("XU100", "XU030", "XUTUM"))
        }
        stocks["XU100"]?.let { bindIndexStock(it, R.id.index100Value, R.id.index100Change, R.id.index100Chart) }
        stocks["XU030"]?.let { bindIndexStock(it, R.id.index30Value, R.id.index30Change, R.id.index30Chart) }
        stocks["XUTUM"]?.let { bindIndexStock(it, R.id.indexAllValue, R.id.indexAllChange, R.id.indexAllChart) }
        if (::repo.isInitialized && ::list.isInitialized) bindList()
    }

    private fun bindIndexStock(stock: Stock, valueId: Int, changeId: Int, chartId: Int) {
        val value = findViewById<TextView>(valueId)
        val change = findViewById<TextView>(changeId)
        val chart = findViewById<StockSparklineView>(chartId)
        val price = stock.quotePrice ?: stock.candles.lastOrNull()?.close
        val previous = stock.previousClose ?: stock.candles.getOrNull(stock.candles.lastIndex - 1)?.close
        val pct = if (price != null && previous != null && price.isFinite() && previous.isFinite() && previous > 0.0) {
            ((price / previous) - 1.0) * 100.0
        } else null
        if (price == null || !price.isFinite() || price <= 0.0) return
        value.text = if (price >= 1000.0) "%,.2f".format(Locale.getDefault(), price) else "%.2f".format(Locale.getDefault(), price)
        val state = MarketDataQuality.uiStatus(stock.marketDataMetadata)
        change.text = listOfNotNull(pct?.let { "%+.2f%%".format(Locale.getDefault(), it) }, state).joinToString(" • ")
        change.setTextColor(
            when {
                stock.marketDataMetadata?.isOffline == true -> getColor(R.color.text_secondary)
                pct == null -> getColor(R.color.text_secondary)
                pct > 0.0 -> getColor(R.color.green)
                pct < 0.0 -> getColor(R.color.red)
                else -> getColor(R.color.text_secondary)
            }
        )
        chart.setCandles(stock.candles, pct)
    }

    private fun renderIndexCards(items: List<Opportunity>) {
        bindIndexCard(items.findByAnySymbol("XU100", "BIST100"), R.id.index100Value, R.id.index100Change, R.id.index100Chart)
        bindIndexCard(items.findByAnySymbol("XU030", "BIST30"), R.id.index30Value, R.id.index30Change, R.id.index30Chart)
        bindIndexCard(items.findByAnySymbol("XUTUM", "BISTTUM", "XTUMY"), R.id.indexAllValue, R.id.indexAllChange, R.id.indexAllChart)
    }

    private fun bindIndexCard(item: Opportunity?, valueId: Int, changeId: Int, chartId: Int) {
        val value = findViewById<TextView>(valueId)
        val change = findViewById<TextView>(changeId)
        val chart = findViewById<StockSparklineView>(chartId)
        val pct = item?.dailyChangePct?.takeIf(Double::isFinite)
        if (item == null || !item.price.isFinite() || item.price <= 0.0) {
            value.text = "Veri yok"
            change.text = "—"
            change.setTextColor(getColor(R.color.text_secondary))
            chart.setCandles(emptyList(), null)
            return
        }
        value.text = if (item.price >= 1000.0) "%,.2f".format(Locale.getDefault(), item.price) else "%.2f".format(Locale.getDefault(), item.price)
        change.text = pct?.let { "%+.2f%%".format(Locale.getDefault(), it) } ?: "—"
        change.setTextColor(
            when {
                pct == null -> getColor(R.color.text_secondary)
                pct > 0.0 -> getColor(R.color.green)
                pct < 0.0 -> getColor(R.color.red)
                else -> getColor(R.color.text_secondary)
            }
        )
        chart.setCandles(item.candles, pct)
    }

    private fun List<Opportunity>.findByAnySymbol(vararg symbols: String): Opportunity? {
        val wanted = symbols.map { it.uppercase(Locale.ROOT) }.toSet()
        return firstOrNull { it.symbol.trim().uppercase(Locale.ROOT) in wanted }
    }

    override fun onResume() {
        super.onResume()
        if (::repo.isInitialized && ::list.isInitialized) {
            lifecycleScope.launch {
                bindList()
                refreshIndexCards()
            }
        }
    }

    private enum class UiFilter(val label: String) {
        ALL("Tümü"), LONG("LONG"), SHORT("SHORT")
    }
}
