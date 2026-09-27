package tr.borsatakip.v5.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.OpportunityDirectionalFilterPolicy
import tr.borsatakip.v5.analysis.ScanDirectionPolicy
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.scan.BistScanMode
import tr.borsatakip.v5.scan.ScanState

class ScanResultsActivity : BaseActivity() {
    private lateinit var list: RecyclerView
    private lateinit var coverageSummary: TextView
    private lateinit var scanState: ScanState
    private var directionFilter = DirectionFilter.ALL
    private var highTechnicalOnly = false
    private var technicalSort = false
    private var summaryMode = SummaryMode.RESULTS

    private enum class DirectionFilter { ALL, LONG, SHORT }
    private enum class SummaryMode { SCANNED, SUCCESS, FAILED, RESULTS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_results)
        setupBottomNav()

        list = findViewById(R.id.scanList)
        list.layoutManager = LinearLayoutManager(this)
        coverageSummary = findViewById(R.id.scanCoverageSummary)
        scanState = AppSession.lastScanState ?: ScanState(
            total = AppSession.lastOpportunities.size,
            successful = AppSession.lastOpportunities.size,
            results = AppSession.lastOpportunities
        )

        bindScanState()
        configureModeUi()
        buildSummaryButtons()
        bindFilterButtons()
        bind()
    }

    private fun bindScanState() {
        findViewById<TextView>(R.id.scanState).text = when (scanState.scanMode) {
            BistScanMode.SESSION_CLOSE_ANALYSIS -> when (scanState.scanRun?.status?.name) {
                "COMPLETE" -> "✓ KAPANIŞ TARAMASI TAMAMLANDI • CANLI DEĞİL"
                "PARTIAL" -> "◐ KISMİ KAPANIŞ TARAMASI • CANLI DEĞİL"
                "FAILED" -> "✕ KAPANIŞ TARAMASI BAŞARISIZ"
                else -> "KAPANIŞ TARAMASI SONUCU BEKLENİYOR"
            }
            BistScanMode.DELAYED_ANALYSIS -> when (scanState.scanRun?.status?.name) {
                "COMPLETE" -> "✓ GECİKMELİ TEKNİK ANALİZ TAMAMLANDI • AL/SAT YOK"
                "PARTIAL" -> "◐ KISMİ GECİKMELİ ANALİZ • AL/SAT YOK"
                "FAILED" -> "✕ GECİKMELİ ANALİZ BAŞARISIZ"
                else -> "GECİKMELİ ANALİZ SONUCU BEKLENİYOR"
            }
            BistScanMode.REALTIME_ONLY -> when (scanState.scanRun?.status?.name) {
                "COMPLETE" -> "✓ ANLIK TARAMA TAMAMLANDI"
                "PARTIAL" -> "◐ KISMİ ANLIK TARAMA TAMAMLANDI"
                "FAILED" -> "✕ ANLIK TARAMA BAŞARISIZ"
                else -> "ANLIK TARAMA SONUCU BEKLENİYOR"
            }
        }
        updateCoverageSummary()
    }

    private fun configureModeUi() {
        // LONG/SHORT hiçbir tarama modunda gizlenmez. Gecikmeli/gün sonu verisinde bunlar
        // yayınlanmış AL/SAT sinyali değil, yalnız fail-closed teknik yön filtresidir.
        findViewById<Button>(R.id.scanAll).text = "TÜM"
        findViewById<Button>(R.id.scan85).text = "85+ T"
        findViewById<Button>(R.id.scanScore).text = "TEKN"
        findViewById<Button>(R.id.scanLong).text = "🟢 LONG"
        findViewById<Button>(R.id.scanShort).text = "🔴 SHORT"
        findViewById<Button>(R.id.scan85).contentDescription = "Teknik skoru 85 ve üzeri olanları ayrıca filtrele"
        findViewById<Button>(R.id.scanScore).contentDescription = "Teknik puana göre sırala"
    }

    private fun buildSummaryButtons() {
        val metrics = findViewById<LinearLayout>(R.id.scanMetrics)
        metrics.removeAllViews()
        val entries = listOf(
            Triple("Tanıla", scanState.scannedSymbols.size.takeIf { it > 0 } ?: scanState.total, SummaryMode.SCANNED),
            Triple("Başarılı", scanState.successful.takeIf { it > 0 } ?: AppSession.lastOpportunities.size, SummaryMode.SUCCESS),
            Triple("Hatalı", scanState.skipped + scanState.failedSymbols.size, SummaryMode.FAILED),
            Triple("Sonuç", AppSession.lastOpportunities.size, SummaryMode.RESULTS)
        )
        entries.forEachIndexed { index, (label, value, target) ->
            val button = Button(this).apply {
                text = "$label  $value"
                isAllCaps = false
                maxLines = 1
                isSingleLine = true
                ellipsize = null
                setHorizontallyScrolling(false)
                minWidth = 0
                minimumWidth = 0
                minimumHeight = dp(48)
                setPadding(dp(14), 0, dp(14), 0)
                includeFontPadding = false
                setTextColor(getColor(R.color.text_primary))
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                val selected = target == summaryMode
                backgroundTintList = ColorStateList.valueOf(getColor(if (selected) R.color.blue else R.color.chip_bg))
                alpha = if (selected) 1f else 0.88f
                contentDescription = "$label: $value${if (selected) ", seçili" else ""}"
                setOnClickListener {
                    summaryMode = target
                    if (target != SummaryMode.FAILED) {
                        directionFilter = DirectionFilter.ALL
                        highTechnicalOnly = false
                        technicalSort = false
                    }
                    buildSummaryButtons()
                    updateFilterVisuals()
                    updateCoverageSummary()
                    bind()
                }
            }
            metrics.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
    }

    private fun bindFilterButtons() {
        findViewById<Button>(R.id.scanAll).setOnClickListener {
            summaryMode = SummaryMode.RESULTS
            directionFilter = DirectionFilter.ALL
            highTechnicalOnly = false
            technicalSort = false
            refreshFilters()
        }
        findViewById<Button>(R.id.scan85).setOnClickListener {
            summaryMode = SummaryMode.RESULTS
            highTechnicalOnly = !highTechnicalOnly
            refreshFilters()
        }
        findViewById<Button>(R.id.scanScore).setOnClickListener {
            summaryMode = SummaryMode.RESULTS
            technicalSort = !technicalSort
            refreshFilters()
        }
        findViewById<Button>(R.id.scanLong).setOnClickListener {
            summaryMode = SummaryMode.RESULTS
            directionFilter = if (directionFilter == DirectionFilter.LONG) DirectionFilter.ALL else DirectionFilter.LONG
            refreshFilters()
        }
        findViewById<Button>(R.id.scanShort).setOnClickListener {
            summaryMode = SummaryMode.RESULTS
            directionFilter = if (directionFilter == DirectionFilter.SHORT) DirectionFilter.ALL else DirectionFilter.SHORT
            refreshFilters()
        }
        updateFilterVisuals()
    }

    private fun refreshFilters() {
        buildSummaryButtons()
        updateCoverageSummary()
        updateFilterVisuals()
        bind()
    }

    private fun updateFilterVisuals() {
        val allSelected = directionFilter == DirectionFilter.ALL && !highTechnicalOnly && !technicalSort
        val states = mapOf(
            R.id.scanAll to allSelected,
            R.id.scan85 to highTechnicalOnly,
            R.id.scanScore to technicalSort,
            R.id.scanLong to (directionFilter == DirectionFilter.LONG),
            R.id.scanShort to (directionFilter == DirectionFilter.SHORT)
        )
        states.forEach { (id, selected) ->
            val button = findViewById<Button>(id)
            val selectedColor = when (id) {
                R.id.scanLong -> R.color.green
                R.id.scanShort -> R.color.red
                else -> R.color.blue
            }
            button.backgroundTintList = ColorStateList.valueOf(getColor(if (selected) selectedColor else R.color.chip_bg))
            button.setTextColor(getColor(when (id) {
                R.id.scanLong -> R.color.green
                R.id.scanShort -> R.color.red
                else -> R.color.text_primary
            }))
            button.alpha = if (selected) 1f else 0.88f
        }
    }

    private fun updateCoverageSummary() {
        coverageSummary.text = when (summaryMode) {
            SummaryMode.SCANNED -> symbolSummary("Taranan hisseler", scanState.scannedSymbols, scanState.total)
            SummaryMode.SUCCESS -> {
                val successfulSymbols = scanState.results.map { it.symbol }.distinct()
                if (successfulSymbols.isEmpty()) {
                    if (scanState.scanMode.observationOnly) {
                        if (scanState.scanMode == BistScanMode.SESSION_CLOSE_ANALYSIS) {
                            "Kapanış analizi: ${scanState.successful} • Son tamamlanmış seans verisi; canlı sinyal değildir."
                        } else {
                            "Teknik analiz: ${scanState.successful} • Gecikmeli veri; AL/SAT sinyali üretilmez."
                        }
                    } else {
                        "Başarılı analiz: ${scanState.successful} • En güçlü sinyaller üstte gösterilir."
                    }
                } else {
                    symbolSummary(
                        when (scanState.scanMode) {
                            BistScanMode.SESSION_CLOSE_ANALYSIS -> "Kapanış analizi"
                            BistScanMode.DELAYED_ANALYSIS -> "Teknik analiz"
                            BistScanMode.REALTIME_ONLY -> "Başarılı analiz"
                        },
                        successfulSymbols,
                        scanState.successful
                    )
                }
            }
            SummaryMode.FAILED -> {
                val known = scanState.failedSymbols
                if (known.isEmpty()) {
                    "Hatalı/atlanan: ${scanState.skipped}. Bu tarama kaydında sembol bazlı hata listesi bulunmuyor."
                } else {
                    symbolSummary("Analiz sonucu üretilemeyen", known, scanState.skipped + known.size)
                }
            }
            SummaryMode.RESULTS -> {
                val partial = scanState.scanRun?.status?.name == "PARTIAL"
                val prefix = when (scanState.scanMode) {
                    BistScanMode.SESSION_CLOSE_ANALYSIS -> if (partial) "Kısmi kapanış taraması" else "Kapanış taraması"
                    BistScanMode.DELAYED_ANALYSIS -> if (partial) "Kısmi gecikmeli analiz" else "Gecikmeli teknik analiz"
                    BistScanMode.REALTIME_ONLY -> if (partial) "Kısmi anlık tarama" else "Anlık tarama"
                }
                val scanned = scanState.scannedSymbols.size.takeIf { it > 0 } ?: scanState.processed.takeIf { it > 0 } ?: scanState.total
                val failed = scanState.skipped.coerceAtLeast(scanState.failedSymbols.size)
                val resultLabel = if (scanState.scanMode.observationOnly) "Teknik sonuç" else "Sonuç"
                "$prefix • Taranan $scanned/${scanState.total.coerceAtLeast(scanned)} • Analiz ${scanState.successful} • Hatalı/atlanan $failed • $resultLabel ${AppSession.lastOpportunities.size}"
            }
        }
    }

    private fun symbolSummary(label: String, symbols: List<String>, totalFallback: Int): String {
        if (symbols.isEmpty()) return "$label: $totalFallback • Sembol listesi bu tarama kaydında tutulmamış."
        val shown = symbols.take(12)
        val remainder = (symbols.size - shown.size).coerceAtLeast(0)
        return buildString {
            append("$label (${symbols.size}): ${shown.joinToString(", ")}")
            if (remainder > 0) append(" • +$remainder hisse")
        }
    }

    private fun bind() {
        if (summaryMode == SummaryMode.FAILED) {
            list.visibility = View.GONE
            return
        }
        list.visibility = View.VISIBLE

        val source = if (summaryMode == SummaryMode.SUCCESS) scanState.results.ifEmpty { AppSession.lastOpportunities } else AppSession.lastOpportunities
        val directionFiltered = source.filter { item ->
            val classification = ScanDirectionPolicy.classify(item)
            when (directionFilter) {
                DirectionFilter.ALL -> true
                DirectionFilter.LONG -> classification.direction == OpportunityDirectionalFilterPolicy.Direction.LONG
                DirectionFilter.SHORT -> classification.direction == OpportunityDirectionalFilterPolicy.Direction.SHORT
            }
        }
        val scoreFiltered = if (highTechnicalOnly) {
            // 85+ T daima TEKNİK SKOR filtresidir; canlı nihai sinyal skoruyla karıştırılmaz.
            directionFiltered.filter { it.score.coerceIn(0, 100) >= 85 }
        } else directionFiltered

        val filtered = when {
            directionFilter != DirectionFilter.ALL -> ScanResultSortPolicy.directional(scoreFiltered)
            technicalSort || highTechnicalOnly -> ScanResultSortPolicy.technical(scoreFiltered)
            else -> ScanResultSortPolicy.default(scoreFiltered)
        }

        if (filtered.isEmpty()) {
            coverageSummary.text = when (directionFilter) {
                DirectionFilter.LONG -> if (scanState.scanMode.observationOnly)
                    "LONG teknik yönü hesaplanamadı • Yeterli/doğrulanmış teknik veri yok veya yön kapısı sağlanmadı. AL/SAT sinyali üretilmez."
                else "LONG yön sınıflandırması bulunamadı • Trend etiketi tek başına LONG sayılmaz."
                DirectionFilter.SHORT -> if (scanState.scanMode.observationOnly)
                    "SHORT teknik yönü hesaplanamadı • Yeterli/doğrulanmış teknik veri yok veya yön kapısı sağlanmadı. AL/SAT sinyali üretilmez."
                else "SHORT yön sınıflandırması bulunamadı • Trend etiketi tek başına SHORT sayılmaz."
                DirectionFilter.ALL -> if (highTechnicalOnly) "85+ teknik skora uyan sonuç bulunamadı." else coverageSummary.text
            }
        } else if (directionFilter != DirectionFilter.ALL && scanState.scanMode.observationOnly) {
            val label = if (directionFilter == DirectionFilter.LONG) "LONG" else "SHORT"
            coverageSummary.text = "$label • Gecikmeli/gün sonu verisindeki teknik yön sınıflandırmasıdır; canlı AL/SAT sinyali değildir."
        }

        list.adapter = ScanResultsAdapter(filtered) { opportunity ->
            AppSession.selected = opportunity
            startActivity(Intent(this, StockDetailActivity::class.java).putExtra("opportunity", opportunity))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
