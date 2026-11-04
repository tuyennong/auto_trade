package com.smclab.autotrade.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.smclab.autotrade.R
import com.smclab.autotrade.model.Side
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object Ui {

    private val dateTime = SimpleDateFormat("dd/MM HH:mm", Locale.US)
    private val timeOnly = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun money(value: Double): String = String.format(Locale.US, "%,.2f", value)

    fun signedMoney(value: Double): String =
        (if (value > 0) "+" else "") + String.format(Locale.US, "%,.2f", value)

    fun compact(value: Double): String = when {
        abs(value) >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000)
        abs(value) >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000)
        else -> String.format(Locale.US, "%.2f", value)
    }

    fun signedPct(value: Double): String =
        (if (value > 0) "+" else "") + String.format(Locale.US, "%.2f%%", value)

    fun dateTime(millis: Long): String = dateTime.format(Date(millis))
    fun time(millis: Long): String = timeOnly.format(Date(millis))

    fun duration(openMillis: Long, closeMillis: Long = System.currentTimeMillis()): String {
        val total = ((closeMillis - openMillis) / 1000L).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun sideColor(context: Context, side: Side): Int = ContextCompat.getColor(
        context,
        if (side == Side.BUY) R.color.buy else R.color.sell,
    )

    fun profitColor(context: Context, value: Double): Int = ContextCompat.getColor(
        context,
        when {
            value > 0 -> R.color.buy
            value < 0 -> R.color.sell
            else -> R.color.text_dim
        },
    )

    fun sideBadge(context: Context, text: TextView, side: Side) {
        text.text = side.label
        text.setTextColor(sideColor(context, side))
        text.setBackgroundResource(if (side == Side.BUY) R.drawable.bg_signal_buy else R.drawable.bg_signal_sell)
    }

    fun chip(parent: ViewGroup, text: String, checked: Boolean = false): Chip {
        val chip = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chip, parent, false) as Chip
        chip.text = text
        chip.isChecked = checked
        chip.id = View.generateViewId()
        chip.isCheckable = true
        return chip
    }

    fun quickLot(parent: ViewGroup, text: String): TextView {
        val context = parent.context
        val tv = TextView(context)
        tv.text = text
        tv.textSize = 10.5f
        tv.setPadding(dp(context, 10f), dp(context, 5f), dp(context, 10f), dp(context, 5f))
        tv.setTextColor(ContextCompat.getColor(context, R.color.text_dim))
        tv.setBackgroundResource(R.drawable.bg_chip)
        tv.setTypeface(android.graphics.Typeface.MONOSPACE)
        val params = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.marginEnd = dp(context, 6f)
        tv.layoutParams = params
        return tv
    }

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun isDark(context: Context): Boolean = true
}
