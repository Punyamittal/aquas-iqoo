package com.aquascope.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.aquascope.R
import com.aquascope.halo.HaloPalette
import com.aquascope.halo.HaloRender
import com.aquascope.halo.SmritiLightState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * On-screen acknowledgment of the rear camera-border Monster Halo.
 * Mirrors [MonsterHaloController] state when hardware is unavailable or for paired UI.
 */
class HaloIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.ocean)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.paper_sky)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val camPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.navy_deep)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ocean)
        textSize = 11f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private var render: HaloRender = HaloPalette.render(SmritiLightState.NORMAL, 0.65f)
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var animPeriodMs = -1

    fun bind(render: HaloRender) {
        this.render = render
        ensureAnim()
        invalidate()
    }

    private fun ensureAnim() {
        val period = render.periodMs.coerceIn(500, 5000)
        if (animator?.isRunning == true && animPeriodMs == period) return
        animator?.cancel()
        animPeriodMs = period
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = period.toLong()
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 8f || h < 8f) return

        val phoneW = min(w * 0.42f, h * 0.38f)
        val phoneH = phoneW * 1.85f
        val left = (w - phoneW) / 2f
        val top = h * 0.12f
        val phone = RectF(left, top, left + phoneW, top + phoneH)
        val radius = phoneW * 0.12f

        canvas.drawRoundRect(phone, radius, radius, fillPaint)
        canvas.drawRoundRect(phone, radius, radius, phonePaint)

        // Camera island near top (Monster Halo sits around this)
        val cx = phone.centerX()
        val cy = phone.top + phoneH * 0.22f
        val islandR = phoneW * 0.28f
        camPaint.color = ContextCompat.getColor(context, R.color.navy_deep)
        canvas.drawCircle(cx, cy, islandR * 0.55f, camPaint)
        canvas.drawCircle(cx - islandR * 0.22f, cy, islandR * 0.16f, phonePaint)
        canvas.drawCircle(cx + islandR * 0.22f, cy, islandR * 0.16f, phonePaint)

        val normal = render.state == SmritiLightState.NORMAL
        val intensity = when (render.motion) {
            HaloPalette.MOTION_BREATHE -> {
                val floor = if (normal) 0.78f else 0.55f
                val breath = (floor + (1f - floor) * (0.5f + 0.5f * sin(phase * Math.PI * 2).toFloat()))
                render.brightness * breath
            }
            HaloPalette.MOTION_SWEEP -> render.brightness
            else -> render.brightness
        }.coerceIn(0f, 1f)

        if (intensity > 0.02f && render.state != SmritiLightState.OFF) {
            val ringR = islandR * if (normal) 0.86f else 0.72f
            if (normal) {
                bloomPaint.color = withAlpha(render.colorArgb, (intensity * 70).toInt())
                canvas.drawCircle(cx, cy, ringR * 1.55f, bloomPaint)
                bloomPaint.color = withAlpha(render.secondaryArgb, (intensity * 110).toInt())
                canvas.drawCircle(cx, cy, ringR * 1.18f, bloomPaint)
                haloPaint.color = withAlpha(render.colorArgb, (intensity * 255).toInt())
                haloPaint.strokeWidth = (4.2f + intensity * 3.4f) * resources.displayMetrics.density
                canvas.drawCircle(cx, cy, ringR, haloPaint)
                haloPaint.color = withAlpha(render.secondaryArgb, (intensity * 200).toInt())
                haloPaint.strokeWidth = 2.2f * resources.displayMetrics.density
                canvas.drawCircle(cx, cy, ringR * 0.62f, haloPaint)
            } else if (render.motion == HaloPalette.MOTION_SWEEP) {
                haloPaint.color = withAlpha(render.colorArgb, (intensity * 230).toInt())
                haloPaint.strokeWidth = (2.5f + intensity * 2.5f) * resources.displayMetrics.density
                val start = phase * 360f
                canvas.drawArc(
                    cx - ringR, cy - ringR, cx + ringR, cy + ringR,
                    start, 110f, false, haloPaint
                )
                haloPaint.color = withAlpha(render.secondaryArgb, (intensity * 120).toInt())
                canvas.drawArc(
                    cx - ringR, cy - ringR, cx + ringR, cy + ringR,
                    start + 140f, 80f, false, haloPaint
                )
            } else {
                haloPaint.color = withAlpha(render.colorArgb, (intensity * 230).toInt())
                haloPaint.strokeWidth = (2.5f + intensity * 2.5f) * resources.displayMetrics.density
                canvas.drawCircle(cx, cy, ringR, haloPaint)
            }
            glowPaint.color = withAlpha(
                render.colorArgb,
                (intensity * if (normal) 220 else 90).toInt()
            )
            val sparks = if (normal) 8 else 4
            val sparkR = if (normal) 3.4f else 2.2f
            for (i in 0 until sparks) {
                val a = phase * Math.PI * 2 + i * (Math.PI * 2 / sparks)
                val gx = cx + cos(a).toFloat() * ringR
                val gy = cy + sin(a).toFloat() * ringR
                canvas.drawCircle(gx, gy, sparkR * resources.displayMetrics.density, glowPaint)
            }
        }

        canvas.drawText(
            render.label.lowercase().replaceFirstChar { it.uppercase() },
            w / 2f,
            phone.bottom + 14f * resources.displayMetrics.density,
            labelPaint.apply {
                color = ContextCompat.getColor(
                    context,
                    if (normal) R.color.cyan else R.color.ocean
                )
            }
        )
    }

    private fun withAlpha(argb: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }
}
