package com.smclab.autotrade.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.smclab.autotrade.R

class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var values: List<Double> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = resources.displayMetrics.density * 1.4f
        style = Paint.Style.STROKE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setValues(values: List<Double>, positive: Boolean? = null) {
        this.values = values
        val up = positive ?: (values.size < 2 || values.last() >= values.first())
        val color = ContextCompat.getColor(context, if (up) R.color.buy else R.color.sell)
        line.color = color
        fill.shader = null
        fill.color = color
        fill.alpha = 22
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2 || width <= 0 || height <= 0) return
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 1e-12 } ?: 1.0
        val pad = height * 0.14f
        val usable = height - pad * 2
        val step = width.toFloat() / (values.size - 1)
        val path = Path()
        val area = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = (pad + (1.0 - (v - min) / span) * usable).toFloat()
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, height.toFloat())
                area.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(width.toFloat(), height.toFloat())
        area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
    }
}
