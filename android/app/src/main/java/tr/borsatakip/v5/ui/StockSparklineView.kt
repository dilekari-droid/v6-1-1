package tr.borsatakip.v5.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.Candle
import kotlin.math.max

class StockSparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var values: List<Double> = emptyList()
    private var lineColor: Int = context.getColor(R.color.green)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setCandles(candles: List<Candle>, changePct: Double?) {
        values = candles.takeLast(36).mapNotNull { it.close.takeIf(Double::isFinite) }
        lineColor = when {
            changePct == null -> context.getColor(R.color.text_secondary)
            changePct > 0.0 -> context.getColor(R.color.green)
            changePct < 0.0 -> context.getColor(R.color.red)
            else -> context.getColor(R.color.text_secondary)
        }
        linePaint.color = lineColor
        fillPaint.color = (0x22 shl 24) or (lineColor and 0x00FFFFFF)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2) return
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top) return

        var minValue = values.minOrNull() ?: return
        var maxValue = values.maxOrNull() ?: return
        if (maxValue <= minValue) {
            minValue -= 1.0
            maxValue += 1.0
        }
        val span = max(1e-9, maxValue - minValue)
        val dx = (right - left) / (values.size - 1)
        fun y(value: Double): Float = bottom - (((value - minValue) / span) * (bottom - top)).toFloat()

        val path = Path()
        values.forEachIndexed { index, value ->
            val x = left + dx * index
            val yy = y(value)
            if (index == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        val fill = Path(path).apply {
            lineTo(right, bottom)
            lineTo(left, bottom)
            close()
        }
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
