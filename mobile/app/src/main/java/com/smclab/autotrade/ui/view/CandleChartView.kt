package com.smclab.autotrade.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.smclab.autotrade.R
import com.smclab.autotrade.model.Candle
import com.smclab.autotrade.model.Side
import com.smclab.autotrade.model.SmcSnapshot
import com.smclab.autotrade.model.SymbolSpec
import com.smclab.autotrade.model.Timeframe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ChartOverlays(
    val ob: Boolean = true,
    val fvg: Boolean = true,
    val structure: Boolean = true,
    val liquidity: Boolean = true,
    val ote: Boolean = true,
)

/** Entry / stop / target of the latest strategy signal, drawn on the chart. */
data class ChartSignal(
    val side: Side,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val index: Int,
)

/** Open position levels for the symbol currently displayed. */
data class ChartPositionLevel(
    val side: Side,
    val lots: Double,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
)

/**
 * Lightweight candlestick renderer with SMC overlays.
 *
 * Interactions (MT5-like):
 *  - one finger drag pans through history
 *  - pinch zooms in/out; the zoom buttons do the same
 *  - double tap or the reset button returns to the live edge
 *  - tap / long press shows the crosshair with OHLC values
 *
 * It also draws the current strategy entry, stop loss and take profit plus the
 * levels of any open position on the displayed symbol.
 */
class CandleChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var candles: List<Candle> = emptyList()
    private var snapshot: SmcSnapshot? = null
    private var spec: SymbolSpec? = null
    private var timeframe: Timeframe = Timeframe.H1
    private var overlays = ChartOverlays()
    private var signal: ChartSignal? = null
    private var positions: List<ChartPositionLevel> = emptyList()

    private var crossX = -1f
    private var crossY = -1f
    private var showCross = false

    /** Number of candles visible at the current zoom level. */
    private var visibleCount = DEFAULT_VISIBLE
    /** Candles hidden on the right side (0 = following the live edge). */
    private var rightOffset = 0f

    private var renderStart = 0
    private var renderCount = 0
    private var renderStep = 1f

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.stroke_soft)
        strokeWidth = dp(1f)
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        textSize = dp(9.5f)
        isAntiAlias = true
    }
    private val candleUp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.buy)
    }
    private val candleDown = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.sell)
    }
    private val wickUp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.buy)
        strokeWidth = dp(1f)
    }
    private val wickDown = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.sell)
        strokeWidth = dp(1f)
    }
    private val zoneBull = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zoneBear = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zoneBorderBull = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.buy)
        style = Paint.Style.STROKE
        strokeWidth = dp(0.9f)
    }
    private val zoneBorderBear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.sell)
        style = Paint.Style.STROKE
        strokeWidth = dp(0.9f)
    }
    private val structurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    }
    private val chochPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.purple)
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    }
    private val liquidityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.warning)
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(4f)), 0f)
    }
    private val otePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.purple)
        style = Paint.Style.FILL
    }
    private val priceLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.1f)
        style = Paint.Style.STROKE
    }
    private val levelDash = DashPathEffect(floatArrayOf(dp(6f), dp(3f)), 0f)
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_dim)
        strokeWidth = dp(0.8f)
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
    }
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        textSize = dp(9.5f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(8.5f)
        isFakeBoldText = true
    }
    private val watermark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text)
        alpha = 14
        textSize = dp(30f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface)
    }

    private val chartRect = RectF()
    private val bodyRect = RectF()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    private val dayFormat = SimpleDateFormat("dd/MM", Locale.US)

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var longPressed = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                showCross = false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor, detector.focusX)
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                longPressed = true
                dragging = false
                showCross = true
                crossX = e.x
                crossY = e.y
                invalidate()
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                longPressed = true
                resetView()
                return true
            }
        },
    )

    fun setData(
        spec: SymbolSpec,
        candles: List<Candle>,
        snapshot: SmcSnapshot?,
        timeframe: Timeframe,
        overlays: ChartOverlays = this.overlays,
    ) {
        this.spec = spec
        this.candles = candles
        this.snapshot = snapshot
        this.timeframe = timeframe
        this.overlays = overlays
        if (candles.isNotEmpty()) {
            val maxOffset = (candles.size - visibleCount).coerceAtLeast(0).toFloat()
            rightOffset = rightOffset.coerceIn(0f, maxOffset)
        }
        invalidate()
    }

    fun setOverlays(overlays: ChartOverlays) {
        this.overlays = overlays
        invalidate()
    }

    /** Latest strategy setup. Pass null when there is no valid entry. */
    fun setSignal(signal: ChartSignal?) {
        this.signal = signal
        invalidate()
    }

    /** Open positions for the symbol currently shown. */
    fun setPositionLevels(levels: List<ChartPositionLevel>) {
        positions = levels
        invalidate()
    }

    fun zoomIn() = zoomBy(1.35f, chartRect.centerX())

    fun zoomOut() = zoomBy(1f / 1.35f, chartRect.centerX())

    /** Return to the live edge at the default zoom. */
    fun resetView() {
        visibleCount = DEFAULT_VISIBLE
        rightOffset = 0f
        showCross = false
        invalidate()
    }

    private fun zoomBy(factor: Float, focusX: Float) {
        if (candles.isEmpty() || renderCount <= 0 || chartRect.width() <= 0f) return
        val maxCount = candles.size.coerceAtMost(MAX_VISIBLE)
        val newCount = (visibleCount / factor).roundToInt().coerceIn(MIN_VISIBLE, maxCount)
        if (newCount == visibleCount) return

        val focus = ((focusX - chartRect.left) / chartRect.width()).coerceIn(0f, 1f)
        val focusIndex = renderStart + focus * renderCount
        visibleCount = newCount
        val newStart = focusIndex - focus * newCount
        rightOffset = (candles.size - newCount - newStart)
            .coerceIn(0f, (candles.size - newCount).coerceAtLeast(0).toFloat())
        showCross = false
        invalidate()
    }

    private fun panBy(dx: Float) {
        if (candles.isEmpty() || renderStep <= 0f) return
        val bars = dx / renderStep
        rightOffset = (rightOffset + bars).coerceIn(
            0f,
            (candles.size - renderCount).coerceAtLeast(0).toFloat(),
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), dp(12f), dp(12f), bg)
        if (width < 40 || height < 40) return
        val axisWidth = dp(54f)
        val bottom = height - dp(18f)
        chartRect.set(dp(2f), dp(8f), width - axisWidth, bottom)
        val spec = this.spec
        if (candles.isEmpty() || spec == null) {
            canvas.drawText("Đang tải dữ liệu…", width / 2f, height / 2f, watermark)
            return
        }

        val total = candles.size
        val count = visibleCount.coerceIn(1, total)
        val maxOffset = (total - count).coerceAtLeast(0).toFloat()
        rightOffset = rightOffset.coerceIn(0f, maxOffset)
        val start = (total - count - rightOffset).roundToInt().coerceIn(0, total - count)
        renderStart = start
        renderCount = count
        renderStep = chartRect.width() / count

        val visible = candles.subList(start, start + count)
        var minPrice = visible.minOf { it.low }
        var maxPrice = visible.maxOf { it.high }
        if (maxPrice <= minPrice) {
            maxPrice = minPrice + spec.pipSize * 10
        }
        val pad = (maxPrice - minPrice) * 0.08
        minPrice -= pad
        maxPrice += pad
        val priceSpan = (maxPrice - minPrice).coerceAtLeast(spec.pipSize)
        fun yOf(price: Double): Float =
            (chartRect.bottom - ((price - minPrice) / priceSpan * chartRect.height())).toFloat()

        fun xOf(index: Int): Float =
            chartRect.left + (index - start + 0.5f) * renderStep

        drawGrid(canvas, minPrice, maxPrice, spec)

        if (overlays.ote) drawOte(canvas, ::yOf)
        if (overlays.ob || overlays.fvg) drawZones(canvas, ::yOf)
        if (overlays.liquidity) drawLiquidity(canvas, ::yOf)
        if (overlays.structure) drawStructure(canvas, ::yOf)

        val bodyWidth = (renderStep * 0.62f).coerceAtLeast(dp(1.6f))
        for ((offset, c) in visible.withIndex()) {
            val x = chartRect.left + (offset + 0.5f) * renderStep
            val up = c.close >= c.open
            val wick = if (up) wickUp else wickDown
            canvas.drawLine(x, yOf(c.high), x, yOf(c.low), wick)
            val top = yOf(max(c.open, c.close))
            val bot = yOf(min(c.open, c.close))
            bodyRect.set(x - bodyWidth / 2f, top, x + bodyWidth / 2f, max(bot, top + dp(1f)))
            canvas.drawRect(bodyRect, if (up) candleUp else candleDown)
        }

        drawLevels(canvas, spec, ::yOf, ::xOf)
        drawCurrentPrice(canvas, spec, ::yOf)

        watermark.textSize = dp(26f)
        canvas.drawText("DEMO · SMC", width / 2f, chartRect.centerY() + dp(6f), watermark)

        // Time axis
        val labelCount = 4
        for (k in 0 until labelCount) {
            val idx = start + (count - 1) * k / (labelCount - 1).coerceAtLeast(1)
            if (idx !in candles.indices) continue
            val text = if (timeframe == Timeframe.D1) {
                dayFormat.format(Date(candles[idx].time))
            } else {
                timeFormat.format(Date(candles[idx].time))
            }
            val x = chartRect.left + (idx - start + 0.5f) * renderStep
            val align = when (k) {
                0 -> Paint.Align.LEFT
                labelCount - 1 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            axisText.textAlign = align
            canvas.drawText(text, x, height - dp(4f), axisText)
        }
        axisText.textAlign = Paint.Align.LEFT

        // Zoom indicator.
        axisText.textAlign = Paint.Align.LEFT
        axisText.alpha = 150
        canvas.drawText("${count} nến", chartRect.left + dp(3f), chartRect.top + dp(9f), axisText)
        axisText.alpha = 255

        if (showCross) drawCrosshair(canvas, start, minPrice, maxPrice, spec)
    }

    private fun drawGrid(canvas: Canvas, minPrice: Double, maxPrice: Double, spec: SymbolSpec) {
        val lines = 4
        axisText.textAlign = Paint.Align.LEFT
        for (i in 0..lines) {
            val ratio = i / lines.toFloat()
            val y = chartRect.top + chartRect.height() * ratio
            canvas.drawLine(chartRect.left, y, chartRect.right, y, gridPaint)
            val price = maxPrice - (maxPrice - minPrice) * ratio
            canvas.drawText(spec.formatPrice(price), chartRect.right + dp(5f), y + dp(3f), axisText)
        }
    }

    private fun drawZones(canvas: Canvas, yOf: (Double) -> Float) {
        val snap = snapshot ?: return
        if (overlays.ob) {
            snap.bullishObs.forEach { drawZone(canvas, yOf, it.top, it.bottom, true, false, it.confirmedIndex) }
            snap.bearishObs.forEach { drawZone(canvas, yOf, it.top, it.bottom, false, false, it.confirmedIndex) }
        }
        if (overlays.fvg) {
            snap.bullishFvgs.forEach { drawZone(canvas, yOf, it.top, it.bottom, true, true, it.confirmedIndex) }
            snap.bearishFvgs.forEach { drawZone(canvas, yOf, it.top, it.bottom, false, true, it.confirmedIndex) }
        }
    }

    private fun drawZone(
        canvas: Canvas,
        yOf: (Double) -> Float,
        top: Double,
        bottom: Double,
        bullish: Boolean,
        fvg: Boolean,
        confirmedIndex: Int,
    ) {
        val color = ContextCompat.getColor(context, if (bullish) R.color.buy else R.color.sell)
        zoneBull.color = color
        zoneBull.alpha = if (fvg) 18 else 30
        val left = (chartRect.left + (confirmedIndex - renderStart).coerceAtLeast(0) * renderStep)
            .coerceIn(chartRect.left, chartRect.right - dp(4f))
        val right = chartRect.right
        val yTop = yOf(top)
        val yBottom = yOf(bottom)
        if (yBottom < chartRect.top || yTop > chartRect.bottom) return
        if (confirmedIndex > renderStart + renderCount) return
        val rect = RectF(left, max(yTop, chartRect.top), right, min(yBottom, chartRect.bottom))
        canvas.drawRoundRect(rect, dp(2f), dp(2f), zoneBull)
        val border = if (bullish) zoneBorderBull else zoneBorderBear
        border.alpha = if (fvg) 110 else 150
        canvas.drawRoundRect(rect, dp(2f), dp(2f), border)
        labelPaint.color = color
        labelPaint.alpha = 210
        canvas.drawText(if (fvg) "FVG" else "OB", left + dp(3f), max(yTop, chartRect.top) + dp(9f), labelPaint)
    }

    private fun drawStructure(canvas: Canvas, yOf: (Double) -> Float) {
        val snap = snapshot ?: return
        snap.breaks.takeLast(7).forEach { brk ->
            if (brk.index < renderStart - 4 || brk.index > renderStart + renderCount + 4) return@forEach
            val x1 = (chartRect.left + (brk.index - renderStart + 0.5f) * renderStep)
                .coerceIn(chartRect.left + dp(2f), chartRect.right - dp(2f))
            val x2 = min(chartRect.right - dp(2f), x1 + renderStep * 7f)
            val y = yOf(brk.swingPrice)
            if (y !in chartRect.top..chartRect.bottom) return@forEach
            val paint = if (brk.isBos) structurePaint else chochPaint
            canvas.drawLine(x1, y, x2, y, paint)
            labelPaint.color = if (brk.isBos) {
                ContextCompat.getColor(context, R.color.primary)
            } else {
                ContextCompat.getColor(context, R.color.purple)
            }
            labelPaint.alpha = 230
            canvas.drawText(brk.label, x1 + dp(2f), y - dp(3f), labelPaint)
        }
    }

    private fun drawLiquidity(canvas: Canvas, yOf: (Double) -> Float) {
        val snap = snapshot ?: return
        snap.liquidity.takeLast(6).forEach { level ->
            if (level.confirmedIndex > renderStart + renderCount) return@forEach
            val y = yOf(level.price)
            if (y !in chartRect.top..chartRect.bottom) return@forEach
            val x1 = (chartRect.left + (level.confirmedIndex - renderStart + 0.5f) * renderStep)
                .coerceIn(chartRect.left + dp(2f), chartRect.right - dp(2f))
            canvas.drawLine(x1, y, chartRect.right - dp(2f), y, liquidityPaint)
            labelPaint.color = ContextCompat.getColor(context, R.color.warning)
            labelPaint.alpha = 220
            canvas.drawText(
                if (level.isHigh) "EQH" else "EQL",
                chartRect.right - dp(24f),
                y - dp(3f),
                labelPaint,
            )
        }
    }

    private fun drawOte(canvas: Canvas, yOf: (Double) -> Float) {
        val ote = snapshot?.ote ?: return
        val yTop = yOf(ote.top)
        val yBottom = yOf(ote.bottom)
        if (yBottom < chartRect.top || yTop > chartRect.bottom) return
        otePaint.color = ContextCompat.getColor(context, R.color.purple)
        otePaint.alpha = 26
        val rect = RectF(chartRect.left, max(yTop, chartRect.top), chartRect.right, min(yBottom, chartRect.bottom))
        canvas.drawRect(rect, otePaint)
        otePaint.alpha = 90
        otePaint.style = Paint.Style.STROKE
        canvas.drawRect(rect, otePaint)
        otePaint.style = Paint.Style.FILL
        labelPaint.color = ContextCompat.getColor(context, R.color.purple)
        labelPaint.alpha = 220
        canvas.drawText("OTE", chartRect.left + dp(3f), max(yTop, chartRect.top) + dp(9f), labelPaint)
    }

    private fun drawLevels(
        canvas: Canvas,
        spec: SymbolSpec,
        yOf: (Double) -> Float,
        xOf: (Int) -> Float,
    ) {
        positions.take(3).forEach { p ->
            val color = ContextCompat.getColor(context, if (p.side == Side.BUY) R.color.buy else R.color.sell)
            levelPaint.color = color
            levelPaint.alpha = 120
            drawLevel(canvas, yOf, p.entry, color, dashed = false, alpha = 150, label = null)
            if (p.stopLoss > 0) {
                drawLevel(
                    canvas, yOf, p.stopLoss,
                    ContextCompat.getColor(context, R.color.sell),
                    dashed = true, alpha = 110,
                    label = "SL lệnh",
                    labelBelow = true,
                )
            }
            if (p.takeProfit > 0) {
                drawLevel(
                    canvas, yOf, p.takeProfit,
                    ContextCompat.getColor(context, R.color.buy),
                    dashed = true, alpha = 110,
                    label = "TP lệnh",
                )
            }
            val entryY = yOf(p.entry)
            if (entryY in chartRect.top..chartRect.bottom) {
                labelPaint.color = color
                labelPaint.alpha = 235
                canvas.drawText(
                    "LỆNH ${p.side.label} ${String.format(Locale.US, "%.2f", p.lots)} @ ${spec.formatPrice(p.entry)}",
                    chartRect.left + dp(4f),
                    (entryY - dp(3f)).coerceAtLeast(chartRect.top + dp(9f)),
                    labelPaint,
                )
            }
        }

        val s = signal ?: return
        val color = ContextCompat.getColor(context, if (s.side == Side.BUY) R.color.buy else R.color.sell)
        drawLevel(
            canvas, yOf, s.entry, color, dashed = false, alpha = 235,
            label = "VÀO ${s.side.label} ${spec.formatPrice(s.entry)}",
        )
        if (s.stopLoss > 0) {
            drawLevel(
                canvas, yOf, s.stopLoss,
                ContextCompat.getColor(context, R.color.sell),
                dashed = true, alpha = 210,
                label = "SL ${spec.formatPrice(s.stopLoss)}",
                labelBelow = true,
            )
        }
        if (s.takeProfit > 0) {
            drawLevel(
                canvas, yOf, s.takeProfit,
                ContextCompat.getColor(context, R.color.buy),
                dashed = true, alpha = 210,
                label = "TP ${spec.formatPrice(s.takeProfit)}",
            )
        }
        drawSignalMarker(canvas, s, yOf, xOf)
    }

    private fun drawLevel(
        canvas: Canvas,
        yOf: (Double) -> Float,
        price: Double,
        color: Int,
        dashed: Boolean,
        alpha: Int,
        label: String?,
        labelBelow: Boolean = false,
    ) {
        if (price <= 0.0) return
        val y = yOf(price)
        if (y !in chartRect.top..chartRect.bottom) return
        levelPaint.color = color
        levelPaint.alpha = alpha
        levelPaint.pathEffect = if (dashed) levelDash else null
        canvas.drawLine(chartRect.left, y, chartRect.right, y, levelPaint)
        levelPaint.pathEffect = null
        if (label != null) {
            labelPaint.color = color
            labelPaint.alpha = 245
            val labelY = if (labelBelow) {
                (y + dp(10f)).coerceAtMost(chartRect.bottom - dp(3f))
            } else {
                (y - dp(3f)).coerceAtLeast(chartRect.top + dp(9f))
            }
            canvas.drawText(label, chartRect.left + dp(4f), labelY, labelPaint)
        }
    }

    private fun drawSignalMarker(
        canvas: Canvas,
        signal: ChartSignal,
        yOf: (Double) -> Float,
        xOf: (Int) -> Float,
    ) {
        val c = candles.getOrNull(signal.index) ?: return
        val x = xOf(signal.index)
        if (x !in chartRect.left..chartRect.right) return
        val buy = signal.side == Side.BUY
        val color = ContextCompat.getColor(context, if (buy) R.color.buy else R.color.sell)
        val baseY = if (buy) yOf(c.low) + dp(7f) else yOf(c.high) - dp(7f)
        if (baseY !in chartRect.top..chartRect.bottom) return
        val path = Path()
        val size = dp(5.5f)
        if (buy) {
            path.moveTo(x, baseY)
            path.lineTo(x - size, baseY + size * 1.7f)
            path.lineTo(x + size, baseY + size * 1.7f)
        } else {
            path.moveTo(x, baseY)
            path.lineTo(x - size, baseY - size * 1.7f)
            path.lineTo(x + size, baseY - size * 1.7f)
        }
        path.close()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawPath(path, fill)
        labelPaint.color = color
        labelPaint.alpha = 255
        val text = if (buy) "MUA" else "BÁN"
        val textY = if (buy) baseY + size * 1.7f + dp(10f) else baseY - size * 1.7f - dp(4f)
        canvas.drawText(text, x + dp(7f), textY.coerceIn(chartRect.top + dp(9f), chartRect.bottom - dp(2f)), labelPaint)
    }

    private fun drawCurrentPrice(
        canvas: Canvas,
        spec: SymbolSpec,
        yOf: (Double) -> Float,
    ) {
        val last = candles.last().close
        val y = yOf(last)
        if (y !in chartRect.top..chartRect.bottom) return
        canvas.drawLine(chartRect.left, y, chartRect.right, y, priceLinePaint)
        val text = spec.formatPrice(last)
        tagText.measureText(text)
        val tagWidth = tagText.measureText(text) + dp(10f)
        val rect = RectF(chartRect.right + dp(1f), y - dp(8.5f), chartRect.right + tagWidth, y + dp(8.5f))
        tagPaint.color = ContextCompat.getColor(context, R.color.primary)
        canvas.drawRoundRect(rect, dp(3f), dp(3f), tagPaint)
        tagText.textAlign = Paint.Align.CENTER
        canvas.drawText(text, rect.centerX(), y + dp(3.2f), tagText)
    }

    private fun drawCrosshair(
        canvas: Canvas,
        start: Int,
        minPrice: Double,
        maxPrice: Double,
        spec: SymbolSpec,
    ) {
        if (crossX !in chartRect.left..chartRect.right || crossY !in chartRect.top..chartRect.bottom) return
        canvas.drawLine(crossX, chartRect.top, crossX, chartRect.bottom, crossPaint)
        canvas.drawLine(chartRect.left, crossY, chartRect.right, crossY, crossPaint)
        val price = maxPrice - (crossY - chartRect.top) / chartRect.height() * (maxPrice - minPrice)
        val text = spec.formatPrice(price)
        val tagWidth = tagText.measureText(text) + dp(10f)
        val rect = RectF(chartRect.right + dp(1f), crossY - dp(8f), chartRect.right + tagWidth, crossY + dp(8f))
        tagPaint.color = ContextCompat.getColor(context, R.color.surface_3)
        canvas.drawRoundRect(rect, dp(3f), dp(3f), tagPaint)
        tagText.textAlign = Paint.Align.CENTER
        canvas.drawText(text, rect.centerX(), crossY + dp(3f), tagText)

        val relative = ((crossX - chartRect.left) / chartRect.width() * renderCount).toInt()
        val idx = (start + relative).coerceIn(0, candles.size - 1)
        val c = candles[idx]
        val info = "${timeFormat.format(Date(c.time))}  O ${spec.formatPrice(c.open)}  H ${spec.formatPrice(c.high)}  " +
            "L ${spec.formatPrice(c.low)}  C ${spec.formatPrice(c.close)}"
        labelPaint.color = ContextCompat.getColor(context, R.color.text)
        labelPaint.alpha = 230
        labelPaint.textSize = dp(9f)
        canvas.drawText(info, chartRect.left + dp(4f), chartRect.bottom - dp(4f), labelPaint)
        labelPaint.textSize = dp(8.5f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                dragging = false
                longPressed = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                showCross = false
                longPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    lastX = event.x
                    return true
                }
                if (!dragging &&
                    (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                ) {
                    dragging = true
                    showCross = false
                }
                if (dragging) {
                    panBy(event.x - lastX)
                }
                lastX = event.x
            }
            MotionEvent.ACTION_UP -> {
                if (scaleDetector.isInProgress) return true
                if (!dragging && !longPressed) {
                    toggleCrosshair(event.x, event.y)
                }
                performClick()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                showCross = false
                invalidate()
            }
        }
        return true
    }

    private fun toggleCrosshair(x: Float, y: Float) {
        if (x in chartRect.left..chartRect.right && y in chartRect.top..chartRect.bottom) {
            showCross = !showCross
            crossX = x
            crossY = y
        } else {
            showCross = false
        }
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            dp(280f).toInt()
        }
        setMeasuredDimension(width, height)
    }

    private companion object {
        const val DEFAULT_VISIBLE = 84
        const val MIN_VISIBLE = 24
        const val MAX_VISIBLE = 260
    }
}
