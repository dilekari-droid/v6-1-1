package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.Stock

class BistWatchlistAdapter(
    private val items: List<Row>,
    private val onOpen: (Row) -> Unit,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<BistWatchlistAdapter.H>() {

    data class Row(val symbol: String, val stock: Stock?, val note: String? = null)

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val symbol = v.findViewById<TextView>(R.id.watchSymbol)
        val company = v.findViewById<TextView>(R.id.watchCompany)
        val last = v.findViewById<TextView>(R.id.watchLast)
        val bid = v.findViewById<TextView>(R.id.watchBid)
        val ask = v.findViewById<TextView>(R.id.watchAsk)
        val change = v.findViewById<TextView>(R.id.watchChange)
        val remove = v.findViewById<TextView>(R.id.watchRemove)
        val meta = v.findViewById<TextView>(R.id.watchMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = H(
        LayoutInflater.from(parent.context).inflate(R.layout.item_bist_watchlist, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val row = items[position]
        val stock = row.stock
        holder.symbol.text = row.symbol
        holder.company.text = stock?.companyName ?: row.note ?: "BIST hissesi"

        val lastPrice = stock?.quotePrice ?: stock?.candles?.lastOrNull()?.close
        holder.last.text = lastPrice?.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "—"
        holder.bid.text = stock?.bid?.takeIf { it.isFinite() && it > 0.0 }?.let { "%.2f".format(it) } ?: "—"
        holder.ask.text = stock?.ask?.takeIf { it.isFinite() && it > 0.0 }?.let { "%.2f".format(it) } ?: "—"

        val change = stock?.let { s ->
            val price = s.quotePrice ?: s.candles.lastOrNull()?.close
            val prev = s.previousClose ?: s.candles.dropLast(1).lastOrNull()?.close
            if (price != null && prev != null && price.isFinite() && prev.isFinite() && prev > 0.0) {
                (price / prev - 1.0) * 100.0
            } else null
        }
        holder.change.text = change?.let { "%+.2f%%".format(it) } ?: "—"

        val movementColor = holder.itemView.context.getColor(
            when {
                change == null -> R.color.text_secondary
                change > 0.0 -> R.color.green
                change < 0.0 -> R.color.red
                else -> R.color.text_secondary
            }
        )
        holder.change.setTextColor(movementColor)
        holder.last.setTextColor(if (change == null) holder.itemView.context.getColor(R.color.text_primary) else movementColor)
        holder.bid.setTextColor(holder.itemView.context.getColor(if (stock?.bid != null) R.color.green else R.color.text_secondary))
        holder.ask.setTextColor(holder.itemView.context.getColor(if (stock?.ask != null) R.color.red else R.color.text_secondary))

        holder.meta.text = stock?.let { s ->
            when {
                s.isRealtime -> "Canlı"
                s.source.contains("yahoo", ignoreCase = true) -> "Yedek/Gecikmeli"
                (s.delaySeconds ?: 0) > 0 -> "Gecikmeli"
                else -> "Gerçek zaman doğrulanmadı"
            }
        } ?: (row.note ?: "Veri yok")

        holder.remove.setOnClickListener { onRemove(row.symbol) }
        holder.itemView.setOnClickListener { onOpen(row) }
    }
}
