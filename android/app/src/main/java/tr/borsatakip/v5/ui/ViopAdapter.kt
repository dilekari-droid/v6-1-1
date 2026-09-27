package tr.borsatakip.v5.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViopAdapter(
    private val items: List<ViopContract>,
    private val onClick: ((ViopContract) -> Unit)? = null,
    private val onLongPress: ((ViopContract) -> Unit)? = null
) : RecyclerView.Adapter<ViopAdapter.H>() {
    class H(v: View) : RecyclerView.ViewHolder(v) { val text = v.findViewById<TextView>(R.id.text) }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = H(LayoutInflater.from(parent.context).inflate(R.layout.item_viop, parent, false))
    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val x = items[position]
        fun f(v: Double?): String = v?.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "—"
        fun fp(v: Double?): String = v?.takeIf { it.isFinite() }?.let { "%+.2f%%".format(it) } ?: "—"
        fun time(v:Long) = if(v>0) SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(v)) else "bilinmiyor"
        val validity = when(x.validity) {
            SignalValidity.VALID -> "DOĞRULANMIŞ"
            SignalValidity.WATCH -> "İZLEME"
            SignalValidity.INSUFFICIENT -> "YETERSİZ VERİ"
            SignalValidity.REJECTED -> "REDDEDİLDİ"
        }
        holder.text.text = buildString {
            append("${x.symbol}   ${f(x.lastPrice)} (${fp(x.dailyChangePct)})\n")
            append("Dayanak: ${x.underlying} • Vade: ${x.expiry} • Tür: ${x.contractType}\n")
            append("Alış: ${f(x.bid)} • Satış: ${f(x.ask)}\n")
            append("Fiyat adımı: ${f(x.tickSize)} • Çarpan: ${f(x.multiplier)}\n")
            append("Açık pozisyon: ${x.openInterest ?: "—"} • Hacim: ${f(x.volume)}\n")
            append("Kaynak: ${x.providerLabel} • Veri Modu: ${x.dataMode.name} • $validity\n")
            append("Piyasa Veri Zamanı: ${time(x.exchangeTimestamp)} • Uygulamaya Ulaşma: ${time(x.receivedAt)}\n")
            append(x.validityReason)
            if (x.isManual) append("\nManuel kayıt: fiyat/sinyal doğrulaması yapılmaz • Uzun bas: sil")
        }
        holder.itemView.setOnClickListener { onClick?.invoke(x) }
        holder.itemView.setOnLongClickListener {
            if (x.isManual && onLongPress != null) { onLongPress.invoke(x); true } else false
        }
    }
}
