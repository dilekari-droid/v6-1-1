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
import tr.borsatakip.v5.analysis.OpportunityRankingPolicy
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.scan.BistScanMode
import tr.borsatakip.v5.scan.ScanState

class ScanResultsActivity : BaseActivity() {
    private lateinit var list: RecyclerView
    private lateinit var coverageSummary: TextView
    private lateinit var scanState: ScanState
    private var mode = Mode.ALL
    private var summaryMode = SummaryMode.RESULTS

    private enum class Mode { ALL, LONG, SHORT, HIGH_POWER, SCORE }
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
        if (!scanState.scanMode.observationOnly) return
        findViewById<Button>(R.id.scanLong).visibility = View.GONE
        findViewById<Button>(R.id.scanShort).visibility = View.GONE
        findViewById<Button>(R.id.scan85).text = "85+ TEKNİK"
        findViewById<Button>(R.id.scanScore).text = "TEKNİK PUAN"
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
                    if (target != SummaryMode.FAILED) mode = Mode.ALL
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
        mapOf(
            R.id.scanAll to Mode.ALL,
            R.id.scanLong to Mode.LONG,
            R.id.scanShort to Mode.SHORT,
            R.id.scan85 to Mode.HIGH_POWER,
            R.id.scanScore to Mode.SCORE
        ).forEach { (id, target) ->
            findViewById<Button>(id).setOnClickListener {
                summaryMode = SummaryMode.RESULTS
                mode = target
                buildSummaryButtons()
                updateCoverageSummary()
                updateFilterVisuals()
                bind()
            }
        }
        updateFilterVisuals()
    }

    private fun updateFilterVisuals() {
        val buttons = mapOf(
            R.id.scanAll to Mode.ALL,
            R.id.scanLong to Mode.LONG,
            R.id.scanShort to Mode.SHORT,
            R.id.scan85 to Mode.HIGH_POWER,
            R.id.scanScore to Mode.SCORE
        )
        buttons.forEach { (id, target) ->
            val button = findViewById<Button>(id)
            val selected = summaryMode != SummaryMode.FAILED && mode == target
            button.backgroundTintList = ColorStateList.valueOf(getColor(if (selected) R.color.blue else R.color.chip_bg))
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
        val delayed = scanState.scanMode.observationOnly
        val filtered: List<Opportunity> = when (mode) {
            Mode.LONG -> if (delayed) emptyList() else source.filter { it.direction.equals("LONG", true) }
            Mode.SHORT -> if (delayed) emptyList() else source.filter { it.direction.equals("SHORT", true) }
            Mode.HIGH_POWER -> source.filter { if (delayed) it.score >= 85 else it.finalSignalScore >= 85 }
            Mode.SCORE, Mode.ALL -> source
        }.let { filteredItems ->
            OpportunityRankingPolicy.sort(filteredItems)
        }

        list.adapter = ScanResultsAdapter(filtered) { opportunity ->
            AppSession.selected = opportunity
            startActivity(Intent(this, StockDetailActivity::class.java).putExtra("opportunity", opportunity))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
