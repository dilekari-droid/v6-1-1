package tr.borsatakip.v5.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import tr.borsatakip.v5.analysis.LinearTrendAnalyzer
import tr.borsatakip.v5.model.Candle

class FullscreenChartActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val candles = if (android.os.Build.VERSION.SDK_INT >= 33) {
            @Suppress("UNCHECKED_CAST")
            intent.getSerializableExtra("candles", ArrayList::class.java) as? ArrayList<Candle>
        } else {
            @Suppress("DEPRECATION", "UNCHECKED_CAST")
            intent.getSerializableExtra("candles") as? ArrayList<Candle>
        }.orEmpty()
        val chart = PriceChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(8, 8, 8, 8)
            this.candles = candles
            timeframeLabel = intent.getStringExtra("timeframe").orEmpty()
            signalPrice = intent.getDoubleExtra("signalPrice", Double.NaN).takeIf { it.isFinite() && it > 0 }
            signalTime = intent.getLongExtra("signalTime", 0L).takeIf { it > 0 }
            trendAnalysis = LinearTrendAnalyzer.analyze(candles)
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@FullscreenChartActivity).apply { text = "+"; setOnClickListener { chart.zoomIn() } }, LinearLayout.LayoutParams(0, 56, 1f))
            addView(Button(this@FullscreenChartActivity).apply { text = "−"; setOnClickListener { chart.zoomOut() } }, LinearLayout.LayoutParams(0, 56, 1f))
            addView(Button(this@FullscreenChartActivity).apply { text = "SIFIRLA"; setOnClickListener { chart.resetZoom() } }, LinearLayout.LayoutParams(0, 56, 1f))
            addView(Button(this@FullscreenChartActivity).apply { text = "KAPAT"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(0, 56, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(2, 30, 44))
            addView(chart)
            addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }
}
