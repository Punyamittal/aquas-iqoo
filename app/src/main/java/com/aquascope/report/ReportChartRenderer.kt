package com.aquascope.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.aquascope.baseline.AnomalyThresholds
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-resolution inspection charts for AquaScope PDF reports.
 * Thermal map = interpolated moisture-risk field (not an IR camera capture).
 */
object ReportChartRenderer {

    data class Charts(
        val thermalMap: Bitmap,
        val density: Bitmap,
        val scoreBars: Bitmap,
        val featureProfile: Bitmap,
        val statusRing: Bitmap,
        val radar: Bitmap
    )

    fun generate(report: SessionReport): Charts {
        val points = report.points
        // 2× resolution for crisp PDF embedding
        return Charts(
            thermalMap = drawThermalMap(points, 1040, 560),
            density = drawDensity(points, 1040, 460),
            scoreBars = drawScoreBars(points, 1040, 500),
            featureProfile = drawFeatureProfile(points, 1040, 500),
            statusRing = drawStatusRing(report, 520, 460),
            radar = drawRadar(points, 520, 460)
        )
    }

    // ── Thermal / moisture field ───────────────────────────────────────────

    fun thermalField(points: List<ReportPoint>, cols: Int, rows: Int): Array<FloatArray> {
        val field = Array(rows) { FloatArray(cols) }
        if (points.isEmpty()) return field
        val n = points.size
        val gridSide = max(1, kotlin.math.ceil(sqrt(n.toDouble())).toInt())
        val coords = points.mapIndexed { i, p ->
            val col = i % gridSide
            val row = i / gridSide
            val x = 0.12f + (col / max(1f, gridSide - 1f)) * 0.76f
            val y = 0.14f + (row / max(1f, gridSide - 1f)) * 0.72f
            Triple(x, y.coerceIn(0.1f, 0.9f), p.anomalyScore.toFloat().coerceIn(0f, 100f))
        }
        val sigma = 0.16f
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = c / (cols - 1).toFloat()
                val y = r / (rows - 1).toFloat()
                var num = 0.0
                var den = 0.0
                for ((px, py, score) in coords) {
                    val d2 = (x - px) * (x - px) + (y - py) * (y - py)
                    val w = exp(-d2 / (2.0 * sigma * sigma))
                    num += w * score
                    den += w
                }
                field[r][c] = if (den > 1e-9) (num / den).toFloat() else 0f
            }
        }
        return field
    }

    private fun drawThermalMap(points: List<ReportPoint>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Deep scientific panel
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 28f, 28f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B1220.toInt() })

        canvas.drawText("MOISTURE RISK FIELD", 36f, 48f, labelPaint(26f, true, 0xFFF8FAFC.toInt()))
        canvas.drawText(
            "Gaussian-interpolated acoustic anomaly · thermal-style false colour · not IR camera",
            36f, 78f, labelPaint(16f, false, 0xFF94A3B8.toInt())
        )

        val left = 36f
        val top = 104f
        val right = w - 110f
        val bottom = h - 56f
        val mapW = (right - left).toInt().coerceAtLeast(8)
        val mapH = (bottom - top).toInt().coerceAtLeast(8)
        val cols = 96
        val rows = 60
        val field = thermalField(points, cols, rows)
        val cellW = mapW / cols.toFloat()
        val cellH = mapH / rows.toFloat()
        val cell = Paint(Paint.ANTI_ALIAS_FLAG)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                cell.color = thermalColor(field[r][c])
                canvas.drawRect(
                    left + c * cellW, top + r * cellH,
                    left + (c + 1) * cellW + 0.6f, top + (r + 1) * cellH + 0.6f, cell
                )
            }
        }

        // Contour isolines at 30 / 50 / 70
        val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
            color = 0x66FFFFFF
        }
        for (iso in listOf(30f, 50f, 70f)) {
            val path = Path()
            var started = false
            for (r in 0 until rows - 1) {
                for (c in 0 until cols - 1) {
                    val v00 = field[r][c]
                    val v10 = field[r][c + 1]
                    val v01 = field[r + 1][c]
                    // Simple edge crossings
                    fun cross(a: Float, b: Float, ax: Float, ay: Float, bx: Float, by: Float) {
                        if ((a < iso && b >= iso) || (a >= iso && b < iso)) {
                            val t = ((iso - a) / (b - a + 1e-6f)).coerceIn(0f, 1f)
                            val x = ax + (bx - ax) * t
                            val y = ay + (by - ay) * t
                            if (!started) {
                                path.moveTo(x, y)
                                started = true
                            } else path.lineTo(x, y)
                        }
                    }
                    val x0 = left + c * cellW
                    val y0 = top + r * cellH
                    cross(v00, v10, x0, y0, x0 + cellW, y0)
                    cross(v00, v01, x0, y0, x0, y0 + cellH)
                }
            }
            if (started) canvas.drawPath(path, contourPaint)
        }

        // Sample markers with glow
        if (points.isNotEmpty()) {
            val n = points.size
            val gridSide = max(1, kotlin.math.ceil(sqrt(n.toDouble())).toInt())
            points.forEachIndexed { i, p ->
                val col = i % gridSide
                val row = i / gridSide
                val x = left + (0.12f + (col / max(1f, gridSide - 1f)) * 0.76f) * mapW
                val y = top + (0.14f + (row / max(1f, gridSide - 1f)) * 0.72f).coerceIn(0.1f, 0.9f) * mapH
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        x, y, 18f,
                        intArrayOf(scoreSolid(p.anomalyScore) and 0x00FFFFFF or 0x88000000.toInt(), 0x00000000),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(x, y, 18f, glow)
                canvas.drawCircle(x, y, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = scoreSolid(p.anomalyScore)
                })
                canvas.drawCircle(x, y, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    color = 0xFFFFFFFF.toInt()
                })
            }
        }

        // Scale bar
        drawThermalScale(canvas, right + 18f, top, bottom)

        canvas.drawRoundRect(
            RectF(left, top, right, bottom), 4f, 4f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = 0x33FFFFFF
                strokeWidth = 2f
            }
        )
        return bmp
    }

    private fun drawThermalScale(canvas: Canvas, x: Float, top: Float, bottom: Float) {
        val h = bottom - top
        val steps = 64
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until steps) {
            val t = 1f - i / (steps - 1f)
            p.color = thermalColor(t * 100f)
            val y0 = top + i * (h / steps)
            canvas.drawRect(x, y0, x + 22f, y0 + h / steps + 0.5f, p)
        }
        canvas.drawRoundRect(
            RectF(x, top, x + 22f, bottom), 4f, 4f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = 0x66FFFFFF
                strokeWidth = 1.5f
            }
        )
        val lab = labelPaint(14f, false, 0xFFCBD5E1.toInt())
        canvas.drawText("100", x + 30f, top + 14f, lab)
        canvas.drawText("50", x + 30f, top + h / 2f, lab)
        canvas.drawText("0", x + 30f, bottom - 4f, lab)
        canvas.drawText("%", x + 30f, top + h / 2f + 18f, labelPaint(12f, false, 0xFF64748B.toInt()))
    }

    // ── Density ────────────────────────────────────────────────────────────

    fun densityBins(points: List<ReportPoint>, binCount: Int = 12): IntArray {
        val bins = IntArray(binCount)
        if (points.isEmpty()) return bins
        val width = 100.0 / binCount
        for (p in points) {
            val idx = min(binCount - 1, max(0, (p.anomalyScore / width).toInt()))
            bins[idx]++
        }
        return bins
    }

    private fun drawDensity(points: List<ReportPoint>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawCardBackground(canvas, w, h)

        canvas.drawText("SCORE DENSITY DISTRIBUTION", 36f, 48f, labelPaint(24f, true, 0xFF0F172A.toInt()))
        canvas.drawText(
            "Histogram + kernel density estimate across inspection points",
            36f, 76f, labelPaint(15f, false, 0xFF64748B.toInt())
        )

        val left = 72f
        val top = 108f
        val right = w - 40f
        val bottom = h - 72f
        val bins = densityBins(points, 12)
        val maxBin = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
        val barW = (right - left) / bins.size

        // Soft threshold washes
        fun wash(x0: Float, x1: Float, color: Int) {
            canvas.drawRect(x0, top, x1, bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
            })
        }
        wash(left, left + barW * 3.6f, 0x1415803D)
        wash(left + barW * 3.6f, left + barW * 8.4f, 0x14B45309)
        wash(left + barW * 8.4f, right, 0x14B91C1C)

        // Horizontal grid
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            strokeWidth = 1.5f
        }
        for (i in 0..4) {
            val y = top + i / 4f * (bottom - top)
            canvas.drawLine(left, y, right, y, grid)
        }

        bins.forEachIndexed { i, count ->
            val bh = (count / maxBin.toFloat()) * (bottom - top - 8f)
            val x0 = left + i * barW + 5f
            val x1 = left + (i + 1) * barW - 5f
            val y0 = bottom - bh
            val scoreMid = i * (100f / bins.size) + 50f / bins.size
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    x0, y0, x0, bottom,
                    thermalColor(scoreMid),
                    thermalColor(scoreMid) and 0x00FFFFFF or 0x55000000,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(RectF(x0, y0, x1, bottom), 8f, 8f, fill)
            if (count > 0) {
                canvas.drawText(
                    "$count",
                    x0 + (x1 - x0) / 2f - 6f,
                    y0 - 10f,
                    labelPaint(16f, true, 0xFF0F172A.toInt())
                )
            }
        }

        // Smooth KDE curve + fill
        if (points.isNotEmpty()) {
            val scores = points.map { it.anomalyScore.toFloat() }
            val samples = 80
            val hK = 10f
            val dens = FloatArray(samples)
            var peak = 0f
            for (i in 0 until samples) {
                val xScore = i / (samples - 1f) * 100f
                var d = 0f
                for (s in scores) {
                    val u = (xScore - s) / hK
                    d += exp(-0.5 * u * u).toFloat()
                }
                dens[i] = d
                if (d > peak) peak = d
            }
            val fillPath = Path()
            val strokePath = Path()
            fillPath.moveTo(left, bottom)
            for (i in 0 until samples) {
                val x = left + (i / (samples - 1f)) * (right - left)
                val ny = if (peak < 1e-6f) 0f else dens[i] / peak
                val y = bottom - ny * (bottom - top - 12f) * 0.88f
                if (i == 0) {
                    fillPath.lineTo(x, y)
                    strokePath.moveTo(x, y)
                } else {
                    fillPath.lineTo(x, y)
                    strokePath.lineTo(x, y)
                }
            }
            fillPath.lineTo(right, bottom)
            fillPath.close()
            canvas.drawPath(fillPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x330E7490
            })
            canvas.drawPath(strokePath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                color = 0xFF0E7490.toInt()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            })
        }

        val ax = labelPaint(14f, false, 0xFF64748B.toInt())
        canvas.drawText("0%", left, bottom + 28f, ax)
        canvas.drawText("50%", left + (right - left) / 2f - 16f, bottom + 28f, ax)
        canvas.drawText("100%", right - 48f, bottom + 28f, ax)
        canvas.drawText("n", 28f, top + 8f, ax)

        // Frame
        canvas.drawRoundRect(
            RectF(left, top, right, bottom), 6f, 6f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = 0xFFCBD5E1.toInt()
                strokeWidth = 2f
            }
        )
        return bmp
    }

    // ── Score bars ─────────────────────────────────────────────────────────

    private fun drawScoreBars(points: List<ReportPoint>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawCardBackground(canvas, w, h)

        canvas.drawText("POINT ANOMALY SCORES", 36f, 48f, labelPaint(24f, true, 0xFF0F172A.toInt()))
        canvas.drawText(
            "Per-point moisture deviation vs dry baseline  ·  dashed = classification thresholds",
            36f, 76f, labelPaint(15f, false, 0xFF64748B.toInt())
        )

        val left = 88f
        val top = 108f
        val right = w - 40f
        val bottom = h - 88f

        // Grid + threshold bands
        for (i in 0..4) {
            val y = bottom - i / 4f * (bottom - top)
            canvas.drawLine(left, y, right, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE2E8F0.toInt()
                strokeWidth = 1.5f
            })
            canvas.drawText(
                "${i * 25}",
                36f, y + 5f,
                labelPaint(13f, false, 0xFF94A3B8.toInt())
            )
        }
        fun thresh(score: Double, color: Int, label: String) {
            val y = bottom - (score / 100.0 * (bottom - top)).toFloat()
            canvas.drawLine(left, y, right, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = 2.5f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
            })
            canvas.drawText(label, right - 110f, y - 8f, labelPaint(13f, true, color))
        }
        thresh(AnomalyThresholds.GREEN_MAX, 0xFF15803D.toInt(), "NORMAL")
        thresh(AnomalyThresholds.YELLOW_MAX, 0xFFB91C1C.toInt(), "ANOMALY")

        val show = points.take(18)
        if (show.isEmpty()) {
            canvas.drawText("No points recorded", left + 20f, (top + bottom) / 2f,
                labelPaint(18f, false, 0xFF94A3B8.toInt()))
            return bmp
        }
        val gap = 10f
        val barW = ((right - left) / show.size) - gap
        show.forEachIndexed { i, p ->
            val bh = (p.anomalyScore / 100.0 * (bottom - top)).toFloat().coerceAtLeast(6f)
            val x0 = left + i * (barW + gap)
            val rect = RectF(x0, bottom - bh, x0 + barW, bottom)
            // Soft shadow
            canvas.drawRoundRect(
                RectF(rect.left + 3f, rect.top + 4f, rect.right + 3f, rect.bottom + 4f),
                10f, 10f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18000000 }
            )
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    x0, bottom - bh, x0, bottom,
                    lighten(scoreSolid(p.anomalyScore), 0.25f),
                    scoreSolid(p.anomalyScore),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, 10f, 10f, fill)
            canvas.drawText(
                "${p.anomalyScore.toInt()}",
                x0 + barW / 2f - 12f,
                bottom - bh - 12f,
                labelPaint(15f, true, 0xFF0F172A.toInt())
            )
            val lab = p.label.take(7)
            canvas.save()
            canvas.rotate(-38f, x0 + barW / 2f, bottom + 18f)
            canvas.drawText(lab, x0, bottom + 18f, labelPaint(13f, false, 0xFF475569.toInt()))
            canvas.restore()
        }
        return bmp
    }

    // ── Feature profile ────────────────────────────────────────────────────

    private fun drawFeatureProfile(points: List<ReportPoint>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawCardBackground(canvas, w, h)

        canvas.drawText("ACOUSTIC FEATURE TRAJECTORY", 36f, 48f, labelPaint(24f, true, 0xFF0F172A.toInt()))
        canvas.drawText(
            "Min–max normalised resonance, decay & centroid across the session sequence",
            36f, 76f, labelPaint(15f, false, 0xFF64748B.toInt())
        )

        val left = 56f
        val top = 118f
        val right = w - 40f
        val bottom = h - 80f

        for (i in 0..4) {
            val y = top + i / 4f * (bottom - top)
            canvas.drawLine(left, y, right, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE2E8F0.toInt()
                strokeWidth = 1.5f
            })
        }

        if (points.isEmpty()) {
            canvas.drawText("No features", left, (top + bottom) / 2f, labelPaint(16f, false, 0xFF94A3B8.toInt()))
            return bmp
        }

        fun series(values: List<Double>): List<Float> {
            val minV = values.minOrNull() ?: 0.0
            val maxV = values.maxOrNull() ?: 1.0
            val span = max(maxV - minV, 1e-6)
            return values.map { ((it - minV) / span).toFloat() }
        }

        val res = series(points.map { it.features.resonanceFreqHz })
        val decay = series(points.map { it.features.decayTimeMs })
        val cent = series(points.map { it.features.spectralCentroidHz })

        fun drawSeries(norm: List<Float>, color: Int, fillAlpha: Int) {
            if (norm.isEmpty()) return
            val stroke = Path()
            val fill = Path()
            norm.forEachIndexed { i, v ->
                val x = if (norm.size == 1) (left + right) / 2f
                else left + i / (norm.size - 1f) * (right - left)
                val y = bottom - v * (bottom - top)
                if (i == 0) {
                    stroke.moveTo(x, y)
                    fill.moveTo(x, bottom)
                    fill.lineTo(x, y)
                } else {
                    stroke.lineTo(x, y)
                    fill.lineTo(x, y)
                }
                canvas.drawCircle(x, y, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                })
                canvas.drawCircle(x, y, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    this.color = 0xFFFFFFFF.toInt()
                })
            }
            fill.lineTo(
                if (norm.size == 1) (left + right) / 2f
                else right,
                bottom
            )
            fill.close()
            canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = fillAlpha })
            canvas.drawPath(stroke, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4.5f
                this.color = color
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            })
        }

        drawSeries(res, 0xFF0E7490.toInt(), 0x220E7490)
        drawSeries(decay, 0xFFD97706.toInt(), 0x22D97706)
        drawSeries(cent, 0xFF7C3AED.toInt(), 0x227C3AED)

        fun legend(x: Float, label: String, color: Int) {
            canvas.drawRoundRect(
                RectF(x, h - 44f, x + 22f, h - 28f), 4f, 4f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            )
            canvas.drawText(label, x + 30f, h - 30f, labelPaint(15f, false, 0xFF334155.toInt()))
        }
        legend(36f, "Resonance", 0xFF0E7490.toInt())
        legend(220f, "Decay", 0xFFD97706.toInt())
        legend(360f, "Centroid", 0xFF7C3AED.toInt())

        canvas.drawRoundRect(
            RectF(left, top, right, bottom), 6f, 6f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = 0xFFCBD5E1.toInt()
                strokeWidth = 2f
            }
        )
        return bmp
    }

    // ── Status ring ────────────────────────────────────────────────────────

    private fun drawStatusRing(report: SessionReport, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawCardBackground(canvas, w, h)

        canvas.drawText("STATUS MIX", 28f, 42f, labelPaint(22f, true, 0xFF0F172A.toInt()))
        canvas.drawText("Share of point classifications", 28f, 68f,
            labelPaint(14f, false, 0xFF64748B.toInt()))

        val normal = report.points.count { it.anomalyScore < AnomalyThresholds.GREEN_MAX }
        val elevated = report.elevatedCount
        val anomaly = report.anomalyCount
        val total = max(1, report.points.size)

        val cx = w / 2f
        val cy = h / 2f + 10f
        val rOuter = min(w, h) * 0.32f
        val ring = RectF(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter)
        val sweepN = 360f * normal / total
        val sweepE = 360f * elevated / total
        val sweepA = 360f * anomaly / total

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 36f
            strokeCap = Paint.Cap.BUTT
        }
        var start = -90f
        fun arc(sweep: Float, color: Int) {
            if (sweep <= 0.1f) return
            paint.color = color
            canvas.drawArc(ring, start, sweep - 1.5f, false, paint)
            start += sweep
        }
        arc(sweepN, 0xFF15803D.toInt())
        arc(sweepE, 0xFFD97706.toInt())
        arc(sweepA, 0xFFB91C1C.toInt())

        // Inner disc
        canvas.drawCircle(cx, cy, rOuter - 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
        })
        canvas.drawText(
            "${report.points.size}",
            cx - 18f, cy - 4f,
            labelPaint(36f, true, 0xFF0F172A.toInt())
        )
        canvas.drawText("points", cx - 28f, cy + 24f, labelPaint(14f, false, 0xFF64748B.toInt()))

        fun chip(x: Float, y: Float, color: Int, label: String, n: Int) {
            canvas.drawCircle(x, y, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            canvas.drawText("$label  $n", x + 14f, y + 5f, labelPaint(14f, false, 0xFF334155.toInt()))
        }
        val ly = h - 48f
        chip(28f, ly, 0xFF15803D.toInt(), "Normal", normal)
        chip(170f, ly, 0xFFD97706.toInt(), "Elevated", elevated)
        chip(330f, ly, 0xFFB91C1C.toInt(), "Anomaly", anomaly)
        return bmp
    }

    // ── Radar ──────────────────────────────────────────────────────────────

    private fun drawRadar(points: List<ReportPoint>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawCardBackground(canvas, w, h)

        canvas.drawText("FEATURE RADAR", 28f, 42f, labelPaint(22f, true, 0xFF0F172A.toInt()))
        canvas.drawText("Session-mean acoustic signature (normalised)", 28f, 68f,
            labelPaint(14f, false, 0xFF64748B.toInt()))

        val labels = listOf("Resonance", "Decay", "Centroid", "Spread", "Flatness")
        val cx = w / 2f
        val cy = h / 2f + 16f
        val radius = min(w, h) * 0.30f

        val values = if (points.isEmpty()) {
            List(5) { 0.2f }
        } else {
            fun absNorm(sel: (ReportPoint) -> Double, lo: Double, hi: Double): Float {
                val m = points.map(sel).average()
                return ((m - lo) / (hi - lo)).toFloat().coerceIn(0.08f, 1f)
            }
            listOf(
                absNorm({ it.features.resonanceFreqHz }, 800.0, 5000.0),
                absNorm({ it.features.decayTimeMs }, 5.0, 80.0),
                absNorm({ it.features.spectralCentroidHz }, 1000.0, 8000.0),
                absNorm({ it.features.spectralSpreadHz }, 200.0, 4000.0),
                absNorm({ it.features.spectralFlatness }, 0.05, 0.9)
            )
        }

        // Web rings
        val web = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFCBD5E1.toInt()
            strokeWidth = 1.8f
        }
        for (ring in 1..4) {
            val rr = radius * ring / 4f
            val path = Path()
            for (i in labels.indices) {
                val a = Math.toRadians((-90 + i * 72).toDouble())
                val x = cx + rr * cos(a).toFloat()
                val y = cy + rr * sin(a).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, web)
        }
        // Axes
        for (i in labels.indices) {
            val a = Math.toRadians((-90 + i * 72).toDouble())
            canvas.drawLine(
                cx, cy,
                cx + radius * cos(a).toFloat(),
                cy + radius * sin(a).toFloat(),
                web
            )
            val lx = cx + (radius + 28f) * cos(a).toFloat()
            val ly = cy + (radius + 28f) * sin(a).toFloat()
            canvas.drawText(
                labels[i],
                lx - 28f, ly + 5f,
                labelPaint(12f, false, 0xFF475569.toInt())
            )
        }

        // Data polygon
        val data = Path()
        values.forEachIndexed { i, v ->
            val a = Math.toRadians((-90 + i * 72).toDouble())
            val x = cx + radius * v * cos(a).toFloat()
            val y = cy + radius * v * sin(a).toFloat()
            if (i == 0) data.moveTo(x, y) else data.lineTo(x, y)
        }
        data.close()
        canvas.drawPath(data, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x330E7490 })
        canvas.drawPath(data, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = 0xFF0E7490.toInt()
            strokeJoin = Paint.Join.ROUND
        })
        values.forEachIndexed { i, v ->
            val a = Math.toRadians((-90 + i * 72).toDouble())
            val x = cx + radius * v * cos(a).toFloat()
            val y = cy + radius * v * sin(a).toFloat()
            canvas.drawCircle(x, y, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF0E7490.toInt()
            })
            canvas.drawCircle(x, y, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = 0xFFFFFFFF.toInt()
            })
        }
        return bmp
    }

    // ── Shared chrome ──────────────────────────────────────────────────────

    private fun drawCardBackground(canvas: Canvas, w: Int, h: Int) {
        // Soft shadow
        canvas.drawRoundRect(
            RectF(6f, 8f, w - 2f, h - 2f), 28f, 28f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14000000 }
        )
        canvas.drawRoundRect(
            RectF(0f, 0f, w - 8f, h - 8f), 28f, 28f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        )
        canvas.drawRoundRect(
            RectF(0f, 0f, w - 8f, h - 8f), 28f, 28f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = 0xFFE2E8F0.toInt()
                strokeWidth = 2f
            }
        )
        // Top accent strip
        canvas.drawRoundRect(
            RectF(0f, 0f, w - 8f, 10f), 28f, 28f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E7490.toInt() }
        )
        canvas.drawRect(0f, 6f, w - 8f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0E7490.toInt()
        })
    }

    fun thermalColor(score: Float): Int {
        val t = (score / 100f).coerceIn(0f, 1f)
        val stops = listOf(
            floatArrayOf(0.04f, 0.08f, 0.22f),
            floatArrayOf(0.06f, 0.35f, 0.65f),
            floatArrayOf(0.08f, 0.72f, 0.55f),
            floatArrayOf(0.55f, 0.88f, 0.18f),
            floatArrayOf(0.98f, 0.72f, 0.08f),
            floatArrayOf(0.92f, 0.18f, 0.08f),
            floatArrayOf(0.55f, 0.02f, 0.08f)
        )
        val idx = t * (stops.size - 1)
        val i0 = idx.toInt().coerceIn(0, stops.size - 2)
        val f = idx - i0
        val a = stops[i0]
        val b = stops[i0 + 1]
        val r = ((a[0] + (b[0] - a[0]) * f) * 255).roundToInt().coerceIn(0, 255)
        val g = ((a[1] + (b[1] - a[1]) * f) * 255).roundToInt().coerceIn(0, 255)
        val bl = ((a[2] + (b[2] - a[2]) * f) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun scoreSolid(score: Double): Int = when {
        score < AnomalyThresholds.GREEN_MAX -> 0xFF15803D.toInt()
        score < AnomalyThresholds.YELLOW_MAX -> 0xFFD97706.toInt()
        else -> 0xFFB91C1C.toInt()
    }

    private fun lighten(color: Int, amount: Float): Int {
        val r = ((color shr 16) and 0xFF)
        val g = ((color shr 8) and 0xFF)
        val b = color and 0xFF
        val nr = (r + (255 - r) * amount).roundToInt().coerceIn(0, 255)
        val ng = (g + (255 - g) * amount).roundToInt().coerceIn(0, 255)
        val nb = (b + (255 - b) * amount).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    private fun labelPaint(size: Float, bold: Boolean, color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(
                Typeface.SANS_SERIF,
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
        }
}
