package tr.borsatakip.v5.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.ViopContractCategory
import tr.borsatakip.v5.analysis.ViopContractCategoryPolicy
import tr.borsatakip.v5.analysis.ViopContractSearch
import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.ProviderState
import tr.borsatakip.v5.data.ViopBuiltinContractCatalog
import tr.borsatakip.v5.data.ViopRepository
import tr.borsatakip.v5.data.ViopWatchlistStore
import tr.borsatakip.v5.data.WatchlistDataResolver
import tr.borsatakip.v5.model.Stock
import tr.borsatakip.v5.model.ViopContract
import java.util.Locale

class ViopContractsActivity : BaseActivity() {
    private lateinit var list: RecyclerView
    private lateinit var suggestions: RecyclerView
    private lateinit var suggestionPanel: LinearLayout
    private lateinit var suggestionTitle: TextView
    private lateinit var suggestionCount: TextView
    private lateinit var watchTitle: TextView
    private lateinit var watchCount: TextView
    private lateinit var watchStatus: TextView
    private lateinit var addButton: Button
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var watchlistStore: ViopWatchlistStore

    private var all: List<ViopContract> = emptyList()
    private var category = ViopContractCategory.BIST30
    private var query = ""
    private var addMode = false
    private var providerWarning: String? = null
    private val underlyingStocks = mutableMapOf<String, Stock>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viop_contracts)
        setupBottomNav()

        watchlistStore = ViopWatchlistStore(this)
        list = findViewById(R.id.viopContractsList)
        suggestions = findViewById(R.id.viopSuggestions)
        suggestionPanel = findViewById(R.id.viopSuggestionPanel)
        suggestionTitle = findViewById(R.id.viopSuggestionTitle)
        suggestionCount = findViewById(R.id.viopSuggestionCount)
        watchTitle = findViewById(R.id.viopWatchTitle)
        watchCount = findViewById(R.id.viopWatchCount)
        watchStatus = findViewById(R.id.viopWatchStatus)
        addButton = findViewById(R.id.viopAddButton)
        search = findViewById(R.id.viopSearch)
        status = findViewById(R.id.viopStatus)

        list.layoutManager = LinearLayoutManager(this)
        suggestions.layoutManager = LinearLayoutManager(this)

        categoryButtons().forEach { (id, selectedCategory) ->
            findViewById<Button>(id).setOnClickListener { switchCategory(selectedCategory) }
        }

        addButton.setOnClickListener {
            if (addMode) closeAddMode() else openAddMode()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty().trim()
                if (addMode) updateSuggestions()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        renderCategoryUi()
        bindWatchlist()
        load()
    }

    private fun categoryButtons() = mapOf(
        R.id.viopBist30 to ViopContractCategory.BIST30,
        R.id.viopFx to ViopContractCategory.FX,
        R.id.viopCommodity to ViopContractCategory.COMMODITY,
        R.id.viopOther to ViopContractCategory.OTHER
    )

    private fun switchCategory(next: ViopContractCategory) {
        if (category == next) return
        closeAddMode(clearFocus = true)
        category = next
        renderCategoryUi()
        bindWatchlist()
    }

    private fun renderCategoryUi() {
        categoryButtons().forEach { (id, buttonCategory) ->
            val button = findViewById<Button>(id)
            val selected = category == buttonCategory
            button.alpha = if (selected) 1f else 0.86f
            button.backgroundTintList = ColorStateList.valueOf(
                getColor(if (selected) categoryAccent(buttonCategory) else R.color.chip_bg)
            )
        }
        addButton.backgroundTintList = ColorStateList.valueOf(getColor(categoryAccent(category)))
        watchTitle.text = "${categoryDisplayName(category)} Takip Listem"
        refreshProviderStatus()
    }

    private fun categoryDisplayName(value: ViopContractCategory): String = when (value) {
        ViopContractCategory.BIST30 -> "BIST30"
        ViopContractCategory.FX -> "Döviz"
        ViopContractCategory.COMMODITY -> "Emtia"
        ViopContractCategory.OTHER -> "Diğer"
        ViopContractCategory.ALL -> "VİOP"
    }

    private fun categoryAccent(value: ViopContractCategory): Int = when (value) {
        ViopContractCategory.BIST30 -> R.color.blue
        ViopContractCategory.FX -> R.color.green
        ViopContractCategory.COMMODITY -> R.color.orange
        ViopContractCategory.OTHER -> R.color.purple
        ViopContractCategory.ALL -> R.color.blue
    }

    private fun openAddMode() {
        addMode = true
        addButton.text = "Kapat"
        search.visibility = View.VISIBLE
        suggestionPanel.visibility = View.VISIBLE
        search.requestFocus()
        updateSuggestions()
        refreshProviderStatus()
        search.post {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeAddMode(clearFocus: Boolean = false) {
        addMode = false
        addButton.text = "+ Ekle"
        query = ""
        if (search.text.isNotEmpty()) search.setText("")
        search.visibility = View.GONE
        suggestionPanel.visibility = View.GONE
        suggestions.adapter = emptySuggestionAdapter()
        if (clearFocus) search.clearFocus()
        refreshProviderStatus()
    }

    private fun load() {
        status.visibility = View.VISIBLE
        status.text = "VİOP sözleşme evreni yükleniyor…"
        lifecycleScope.launch {
            val repository = ViopRepository(this@ViopContractsActivity)
            val localItems = repository.loadLocal()
            val readiness = ProviderReadinessService(this@ViopContractsActivity).localConfigState()

            if (readiness.state != ProviderState.PROVIDER_READY) {
                all = mergeWithBuiltinCatalog(localItems)
                providerWarning = if (localItems.isNotEmpty()) {
                    "Production backend bağlı değil • ${localItems.size} yerel sözleşme + katalog kullanılabilir."
                } else {
                    "Production backend bağlı değil • Katalog sonuçları fiyat/veri olarak doğrulanmamıştır."
                }
                migrateLegacyWatchlistIfNeeded()
                seedAutomaticWatchlistsIfEmpty()
                renderCategoryUi()
                bindWatchlist()
                refreshUnderlyingReferences()
                if (addMode) updateSuggestions()
                return@launch
            }

            val out = repository.refreshDetailed()
            all = mergeWithBuiltinCatalog(out.productionItems + out.manualItems)
            providerWarning = null
            migrateLegacyWatchlistIfNeeded()
            seedAutomaticWatchlistsIfEmpty()
            renderCategoryUi()
            bindWatchlist()
            refreshUnderlyingReferences()
            if (addMode) updateSuggestions()
        }
    }

    private fun migrateLegacyWatchlistIfNeeded() {
        if (watchlistStore.isCategoryMigrationComplete()) return
        val legacy = watchlistStore.legacySymbols()
        if (legacy.isEmpty()) {
            watchlistStore.migrateLegacy(emptyMap())
            return
        }
        val bySymbol = all.associateBy { it.symbol.uppercase(Locale.ROOT) }
        val assignments = legacy.groupBy { symbol ->
            bySymbol[symbol.uppercase(Locale.ROOT)]
                ?.let(ViopContractCategoryPolicy::classify)
                ?.takeIf { it != ViopContractCategory.ALL }
                ?: ViopContractCategory.OTHER
        }
        watchlistStore.migrateLegacy(assignments)
    }

    private fun seedAutomaticWatchlistsIfEmpty() {
        ViopWatchlistStore.WATCHLIST_CATEGORIES.forEach { c ->
            val symbols = ViopContractCategoryPolicy.filter(all, c)
                .sortedWith(compareBy<ViopContract> { it.expiryAt ?: Long.MAX_VALUE }.thenBy { it.symbol })
                .map { it.symbol }
            watchlistStore.seedIfEmpty(c, symbols)
        }
    }

    private fun mergeWithBuiltinCatalog(primary: List<ViopContract>): List<ViopContract> =
        (primary + ViopBuiltinContractCatalog.current())
            .distinctBy { it.symbol.uppercase(Locale.ROOT) }

    private fun filteredByCategory(source: List<ViopContract>): List<ViopContract> =
        ViopContractCategoryPolicy.filter(source, category)

    private fun refreshProviderStatus() {
        val warning = providerWarning
        if (addMode && !warning.isNullOrBlank()) {
            status.text = warning
            status.visibility = View.VISIBLE
        } else {
            status.visibility = View.GONE
        }
    }

    private fun updateSuggestions() {
        if (!addMode) {
            suggestionPanel.visibility = View.GONE
            return
        }
        val q = query.trim()
        val filtered = filteredByCategory(all)
        val candidates = if (q.isEmpty()) {
            ViopContractSearch.browse(filtered, limit = 20)
        } else {
            ViopContractSearch.search(filtered, q, limit = 20)
        }

        suggestionPanel.visibility = View.VISIBLE
        suggestionTitle.text = when {
            candidates.isEmpty() && q.isEmpty() -> "${categoryDisplayName(category)} • kontrat bulunamadı"
            candidates.isEmpty() -> "'$q' için eşleşme yok"
            q.isEmpty() -> "${categoryDisplayName(category)} • Kontrat Seç"
            else -> "${categoryDisplayName(category)} • '$q'"
        }
        suggestionCount.text = "${candidates.size} sonuç"
        val selected = watchlistStore.symbols(category).map { it.uppercase(Locale.ROOT) }.toSet()
        suggestions.adapter = ViopSuggestionAdapter(
            candidates,
            selectedSymbols = selected,
            onAdd = { addToWatchlist(it) },
            onOpen = { openContract(it) }
        )
    }

    private fun emptySuggestionAdapter() = ViopSuggestionAdapter(
        emptyList(), emptySet(), onAdd = {}, onOpen = {}
    )

    private fun addToWatchlist(contract: ViopContract) {
        // Search results are already category-filtered, but enforce it again at mutation boundary.
        if (ViopContractCategoryPolicy.classify(contract) != category) {
            Toast.makeText(this, "Bu kontrat ${categoryDisplayName(category)} kategorisine ait değil", Toast.LENGTH_SHORT).show()
            return
        }
        when (watchlistStore.add(category, contract.symbol)) {
            ViopWatchlistStore.AddResult.ADDED -> {
                Toast.makeText(this, "${contract.symbol} ${categoryDisplayName(category)} listesine eklendi", Toast.LENGTH_SHORT).show()
                bindWatchlist()
                closeAddMode(clearFocus = true)
            }
            ViopWatchlistStore.AddResult.ALREADY_EXISTS ->
                Toast.makeText(this, "${contract.symbol} zaten bu takip listesinde", Toast.LENGTH_SHORT).show()
            ViopWatchlistStore.AddResult.LIMIT_REACHED ->
                Toast.makeText(this, "${categoryDisplayName(category)} listesi en fazla 20 kontrat olabilir", Toast.LENGTH_LONG).show()
            ViopWatchlistStore.AddResult.INVALID ->
                Toast.makeText(this, "Geçersiz kontrat sembolü", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindWatchlist() {
        val symbols = watchlistStore.symbols(category)
        watchTitle.text = "${categoryDisplayName(category)} Takip Listem"
        watchCount.text = "${symbols.size}/${ViopWatchlistStore.MAX_ITEMS}"

        val bySymbol = all.associateBy { it.symbol.uppercase(Locale.ROOT) }
        val rows = symbols.map { symbol ->
            val contract = bySymbol[symbol.uppercase(Locale.ROOT)]
            ViopWatchlistAdapter.Row(symbol, contract, contract?.underlying?.let { underlyingStocks[it.uppercase(Locale.ROOT)] })
        }
        watchStatus.text = when {
            rows.isEmpty() -> "${categoryDisplayName(category)} listeniz boş • + Ekle ile kontrat seçin."
            else -> "${categoryDisplayName(category)} sekmesi seçili • ${rows.size}/20 kontrat bu listede korunur."
        }
        watchStatus.setTextColor(getColor(categoryAccent(category)))

        list.adapter = ViopWatchlistAdapter(
            rows,
            onOpen = { row ->
                row.contract?.let(::openContract)
                    ?: Toast.makeText(this, "${row.symbol} için güncel sözleşme verisi henüz yok", Toast.LENGTH_SHORT).show()
            },
            onRemove = { symbol ->
                watchlistStore.remove(category, symbol)
                Toast.makeText(this, "$symbol ${categoryDisplayName(category)} listesinden çıkarıldı", Toast.LENGTH_SHORT).show()
                bindWatchlist()
                if (addMode) updateSuggestions()
            }
        )
    }

    private fun refreshUnderlyingReferences() {
        val contracts = watchlistStore.symbols(category)
            .mapNotNull { symbol -> all.firstOrNull { it.symbol.equals(symbol, true) } }
        val underlyings = contracts.map { it.underlying.uppercase(Locale.ROOT) }
            .filter { it.matches(Regex("[A-Z0-9_]{3,12}")) && it != "XU030" }
            .distinct()
        if (underlyings.isEmpty()) return
        lifecycleScope.launch {
            val resolver = WatchlistDataResolver(this@ViopContractsActivity)
            underlyings.forEach { underlying ->
                val stock = runCatching { resolver.fetchDisplayStock(underlying) }.getOrNull()
                if (stock != null) underlyingStocks[underlying] = stock
            }
            bindWatchlist()
            watchStatus.text = if (underlyingStocks.isEmpty()) {
                "${categoryDisplayName(category)} • gerçek VİOP fiyatı yok; dayanak referansı da alınamadı."
            } else {
                "${categoryDisplayName(category)} • VİOP kontrat fiyatı yok; ${underlyingStocks.size} dayanak referansı gösteriliyor."
            }
        }
    }

    private fun openContract(contract: ViopContract) {
        AppSession.selectedViopOpportunity = null
        AppSession.selectedViopUnderlyingOpportunity = null
        AppSession.selectedViopContract = contract
        startActivity(Intent(this, ViopDetailActivity::class.java))
    }
}
