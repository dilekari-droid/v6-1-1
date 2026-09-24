package tr.borsatakip.v5.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.HistoryLoadState
import tr.borsatakip.v5.data.SignalHistoryFilter
import tr.borsatakip.v5.data.SignalHistoryStore
import tr.borsatakip.v5.data.ViopStrategyHistoryStore
import java.text.SimpleDateFormat
import java.util.Locale

class SignalHistoryActivity : BaseActivity() {
    private lateinit var store: SignalHistoryStore
    private lateinit var viopStrategyStore: ViopStrategyHistoryStore
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var forwardPanel: View
    private lateinit var forwardSummary: TextView
    private lateinit var forwardCurve: ForwardCurveView
    private lateinit var historyFilters: View
    private lateinit var horizonBar: View
    private lateinit var strategyMarketBar: View
    private lateinit var symbol: EditText
    private lateinit var from: EditText
    private lateinit var to: EditText
    private var direction: String? = null
    private var minScore: Int? = null
    private var horizon = "15m"
    private var strategyMode = false
    private var strategyMarket = "BIST"
    private val dayFormat = SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR")).apply { isLenient = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signal_history)
        setupBottomNav()
        store = SignalHistoryStore(this)
        viopStrategyStore = ViopStrategyHistoryStore(this)
        status = findViewById(R.id.historyStatus)
        list = findViewById(R.id.historyList)
        list.layoutManager = LinearLayoutManager(this)
        forwardPanel = findViewById(R.id.forwardPanel)
        forwardSummary = findViewById(R.id.forwardSummary)
        forwardCurve = findViewById(R.id.forwardCurve)
        historyFilters = findViewById(R.id.historyFilters)
        horizonBar = findViewById(R.id.horizonBar)
        strategyMarketBar = findViewById(R.id.strategyMarketBar)
        symbol = findViewById(R.id.historySymbol)
        from = findViewById(R.id.historyFrom)
        to = findViewById(R.id.historyTo)

        findViewById<Button>(R.id.tabHistory).setOnClickListener { showHistory() }
        findViewById<Button>(R.id.tabForward).setOnClickListener { showForward(false) }
        findViewById<Button>(R.id.tabStats).setOnClickListener { showForward(true) }
        findViewById<Button>(R.id.tabStrategies).setOnClickListener { showStrategies() }
        findViewById<Button>(R.id.strategyBist).setOnClickListener { strategyMarket = "BIST"; loadStrategies() }
        findViewById<Button>(R.id.strategyViop).setOnClickListener { strategyMarket = "VIOP"; loadStrategies() }
        findViewById<Button>(R.id.historyAll).setOnClickListener { direction = null; minScore = null; loadHistory() }
        findViewById<Button>(R.id.historyLong).setOnClickListener { direction = "LONG"; minScore = null; loadHistory() }
        findViewById<Button>(R.id.historyShort).setOnClickListener { direction = "SHORT"; minScore = null; loadHistory() }
        findViewById<Button>(R.id.history85).setOnClickListener { minScore = 85; loadHistory() }
        findViewById<Button>(R.id.history75).setOnClickListener { minScore = 75; loadHistory() }
        findViewById<Button>(R.id.historyApply).setOnClickListener { loadHistory() }

        bindHorizon(R.id.h15m, "15m")
        bindHorizon(R.id.h30m, "30m")
        bindHorizon(R.id.h1h, "1h")
        bindHorizon(R.id.h4h, "4h")
        bindHorizon(R.id.h1d, "1d")
        showHistory()
    }

    private fun bindHorizon(id: Int, value: String) {
        findViewById<Button>(id).setOnClickListener { horizon = value; if (strategyMode) loadStrategies() else loadForward() }
    }

    private fun showHistory() {
        strategyMode = false
        historyFilters.visibility = View.VISIBLE
        horizonBar.visibility = View.GONE
        strategyMarketBar.visibility = View.GONE
        list.visibility = View.VISIBLE
        forwardPanel.visibility = View.GONE
        loadHistory()
    }

    private fun showForward(statOnly: Boolean) {
        strategyMode = false
        historyFilters.visibility = View.GONE
        horizonBar.visibility = View.VISIBLE
        strategyMarketBar.visibility = View.GONE
        list.visibility = View.GONE
        forwardPanel.visibility = View.VISIBLE
        loadForward(statOnly)
    }

    private fun showStrategies() {
        strategyMode = true
        historyFilters.visibility = View.GONE
        horizonBar.visibility = View.VISIBLE
        strategyMarketBar.visibility = View.VISIBLE
        forwardPanel.visibility = View.GONE
        list.visibility = View.VISIBLE
        loadStrategies()
    }

    private fun loadStrategies() {
        lifecycleScope.launch {
            val effectiveHorizon = if (strategyMarket == "VIOP") "1h" else horizon
            val stats = if (strategyMarket == "VIOP") {
                viopStrategyStore.performance(limit = 100)
            } else {
                store.strategyPerformance(effectiveHorizon)
            }
            status.text = if (strategyMarket == "VIOP") {
                "VİOP STRATEJİ KARŞILAŞTIRMA • 1 sa • VERIFIED_HISTORICAL • V5.1.48"
            } else {
                "BIST STRATEJİ KARŞILAŞTIRMA • ${label(effectiveHorizon)} • VERIFIED_HISTORICAL • V5.1.48"
            }
            val lines = stats.map { s ->
                buildString {
                    append("${s.strategyLabel} • ${s.confidenceLabel}\n")
                    append("Doğrulanmış örnek: ${s.totalVerified} • Pozitif ${s.positive} • Negatif ${s.negative}\n")
                    append("Başarı: ${pct(s.successRatePct)} • Ort. getiri: ${pct(s.averageReturnPct)} • PF: ${factor(s.profitFactor)}\n")
                    append("Max DD: ${pct(s.maxDrawdownPct?.let { -it })}")
                    if (strategyMarket == "BIST") append(" • T1 ${s.target1Count} • T2 ${s.target2Count} • Stop ${s.stopCount}")
                    append("\n")
                    if (strategyMarket == "BIST") append("Net P&L: ${s.netPnl?.let { "%.2f".format(it) } ?: "MALİYET MODELİ YOK / ÖRNEK YOK"} (${s.netPnlSampleCount} kayıt)\n")
                    if (!s.sampleSufficient) {
                        append("DYNAMIC ENSEMBLE DEVRE DIŞI: minimum 20 doğrulanmış örnek gerekli.")
                    } else {
                        append("Karşılaştırılabilir örnek eşiği sağlandı. Canlı ağırlık yalnız yön + rejim bağlamında, tavan ve ±5 puan adım limitiyle güncellenir.")
                    }
                }
            }
            list.adapter = HistoryTextAdapter(lines.ifEmpty { listOf("$strategyMarket için doğrulanmış strateji örneği henüz oluşmadı.") })
        }
    }

    private fun loadHistory() {
        val fromRaw = from.text.toString().trim()
        val toRaw = to.text.toString().trim()
        val fromTime = parseStart(fromRaw)
        val toTime = parseEnd(toRaw)
        from.error = if (fromRaw.isNotBlank() && fromTime == null) "Geçersiz tarih (GG.AA.YYYY)" else null
        to.error = if (toRaw.isNotBlank() && toTime == null) "Geçersiz tarih (GG.AA.YYYY)" else null
        if (from.error != null || to.error != null) { status.text = "FİLTRE HATASI • Tarih alanlarını düzeltin."; return }
        if (fromTime != null && toTime != null && fromTime > toTime) {
            to.error = "Bitiş tarihi başlangıçtan önce olamaz."
            status.text = "FİLTRE HATASI • Tarih aralığı ters."
            return
        }
        lifecycleScope.launch {
            val filter = SignalHistoryFilter(
                direction = direction,
                minScore = minScore,
                symbol = symbol.text.toString().trim().ifBlank { null },
                fromTime = fromTime,
                toTime = toTime
            )
            val overview = store.overview()
            val lines = store.summaryLines(filter = filter)
            val filterInfo = "Filtre: ${direction ?: "TÜM YÖNLER"} • ${minScore?.let { "$it+" } ?: "TÜM PUANLAR"} • Gösterilen ${lines.size}/toplam ${overview.totalRecords} (ekran üst sınırı 200, saklama üst sınırı 3000)"
            status.text = when (overview.state) {
                HistoryLoadState.NO_HISTORY -> "NO_HISTORY • Geçerli sinyal geçmişi yok.\n$filterInfo"
                HistoryLoadState.PARTIAL_HISTORY -> "PARTIAL_HISTORY • Kısmi taramadan ${overview.totalRecords} geçerli sinyal kaydı mevcut.\n$filterInfo"
                HistoryLoadState.COMPLETE_HISTORY -> "COMPLETE_HISTORY • ${overview.totalRecords} geçerli sinyal kaydı mevcut.\n$filterInfo"
                HistoryLoadState.HISTORY_LOAD_ERROR -> "HISTORY_LOAD_ERROR • Sinyal geçmişi okunamadı."
            }
            list.adapter = HistoryTextAdapter(
                when {
                    overview.state == HistoryLoadState.HISTORY_LOAD_ERROR -> listOf("Geçmiş dosyası okunamadı; kayıt yokmuş gibi gösterilmedi.")
                    lines.isEmpty() && overview.totalRecords == 0 -> listOf("Kayıt yok.")
                    lines.isEmpty() -> listOf("Seçili filtreye uyan kayıt yok.")
                    else -> lines
                }
            )
        }
    }

    private fun loadForward(statOnly: Boolean = false) {
        lifecycleScope.launch {
            val s = store.forwardStats(horizon)
            val insufficient = s.totalVerified < 2
            status.text = if (insufficient) "FORWARD • VERİ YETERSİZ • $horizon" else "FORWARD • DOĞRULANMIŞ TARİHSEL SONUÇLAR • $horizon"
            forwardSummary.text = buildString {
                append("Periyot: ${label(horizon)}\n")
                append("Toplam doğrulanmış sinyal: ${s.totalVerified}\n")
                append("Pozitif: ${s.positive} • Negatif: ${s.negative}\n")
                append("Başarı oranı: ${pct(s.successRatePct)}\n")
                append("Ortalama getiri: ${pct(s.averageReturnPct)}\n")
                append("Medyan getiri: ${pct(s.medianReturnPct)}\n")
                append("Ort. kazanç: ${pct(s.averageGainPct)}\n")
                append("Ort. kayıp: ${pct(s.averageLossPct)}\n")
                append("Profit Factor: ${factor(s.profitFactor)}\n")
                if (statOnly) append("\nİstatistikler yalnız VERIFIED_HISTORICAL outcome kayıtlarından hesaplanır.")
                else append("\nGetiri eğrisi aşağıda gerçek doğrulanmış outcome serisinden çizilir.")
            }
            forwardCurve.values = s.curve
        }
    }

    private fun parseStart(value: String): Long? = if (value.isBlank()) null else runCatching { dayFormat.parse(value)?.time }.getOrNull()
    private fun parseEnd(value: String): Long? = if (value.isBlank()) null else runCatching { dayFormat.parse(value)?.time?.plus(86_399_999L) }.getOrNull()
    private fun pct(v: Double?) = v?.takeIf { it.isFinite() }?.let { "%+.2f%%".format(it) } ?: "VERİ YETERSİZ"
    private fun factor(v: Double?) = when {
        v == null -> "VERİ YETERSİZ"
        v == Double.POSITIVE_INFINITY -> "∞ (kayıp gözlenmedi)"
        v.isFinite() -> "%.2f".format(v)
        else -> "VERİ YETERSİZ"
    }
    private fun label(v: String) = when (v) { "15m" -> "15 dk"; "30m" -> "30 dk"; "1h" -> "1 sa"; "4h" -> "4 sa"; else -> "1 gün" }
}
