package com.aquascope.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.aquascope.R
import com.aquascope.baseline.AnomalyThresholds

/** Latest score of several places as horizontal bars on a 0–100 scale, with the 50% dry/damp line. */
class PlaceBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** [trusted] false draws the bar grey: that place can't tell dry from damp yet. */
    data class Bar(val label: String, val score: Double, val trusted: Boolean, val againstDamp: Boolean)

    private val density = resources.displayMetrics.density
    private val rowHeight = 30f * density
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density
        color = ContextCompat.getColor(context, R.color.warm_muted)
    }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        color = ContextCompat.getColor(context, R.color.warm_white)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.hairline_dark)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val splitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = ContextCompat.getColor(context, R.color.warm_muted)
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }

    private val ok = ContextCompat.getColor(context, R.color.status_ok)
    private val warn = ContextCompat.getColor(context, R.color.status_warn)
    private val alert = ContextCompat.getColor(context, R.color.status_alert)
    private val faint = ContextCompat.getColor(context, R.color.warm_faint)

    private var bars: List<Bar> = emptyList()
    private val rect = RectF()

    fun setBars(list: List<Bar>) {
        bars = list
        contentDescription = list.joinToString(", ") { "${it.label} ${it.score.toInt()}%" }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (bars.size * rowHeight + 4f * density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val labelWidth = width * 0.32f
        val valueWidth = valuePaint.measureText("100%") + 6f * density
        val left = labelWidth + 8f * density
        val right = width - valueWidth
        val barH = 10f * density
        val radius = barH / 2

        bars.forEachIndexed { i, bar ->
            val cy = i * rowHeight + rowHeight / 2
            val label = TextUtils.ellipsize(bar.label, labelPaint, labelWidth, TextUtils.TruncateAt.END).toString()
            canvas.drawText(label, 0f, cy + labelPaint.textSize * 0.35f, labelPaint)

            rect.set(left, cy - barH / 2, right, cy + barH / 2)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            val s = bar.score.coerceIn(0.0, 100.0)
            barPaint.color = when {
                !bar.trusted -> faint
                bar.againstDamp -> if (s < AnomalyThresholds.DAMP_SPLIT) ok else alert
                s < AnomalyThresholds.GREEN_MAX -> ok
                s < AnomalyThresholds.YELLOW_MAX -> warn
                else -> alert
            }
            rect.right = left + (right - left) * (s / 100.0).toFloat()
            if (rect.width() > 0f) canvas.drawRoundRect(rect, radius, radius, barPaint)

            canvas.drawText("${s.toInt()}%", right + 6f * density, cy + valuePaint.textSize * 0.35f, valuePaint)
        }
        val splitX = left + (right - left) * (AnomalyThresholds.DAMP_SPLIT / 100.0).toFloat()
        canvas.drawLine(splitX, 0f, splitX, bars.size * rowHeight, splitPaint)
    }
}
