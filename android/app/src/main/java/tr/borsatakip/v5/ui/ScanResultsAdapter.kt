package tr.borsatakip.v5.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.TrendUiPolicy
import tr.borsatakip.v5.analysis.TrendUiStyle
import tr.borsatakip.v5.data.ScanTimeframe
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tarama Sonuçları ekranına özel sunum katmanı.
 * Opportunity modelini veya sinyal hesaplarını değiştirmez; yalnızca mevcut gerçek değerleri
 * okunabilir bölümlere ayırır.
 */
class ScanResultsAdapter(
    private val items: List<Opportunity>,
    private val click: (Opportunity) -> Unit
) : RecyclerView.Adapter<ScanResultsAdapter.Holder>() {

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val company: TextView = v.findViewById(R.id.company)
        val symbol: TextView = v.findViewById(R.id.symbol)
        val priceMovement: TextView = v.findViewById(R.id.priceMovement)
        val direction: TextView = v.findViewById(R.id.directionBadge)
        val strengthBar: ProgressBar = v.findViewById(R.id.strengthBar)
        val strengthValue: TextView = v.findViewById(R.id.strengthValue)
        val strengthTier: TextView = v.findViewById(R.id.strengthTier)
        val technicalScore: TextView = v.findViewById(R.id.technicalScore)
        val riskScore: TextView = v.findViewById(R.id.riskScore)
        val confidenceScore: TextView = v.findViewById(R.id.confidenceScore)
        val dataMode: TextView = v.findViewById(R.id.dataMode)
        val source: TextView = v.findViewById(R.id.source)
        val timeInfo: TextView = v.findViewById(R.id.timeInfo)
        val levels: TextView = v.findViewById(R.id.levels)
        val coverage: TextView = v.findViewById(R.id.coverage)
        val reason: TextView = v.findViewById(R.id.reason)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_scan_result, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val symbol = item.symbol.trim().uppercase(Locale.ROOT)
        val company = item.companyName?.trim().takeUnless { it.isNullOrBlank() } ?: symbol
        val delayedObservation = item.dataMode != DataMode.REALTIME || !item.isRealtime
        val direction = if (delayedObservation) "TEKNİK İZLEME" else item.direction.trim().uppercase(Locale.ROOT)
        val score = if (delayedObservation) item.score.coerceIn(0, 100) else item.finalSignalScore.coerceIn(0, 100)
        val trendStyle = TrendUiPolicy.resolve(item)

        val timeframeLabel = ScanTimeframe.displayLabel(item.analysisTimeframeMinutes)
        holder.company.text = company
        holder.symbol.text = "$symbol — $timeframeLabel FIRSAT"
        bindPriceMovement(holder.priceMovement, item.price, item.dailyChangePct)
        val directionLabel = when {
            delayedObservation -> "TEKNİK İZLEME"
            direction.equals("LONG", true) -> "▲ AL ADAYI"
            direction.equals("SHORT", true) -> "▼ SAT ADAYI"
            else -> "İZLE"
        }
        holder.direction.text = directionLabel
        holder.direction.contentDescription = if (delayedObservation) "Gecikmeli teknik izleme; AL/SAT sinyali yok" else "Sinyal yönü: $directionLabel"
        holder.strengthBar.progress = score
        holder.strengthValue.text = "$score/100"
        holder.strengthTier.text = if (delayedObservation) {
            "TREND: ${trendStyle.arrow} ${trendStyle.label} • TEKNİK PUAN • GECİKMELİ VERİ • AL/SAT YOK"
        } else {
            "TREND: ${trendStyle.arrow} ${trendStyle.label} • ${strengthLabel(score)} • ${validityLabel(item)}"
        }
        holder.technicalScore.text = "TEKNİK SKOR\n${item.score.coerceIn(0, 100)}/100"
        holder.riskScore.text = "RİSK\n${item.riskScore.coerceIn(0, 100)}/100"
        holder.confidenceScore.text = "VERİ GÜVENİ\n${item.dataConfidenceScore.coerceIn(0, 100)}/100\n${item.dataConfidenceLabel}"

        holder.dataMode.text = dataModeLabel(item.dataMode)
        holder.source.text = buildString {
            append("Kaynak: ${item.source}")
            item.delaySeconds?.let { append(" • Sağlayıcı gecikmesi: $it sn") }
        }

        val marketTime = formatTimestamp(item.exchangeTimestamp)
        val receivedTime = formatTimestamp(item.receivedAt)
        holder.timeInfo.text = buildString {
            append("Timeframe: $timeframeLabel\n")
            append("Piyasa zamanı: $marketTime\n")
            append("Veri yaşı: ${measuredAge(item)}")
            if (receivedTime != "bilinmiyor") append(" • Uygulamaya geliş: $receivedTime")
        }

        holder.levels.text = buildString {
            append("Destek: ${priceOrMissing(item.support)}")
            append("   •   Direnç: ${priceOrMissing(item.resistance)}")
        }

        val hasPrice = item.price.isFinite() && item.price > 0.0
        val hasVolume = !item.volumeLabel.equals("Veri yok", true) && item.volumeLabel.isNotBlank()
        val hasOhlcv = item.candles.isNotEmpty()
        val hasKap = !item.kapLabel.equals("Veri yok", true) && item.kapLabel.isNotBlank()
        holder.coverage.text = "Veri kapsamı: Fiyat ${mark(hasPrice)} • Hacim ${mark(hasVolume)} • OHLCV ${mark(hasOhlcv)} • KAP ${mark(hasKap)}"

        val factors = item.scoreBreakdown.asSequence()
            .takeWhile { !it.startsWith("KAP:") }
            .filter { it.contains(": +") }
            .map { it.substringBefore(":").trim() }
            .distinct()
            .take(4)
            .toList()
        holder.reason.text = buildString {
            append(if (delayedObservation) "Teknik analiz özeti: " else "Sinyal özeti: ")
            append(if (delayedObservation) item.signalValidityReason else if (factors.isEmpty()) item.signalValidityReason else factors.joinToString(" • "))
        }

        applyVisuals(holder, direction, score, delayedObservation, trendStyle)
        holder.itemView.setOnClickListener { click(item) }
    }

    private fun applyVisuals(
        holder: Holder,
        direction: String,
        score: Int,
        delayedObservation: Boolean,
        trendStyle: TrendUiStyle
    ) {
        val isLong = direction.equals("LONG", true)
        val isShort = direction.equals("SHORT", true)
        val signalAccent = when {
            delayedObservation -> Color.rgb(96, 165, 250)
            isLong -> Color.rgb(0, 240, 128)
            isShort -> Color.rgb(255, 69, 69)
            else -> Color.rgb(250, 204, 21)
        }
        val trendAccent = Color.rgb(trendStyle.red, trendStyle.green, trendStyle.blue)
        val strength = score / 100f
        val border = ColorUtils.blendARGB(trendAccent, Color.WHITE, strength * 0.20f)
        holder.itemView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(holder.itemView, 14f)
            setColor(ColorUtils.blendARGB(Color.rgb(6, 38, 58), trendAccent, 0.13f))
            setStroke(dp(holder.itemView, if (score >= 85) 2f else 1f).toInt().coerceAtLeast(1), border)
        }
        holder.direction.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(holder.itemView, 12f)
            setColor(ColorUtils.setAlphaComponent(signalAccent, 42))
            setStroke(dp(holder.itemView, 1.5f).toInt().coerceAtLeast(1), signalAccent)
        }
        holder.direction.setTextColor(signalAccent)
        holder.strengthValue.setTextColor(trendAccent)
        holder.strengthBar.progressTintList = ColorStateList.valueOf(trendAccent)
        holder.strengthBar.progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(trendAccent, 40))
    }

    private fun bindPriceMovement(view: TextView, price: Double, changePct: Double?) {
        val validPrice = price.isFinite() && price > 0.0
        val validChange = changePct?.isFinite() == true
        if (!validPrice || !validChange) {
            view.text = "Fiyat/değişim verisi yok"
            view.setTextColor(view.context.getColor(R.color.text_secondary))
            return
        }
        val change = changePct ?: return
        val movementColor = when {
            change > 0.0 -> R.color.green
            change < 0.0 -> R.color.red
            else -> R.color.text_secondary
        }
        val arrow = when {
            change > 0.0 -> "▲"
            change < 0.0 -> "▼"
            else -> "•"
        }
        view.text = "${"%.2f".format(price)}  $arrow ${"%+.2f".format(change)}%"
        view.setTextColor(view.context.getColor(movementColor))
        view.contentDescription = when {
            change > 0.0 -> "Yükseliş, fiyat ${"%.2f".format(price)}, değişim yüzde ${"%+.2f".format(change)}"
            change < 0.0 -> "Düşüş, fiyat ${"%.2f".format(price)}, değişim yüzde ${"%+.2f".format(change)}"
            else -> "Değişim yok, fiyat ${"%.2f".format(price)}"
        }
    }

    private fun strengthLabel(score: Int): String = when {
        score >= 90 -> "ÇOK GÜÇLÜ SİNYAL"
        score >= 85 -> "GÜÇLÜ SİNYAL"
        score >= 70 -> "ORTA SİNYAL"
        else -> "ZAYIF SİNYAL"
    }

    private fun validityLabel(item: Opportunity): String = when {
        item.decisionState == DecisionState.VERIFIED_OPPORTUNITY && item.signalValidity == SignalValidity.VALID -> "Doğrulanmış fırsat"
        item.signalValidity == SignalValidity.VALID -> "Veri geçerli • karar izleme"
        item.signalValidity == SignalValidity.WATCH -> "İzleme"
        item.signalValidity == SignalValidity.INSUFFICIENT -> "Yetersiz veri"
        else -> "Reddedildi"
    }

    private fun dataModeLabel(mode: DataMode): String = when (mode) {
        DataMode.REALTIME -> "CANLI VERİ"
        DataMode.DELAYED -> "GECİKMELİ VERİ"
        DataMode.EOD -> "GÜN SONU VERİSİ"
        DataMode.UNVERIFIED -> "DOĞRULANMAMIŞ VERİ"
    }

    private fun measuredAge(item: Opportunity): String {
        if (item.receivedElapsedRealtime <= 0L) return "yeniden başlatma sonrası doğrulanamıyor"
        val elapsedSinceReceipt = (SystemClock.elapsedRealtime() - item.receivedElapsedRealtime).coerceAtLeast(0L)
        val ageAtReceipt = if (item.exchangeTimestamp > 0L && item.receivedAt > 0L) {
            (item.receivedAt - item.exchangeTimestamp).coerceAtLeast(0L)
        } else 0L
        return formatAge(ageAtReceipt + elapsedSinceReceipt)
    }

    private fun formatTimestamp(value: Long): String = if (value > 0L) {
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(value))
    } else "bilinmiyor"

    private fun formatAge(ms: Long): String = when {
        ms < 60_000L -> "${ms / 1000L} sn"
        ms < 3_600_000L -> "${ms / 60_000L} dk"
        ms < 86_400_000L -> "${ms / 3_600_000L} sa"
        else -> "${ms / 86_400_000L} gün"
    }

    private fun priceOrMissing(value: Double?): String = value?.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "veri yok"
    private fun mark(ok: Boolean): String = if (ok) "✓" else "—"
    private fun dp(view: View, value: Float): Float = value * view.resources.displayMetrics.density
}
