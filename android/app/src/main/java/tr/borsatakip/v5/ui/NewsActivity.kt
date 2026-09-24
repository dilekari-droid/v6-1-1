package tr.borsatakip.v5.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.NewsRepository
import tr.borsatakip.v5.model.NewsItem
import tr.borsatakip.v5.model.Opportunity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NewsActivity : BaseActivity() {
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: Button
    private lateinit var repository: NewsRepository
    private var category = "ALL"
    private var selected: Opportunity? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news)
        setupBottomNav()
        list = findViewById(R.id.newsList)
        list.layoutManager = LinearLayoutManager(this)
        status = findViewById(R.id.newsStatus)
        progress = findViewById(R.id.newsProgress)
        retry = findViewById(R.id.newsRetry)
        repository = NewsRepository(this)
        selected = (if (android.os.Build.VERSION.SDK_INT >= 33) intent.getSerializableExtra("opportunity", Opportunity::class.java) else @Suppress("DEPRECATION") (intent.getSerializableExtra("opportunity") as? Opportunity)) ?: AppSession.selected
        AppSession.selected = selected
        findViewById<TextView>(R.id.newsSubtitle).text = selected?.let { "${it.companyName ?: it.symbol} • (${it.symbol.uppercase()})" } ?: "Borsa İstanbul"
        mapOf(R.id.newsAll to "ALL", R.id.newsKap to "KAP", R.id.newsCompany to "ŞİRKET", R.id.newsSector to "SEKTÖR", R.id.newsMarket to "PİYASA").forEach { (id, c) ->
            findViewById<Button>(id).setOnClickListener { category = c; load(force = false) }
        }
        retry.setOnClickListener { load(force = true) }
        load(force = false)
    }

    private fun load(force: Boolean) {
        job?.cancel()
        progress.visibility = View.VISIBLE
        retry.visibility = View.GONE
        status.text = "Haberler yükleniyor..."
        job = lifecycleScope.launch {
            repository.load(selected?.symbol, selected?.companyName, category, force).fold(
                onSuccess = { r ->
                    progress.visibility = View.GONE
                    list.adapter = NewsAdapter(r.items)
                    if (r.items.isEmpty()) {
                        status.text = "Bu hisse için güncel haber bulunamadı."
                        retry.visibility = View.VISIBLE
                    } else {
                        val t = SimpleDateFormat("HH:mm", Locale("tr", "TR")).format(Date(r.updatedAt))
                        status.text = "${r.items.size} haber • ${r.source}${if (r.fromCache) " • Son alınan haberler" else ""} • Son güncelleme: $t"
                    }
                },
                onFailure = {
                    progress.visibility = View.GONE
                    list.adapter = NewsAdapter(emptyList())
                    status.text = "Haberler şu anda alınamadı. İnternet bağlantınızı kontrol edin veya tekrar deneyin."
                    retry.visibility = View.VISIBLE
                }
            )
        }
    }
}
