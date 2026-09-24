package tr.borsatakip.v5.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import tr.borsatakip.v5.analysis.LinearTrendAnalyzer
import tr.borsatakip.v5.analysis.OhlcvResampler
import tr.borsatakip.v5.model.Candle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.abs

/**
 * Gerçek OHLCV serisini çizer. Veri üretmez/interpolate etmez.
 * EMA20/50/200, Bollinger, RSI, MACD, hacim ve regresyon çizgileri aynı seçili zaman dilimi serisinden gelir.
 */
class PriceChartView(c: Context, a: AttributeSet? = null) : View(c, a) {
    var candles: List<Candle> = emptyList()
        set(value) {
            field = OhlcvResampler.sanitize(value)
            zoomScale = 1f
            invalidate()
        }

    var trendAnalysis: LinearTrendAnalyzer.Result? = null
        set(value) { field = value; invalidate() }

    var timeframeLabel: String = ""
        set(value) { field = value; invalidate() }

    var signalPrice: Double? = null
        set(value) { field = value?.takeIf { it.isFinite() && it > 0.0 }; invalidate() }
    var signalTime: Long? = null
        set(value) { field = value?.takeIf { it > 0L }; invalidate() }

    var showEma20: Boolean = true
        set(value) { field = value; invalidate() }
    var showEma50: Boolean = true
        set(value) { field = value; invalidate() }
    var showEma200: Boolean = true
        set(value) { field = value; invalidate() }
    var showBollinger: Boolean = true
        set(value) { field = value; invalidate() }
    var showTrendLines: Boolean = true
        set(value) { field = value; invalidate() }
    var showChannel: Boolean = true
        set(value) { field = value; invalidate() }
    var onRequestFullscreen: (() -> Unit)? = null

    var statusText: String = ""
        private set

    private var zoomScale = 1f
    private var panBars = 0
    private var selectedAbsoluteIndex: Int? = null
    private var lastVisibleStart = 0
    private var lastVisibleCount = 0
    private var lastLeft = 0f
    private var lastRight = 0f
    private var lastTop = 0f
    private var lastBottom = 0f
    private val scaleDetector = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomScale = (zoomScale * detector.scaleFactor).coerceIn(1f, 10f)
            panBars = panBars.coerceIn(0, max(0, candles.size - max(12, (candles.size / zoomScale).toInt())))
            invalidate()
            return true
        }
    })
    private val gestureDetector = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean { zoomIn(); selectAt(e.x); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { selectAt(e.x); return true }
        override fun onLongPress(e: MotionEvent) { onRequestFullscreen?.invoke() }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (lastVisibleCount <= 1 || lastRight <= lastLeft) return false
            val barsPerPixel = lastVisibleCount.toFloat() / (lastRight - lastLeft)
            panBars = (panBars + (distanceX * barsPerPixel).toInt()).coerceIn(0, max(0, candles.size - lastVisibleCount))
            invalidate(); return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(event.actionMasked == MotionEvent.ACTION_MOVE && (scaleDetector.isInProgress || zoomScale > 1.02f))
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    fun zoomIn() { zoomScale = (zoomScale * 1.35f).coerceAtMost(10f); invalidate() }
    fun zoomOut() { zoomScale = (zoomScale / 1.35f).coerceAtLeast(1f); if (zoomScale <= 1.01f) panBars = 0; invalidate() }
    fun resetZoom() { zoomScale = 1f; panBars = 0; selectedAbsoluteIndex = null; invalidate() }

    private fun selectAt(x: Float) {
        if (lastVisibleCount <= 0 || lastRight <= lastLeft) return
        val f = ((x - lastLeft) / (lastRight - lastLeft)).coerceIn(0f, 1f)
        selectedAbsoluteIndex = (lastVisibleStart + (f * (lastVisibleCount - 1)).toInt()).coerceIn(0, candles.lastIndex)
        invalidate()
    }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225, 235, 244); textSize = 24f }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(175, 194, 212); textSize = 19f }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(52, 100, 140, 165); strokeWidth = 1f }
    private val up = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 200, 83); strokeWidth = 2f }
    private val down = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 82, 82); strokeWidth = 2f }
    private val ema20Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 193, 7); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val ema50Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(3, 169, 244); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val ema200Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(186, 104, 200); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val bbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 158, 158, 158); strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val rsiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 235, 59); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val macdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val signalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 152, 0); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val mainTrendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(224, 224, 224); strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val supportTrendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 200, 83); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val resistanceTrendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 82, 82); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val currentPricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 224, 128); strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val channelUpperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 100, 255); strokeWidth = 3f; style = Paint.Style.STROKE }
    private val channelLowerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 75, 75); strokeWidth = 3f; style = Paint.Style.STROKE }
    private val channelMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 220, 220, 220); strokeWidth = 1.5f; style = Paint.Style.STROKE; pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f) }
    private val channelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(34, 45, 110, 255); style = Paint.Style.FILL }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 220, 230, 240); strokeWidth = 1.2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(7f, 7f), 0f) }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 205, 218); textSize = 18f }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val all = candles
        if (all.isEmpty()) {
            statusText = "Yeterli OHLCV verisi bulunamadı."
            canvas.drawText(statusText, paddingLeft + 12f, height / 2f, text)
            return
        }
        statusText = ""
        val visibleCount = max(12, (all.size / zoomScale).toInt()).coerceAtMost(all.size)
        val endExclusive = (all.size - panBars).coerceIn(visibleCount, all.size)
        val start = endExclusive - visibleCount
        val visible = all.subList(start, endExclusive)
        contentDescription = "${timeframeLabel.ifBlank { "Seçili dönem" }} gerçek OHLCV grafiği. Mum sayısı ${all.size}. Yakınlaştırma ${"%.1f".format(zoomScale)} kat."

        val left = paddingLeft.toFloat() + 10f
        val right = width.toFloat() - paddingRight - 76f
        val top = paddingTop.toFloat() + 8f
        val bottom = height.toFloat() - paddingBottom - 8f
        if (right <= left || bottom <= top) return
        lastVisibleStart = start; lastVisibleCount = visibleCount; lastLeft = left; lastRight = right; lastTop = top; lastBottom = bottom

        val h = bottom - top
        val priceTop = top
        val priceBottom = top + h * 0.55f
        val volumeTop = priceBottom + h * 0.03f
        val volumeBottom = volumeTop + h * 0.12f
        val rsiTop = volumeBottom + h * 0.03f
        val rsiBottom = rsiTop + h * 0.12f
        val macdTop = rsiBottom + h * 0.03f
        val macdBottom = bottom

        listOf(priceBottom, volumeBottom, rsiBottom).forEach { y -> canvas.drawLine(left, y, right, y, grid) }
        for (i in 0..5) {
            val gx = left + (right - left) * i / 5f
            canvas.drawLine(gx, priceTop, gx, bottom, grid)
        }
        val latest = visible.last()
        canvas.drawText("${timeframeLabel.ifBlank { "GRAFİK" }}  A ${"%.2f".format(latest.open)}  Y ${"%.2f".format(latest.high)}  D ${"%.2f".format(latest.low)}  K ${"%.2f".format(latest.close)}", left, priceTop + 24f, smallText)
        canvas.drawText("HACİM ${formatCompact(latest.volume)}", left, volumeTop + 22f, smallText)
        canvas.drawText("RSI (14)", left, rsiTop + 22f, smallText)
        canvas.drawText("MACD (12,26,9)", left, macdTop + 22f, smallText)

        drawPrice(canvas, all, visible, start, left, right, priceTop + 30f, priceBottom)
        drawVolume(canvas, visible, left, right, volumeTop + 26f, volumeBottom)
        drawRsi(canvas, all, start, visibleCount, left, right, rsiTop + 26f, rsiBottom)
        drawMacd(canvas, all, start, visibleCount, left, right, macdTop + 26f, macdBottom)
        drawSelection(canvas, all, start, visibleCount, left, right, priceTop, bottom)
    }

    private fun drawPrice(canvas: Canvas, all: List<Candle>, visible: List<Candle>, start: Int, left: Float, right: Float, top: Float, bottom: Float) {
        val closes = all.map { it.close }
        val ema20 = emaSeriesAligned(closes, 20)
        val ema50 = emaSeriesAligned(closes, 50)
        val ema200 = emaSeriesAligned(closes, 200)
        val bb = bollingerAligned(closes, 20)
        val analysis = trendAnalysis
        val markerTime = signalTime
        val markerPrice = signalPrice
        val markerVisible = markerTime != null && markerPrice != null && markerTime in visible.first().timestamp..visible.last().timestamp
        val indicatorValues = buildList<Double> {
            visible.forEach { add(it.low); add(it.high) }
            if (showEma20) ema20.drop(start).filterNotNull().forEach(::add)
            if (showEma50) ema50.drop(start).filterNotNull().forEach(::add)
            if (showEma200) ema200.drop(start).filterNotNull().forEach(::add)
            if (showBollinger) {
                bb.first.drop(start).filterNotNull().forEach(::add)
                bb.second.drop(start).filterNotNull().forEach(::add)
            }
            if (showTrendLines) {
                analysis?.main?.let { line -> (start until all.size).forEach { add(line.valueAt(it)) } }
                analysis?.support?.let { line -> (start until all.size).forEach { add(line.valueAt(it)) } }
                analysis?.resistance?.let { line -> (start until all.size).forEach { add(line.valueAt(it)) } }
            }
            if (markerVisible) add(markerPrice!!)
        }.filter { it.isFinite() && it > 0.0 }
        val minV = indicatorValues.minOrNull() ?: return
        val maxV = indicatorValues.maxOrNull() ?: return
        val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
        fun y(v: Double) = bottom - (((v - minV) / span).toFloat() * (bottom - top))
        val step = if (visible.size <= 1) (right - left) else (right - left) / (visible.size - 1)
        val bodyWidth = max(2f, min(12f, step * 0.55f))
        if (showChannel) drawDynamicChannel(canvas, all, start, visible.size, left, right, ::y)
        visible.forEachIndexed { i, k ->
            val x = if (visible.size <= 1) (left + right) / 2f else left + step * i
            val p = if (k.close >= k.open) up else down
            canvas.drawLine(x, y(k.low), x, y(k.high), p)
            p.style = Paint.Style.FILL
            val y1 = y(k.open); val y2 = y(k.close)
            canvas.drawRect(x - bodyWidth/2f, min(y1,y2), x + bodyWidth/2f, max(y1,y2).coerceAtLeast(min(y1,y2)+2f), p)
            p.style = Paint.Style.STROKE
        }
        if (showEma20) drawAlignedLine(canvas, ema20, start, visible.size, left, right, ::y, ema20Paint)
        if (showEma50) drawAlignedLine(canvas, ema50, start, visible.size, left, right, ::y, ema50Paint)
        if (showEma200) drawAlignedLine(canvas, ema200, start, visible.size, left, right, ::y, ema200Paint)
        if (showBollinger) {
            drawAlignedLine(canvas, bb.first, start, visible.size, left, right, ::y, bbPaint)
            drawAlignedLine(canvas, bb.second, start, visible.size, left, right, ::y, bbPaint)
        }
        if (showTrendLines) {
            analysis?.main?.let { drawRegression(canvas, it, start, visible.size, left, right, ::y, mainTrendPaint) }
            analysis?.support?.let { drawRegression(canvas, it, start, visible.size, left, right, ::y, supportTrendPaint) }
            analysis?.resistance?.let { drawRegression(canvas, it, start, visible.size, left, right, ::y, resistanceTrendPaint) }
            analysis?.supportNow?.takeIf { it in minV..maxV }?.let { canvas.drawText("D ${"%.2f".format(it)}", right - 104f, y(it) - 5f, smallText) }
            analysis?.resistanceNow?.takeIf { it in minV..maxV }?.let { canvas.drawText("R ${"%.2f".format(it)}", right - 104f, y(it) - 5f, smallText) }
        }

        for (i in 0..4) {
            val v = minV + (maxV - minV) * i / 4.0
            val yy = y(v)
            canvas.drawLine(left, yy, right, yy, grid)
            canvas.drawText("%.2f".format(v), right + 8f, yy + 6f, axisText)
        }
        val lastClose = visible.last().close
        if (lastClose in minV..maxV) {
            val yy = y(lastClose)
            canvas.drawLine(left, yy, right, yy, currentPricePaint)
            val oldStyle = currentPricePaint.style
            currentPricePaint.style = Paint.Style.FILL
            canvas.drawRect(right + 3f, yy - 15f, width.toFloat() - paddingRight - 3f, yy + 15f, currentPricePaint)
            currentPricePaint.style = oldStyle
            val labelPaint = Paint(axisText).apply { color = Color.WHITE; textSize = 17f; textAlign = Paint.Align.CENTER }
            canvas.drawText("%.2f".format(lastClose), (right + width.toFloat() - paddingRight - 3f) / 2f, yy + 6f, labelPaint)
        }

        if (markerVisible) {
            val nearest = visible.indices.minByOrNull { kotlin.math.abs(visible[it].timestamp - markerTime!!) } ?: visible.lastIndex
            val markerX = if (visible.size <= 1) (left + right)/2f else left + step * nearest
            val markerY = y(markerPrice!!)
            val marker = Path().apply { moveTo(markerX, markerY - 12f); lineTo(markerX - 9f, markerY + 8f); lineTo(markerX + 9f, markerY + 8f); close() }
            signalPaint.style = Paint.Style.FILL
            canvas.drawPath(marker, signalPaint)
            signalPaint.style = Paint.Style.STROKE
        }
    }

    private fun drawDynamicChannel(canvas: Canvas, all: List<Candle>, start: Int, count: Int, left: Float, right: Float, y: (Double) -> Float) {
        val main = trendAnalysis?.main ?: return
        if (count < 2) return
        val residualHigh = all.indices.map { i -> all[i].high - main.valueAt(i) }.filter { it.isFinite() }.sorted()
        val residualLow = all.indices.map { i -> all[i].low - main.valueAt(i) }.filter { it.isFinite() }.sorted()
        if (residualHigh.size < 6 || residualLow.size < 6) return
        fun q(v: List<Double>, p: Double): Double = v[((v.lastIndex * p).toInt()).coerceIn(0, v.lastIndex)]
        val upperOffset = q(residualHigh, 0.90)
        val lowerOffset = q(residualLow, 0.10)
        val end = start + count - 1
        val upper1 = main.valueAt(start) + upperOffset; val upper2 = main.valueAt(end) + upperOffset
        val lower1 = main.valueAt(start) + lowerOffset; val lower2 = main.valueAt(end) + lowerOffset
        if (listOf(upper1,upper2,lower1,lower2).any { !it.isFinite() || it <= 0.0 }) return
        channelFillPaint.color = when (main.direction) {
            LinearTrendAnalyzer.Direction.UP -> Color.argb(38, 0, 200, 120)
            LinearTrendAnalyzer.Direction.DOWN -> Color.argb(38, 255, 82, 82)
            else -> Color.argb(28, 120, 150, 180)
        }
        val path = Path().apply { moveTo(left,y(upper1)); lineTo(right,y(upper2)); lineTo(right,y(lower2)); lineTo(left,y(lower1)); close() }
        canvas.drawPath(path, channelFillPaint)
        canvas.drawLine(left,y(upper1),right,y(upper2),channelUpperPaint)
        canvas.drawLine(left,y(lower1),right,y(lower2),channelLowerPaint)
        canvas.drawLine(left,y(main.valueAt(start)),right,y(main.valueAt(end)),channelMidPaint)
    }

    private fun drawSelection(canvas: Canvas, all: List<Candle>, start: Int, count: Int, left: Float, right: Float, top: Float, bottom: Float) {
        val idx = selectedAbsoluteIndex ?: return
        if (idx !in start until (start + count)) return
        val local = idx - start
        val x = if (count <= 1) (left + right) / 2f else left + (right-left) * local / (count-1).toFloat()
        val c = all[idx]
        val prices = all.subList(start, (start+count).coerceAtMost(all.size)).flatMap { listOf(it.low,it.high) }
        val minP = prices.minOrNull() ?: return; val maxP = prices.maxOrNull() ?: return; val span=(maxP-minP).takeIf{it>0}?:1.0
        val priceBottom = top + (bottom-top)*0.55f
        val py = priceBottom - (((c.close-minP)/span).toFloat()*(priceBottom-top))
        canvas.drawLine(x, top, x, bottom, crosshairPaint); canvas.drawLine(left, py, right, py, crosshairPaint)
        val info = "A %.2f  Y %.2f  D %.2f  K %.2f  H %s".format(c.open,c.high,c.low,c.close,formatCompact(c.volume))
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 8, 34, 48); style = Paint.Style.FILL }
        canvas.drawRoundRect(left+6f, top+32f, min(right-6f,left+520f), top+68f, 8f,8f,box)
        canvas.drawText(info,left+14f,top+56f,smallText)
    }

    private fun drawRegression(canvas: Canvas, line: LinearTrendAnalyzer.RegressionLine, start: Int, count: Int, left: Float, right: Float, y: (Double) -> Float, paint: Paint) {
        if (count < 2) return
        val x1 = left
        val x2 = right
        val y1 = line.valueAt(start)
        val y2 = line.valueAt(start + count - 1)
        if (y1.isFinite() && y2.isFinite() && y1 > 0.0 && y2 > 0.0) canvas.drawLine(x1, y(y1), x2, y(y2), paint)
    }

    private fun drawVolume(canvas: Canvas, visible: List<Candle>, left: Float, right: Float, top: Float, bottom: Float) {
        val maxV = visible.maxOfOrNull { it.volume }?.takeIf { it > 0.0 } ?: return
        val step = if (visible.size <= 1) right-left else (right-left)/(visible.size-1)
        val w = max(2f, min(10f, step*0.5f))
        visible.forEachIndexed { i, c ->
            val x = if (visible.size <= 1) (left+right)/2f else left + step*i
            val yy = bottom - ((c.volume/maxV).toFloat()*(bottom-top))
            val p = if (c.close >= c.open) up else down
            p.style = Paint.Style.FILL
            canvas.drawRect(x-w/2f,yy,x+w/2f,bottom,p)
            p.style = Paint.Style.STROKE
        }
    }

    private fun drawRsi(canvas: Canvas, all: List<Candle>, start: Int, count: Int, left: Float, right: Float, top: Float, bottom: Float) {
        val rsi = rsiSeriesAligned(all.map { it.close },14)
        fun y(v: Double) = bottom - ((v.coerceIn(0.0,100.0)/100.0).toFloat()*(bottom-top))
        canvas.drawLine(left,y(30.0),right,y(30.0),grid)
        canvas.drawLine(left,y(70.0),right,y(70.0),grid)
        drawAlignedLine(canvas,rsi,start,count,left,right,::y,rsiPaint)
        rsi.getOrNull(start + count - 1)?.let { canvas.drawText("%.1f".format(it), right + 8f, y(it) + 6f, axisText) }
    }

    private fun drawMacd(canvas: Canvas, all: List<Candle>, start: Int, count: Int, left: Float, right: Float, top: Float, bottom: Float) {
        val closes = all.map { it.close }
        val fast = emaSeriesAligned(closes,12)
        val slow = emaSeriesAligned(closes,26)
        val line = List(closes.size) { i -> if (fast[i] != null && slow[i] != null) fast[i]!! - slow[i]!! else null }
        val signal = emaNullableAligned(line,9)
        val vals = (line.drop(start).take(count) + signal.drop(start).take(count)).filterNotNull()
        if (vals.isEmpty()) return
        val minV = min(0.0, vals.minOrNull() ?: 0.0); val maxV = max(0.0, vals.maxOrNull() ?: 0.0)
        val span = (maxV-minV).takeIf { it > 0.0 } ?: 1.0
        fun y(v:Double) = bottom - (((v-minV)/span).toFloat()*(bottom-top))
        canvas.drawLine(left,y(0.0),right,y(0.0),grid)
        drawAlignedLine(canvas,line,start,count,left,right,::y,macdPaint)
        drawAlignedLine(canvas,signal,start,count,left,right,::y,signalPaint)
        val lastLine = line.getOrNull(start + count - 1)
        val lastSignal = signal.getOrNull(start + count - 1)
        if (lastLine != null) canvas.drawText("M %.2f".format(lastLine), right + 8f, top + 18f, axisText)
        if (lastSignal != null) canvas.drawText("S %.2f".format(lastSignal), right + 8f, top + 38f, axisText)
    }

    private fun drawAlignedLine(canvas:Canvas, values:List<Double?>, start:Int, count:Int, left:Float, right:Float, y:(Double)->Float, paint:Paint) {
        val path = Path(); var started=false
        val step = if (count <= 1) right-left else (right-left)/(count-1)
        for (i in 0 until count) {
            val v = values.getOrNull(start+i) ?: continue
            val x = if (count <= 1) (left+right)/2f else left + step*i
            if (!started) { path.moveTo(x,y(v)); started=true } else path.lineTo(x,y(v))
        }
        if (started) canvas.drawPath(path,paint)
    }

    private fun formatCompact(value: Double): String = when {
        value >= 1_000_000_000.0 -> "%.1fB".format(value / 1_000_000_000.0)
        value >= 1_000_000.0 -> "%.1fM".format(value / 1_000_000.0)
        value >= 1_000.0 -> "%.1fK".format(value / 1_000.0)
        else -> "%.0f".format(value)
    }

    private fun bollingerAligned(values: List<Double>, period: Int): Pair<List<Double?>, List<Double?>> {
        val upper = MutableList<Double?>(values.size) { null }
        val lower = MutableList<Double?>(values.size) { null }
        if (period <= 1 || values.size < period) return upper to lower
        for (i in period - 1 until values.size) {
            val w = values.subList(i - period + 1, i + 1)
            val mean = w.average()
            val variance = w.sumOf { (it - mean).pow(2) } / w.size
            val sd = sqrt(variance.coerceAtLeast(0.0))
            upper[i] = mean + 2.0 * sd
            lower[i] = mean - 2.0 * sd
        }
        return upper to lower
    }

    private fun emaSeriesAligned(values:List<Double>, period:Int):List<Double?> {
        val out = MutableList<Double?>(values.size){null}
        if (period<=0 || values.size<period) return out
        var e = values.take(period).average(); if (!e.isFinite()) return out
        out[period-1]=e; val k=2.0/(period+1)
        for(i in period until values.size){ val x=values[i]; if(!x.isFinite()) break; e=x*k+e*(1-k); if(!e.isFinite()) break; out[i]=e }
        return out
    }

    private fun rsiSeriesAligned(values:List<Double>, p:Int):List<Double?> {
        val out=MutableList<Double?>(values.size){null}; if(p<=0||values.size<p+1)return out
        var gain=0.0; var loss=0.0
        for(i in 1..p){ val d=values[i]-values[i-1]; if(d>0)gain+=d else loss-=d }
        gain/=p; loss/=p
        fun value():Double = if(gain==0.0 && loss==0.0)50.0 else if(loss==0.0)100.0 else 100.0-(100.0/(1.0+gain/loss))
        out[p]=value()
        for(i in p+1 until values.size){ val d=values[i]-values[i-1]; gain=(gain*(p-1)+if(d>0)d else 0.0)/p; loss=(loss*(p-1)+if(d<0)-d else 0.0)/p; val r=value(); if(r.isFinite())out[i]=r }
        return out
    }

    private fun emaNullableAligned(values:List<Double?>, period:Int):List<Double?> {
        val out=MutableList<Double?>(values.size){null}; val first=values.indexOfFirst{it!=null}; if(first<0)return out
        val dense=values.drop(first).takeWhile{it!=null}.map{it!!}; if(dense.size<period)return out
        val aligned=emaSeriesAligned(dense,period); aligned.forEachIndexed{i,v-> if(v!=null && first+i<out.size) out[first+i]=v}; return out
    }
}
