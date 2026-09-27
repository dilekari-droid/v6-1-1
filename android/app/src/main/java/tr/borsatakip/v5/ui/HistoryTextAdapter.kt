package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R

class HistoryTextAdapter(private val items: List<String>) : RecyclerView.Adapter<HistoryTextAdapter.Holder>() {
    class Holder(v: View) : RecyclerView.ViewHolder(v) { val text: TextView = v.findViewById(R.id.historyRowText) }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) { holder.text.text = items[position] }
}
