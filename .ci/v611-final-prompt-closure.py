from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()

def replace_once(path, old, new, label):
    p = root / path
    s = p.read_text()
    c = s.count(old)
    if c != 1:
        raise SystemExit(f'{label}: pattern count={c} path={p}')
    p.write_text(s.replace(old, new, 1))

# 1) Results cards: visible rank + explicit LONG/SHORT text.
replace_once(
    'app/src/main/java/tr/borsatakip/v5/ui/ScanResultsAdapter.kt',
    '        holder.symbol.text = "$symbol — $timeframeLabel FIRSAT"\n',
    '        holder.symbol.text = "#${position + 1} • $symbol — $timeframeLabel FIRSAT"\n',
    'scan result visible rank'
)
replace_once(
    'app/src/main/java/tr/borsatakip/v5/ui/ScanResultsAdapter.kt',
    '            direction.equals("LONG", true) -> "▲ AL ADAYI"\n            direction.equals("SHORT", true) -> "▼ SAT ADAYI"\n',
    '            direction.equals("LONG", true) -> "↗ LONG • AL ADAYI"\n            direction.equals("SHORT", true) -> "↘ SHORT • SAT ADAYI"\n',
    'explicit LONG SHORT labels'
)
replace_once(
    'app/src/main/java/tr/borsatakip/v5/ui/ScanResultsAdapter.kt',
    '''        holder.direction.setTextColor(signalAccent)
        holder.strengthValue.setTextColor(trendAccent)
        holder.strengthBar.progressTintList = ColorStateList.valueOf(trendAccent)
        holder.strengthBar.progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(trendAccent, 40))
''',
    '''        holder.direction.setTextColor(signalAccent)
        // Direction owns the colour; score owns the brightness. Trend remains a separate visual concept.
        val signalStrengthAccent = ColorUtils.blendARGB(signalAccent, Color.WHITE, (strength * 0.18f).coerceIn(0f, 0.18f))
        holder.strengthValue.setTextColor(signalStrengthAccent)
        holder.strengthBar.progressTintList = ColorStateList.valueOf(signalStrengthAccent)
        holder.strengthBar.progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(signalAccent, 40))
''',
    'direction color strength brightness'
)

# 2) Chart empty state must show the real failure reason, not a generic insufficient-candle message.
replace_once(
    'app/src/main/java/tr/borsatakip/v5/ui/PriceChartView.kt',
    '''    var statusText: String = ""
        private set
''',
    '''    var emptyStateMessage: String = "OHLCV verisi bekleniyor."
        set(value) { field = value.ifBlank { "OHLCV verisi kullanılamıyor." }; invalidate() }

    var statusText: String = ""
        private set
''',
    'chart empty state property'
)
replace_once(
    'app/src/main/java/tr/borsatakip/v5/ui/PriceChartView.kt',
    '''        if (all.isEmpty()) {
            statusText = "Yeterli OHLCV verisi bulunamadı."
            canvas.drawText(statusText, paddingLeft + 12f, height / 2f, text)
            return
        }
''',
    '''        if (all.isEmpty()) {
            statusText = emptyStateMessage
            canvas.drawText(statusText, paddingLeft + 12f, height / 2f, text)
            return
        }
''',
    'chart empty state render'
)

stock_detail = 'app/src/main/java/tr/borsatakip/v5/ui/StockDetailActivity.kt'
replace_once(
    stock_detail,
    '''        chartLoadJob?.cancel()
        chartStatus.text = "${timeframe.label} gerçek OHLCV verisi yükleniyor..."
        trendInfoPanel.text = "Trend analizi hazırlanıyor..."
''',
    '''        chartLoadJob?.cancel()
        chart.emptyStateMessage = "${timeframe.label}: OHLCV verisi yükleniyor..."
        chartStatus.text = "${timeframe.label} gerçek OHLCV verisi yükleniyor..."
        trendInfoPanel.text = "Trend analizi hazırlanıyor..."
''',
    'chart loading empty state'
)
replace_once(
    stock_detail,
    '''                chart.timeframeLabel = timeframe.label
                chart.candles = if (loaded.reason == tr.borsatakip.v5.data.ChartHistoryFailureReason.NONE) candles else emptyList()
                chart.trendAnalysis = if (loaded.reason == tr.borsatakip.v5.data.ChartHistoryFailureReason.NONE) trend else null
                chartStatus.text = when (loaded.reason) {
''',
    '''                chart.timeframeLabel = timeframe.label
                chart.emptyStateMessage = when (loaded.reason) {
                    tr.borsatakip.v5.data.ChartHistoryFailureReason.NONE -> "OHLCV verisi bekleniyor."
                    tr.borsatakip.v5.data.ChartHistoryFailureReason.API_RESPONSE_EMPTY -> "API yanıtı yok veya boş."
                    tr.borsatakip.v5.data.ChartHistoryFailureReason.SYMBOL_NOT_FOUND -> "Sembol bulunamadı."
                    tr.borsatakip.v5.data.ChartHistoryFailureReason.INSUFFICIENT_CANDLES -> "Yeterli OHLCV verisi bulunamadı • ${loaded.detail}"
                    tr.borsatakip.v5.data.ChartHistoryFailureReason.TIMEFRAME_UNSUPPORTED -> "Timeframe desteklenmiyor."
                    tr.borsatakip.v5.data.ChartHistoryFailureReason.PARSE_ERROR -> "OHLCV veri parse edilemedi."
                    tr.borsatakip.v5.data.ChartHistoryFailureReason.PROVIDER_ERROR -> "Veri sağlayıcıdan OHLCV alınamadı."
                }
                chart.candles = if (loaded.reason == tr.borsatakip.v5.data.ChartHistoryFailureReason.NONE) candles else emptyList()
                chart.trendAnalysis = if (loaded.reason == tr.borsatakip.v5.data.ChartHistoryFailureReason.NONE) trend else null
                chartStatus.text = when (loaded.reason) {
''',
    'chart diagnostic empty reason'
)
replace_once(
    stock_detail,
    '''                chart.timeframeLabel = timeframe.label
                chart.candles = emptyList()
                chart.trendAnalysis = null
                android.util.Log.w("StockDetailActivity", "Chart load failed for ${timeframe.label}", error)
''',
    '''                chart.timeframeLabel = timeframe.label
                chart.emptyStateMessage = "${timeframe.label}: gerçek OHLCV verisi alınamadı."
                chart.candles = emptyList()
                chart.trendAnalysis = null
                android.util.Log.w("StockDetailActivity", "Chart load failed for ${timeframe.label}", error)
''',
    'chart unexpected failure empty state'
)

# 3) OHLCV HTTP status/latency logging without headers/API key.
replace_once(
    'app/src/main/java/tr/borsatakip/v5/data/MobileMarketDataProvider.kt',
    '''    private suspend fun executeCancellable(call: Call): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
''',
    '''    private suspend fun executeCancellable(call: Call): String {
        val startedAt = SystemClock.elapsedRealtime()
        val isOhlcvRequest = call.request().url.encodedPath.startsWith("/v1/bist/history")
        return suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
''',
    'ohlcv http timing start'
)
replace_once(
    'app/src/main/java/tr/borsatakip/v5/data/MobileMarketDataProvider.kt',
    '''            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isActive) return
                val ex = when (e) {
''',
    '''            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isActive) return
                if (BuildConfig.DEBUG && isOhlcvRequest) {
                    Log.w("OHLCV_HTTP", "status=NETWORK_FAILURE durationMs=${SystemClock.elapsedRealtime() - startedAt} path=${call.request().url.encodedPath} query=${call.request().url.encodedQuery ?: ""} error=${e.javaClass.simpleName}")
                }
                val ex = when (e) {
''',
    'ohlcv http failure log'
)
replace_once(
    'app/src/main/java/tr/borsatakip/v5/data/MobileMarketDataProvider.kt',
    '''                    val code = it.code
                    val text = it.body?.string().orEmpty()
                    val error = when {
''',
    '''                    val code = it.code
                    val text = it.body?.string().orEmpty()
                    if (BuildConfig.DEBUG && isOhlcvRequest) {
                        Log.d("OHLCV_HTTP", "status=$code durationMs=${SystemClock.elapsedRealtime() - startedAt} path=${call.request().url.encodedPath} query=${call.request().url.encodedQuery ?: ""} bodyChars=${text.length}")
                    }
                    val error = when {
''',
    'ohlcv http response log'
)
replace_once(
    'app/src/main/java/tr/borsatakip/v5/data/MobileMarketDataProvider.kt',
    '''        })
    }

    private fun String?.ifNullOrBlank''',
    '''        })
        }
    }

    private fun String?.ifNullOrBlank''',
    'ohlcv http timing close'
)

# 4) Prevent concurrent manual refresh storms while keeping accepted refresh a real force-refresh query.
market = 'app/src/main/java/tr/borsatakip/v5/ui/MarketInstrumentDetailActivity.kt'
replace_once(
    market,
    '''    private val trLocale = Locale("tr", "TR")
    private lateinit var instrument: String
''',
    '''    private val trLocale = Locale("tr", "TR")
    private lateinit var instrument: String
    private var loadInFlight = false
''',
    'market refresh in-flight flag'
)
replace_once(
    market,
    '''    private fun load(forceExternalRefresh: Boolean = false) {
        setLoading()
        lifecycleScope.launch {
            when (instrument) {
                "BIST100" -> loadStock("BIST 100", "XU100", listOf("XU100.IS", "^XU100"))
                "BIST30" -> loadStock("BIST 30", "XU030", listOf("XU030.IS", "^XU030", "XU030"))
                "USDTRY" -> loadExternal("DOLAR / TL", "USDTRY", listOf("TRY=X"), forceExternalRefresh)
                "XAUUSD" -> loadExternal("ONS ALTIN", "XAUUSD", listOf("XAUUSD=X", "GC=F"), forceExternalRefresh)
                "VIOP30" -> loadViop30()
                else -> showError("Bilinmeyen piyasa kartı")
            }
        }
    }
''',
    '''    private fun load(forceExternalRefresh: Boolean = false) {
        if (loadInFlight) return
        loadInFlight = true
        val refreshButton = findViewById<Button>(R.id.marketDetailRefresh)
        refreshButton.isEnabled = false
        setLoading()
        lifecycleScope.launch {
            try {
                when (instrument) {
                    "BIST100" -> loadStock("BIST 100", "XU100", listOf("XU100.IS", "^XU100"))
                    "BIST30" -> loadStock("BIST 30", "XU030", listOf("XU030.IS", "^XU030", "XU030"))
                    "USDTRY" -> loadExternal("DOLAR / TL", "USDTRY", listOf("TRY=X"), forceExternalRefresh)
                    "XAUUSD" -> loadExternal("ONS ALTIN", "XAUUSD", listOf("XAUUSD=X", "GC=F"), forceExternalRefresh)
                    "VIOP30" -> loadViop30()
                    else -> showError("Bilinmeyen piyasa kartı")
                }
            } finally {
                loadInFlight = false
                refreshButton.isEnabled = true
            }
        }
    }
''',
    'market refresh single flight'
)

# 5) VIOP external cards start fail-closed in a truthful pending state.
viop_layout = root / 'app/src/main/res/layout/activity_viop.xml'
vs = viop_layout.read_text()
old_status = 'android:text="Doğrulanmış kaynak yok"'
if vs.count(old_status) != 2:
    raise SystemExit(f'viop external initial-state count={vs.count(old_status)}')
viop_layout.write_text(vs.replace(old_status, 'android:text="Sağlayıcılar kontrol ediliyor"'))

# 6) Remove stale patch backup files from the real distributable source tree.
for pattern in ('*.orig', '*.rej', '*.bak', '*~'):
    for p in root.rglob(pattern):
        if p.is_file():
            p.unlink()

print('V6.1.1 final prompt closure applied')
