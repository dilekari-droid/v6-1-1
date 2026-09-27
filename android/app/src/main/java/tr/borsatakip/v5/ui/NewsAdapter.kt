package tr.borsatakip.v5.ui

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.NewsItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NewsAdapter(private val items: List<NewsItem>) : RecyclerView.Adapter<NewsAdapter.VH>() {
    class VH(v: View): RecyclerView.ViewHolder(v) {
        val badge:TextView=v.findViewById(R.id.newsBadge); val time:TextView=v.findViewById(R.id.newsTime)
        val headline:TextView=v.findViewById(R.id.newsHeadline); val summary:TextView=v.findViewById(R.id.newsSummary); val source:TextView=v.findViewById(R.id.newsSource)
    }
    override fun onCreateViewHolder(p:ViewGroup,t:Int)=VH(LayoutInflater.from(p.context).inflate(R.layout.item_news,p,false))
    override fun getItemCount()=items.size
    override fun onBindViewHolder(h:VH,pos:Int){ val x=items[pos]; h.badge.text=listOfNotNull(x.symbol,x.category).joinToString(" • "); h.time.text=SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr","TR")).format(Date(x.publishedAt)); h.headline.text=x.title; h.summary.text=x.summary ?: "Özet sağlanmadı."; h.source.text="${x.source} • ${if(x.verified) "DOĞRULANMIŞ" else "DOĞRULANAMADI"}"; h.itemView.setOnClickListener { x.url?.let { u -> h.itemView.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } } }
}
