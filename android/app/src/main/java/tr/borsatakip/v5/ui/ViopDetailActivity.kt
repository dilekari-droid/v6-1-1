package tr.borsatakip.v5.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.analysis.LinearTrendAnalyzer
import tr.borsatakip.v5.analysis.OhlcvResampler
import tr.borsatakip.v5.analysis.TechnicalAnalyzer
import tr.borsatakip.v5.analysis.ViopContractCategoryPolicy
import tr.borsatakip.v5.data.BackendProvider
import tr.borsatakip.v5.data.ProviderReadinessService
import tr.borsatakip.v5.data.ProviderState
import tr.borsatakip.v5.data.ViopWatchlistStore
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.TechnicalSnapshot
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopOpportunity
import tr.borsatakip.v5.model.ViopQuote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ViopDetailActivity : BaseActivity() {
    private enum class DetailTab { CHART, DEPTH, SIGNAL, CONTRACT }

    private lateinit var chart: PriceChartView
    private lateinit var watchlistStore: ViopWatchlistStore

    private var contract: ViopContract? = null
    private var opportunity: ViopOpportunity? = null
    private var underlyingOpportunity: Opportunity? = null
    private var quote: ViopQuote? = null
    private var candles: List<Candle> = emptyList()
    private var technical: TechnicalSnapshot? = null
    private var timeframe: ViopDetailTimeframe = ViopDetailTimeframe.FIVE_MIN
    private var activeTab: DetailTab = DetailTab.CHART

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viop_detail)
        setupBottomNav()
        watchlistStore = ViopWatchlistStore(this)
        chart = findViewById(R.id.viopPriceChart)

        resolveSelection()
        bindTabs()
        bindTimeframes()
        bindActions()
        renderKnownState()
        showTab(DetailTab.CHART)
        refreshVerifiedMarketData()
    }

    override fun onResume() {
        super.onResume()
        renderWatchState()
    }

    private fun resolveSelection() {
        val selectedContract = AppSession.selectedViopContract
        val selectedOpportunity = AppSession.selectedViopOpportunity
        val selectedUnderlying = AppSession.selectedViopUnderlyingOpportunity
        opportunity = selectedOpportunity?.takeIf { selectedContract == null || it.contract.symbol.equals(selectedContract.symbol, true) }
        contract = selectedContract ?: opportunity?.contract
        underlyingOpportunity = selectedUnderlying?.takeIf { selected ->
            contract?.underlying?.equals(selected.symbol, true) == true && opportunity == null
        }
        quote = opportunity?.quote
        candles = opportunity?.candles ?: underlyingOpportunity?.candles.orEmpty()
        technical = opportunity?.technical ?: underlyingOpportunity?.technical ?: candles.takeIf { it.isNotEmpty() }?.let(TechnicalAnalyzer::analyze)
        if (candles.isNotEmpty()) timeframe = ViopDetailTimeframe.ONE_DAY
    }

    private fun bindTabs() {
        findViewById<Button>(R.id.viopTabChart).setOnClickListener { showTab(DetailTab.CHART) }
        findViewById<Button>(R.id.viopTabDepth).setOnClickListener { showTab(DetailTab.DEPTH) }
        findViewById<Button>(R.id.viopTabSignal).setOnClickListener { showTab(DetailTab.SIGNAL) }
        findViewById<Button>(R.id.viopTabContract).setOnClickListener { showTab(DetailTab.CONTRACT) }
    }

    private fun bindTimeframes() {
        timeframeButtons().forEach { (id, spec) ->
            findViewById<Button>(id).setOnClickListener { loadTimeframe(spec) }
        }
    }

    private fun bindActions() {
        findViewById<TextView>(R.id.viopActionLong).setOnClickListener { showTab(DetailTab.SIGNAL) }
        findViewById<TextView>(R.id.viopActionShort).setOnClickListener { showTab(DetailTab.SIGNAL) }
        findViewById<TextView>(R.id.viopActionWatch).setOnClickListener { toggleWatchlist() }
        chart.onRequestFullscreen = { openFullscreenChart() }
    }

    private fun renderKnownState() {
        val c = contract
        findViewById<TextView>(R.id.viopDetailSubtitle).text = c?.let {
            "${it.underlying.uppercase(Locale.ROOT)} • ${it.expiry} VİOP"
        } ?: "Seçili sözleşme yok"

        renderQuoteCard()
        renderChart()
        renderTechnical()
        renderSignal()
        renderContract()
        renderDepth()
        renderSummaries()
        renderWatchState()
        updateTimeframeButtons()

        if (c == null) {
            setStatusMessage("VİOP sözleşmeleri ekranından bir sözleşme seçin.", R.color.text_secondary)
        }
    }

    private fun refreshVerifiedMarketData() {
        val c = contract ?: return
        if (c.symbol.isBlank() || c.isManual || c.validity != SignalValidity.VALID) {
            setStatusMessage(
                if (underlyingOpportunity != null) "DAYANAK DETAY • gerçek VİOP kontratı/quote yok; VİOP fiyatı üretilmedi."
                else "Gerçek VİOP kontratı doğrulanamadı • canlı veri gösterilmedi.",
                if (underlyingOpportunity != null) R.color.yellow else R.color.red
            )
            return
        }
        val readiness = ProviderReadinessService(this).localConfigState()
        if (readiness.state != ProviderState.PROVIDER_READY) {
            setStatusMessage("Production VİOP provider bağlı değil • mevcut doğrulanmış veri varsa korunur, yeni canlı veri üretilmez.", R.color.yellow)
            return
        }

        lifecycleScope.launch {
            val provider = BackendProvider(this@ViopDetailActivity)
            try {
                provider.loadViopQuote(c.symbol).getOrThrow().also {
                    quote = it
                    renderQuoteCard()
                    renderDepth()
                    renderSummaries()
                }
                setStatusMessage("Canlı kontrat verisi doğrulandı • grafik yükleniyor…", R.color.green)
                loadTimeframeInternal(provider, ViopDetailTimeframe.FIVE_MIN, userInitiated = false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val hasVerified = ViopDetailPresentationPolicy.dataState(quote) == ViopDetailDataState.LIVE
                setStatusMessage(
                    if (hasVerified) "Grafik yenilenemedi • doğrulanmış mevcut quote korunuyor."
                    else "VİOP quote/history doğrulanamadı • ${error.message ?: "veri alınamadı"}",
                    if (hasVerified) R.color.yellow else R.color.red
                )
            }
        }
    }

    private fun loadTimeframe(spec: ViopDetailTimeframe) {
        val c = contract ?: return
        val readiness = ProviderReadinessService(this).localConfigState()
        if (readiness.state != ProviderState.PROVIDER_READY) {
            val localCandles = opportunity?.candles ?: underlyingOpportunity?.candles.orEmpty()
            if (spec == ViopDetailTimeframe.ONE_DAY && localCandles.isNotEmpty()) {
                timeframe = spec
                candles = localCandles
                technical = opportunity?.technical ?: underlyingOpportunity?.technical ?: TechnicalAnalyzer.analyze(candles)
                renderChart()
                renderTechnical()
                updateTimeframeButtons()
            } else {
                Toast.makeText(this, "Bu zaman dilimi için production VİOP provider gerekli", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (c.validity != SignalValidity.VALID || c.isManual) {
            Toast.makeText(this, "Doğrulanmış aktif kontrat gerekli", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                loadTimeframeInternal(BackendProvider(this@ViopDetailActivity), spec, userInitiated = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                setStatusMessage("${spec.displayLabel} grafiği alınamadı • ${error.message ?: "veri yok"}", R.color.red)
            }
        }
    }

    private suspend fun loadTimeframeInternal(provider: BackendProvider, spec: ViopDetailTimeframe, userInitiated: Boolean) {
        val c = contract ?: return
        if (userInitiated) setStatusMessage("${spec.displayLabel} OHLCV verisi yükleniyor…", R.color.text_secondary)
        val raw = provider.loadViopHistoryFlexible(c.symbol, spec.range, spec.requestInterval).getOrThrow()
        val next = spec.aggregateMinutes?.let { target ->
            OhlcvResampler.aggregate(raw, spec.sourceMinutes, target)
        } ?: raw
        require(next.isNotEmpty()) { "${spec.displayLabel} için kullanılabilir OHLCV verisi yok." }

        timeframe = spec
        candles = next
        technical = TechnicalAnalyzer.analyze(next)
        renderChart()
        renderTechnical()
        updateTimeframeButtons()
        setStatusMessage("${spec.displayLabel} • ${next.size} doğrulanmış mum", R.color.green)
    }

    private fun renderQuoteCard() {
        val q = quote
        val state = ViopDetailPresentationPolicy.dataState(q)
        findViewById<TextView>(R.id.viopDetailPrice).text = q?.price?.takeIf { it.isFinite() && it > 0.0 }?.let(::formatPrice) ?: "—"

        val changeView = findViewById<TextView>(R.id.viopDetailChange)
        val change = q?.dailyChangePct
        changeView.text = change?.let { "%+.2f%%".format(Locale.US, it) } ?: "—"
        changeView.setTextColor(getColor(when {
            change == null -> R.color.text_secondary
            change > 0.0 -> R.color.green
            change < 0.0 -> R.color.red
            else -> R.color.text_secondary
        }))

        val stateView = findViewById<TextView>(R.id.viopDataState)
        stateView.text = state.label
        stateView.setTextColor(getColor(when (state) {
            ViopDetailDataState.LIVE -> R.color.green
            ViopDetailDataState.DELAYED -> R.color.yellow
            ViopDetailDataState.STALE -> R.color.red
            ViopDetailDataState.UNAVAILABLE -> R.color.text_secondary
        }))

        findViewById<TextView>(R.id.viopVolume).text = "Hacim ${q?.volume?.let(::formatCompact) ?: "—"}"
        findViewById<TextView>(R.id.viopOpenInterest).text = "Açık Poz. ${q?.openInterest?.let(::formatLong) ?: "—"}"
        findViewById<TextView>(R.id.viopDataAge).text = q?.exchangeTimestamp?.takeIf { it > 0L }?.let {
            "Veri ${formatAge(System.currentTimeMillis() - it)}"
        } ?: "Veri zamanı —"
    }

    private fun renderChart() {
        val c = contract
        chart.candles = candles
        chart.timeframeLabel = timeframe.displayLabel
        chart.trendAnalysis = LinearTrendAnalyzer.analyze(candles)
        chart.signalPrice = opportunity?.decisionPrice?.takeIf { ViopDetailPresentationPolicy.verifiedDirection(opportunity) != null }
        chart.signalTime = null
        val sourceLabel = if (underlyingOpportunity != null && opportunity == null) "DAYANAK" else "VİOP"
        findViewById<TextView>(R.id.viopChartTitle).text = if (candles.isEmpty()) {
            "$sourceLabel • ${c?.underlying?.uppercase(Locale.ROOT) ?: "VİOP"} • ${timeframe.displayLabel} • VERİ YOK"
        } else {
            "$sourceLabel • ${c?.underlying?.uppercase(Locale.ROOT) ?: "VİOP"} • ${timeframe.displayLabel}"
        }
    }

    private fun renderTechnical() {
        val t = technical
        findViewById<TextView>(R.id.viopRsi).text = t?.rsi14?.let { "%.1f".format(Locale.US, it) } ?: "—"
        findViewById<TextView>(R.id.viopMacd).text = t?.macd?.let { "%+.3f".format(Locale.US, it) } ?: "—"
        findViewById<TextView>(R.id.viopEma20).text = t?.ema20?.let(::formatPrice) ?: "—"
        findViewById<TextView>(R.id.viopAtr).text = t?.atr14?.let { "%.3f".format(Locale.US, it) } ?: "—"
    }

    private fun renderSignal() {
        val o = opportunity
        val verifiedDirection = ViopDetailPresentationPolicy.verifiedDirection(o)
        val directionView = findViewById<TextView>(R.id.viopSignalDirection)
        directionView.text = verifiedDirection ?: if (o == null) "SİNYAL YOK" else "İZLE"
        directionView.setTextColor(getColor(when (verifiedDirection) {
            "LONG" -> R.color.green
            "SHORT" -> R.color.red
            else -> R.color.text_secondary
        }))
        findViewById<TextView>(R.id.viopSignalScore).text = "Sinyal skoru: ${o?.finalScore?.let { "$it/100" } ?: "—"}"
        findViewById<TextView>(R.id.viopSignalConfidence).text = "Veri güveni: ${o?.dataConfidenceScore?.let { "$it/100" } ?: "—"}"
        findViewById<TextView>(R.id.viopSignalRisk).text = "Risk: ${o?.riskScore?.let { "$it/100" } ?: "—"}"
        findViewById<TextView>(R.id.viopSignalReason).text = when {
            o == null && underlyingOpportunity != null -> "Dayanak analizi görüntüleniyor • doğrulanmış VİOP quote/history olmadan gerçek VİOP LONG/SHORT sinyali üretilmez."
            o == null -> "Quote ve strict history doğrulanmadan gerçek VİOP LONG/SHORT sinyali üretilmez."
            verifiedDirection == null -> "Karar durumu: ${o.decisionState} • ${o.signalReason}"
            else -> "${o.signalReason}\nKarar fiyatı: ${formatPrice(o.decisionPrice)} • MTF: ${o.mtfConsensusLabel} • Rejim: ${o.marketRegime}"
        }

        findViewById<TextView>(R.id.viopActionLong).alpha = if (verifiedDirection == "LONG") 1f else 0.55f
        findViewById<TextView>(R.id.viopActionShort).alpha = if (verifiedDirection == "SHORT") 1f else 0.55f
    }

    private fun renderContract() {
        val c = contract
        findViewById<TextView>(R.id.viopContractBody).text = c?.let {
            buildString {
                append("Kontrat: ${it.symbol}\n")
                append("Dayanak: ${it.underlying}\n")
                append("Vade: ${it.expiry}\n")
                append("Tür: ${it.contractType}\n")
                append("Çarpan: ${it.multiplier?.let(::formatPlain) ?: "—"}\n")
                append("Minimum adım: ${it.tickSize?.let(::formatPlain) ?: "—"}\n")
                append("Uzlaşma: ${it.settlementType ?: "—"}\n")
                append("Son işlem: ${it.lastTradingAt?.let(::formatTimestamp) ?: "—"}\n")
                append("Kaynak: ${it.providerLabel}\n")
                append("Doğrulama: ${it.validity} • ${it.validityReason}")
            }
        } ?: "Sözleşme seçilmedi."
    }

    private fun renderDepth() {
        val q = quote
        val bid = q?.bid?.takeIf { it.isFinite() && it > 0.0 }
        val ask = q?.ask?.takeIf { it.isFinite() && it > 0.0 }
        findViewById<TextView>(R.id.viopDepthBid).text = "Alış ${bid?.let(::formatPrice) ?: "—"}"
        findViewById<TextView>(R.id.viopDepthAsk).text = "Satış ${ask?.let(::formatPrice) ?: "—"}"
        findViewById<TextView>(R.id.viopDepthSpread).text = "Spread ${if (bid != null && ask != null && ask >= bid) formatPrice(ask - bid) else "—"}"
        findViewById<TextView>(R.id.viopDepthNote).text = if (bid != null || ask != null) {
            "En iyi alış/satış quote verisi gösteriliyor. Kademe bazlı tam derinlik endpoint'i mevcut olmadığı için seviye/miktar uydurulmadı."
        } else {
            "Kademe bazlı piyasa derinliği ve doğrulanmış alış/satış verisi yok; değer üretilmedi."
        }
    }

    private fun renderSummaries() {
        val c = contract
        findViewById<TextView>(R.id.viopSummaryContract).text = c?.let {
            "Dayanak  ${it.underlying}\nVade  ${it.expiry}\nÇarpan  ${it.multiplier?.let(::formatPlain) ?: "—"}\nAdım  ${it.tickSize?.let(::formatPlain) ?: "—"}"
        } ?: "Veri yok"

        val o = opportunity
        val direction = ViopDetailPresentationPolicy.verifiedDirection(o) ?: "İZLE"
        val dataLabel = ViopDetailPresentationPolicy.dataState(quote).label
        findViewById<TextView>(R.id.viopSummarySignal).text =
            "Trend  $direction\nGüven  ${o?.dataConfidenceScore?.let { "$it/100" } ?: "—"}\nVeri  $dataLabel\nSkor  ${o?.finalScore?.let { "$it/100" } ?: "—"}"
    }

    private fun renderWatchState() {
        val c = contract ?: return
        val category = ViopContractCategoryPolicy.classify(c)
        val watched = watchlistStore.symbols(category).any { it.equals(c.symbol, true) }
        findViewById<TextView>(R.id.viopActionWatch).text = if (watched) "★ TAKİP" else "☆ TAKİP"
        findViewById<TextView>(R.id.viopActionWatch).setTextColor(getColor(if (watched) R.color.yellow else R.color.text_primary))
    }

    private fun toggleWatchlist() {
        val c = contract ?: return
        val category = ViopContractCategoryPolicy.classify(c)
        val watched = watchlistStore.symbols(category).any { it.equals(c.symbol, true) }
        if (watched) {
            watchlistStore.remove(category, c.symbol)
            Toast.makeText(this, "${c.symbol} takip listesinden çıkarıldı", Toast.LENGTH_SHORT).show()
        } else {
            when (watchlistStore.add(category, c.symbol)) {
                ViopWatchlistStore.AddResult.ADDED -> Toast.makeText(this, "${c.symbol} takip listesine eklendi", Toast.LENGTH_SHORT).show()
                ViopWatchlistStore.AddResult.ALREADY_EXISTS -> Unit
                ViopWatchlistStore.AddResult.LIMIT_REACHED -> Toast.makeText(this, "Bu VİOP takip listesi dolu", Toast.LENGTH_LONG).show()
                ViopWatchlistStore.AddResult.INVALID -> Toast.makeText(this, "Kontrat sembolü takip için uygun değil", Toast.LENGTH_SHORT).show()
            }
        }
        renderWatchState()
    }

    private fun showTab(tab: DetailTab) {
        activeTab = tab
        findViewById<LinearLayout>(R.id.viopGraphSection).visibility = if (tab == DetailTab.CHART) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.viopDepthSection).visibility = if (tab == DetailTab.DEPTH) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.viopSignalSection).visibility = if (tab == DetailTab.SIGNAL) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.viopContractSection).visibility = if (tab == DetailTab.CONTRACT) View.VISIBLE else View.GONE
        tabButtons().forEach { (id, value) ->
            val button = findViewById<Button>(id)
            button.setBackgroundResource(if (value == activeTab) R.drawable.bg_viop_terminal_tab_selected else R.drawable.bg_viop_terminal_tab)
            button.setTextColor(getColor(if (value == activeTab) R.color.white else R.color.text_primary))
        }
    }

    private fun openFullscreenChart() {
        if (candles.isEmpty()) {
            Toast.makeText(this, "Tam ekran grafik için OHLCV verisi yok", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, FullscreenChartActivity::class.java).apply {
            putExtra("candles", ArrayList(candles))
            putExtra("timeframe", timeframe.displayLabel)
            opportunity?.decisionPrice?.takeIf { ViopDetailPresentationPolicy.verifiedDirection(opportunity) != null }?.let {
                putExtra("signalPrice", it)
            }
        })
    }

    private fun updateTimeframeButtons() {
        timeframeButtons().forEach { (id, value) ->
            val button = findViewById<Button>(id)
            button.setBackgroundResource(if (value == timeframe) R.drawable.bg_viop_terminal_tab_selected else R.drawable.bg_viop_terminal_tab)
            button.setTextColor(getColor(if (value == timeframe) R.color.white else R.color.text_primary))
        }
    }

    private fun timeframeButtons(): Map<Int, ViopDetailTimeframe> = linkedMapOf(
        R.id.viopTf1m to ViopDetailTimeframe.ONE_MIN,
        R.id.viopTf3m to ViopDetailTimeframe.THREE_MIN,
        R.id.viopTf5m to ViopDetailTimeframe.FIVE_MIN,
        R.id.viopTf15m to ViopDetailTimeframe.FIFTEEN_MIN,
        R.id.viopTf1h to ViopDetailTimeframe.ONE_HOUR,
        R.id.viopTf4h to ViopDetailTimeframe.FOUR_HOUR,
        R.id.viopTf1d to ViopDetailTimeframe.ONE_DAY
    )

    private fun tabButtons(): Map<Int, DetailTab> = linkedMapOf(
        R.id.viopTabChart to DetailTab.CHART,
        R.id.viopTabDepth to DetailTab.DEPTH,
        R.id.viopTabSignal to DetailTab.SIGNAL,
        R.id.viopTabContract to DetailTab.CONTRACT
    )

    private fun setStatusMessage(message: String, colorRes: Int) {
        findViewById<TextView>(R.id.viopDetailStatusMessage).apply {
            text = message
            setTextColor(getColor(colorRes))
        }
    }

    private fun formatPrice(value: Double): String = "%.2f".format(Locale.US, value).replace('.', ',')

    private fun formatPlain(value: Double): String = if (value % 1.0 == 0.0) {
        "%.0f".format(Locale.US, value)
    } else {
        "%.4f".format(Locale.US, value).trimEnd('0').trimEnd('.')
    }

    private fun formatCompact(value: Double): String = when {
        value >= 1_000_000_000 -> "%.2f Mr".format(Locale.US, value / 1_000_000_000)
        value >= 1_000_000 -> "%.2f Mn".format(Locale.US, value / 1_000_000)
        value >= 1_000 -> "%.1f B".format(Locale.US, value / 1_000)
        else -> "%.0f".format(Locale.US, value)
    }

    private fun formatLong(value: Long): String = String.format(Locale.US, "%,d", value).replace(',', '.')

    private fun formatAge(ageMs: Long): String = when {
        ageMs < 0L -> "saat doğrulaması bekleniyor"
        ageMs < 60_000L -> "${ageMs / 1000L} sn önce"
        ageMs < 3_600_000L -> "${ageMs / 60_000L} dk önce"
        else -> "${ageMs / 3_600_000L} sa önce"
    }

    private fun formatTimestamp(timestamp: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR")).format(Date(timestamp))
}
