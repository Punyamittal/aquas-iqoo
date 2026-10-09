package com.aquascope.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aquascope.R
import com.aquascope.baseline.AnomalyThresholds
import com.aquascope.databinding.ActivityReportBinding
import com.aquascope.report.IndianComplianceGuidelines
import com.aquascope.report.ReportGenerator
import com.aquascope.report.ReportPdfExporter
import com.aquascope.report.SessionReport
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportBinding
    private lateinit var report: SessionReport
    private var pdfFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val json = intent.getStringExtra(EXTRA_REPORT_JSON)
        if (json.isNullOrBlank()) {
            Toast.makeText(this, "No report data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        report = try {
            Gson().fromJson(json, SessionReport::class.java)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open report", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindReport(report)
        binding.btnDownloadPdf.setOnClickListener { downloadPdf(openAfter = true) }
        binding.btnShareReport.setOnClickListener { sharePdfOrText() }
        binding.btnCloseReport.setOnClickListener { finish() }

        // Generate the complete PDF as soon as the session report opens.
        downloadPdf(openAfter = false, silent = true)
    }

    private fun bindReport(report: SessionReport) {
        val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
        val band = IndianComplianceGuidelines.riskBand(report)
        binding.textReportLocation.text = report.locationLabel
        binding.textReportTime.text = fmt.format(Date(report.generatedAtMs))
        binding.textRiskBand.text = when (band) {
            IndianComplianceGuidelines.RiskBand.NORMAL -> "Low risk. Keep monitoring as usual."
            IndianComplianceGuidelines.RiskBand.ELEVATED -> "Moderate risk. Document it and notify the owner."
            IndianComplianceGuidelines.RiskBand.ANOMALY -> "High risk. Give notice and repair promptly."
        }
        binding.textRiskBand.setTextColor(ContextCompat.getColor(this, colorForBand(band)))
        binding.textAssessment.text = IndianComplianceGuidelines.executiveSummary(report)
        binding.textMaxScore.text = "${report.maxScore.toInt()}%"
        binding.textAvgScore.text = "${report.avgScore.toInt()}%"
        binding.textPointCount.text = report.points.size.toString()

        binding.textMaxScore.setTextColor(
            ContextCompat.getColor(this, colorForScore(report.maxScore))
        )

        binding.layoutPointRows.removeAllViews()
        for (point in report.points) {
            val row = layoutInflater.inflate(R.layout.item_report_point, binding.layoutPointRows, false)
            val stripe = row.findViewById<android.view.View>(R.id.pointStripe)
            val label = row.findViewById<android.widget.TextView>(R.id.textPointLabel)
            val features = row.findViewById<android.widget.TextView>(R.id.textPointFeatures)
            val score = row.findViewById<android.widget.TextView>(R.id.textPointScore)
            val status = row.findViewById<android.widget.TextView>(R.id.textPointStatus)

            val color = ContextCompat.getColor(this, colorForScore(point.anomalyScore))
            label.text = point.label
            features.text =
                "Resonance ${point.features.resonanceFreqHz.toInt()} Hz, " +
                    "decay ${"%.0f".format(point.features.decayTimeMs)} ms, " +
                    "centroid ${point.features.spectralCentroidHz.toInt()} Hz"
            score.text = "${point.anomalyScore.toInt()}%"
            status.text = ReportGenerator.statusLabel(point.anomalyScore)
                .lowercase(Locale.ROOT)
                .replaceFirstChar { it.uppercase() }
            score.setTextColor(color)
            status.setTextColor(color)
            stripe.setBackgroundColor(color)
            binding.layoutPointRows.addView(row)
        }

        binding.layoutGuidelineRows.removeAllViews()
        IndianComplianceGuidelines.actionGuidelines(report).forEachIndexed { index, guide ->
            val tv = android.widget.TextView(this).apply {
                val pad = (8 * resources.displayMetrics.density).toInt()
                text = "${index + 1}. $guide"
                setTextColor(ContextCompat.getColor(this@ReportActivity, R.color.ink))
                textSize = 15f
                setLineSpacing(0f, 1.3f)
                setPadding(0, pad, 0, pad)
            }
            binding.layoutGuidelineRows.addView(tv)
        }
    }

    private fun downloadPdf(openAfter: Boolean, silent: Boolean = false) {
        binding.btnDownloadPdf.isEnabled = false
        if (!silent) {
            binding.btnDownloadPdf.text = getString(R.string.report_pdf_generating)
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ReportPdfExporter.export(this@ReportActivity, report) }
            }
            binding.btnDownloadPdf.isEnabled = true
            binding.btnDownloadPdf.text = getString(R.string.report_download_pdf)
            result.onSuccess { export ->
                pdfFile = export.cacheFile
                if (!silent) {
                    val where = if (export.downloadsUri != null) {
                        getString(R.string.report_pdf_saved_downloads, export.displayName)
                    } else {
                        getString(R.string.report_pdf_saved_app, export.displayName)
                    }
                    Toast.makeText(this@ReportActivity, where, Toast.LENGTH_LONG).show()
                }
                if (openAfter) {
                    runCatching {
                        startActivity(ReportPdfExporter.viewIntent(this@ReportActivity, export.cacheFile))
                    }.onFailure {
                        Toast.makeText(
                            this@ReportActivity,
                            R.string.report_pdf_no_viewer,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }.onFailure {
                Toast.makeText(
                    this@ReportActivity,
                    getString(R.string.report_pdf_failed, it.message ?: "error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun sharePdfOrText() {
        val existing = pdfFile
        if (existing != null && existing.exists()) {
            startActivity(
                Intent.createChooser(
                    ReportPdfExporter.shareIntent(this, existing, report),
                    getString(R.string.share_report)
                )
            )
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ReportPdfExporter.export(this@ReportActivity, report) }
            }
            result.onSuccess { export ->
                pdfFile = export.cacheFile
                startActivity(
                    Intent.createChooser(
                        ReportPdfExporter.shareIntent(this@ReportActivity, export.cacheFile, report),
                        getString(R.string.share_report)
                    )
                )
            }.onFailure {
                // Fallback to plain text if PDF generation fails.
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "AquaScope report — ${report.locationLabel}")
                    putExtra(Intent.EXTRA_TEXT, ReportGenerator.toShareText(report))
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share_report)))
            }
        }
    }

    private fun colorForScore(score: Double): Int = when {
        score < AnomalyThresholds.GREEN_MAX -> R.color.status_ok
        score < AnomalyThresholds.YELLOW_MAX -> R.color.status_warn
        else -> R.color.status_alert
    }

    private fun colorForBand(band: IndianComplianceGuidelines.RiskBand): Int = when (band) {
        IndianComplianceGuidelines.RiskBand.NORMAL -> R.color.status_ok
        IndianComplianceGuidelines.RiskBand.ELEVATED -> R.color.status_warn
        IndianComplianceGuidelines.RiskBand.ANOMALY -> R.color.status_alert
    }

    companion object {
        const val EXTRA_REPORT_JSON = "report_json"

        fun reportToJson(report: SessionReport): String = Gson().toJson(report)
    }
}
