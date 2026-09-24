package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.AlertEventStore
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.data.MarketCapabilityClient
import tr.borsatakip.v5.data.TradingViewSignalClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationsActivity : BaseActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_notifications)
        setupBottomNav()

        val s = SettingsStore(this)
        findViewById<TextView>(R.id.notificationState).text = if (s.notifications) "AÇIK" else "KAPALI"
        findViewById<TextView>(R.id.notificationRefresh).text = "Yenileme aralığı: ${s.refreshMinutes} dk"
        val lines = AlertEventStore(this).recentLines()
        findViewById<TextView>(R.id.notificationEmpty).text = if (lines.isEmpty()) {
            "Henüz kalıcı alarm olayı yok. Olaylar gerçek tarama sonuçlarından üretildiğinde burada saklanır."
        } else lines.joinToString("\n\n")
        findViewById<Button>(R.id.notificationSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.tradingViewRefresh).setOnClickListener { loadTradingView() }
        loadTradingView()
    }

    private fun loadTradingView() {
        val status = findViewById<TextView>(R.id.tradingViewStatus)
        val list = findViewById<TextView>(R.id.tradingViewSignals)
        status.text = "TradingView webhook sinyalleri alınıyor…"
        lifecycleScope.launch {
            val capability = MarketCapabilityClient(this@NotificationsActivity).load().getOrNull()
            if (capability?.features?.tradingViewSignals != true) {
                status.text = "TradingView webhook sinyalleri • DEVRE DIŞI"
                list.text = "Backend gerçek ve kalıcı TradingView webhook sinyal deposunu etkin ilan etmiyor; bu nedenle endpoint çağrılmadı."
                return@launch
            }
            TradingViewSignalClient(this@NotificationsActivity).loadSignals(50)
                .onSuccess { signals ->
                    status.text = "TradingView • CANLI ALARM/SİNYAL KANALI • ${signals.size} kayıt\nWebhook ile gelen güncel sinyaller gösterilir; canlı BIST quote/mum veri akışı değildir."
                    list.text = if (signals.isEmpty()) {
                        "Henüz TradingView webhook sinyali yok."
                    } else {
                        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr", "TR"))
                        signals.joinToString("\n\n") { x ->
                            buildString {
                                append(if (x.freshSignal) "CANLI SİNYAL • " else "SİNYAL • ")
                                append(x.symbol).append(" • ").append(x.action)
                                x.price?.let { append(" • ").append(String.format(Locale.US, "%.4f", it)) }
                                x.interval?.let { append(" • ").append(it) }
                                x.score?.let { append(" • V5 ").append(String.format(Locale.US, "%.0f/100", it)) }
                                append("\nSinyal: ").append(fmt.format(Date(x.signalTime)))
                                x.strategy?.let { append("\nStrateji: ").append(it) }
                                val metrics = buildList {
                                    x.rsi?.let { add("RSI ${String.format(Locale.US, "%.1f", it)}") }
                                    x.macdHistogram?.let { add("MACD Δ ${String.format(Locale.US, "%.4f", it)}") }
                                    x.volumeRatio?.let { add("Hacim x${String.format(Locale.US, "%.2f", it)}") }
                                    x.trend?.let { add("Trend $it") }
                                }
                                if (metrics.isNotEmpty()) append("\n").append(metrics.joinToString(" • "))
                                x.message?.let { append("\n").append(it) }
                                append("\nKaynak: TradingView alarmı • canlı piyasa quote akışı değildir")
                            }
                        }
                    }
                }
                .onFailure { e ->
                    status.text = "TradingView webhook sinyalleri alınamadı"
                    list.text = e.message ?: "Bilinmeyen bağlantı hatası"
                }
        }
    }
}
