package tr.borsatakip.v5.ui

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.ChartTimeframe
import tr.borsatakip.v5.analysis.MultiTimeframeSignalAnalyzer
import tr.borsatakip.v5.data.ChartHistoryRepository
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.model.Opportunity

class TechnicalAnalysisActivity : BaseActivity() {

    data class I(val n: String, val v: Double?, val h: String, val s: String)
    data class TrendRowIds(
        val row: Int,
        val label: Int,
        val arrow: Int,
        val trend: Int,
        val delta: Int,
        val r2: Int,
        val comment: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_technical_analysis)
        setupBottomNav()
        bind()
    }

    private fun bind() {
        val x = (if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getSerializableExtra("opportunity", Opportunity::class.java)
        } else {
            @Suppress("DEPRECATION") (intent.getSerializableExtra("opportunity") as? Opportunity)
        }) ?: AppSession.selected

        findViewById<TextView>(R.id.techSubtitle).text = x?.let { "${it.companyName ?: it.symbol}  (${it.symbol.uppercase()})" } ?: "Seçili hisse yok"
        val sig = findViewById<TextView>(R.id.techSignal)
        val score = findViewById<TextView>(R.id.techScoreLine)
        val c = findViewById<LinearLayout>(R.id.indicatorContainer)
        c.removeAllViews()

        if (x == null) {
            sig.text = "VERİ YOK"
            score.text = "Teknik analiz için bir hisse seçin."
            findViewById<TextView>(R.id.multiTfStatus).text = "Çoklu zaman dilimi analizi için bir hisse seçin."
            findViewById<TextView>(R.id.techAdviceLabel).text = "VERİ YOK"
            findViewById<TextView>(R.id.techAdviceDetail).text = "Teknik sinyal özeti üretilemedi."
            listOf(row3m(), row5m(), row1h(), row1d()).forEach { bindTrendRow(it, "", MultiTimeframeSignalAnalyzer.TrendResult(MultiTimeframeSignalAnalyzer.TrendDirection.INSUFFICIENT, null, null, 0)) }
            return
        }

        sig.text = "${x.direction} • Nihai Sinyal ${x.finalSignalScore}/100"
        score.text = "Teknik ${x.score} • Risk ${x.riskScore} • Veri Güveni ${x.dataConfidenceScore} • ${x.technicalLabel}"
        val t = x.technical
        listOf(
            I("RSI (14)", t.rsi14, "Göreli güç", stateRsi(t.rsi14)),
            I("MACD", t.macd, "Signal ${fmt(t.macdSignal)}", statePair(t.macd, t.macdSignal)),
            I("EMA 20", t.ema20, "Kısa trend", statePrice(x.price, t.ema20)),
            I("EMA 50", t.ema50, "Orta trend", statePrice(x.price, t.ema50)),
            I("EMA 200", t.ema200, "Uzun trend", statePrice(x.price, t.ema200)),
            I("Bollinger", null, "${fmt(t.bbLower)} / ${fmt(t.bbUpper)}", "BANT"),
            I("ATR (14)", t.atr14, "Volatilite", "BİLGİ"),
            I("Hacim", t.volumeRatio, "Oran", t.volumeRatio?.let { "%.2fx".format(it) } ?: "VERİ YOK"),
            I("VWMA20", t.vwma20 ?: t.vwap, "20 dönem hacim ağırlıklı fiyat", statePrice(x.price, t.vwma20 ?: t.vwap)),
            I("Session VWAP", t.sessionVwap, "Gerçek intraday seans VWAP", statePrice(x.price, t.sessionVwap)),
            I("Destek", t.support, "Fiyat seviyesi", statePrice(x.price, t.support)),
            I("Direnç", t.resistance, "Fiyat seviyesi", statePrice(x.price, t.resistance))
        ).forEach { add(c, it) }
        loadMultiTimeframe(x)
    }

    private fun loadMultiTimeframe(x: Opportunity) {
        val status = findViewById<TextView>(R.id.multiTfStatus)
        val alignmentView = findViewById<TextView>(R.id.trendAlignment)
        val adviceLabel = findViewById<TextView>(R.id.techAdviceLabel)
        val adviceScore = findViewById<TextView>(R.id.techAdviceScore)
        val adviceDetail = findViewById<TextView>(R.id.techAdviceDetail)
        val adviceConfidence = findViewById<TextView>(R.id.techAdviceConfidence)
        val adviceEvidence = findViewById<TextView>(R.id.techAdviceEvidence)
        val adviceRisk = findViewById<TextView>(R.id.techAdviceRisk)
        val adviceInvalidation = findViewById<TextView>(R.id.techAdviceInvalidation)

        status.text = "3 DK, 5 DK, 1 SA ve 1 GÜN gerçek OHLCV verileri analiz ediliyor..."
        alignmentView.text = "Ana uyum: HESAPLANIYOR"
        adviceLabel.text = "HESAPLANIYOR"
        adviceScore.text = ""
        adviceDetail.text = "Zaman dilimleri yüklendikten sonra teknik sinyal özeti oluşturulacak."
        adviceConfidence.text = "Kanıt kalitesi hesaplanıyor..."
        adviceEvidence.text = "İstatistiksel kanıtlar hazırlanıyor..."
        adviceRisk.text = "Risk bayrakları hazırlanıyor..."
        adviceInvalidation.text = "Geçersizleşme seviyesi hazırlanıyor..."

        lifecycleScope.launch {
            try {
                val repo = ChartHistoryRepository(ProviderRouter(this@TechnicalAnalysisActivity))
                val frames = listOf(
                    "3 DK" to ChartTimeframe.THREE_MIN,
                    "5 DK" to ChartTimeframe.FIVE_MIN,
                    "1 SA" to ChartTimeframe.ONE_HOUR,
                    "1 GÜN" to ChartTimeframe.ONE_DAY
                )
                val results = frames.map { (label, frame) ->
                    async {
                        val candles = repo.load(x.symbol, frame, x.candles)
                        MultiTimeframeSignalAnalyzer.TimeframeTrend(label, MultiTimeframeSignalAnalyzer.trend(candles))
                    }
                }.awaitAll()

                bindTrendRow(row3m(), "3 DK", results[0].result)
                bindTrendRow(row5m(), "5 DK", results[1].result)
                bindTrendRow(row1h(), "1 SA", results[2].result)
                bindTrendRow(row1d(), "1 GÜN", results[3].result)

                val summary = MultiTimeframeSignalAnalyzer.summarize(results, x.price, x.technical)
                val alignmentColor = colorForAlignment(summary.alignmentLabel)
                alignmentView.setTextColor(alignmentColor)
                alignmentView.text = colorizePair("Ana uyum: ", summary.alignmentLabel, alignmentColor)
                status.text = buildAlignmentExplanation(results, summary.alignmentLabel)

                adviceLabel.text = summary.advice.label
                adviceLabel.setTextColor(colorForAdvice(summary.advice))
                adviceScore.text = "Teknik sinyal puanı: ${summary.score}/100"
                adviceConfidence.text = buildConfidenceLine(summary.confidence, summary.alignmentLabel)
                adviceEvidence.text = summary.evidenceSummary
                adviceRisk.text = summary.riskSummary
                adviceInvalidation.text = summary.invalidationSummary
                adviceDetail.text = summary.explanation
            } catch (t: Throwable) {
                status.text = "Çoklu zaman dilimi analizi alınamadı: ${t.message ?: "veri sağlayıcı hatası"}"
                listOf(row3m(), row5m(), row1h(), row1d()).forEach { row ->
                    bindTrendRow(row, rowLabel(row), MultiTimeframeSignalAnalyzer.TrendResult(MultiTimeframeSignalAnalyzer.TrendDirection.INSUFFICIENT, null, null, 0))
                }
                alignmentView.text = "Ana uyum: HESAPLANAMADI"
                alignmentView.setTextColor(ContextCompat.getColor(this@TechnicalAnalysisActivity, R.color.text_secondary))
                adviceLabel.text = "VERİ YOK"
                adviceLabel.setTextColor(ContextCompat.getColor(this@TechnicalAnalysisActivity, R.color.text_secondary))
                adviceScore.text = ""
                adviceDetail.text = "Teknik sinyal özeti için gerekli çoklu zaman dilimi verisi alınamadı."
                adviceConfidence.text = "Kanıt güveni hesaplanamadı."
                adviceEvidence.text = "Yeterli istatistiksel kanıt yok."
                adviceRisk.text = "Risk analizi için veri yetersiz."
                adviceInvalidation.text = "Geçersizleşme seviyesi hesaplanamadı."
            }
        }
    }

    private fun bindTrendRow(ids: TrendRowIds, label: String, r: MultiTimeframeSignalAnalyzer.TrendResult) {
        findViewById<TextView>(ids.label).text = if (label.isNotBlank()) label else rowLabel(ids)
        val arrowView = findViewById<TextView>(ids.arrow)
        val trendView = findViewById<TextView>(ids.trend)
        val deltaView = findViewById<TextView>(ids.delta)
        val r2View = findViewById<TextView>(ids.r2)
        val commentView = findViewById<TextView>(ids.comment)
        val rowView = findViewById<LinearLayout>(ids.row)

        val direction = r.direction
        val color = colorForDirection(direction)
        val rowBg = backgroundForDirection(direction)
        val chipBg = chipBackgroundForDirection(direction)
        val arrow = when (direction) {
            MultiTimeframeSignalAnalyzer.TrendDirection.UP -> "↗"
            MultiTimeframeSignalAnalyzer.TrendDirection.DOWN -> "↘"
            MultiTimeframeSignalAnalyzer.TrendDirection.SIDEWAYS -> "→"
            MultiTimeframeSignalAnalyzer.TrendDirection.INSUFFICIENT -> "•"
        }
        val comment = when (direction) {
            MultiTimeframeSignalAnalyzer.TrendDirection.INSUFFICIENT -> "VERİ YOK"
            MultiTimeframeSignalAnalyzer.TrendDirection.SIDEWAYS -> "NÖTR"
            else -> strengthComment(r.rSquared)
        }
        rowView.setBackgroundResource(rowBg)
        commentView.setBackgroundResource(chipBg)
        arrowView.text = arrow
        trendView.text = direction.label
        deltaView.text = r.slopePct?.let { signedPercent(it) } ?: "—"
        r2View.text = r.rSquared?.let { "%.2f".format(it) } ?: "—"
        commentView.text = comment

        arrowView.setTextColor(color)
        trendView.setTextColor(color)
        deltaView.setTextColor(color)
        commentView.setTextColor(color)
        r2View.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
    }

    private fun buildAlignmentExplanation(
        results: List<MultiTimeframeSignalAnalyzer.TimeframeTrend>,
        alignmentLabel: String
    ): CharSequence {
        val shortText = aggregateDirection(results.filter { it.label == "3 DK" || it.label == "5 DK" })
        val midText = directionText(results.firstOrNull { it.label == "1 SA" }?.result?.direction)
        val longText = directionText(results.firstOrNull { it.label == "1 GÜN" }?.result?.direction)

        val sb = SpannableStringBuilder()
        sb.append("Gerçek OHLCV verilerinden bağımsız zaman dilimi regresyonları hesaplandı. ")
        val shortPhrase = "Kısa vadede $shortText"
        appendColoredPhrase(sb, shortPhrase, colorFromPhrase(shortText))
        sb.append(", ")
        val midPhrase = "orta vadede $midText"
        appendColoredPhrase(sb, midPhrase, colorFromPhrase(midText))
        sb.append(" ve ")
        val longPhrase = "uzun vadede $longText"
        appendColoredPhrase(sb, longPhrase, colorFromPhrase(longText))
        sb.append(" görünümü izleniyor. Ana uyum ")
        val alignColor = colorForAlignment(alignmentLabel)
        val start = sb.length
        sb.append(alignmentLabel)
        sb.setSpan(ForegroundColorSpan(alignColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(" seviyesindedir.")
        return sb
    }

    private fun buildConfidenceLine(confidence: Int, alignmentLabel: String): CharSequence {
        val sb = SpannableStringBuilder()
        val blue = ContextCompat.getColor(this, R.color.blue)
        val green = colorForAlignment(alignmentLabel)
        val p1 = "Kanıt güveni: $confidence/100"
        sb.append(p1)
        sb.setSpan(ForegroundColorSpan(blue), 0, p1.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(" • Ana uyum: ")
        val start = sb.length
        sb.append(alignmentLabel)
        sb.setSpan(ForegroundColorSpan(green), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun colorizePair(prefix: String, value: String, color: Int): CharSequence {
        val sb = SpannableStringBuilder(prefix)
        val start = sb.length
        sb.append(value)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun appendColoredPhrase(sb: SpannableStringBuilder, phrase: String, color: Int) {
        val start = sb.length
        sb.append(phrase)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun colorFromPhrase(text: String): Int = when {
        text.contains("yükseliş", true) -> ContextCompat.getColor(this, R.color.green)
        text.contains("düşüş", true) -> ContextCompat.getColor(this, R.color.red)
        else -> ContextCompat.getColor(this, R.color.text_secondary)
    }

    private fun aggregateDirection(items: List<MultiTimeframeSignalAnalyzer.TimeframeTrend>): String {
        val directions = items.map { it.result.direction }
        val up = directions.count { it == MultiTimeframeSignalAnalyzer.TrendDirection.UP }
        val down = directions.count { it == MultiTimeframeSignalAnalyzer.TrendDirection.DOWN }
        return when {
            directions.isEmpty() -> "yetersiz veri var"
            up > down -> "yükseliş eğilimi baskın"
            down > up -> "düşüş eğilimi baskın"
            else -> "karışık / yatay görünüm var"
        }
    }

    private fun directionText(direction: MultiTimeframeSignalAnalyzer.TrendDirection?): String = when (direction) {
        MultiTimeframeSignalAnalyzer.TrendDirection.UP -> "yükseliş eğilimi güçlü"
        MultiTimeframeSignalAnalyzer.TrendDirection.DOWN -> "düşüş eğilimi belirgin"
        MultiTimeframeSignalAnalyzer.TrendDirection.SIDEWAYS -> "yatay / kararsız"
        else -> "yetersiz veri"
    }

    private fun strengthComment(r2: Double?): String = when {
        r2 == null -> "—"
        r2 >= 0.70 -> "GÜÇLÜ"
        r2 >= 0.35 -> "ORTA"
        else -> "ZAYIF"
    }

    private fun signedPercent(v: Double): String = (if (v > 0) "+" else "") + "%.2f%%".format(v)

    private fun colorForAdvice(advice: MultiTimeframeSignalAnalyzer.Advice): Int = when (advice) {
        MultiTimeframeSignalAnalyzer.Advice.STRONG_BUY,
        MultiTimeframeSignalAnalyzer.Advice.BUY -> ContextCompat.getColor(this, R.color.green)
        MultiTimeframeSignalAnalyzer.Advice.STRONG_SELL,
        MultiTimeframeSignalAnalyzer.Advice.SELL -> ContextCompat.getColor(this, R.color.red)
        MultiTimeframeSignalAnalyzer.Advice.NEUTRAL -> ContextCompat.getColor(this, R.color.yellow)
    }

    private fun colorForAlignment(label: String): Int = when (label.uppercase()) {
        "ÇOK GÜÇLÜ", "GÜÇLÜ" -> ContextCompat.getColor(this, R.color.green)
        "KARIŞIK", "ORTA" -> ContextCompat.getColor(this, R.color.yellow)
        "ZAYIF", "HESAPLANAMADI", "YETERSİZ VERİ" -> ContextCompat.getColor(this, R.color.red)
        else -> ContextCompat.getColor(this, R.color.text_secondary)
    }

    private fun colorForDirection(direction: MultiTimeframeSignalAnalyzer.TrendDirection): Int = when (direction) {
        MultiTimeframeSignalAnalyzer.TrendDirection.UP -> ContextCompat.getColor(this, R.color.green)
        MultiTimeframeSignalAnalyzer.TrendDirection.DOWN -> ContextCompat.getColor(this, R.color.red)
        MultiTimeframeSignalAnalyzer.TrendDirection.SIDEWAYS,
        MultiTimeframeSignalAnalyzer.TrendDirection.INSUFFICIENT -> ContextCompat.getColor(this, R.color.text_secondary)
    }

    private fun backgroundForDirection(direction: MultiTimeframeSignalAnalyzer.TrendDirection): Int = when (direction) {
        MultiTimeframeSignalAnalyzer.TrendDirection.UP -> R.drawable.bg_trend_row_up
        MultiTimeframeSignalAnalyzer.TrendDirection.DOWN -> R.drawable.bg_trend_row_down
        MultiTimeframeSignalAnalyzer.TrendDirection.SIDEWAYS,
        MultiTimeframeSignalAnalyzer.TrendDirection.INSUFFICIENT -> R.drawable.bg_trend_row_neutral
    }

    private fun chipBackgroundForDirection(direction: MultiTimeframeSignalAnalyzer.TrendDirection): Int = when (direction) {
        MultiTimeframeSignalAnalyzer.TrendDirection.UP -> R.drawable.bg_trend_chip_up
        MultiTimeframeSignalAnalyzer.TrendDirection.DOWN -> R.drawable.bg_trend_chip_down
        MultiTimeframeSignalAnalyzer.TrendDirection.SIDEWAYS,
        MultiTimeframeSignalAnalyzer.TrendDirection.INSUFFICIENT -> R.drawable.bg_trend_chip_neutral
    }

    private fun row3m() = TrendRowIds(R.id.trendRow3m, R.id.trend3mLabel, R.id.trend3mArrow, R.id.trend3mTrend, R.id.trend3mDelta, R.id.trend3mR2, R.id.trend3mComment)
    private fun row5m() = TrendRowIds(R.id.trendRow5m, R.id.trend5mLabel, R.id.trend5mArrow, R.id.trend5mTrend, R.id.trend5mDelta, R.id.trend5mR2, R.id.trend5mComment)
    private fun row1h() = TrendRowIds(R.id.trendRow1h, R.id.trend1hLabel, R.id.trend1hArrow, R.id.trend1hTrend, R.id.trend1hDelta, R.id.trend1hR2, R.id.trend1hComment)
    private fun row1d() = TrendRowIds(R.id.trendRow1d, R.id.trend1dLabel, R.id.trend1dArrow, R.id.trend1dTrend, R.id.trend1dDelta, R.id.trend1dR2, R.id.trend1dComment)

    private fun rowLabel(ids: TrendRowIds): String = when (ids.row) {
        R.id.trendRow3m -> "3 DK"
        R.id.trendRow5m -> "5 DK"
        R.id.trendRow1h -> "1 SA"
        else -> "1 GÜN"
    }

    private fun add(c: LinearLayout, i: I) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_indicator, c, false)
        v.findViewById<TextView>(R.id.indicatorName).text = i.n
        v.findViewById<TextView>(R.id.indicatorHint).text = i.h
        v.findViewById<TextView>(R.id.indicatorValue).text = fmt(i.v)
        v.findViewById<TextView>(R.id.indicatorState).text = i.s
        c.addView(v)
    }

    private fun fmt(v: Double?) = v?.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "VERİ YOK"
    private fun stateRsi(v: Double?) = when {
        v == null -> "YOK"
        v < 30 -> "AL"
        v > 70 -> "SAT"
        else -> "NÖTR"
    }

    private fun statePair(a: Double?, b: Double?) = when {
        a == null || b == null -> "YOK"
        a > b -> "AL"
        a < b -> "SAT"
        else -> "NÖTR"
    }

    private fun statePrice(p: Double, v: Double?) = when {
        v == null -> "YOK"
        p > v -> "ÜSTÜ"
        p < v -> "ALTI"
        else -> "EŞİT"
    }
}
