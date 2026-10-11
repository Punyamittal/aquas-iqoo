package com.aquascope.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aquascope.R
import com.aquascope.audio.AudioEngine
import com.aquascope.baseline.AnomalyScorer
import com.aquascope.baseline.AnomalyThresholds
import com.aquascope.baseline.PlaceReliability
import com.aquascope.data.ImpulseArchive
import com.aquascope.data.ScanLocation
import com.aquascope.data.ScanRecord
import com.aquascope.data.ScanRepository
import com.aquascope.data.SerializableFeatures
import com.aquascope.data.SurfacePhotoArchive
import com.aquascope.dsp.AcousticFeatures
import com.aquascope.dsp.ChirpCombiner
import com.aquascope.dsp.Deconvolution
import com.aquascope.dsp.FeatureExtractor
import com.aquascope.dsp.ImpulseWindow
import com.aquascope.databinding.ActivityScanBinding
import com.aquascope.halo.SmritiLightMapper
import com.aquascope.halo.SmritiLightState
import com.aquascope.report.ReportPoint
import com.aquascope.report.SessionReport
import com.aquascope.sensors.MotionMonitor
import com.aquascope.sensors.Steadiness
import com.aquascope.smriti.SmritiCore
import com.aquascope.vision.SurfaceLookAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var repo: ScanRepository
    private lateinit var impulseArchive: ImpulseArchive
    private lateinit var audioEngine: AudioEngine
    private lateinit var smriti: SmritiCore
    private lateinit var haloBind: HaloBinding
    private var locationId: String = ""
    private var locationLabel: String = ""
    private var lastSmritiEventId: String? = null

    /** Archived impulse response of the latest scan, labelled when the user teaches it. */
    private var lastScanId: String? = null
    private var lastScanFeatures: AcousticFeatures? = null

    private val sessionPoints = mutableListOf<ReportPoint>()

    private lateinit var photoArchive: SurfacePhotoArchive

    /** Photo taken just before the running scan; linked to the scan once it is saved. */
    private var photoBeforeScan: String? = null
    private var scanPhotoId: String? = null

    private val surfacePhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val photoId = result.data?.getStringExtra(SurfacePhotoActivity.EXTRA_PHOTO_ID)
        if (result.resultCode == RESULT_OK && photoId != null) showSurfacePhoto(photoId)
    }

    private val photoThenScan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val photo = result.data?.getStringExtra(SurfacePhotoActivity.EXTRA_PHOTO_ID)
            ?.takeIf { result.resultCode == RESULT_OK }
        AlertDialog.Builder(this)
            .setTitle(R.string.scan_ready_title)
            .setMessage(if (photo != null) R.string.scan_ready_after_photo else R.string.scan_ready_no_photo)
            .setPositiveButton(R.string.scan_ready_start) { _, _ ->
                photoBeforeScan = photo
                runScan()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ScanRepository(this)
        impulseArchive = ImpulseArchive(this)
        photoArchive = SurfacePhotoArchive(this)
        audioEngine = AudioEngine(this)
        smriti = SmritiCore.get(this)
        haloBind = HaloBinding(this, binding.haloIndicator)
        locationId = intent.getStringExtra("location_id") ?: run { finish(); return }

        val location = repo.getLocation(locationId)
        if (location == null) {
            Toast.makeText(this, "Location not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        locationLabel = location.label
        binding.textLocationLabel.text = location.label

        binding.btnStartScan.setOnClickListener { photoThenScan() }
        binding.btnAddPoint.setOnClickListener { addMultiPoint() }
        binding.btnViewReport.setOnClickListener { openSessionReport() }
        binding.btnSurfacePhoto.setOnClickListener { openSurfacePhoto() }
        binding.btnViewEvidence.setOnClickListener {
            val eid = lastSmritiEventId
            if (eid != null) {
                startActivity(Intent(this, EvidenceActivity::class.java).putExtra("event_id", eid))
            }
        }

        showIdle()
    }

    override fun onResume() {
        super.onResume()
        haloBind.start()
        if (binding.groupIdle.visibility == View.VISIBLE) {
            haloBind.controller().setState(SmritiLightState.NORMAL)
        }
    }

    override fun onPause() {
        haloBind.stop()
        super.onPause()
    }

    private fun showIdle() {
        binding.groupIdle.visibility = View.VISIBLE
        binding.groupProgress.visibility = View.GONE
        binding.groupResult.visibility = View.GONE
        binding.pulseView.setScanning(false)
        binding.pulseView.clearSignal()
        binding.liveWaveform.clear()
        binding.acousticFieldIdle.clearSignal()
        binding.btnMarkMoist.visibility = View.GONE
        if (::haloBind.isInitialized) {
            haloBind.controller().setState(SmritiLightState.NORMAL)
        }
    }

    private fun showProgress() {
        binding.groupIdle.visibility = View.GONE
        binding.groupProgress.visibility = View.VISIBLE
        binding.groupResult.visibility = View.GONE
        binding.pulseView.clearSignal()
        binding.pulseView.setScanning(true)
        binding.liveWaveform.clear()
        binding.btnMarkMoist.visibility = View.GONE
    }

    private fun showResult() {
        binding.groupIdle.visibility = View.GONE
        binding.groupProgress.visibility = View.GONE
        binding.groupResult.visibility = View.VISIBLE
        binding.pulseView.setScanning(false)
        binding.imageSurface.visibility = View.GONE
        binding.textSurfaceLook.visibility = View.GONE
        binding.btnSurfacePhoto.setText(R.string.surface_photo_button)
        binding.btnSurfacePhoto.visibility = if (lastScanId != null) View.VISIBLE else View.GONE
        scanPhotoId?.let { showSurfacePhoto(it) }
    }

    private fun photoThenScan() {
        photoThenScan.launch(
            Intent(this, SurfacePhotoActivity::class.java)
                .putExtra(SurfacePhotoActivity.EXTRA_LOCATION_ID, locationId)
        )
    }

    private fun openSurfacePhoto() {
        val scanId = lastScanId ?: return
        surfacePhoto.launch(
            Intent(this, SurfacePhotoActivity::class.java)
                .putExtra(SurfacePhotoActivity.EXTRA_SCAN_ID, scanId)
                .putExtra(SurfacePhotoActivity.EXTRA_LOCATION_ID, locationId)
        )
    }

    private fun showSurfacePhoto(photoId: String) {
        lifecycleScope.launch {
            val (bitmap, sentence) = withContext(Dispatchers.IO) {
                val meta = photoArchive.load(photoId) ?: return@withContext null to null
                val (dry, damp) = photoArchive.taughtLooks(locationId, excludeId = meta.id)
                val comparison = SurfaceLookAnalyzer.compare(meta.look, dry, damp)
                BitmapFactory.decodeFile(photoArchive.imageFile(photoId).path) to SurfaceLookAnalyzer.describe(comparison)
            }
            if (sentence == null) return@launch
            bitmap?.let {
                binding.imageSurface.setImageBitmap(it)
                binding.imageSurface.visibility = View.VISIBLE
            }
            binding.textSurfaceLook.text = sentence
            binding.textSurfaceLook.visibility = View.VISIBLE
            binding.btnSurfacePhoto.setText(R.string.surface_photo_retake)
        }
    }

    private fun runScan() {
        showProgress()
        binding.textStatus.text = getString(R.string.scan_initializing)
        haloBind.controller().setState(SmritiLightState.SCANNING)
        val photo = photoBeforeScan
        photoBeforeScan = null
        scanPhotoId = null

        lifecycleScope.launch {
            try {
                val motion = MotionMonitor(this@ScanActivity)
                val captures = mutableListOf<AudioEngine.CaptureResult>()
                motion.start()
                val steadiness = try {
                    for (i in 1..CHIRPS_PER_SCAN) {
                        binding.textStatus.text = if (CHIRPS_PER_SCAN == 1) {
                            getString(R.string.scan_acoustic_active)
                        } else {
                            getString(R.string.scan_chirp_progress, i, CHIRPS_PER_SCAN)
                        }
                        val capture = audioEngine.playAndRecord { rms ->
                            runOnUiThread {
                                binding.pulseView.setLiveAmplitude((rms * 4f).coerceIn(0f, 1f))
                            }
                        }
                        captures += capture
                        binding.liveWaveform.setWaveform(SignalPreview.waveform(capture.recorded))
                    }
                    motion.stop()
                } catch (e: Exception) {
                    motion.stop()
                    throw e
                }

                val audible = captures.filter {
                    sqrt(it.recorded.sumOf { s -> s * s } / it.recorded.size.coerceAtLeast(1)) >= SILENT_RMS
                }
                if (audible.isEmpty()) {
                    showIdle()
                    haloBind.controller().setState(SmritiLightState.NORMAL)
                    AlertDialog.Builder(this@ScanActivity)
                        .setTitle(R.string.scan_silent_title)
                        .setMessage(R.string.scan_silent_body)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@launch
                }

                binding.textStatus.text = getString(R.string.scan_processing)
                haloBind.controller().setState(SmritiLightState.PROCESSING)

                val chirps = withContext(Dispatchers.Default) {
                    audible.map { c ->
                        val ir = Deconvolution.deconvolveBandLimited(c.recorded, c.reference, c.sampleRate)
                        val echo = ImpulseWindow.aroundDirectPath(ir, c.sampleRate)
                        Chirp(ir, echo, FeatureExtractor.extract(echo.windowed, c.sampleRate), c.sampleRate)
                    }
                }
                val usable = chirps.filter { it.echo.isUsable(it.sampleRate) }
                val counted = usable.ifEmpty { chirps }
                val features = ChirpCombiner.median(counted.map { it.features })
                val shown = counted[ChirpCombiner.closestIndex(counted.map { it.features }, features)]
                val echo = shown.echo
                val spectrum = SignalPreview.spectrum(echo.windowed, shown.sampleRate)
                val wave = SignalPreview.waveform(echo.windowed)
                lastScanFeatures = features
                lastScanId = withContext(Dispatchers.IO) {
                    impulseArchive.save(
                        locationId, shown.sampleRate, shown.impulseResponse, features, echo.peakToNoiseDb,
                        chirpFeatures = if (chirps.size > 1) chirps.map { it.features } else emptyList(),
                        motion = steadiness,
                        chirpEchoes = if (chirps.size > 1) chirps.map { it.echo.windowed } else emptyList()
                    )
                }

                if (Steadiness.moved(steadiness)) {
                    impulseArchive.label(lastScanId, ImpulseArchive.LABEL_REJECTED)
                    lastScanId = null
                    showIdle()
                    haloBind.controller().setState(SmritiLightState.NORMAL)
                    AlertDialog.Builder(this@ScanActivity)
                        .setTitle(R.string.scan_moved_title)
                        .setMessage(R.string.scan_moved_body)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@launch
                }

                if (usable.size < MIN_USABLE_CHIRPS) {
                    impulseArchive.label(lastScanId, ImpulseArchive.LABEL_REJECTED)
                    lastScanId = null
                    showIdle()
                    haloBind.controller().setState(SmritiLightState.NORMAL)
                    AlertDialog.Builder(this@ScanActivity)
                        .setTitle(R.string.scan_weak_echo_title)
                        .setMessage(R.string.scan_weak_echo_body)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@launch
                }

                val scanId = lastScanId
                if (photo != null && scanId != null) {
                    scanPhotoId = withContext(Dispatchers.IO) { photoArchive.attach(photo, scanId)?.id }
                }

                val location = repo.getLocation(locationId)
                if (location == null) {
                    showIdle()
                    Toast.makeText(this@ScanActivity, "Location was deleted", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }

                if (location.featureVersion < ScanLocation.CURRENT_FEATURE_VERSION) {
                    location.baselineFeatures.clear()
                    location.moistFeatures.clear()
                    location.featureVersion = ScanLocation.CURRENT_FEATURE_VERSION
                    repo.updateLocation(location)
                    Toast.makeText(this@ScanActivity, R.string.scan_reteach_notice, Toast.LENGTH_LONG).show()
                }

                if (location.baselineFeatures.isEmpty()) {
                    showResult()
                    haloBind.controller().setStateBrief(
                        SmritiLightState.NEW_MEMORY,
                        SmritiLightState.NORMAL,
                        900L
                    )
                    binding.textScore.text = "—"
                    binding.textScoreLabel.text = getString(R.string.scan_first_baseline_label)
                    binding.textScoreState.text = getString(R.string.scan_state_baseline_needed)
                    binding.textScanMeta.text = getString(R.string.scan_first_baseline_meta)
                    binding.acousticFieldResult.setSpectrum(spectrum, anomaly = false)
                    binding.resultWaveform.setWaveform(wave)
                    binding.textScoreState.setTextColor(
                        ContextCompat.getColor(this@ScanActivity, R.color.cyan)
                    )
                    binding.textScore.setTextColor(
                        ContextCompat.getColor(this@ScanActivity, R.color.ink)
                    )
                    binding.cardResult.setBackgroundResource(R.drawable.bg_surface_panel)
                    binding.btnSetBaseline.visibility = View.VISIBLE
                    binding.btnMarkMoist.visibility = View.GONE
                    binding.btnViewReport.visibility = View.GONE
                    binding.btnViewEvidence.visibility = View.GONE
                    binding.btnSetBaseline.text = getString(R.string.set_baseline)
                    binding.btnSetBaseline.setOnClickListener {
                        teachDry(features, firstBaseline = true)
                    }
                } else {
                    val dry = location.baselineFeatures.map { it.toAcousticFeatures() }
                    val moist = location.moistFeatures.map { it.toAcousticFeatures() }
                    val score = AnomalyScorer.score(
                        scan = features,
                        drySamples = dry,
                        moistSamples = moist
                    )

                    location.scanHistory.add(
                        ScanRecord(
                            features = SerializableFeatures.from(features),
                            anomalyScore = score,
                            scanId = scanId
                        )
                    )
                    repo.updateLocation(location)

                    val mem = smriti.rememberScan(
                        locationId = location.id,
                        locationLabel = location.label,
                        features = features,
                        anomalyScore = score,
                        hasBaseline = true
                    )
                    lastSmritiEventId = mem.id

                    val point = ReportPoint(
                        label = "P${sessionPoints.size + 1}",
                        anomalyScore = score,
                        features = features
                    )
                    sessionPoints.add(point)

                    showResult()
                    displayScore(score, againstDamp = moist.isNotEmpty())
                    binding.acousticFieldResult.setSpectrum(
                        spectrum,
                        anomaly = if (moist.isNotEmpty()) {
                            score >= AnomalyThresholds.DAMP_SPLIT
                        } else {
                            score >= AnomalyThresholds.GREEN_MAX
                        }
                    )
                    binding.resultWaveform.setWaveform(wave)
                    val selfCheck = withContext(Dispatchers.Default) {
                        AnomalyScorer.teachingSelfCheck(dry, moist)
                    }
                    val unreliable = selfCheck != null && selfCheck < AnomalyScorer.MIN_RELIABLE_SELF_CHECK
                    val poseDifference = withContext(Dispatchers.IO) {
                        Steadiness.poseDifferenceDeg(steadiness?.gravity, impulseArchive.taughtGravities(locationId))
                    }
                    binding.textScanMeta.text = buildString {
                        append(scanMeta(mem))
                        append("\nTaught so far: ${dry.size} dry, ${moist.size} damp. ")
                        append(
                            if (moist.isNotEmpty()) "Scored against both."
                            else "Scored against the dry baseline only."
                        )
                        if (selfCheck != null) {
                            append("\n")
                            append(
                                getString(
                                    if (unreliable) R.string.scan_self_check_unreliable else R.string.scan_self_check_ok,
                                    selfCheck.toInt()
                                )
                            )
                        }
                        val dampNeeded = PlaceReliability.MIN_DAMP_FOR_RELIABLE_SCORE - moist.size
                        if (dampNeeded > 0) {
                            append("\n")
                            append(resources.getQuantityString(R.plurals.scan_damp_needed, dampNeeded, dampNeeded))
                        }
                        if (poseDifference != null && poseDifference > Steadiness.MAX_POSE_DIFFERENCE_DEG) {
                            append("\n")
                            append(getString(R.string.scan_pose_differs, poseDifference.toInt()))
                        }
                        append("\n")
                        append(getString(R.string.scan_label_hint))
                    }
                    mem.deviationVsYesterday?.let { delta ->
                        binding.textScoreLabel.text =
                            getString(R.string.baseline_deviation) +
                                ", ${"%+.0f".format(delta)} points since yesterday"
                    }
                    if (moist.size < PlaceReliability.MIN_DAMP_FOR_RELIABLE_SCORE) {
                        binding.textScoreLabel.text = getString(R.string.scan_score_preliminary)
                    } else if (unreliable) {
                        binding.textScoreLabel.text = getString(R.string.scan_score_unreliable)
                        binding.textScoreState.text = getString(R.string.scan_state_unreliable)
                        val muted = ContextCompat.getColor(this@ScanActivity, R.color.warm_muted)
                        binding.textScoreState.setTextColor(muted)
                        binding.textScore.setTextColor(muted)
                        binding.cardResult.setBackgroundResource(R.drawable.bg_surface_panel)
                    }
                    binding.btnSetBaseline.visibility = View.VISIBLE
                    binding.btnSetBaseline.text = getString(R.string.add_to_baseline_dry)
                    binding.btnSetBaseline.setOnClickListener {
                        confirmAddToBaseline(features)
                    }
                    binding.btnMarkMoist.visibility = View.VISIBLE
                    binding.btnMarkMoist.setOnClickListener {
                        teachMoist(features)
                    }
                    binding.btnViewReport.visibility = View.VISIBLE
                    binding.btnViewReport.text = getString(R.string.complete_report)
                    binding.btnViewReport.setOnClickListener {
                        openSessionReport()
                    }
                    binding.btnViewEvidence.visibility = View.VISIBLE
                    updateMultiPointCards()

                    val settle = if (unreliable || moist.size < PlaceReliability.MIN_DAMP_FOR_RELIABLE_SCORE) {
                        SmritiLightState.NORMAL
                    } else {
                        SmritiLightMapper.fromScanEvent(mem)
                    }
                    haloBind.controller().setStateBrief(
                        SmritiLightState.NEW_MEMORY,
                        settle,
                        850L
                    )
                }
            } catch (e: Exception) {
                showIdle()
                Toast.makeText(this@ScanActivity, "Scan failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openSessionReport() {
        if (sessionPoints.isEmpty()) {
            Toast.makeText(this, "Scan at least one point first", Toast.LENGTH_SHORT).show()
            return
        }
        val report = SessionReport(
            locationLabel = locationLabel,
            points = sessionPoints.toList()
        )
        startActivity(Intent(this, ReportActivity::class.java).apply {
            putExtra(ReportActivity.EXTRA_REPORT_JSON, ReportActivity.reportToJson(report))
        })
    }

    private fun confirmAddToBaseline(features: AcousticFeatures) {
        AlertDialog.Builder(this)
            .setTitle("Add to the dry baseline?")
            .setMessage(
                "Only confirm if this surface is dry and known to be fine. " +
                    "Dry samples teach what normal sounds like. Damp samples teach the opposite."
            )
            .setPositiveButton("Add as dry") { _, _ ->
                teachDry(features, firstBaseline = false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun teachDry(features: AcousticFeatures, firstBaseline: Boolean) {
        val location = repo.getLocation(locationId) ?: return
        val dry = location.baselineFeatures.map { it.toAcousticFeatures() }
        val moist = location.moistFeatures.map { it.toAcousticFeatures() }
        val soundsDamp = moist.isNotEmpty() && dry.isNotEmpty() &&
            AnomalyScorer.prepareTeaching(dry + features, moist).first.none { it === features || near(it, features) }
        location.baselineFeatures.add(SerializableFeatures.from(features))
        labelArchivedScan(features, ImpulseArchive.LABEL_DRY)
        // If this was previously taught as moist, remove the closest duplicate.
        removeSimilar(location.moistFeatures, features)
        while (location.baselineFeatures.size > 40) {
            location.baselineFeatures.removeAt(0)
        }
        repo.updateLocation(location)
        binding.btnSetBaseline.visibility = View.GONE
        binding.btnMarkMoist.visibility = View.GONE
        if (firstBaseline) {
            val mem = smriti.rememberBaseline(
                location.id,
                location.label,
                features,
                location.baselineFeatures.size
            )
            lastSmritiEventId = mem.id
            haloBind.controller().setStateBrief(
                SmritiLightState.NEW_MEMORY,
                SmritiLightState.NORMAL,
                1000L
            )
            binding.textScoreLabel.text =
                "Dry baseline saved. Come back later, or scan a damp spot and mark it to sharpen the detector."
            binding.textScoreState.text = getString(R.string.memory_recorded)
            Toast.makeText(this, "Dry baseline saved", Toast.LENGTH_SHORT).show()
        } else {
            binding.textScoreLabel.text =
                "Added as dry. ${location.baselineFeatures.size} dry and ${location.moistFeatures.size} damp samples taught."
            if (soundsDamp) {
                Toast.makeText(this, R.string.teach_dry_sounds_damp, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Added to the dry baseline", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun teachMoist(features: AcousticFeatures) {
        val location = repo.getLocation(locationId) ?: return
        val dry = location.baselineFeatures.map { it.toAcousticFeatures() }
        val moist = location.moistFeatures.map { it.toAcousticFeatures() }
        val soundsDry = dry.isNotEmpty() &&
            AnomalyScorer.prepareTeaching(dry, moist + features).second.none { near(it, features) }
        location.moistFeatures.add(SerializableFeatures.from(features))
        labelArchivedScan(features, ImpulseArchive.LABEL_DAMP)
        removeSimilar(location.baselineFeatures, features)
        // Cap teaching set so one location does not grow forever
        while (location.moistFeatures.size > 40) {
            location.moistFeatures.removeAt(0)
        }
        repo.updateLocation(location)
        binding.btnSetBaseline.visibility = View.GONE
        binding.btnMarkMoist.visibility = View.GONE
        binding.textScoreLabel.text =
            "Taught as damp. ${location.baselineFeatures.size} dry and ${location.moistFeatures.size} damp samples taught."
        if (soundsDry) {
            Toast.makeText(this, R.string.teach_damp_sounds_dry, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Detector updated with a damp sample", Toast.LENGTH_SHORT).show()
        }
    }

    private fun labelArchivedScan(features: AcousticFeatures, label: String) {
        if (features !== lastScanFeatures) return
        val scanId = lastScanId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            impulseArchive.label(scanId, label)
            photoArchive.label(scanId, label)
        }
    }

    private fun near(a: AcousticFeatures, b: AcousticFeatures): Boolean {
        val x = a.toDoubleArray()
        val y = b.toDoubleArray()
        var sum = 0.0
        for (i in x.indices) {
            val d = x[i] - y[i]
            sum += d * d
        }
        return sqrt(sum) < 1.0
    }

    private fun removeSimilar(
        list: MutableList<SerializableFeatures>,
        target: AcousticFeatures
    ) {
        val t = target.toDoubleArray()
        val idx = list.indexOfFirst { s ->
            val a = s.toAcousticFeatures().toDoubleArray()
            var sum = 0.0
            for (i in a.indices) {
                val d = a[i] - t[i]
                sum += d * d
            }
            sqrt(sum) < 1e-3
        }
        if (idx >= 0) list.removeAt(idx)
    }

    private fun displayScore(score: Double, againstDamp: Boolean) {
        binding.textScore.text = "${score.toInt()}%"
        binding.textScoreLabel.text = getString(R.string.baseline_deviation)

        val (state, color, bg) = if (againstDamp) {
            if (score < AnomalyThresholds.DAMP_SPLIT) {
                Triple(getString(R.string.scan_state_normal), R.color.status_ok, R.drawable.bg_result_ok)
            } else {
                Triple(getString(R.string.anomaly_observed), R.color.status_alert, R.drawable.bg_result_alert)
            }
        } else {
            when {
                score < AnomalyThresholds.GREEN_MAX ->
                    Triple(getString(R.string.scan_state_normal), R.color.status_ok, R.drawable.bg_result_ok)
                score < AnomalyThresholds.YELLOW_MAX ->
                    Triple(getString(R.string.anomaly_observed), R.color.status_warn, R.drawable.bg_result_warn)
                else ->
                    Triple(getString(R.string.anomaly_observed), R.color.status_alert, R.drawable.bg_result_alert)
            }
        }

        binding.textScoreState.text = state
        binding.textScoreState.setTextColor(ContextCompat.getColor(this, color))
        binding.textScore.setTextColor(ContextCompat.getColor(this, color))
        binding.cardResult.setBackgroundResource(bg)
    }

    private fun scanMeta(mem: com.aquascope.smriti.model.PhysicalEvent): String {
        val fmt = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault())
        val first = smriti.firstAnomalyAt(mem.locationId)
        return buildString {
            val status = mem.status.name.replace('_', ' ').lowercase(java.util.Locale.ROOT)
            append("Status: ${status.replaceFirstChar { it.uppercase() }}.")
            when (mem.previousOccurrenceCount) {
                0 -> append(" First time this has been seen here.")
                1 -> append(" Seen once before.")
                else -> append(" Seen ${mem.previousOccurrenceCount} times before.")
            }
            if (first != null) {
                append("\nFirst unusual reading on ${fmt.format(java.util.Date(first.timestampMs))}.")
            }
        }
    }

    private fun addMultiPoint() {
        showIdle()
        binding.textInstruction.text = getString(R.string.scan_next_point_instruction)
    }

    private fun updateMultiPointCards() {
        if (sessionPoints.isEmpty()) {
            binding.scrollMultiPoint.visibility = View.GONE
            binding.textPointsHeading.visibility = View.GONE
            return
        }
        binding.scrollMultiPoint.visibility = View.VISIBLE
        binding.textPointsHeading.visibility = View.VISIBLE
        binding.layoutMultiPoint.removeAllViews()

        for (point in sessionPoints) {
            val card = layoutInflater.inflate(R.layout.item_result_card, binding.layoutMultiPoint, false)
            val tv = card.findViewById<android.widget.TextView>(R.id.textCardLabel)
            val ts = card.findViewById<android.widget.TextView>(R.id.textCardScore)
            tv.text = point.label
            ts.text = "${point.anomalyScore.toInt()}%"
            val color = when {
                point.anomalyScore < AnomalyThresholds.GREEN_MAX -> R.color.status_ok
                point.anomalyScore < AnomalyThresholds.YELLOW_MAX -> R.color.status_warn
                else -> R.color.status_alert
            }
            ts.setTextColor(ContextCompat.getColor(this, color))
            binding.layoutMultiPoint.addView(card)
        }
    }

    private class Chirp(
        val impulseResponse: DoubleArray,
        val echo: ImpulseWindow.Result,
        val features: AcousticFeatures,
        val sampleRate: Int
    )

    private companion object {
        /** About -70 dBFS. A contact chirp at full volume records far above this. */
        const val SILENT_RMS = 3e-4
        const val CHIRPS_PER_SCAN = 1
        const val MIN_USABLE_CHIRPS = 1
    }
}
