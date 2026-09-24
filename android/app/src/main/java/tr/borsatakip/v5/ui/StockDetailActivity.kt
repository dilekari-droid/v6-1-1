package tr.borsatakip.v5.ui

import tr.borsatakip.v5.data.MarketDataQuality
import tr.borsatakip.v5.data.MarketPresentationPolicy
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tr.borsatakip.v5.analysis.ChartTimeframe
import tr.borsatakip.v5.analysis.LinearTrendAnalyzer
import tr.borsatakip.v5.data.ChartHistoryRepository
import tr.borsatakip.v5.data.LiveMarketSocket
import tr.borsatakip.v5.data.MarketCapabilityClient
import tr.borsatakip.v5.data.ProviderRouter
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StockDetailActivity : BaseActivity() {
    private lateinit var x: Opportunity
    private lateinit var chart: PriceChartView
    private lateinit var chartStatus: TextView
    private lateinit var trendInfoPanel: TextView
    private lateinit var chartRepository: ChartHistoryRepository
    private lateinit var quoteProvider: ProviderRouter
    private var chartLoadJob: Job? = null
    private var selectedTimeframe: ChartTimeframe = ChartTimeframe.ONE_DAY
    private lateinit var liveSocket: LiveMarketSocket
    private lateinit var liveConnectionStatus: TextView
    private lateinit var liveQuote: TextView
    private lateinit var liveCandle: TextView
    private lateinit var liveMomentum: TextView
    private lateinit var liveVolume: TextView
    private lateinit var liveTrend: TextView
    private lateinit var liveV5Score: TextView
    private lateinit var liveDecision: TextView
    private lateinit var liveTradingViewSignal: TextView
    private val df = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr","TR"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_detail)
        setupBottomNav()
        x = (if (android.os.Build.VERSION.SDK_INT >= 33) intent.getSerializableExtra("opportunity", Opportunity::class.java) else @Suppress("DEPRECATION") (intent.getSerializableExtra("opportunity") as? Opportunity))
            ?: AppSession.selected ?: run { finish(); return }
        AppSession.selected = x
        chart = findViewById(R.id.chart)
        chartStatus = findViewById(R.id.chartStatus)
        trendInfoPanel = findViewById(R.id.trendInfoPanel)
        quoteProvider = ProviderRouter(this)
        chartRepository = ChartHistoryRepository(quoteProvider)
        liveSocket = LiveMarketSocket(this)
        liveConnectionStatus = findViewById(R.id.liveConnectionStatus)
        liveQuote = findViewById(R.id.liveQuote)
        liveCandle = findViewById(R.id.liveCandle)
        liveMomentum = findViewById(R.id.liveMomentum)
        liveVolume = findViewById(R.id.liveVolume)
        liveTrend = findViewById(R.id.liveTrend)
        liveV5Score = findViewById(R.id.liveV5Score)
        liveDecision = findViewById(R.id.liveDecision)
        liveTradingViewSignal = findViewById(R.id.liveTradingViewSignal)

        findViewById<TextView>(R.id.title).text = "${x.symbol}  ${money(x.price)}"
        findViewById<TextView>(R.id.subtitle).text = buildString {
            append(x.companyName ?: "Şirket adı yok")
            append(" • Günlük ").append(x.dailyChangePct?.let { "%+.2f%%".format(it) } ?: "VERİ YOK")
        }
        findViewById<TextView>(R.id.scoreSummary).text = summaryText()
        findViewById<TextView>(R.id.overview).text = overviewText()
        bindFactorCards()
        findViewById<TextView>(R.id.technicalDetails).text = technicalText()
        findViewById<TextView>(R.id.dataQuality).text = qualityText()
        findViewById<TextView>(R.id.dataStatus).text = dataStatusText()
        bindLiveDecisionSnapshot()
        renderIntentDisplayQuote()
        findViewById<TextView>(R.id.validity).text = if (isDelayedObservation()) {
            "VERİ DURUMU: GECİKMELİ TEKNİK İZLEME\n${x.signalValidityReason}"
        } else {
            "SİNYAL GEÇERLİLİĞİ: ${validityLabel()}\n${x.signalValidityReason}"
        }

        if (isDelayedObservation()) {
            chart.signalPrice = null
            chart.signalTime = null
        } else {
            chart.signalPrice = x.price
            chart.signalTime = x.signalGeneratedAt.takeIf { it > 0L } ?: x.exchangeTimestamp.takeIf { it > 0L }
        }
        bindTimeframeMenu()
        bindChartToggles()
        findViewById<Button>(R.id.chartZoomIn).setOnClickListener { chart.zoomIn() }
        findViewById<Button>(R.id.chartZoomOut).setOnClickListener { chart.zoomOut() }
        findViewById<Button>(R.id.chartFullscreen).setOnClickListener { openFullscreenChart() }
        chart.onRequestFullscreen = { openFullscreenChart() }
        loadTimeframe(ChartTimeframe.ONE_DAY)
        findViewById<Button>(R.id.btnTechnicalScreen).setOnClickListener { startActivity(Intent(this, TechnicalAnalysisActivity::class.java).putExtra("opportunity", x)) }
        findViewById<Button>(R.id.btnNewsScreen).setOnClickListener { startActivity(Intent(this, NewsActivity::class.java).putExtra("opportunity", x)) }
        findViewById<Button>(R.id.btnSignalScreen).setOnClickListener { startActivity(Intent(this, SignalHistoryActivity::class.java)) }
        findViewById<View>(R.id.btnTradingViewChart).apply {
            contentDescription = "${this@StockDetailActivity.x.symbol} TradingView grafiğini aç"
            setOnClickListener { openTradingViewChart() }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshDisplayQuote()
        startUnifiedLiveChannel()
    }

    override fun onStop() {
        liveSocket.close()
        super.onStop()
    }

    private fun renderIntentDisplayQuote() {
        val p = intent.getDoubleExtra("displayPrice", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
        val c = intent.getDoubleExtra("displayChangePct", Double.NaN).takeIf { it.isFinite() }
        if (p != null) {
            renderDisplayQuote(
                price = p,
                changePct = c,
                source = intent.getStringExtra("displaySource") ?: x.source,
                realtime = intent.getBooleanExtra("displayRealtime", x.isRealtime),
                timestamp = intent.getLongExtra("displayTimestamp", x.exchangeTimestamp)
            )
        }
    }

    private fun refreshDisplayQuote() {
        lifecycleScope.launch {
            val stock = runCatching { quoteProvider.fetchOne(x.symbol) }.getOrNull() ?: return@launch
            val price = stock.quotePrice ?: stock.candles.lastOrNull()?.close ?: return@launch
            if (!price.isFinite() || price <= 0.0) return@launch
            val prev = stock.previousClose
            val change = if (prev != null && prev.isFinite() && prev > 0.0) (price / prev - 1.0) * 100.0 else null
            renderDisplayQuote(price, change, stock.source, stock.isRealtime, stock.dataTimestamp)
        }
    }

    private fun renderDisplayQuote(price: Double, changePct: Double?, source: String, realtime: Boolean, timestamp: Long) {
        findViewById<TextView>(R.id.title).text = "${x.symbol}  ${money(price)}"
        findViewById<TextView>(R.id.subtitle).text = buildString {
            append(x.companyName ?: "Şirket adı yok")
            append(" • Günlük ").append(changePct?.let { "%+.2f%%".format(it) } ?: "VERİ YOK")
        }
        liveQuote.text = buildString {
            append("SON FİYAT  ").append(money(price))
            append("\nGÜNLÜK DEĞİŞİM  ").append(changePct?.let { "%+.2f%%".format(it) } ?: "VERİ YOK")
            append("\n").append(if (realtime) "CANLI/DOĞRULANMIŞ" else "YEDEK/GECİKMELİ")
            append(" • ").append(source)
            if (timestamp > 0L) append(" • ").append(df.format(Date(timestamp)))
            append("\nV5 puanı kapanmış mum karar anına aittir; güncel quote puanı geriye dönük değiştirmez.")
        }
        liveQuote.setTextColor(directionColor(changePct))
    }

    private fun startUnifiedLiveChannel() {
        liveConnectionStatus.text = "Canlı kanal capability doğrulanıyor…"
        lifecycleScope.launch {
            val capability = MarketCapabilityClient(this@StockDetailActivity).load().getOrNull()
            if (capability?.features?.liveMarketWebSocket != true) {
                liveSocket.close()
                liveConnectionStatus.text = "Canlı WebSocket backend tarafından desteklenmiyor • HTTP quote görüntüleniyor"
                return@launch
            }
            connectLiveMarketSocket()
        }
    }

    private fun connectLiveMarketSocket() {
        liveConnectionStatus.text = "WSS canlı kanalına bağlanılıyor…"
        liveSocket.connect(
            symbols = listOf(x.symbol),
            includeSignals = true,
            includeCandles = true,
            candleInterval = "1m",
            listener = object : LiveMarketSocket.Listener {
                override fun onConnected() = runOnUiThread {
                    liveConnectionStatus.text = "WebSocket bağlandı • doğrulanmış piyasa verisi bekleniyor"
                }

                override fun onSubscribed(message: String) = runOnUiThread {
                    liveConnectionStatus.text = "CANLI KANAL HAZIR • $message"
                }

                override fun onTick(tick: LiveMarketSocket.Tick) = runOnUiThread {
                    findViewById<TextView>(R.id.title).text = "${tick.symbol}  ${money(tick.price)}"
                    val bid = tick.bid?.let(::money) ?: "—"
                    val ask = tick.ask?.let(::money) ?: "—"
                    val change = tick.changePct
                    val changeText = change?.let { "%+.2f%%".format(it) } ?: "—"
                    val volume = tick.volume?.let { String.format(Locale.US, "%,.0f", it) } ?: "—"
                    liveQuote.text = buildString {
                        append("SON FİYAT  ").append(money(tick.price))
                        append("\nDEĞİŞİM  ").append(changeText)
                        append("   •   ALIŞ  ").append(bid).append("   SATIŞ  ").append(ask)
                        append("\nHACİM  ").append(volume)
                        append("\n").append(df.format(Date(tick.timestamp)))
                        append(" • ").append(tick.source ?: tick.providerId ?: "lisanslı sağlayıcı")
                        append(" • gecikme ").append(tick.delaySeconds).append(" sn")
                    }
                    liveQuote.setTextColor(directionColor(change))
                    liveConnectionStatus.text = "ANLIK ✓ • mevcut seans doğrulandı • WebSocket"
                }

                override fun onCandle(candle: LiveMarketSocket.CandleUpdate) = runOnUiThread {
                    val momentumPct = if (candle.open > 0.0) ((candle.close / candle.open) - 1.0) * 100.0 else null
                    liveMomentum.text = momentumPct?.let { "1 DK MOMENTUM  ${"%+.2f".format(it)}%" } ?: "1 DK MOMENTUM  —"
                    liveMomentum.setTextColor(directionColor(momentumPct))
                    liveCandle.text = buildString {
                        append(candle.interval).append(" MUM • ")
                        append("O ").append(money(candle.open)).append("  ")
                        append("H ").append(money(candle.high)).append("  ")
                        append("L ").append(money(candle.low)).append("  ")
                        append("C ").append(money(candle.close))
                        append("\nHacim ").append(String.format(Locale.US, "%,.0f", candle.volume))
                        append(" • ").append(df.format(Date(candle.timestamp)))
                    }
                }

                override fun onTradingViewSignal(signal: LiveMarketSocket.TradingViewSignalEvent) = runOnUiThread {
                    liveTradingViewSignal.text = buildString {
                        append(if (signal.freshSignal) "TRADINGVIEW DOĞRULAMASI • " else "TRADINGVIEW ALARMI • ")
                        append(signal.action)
                        signal.price?.let { append(" • ").append(money(it)) }
                        signal.interval?.let { append(" • ").append(it) }
                        signal.score?.let { append(" • V5 ").append(String.format(Locale.US, "%.0f/100", it)) }
                        append("\n").append(df.format(Date(signal.signalTime)))
                        signal.strategy?.let { append(" • ").append(it) }
                        val metrics = buildList {
                            signal.rsi?.let { add("RSI ${String.format(Locale.US, "%.1f", it)}") }
                            signal.macdHistogram?.let { add("MACD Δ ${String.format(Locale.US, "%.4f", it)}") }
                            signal.volumeRatio?.let { add("Hacim x${String.format(Locale.US, "%.2f", it)}") }
                            signal.trend?.let { add("Trend $it") }
                        }
                        if (metrics.isNotEmpty()) append("\n").append(metrics.joinToString(" • "))
                        signal.message?.let { append("\n").append(it) }
                        append("\nBu bir TradingView alarm/doğrulama bilgisidir; emir gerçekleşme kaydı değildir.")
                    }
                }

                override fun onDisconnected(reason: String) = runOnUiThread {
                    liveConnectionStatus.text = "Canlı bağlantı kapandı • $reason"
                }

                override fun onError(message: String) = runOnUiThread {
                    liveConnectionStatus.text = "Canlı kanal: $message"
                }
            }
        )
    }

    private fun openTradingViewChart() {
        val tradingViewSymbol = TradingViewChartLink.toBistSymbol(x.symbol) ?: run {
            Toast.makeText(this, "TradingView sembolü oluşturulamadı", Toast.LENGTH_SHORT).show()
            return
        }
        val encoded = Uri.encode(tradingViewSymbol)
        val uri = Uri.parse("https://www.tradingview.com/chart/?symbol=$encoded")
        // Implicit ACTION_VIEW is deliberate: if TradingView owns the URL on the device,
        // Android opens the TradingView app directly. Otherwise a browser/Custom Tab is used.
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { addCategory(Intent.CATEGORY_BROWSABLE) }
        runCatching { startActivity(intent) }
            .recoverCatching { CustomTabsIntent.Builder().build().launchUrl(this, uri) }
            .onFailure {
                Toast.makeText(this, "TradingView grafiği açılamadı", Toast.LENGTH_SHORT).show()
            }
    }

    private fun bindLiveDecisionSnapshot() {
        val realtimeDecision = !isDelayedObservation()
        val ratio = x.technical.volumeRatio
        liveVolume.text = "HACİM  ${ratio?.let { "x%.2f".format(it) } ?: "—"}"
        liveVolume.setTextColor(when { ratio == null -> getColor(R.color.text_secondary); ratio >= 1.5 -> getColor(R.color.green); ratio < 0.8 -> getColor(R.color.red); else -> getColor(R.color.text_primary) })

        val normalizedDirection = x.direction.trim().uppercase(Locale.ROOT)
        val trendLabel = when (normalizedDirection) {
            "LONG" -> "↗ YÜKSELİŞ"
            "SHORT" -> "↘ DÜŞÜŞ"
            else -> "→ NÖTR / KARIŞIK"
        }
        liveTrend.text = "TREND  $trendLabel"
        liveTrend.setTextColor(when (normalizedDirection) { "LONG" -> getColor(R.color.green); "SHORT" -> getColor(R.color.red); else -> getColor(R.color.text_secondary) })

        liveV5Score.text = "V5 SKOR  ${x.finalSignalScore.coerceIn(0, 100)} / 100   •   RİSK ${x.riskScore.coerceIn(0, 100)} / 100"
        val decision = when {
            !realtimeDecision -> "TEKNİK İZLEME • CANLI KARAR YOK"
            normalizedDirection == "LONG" && x.finalSignalScore >= 85 -> "GÜÇLÜ AL ADAYI"
            normalizedDirection == "LONG" && x.finalSignalScore >= 70 -> "AL ADAYI"
            normalizedDirection == "SHORT" && x.finalSignalScore >= 85 -> "GÜÇLÜ SAT ADAYI"
            normalizedDirection == "SHORT" && x.finalSignalScore >= 70 -> "SAT ADAYI"
            else -> "İZLE"
        }
        liveDecision.text = "V5 KARARI  $decision"
        liveDecision.setTextColor(when {
            decision.contains("AL ADAYI") -> getColor(R.color.green)
            decision.contains("SAT ADAYI") -> getColor(R.color.red)
            else -> getColor(R.color.yellow)
        })
        liveQuote.text = buildString {
            append("SON FİYAT  ").append(money(x.price))
            append("\nGÜNLÜK DEĞİŞİM  ").append(x.dailyChangePct?.let { "%+.2f%%".format(it) } ?: "VERİ YOK")
        }
        liveQuote.setTextColor(directionColor(x.dailyChangePct))
    }

    private fun directionColor(changePct: Double?): Int = when {
        changePct == null || !changePct.isFinite() || changePct == 0.0 -> getColor(R.color.text_primary)
        changePct > 0.0 -> getColor(R.color.green)
        else -> getColor(R.color.red)
    }

    private fun bindTimeframeMenu() {
        val button = findViewById<Button>(R.id.btnTimeframeMenu)
        button.text = "◷ Zaman ▼"
        button.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            val options = listOf(
                ChartTimeframe.ONE_MIN,
                ChartTimeframe.THREE_MIN,
                ChartTimeframe.FIVE_MIN,
                ChartTimeframe.FIFTEEN_MIN,
                ChartTimeframe.ONE_HOUR,
                ChartTimeframe.ONE_DAY,
                ChartTimeframe.ALL_TIME
            )
            options.forEachIndexed { index, timeframe ->
                popup.menu.add(0, index + 1, index, "◷  ${timeframe.label}")
            }
            popup.setOnMenuItemClickListener { item ->
                val timeframe = options.getOrNull(item.itemId - 1) ?: return@setOnMenuItemClickListener false
                button.text = "◷ ${timeframe.label} ▼"
                loadTimeframe(timeframe)
                true
            }
            popup.show()
        }
    }

    private fun bindChartToggles() {
        fun bind(id: Int, activeText: String, inactiveText: String, current: () -> Boolean, apply: (Boolean) -> Unit) {
            val button = findViewById<Button>(id)
            fun refresh() {
                val active = current()
                button.text = if (active) activeText else inactiveText
                button.alpha = if (active) 1.0f else 0.62f
            }
            button.setOnClickListener {
                apply(!current())
                refresh()
            }
            refresh()
        }
        bind(R.id.toggleEma20, "EMA20 ✓", "EMA20", { chart.showEma20 }, { chart.showEma20 = it })
        bind(R.id.toggleEma50, "EMA50 ✓", "EMA50", { chart.showEma50 }, { chart.showEma50 = it })
        bind(R.id.toggleEma200, "EMA200 ✓", "EMA200", { chart.showEma200 }, { chart.showEma200 = it })
        bind(R.id.toggleBollinger, "BOLLINGER ✓", "BOLLINGER", { chart.showBollinger }, { chart.showBollinger = it })
        bind(R.id.toggleTrend, "TREND ✓", "TREND", { chart.showTrendLines }, { chart.showTrendLines = it })
        bind(R.id.toggleChannel, "KANAL ✓", "KANAL", { chart.showChannel }, { chart.showChannel = it })
    }

    private fun loadTimeframe(timeframe: ChartTimeframe) {
        selectedTimeframe = timeframe
        chartLoadJob?.cancel()
        chartStatus.text = "${timeframe.label} gerçek OHLCV verisi yükleniyor..."
        trendInfoPanel.text = "Trend analizi hazırlanıyor..."
        chartLoadJob = lifecycleScope.launch {
            runCatching {
                val candles = chartRepository.load(x.symbol, timeframe, x.candles)
                val trend = withContext(Dispatchers.Default) { LinearTrendAnalyzer.analyze(candles) }
                candles to trend
            }.onSuccess { (candles, trend) ->
                if (selectedTimeframe != timeframe) return@onSuccess
                chart.timeframeLabel = timeframe.label
                chart.candles = candles
                chart.trendAnalysis = trend
                chartStatus.text = when {
                    candles.isEmpty() -> "${timeframe.label}: gerçek OHLCV verisi bulunamadı. Sahte veri üretilmedi."
                    else -> "Zaman Dilimi: ${timeframe.label} • ${candles.size} gerçek mum • ${dateRange(candles.first().timestamp, candles.last().timestamp)} • Yakınlaştırmak için iki parmak kullanın."
                }
                trendInfoPanel.text = trendInfo(timeframe, trend)
            }.onFailure { error ->
                if (selectedTimeframe != timeframe) return@onFailure
                chart.timeframeLabel = timeframe.label
                chart.candles = emptyList()
                chart.trendAnalysis = null
                chartStatus.text = "${timeframe.label}: gerçek OHLCV verisi alınamadı • ${error.message ?: error.javaClass.simpleName}"
                trendInfoPanel.text = "Trend analizi için yeterli veri yok."
            }
        }
    }


    private fun openFullscreenChart() {
        if (chart.candles.isEmpty()) return
        startActivity(Intent(this, FullscreenChartActivity::class.java).apply {
            putExtra("candles", ArrayList(chart.candles))
            putExtra("timeframe", selectedTimeframe.label)
            if (!isDelayedObservation()) {
                putExtra("signalPrice", x.price)
                putExtra("signalTime", x.signalGeneratedAt.takeIf { it > 0L } ?: x.exchangeTimestamp)
            }
        })
    }

    private fun trendInfo(timeframe: ChartTimeframe, r: LinearTrendAnalyzer.Result): String {
        val main = r.main
        if (main == null) return "Zaman Dilimi: ${timeframe.label}\n${r.message ?: "Trend analizi için yeterli veri yok."}"
        val strength = sequenceOf(r.support, r.resistance, r.main).filterNotNull().maxByOrNull { it.strengthScore } ?: main
        val breakoutDetail = when (r.breakout) {
            LinearTrendAnalyzer.Breakout.UP -> "YUKARI KIRILIM • ${r.volumeConfirmation.label} hacim teyidi"
            LinearTrendAnalyzer.Breakout.DOWN -> "AŞAĞI KIRILIM • ${r.volumeConfirmation.label} hacim teyidi"
            LinearTrendAnalyzer.Breakout.WAITING -> "TEYİT BEKLENİYOR • ${r.volumeConfirmation.label} hacim"
            LinearTrendAnalyzer.Breakout.NONE -> "KIRILIM YOK"
        }
        return buildString {
            append("Zaman Dilimi: ${timeframe.label}\n")
            append("Ana Trend: ${main.direction.label}\n")
            append("Trend Gücü: ${strength.strength.label} (${strength.strengthScore}/100)\n")
            append("Ana Eğim: ${"%.6f".format(main.slope)} • R²: ${"%.3f".format(main.r2)}\n")
            append("Temas: ${strength.contactCount} • Son temas: ${strength.lastContactIndex?.let { "${r.candleCount - 1 - it} mum önce" } ?: "yok"}\n")
            append("Destek Trend: ${r.supportNow?.let { "%.2f".format(it) } ?: "yok"}\n")
            append("Direnç Trend: ${r.resistanceNow?.let { "%.2f".format(it) } ?: "yok"}\n")
            append("Trend Kırılımı: $breakoutDetail")
            r.breakoutPct?.let { append(" • ${"%+.2f".format(it)}%") }
        }
    }

    private fun dateRange(first: Long, last: Long): String = "${df.format(Date(first))} – ${df.format(Date(last))}"

    private fun summaryText():String {
        if (isDelayedObservation()) {
            return "GECİKMELİ TEKNİK İZLEME • AL/SAT YOK\nTeknik Skor ${x.score}/100 • Sıralama ${x.rankingScore}/100 • Risk ${x.riskScore}/100\nVeri Güveni ${x.dataConfidenceScore}/100 (${x.dataConfidenceLabel})\n${x.setupType} • ${x.signalValidityReason}"
        }
        val strength = when {
            x.finalSignalScore >= 85 -> "YÜKSEK GÜÇ"
            x.finalSignalScore >= 70 -> "İZLE"
            x.finalSignalScore >= 60 -> "ZAYIF SİNYAL"
            else -> "DÜŞÜK GÜÇ"
        }
        return "${x.direction} • LONG ${x.longScore}/100 • SHORT ${x.shortScore}/100\nNihai Sinyal ${x.finalSignalScore}/100 • Sıralama ${x.rankingScore}/100 • $strength\nTeknik Skor ${x.score}/100 • Risk ${x.riskScore}/100 • Veri Güveni ${x.dataConfidenceScore}/100 (${x.dataConfidenceLabel})\n${validityLabel()} • ${x.setupType}"
    }

    private fun overviewText():String {
        val t = x.technical
        val emaState = when {
            listOf(t.ema20,t.ema50,t.ema200).all { it != null } && x.price < t.ema20!! && t.ema20!! < t.ema50!! && t.ema50!! < t.ema200!! ->
                "Fiyat EMA20, EMA50 ve EMA200 seviyelerinin altında; mevcut trend aşağı yönlü."
            listOf(t.ema20,t.ema50,t.ema200).all { it != null } && x.price > t.ema20!! && t.ema20!! > t.ema50!! && t.ema50!! > t.ema200!! ->
                "Fiyat EMA20, EMA50 ve EMA200 seviyelerinin üzerinde; mevcut trend yukarı yönlü."
            else -> "EMA dizilimi tek yönlü güçlü trend teyidi vermiyor."
        }
        val volume = x.technical.volumeRatio?.let {
            if (it < 0.8) "İşlem hacmi zayıf olduğu için sinyal teyidi azalıyor." else "Hacim sinyal değerlendirmesine veri sağlıyor."
        } ?: "Hacim teyidi doğrulanamadı."
        return if (isDelayedObservation()) {
            "$emaState $volume Bu kayıt gecikmeli/günlük teknik analizdir; AL/SAT sinyali veya anlık işlem kararı değildir."
        } else {
            "$emaState $volume Nihai Sinyal ${x.finalSignalScore}/100 bir başarı olasılığı değildir; veri ve teknik koşulların mevcut sürümdeki birleşik skorudur."
        }
    }

    private fun bindFactorCards() {
        val t = x.technical
        val trend = when {
            t.ema20 != null && t.ema50 != null && t.ema200 != null && x.price < t.ema20 && t.ema20 < t.ema50 && t.ema50 < t.ema200 -> "NEGATİF\nFiyat EMA20/50/200 altında. SHORT yönünü destekliyor."
            t.ema20 != null && t.ema50 != null && t.ema200 != null && x.price > t.ema20 && t.ema20 > t.ema50 && t.ema50 > t.ema200 -> "POZİTİF\nFiyat EMA20/50/200 üzerinde. LONG yönünü destekliyor."
            else -> "KARIŞIK\nEMA dizilimi güçlü tek yön teyidi vermiyor."
        }
        findViewById<TextView>(R.id.trendCard).text = "TREND\n$trend"

        val rsi = t.rsi14
        val momentum = when {
            rsi == null -> "YETERSİZ VERİ\nRSI14 hesaplanamadı."
            rsi < 30 -> "AŞIRI SATIM BÖLGESİNE YAKIN/ALTINDA\nRSI14 ${fmt(rsi)}. Tepki hareketi riski izlenmeli."
            rsi < 48 -> "ZAYIF\nRSI14 ${fmt(rsi)}. Alıcı momentumu zayıf."
            rsi <= 68 -> "DENGELİ/POZİTİF\nRSI14 ${fmt(rsi)}. Momentum aşırı bölge dışında."
            else -> "YÜKSEK\nRSI14 ${fmt(rsi)}. Aşırı alım/geri çekilme riski izlenmeli."
        }
        findViewById<TextView>(R.id.momentumCard).text = "MOMENTUM\n$momentum"

        val macd = if (t.macd != null && t.macdSignal != null) {
            val state = if (t.macd >= t.macdSignal) "POZİTİF" else "NEGATİF"
            "$state\nMACD ${fmt(t.macd)} / Signal ${fmt(t.macdSignal)}. Kısa vadeli momentum ${if (state=="POZİTİF") "yukarı" else "aşağı"} yönde."
        } else "YETERSİZ VERİ\nMACD/Signal hesaplanamadı."
        findViewById<TextView>(R.id.macdCard).text = "MACD\n$macd"

        val volume = t.volumeRatio?.let { ratio ->
            val state = when { ratio >= 1.5 -> "GÜÇLÜ TEYİT"; ratio >= 1.0 -> "ORTA TEYİT"; else -> "ZAYIF TEYİT" }
            "$state\nHacim ${"%.2f".format(ratio)}x. ${if (ratio < 1.0) "Düşük katılım sinyal teyidini azaltıyor." else "Hacim katılımı sinyal değerlendirmesini destekliyor."}"
        } ?: "YETERSİZ VERİ\nHacim oranı hesaplanamadı."
        findViewById<TextView>(R.id.volumeCard).text = "HACİM\n$volume"

        findViewById<TextView>(R.id.extraCard).text = buildString {
            append("EK FAKTÖRLER\n")
            append("VWMA20: ${fmt(t.vwma20 ?: t.vwap)} • ${if ((t.vwma20 ?: t.vwap) != null) if (x.price >= (t.vwma20 ?: t.vwap)!!) "fiyat üzerinde" else "fiyat altında" else "veri yok"}\n")
            append("Session VWAP: ${fmt(t.sessionVwap)} • ${if (t.sessionVwap != null) if (x.price >= t.sessionVwap) "fiyat üzerinde" else "fiyat altında" else "intraday veri yok"}\n")
            append("VWMA: ${fmt(t.vwma)}\n")
            append("ATR14: ${fmt(t.atr14)} • volatilite/risk girdisi\n")
            append("Destek: ${fmt(t.support)} • Direnç: ${fmt(t.resistance)}\n")
            append("Hacim anomalisi: ${x.volumeAnomalyPct?.let { "%+.1f%%".format(it) } ?: "Veri yok"}\n")
            x.rsiRiskMessage?.let { append("RSI Risk Uyarısı: $it\n") }
            x.riskPlan?.let { p ->
                append("Giriş: ${fmt(p.entry)} • Stop: ${fmt(p.stop)} • Hedef1: ${fmt(p.target1)} • Hedef2: ${fmt(p.target2)}\n")
                append("RR1: ${p.rr1?.let { "1:%.2f".format(it) } ?: "—"} • RR2: ${p.rr2?.let { "1:%.2f".format(it) } ?: "—"}")
            }
        }
    }

    private fun technicalText():String {
        val t=x.technical
        return "EMA20: ${fmt(t.ema20)}\nEMA50: ${fmt(t.ema50)}\nEMA200: ${fmt(t.ema200)}\nRSI14: ${fmt(t.rsi14)}\nMACD: ${fmt(t.macd)}\nSignal: ${fmt(t.macdSignal)}\nATR14: ${fmt(t.atr14)}\nVWMA20: ${fmt(t.vwma20 ?: t.vwap)}\nSession VWAP: ${fmt(t.sessionVwap)}\nVWMA(legacy): ${fmt(t.vwma)}\nHacim oranı: ${t.volumeRatio?.let { "%.2fx".format(it) } ?: "Veri yok"}\nBollinger alt/üst: ${fmt(t.bbLower)} / ${fmt(t.bbUpper)}\nDestek/Direnç: ${fmt(t.support)} / ${fmt(t.resistance)}"
    }

    private fun qualityText():String {
        fun mark(ok:Boolean)=if(ok)"✓" else "⚠ Veri yok"
        val hasPrice=x.price.isFinite()&&x.price>0
        val hasVol=x.technical.volumeRatio!=null
        val hasOhlcv=x.candles.size>=220
        val hasKap=!x.kapLabel.equals("Veri yok",true)&&x.kapLabel.isNotBlank()
        val hasLevels=x.support!=null&&x.resistance!=null
        val hasVwap=(x.technical.sessionVwap ?: x.technical.vwma20 ?: x.technical.vwap)!=null
        val footer = if (isDelayedObservation()) {
            "Eksik alanlar uydurulmaz. Gecikmeli veri yalnız teknik izleme içindir; AL/SAT sinyali üretilmez."
        } else {
            "Eksik alanlar uydurulmaz ve Veri Güveni ile Nihai Sinyal birbirinin yerine kullanılmaz."
        }
        return "VERİ GÜVENİ ${x.dataConfidenceScore}/100 (${x.dataConfidenceLabel})\nFiyat ${mark(hasPrice)}\nHacim ${mark(hasVol)}\nOHLCV ${mark(hasOhlcv)}\nKAP ${mark(hasKap)}\nDestek/Direnç ${mark(hasLevels)}\nVWMA20/Session VWAP ${mark(hasVwap)}\n$footer"
    }

    private fun dataStatusText():String {
        val measured = if (x.receivedElapsedRealtime > 0L) {
            val elapsed=(SystemClock.elapsedRealtime()-x.receivedElapsedRealtime).coerceAtLeast(0L)
            val atReceipt=if(x.receivedAt>0&&x.exchangeTimestamp>0)(x.receivedAt-x.exchangeTimestamp).coerceAtLeast(0L) else 0L
            MarketPresentationPolicy.formatDataAge(atReceipt+elapsed)
        } else "yeniden başlatma sonrası monotonic yaş doğrulanamıyor"
        val decisionLabel = if (isDelayedObservation()) "Karar Durumu: TEKNİK İZLEME • AL/SAT YOK" else "Karar Durumu: ${x.decisionState.name}"
        val providerPresentation = MarketDataQuality.providerStatus(x.marketDataMetadata)
        return "$decisionLabel\nSıralama Skoru: ${x.rankingScore}/100\nKaynak: ${x.source} • ${providerPresentation.stateLabel}\nVeri Modu: ${x.dataMode.name}\nPiyasa Veri Zamanı: ${time(x.exchangeTimestamp)}\nUygulamaya Ulaşma: ${time(x.receivedAt)}\nTarama Anındaki Veri Yaşı: ${MarketPresentationPolicy.formatDataAge(x.dataAgeMs)}\nŞu An Ölçülen Veri Yaşı: $measured\nSağlayıcı gecikmesi: ${x.delaySeconds?.let { "$it sn" } ?: "bilinmiyor"}\nTarama başladı: ${time(x.scanStartedAt)}\nTarama tamamlandı: ${time(x.scanCompletedAt)}\nScanRun: ${x.scanRunId ?: "yok"}\nNot: Uygulamaya ulaşma süresi, tek başına piyasa gecikmesi olarak yorumlanmaz."
    }

    private fun isDelayedObservation(): Boolean = x.dataMode != DataMode.REALTIME || !x.isRealtime

    private fun validityLabel() = when {
        x.decisionState == DecisionState.VERIFIED_OPPORTUNITY && x.signalValidity == SignalValidity.VALID -> "DOĞRULANMIŞ FIRSAT"
        x.signalValidity == SignalValidity.VALID -> "VERİ BÜTÜNLÜĞÜ GEÇERLİ • KARAR İZLEME"
        x.signalValidity == SignalValidity.WATCH -> "İZLEME"
        x.signalValidity == SignalValidity.INSUFFICIENT -> "YETERSİZ VERİ"
        else -> "REDDEDİLDİ"
    }
    private fun time(v:Long)=if(v>0)df.format(Date(v)) else "bilinmiyor"
    private fun money(v:Double)=if(v.isFinite())"%.2f TL".format(v) else "Veri yok"
    private fun fmt(v:Double?)=v?.takeIf{it.isFinite()}?.let{"%.2f".format(it)}?:"Veri yok"
}
