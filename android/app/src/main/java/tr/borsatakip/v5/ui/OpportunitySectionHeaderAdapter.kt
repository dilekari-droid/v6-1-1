package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R

class OpportunitySectionHeaderAdapter(
    private val title: String,
    private val count: Int,
    private val colorRes: Int
) : RecyclerView.Adapter<OpportunitySectionHeaderAdapter.H>() {

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.sectionTitle)
        val count: TextView = v.findViewById(R.id.sectionCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H =
        H(LayoutInflater.from(parent.context).inflate(R.layout.item_opportunity_section_header, parent, false))

    override fun getItemCount(): Int = 1

    override fun onBindViewHolder(holder: H, position: Int) {
        holder.title.text = title
        holder.title.setTextColor(holder.itemView.context.getColor(colorRes))
        holder.count.text = count.toString()
    }
}
