package com.smriti.aqua.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smriti.aqua.AquaApp
import com.smriti.aqua.audio.AcousticProbe
import com.smriti.aqua.demo.DemoRigSimulator
import com.smriti.aqua.dsp.DspEngine
import com.smriti.aqua.memory.EpisodicStore
import com.smriti.aqua.memory.Event
import com.smriti.aqua.memory.EventStatus
import com.smriti.aqua.memory.RecallAnswer
import com.smriti.aqua.memory.RecallEngine
import com.smriti.aqua.sensors.AccelRecorder
import com.smriti.aqua.sensors.FusionGate
import com.smriti.aqua.sensors.HapticFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class ScanUiState(
    val status: String = "Ready — calibrate a baseline, then inspect.",
    val anomalyScore: Float? = null,
    val coherence: Float? = null,
    val spectrogram: FloatArray? = null,
    val frames: Int = 0,
    val mels: Int = 0,
    val objectId: String = "PIPE_01_KITCHEN",
    val verdict: String? = null,
    val busy: Boolean = false,
)

/**
 * Wires capture -> understand -> store -> recall for the Scan/Memory/Ask screens.
 * [useSimulator] defaults to true so the full pipeline is demoable without a rig.
 */
class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val store: EpisodicStore = (app as? AquaApp)?.store ?: EpisodicStore(app)
    private val recall = RecallEngine(store)
    private val simulator = DemoRigSimulator(seed = 42L)
    private val accelRecorder = AccelRecorder(app)
    private val probe = AcousticProbe(accelRecorder)
    private val haptics = HapticFeedback(app)

    /** true = synthetic demo-rig data; false = real mic/chirp probe path. */
    var useSimulator: Boolean = true

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val _chat = MutableStateFlow<List<Pair<String, RecallAnswer>>>(emptyList())
    val chat: StateFlow<List<Pair<String, RecallAnswer>>> = _chat.asStateFlow()

    init {
        refreshEvents()
    }

    fun onObjectIdChange(id: String) {
        _state.update { it.copy(objectId = id) }
        refreshEvents()
    }

    /** 3 clean probes (or simulator frames) -> saveBaseline + BASELINE event. */
    fun calibrate() {
        val objectId = _state.value.objectId.trim().ifEmpty { "PIPE_01_KITCHEN" }
        viewModelScope.launch {
            _state.update {
                it.copy(busy = true, verdict = null, status = "Calibrating — listening to the normal…")
            }
            val result = withContext(Dispatchers.IO) {
                val fps: List<FloatArray>
                val spec: FloatArray?
                val frames: Int
                val mels: Int
                if (useSimulator) {
                    fps = List(3) { simulator.syntheticFingerprint(leak = false) }
                    val pcm = simulator.syntheticPcm(leak = false)
                    spec = DspEngine.computeSpectrogram(pcm, DspEngine.SAMPLE_RATE)
                    val dims = DspEngine.spectrogramDims(pcm.size)
                    frames = dims[0]; mels = dims[1]
                } else {
                    val results = List(3) { probe.probe() }
                    fps = results.map { it.fingerprint }
                    val last = results.last()
                    spec = last.spectrogram; frames = last.frames; mels = last.mels
                }
                val baselineId = store.saveBaseline(objectId, fps)
                store.insertEvent(
                    Event(
                        objectId = objectId,
                        location = locationOf(objectId),
                        timestamp = System.currentTimeMillis(),
                        sensor = if (useSimulator) "simulator" else "mic",
                        baselineId = baselineId,
                        deviation = 0f,
                        durationMs = 0L,
                        coherence = 0f,
                        status = EventStatus.BASELINE,
                        note = "Baseline fingerprint saved",
                    )
                )
                CalOutcome(spec, frames, mels)
            }
            haptics.buzzConfirm()
            refreshEvents()
            _state.update {
                it.copy(
                    busy = false,
                    status = "Baseline fingerprint saved",
                    verdict = "Baseline fingerprint saved",
                    spectrogram = result.spec,
                    frames = result.frames,
                    mels = result.mels,
                    anomalyScore = null,
                    coherence = null,
                )
            }
        }
    }

    /** probe -> fingerprint -> anomalyScore vs latest baseline -> FusionGate -> event -> haptic. */
    fun inspect() {
        val objectId = _state.value.objectId.trim().ifEmpty { "PIPE_01_KITCHEN" }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, verdict = null, status = "Inspecting — chirp out, echo in…") }
            val outcome = withContext(Dispatchers.IO) {
                val baseline = store.latestBaseline(objectId)
                    ?: return@withContext InspOutcome.NoBaseline

                val pcm: ShortArray
                val accelZ: FloatArray
                val fingerprint: FloatArray
                val spectrogram: FloatArray
                val frames: Int
                val mels: Int
                if (useSimulator) {
                    // Demo beat: after calibration the rig develops a leak.
                    pcm = simulator.syntheticPcm(leak = true)
                    accelZ = simulator.syntheticAccel(coherent = true) // structural, not ambient
                    fingerprint = simulator.syntheticFingerprint(leak = true)
                    spectrogram = DspEngine.computeSpectrogram(pcm, DspEngine.SAMPLE_RATE)
                    val dims = DspEngine.spectrogramDims(pcm.size)
                    frames = dims[0]; mels = dims[1]
                } else {
                    val r = probe.probe()
                    pcm = r.pcm
                    accelZ = r.accelZ
                    fingerprint = r.fingerprint
                    spectrogram = r.spectrogram
                    frames = r.frames
                    mels = r.mels
                }

                val anomaly = DspEngine.anomalyScore(fingerprint, baseline.mean, baseline.variance)
                val coherence = DspEngine.coherenceScore(pcm, accelZ, DspEngine.SAMPLE_RATE, ACCEL_RATE_HZ)

                when (FusionGate.decide(anomaly, coherence)) {
                    FusionGate.Verdict.DISCARD_AMBIENT ->
                        InspOutcome.Discarded(anomaly, coherence, spectrogram, frames, mels)
                    FusionGate.Verdict.PROCESS -> {
                        val isAnomaly = anomaly > ANOMALY_THRESHOLD
                        store.insertEvent(
                            Event(
                                objectId = objectId,
                                location = locationOf(objectId),
                                timestamp = System.currentTimeMillis(),
                                sensor = if (useSimulator) "simulator" else "mic",
                                baselineId = baseline.id,
                                deviation = anomaly,
                                durationMs = LISTEN_MS,
                                coherence = coherence,
                                status = if (isAnomaly) EventStatus.ANOMALY else EventStatus.NORMAL,
                                note = if (isAnomaly) "Unusual reading. Not confirmed yet." else "Sounds normal",
                            )
                        )
                        if (isAnomaly) haptics.buzzAnomaly(anomaly)
                        InspOutcome.Recorded(anomaly, coherence, isAnomaly, spectrogram, frames, mels)
                    }
                }
            }
            refreshEvents()
            when (outcome) {
                InspOutcome.NoBaseline -> _state.update {
                    it.copy(busy = false, status = "No baseline yet — run CALIBRATE first.")
                }
                is InspOutcome.Discarded -> _state.update {
                    it.copy(
                        busy = false,
                        status = "Discarded — ambient sound, not the pipe (incoherent).",
                        verdict = null,
                        anomalyScore = outcome.anomaly,
                        coherence = outcome.coherence,
                        spectrogram = outcome.spec,
                        frames = outcome.frames,
                        mels = outcome.mels,
                    )
                }
                is InspOutcome.Recorded -> _state.update {
                    it.copy(
                        busy = false,
                        status = if (outcome.isAnomaly) "Unusual reading saved. Not confirmed yet." else "Normal. Matches the baseline.",
                        verdict = if (outcome.isAnomaly)
                            "Unusual reading, ${pct(outcome.anomaly)}. Not confirmed yet."
                        else
                            "Normal, ${pct(outcome.anomaly)} from baseline",
                        anomalyScore = outcome.anomaly,
                        coherence = outcome.coherence,
                        spectrogram = outcome.spec,
                        frames = outcome.frames,
                        mels = outcome.mels,
                    )
                }
            }
        }
    }

    /** Grounded Q&A from the episodic store only. */
    fun ask(q: String) {
        val question = q.trim()
        if (question.isEmpty()) return
        viewModelScope.launch {
            val answer = withContext(Dispatchers.IO) { recall.answer(question) }
            _chat.update { it + (question to answer) }
        }
    }

    private fun refreshEvents() {
        val objectId = _state.value.objectId.trim().ifEmpty { "PIPE_01_KITCHEN" }
        viewModelScope.launch {
            _events.value = withContext(Dispatchers.IO) { store.eventsFor(objectId) }
        }
    }

    private fun locationOf(objectId: String): String =
        objectId.substringAfterLast('_', "home").lowercase(Locale.US)

    private fun pct(v: Float): String = String.format(Locale.US, "%.2f", v)

    private data class CalOutcome(val spec: FloatArray?, val frames: Int, val mels: Int)

    private sealed interface InspOutcome {
        data object NoBaseline : InspOutcome
        data class Discarded(
            val anomaly: Float, val coherence: Float,
            val spec: FloatArray, val frames: Int, val mels: Int,
        ) : InspOutcome
        data class Recorded(
            val anomaly: Float, val coherence: Float, val isAnomaly: Boolean,
            val spec: FloatArray, val frames: Int, val mels: Int,
        ) : InspOutcome
    }

    private companion object {
        const val ANOMALY_THRESHOLD = 0.45f
        const val ACCEL_RATE_HZ = 100
        const val LISTEN_MS = 1200L
    }
}
