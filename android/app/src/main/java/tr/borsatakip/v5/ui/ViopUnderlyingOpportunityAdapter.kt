package tr.borsatakip.v5.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.ViopSignalPolicy
import tr.borsatakip.v5.analysis.ViopUnderlyingScanner
import tr.borsatakip.v5.data.MarketDataQuality
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViopUnderlyingOpportunityAdapter(
    private val items: List<ViopUnderlyingScanner.Candidate>,
    private val onOpen: (ViopUnderlyingScanner.Candidate) -> Unit
) : RecyclerView.Adapter<ViopUnderlyingOpportunityAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val symbol: TextView = v.findViewById(R.id.viopSymbol)
        val klass: TextView = v.findViewById(R.id.viopClass)
        val subtitle: TextView = v.findViewById(R.id.viopSubtitle)
        val direction: TextView = v.findViewById(R.id.viopDirection)
        val scores: TextView = v.findViewById(R.id.viopScores)
        val levels: TextView = v.findViewById(R.id.viopLevels)
        val reason: TextView = v.findViewById(R.id.viopReason)
        val detail: TextView = v.findViewById(R.id.viopDetailButton)
        val chart: ViopSparklineView = v.findViewById(R.id.viopMiniChart)
        val price: TextView = v.findViewById(R.id.viopPrice)
        val change: TextView = v.findViewById(R.id.viopChange)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        H(LayoutInflater.from(parent.context).inflate(R.layout.item_viop_opportunity, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val x = items[position]
        val o = x.underlying
        val bias = ViopSignalPolicy.analysisBias(o)
        val biasScore = ViopSignalPolicy.underlyingBiasScore(o)

        holder.symbol.text = x.contract.underlying.uppercase(Locale.ROOT)
        holder.subtitle.text = "DAYANAK ANALİZİ • Referans vade ${x.contract.expiry}"
        holder.klass.text = "Eğilim %$biasScore"
        holder.klass.setTextColor(Color.parseColor("#FFC928"))

        holder.price.text = o.price.takeIf { it.isFinite() && it > 0.0 }?.let { "Dayanak ${formatPrice(it)}" } ?: "Dayanak —"
        holder.change.text = o.dailyChangePct?.let { "%+.2f%%".format(it) } ?: "Değişim —"
        holder.change.setTextColor(
            when {
                (o.dailyChangePct ?: 0.0) > 0.0 -> Color.parseColor("#00E676")
                (o.dailyChangePct ?: 0.0) < 0.0 -> Color.parseColor("#FF3B4D")
                else -> Color.parseColor("#8FA3B8")
            }
        )
        holder.chart.setDirection(bias.name)
        holder.chart.setCandles(o.candles)

        holder.direction.text = when (bias) {
            ViopSignalPolicy.AnalysisBias.LONG -> "LONG EĞİLİMİ"
            ViopSignalPolicy.AnalysisBias.SHORT -> "SHORT EĞİLİMİ"
            ViopSignalPolicy.AnalysisBias.NEUTRAL -> "NÖTR"
        }
        holder.direction.setTextColor(
            when (bias) {
                ViopSignalPolicy.AnalysisBias.LONG -> Color.parseColor("#00E676")
                ViopSignalPolicy.AnalysisBias.SHORT -> Color.parseColor("#FF3B4D")
                ViopSignalPolicy.AnalysisBias.NEUTRAL -> Color.parseColor("#8EB2D9")
            }
        )

        holder.scores.text = "Sıralama %${o.rankingScore.coerceIn(0, 100)} • Güven %${o.dataConfidenceScore.coerceIn(0, 100)}\n${MarketDataQuality.uiStatus(o.marketDataMetadata)}"
        holder.levels.text = "VİOP fiyatı/sinyali yok\nDayanak veri zamanı ${timeOf(o.exchangeTimestamp)}"
        holder.reason.text = "Gerçek VİOP kontratı değildir • Bunlar dayanak teknik eğilimleridir; işlem sinyali değildir."
        holder.detail.text = "›"
        holder.detail.setOnClickListener { onOpen(x) }
        holder.itemView.setOnClickListener { onOpen(x) }
    }

    private fun formatPrice(v: Double) = if (v >= 1000.0) "%,.2f".format(v) else "%.2f".format(v)

    private fun timeOf(ms: Long) = if (ms <= 0L) "—" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}
