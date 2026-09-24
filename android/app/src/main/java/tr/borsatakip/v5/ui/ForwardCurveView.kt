package tr.borsatakip.v5.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import tr.borsatakip.v5.R

class ForwardCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.blue)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        strokeWidth = 1f
    }
    var values: List<Double> = emptyList()
        set(value) { field = value.filter { it.isFinite() }; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = 18f
        canvas.drawLine(pad, h / 2f, w - pad, h / 2f, axisPaint)
        if (values.size < 2) return
        val min = values.minOrNull() ?: return
        val max = values.maxOrNull() ?: return
        val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
        fun x(i: Int) = pad + i.toFloat() / (values.size - 1).toFloat() * (w - 2 * pad)
        fun y(v: Double) = pad + ((max - v) / span).toFloat() * (h - 2 * pad)
        for (i in 1 until values.size) canvas.drawLine(x(i - 1), y(values[i - 1]), x(i), y(values[i]), linePaint)
    }
}
