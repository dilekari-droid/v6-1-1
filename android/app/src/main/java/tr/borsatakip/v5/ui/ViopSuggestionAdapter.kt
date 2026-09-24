package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.ViopContractSearch
import tr.borsatakip.v5.model.ViopContract

class ViopSuggestionAdapter(
    private val items: List<ViopContract>,
    private val selectedSymbols: Set<String>,
    private val onAdd: (ViopContract) -> Unit,
    private val onOpen: (ViopContract) -> Unit
) : RecyclerView.Adapter<ViopSuggestionAdapter.H>() {

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val symbol: TextView = v.findViewById(R.id.suggestionSymbol)
        val meta: TextView = v.findViewById(R.id.suggestionMeta)
        val last: TextView = v.findViewById(R.id.suggestionLast)
        val change: TextView = v.findViewById(R.id.suggestionChange)
        val action: TextView = v.findViewById(R.id.suggestionAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H = H(
        LayoutInflater.from(parent.context).inflate(R.layout.item_viop_suggestion, parent, false)
    )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val item = items[position]
        holder.symbol.text = item.symbol
        holder.meta.text = buildString {
            append(ViopContractSearch.displayUnderlying(item))
            if (item.expiry.isNotBlank() && item.expiry != "-") append(" • ").append(item.expiry)
        }
        holder.last.text = item.lastPrice?.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "—"
        val change = item.dailyChangePct?.takeIf { it.isFinite() }
        holder.change.text = change?.let { "%+.2f%%".format(it) } ?: "—"
        val color = holder.itemView.context.getColor(when {
            change == null -> R.color.text_secondary
            change > 0.0 -> R.color.green
            change < 0.0 -> R.color.red
            else -> R.color.text_secondary
        })
        holder.change.setTextColor(color)
        holder.last.setTextColor(if (change == null) holder.itemView.context.getColor(R.color.text_primary) else color)

        val selected = selectedSymbols.contains(item.symbol.uppercase())
        holder.action.text = if (selected) "✓" else "+"
        holder.action.setTextColor(holder.itemView.context.getColor(if (selected) R.color.green else R.color.blue))
        holder.action.setOnClickListener { if (!selected) onAdd(item) }
        holder.itemView.setOnClickListener { onOpen(item) }
    }
}
