package com.smclab.autotrade.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.smclab.autotrade.R
import kotlin.math.max
import kotlin.math.min

class EquityCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var values: List<Double> = emptyList()
    private var initial = 0.0
    private val density = resources.displayMetrics.density

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.stroke_soft)
        strokeWidth = density
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = density * 1.8f
        style = Paint.Style.STROKE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val baseline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(density * 4, density * 4), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        textSize = density * 9.5f
    }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        textSize = density * 9.5f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    fun setData(curve: List<Double>, initialBalance: Double) {
        values = curve
        initial = initialBalance
        val up = curve.isEmpty() || curve.last() >= initial
        val color = ContextCompat.getColor(context, if (up) R.color.buy else R.color.sell)
        line.color = color
        tag.color = color
        fill.shader = null
        fill.color = color
        fill.alpha = 24
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 8 || height <= 8) return
        rect.set(density * 4, density * 8, width - density * 54, height - density * 18)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, grid)
        canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, grid)
        if (values.size < 2) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("Chưa có dữ liệu", width / 2f, height / 2f, text)
            return
        }
        var minV = values.min()
        var maxV = values.max()
        if (maxV - minV < 1e-9) {
            minV -= 1.0
            maxV += 1.0
        }
        val span = maxV - minV
        val pad = span * 0.08
        minV -= pad
        maxV += pad
        fun yOf(v: Double): Float =
            (rect.bottom - ((v - minV) / (maxV - minV) * rect.height())).toFloat()

        // Horizontal guides.
        for (i in 0..2) {
            val y = rect.top + rect.height() * i / 2f
            canvas.drawLine(rect.left, y, rect.right, y, grid)
        }

        val path = Path()
        val area = Path()
        val step = rect.width() / (values.size - 1)
        values.forEachIndexed { i, v ->
            val x = rect.left + i * step
            val y = yOf(v)
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, rect.bottom)
                area.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(rect.right, rect.bottom)
        area.close()
        fill.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(line.color, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)

        if (initial > 0 && initial in minV..maxV) {
            val y = yOf(initial)
            canvas.drawLine(rect.left, y, rect.right, y, baseline)
        }

        text.textAlign = Paint.Align.LEFT
        val maxLabel = String.format(java.util.Locale.US, "%,.0f", values.max())
        val minLabel = String.format(java.util.Locale.US, "%,.0f", values.min())
        canvas.drawText(maxLabel, rect.right + density * 5, rect.top + density * 9, text)
        canvas.drawText(minLabel, rect.right + density * 5, rect.bottom, text)

        val last = values.last()
        val y = yOf(last)
        val label = String.format(java.util.Locale.US, "%,.0f", last)
        val tagWidth = tagText.measureText(label) + density * 10
        val left = (rect.right - tagWidth).coerceAtLeast(rect.left)
        val tagRect = RectF(left, y - density * 8, left + tagWidth, y + density * 8)
        canvas.drawRoundRect(tagRect, density * 3, density * 3, tag)
        canvas.drawText(label, tagRect.centerX(), y + density * 3.2f, tagText)
    }
}
