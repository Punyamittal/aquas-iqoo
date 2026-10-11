package com.aquascope.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.aquascope.data.SurfacePhotoArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
import com.aquascope.baseline.ScoreTrend
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
        val historyAdapter = HistoryAdapter(
            records,
            againstDamp = location?.moistFeatures?.isNotEmpty() == true,
            loadThumb = ::loadThumb,
            onPhoto = ::showPhoto
        )
        binding.recyclerHistory.adapter = historyAdapter
        if (records.isNotEmpty()) lifecycleScope.launch {
            val photos = withContext(Dispatchers.IO) {
                SurfacePhotoArchive(this@HistoryActivity).photosFor(locationId, records)
            }
            historyAdapter.setPhotos(photos)
        }

        val empty = records.isEmpty()
        binding.textEmptyHistory.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerHistory.visibility = if (empty) View.GONE else View.VISIBLE
        binding.btnGenerateReport.visibility = if (empty) View.GONE else View.VISIBLE
        bindTrend(records.sortedBy { it.timestamp }, againstDamp = location?.moistFeatures?.isNotEmpty() == true)

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

    private val thumbs = LruCache<String, Bitmap>(64)

    private fun loadThumb(file: File, view: ImageView) {
        view.tag = file.path
        thumbs.get(file.path)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        val size = (52 * resources.displayMetrics.density).toInt()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeSampled(file, size) } ?: return@launch
            thumbs.put(file.path, bitmap)
            if (view.tag == file.path) view.setImageBitmap(bitmap)
        }
    }

    private fun showPhoto(file: File) {
        val side = resources.displayMetrics.widthPixels
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeSampled(file, side) } ?: return@launch
            val image = ImageView(this@HistoryActivity).apply {
                adjustViewBounds = true
                setImageBitmap(bitmap)
                contentDescription = getString(R.string.history_photo_description)
            }
            AlertDialog.Builder(this@HistoryActivity)
                .setView(image)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun decodeSampled(file: File, targetPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun bindTrend(chronological: List<ScanRecord>, againstDamp: Boolean) {
        if (chronological.size < 2) return
        val scores = chronological.map { it.anomalyScore }
        binding.trendChart.visibility = View.VISIBLE
        binding.trendChart.setScores(chronological.map { it.timestamp }, scores, againstDamp)
        val trend = ScoreTrend.summarize(scores)
        val recent = trend.recentMedian?.toInt()
        val earlier = trend.earlierMedian?.toInt()
        binding.textTrend.visibility = View.VISIBLE
        binding.textTrend.text = when (trend.direction) {
            ScoreTrend.Direction.TOO_FEW -> getString(R.string.trend_too_few, 2 * ScoreTrend.WINDOW)
            ScoreTrend.Direction.RISING -> getString(R.string.trend_rising, earlier, recent)
            ScoreTrend.Direction.FALLING -> getString(R.string.trend_falling, earlier, recent)
            ScoreTrend.Direction.STEADY -> getString(R.string.trend_steady, recent)
        }
    }
}

class HistoryAdapter(
    private val records: List<ScanRecord>,
    private val againstDamp: Boolean = false,
    private val loadThumb: (File, ImageView) -> Unit = { _, _ -> },
    private val onPhoto: (File) -> Unit = {}
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private var photos: List<File?> = emptyList()

    /** Photos aligned with the records, or null where a scan has none. */
    fun setPhotos(list: List<File?>) {
        photos = list
        notifyItemRangeChanged(0, itemCount)
    }

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
            againstDamp -> if (rec.anomalyScore < AnomalyThresholds.DAMP_SPLIT) R.color.status_ok else R.color.status_alert
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

        val photo = photos.getOrNull(position)
        holder.b.imageThumb.visibility = if (photo == null) View.GONE else View.VISIBLE
        if (photo == null) {
            holder.b.imageThumb.tag = null
            holder.b.imageThumb.setOnClickListener(null)
        } else {
            loadThumb(photo, holder.b.imageThumb)
            holder.b.imageThumb.setOnClickListener { onPhoto(photo) }
        }
    }
}
