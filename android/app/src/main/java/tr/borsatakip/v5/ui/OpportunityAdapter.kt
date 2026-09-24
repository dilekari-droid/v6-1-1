package tr.borsatakip.v5.ui

import tr.borsatakip.v5.data.MarketPresentationPolicy
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.OpportunityDiscoveryPresentation
import tr.borsatakip.v5.analysis.TrendUiPolicy
import tr.borsatakip.v5.analysis.SignalVisualPolicy
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.data.ScanTimeframe
import java.util.Locale

class OpportunityAdapter(
    private val items: List<Opportunity>,
    private val favoriteSymbols: Set<String>,
    private val click: (Opportunity) -> Unit,
    private val toggleFavorite: (Opportunity) -> Unit
) : RecyclerView.Adapter<OpportunityAdapter.H>() {

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val badge: TextView = v.findViewById(R.id.symbolBadge)
        val favorite: TextView = v.findViewById(R.id.favoriteToggle)
        val company: TextView = v.findViewById(R.id.company)
        val priceMovement: TextView = v.findViewById(R.id.priceMovement)
        val symbol: TextView = v.findViewById(R.id.symbol)
        val label: TextView = v.findViewById(R.id.discoveryLabel)
        val signalQuality: TextView = v.findViewById(R.id.signalQualityLabel)
        val scoreBox: View = v.findViewById(R.id.opportunityScoreBox)
        val score: TextView = v.findViewById(R.id.opportunityScore)
        val reason: TextView = v.findViewById(R.id.discoveryReason)
        val scoreBar: ProgressBar = v.findViewById(R.id.scoreBar)
        val sparkline: StockSparklineView = v.findViewById(R.id.opportunitySparkline)
        val risk: TextView = v.findViewById(R.id.riskMetric)
        val volume: TextView = v.findViewById(R.id.volumeMetric)
        val catalyst: TextView = v.findViewById(R.id.catalystMetric)
        val factors: TextView = v.findViewById(R.id.factorSummary)
        val coverage: TextView = v.findViewById(R.id.coverageText)
        val graph: TextView = v.findViewById(R.id.openGraph)
        val detail: TextView = v.findViewById(R.id.openDetail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        H(LayoutInflater.from(parent.context).inflate(R.layout.item_opportunity, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val x = items[position]
        val p = OpportunityDiscoveryPresentation.from(x)
        val trendStyle = TrendUiPolicy.resolve(x)
        val symbolUpper = x.symbol.trim().uppercase(Locale.ROOT)
        val companyText = x.companyName?.trim().takeUnless { it.isNullOrBlank() } ?: symbolUpper
        val normalized = FavoriteRepository.normalizeSymbol(symbolUpper)
        val isFav = favoriteSymbols.contains(normalized)

        holder.badge.text = symbolUpper.take(5)
        holder.symbol.text = symbolUpper
        holder.company.text = companyText
        bindPriceMovement(holder.priceMovement, x.price, x.dailyChangePct)
        holder.favorite.text = if (isFav) "★" else "☆"
        holder.favorite.contentDescription = if (isFav) "Favorilerden çıkar" else "Favoriye ekle"
        holder.favorite.setOnClickListener { toggleFavorite(x) }

        val directionLabel = OpportunityUiPolicy.directionLabel(x)
        holder.label.text = directionLabel
        holder.score.text = x.rankingScore.toString()
        holder.reason.text = p.reason.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("tr", "TR")) else it.toString() }

        val signalStyle = SignalVisualPolicy.resolve(x.direction, x.longScore, x.shortScore)
        holder.scoreBar.progress = signalStyle?.quality ?: 0
        holder.sparkline.setCandles(x.candles, x.dailyChangePct)
        val signalAccent = signalStyle?.let { Color.rgb(it.red, it.green, it.blue) } ?: Color.rgb(180, 190, 200)
        val trendAccent = Color.rgb(trendStyle.red, trendStyle.green, trendStyle.blue)
        val statusAccent = when (directionLabel) {
            "LONG" -> Color.rgb(0, 230, 118)
            "SHORT" -> Color.rgb(255, 59, 77)
            "REDDEDİLDİ" -> Color.rgb(255, 90, 102)
            "YETERSİZ VERİ" -> Color.rgb(143, 163, 184)
            else -> Color.rgb(143, 163, 184)
        }
        holder.label.setTextColor(statusAccent)
        holder.signalQuality.text = signalStyle?.let { "${it.arrow} ${it.direction} • Güç %${it.quality}" } ?: "Yön/güç doğrulanamadı"
        holder.signalQuality.setTextColor(signalAccent)
        holder.signalQuality.contentDescription = signalStyle?.let { "${it.direction} yönü, trend gücü yüzde ${it.quality}" } ?: "Yön ve güç doğrulanamadı"
        holder.score.setTextColor(signalAccent)
        holder.scoreBar.progressTintList = ColorStateList.valueOf(signalAccent)
        holder.scoreBar.progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(signalAccent, 34))
        holder.badge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * holder.itemView.resources.displayMetrics.density
            setColor(ColorUtils.setAlphaComponent(trendAccent, 52))
            setStroke((1.2f * holder.itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1), trendAccent)
        }
        holder.badge.contentDescription = "${x.symbol}, trend ${trendStyle.label}"
        holder.scoreBox.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * holder.itemView.resources.displayMetrics.density
            setColor(ColorUtils.blendARGB(Color.rgb(8, 34, 54), signalAccent, 0.10f))
            setStroke((1.1f * holder.itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(signalAccent, 180))
        }

        val riskLabel = when {
            x.riskScore <= 30 -> "Düşük"
            x.riskScore <= 60 -> "Orta"
            else -> "Yüksek"
        }
        holder.risk.text = "Risk\n$riskLabel"
        holder.volume.text = "Hacim\n${x.technical.volumeRatio?.let { "%.1fx".format(it) } ?: "Veri yok"}"
        holder.catalyst.text = "Katalizör\n${if (p.catalystAvailable) x.kapLabel.take(18) else "Veri yok"}"

        val prioritized = p.factors.filter { it.score != null }.sortedByDescending { it.score }.take(5)
        holder.factors.text = buildString {
            append("KARAR DESTEK ÖZETİ\n")
            append("${x.direction} • LONG ${x.longScore}/100 • SHORT ${x.shortScore}/100 • Nihai ${x.finalSignalScore}/100 • Sıralama ${x.rankingScore}/100\n")
            append("${x.setupType}")
            x.ensembleScore?.let { append(" • Ensemble $it/100") }
            if (x.mtfConsensusScore != null) append(" • MTF ${x.mtfConsensusLabel} (${x.mtfConsensusScore})")
            if (x.marketRegime != "VERİ YOK") append(" • Rejim ${x.marketRegime} %${x.marketRegimeConfidence}")
            x.volumeAnomalyPct?.let { append(" • Hacim anomalisi ${"%+.0f".format(it)}%") }
            x.rsiRiskMessage?.let { append("\n⚠ ").append(it) }
            append("\n")
            prioritized.forEachIndexed { index, f ->
                if (index > 0) append("   •   ")
                append(f.label).append(" ").append(f.score).append("/100")
            }
            val unavailable = p.factors.count { it.score == null }
            if (unavailable > 0) append("\nEksik faktör: ").append(unavailable).append("/12")
        }
        holder.coverage.text = buildString {
            append("Kanıt kapsamı %${p.coveragePct} • Veri güveni ${x.dataConfidenceScore}/100 • Veri modu ${x.dataMode.name}")
            if (ScanTimeframe.isSupportedStored(x.analysisTimeframeMinutes)) append(" • Analiz ${ScanTimeframe.displayLabel(x.analysisTimeframeMinutes)}")
            if (x.scanCadenceMinutes > 0) append(" • Cadence ${x.scanCadenceMinutes} DK")
            append(" • Trend ${trendStyle.arrow} ${trendStyle.label}")
            append("\nEnsemble: ${x.ensembleStatus}\n${x.strategyWeightsLabel}")
            x.dataAgeMs?.let { append(" • Yaş ${MarketPresentationPolicy.formatDataAge(it)}") }
            x.riskPlan?.let { plan -> append(" • RR2 ").append(plan.rr2?.let { "1:%.2f".format(it) } ?: "—") }
        }

        holder.itemView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * holder.itemView.resources.displayMetrics.density
            setColor(ColorUtils.blendARGB(Color.rgb(5, 28, 45), trendAccent, 0.04f))
            setStroke((1.2f * holder.itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(trendAccent, 120))
        }

        holder.graph.setOnClickListener { click(x) }
        holder.detail.setOnClickListener { click(x) }
        holder.itemView.setOnClickListener { click(x) }
    }
    private fun bindPriceMovement(view: TextView, price: Double, changePct: Double?) {
        val validPrice = price.isFinite() && price > 0.0
        val validChange = changePct?.isFinite() == true
        if (!validPrice || !validChange) {
            view.text = "Fiyat/değişim verisi yok"
            view.setTextColor(view.context.getColor(R.color.text_secondary))
            return
        }
        val change = changePct ?: return
        val movementColor = when {
            change > 0.0 -> R.color.green
            change < 0.0 -> R.color.red
            else -> R.color.text_secondary
        }
        val arrow = when {
            change > 0.0 -> "▲"
            change < 0.0 -> "▼"
            else -> "•"
        }
        view.text = "${"%.2f".format(price)}  $arrow ${"%+.2f".format(change)}%"
        view.setTextColor(view.context.getColor(movementColor))
        view.contentDescription = when {
            change > 0.0 -> "Yükseliş, fiyat ${"%.2f".format(price)}, değişim yüzde ${"%+.2f".format(change)}"
            change < 0.0 -> "Düşüş, fiyat ${"%.2f".format(price)}, değişim yüzde ${"%+.2f".format(change)}"
            else -> "Değişim yok, fiyat ${"%.2f".format(price)}"
        }
    }

}
