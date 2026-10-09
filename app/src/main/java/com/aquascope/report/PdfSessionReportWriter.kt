package com.aquascope.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.aquascope.baseline.AnomalyThresholds
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Premium multi-page A4 PDF for AquaScope acoustic moisture inspection sessions.
 */
object PdfSessionReportWriter {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 36f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN
    private const val FOOTER_Y = PAGE_H - 48f

    private val ink = 0xFF0B1220.toInt()
    private val muted = 0xFF64748B.toInt()
    private val faint = 0xFF94A3B8.toInt()
    private val line = 0xFFE2E8F0.toInt()
    private val brand = 0xFF0E7490.toInt()
    private val brandDeep = 0xFF083344.toInt()
    private val brandSoft = 0xFFECFEFF.toInt()
    private val ok = 0xFF15803D.toInt()
    private val warn = 0xFFD97706.toInt()
    private val alert = 0xFFB91C1C.toInt()
    private val panel = 0xFFF8FAFC.toInt()
    private val white = 0xFFFFFFFF.toInt()

    fun write(report: SessionReport, outFile: File): File {
        outFile.parentFile?.mkdirs()
        val doc = PdfDocument()
        var state = newPage(doc, 1, hero = true)

        state = drawHeroCover(state, doc, report)
        state = drawSummary(state, doc, report)
        state = drawPointTable(state, doc, report)
        state = drawCharts(state, doc, report)
        state = drawGuidelines(state, doc, report)
        drawFrameworkAndDisclaimer(state, doc, report)

        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        return outFile
    }

    private data class PageState(
        val page: PdfDocument.Page,
        val canvas: Canvas,
        var y: Float,
        val number: Int,
        val doc: PdfDocument
    )

    private fun newPage(doc: PdfDocument, number: Int, hero: Boolean = false): PageState {
        val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create()
        val page = doc.startPage(info)
        val canvas = page.canvas
        canvas.drawColor(white)
        if (!hero) drawHeaderBar(canvas)
        // Subtle left rail
        canvas.drawRect(0f, 0f, 6f, PAGE_H.toFloat(), Paint().apply { color = brand })
        return PageState(page, canvas, if (hero) 0f else MARGIN + 40f, number, doc)
    }

    private fun finish(state: PageState): PageState {
        drawFooter(state.canvas, state.number)
        state.doc.finishPage(state.page)
        return state
    }

    private fun ensureSpace(state: PageState, needed: Float, doc: PdfDocument): PageState {
        if (state.y + needed <= FOOTER_Y - 8f) return state
        finish(state)
        return newPage(doc, state.number + 1)
    }

    private fun drawHeaderBar(canvas: Canvas) {
        canvas.drawRect(
            0f, 0f, PAGE_W.toFloat(), 34f,
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, PAGE_W.toFloat(), 0f,
                    brandDeep, brand, Shader.TileMode.CLAMP
                )
            }
        )
        canvas.drawText(
            "AQUASCOPE",
            MARGIN, 22f,
            paint(11f, white, true).apply { letterSpacing = 0.12f }
        )
        val right = "Acoustic Moisture Screening · India"
        val rp = paint(8.5f, 0xFFA5F3FC.toInt())
        canvas.drawText(right, PAGE_W - MARGIN - rp.measureText(right), 22f, rp)
    }

    private fun drawFooter(canvas: Canvas, pageNo: Int) {
        canvas.drawLine(MARGIN, PAGE_H - 36f, PAGE_W - MARGIN, PAGE_H - 36f, Paint().apply {
            color = line
            strokeWidth = 0.8f
        })
        val p = paint(7.5f, faint)
        canvas.drawText(
            "Confidential screening record · Not a certified structural certificate",
            MARGIN,
            PAGE_H - 20f,
            p
        )
        val page = "Page $pageNo"
        canvas.drawText(page, PAGE_W - MARGIN - p.measureText(page), PAGE_H - 20f, p)
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(
                Typeface.SANS_SERIF,
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
        }

    private fun drawHeroCover(state: PageState, doc: PdfDocument, report: SessionReport): PageState {
        var s = state
        val fmt = SimpleDateFormat("dd MMMM yyyy · HH:mm", Locale.ENGLISH)
        val band = IndianComplianceGuidelines.riskBand(report)
        val bandColor = when (band) {
            IndianComplianceGuidelines.RiskBand.NORMAL -> ok
            IndianComplianceGuidelines.RiskBand.ELEVATED -> warn
            IndianComplianceGuidelines.RiskBand.ANOMALY -> alert
        }

        // Hero band
        s.canvas.drawRect(
            0f, 0f, PAGE_W.toFloat(), 168f,
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, PAGE_W.toFloat(), 168f,
                    brandDeep, 0xFF155E75.toInt(), Shader.TileMode.CLAMP
                )
            }
        )
        // Decorative circles
        s.canvas.drawCircle(520f, 40f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x18FFFFFF
        })
        s.canvas.drawCircle(560f, 120f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x12FFFFFF
        })

        s.canvas.drawText(
            "AQUASCOPE",
            MARGIN, 48f,
            paint(12f, 0xFFA5F3FC.toInt(), true).apply { letterSpacing = 0.18f }
        )
        s.canvas.drawText("Session Inspection Report", MARGIN, 88f, paint(22f, white, true))
        s.canvas.drawText(report.locationLabel, MARGIN, 118f, paint(14f, 0xFFE0F2FE.toInt(), true))
        s.canvas.drawText(
            "Generated ${fmt.format(Date(report.generatedAtMs))}",
            MARGIN, 142f,
            paint(10f, 0xFFBAE6FD.toInt())
        )
        s.y = 188f

        // Risk badge
        val badge = IndianComplianceGuidelines.riskTitle(band)
        val bp = paint(9.5f, white, true)
        val tw = bp.measureText(badge) + 28f
        s.canvas.drawRoundRect(
            RectF(MARGIN, s.y, MARGIN + tw, s.y + 26f),
            13f, 13f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bandColor }
        )
        s.canvas.drawText(badge, MARGIN + 14f, s.y + 17.5f, bp)
        s.y += 42f

        // KPI cards (4)
        s = ensureSpace(s, 78f, doc)
        val gap = 8f
        val boxW = (CONTENT_W - 3 * gap) / 4f
        val boxH = 68f
        val kpis = listOf(
            Triple("POINTS", report.points.size.toString(), ink),
            Triple("MAX", "${report.maxScore.toInt()}%", colorForScore(report.maxScore)),
            Triple("AVERAGE", "${"%.0f".format(report.avgScore)}%", colorForScore(report.avgScore)),
            Triple("FLAGS", "${report.anomalyCount + report.elevatedCount}", if (report.anomalyCount > 0) alert else if (report.elevatedCount > 0) warn else ok)
        )
        kpis.forEachIndexed { i, (label, value, vc) ->
            val left = MARGIN + i * (boxW + gap)
            // shadow
            s.canvas.drawRoundRect(
                RectF(left + 1.5f, s.y + 2f, left + boxW + 1.5f, s.y + boxH + 2f),
                10f, 10f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14000000 }
            )
            s.canvas.drawRoundRect(
                RectF(left, s.y, left + boxW, s.y + boxH),
                10f, 10f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = panel }
            )
            s.canvas.drawRoundRect(
                RectF(left, s.y, left + 4f, s.y + boxH),
                10f, 10f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = vc }
            )
            s.canvas.drawText(label, left + 14f, s.y + 22f, paint(7.5f, faint, true))
            s.canvas.drawText(value, left + 14f, s.y + 48f, paint(18f, vc, true))
        }
        s.y += boxH + 22f
        return s
    }

    private fun drawSummary(state: PageState, doc: PdfDocument, report: SessionReport): PageState {
        var s = ensureSpace(state, 110f, doc)
        s = sectionTitle(s, "01", "Executive assessment")

        // Quote panel
        val summary = IndianComplianceGuidelines.executiveSummary(report)
        val body = paint(10f, ink)
        val lines = wrap(summary, body, CONTENT_W - 28f)
        val panelH = lines.size * (body.textSize + 4f) + 24f
        s = ensureSpace(s, panelH + 20f, doc)
        s.canvas.drawRoundRect(
            RectF(MARGIN, s.y, PAGE_W - MARGIN, s.y + panelH),
            10f, 10f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brandSoft }
        )
        s.canvas.drawRect(
            MARGIN, s.y, MARGIN + 5f, s.y + panelH,
            Paint().apply { color = brand }
        )
        var ty = s.y + 18f
        for (line in lines) {
            s.canvas.drawText(line, MARGIN + 16f, ty, body)
            ty += body.textSize + 4f
        }
        s.y += panelH + 12f

        s = drawParagraph(
            s, doc,
            "Classification: NORMAL < ${AnomalyThresholds.GREEN_MAX.toInt()}%  ·  " +
                "ELEVATED < ${AnomalyThresholds.YELLOW_MAX.toInt()}%  ·  " +
                "ANOMALY ≥ ${AnomalyThresholds.YELLOW_MAX.toInt()}%.",
            paint(9f, muted)
        )
        s.y += 16f
        return s
    }

    private fun drawPointTable(state: PageState, doc: PdfDocument, report: SessionReport): PageState {
        var s = ensureSpace(state, 80f, doc)
        s = sectionTitle(s, "02", "Point-wise findings")

        s = ensureSpace(s, 28f, doc)
        s.canvas.drawRoundRect(
            RectF(MARGIN, s.y, PAGE_W - MARGIN, s.y + 24f),
            6f, 6f,
            Paint().apply { color = brandDeep }
        )
        val h = paint(8f, white, true)
        s.canvas.drawText("#", MARGIN + 8f, s.y + 16f, h)
        s.canvas.drawText("POINT", MARGIN + 30f, s.y + 16f, h)
        s.canvas.drawText("SCORE", MARGIN + 100f, s.y + 16f, h)
        s.canvas.drawText("STATUS", MARGIN + 148f, s.y + 16f, h)
        s.canvas.drawText("RESONANCE", MARGIN + 218f, s.y + 16f, h)
        s.canvas.drawText("DECAY", MARGIN + 300f, s.y + 16f, h)
        s.canvas.drawText("CENTROID", MARGIN + 360f, s.y + 16f, h)
        s.canvas.drawText("TIME", MARGIN + 450f, s.y + 16f, h)
        s.y += 28f

        val fmt = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)
        val body = paint(8.5f, ink)
        report.points.forEachIndexed { index, p ->
            s = ensureSpace(s, 22f, doc)
            if (index % 2 == 1) {
                s.canvas.drawRect(
                    MARGIN, s.y - 5f, PAGE_W - MARGIN, s.y + 15f,
                    Paint().apply { color = panel }
                )
            }
            // Score pill
            val sc = colorForScore(p.anomalyScore)
            val scoreText = "${p.anomalyScore.toInt()}%"
            val sp = paint(8f, white, true)
            val sw = sp.measureText(scoreText) + 12f
            s.canvas.drawRoundRect(
                RectF(MARGIN + 100f, s.y - 2f, MARGIN + 100f + sw, s.y + 12f),
                6f, 6f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sc }
            )
            s.canvas.drawText(scoreText, MARGIN + 106f, s.y + 8f, sp)

            s.canvas.drawText("${index + 1}", MARGIN + 8f, s.y + 8f, body)
            s.canvas.drawText(p.label.take(8), MARGIN + 30f, s.y + 8f, body)
            s.canvas.drawText(
                ReportGenerator.statusLabel(p.anomalyScore),
                MARGIN + 148f, s.y + 8f, paint(8.5f, sc, true)
            )
            s.canvas.drawText("${p.features.resonanceFreqHz.toInt()} Hz", MARGIN + 218f, s.y + 8f, body)
            s.canvas.drawText("${"%.0f".format(p.features.decayTimeMs)} ms", MARGIN + 300f, s.y + 8f, body)
            s.canvas.drawText("${p.features.spectralCentroidHz.toInt()} Hz", MARGIN + 360f, s.y + 8f, body)
            s.canvas.drawText(fmt.format(Date(p.timestampMs)), MARGIN + 450f, s.y + 8f, paint(8.5f, muted))
            s.y += 20f
        }
        if (report.notes.isNotBlank()) {
            s.y += 10f
            s = sectionTitle(s, "••", "Session notes")
            s = drawParagraph(s, doc, report.notes, paint(10f, ink))
        }
        s.y += 14f
        return s
    }

    private fun drawCharts(state: PageState, doc: PdfDocument, report: SessionReport): PageState {
        var s = ensureSpace(state, 40f, doc)
        // Prefer starting charts on a fresh page when near the bottom
        if (s.y > 120f) {
            finish(s)
            s = newPage(doc, s.number + 1)
        }
        s = sectionTitle(s, "03", "Visual analysis")
        s = drawParagraph(
            s, doc,
            "All figures are generated from this session’s acoustic scores and spectral features. " +
                "The thermal-style field is a Gaussian-interpolated moisture-risk surface — not an IR camera capture.",
            paint(9f, muted)
        )
        s.y += 12f

        val charts = ReportChartRenderer.generate(report)

        fun fullWidth(caption: String, bmp: Bitmap, targetH: Float) {
            var st = ensureSpace(s, targetH + 36f, doc)
            st.canvas.drawText(caption, MARGIN, st.y, paint(9f, brand, true))
            st.y += 10f
            val dest = RectF(MARGIN, st.y, MARGIN + CONTENT_W, st.y + targetH)
            st.canvas.drawBitmap(bmp, null, dest, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
            })
            st.y += targetH + 18f
            bmp.recycle()
            s = st
        }

        fun pair(captionL: String, leftBmp: Bitmap, captionR: String, rightBmp: Bitmap, targetH: Float) {
            var st = ensureSpace(s, targetH + 40f, doc)
            val gap = 10f
            val half = (CONTENT_W - gap) / 2f
            st.canvas.drawText(captionL, MARGIN, st.y, paint(8.5f, brand, true))
            st.canvas.drawText(captionR, MARGIN + half + gap, st.y, paint(8.5f, brand, true))
            st.y += 10f
            st.canvas.drawBitmap(
                leftBmp, null,
                RectF(MARGIN, st.y, MARGIN + half, st.y + targetH),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
            )
            st.canvas.drawBitmap(
                rightBmp, null,
                RectF(MARGIN + half + gap, st.y, MARGIN + CONTENT_W, st.y + targetH),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
            )
            st.y += targetH + 18f
            leftBmp.recycle()
            rightBmp.recycle()
            s = st
        }

        fullWidth("Fig A · Moisture risk field (thermal-style)", charts.thermalMap, 198f)
        fullWidth("Fig B · Anomaly score density", charts.density, 162f)
        fullWidth("Fig C · Point anomaly scores", charts.scoreBars, 172f)
        fullWidth("Fig D · Acoustic feature trajectory", charts.featureProfile, 172f)
        pair(
            "Fig E · Status mix", charts.statusRing,
            "Fig F · Feature radar", charts.radar,
            168f
        )
        s.y += 4f
        return s
    }

    private fun drawGuidelines(state: PageState, doc: PdfDocument, report: SessionReport): PageState {
        var s = ensureSpace(state, 60f, doc)
        if (s.y > 200f) {
            finish(s)
            s = newPage(doc, s.number + 1)
        }
        s = sectionTitle(s, "04", "Recommended actions & Indian legal guidelines")
        s = drawParagraph(
            s, doc,
            "Guidance is matched to this session’s overall risk band (" +
                IndianComplianceGuidelines.riskTitle(IndianComplianceGuidelines.riskBand(report)) +
                ").",
            paint(9f, muted)
        )
        s.y += 8f
        IndianComplianceGuidelines.actionGuidelines(report).forEachIndexed { i, bullet ->
            s = drawBullet(s, doc, "${i + 1}.", bullet)
        }
        s.y += 12f
        return s
    }

    private fun drawFrameworkAndDisclaimer(state: PageState, doc: PdfDocument, report: SessionReport): PageState {
        var s = ensureSpace(state, 80f, doc)
        s = sectionTitle(s, "05", "Legal & standards framework referenced")
        IndianComplianceGuidelines.legalFrameworkNotes().forEach { note ->
            s = drawBullet(s, doc, "•", note)
        }
        s.y += 14f
        s = ensureSpace(s, 100f, doc)
        s = sectionTitle(s, "06", "Disclaimer")
        s = drawParagraph(s, doc, IndianComplianceGuidelines.disclaimer(), paint(8.5f, muted))
        s.y += 16f
        s = drawParagraph(
            s, doc,
            "Report location: ${report.locationLabel} · Points: ${report.points.size} · " +
                "Anomaly: ${report.anomalyCount} · Elevated: ${report.elevatedCount}",
            paint(8f, faint)
        )
        return finish(s)
    }

    private fun sectionTitle(state: PageState, num: String, title: String): PageState {
        val s = ensureSpace(state, 36f, state.doc)
        // Number pill
        val np = paint(9f, white, true)
        val nw = maxOf(22f, np.measureText(num) + 12f)
        s.canvas.drawRoundRect(
            RectF(MARGIN, s.y - 12f, MARGIN + nw, s.y + 6f),
            9f, 9f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brand }
        )
        s.canvas.drawText(num, MARGIN + 6f, s.y + 1f, np)
        s.canvas.drawText(
            title.uppercase(Locale.ENGLISH),
            MARGIN + nw + 10f,
            s.y,
            paint(11f, ink, true)
        )
        s.y += 8f
        s.canvas.drawLine(
            MARGIN, s.y, PAGE_W - MARGIN, s.y,
            Paint().apply {
                color = line
                strokeWidth = 1f
            }
        )
        s.y += 16f
        return s
    }

    private fun drawParagraph(state: PageState, doc: PdfDocument, text: String, paint: Paint): PageState {
        var s = state
        val lines = wrap(text, paint, CONTENT_W)
        for (line in lines) {
            s = ensureSpace(s, paint.textSize + 6f, doc)
            s.canvas.drawText(line, MARGIN, s.y, paint)
            s.y += paint.textSize + 4f
        }
        return s
    }

    private fun drawBullet(state: PageState, doc: PdfDocument, marker: String, text: String): PageState {
        var s = state
        val body = paint(9.5f, ink)
        val markerPaint = paint(9.5f, brand, true)
        val indent = 20f
        val lines = wrap(text, body, CONTENT_W - indent)
        s = ensureSpace(s, body.textSize + 8f, doc)
        s.canvas.drawText(marker, MARGIN, s.y, markerPaint)
        lines.forEachIndexed { idx, line ->
            if (idx > 0) s = ensureSpace(s, body.textSize + 6f, doc)
            s.canvas.drawText(line, MARGIN + indent, s.y, body)
            s.y += body.textSize + 3.5f
        }
        s.y += 7f
        return s
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (w in words) {
            val trial = if (current.isEmpty()) w else "$current $w"
            if (paint.measureText(trial) <= maxWidth) {
                current = StringBuilder(trial)
            } else {
                if (current.isNotEmpty()) lines += current.toString()
                current = StringBuilder(w)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private fun colorForScore(score: Double): Int = when {
        score < AnomalyThresholds.GREEN_MAX -> ok
        score < AnomalyThresholds.YELLOW_MAX -> warn
        else -> alert
    }
}
