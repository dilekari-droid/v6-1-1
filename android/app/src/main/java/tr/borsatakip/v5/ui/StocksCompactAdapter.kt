package tr.borsatakip.v5.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.OpportunityDirectionalFilterPolicy
import tr.borsatakip.v5.data.favorites.FavoriteRepository
import tr.borsatakip.v5.model.Opportunity
import java.util.Locale

class StocksCompactAdapter(
    private val items: List<Opportunity>,
    private val favoriteSymbols: Set<String>,
    private val click: (Opportunity) -> Unit,
    private val toggleFavorite: (Opportunity) -> Unit
) : RecyclerView.Adapter<StocksCompactAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val favorite: TextView = view.findViewById(R.id.stockFavorite)
        val symbol: TextView = view.findViewById(R.id.stockSymbol)
        val company: TextView = view.findViewById(R.id.stockCompany)
        val direction: TextView = view.findViewById(R.id.stockDirection)
        val chart: StockSparklineView = view.findViewById(R.id.stockMiniChart)
        val price: TextView = view.findViewById(R.id.stockPrice)
        val change: TextView = view.findViewById(R.id.stockChange)
        val volume: TextView = view.findViewById(R.id.stockVolume)
        val more: TextView = view.findViewById(R.id.stockMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_stock_compact, parent, false)
    )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val symbol = FavoriteRepository.normalizeSymbol(item.symbol)
        val isFavorite = favoriteSymbols.contains(symbol)
        val effectiveDirection = OpportunityDirectionalFilterPolicy.effectiveDirection(item)
        val direction = effectiveDirection.name
        val changePct = item.dailyChangePct?.takeIf(Double::isFinite)
        val accent = when {
            direction == "LONG" -> holder.itemView.context.getColor(R.color.green)
            direction == "SHORT" -> holder.itemView.context.getColor(R.color.red)
            (changePct ?: 0.0) > 0.0 -> holder.itemView.context.getColor(R.color.green)
            (changePct ?: 0.0) < 0.0 -> holder.itemView.context.getColor(R.color.red)
            else -> holder.itemView.context.getColor(R.color.stroke)
        }

        holder.favorite.text = if (isFavorite) "★" else "☆"
        holder.favorite.setTextColor(if (isFavorite) holder.itemView.context.getColor(R.color.yellow) else holder.itemView.context.getColor(R.color.text_secondary))
        holder.favorite.contentDescription = if (isFavorite) "Favorilerden çıkar" else "Favoriye ekle"
        holder.favorite.setOnClickListener { toggleFavorite(item) }

        holder.symbol.text = symbol
        holder.company.text = item.companyName?.trim().takeUnless { it.isNullOrBlank() } ?: "Şirket adı yok"
        val verified = item.signalValidity == tr.borsatakip.v5.model.SignalValidity.VALID &&
            item.direction.equals(direction, ignoreCase = true)
        holder.direction.text = when (effectiveDirection) {
            OpportunityDirectionalFilterPolicy.Direction.LONG -> if (verified) "LONG" else "LONG EĞİLİMİ"
            OpportunityDirectionalFilterPolicy.Direction.SHORT -> if (verified) "SHORT" else "SHORT EĞİLİMİ"
            OpportunityDirectionalFilterPolicy.Direction.NEUTRAL -> "NÖTR"
        }
        holder.direction.setTextColor(accent)

        holder.chart.setCandles(item.candles, changePct)
        holder.price.text = item.price.takeIf { it.isFinite() && it > 0.0 }?.let(::formatPrice) ?: "—"
        holder.change.text = changePct?.let { "%+.2f%%".format(Locale.getDefault(), it) } ?: "—"
        holder.change.setTextColor(
            when {
                changePct == null -> holder.itemView.context.getColor(R.color.text_secondary)
                changePct > 0.0 -> holder.itemView.context.getColor(R.color.green)
                changePct < 0.0 -> holder.itemView.context.getColor(R.color.red)
                else -> holder.itemView.context.getColor(R.color.text_secondary)
            }
        )
        holder.volume.text = item.technical.volumeRatio?.takeIf(Double::isFinite)?.let {
            "Hacim ${"%.1fx".format(Locale.getDefault(), it)}"
        } ?: "Hacim —"
        holder.more.text = "⋮"
        holder.more.contentDescription = "Hisse detayını aç"

        holder.itemView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * holder.itemView.resources.displayMetrics.density
            setColor(Color.rgb(5, 28, 45))
            setStroke(
                (1f * holder.itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                ColorUtils.setAlphaComponent(accent, 150)
            )
        }
        val tap = View.OnClickListener { click(item) }
        holder.itemView.setOnClickListener(tap)
        holder.more.setOnClickListener(tap)
    }

    private fun formatPrice(value: Double): String = if (value >= 1000.0) {
        "%,.2f".format(Locale.getDefault(), value)
    } else {
        "%.2f".format(Locale.getDefault(), value)
    }
}
