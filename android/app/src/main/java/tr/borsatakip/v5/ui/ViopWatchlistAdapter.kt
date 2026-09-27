package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.ViopContractSearch
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.Stock

class ViopWatchlistAdapter(
    private val items: List<Row>,
    private val onOpen: (Row) -> Unit,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<ViopWatchlistAdapter.H>() {

    data class Row(val symbol: String, val contract: ViopContract?, val underlyingStock: Stock? = null)

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val symbol = v.findViewById<TextView>(R.id.viopWatchSymbol)
        val underlying = v.findViewById<TextView>(R.id.viopWatchUnderlying)
        val last = v.findViewById<TextView>(R.id.viopWatchLast)
        val change = v.findViewById<TextView>(R.id.viopWatchChange)
        val status = v.findViewById<TextView>(R.id.viopWatchStatus)
        val remove = v.findViewById<TextView>(R.id.viopWatchRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = H(
        LayoutInflater.from(parent.context).inflate(R.layout.item_viop_watchlist, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val row = items[position]
        val c = row.contract
        holder.symbol.text = row.symbol
        holder.underlying.text = c?.let {
            buildString {
                append(ViopContractSearch.displayUnderlying(it))
                if (it.expiry.isNotBlank() && it.expiry != "-") append(" • ").append(it.expiry)
            }
        } ?: "Sözleşme verisi bekleniyor"

        holder.last.text = c?.lastPrice?.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "—"
        val change = c?.dailyChangePct?.takeIf { it.isFinite() }
        holder.change.text = change?.let { "%+.2f%%".format(it) } ?: "—"

        val color = holder.itemView.context.getColor(when {
            change == null -> R.color.text_secondary
            change > 0.0 -> R.color.green
            change < 0.0 -> R.color.red
            else -> R.color.text_secondary
        })
        holder.change.setTextColor(color)
        holder.last.setTextColor(if (change == null) holder.itemView.context.getColor(R.color.text_primary) else color)

        val underlyingPrice = row.underlyingStock?.quotePrice?.takeIf { it.isFinite() && it > 0.0 }
        holder.status.text = c?.let {
            when {
                underlyingPrice != null -> "Dayanak ${"%.2f".format(underlyingPrice)}"
                it.providerId == "builtin_catalog" -> "Katalog"
                it.validity == SignalValidity.REJECTED -> "Reddedildi"
                it.validity == SignalValidity.INSUFFICIENT -> "Yetersiz"
                it.isRealtime || it.dataMode == DataMode.REALTIME -> "Güncel"
                (it.delaySeconds ?: 0) > 0 || it.dataMode == DataMode.DELAYED -> "Gecikmeli"
                it.validity == SignalValidity.WATCH -> "İzleme"
                else -> it.status.take(12).ifBlank { "Bilinmiyor" }
            }
        } ?: "Veri yok"

        holder.remove.setOnClickListener { onRemove(row.symbol) }
        holder.itemView.setOnClickListener { onOpen(row) }
    }
}
