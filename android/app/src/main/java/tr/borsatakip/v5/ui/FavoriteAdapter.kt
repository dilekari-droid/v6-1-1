package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.ScanTimeframe
import tr.borsatakip.v5.data.favorites.FavoriteStock
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.Stock

/**
 * Favoriler kartı B117 öncesindeki sade düzene döndürülmüştür.
 * Fiyat/günlük değişim için tarama sonucundaki eski Opportunity yerine mümkün olduğunda
 * yeni alınmış Stock quote'u kullanılır; Opportunity yalnız karar/skor özeti içindir.
 */
class FavoriteAdapter(
    private val items: List<FavoriteRow>,
    private val onRemove: (FavoriteStock) -> Unit,
    private val onOpen: (FavoriteRow) -> Unit
) : RecyclerView.Adapter<FavoriteAdapter.H>() {

    data class FavoriteRow(
        val favorite: FavoriteStock,
        val opportunity: Opportunity?,
        val stock: Stock?,
        val error: String? = null
    )

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val symbol = v.findViewById<TextView>(R.id.favoriteSymbol)
        val company = v.findViewById<TextView>(R.id.favoriteCompany)
        val details = v.findViewById<TextView>(R.id.favoriteDetails)
        val remove = v.findViewById<TextView>(R.id.favoriteRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = H(
        LayoutInflater.from(parent.context).inflate(R.layout.item_favorite, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val row = items[position]
        val fav = row.favorite
        val stock = row.stock
        val opportunity = row.opportunity
        holder.symbol.text = "★ ${fav.symbol}"
        holder.company.text = stock?.companyName ?: opportunity?.companyName ?: fav.displayName
            ?: "Şirket adı veri sağlayıcıdan alınamadı"

        val currentPrice = stock?.quotePrice?.takeIf { it.isFinite() && it > 0.0 }
            ?: stock?.candles?.lastOrNull()?.close?.takeIf { it.isFinite() && it > 0.0 }
        val currentChange = stockChange(stock)
        val sourceLabel = stock?.let { sourceState(it) } ?: "Veri yenilenemedi"

        holder.details.text = buildString {
            if (currentPrice != null) {
                append("Son fiyat ").append("%.2f TL".format(currentPrice))
                currentChange?.let { append(" • ").append("%+.2f%%".format(it)) }
                append(" • ").append(sourceLabel)
            } else {
                append("Güncel fiyat alınamadı")
                row.error?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it) }
                    ?: append(" • ").append(sourceLabel)
            }
            opportunity?.let {
                append("\nV5 ").append(it.finalSignalScore).append("/100")
                append(" • Risk ").append(it.riskScore).append("/100")
                val interval = it.analysisTimeframeMinutes.takeIf { value -> ScanTimeframe.isSupportedStored(value) }
                    ?: fav.analysisIntervalMinutes
                interval?.let { value -> append(" • Analiz ").append(ScanTimeframe.displayLabel(value)) }
                append(" • Karar: ").append(it.direction)
            }
        }

        holder.remove.setOnClickListener { onRemove(fav) }
        holder.itemView.setOnClickListener { onOpen(row) }
    }

    private fun stockChange(stock: Stock?): Double? {
        stock ?: return null
        val price = stock.quotePrice ?: stock.candles.lastOrNull()?.close ?: return null
        val prev = stock.previousClose ?: return null
        if (!price.isFinite() || !prev.isFinite() || price <= 0.0 || prev <= 0.0) return null
        return (price / prev - 1.0) * 100.0
    }

    private fun sourceState(stock: Stock): String = when {
        stock.isRealtime -> "Canlı • ${stock.source}"
        stock.source.contains("yahoo", ignoreCase = true) -> "Yedek/gecikmeli • ${stock.source}"
        (stock.delaySeconds ?: 0) > 0 -> "Gecikmeli • ${stock.source}"
        else -> "${stock.source} • gerçek zaman doğrulanmadı"
    }
}
