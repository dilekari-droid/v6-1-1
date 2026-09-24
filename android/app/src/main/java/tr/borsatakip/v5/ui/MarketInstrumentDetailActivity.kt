package tr.borsatakip.v5.ui

import tr.borsatakip.v5.data.ProviderReadinessService
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.BackendProvider
import tr.borsatakip.v5.data.BistIndexDataService
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.ViopContractSelector
import tr.borsatakip.v5.model.Stock
import tr.borsatakip.v5.model.ViopQuote
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MarketInstrumentDetailActivity : BaseActivity() {
    private val trLocale = Locale("tr", "TR")
    private lateinit var instrument: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_market_instrument_detail)
        setupBottomNav()
        instrument = intent.getStringExtra(EXTRA_INSTRUMENT).orEmpty().uppercase(Locale.ROOT)
        findViewById<Button>(R.id.marketDetailRefresh).setOnClickListener { load() }
        findViewById<Button>(R.id.marketDetailSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        load()
    }

    private fun load() {
        setLoading()
        lifecycleScope.launch {
            when (instrument) {
                "BIST100" -> loadStock("BIST 100", "XU100", listOf("XU100.IS", "^XU100"))
                "BIST30" -> loadStock("BIST 30", "XU030", listOf("XU030.IS", "^XU030", "XU030"))
                "USDTRY" -> loadExternal("DOLAR / TL", "USDTRY", listOf("TRY=X"))
                "XAUUSD" -> loadExternal("ONS ALTIN", "XAUUSD", listOf("XAUUSD=X", "GC=F"))
                "VIOP30" -> loadViop30()
                else -> showError("Bilinmeyen piyasa kartı")
            }
        }
    }

    private suspend fun loadStock(title: String, strictSymbol: String, delayedSymbols: List<String>) {
        val stock = BistIndexDataService(this).load(strictSymbol)
        if (stock == null) showError("$title verisi alınamadı. Production benchmark OHLCV doğrulanamadı.") else bindStock(title, stock)
    }

    private suspend fun loadExternal(title: String, label: String, symbols: List<String>) {
        val result = ProviderRouter(this).fetchExternalQuote(label, symbols)
        val stock = result.stock
        if (stock == null) showError("$title verisi alınamadı. ${result.error ?: ""}".trim()) else bindStock(title, stock)
    }

    private suspend fun loadViop30() {
        val settings = SettingsStore(this)
        if (!ProviderReadinessService.isValidHttps(settings.baseUrl) || settings.apiKey.isBlank()) {
            return loadViop30Underlying("Production VİOP backend bağlı değil")
        }
        val backend = BackendProvider(this)
        val contract = ViopContractSelector.candidates(
            backend.loadViop().getOrNull().orEmpty(), underlying = "XU030", allowWatch = false
        ).firstOrNull()
        if (contract == null) {
            return loadViop30Underlying("XU030 dayanaklı production VİOP kontratı alınamadı")
        }
        val quote = backend.loadViopQuote(contract.symbol).getOrNull()
        if (quote == null) loadViop30Underlying("${contract.symbol} production VİOP quote alınamadı")
        else bindViop("VİOP 30 • ${contract.symbol}", quote)
    }

    private suspend fun loadViop30Underlying(reason: String) {
        val result = ProviderRouter(this).fetchExternalQuote("XU030", listOf("XU030.IS", "^XU030", "XU030"))
        val stock = result.stock
        if (stock == null) {
            showError("$reason. XU030 dayanak referansı da alınamadı. ${result.error ?: ""}".trim(), showSettings = true)
            return
        }
        bindStock("VİOP 30 • DAYANAK XU030", stock)
        findViewById<TextView>(R.id.marketDetailStats).text = buildString {
            append("BU DEĞER VİOP KONTRAT FİYATI DEĞİLDİR.\n")
            append("XU030 spot/dayanak referansıdır; VİOP kontrat fiyatı, hacim ve açık pozisyon üretilmez.\n")
            append("Neden: ").append(reason)
        }
        findViewById<Button>(R.id.marketDetailSettings).visibility = View.VISIBLE
    }

    private fun setLoading() {
        findViewById<TextView>(R.id.marketDetailTitle).text = titleFor(instrument)
        findViewById<TextView>(R.id.marketDetailValue).text = "—"
        findViewById<TextView>(R.id.marketDetailChange).text = "Veri alınıyor..."
        findViewById<TextView>(R.id.marketDetailMeta).text = "Kaynak doğrulanıyor"
        findViewById<TextView>(R.id.marketDetailStats).text = ""
        findViewById<Button>(R.id.marketDetailSettings).visibility = View.GONE
    }

    private fun bindStock(title: String, stock: Stock) {
        val price = stock.quotePrice ?: stock.candles.lastOrNull()?.close ?: run { showError("$title fiyatı boş döndü."); return }
        val previous = stock.previousClose ?: stock.candles.dropLast(1).lastOrNull()?.close
        val change = previous?.takeIf { it > 0.0 }?.let { ((price - it) / it) * 100.0 }
        bindCommon(title, price, change, stock.source, stock.dataTimestamp, stock.isRealtime, stock.delaySeconds)
        findViewById<TextView>(R.id.marketDetailStats).text = buildString {
            append("Önceki kapanış: ${previous?.let(::fmt) ?: "—"}\n")
            append("Alış: ${stock.bid?.let(::fmt) ?: "—"}   •   Satış: ${stock.ask?.let(::fmt) ?: "—"}\n")
            append("Para birimi: ${stock.currency ?: "—"}   •   Piyasa: ${stock.market}\n")
            append("Interval: ${stock.interval}   •   Mum sayısı: ${stock.candles.size}")
        }
    }

    private fun bindViop(title: String, quote: ViopQuote) {
        bindCommon(title, quote.price, quote.dailyChangePct, quote.source, quote.exchangeTimestamp, quote.realtime, quote.delaySeconds)
        findViewById<TextView>(R.id.marketDetailStats).text = buildString {
            append("Alış: ${quote.bid?.let(::fmt) ?: "—"}   •   Satış: ${quote.ask?.let(::fmt) ?: "—"}\n")
            append("Hacim: ${quote.volume?.let(::fmt) ?: "—"}   •   Açık pozisyon: ${quote.openInterest ?: "—"}\n")
            append("Current session: ${if (quote.currentSessionIncluded) "EVET" else "HAYIR"}")
        }
    }

    private fun bindCommon(title: String, price: Double, change: Double?, source: String, timestamp: Long, realtime: Boolean, delaySeconds: Int?) {
        findViewById<TextView>(R.id.marketDetailTitle).text = title
        findViewById<TextView>(R.id.marketDetailValue).text = fmt(price)
        val changeView = findViewById<TextView>(R.id.marketDetailChange)
        if (change == null || !change.isFinite()) {
            changeView.text = "Değişim —"
            changeView.setTextColor(getColor(R.color.text_secondary))
        } else {
            val up = change >= 0
            changeView.text = "${if (up) "▲" else "▼"} ${String.format(trLocale, "%.2f%%", abs(change))}"
            changeView.setTextColor(getColor(if (up) R.color.green else R.color.red))
        }
        val time = if (timestamp > 0) SimpleDateFormat("dd.MM.yyyy HH:mm:ss", trLocale).format(Date(timestamp)) else "—"
        findViewById<TextView>(R.id.marketDetailMeta).text = "Kaynak: $source\nVeri zamanı: $time\nMod: ${if (realtime) "CANLI" else "SEANS DIŞI / KAPANIŞ VERİSİ"}${delaySeconds?.let { " • gecikme ${it}s" } ?: ""}"
        findViewById<Button>(R.id.marketDetailSettings).visibility = View.GONE
    }

    private fun showError(message: String, showSettings: Boolean = false) {
        findViewById<TextView>(R.id.marketDetailChange).text = "VERİ YOK"
        findViewById<TextView>(R.id.marketDetailChange).setTextColor(getColor(R.color.red))
        findViewById<TextView>(R.id.marketDetailMeta).text = message
        findViewById<TextView>(R.id.marketDetailStats).text = "Sahte veya tahmini piyasa değeri gösterilmez."
        findViewById<Button>(R.id.marketDetailSettings).visibility = if (showSettings) View.VISIBLE else View.GONE
    }

    private fun fmt(v: Double): String = NumberFormat.getNumberInstance(trLocale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(v)

    private fun titleFor(code: String) = when (code) {
        "BIST100" -> "BIST 100"
        "BIST30" -> "BIST 30"
        "USDTRY" -> "DOLAR / TL"
        "XAUUSD" -> "ONS ALTIN"
        "VIOP30" -> "VİOP 30"
        else -> "Piyasa Detayı"
    }

    companion object {
        const val EXTRA_INSTRUMENT = "market_instrument"
    }
}
