package com.aquascope.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.aquascope.R
import com.aquascope.baseline.AnomalyThresholds
import com.aquascope.baseline.ScoreTrend
import java.util.Calendar

/**
 * A place's scores in scan order: one dot per scan, a rolling-median line, the 50% dry/damp line,
 * and a faint vertical line wherever a new day starts.
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.hairline_dark)
    }
    private val splitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = ContextCompat.getColor(context, R.color.warm_muted)
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.cyan_bright)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        color = ContextCompat.getColor(context, R.color.warm_faint)
    }

    private val ok = ContextCompat.getColor(context, R.color.status_ok)
    private val warn = ContextCompat.getColor(context, R.color.status_warn)
    private val alert = ContextCompat.getColor(context, R.color.status_alert)

    private var scores: List<Double> = emptyList()
    private var times: List<Long> = emptyList()
    private var againstDamp = false

    /** [timestamps] and [values] in chronological order; [againstDamp] when the place has damp examples. */
    fun setScores(timestamps: List<Long>, values: List<Double>, againstDamp: Boolean) {
        times = timestamps
        scores = values
        this.againstDamp = againstDamp
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val left = 26f * density
        val right = width - 8f * density
        val top = 8f * density
        val bottom = height - 8f * density
        fun y(score: Double) = bottom - (score.coerceIn(0.0, 100.0) / 100.0).toFloat() * (bottom - top)

        canvas.drawLine(left, top, right, top, gridPaint)
        canvas.drawLine(left, bottom, right, bottom, gridPaint)
        canvas.drawLine(left, y(AnomalyThresholds.DAMP_SPLIT), right, y(AnomalyThresholds.DAMP_SPLIT), splitPaint)
        canvas.drawText("100", 0f, top + labelPaint.textSize * 0.4f, labelPaint)
        canvas.drawText("50", 0f, y(50.0) + labelPaint.textSize * 0.4f, labelPaint)
        canvas.drawText("0", 0f, bottom, labelPaint)
        if (scores.isEmpty()) return

        val n = scores.size
        fun x(i: Int) = if (n == 1) (left + right) / 2 else left + i * (right - left) / (n - 1)

        val cal = Calendar.getInstance()
        var lastDay = -1
        times.forEachIndexed { i, t ->
            cal.timeInMillis = t
            val day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            if (lastDay != -1 && day != lastDay) {
                val dx = (x(i - 1) + x(i)) / 2
                canvas.drawLine(dx, top, dx, bottom, gridPaint)
            }
            lastDay = day
        }

        if (n >= 2) {
            val smooth = ScoreTrend.rollingMedian(scores)
            val path = Path()
            smooth.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(i), y(s)) else path.lineTo(x(i), y(s)) }
            canvas.drawPath(path, linePaint)
        }

        val r = (if (n > 60) 2.2f else 3.2f) * density
        scores.forEachIndexed { i, s ->
            dotPaint.color = when {
                againstDamp -> if (s < AnomalyThresholds.DAMP_SPLIT) ok else alert
                s < AnomalyThresholds.GREEN_MAX -> ok
                s < AnomalyThresholds.YELLOW_MAX -> warn
                else -> alert
            }
            canvas.drawCircle(x(i), y(s), r, dotPaint)
        }
    }
}
