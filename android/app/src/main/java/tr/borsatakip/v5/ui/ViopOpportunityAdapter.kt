package tr.borsatakip.v5.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.ViopSignalPolicy
import tr.borsatakip.v5.analysis.SignalVisualPolicy
import tr.borsatakip.v5.analysis.ViopContractCategoryPolicy
import tr.borsatakip.v5.data.MarketDataQuality
import tr.borsatakip.v5.data.ViopWatchlistStore
import tr.borsatakip.v5.model.ViopOpportunity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViopOpportunityAdapter(
    private val items: List<ViopOpportunity>,
    private val click: ((ViopOpportunity) -> Unit)? = null
) : RecyclerView.Adapter<ViopOpportunityAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) {
        val symbol: TextView = v.findViewById(R.id.viopSymbol)
        val klass: TextView = v.findViewById(R.id.viopClass)
        val subtitle: TextView = v.findViewById(R.id.viopSubtitle)
        val direction: TextView = v.findViewById(R.id.viopDirection)
        val scores: TextView = v.findViewById(R.id.viopScores)
        val levels: TextView = v.findViewById(R.id.viopLevels)
        val reason: TextView = v.findViewById(R.id.viopReason)
        val favorite: TextView = v.findViewById(R.id.viopFavorite)
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
        val q = x.quote
        val c = x.contract
        val published = ViopSignalPolicy.publishedDirection(x)

        holder.symbol.text = c.symbol.uppercase(Locale.ROOT)
        holder.subtitle.text = "${c.underlying.uppercase(Locale.ROOT)} • ${c.contractType} • Vade ${c.expiry}"
        val signalVisual = when (published) {
            ViopSignalPolicy.PublishedDirection.LONG -> SignalVisualPolicy.resolve("LONG", x.longScore, x.shortScore)
            ViopSignalPolicy.PublishedDirection.SHORT -> SignalVisualPolicy.resolve("SHORT", x.longScore, x.shortScore)
            ViopSignalPolicy.PublishedDirection.WATCH -> null
        }
        val signalColor = signalVisual?.let { Color.rgb(it.red, it.green, it.blue) } ?: Color.parseColor("#8FA3B8")
        val signalStrength = signalVisual?.quality
        holder.klass.text = signalStrength?.let { "Güç %$it" } ?: "İZLE"
        holder.klass.setTextColor(signalColor)

        holder.price.text = formatPrice(q.price)
        holder.change.text = q.dailyChangePct?.let { pct -> "%+.2f%%".format(pct) } ?: "—"
        holder.change.setTextColor(
            when {
                (q.dailyChangePct ?: 0.0) > 0.0 -> Color.parseColor("#00E676")
                (q.dailyChangePct ?: 0.0) < 0.0 -> Color.parseColor("#FF3B4D")
                else -> Color.parseColor("#8FA3B8")
            }
        )
        holder.chart.setDirection(when (published) {
            ViopSignalPolicy.PublishedDirection.LONG -> "LONG"
            ViopSignalPolicy.PublishedDirection.SHORT -> "SHORT"
            ViopSignalPolicy.PublishedDirection.WATCH -> "WATCH"
        })
        holder.chart.setCandles(x.candles)

        holder.direction.text = when (published) {
            ViopSignalPolicy.PublishedDirection.LONG -> "VİOP LONG"
            ViopSignalPolicy.PublishedDirection.SHORT -> "VİOP SHORT"
            ViopSignalPolicy.PublishedDirection.WATCH -> "İZLE"
        }
        holder.direction.setTextColor(signalColor)

        holder.scores.text = "Sıralama %${x.rankingScore.coerceIn(0, 100)} • Güven %${x.dataConfidenceScore.coerceIn(0, 100)}\nRisk %${x.riskScore.coerceIn(0, 100)} • Likidite %${x.liquidityScore.coerceIn(0, 100)}"
        val rr = x.riskPlan?.rr1?.let { "RR %.2f".format(it) } ?: "RR —"
        holder.levels.text = "Hacim ${q.volume?.let(::formatCompact) ?: "—"} • OI ${q.openInterest ?: "—"}\n$rr • ${MarketDataQuality.uiStatus(x.marketDataMetadata)} • ${timeOf(q.exchangeTimestamp)}"
        holder.reason.text = when (published) {
            ViopSignalPolicy.PublishedDirection.WATCH -> "Nihai publication: İZLE • Analitik eğilim ${x.analysisBias}"
            else -> "Nihai publication: ${published.name} • ${x.signalReasonCodes.take(3).joinToString(" • ")}"
        }
        holder.detail.text = "›"
        val category = ViopContractCategoryPolicy.classify(c)
        val watchlist = ViopWatchlistStore(holder.itemView.context)
        val watched = c.symbol.uppercase(Locale.ROOT) in watchlist.symbols(category)
        holder.favorite.text = if (watched) "★" else "☆"
        holder.favorite.setOnClickListener {
            if (watched) watchlist.remove(category, c.symbol) else watchlist.add(category, c.symbol)
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
        }

        val tap = View.OnClickListener { click?.invoke(x) }
        holder.itemView.setOnClickListener(tap)
        holder.detail.setOnClickListener(tap)
    }

    private fun formatPrice(v: Double) = if (v >= 1000.0) "%,.2f".format(v) else "%.2f".format(v)
    private fun formatCompact(v: Double) = when {
        v >= 1_000_000 -> "%.1f M".format(v / 1_000_000.0)
        v >= 1_000 -> "%.1f K".format(v / 1_000.0)
        else -> "%.0f".format(v)
    }
    private fun timeOf(ms: Long) = if (ms <= 0L) "—" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}
