package com.aquascope.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.aquascope.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Scan field: stacked bright wave layers on concentric rings.
 * Live amplitude / spectrum come from real capture — never invented.
 */
class AcousticFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dens = resources.displayMetrics.density

    private val baseRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * dens
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val hashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * dens
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val wavePath = Path()
    private val harmonicPath = Path()

    private val cyan = ContextCompat.getColor(context, R.color.cyan)
    private val cyanBright = ContextCompat.getColor(context, R.color.cyan_bright)
    private val cyanBloom = ContextCompat.getColor(context, R.color.cyan_bloom)
    private val amber = ContextCompat.getColor(context, R.color.amber)
    private val paper = ContextCompat.getColor(context, R.color.paper_mist)

    private var scanning = false
    private var liveAmp = 0f
    private var spectrum: FloatArray = FloatArray(0)
    private var phase = 0f
    private var highlightAmber = false
    private var animator: ValueAnimator? = null

    fun setScanning(value: Boolean) {
        scanning = value
        if (!value) liveAmp = 0f
        animator?.duration = if (value) 2800L else 5200L
        invalidate()
    }

    fun setLiveAmplitude(rms: Float) {
        liveAmp = rms.coerceIn(0f, 1f)
        invalidate()
    }

    fun setSpectrum(bands: FloatArray, anomaly: Boolean = false) {
        spectrum = bands
        highlightAmber = anomaly
        scanning = false
        invalidate()
    }

    fun clearSignal() {
        spectrum = FloatArray(0)
        liveAmp = 0f
        highlightAmber = false
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 5200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedFraction
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(cx, cy) * 0.94f
        val hot = if (highlightAmber) amber else cyanBloom
        val mid = if (highlightAmber) amber else cyanBright
        val cool = if (highlightAmber) amber else cyan

        fillPaint.color = hot
        fillPaint.alpha = (36 + liveAmp * 40).toInt().coerceIn(28, 90)
        canvas.drawCircle(cx, cy, maxR * (0.20f + liveAmp * 0.08f), fillPaint)
        fillPaint.color = mid
        fillPaint.alpha = (70 + liveAmp * 50).toInt().coerceIn(50, 140)
        canvas.drawCircle(cx, cy, maxR * 0.10f, fillPaint)
        fillPaint.color = hot
        fillPaint.alpha = 255
        canvas.drawCircle(cx, cy, maxR * 0.045f, fillPaint)

        val rings = 5
        for (i in 0 until rings) {
            val t = (i + 1f) / (rings + 1f)
            val baseR = maxR * (0.20f + t * 0.74f)
            val ringCore = when {
                highlightAmber && i >= rings - 2 -> amber
                i % 2 == 0 -> cyanBright
                else -> cyan
            }
            val ringGlow = when {
                highlightAmber && i >= rings - 2 -> amber
                i % 2 == 0 -> cyanBloom
                else -> cyanBright
            }

            baseRingPaint.color = ringCore
            baseRingPaint.alpha = (70 + (1f - t) * 90 + liveAmp * 40).toInt().coerceIn(60, 200)
            canvas.drawCircle(cx, cy, baseR, baseRingPaint)

            drawHashLayer(canvas, cx, cy, baseR, t, i, ringGlow)
            buildWave(wavePath, cx, cy, baseR, t, i, lobes = 5 + i, invert = false)
            buildWave(harmonicPath, cx, cy, baseR, t, i, lobes = 8 + i, invert = true)
            drawBrightLayer(canvas, harmonicPath, t, cool, glow = true)
            drawBrightLayer(canvas, wavePath, t, ringGlow, glow = true)
            drawBrightLayer(canvas, wavePath, t, ringCore, glow = false)
        }
    }

    private fun drawHashLayer(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        baseR: Float,
        t: Float,
        ringIndex: Int,
        color: Int
    ) {
        val tickCount = 36 + ringIndex * 8
        val tickLen = (4.2f + (1f - t) * 6f + liveAmp * 5f) * dens
        val spin = phase * 2.0 * PI * (if (ringIndex % 2 == 0) 0.42 else -0.34)
        hashPaint.color = color
        hashPaint.alpha = (90 + (1f - t) * 90 + liveAmp * 50).toInt().coerceIn(70, 230)

        for (k in 0 until tickCount) {
            if ((k + ringIndex) % 2 == 0) continue
            val a = (k / tickCount.toFloat()) * 2.0 * PI + spin
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val r0 = baseR - tickLen * 0.5f
            val r1 = baseR + tickLen * 0.5f
            canvas.drawLine(
                cx + ca * r0,
                cy + sa * r0,
                cx + ca * r1,
                cy + sa * r1,
                hashPaint
            )
        }
    }

    private fun buildWave(
        path: Path,
        cx: Float,
        cy: Float,
        baseR: Float,
        t: Float,
        ringIndex: Int,
        lobes: Int,
        invert: Boolean
    ) {
        val steps = 120
        val amp = baseR * (1.2f - t * 0.4f) * 0.10f
        val dir = if (invert xor (ringIndex % 2 == 0)) 1.0 else -1.0
        val travel = phase * 2.0 * PI * (1.05 + ringIndex * 0.18) * dir
        path.reset()
        for (s in 0..steps) {
            val u = s / steps.toFloat()
            val a = u * 2.0 * PI
            val level = if (scanning) liveAmp else spectrumSample(u)
            val wobble = sin(a * lobes + travel).toFloat() * amp * level
            val r = baseR + wobble
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private fun drawBrightLayer(
        canvas: Canvas,
        path: Path,
        t: Float,
        color: Int,
        glow: Boolean
    ) {
        wavePaint.color = color
        if (glow) {
            wavePaint.alpha = (55 + (1f - t) * 70 + liveAmp * 50).toInt().coerceIn(50, 160)
            wavePaint.strokeWidth = (7.5f + (1f - t) * 3.5f + liveAmp * 2.4f) * dens
        } else {
            wavePaint.alpha = (190 + (1f - t) * 50 + liveAmp * 15).toInt().coerceIn(180, 255)
            wavePaint.strokeWidth = (2.2f + (1f - t) * 1.4f + liveAmp * 1.6f) * dens
        }
        canvas.drawPath(path, wavePaint)
        if (!glow) {
            wavePaint.color = paper
            wavePaint.alpha = (90 + liveAmp * 40).toInt().coerceIn(70, 160)
            wavePaint.strokeWidth = (0.9f + liveAmp * 0.6f) * dens
            canvas.drawPath(path, wavePaint)
        }
    }

    private fun spectrumSample(u: Float): Float {
        if (spectrum.isEmpty()) return 0f
        val idx = (u * spectrum.size).toInt().coerceIn(0, spectrum.lastIndex)
        return spectrum[idx].coerceIn(0f, 1f)
    }
}
