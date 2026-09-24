package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.SearchView
import android.widget.TextView
import androidx.core.content.ContextCompat
import tr.borsatakip.v5.R
import java.util.Locale

class SettingsSearchActivity : BaseActivity() {
    private data class Item(val label: String, val keywords: String, val mode: String)

    private val allItems = listOf(
        Item("Sistem Durumu", "backend https authentication provider bist viop servis", SettingsDetailActivity.MODE_SYSTEM_STATUS),
        Item("Veri Bağlantısı", "api provider backend url bağlantı", SettingsDetailActivity.MODE_DATA_CONNECTION),
        Item("API Anahtarları", "api key token kimlik güvenli", SettingsDetailActivity.MODE_API_KEYS),
        Item("Tarama", "tarama analiz timeframe cadence otomatik scan forward", SettingsDetailActivity.MODE_SCAN),
        Item("İşlem Simülasyonu", "simülasyon miktar komisyon slippage kayma hesapla", SettingsDetailActivity.MODE_SIMULATION),
        Item("Bildirimler", "bildirim izin alarm realtime", SettingsDetailActivity.MODE_NOTIFICATIONS),
        Item("Bildirim Geçmişi", "bildirim geçmiş olay alarm", SettingsDetailActivity.MODE_NOTIFICATION_HISTORY),
        Item("Görünüm", "tema koyu görünüm", SettingsDetailActivity.MODE_APPEARANCE),
        Item("Dil ve Bölge", "dil türkçe bölge saat dilimi tarih sayı", SettingsDetailActivity.MODE_LOCALE),
        Item("Sistem Tanılama", "diagnostics tanılama health readiness ohlcv", SettingsDetailActivity.MODE_DIAGNOSTICS),
        Item("Uygulama Hakkında", "hakkında sürüm build application id lisans gizlilik", SettingsDetailActivity.MODE_ABOUT),
        Item("Çalışma Modu", "canlı veri yedek provider çalışma", SettingsDetailActivity.MODE_WORK_MODE)
    )

    private var visibleItems = allItems

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_search)
        setupBottomNav()
        findViewById<TextView>(R.id.navSettings)?.setTextColor(ContextCompat.getColor(this, R.color.blue))
        findViewById<TextView>(R.id.searchBack).setOnClickListener { finish() }

        val search = findViewById<SearchView>(R.id.settingsSearchView)
        val list = findViewById<ListView>(R.id.settingsSearchResults)
        val empty = findViewById<TextView>(R.id.searchEmpty)

        fun render(query: String) {
            val q = query.trim().lowercase(Locale("tr", "TR"))
            visibleItems = if (q.isBlank()) allItems else allItems.filter {
                (it.label + " " + it.keywords).lowercase(Locale("tr", "TR")).contains(q)
            }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, visibleItems.map { it.label })
            empty.text = if (visibleItems.isEmpty()) "Bu ifadeyle eşleşen gerçek bir ayar bulunamadı." else "${visibleItems.size} sonuç"
            empty.visibility = android.view.View.VISIBLE
        }

        list.setOnItemClickListener { _, _, position, _ ->
            val item = visibleItems.getOrNull(position) ?: return@setOnItemClickListener
            startActivity(Intent(this, SettingsDetailActivity::class.java).putExtra(SettingsDetailActivity.EXTRA_MODE, item.mode))
        }
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                render(query.orEmpty())
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                render(newText.orEmpty())
                return true
            }
        })
        render("")
    }
}
