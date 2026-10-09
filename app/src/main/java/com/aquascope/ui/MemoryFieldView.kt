package com.aquascope.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.aquascope.R
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Paper-cut memory strata of the home — visual metaphor, not a live sensor.
 * Stacked topographic layers with drop-shadow relief.
 */
class MemoryFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    data class FieldNode(
        val id: String,
        val label: String,
        val state: Int,
        val xFrac: Float,
        val yFrac: Float
    )

    companion object {
        const val STATE_NORMAL = 0
        const val STATE_ACTIVE = 1
        const val STATE_ANOMALY = 2
    }

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2A001018
    }
    private val crestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val nodeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.navy_deep)
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val cyan = ContextCompat.getColor(context, R.color.cyan)
    private val amber = ContextCompat.getColor(context, R.color.amber)
    private val navy = ContextCompat.getColor(context, R.color.navy_deep)
    private val cream = 0xFFFAF9F3.toInt()

    /** Light → dark paper cuts, drawn top (near sky) to bottom. */
    private val layerColors = intArrayOf(
        0xFFD6EAF0.toInt(),
        0xFFB0DEE9.toInt(),
        0xFF8EC9D6.toInt(),
        0xFF78B0C8.toInt(),
        0xFF4AAFC2.toInt(),
        0xFF6EA2B3.toInt(),
        0xFF4E6EA2.toInt(),
        0xFF126A8A.toInt(),
        0xFF0A4174.toInt(),
        0xFF0C2A43.toInt(),
        0xFF071A2B.toInt(),
        0xFF001D39.toInt()
    )

    private val layerCount = layerColors.size
    private val samples = 32
    private val paths = Array(layerCount) { Path() }
    private val crests = Array(layerCount) { Path() }
    private val sampleX = FloatArray(samples + 1)
    private val sampleY = FloatArray(samples + 1)
    private var phase = 0f
    private var pulse = 0f
    private var disturbance = 0f
    private var density = 0.35f
    private var nodes: List<FieldNode> = emptyList()
    private var selectedId: String? = null
    private var animator: ValueAnimator? = null
    private var expressionSpeed = 1f
    private var expressionTint = 0
    var onNodeTap: ((FieldNode) -> Unit)? = null

    fun setField(nodes: List<FieldNode>, disturbance: Float, density: Float) {
        this.nodes = nodes
        this.disturbance = disturbance.coerceIn(0f, 1f)
        this.density = density.coerceIn(0.15f, 1f)
        invalidate()
    }

    fun syncExpression(state: com.aquascope.halo.SmritiLightState) {
        when (state) {
            com.aquascope.halo.SmritiLightState.SCANNING -> {
                expressionSpeed = 1.8f
                expressionTint = 0
                density = (density + 0.08f).coerceAtMost(0.85f)
            }
            com.aquascope.halo.SmritiLightState.PROCESSING -> {
                expressionSpeed = 1.4f
                expressionTint = 1
                density = (density + 0.18f).coerceAtMost(1f)
            }
            com.aquascope.halo.SmritiLightState.ANOMALY -> {
                expressionSpeed = 0.9f
                expressionTint = 2
                disturbance = (disturbance + 0.18f).coerceAtMost(0.7f)
            }
            com.aquascope.halo.SmritiLightState.HIGH_ANOMALY,
            com.aquascope.halo.SmritiLightState.PERSISTENT_ANOMALY,
            com.aquascope.halo.SmritiLightState.CONFIRMED -> {
                expressionSpeed = 0.55f
                expressionTint = 2
                disturbance = (disturbance + 0.35f).coerceAtMost(1f)
            }
            com.aquascope.halo.SmritiLightState.MEMORY_RECALL,
            com.aquascope.halo.SmritiLightState.NEW_MEMORY -> {
                expressionSpeed = 1.15f
                expressionTint = 0
            }
            else -> {
                expressionSpeed = 1f
                expressionTint = 0
            }
        }
        restartAnimator()
        invalidate()
    }

    fun setSelected(id: String?) {
        selectedId = id
        invalidate()
    }

    private fun restartAnimator() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (22000 / expressionSpeed).toLong().coerceIn(8000L, 28000L)
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedFraction
                pulse = ((System.currentTimeMillis() % 2600L) / 2600f)
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null) restartAnimator()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val skyTop = when (expressionTint) {
            1 -> 0xFFE8E4F0.toInt()
            2 -> 0xFFF3E6D4.toInt()
            else -> cream
        }
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.42f,
            intArrayOf(skyTop, 0xFFC5DCE3.toInt(), 0xFF8EC9D6.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, skyPaint)
        skyPaint.shader = null

        val d = resources.displayMetrics.density
        for (i in 0 until layerCount) {
            val t = i / (layerCount - 1f)
            val baseY = h * (0.22f + t * 0.62f)
            val amp = h * (0.055f + 0.012f * (1f - t)) * (0.82f + density * 0.28f)
            val valley = h * (0.018f + t * 0.012f)
            val layerPhase = phase * (2f * PI.toFloat()) * (0.35f + i * 0.018f) + i * 0.91f
            for (s in 0..samples) {
                val x = w * s / samples
                sampleX[s] = x
                sampleY[s] = paperY(x, w, baseY, amp, valley, layerPhase, i)
            }
            buildSmoothWave(paths[i], crests[i], w, h)

            val shadowDy = (7f + i * 0.85f) * d
            canvas.save()
            canvas.translate(0f, shadowDy)
            shadowPaint.alpha = (28 + i * 4).coerceAtMost(72)
            canvas.drawPath(paths[i], shadowPaint)
            canvas.restore()

            layerPaint.color = layerColors[i]
            canvas.drawPath(paths[i], layerPaint)

            crestPaint.color = if (i < 4) 0x66FFFFFF else 0x22126A8A
            canvas.drawPath(crests[i], crestPaint)
        }

        val hit = 11f * d
        nodes.forEach { node ->
            val cx = node.xFrac * w
            val cy = node.yFrac * h
            val isAnomaly = node.state == STATE_ANOMALY
            val isActive = node.state == STATE_ACTIVE
            val color = if (isAnomaly) amber else cyan
            val pulseAmt = when {
                isAnomaly -> 0.55f + 0.45f * (0.5f + 0.5f * sin(pulse * 2f * PI).toFloat())
                isActive -> 0.70f + 0.20f * (0.5f + 0.5f * sin(pulse * PI).toFloat())
                else -> 0.45f + 0.12f * (0.5f + 0.5f * sin(pulse * 0.7f * PI).toFloat())
            }
            val r = hit * (if (node.id == selectedId) 1.25f else 1f)
            nodeFill.color = 0xFFFFFFFF.toInt()
            nodeFill.alpha = 230
            canvas.drawCircle(cx, cy, r * 1.05f, nodeFill)
            nodeFill.color = color
            nodeFill.alpha = (90 + pulseAmt * 90).toInt().coerceIn(50, 200)
            canvas.drawCircle(cx, cy, r * 2.0f, nodeFill)
            nodeFill.alpha = 255
            canvas.drawCircle(cx, cy, r * 0.38f, nodeFill)
            nodeStroke.color = if (isAnomaly) amber else navy
            nodeStroke.alpha = 200
            canvas.drawCircle(cx, cy, r * 0.82f, nodeStroke)
            labelPaint.color = if (cy < h * 0.42f) navy else 0xFFF5F5F0.toInt()
            labelPaint.alpha = 230
            canvas.drawText(node.label, cx, cy + r * 2.2f, labelPaint)
        }
    }

    private fun paperY(
        x: Float,
        w: Float,
        baseY: Float,
        amp: Float,
        valley: Float,
        phase: Float,
        layer: Int
    ): Float {
        val t = x / w
        val nx = (t - 0.5f) * 2f
        val bowl = (1f - nx * nx) * valley
        val hill = sin(t * 1.15 * 2.0 * PI + phase).toFloat()
        val drift = sin(t * 0.55 * 2.0 * PI + phase * 0.48f + layer * 0.37f).toFloat()
        val fine = sin(t * 2.1 * 2.0 * PI + layer * 0.9f).toFloat() * 0.12f
        val ripple = sin(t * 4.2 * PI + phase * 0.9f).toFloat() * disturbance * 0.14f
        return baseY + bowl + amp * (hill * 0.62f + drift * 0.26f + fine + ripple)
    }

    private fun buildSmoothWave(fill: Path, crest: Path, w: Float, h: Float) {
        fill.reset()
        crest.reset()
        val last = samples
        fill.moveTo(0f, h)
        fill.lineTo(sampleX[0], sampleY[0])
        crest.moveTo(sampleX[0], sampleY[0])
        for (i in 0 until last) {
            val i0 = (i - 1).coerceAtLeast(0)
            val i1 = i
            val i2 = i + 1
            val i3 = (i + 2).coerceAtMost(last)
            val c1x = sampleX[i1] + (sampleX[i2] - sampleX[i0]) / 6f
            val c1y = sampleY[i1] + (sampleY[i2] - sampleY[i0]) / 6f
            val c2x = sampleX[i2] - (sampleX[i3] - sampleX[i1]) / 6f
            val c2y = sampleY[i2] - (sampleY[i3] - sampleY[i1]) / 6f
            fill.cubicTo(c1x, c1y, c2x, c2y, sampleX[i2], sampleY[i2])
            crest.cubicTo(c1x, c1y, c2x, c2y, sampleX[i2], sampleY[i2])
        }
        fill.lineTo(w, h)
        fill.close()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (nodes.isEmpty()) return false
        val hitR = 28f * resources.displayMetrics.density
        val tapped = nodes.minByOrNull { node ->
            hypot(event.x - node.xFrac * width, event.y - node.yFrac * height)
        } ?: return false
        val dist = hypot(event.x - tapped.xFrac * width, event.y - tapped.yFrac * height)
        if (dist > hitR) return false
        if (event.action == MotionEvent.ACTION_UP) {
            selectedId = tapped.id
            invalidate()
            onNodeTap?.invoke(tapped)
        }
        return true
    }
}
