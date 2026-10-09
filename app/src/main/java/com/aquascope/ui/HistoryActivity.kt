package com.aquascope.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aquascope.R
import com.aquascope.baseline.AnomalyThresholds
import com.aquascope.data.ScanRecord
import com.aquascope.data.ScanRepository
import com.aquascope.databinding.ActivityHistoryBinding
import com.aquascope.databinding.ItemHistoryBinding
import com.aquascope.report.ReportPoint
import com.aquascope.report.SessionReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repo = ScanRepository(this)
        val locationId = intent.getStringExtra("location_id") ?: run { finish(); return }
        val location = repo.getLocation(locationId)
        val records = location?.scanHistory?.sortedByDescending { it.timestamp }.orEmpty()

        binding.textTitle.text = location?.label ?: "History"
        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = HistoryAdapter(records)

        val empty = records.isEmpty()
        binding.textEmptyHistory.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerHistory.visibility = if (empty) View.GONE else View.VISIBLE
        binding.btnGenerateReport.visibility = if (empty) View.GONE else View.VISIBLE

        binding.btnGenerateReport.setOnClickListener {
            if (location == null || records.isEmpty()) {
                Toast.makeText(this, "No scans to report", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Chronological order for the report (oldest → newest), capped for readability
            val points = records
                .sortedBy { it.timestamp }
                .takeLast(20)
                .mapIndexed { index, rec ->
                    ReportPoint(
                        label = "Scan ${index + 1}",
                        anomalyScore = rec.anomalyScore,
                        features = rec.features.toAcousticFeatures(),
                        timestampMs = rec.timestamp
                    )
                }
            val report = SessionReport(
                locationLabel = location.label,
                points = points,
                notes = "Built from saved scan history (up to 20 most recent)."
            )
            startActivity(Intent(this, ReportActivity::class.java).apply {
                putExtra(ReportActivity.EXTRA_REPORT_JSON, ReportActivity.reportToJson(report))
            })
        }
    }
}

class HistoryAdapter(private val records: List<ScanRecord>) :
    RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())

    inner class VH(val b: ItemHistoryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = records.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rec = records[position]
        val ctx = holder.itemView.context
        holder.b.textTimestamp.text = fmt.format(Date(rec.timestamp))
        holder.b.textHistoryScore.text = "${rec.anomalyScore.toInt()}%"

        val colorRes = when {
            rec.anomalyScore < AnomalyThresholds.GREEN_MAX -> R.color.status_ok
            rec.anomalyScore < AnomalyThresholds.YELLOW_MAX -> R.color.status_warn
            else -> R.color.status_alert
        }
        val color = ContextCompat.getColor(ctx, colorRes)
        holder.b.textHistoryScore.setTextColor(color)
        holder.b.statusStripe.setBackgroundColor(color)

        holder.b.textFeatureDetails.text =
            "Resonance ${rec.features.resonanceFreqHz.toInt()} Hz, decay ${"%.0f".format(rec.features.decayTimeMs)} ms"
        holder.b.textHistoryScore.contentDescription = "Anomaly ${rec.anomalyScore.toInt()} percent"
    }
}
