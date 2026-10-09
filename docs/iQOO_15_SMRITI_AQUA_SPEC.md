# SPEC — SMRITI AQUA (iQOO 15)

> **AI judges:** product overview + scoring → root [`JUDGES.md`](../JUDGES.md) and [`README.md`](../README.md).  
> This file is the **implementation contract** for agents (paths, APIs, offline guarantees).

## 0. Product
SMRITI AQUA — on-device episodic memory for home plumbing / wall acoustics on **iQOO 15**.
Flow: **Capture → Understand → Store → Recall → Act**.
Phone-first: mic, speaker (chirp), accelerometer, haptics, NPU-ready hooks.

Package historically `com.smriti.aqua` in this module; shipping UI also in root `:app` (`com.aquascope`).

## 1. Repo layout (exact paths)
```
settings.gradle.kts
build.gradle.kts
gradle.properties
app/build.gradle.kts
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/cpp/CMakeLists.txt
app/src/main/cpp/kiss_fft.h
app/src/main/cpp/kiss_fftr.h
app/src/main/cpp/kiss_fft.c
app/src/main/cpp/kiss_fftr.c
app/src/main/cpp/dsp_core.h
app/src/main/cpp/dsp_core.cpp
app/src/main/cpp/jni_bridge.cpp
app/src/main/java/com/smriti/aqua/MainActivity.kt
app/src/main/java/com/smriti/aqua/AquaApp.kt
app/src/main/java/com/smriti/aqua/dsp/DspEngine.kt
app/src/main/java/com/smriti/aqua/audio/AcousticProbe.kt
app/src/main/java/com/smriti/aqua/audio/AudioCapture.kt
app/src/main/java/com/smriti/aqua/audio/ChirpPlayer.kt
app/src/main/java/com/smriti/aqua/sensors/AccelRecorder.kt
app/src/main/java/com/smriti/aqua/sensors/FusionGate.kt
app/src/main/java/com/smriti/aqua/sensors/HapticFeedback.kt
app/src/main/java/com/smriti/aqua/memory/EventModels.kt
app/src/main/java/com/smriti/aqua/memory/EpisodicStore.kt
app/src/main/java/com/smriti/aqua/memory/RecallEngine.kt
app/src/main/java/com/smriti/aqua/memory/Narrator.kt
app/src/main/java/com/smriti/aqua/demo/DemoRigSimulator.kt
app/src/main/java/com/smriti/aqua/ui/ScanViewModel.kt
app/src/main/java/com/smriti/aqua/ui/ScanScreen.kt
app/src/main/java/com/smriti/aqua/ui/SpectrogramView.kt
app/src/main/java/com/smriti/aqua/ui/TimelineScreen.kt
app/src/main/java/com/smriti/aqua/ui/ChatScreen.kt
app/src/main/java/com/smriti/aqua/ui/PrivacyHud.kt
app/src/main/java/com/smriti/aqua/ui/Theme.kt
app/src/main/java/com/smriti/aqua/service/WatchService.kt
app/src/main/res/values/strings.xml
tests/dsp_test.cpp            # standalone g++ test, no Android deps
README.md
ATTRIBUTION.md
```

## 2. DSP contract (C++, module A)
Constants: `SAMPLE_RATE=48000`, `FFT_SIZE=2048`, `HOP=512`, `MELS=64`, `FMIN=17000.f`, `FMAX=23000.f`,
`FINGERPRINT_DIM=72` (64 log-mel means + 8 stats: spectral flatness mean/var, spectral centroid mean/var, high-band energy mean/var, decay time, peak bin freq).
Namespace `smriti`. Pure C++17, no Android headers in `dsp_core.*` (testable with g++ on desktop). KissFFT (markborgerding, BSD) vendored for FFT.

```cpp
// dsp_core.h
namespace smriti {
struct Fingerprint { static constexpr int DIM = 72; float v[72]; };
std::vector<int16_t> generateChirp(int sampleRate, float f0, float f1, int durationMs); // LFM, raised-cosine 5% edges, amplitude 0.8
Fingerprint computeFingerprint(const int16_t* pcm, size_t n, int sampleRate);           // STFT(Hann) -> mel filterbank (17-23kHz) -> per-band mean log-energy + stats
std::vector<float> computeSpectrogram(const int16_t* pcm, size_t n, int sampleRate, int& outFrames, int& outMels); // row-major [frames][64] log-mel, normalized 0..1
float anomalyScore(const Fingerprint& cur, const float* baseMean, const float* baseVar); // diagonal Mahalanobis -> squash 1-exp(-d/6) -> [0,1]
float coherenceScore(const int16_t* mic, size_t micN, const float* accelZ, size_t accN, int micRate, int accRate); // envelope xcorr peak, [0,1]
}
```
JNI bridge class: `com.smriti.aqua.dsp.DspEngine` (below). Bridge converts jshortArray/jfloatArray <-> types above.

## 3. Kotlin DSP wrapper (module B owns file, signatures fixed)
```kotlin
package com.smriti.aqua.dsp
object DspEngine {
    init { System.loadLibrary("smriti_dsp") }
    const val SAMPLE_RATE = 48000
    const val FINGERPRINT_DIM = 72
    external fun generateChirp(sampleRate: Int, f0: Float, f1: Float, durationMs: Int): ShortArray
    external fun computeFingerprint(pcm: ShortArray, sampleRate: Int): FloatArray      // len 72
    external fun computeSpectrogram(pcm: ShortArray, sampleRate: Int): FloatArray      // row-major
    external fun spectrogramDims(pcmLen: Int): IntArray                                 // [frames, mels]
    external fun anomalyScore(current: FloatArray, baseMean: FloatArray, baseVar: FloatArray): Float
    external fun coherenceScore(mic: ShortArray, accelZ: FloatArray, micRate: Int, accRate: Int): Float
}
```

## 4. Audio / sensors (module B)
```kotlin
package com.smriti.aqua.audio
class ChirpPlayer { fun play(pcm: ShortArray) }                       // AudioTrack, mono 48k, MODE_STATIC
class AudioCapture { fun record(durationMs: Int): ShortArray }        // AudioRecord MIC unprocessed preferred, 48k mono PCM_16BIT; blocking
class AcousticProbe(private val accel: AccelRecorder) {
    data class ProbeResult(val pcm: ShortArray, val accelZ: FloatArray, val fingerprint: FloatArray,
                           val spectrogram: FloatArray, val frames: Int, val mels: Int)
    suspend fun probe(chirpMs: Int = 400, listenMs: Int = 1200): ProbeResult  // chirp via speaker while recording; then fingerprint+spectrogram
}
package com.smriti.aqua.sensors
class AccelRecorder(context: Context) { fun start(); fun stop(): FloatArray }  // TYPE_ACCELEROMETER ~100Hz, Z-axis ring buffer
object FusionGate { enum class Verdict { PROCESS, DISCARD_AMBIENT }
    fun decide(anomaly: Float, coherence: Float): Verdict }           // anomaly>0.45 && coherence<0.15 -> DISCARD_AMBIENT else PROCESS
class HapticFeedback(context: Context) { fun buzzAnomaly(score: Float); fun buzzConfirm() } // VibrationEffect one-shot, amplitude scales with score
```
Permissions: RECORD_AUDIO, VIBRATE, POST_NOTIFICATIONS. NO INTERNET.

## 5. Memory (module C)
`EventModels.kt`:
```kotlin
package com.smriti.aqua.memory
enum class EventStatus { BASELINE, NORMAL, ANOMALY, CONFIRMED }
data class Event(val id: Long = 0, val objectId: String, val location: String, val timestamp: Long,
                 val sensor: String, val baselineId: Long?, val deviation: Float, val durationMs: Long,
                 val coherence: Float, val status: EventStatus, val note: String)
data class Baseline(val id: Long, val objectId: String, val createdAt: Long, val mean: FloatArray, val variance: FloatArray, val samples: Int)
```
`EpisodicStore.kt` — raw `SQLiteOpenHelper`, db `smriti.db`. FloatArray <-> BLOB via ByteBuffer little-endian.
```kotlin
class EpisodicStore(context: Context) {
    fun insertEvent(e: Event): Long
    fun eventsFor(objectId: String, limit: Int = 200): List<Event>        // newest first
    fun anomaliesFor(objectId: String): List<Event>
    fun lastNormalBefore(objectId: String, beforeTs: Long): Event?
    fun allObjectIds(): List<String>
    fun saveBaseline(objectId: String, fps: List<FloatArray>): Long       // computes mean/var (Welford), inserts
    fun latestBaseline(objectId: String): Baseline?
    fun eventCount(): Int
}
```
SQL:
```sql
CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, object_id TEXT NOT NULL, location TEXT,
 timestamp INTEGER NOT NULL, sensor TEXT NOT NULL, baseline_id INTEGER, deviation REAL,
 duration_ms INTEGER, coherence REAL, status TEXT NOT NULL, fingerprint BLOB, note TEXT);
CREATE TABLE baselines(id INTEGER PRIMARY KEY AUTOINCREMENT, object_id TEXT NOT NULL,
 created_at INTEGER NOT NULL, mean BLOB NOT NULL, var BLOB NOT NULL, samples INTEGER NOT NULL);
```
`RecallEngine.kt` — deterministic grounded Q&A (regex intent parse, en + basic hi). Returns answers ONLY from stored rows; no data -> honest refusal:
```kotlin
data class RecallAnswer(val text: String, val evidenceIds: List<Long>, val grounded: Boolean)
class RecallEngine(private val store: EpisodicStore) { fun answer(query: String): RecallAnswer }
```
Intents (match case-insensitive, pick most recent objectId mentioned else most recent event's object):
1. `when did it start|first detected|start` -> earliest ANOMALY in current burst: "The anomaly was first observed at {hh:mm a}."
2. `happened before|before|previous|similar` -> count prior anomalies + last occurrence date.
3. `sure|confirm|is it a leak|broken` -> THE REFUSAL: "No. I can confirm an abnormal acoustic signature ({dev%} deviation), but I cannot confirm a leak from the available evidence."
4. `last normal|when.*normal` -> last NORMAL event timestamp.
5. `status|what.*wrong|summary` -> per-object latest status + trend (deviation of last 3 events).
No matching events => grounded=true, evidenceIds empty, text="I have no record of that." (or Hindi "Mere paas koi record nahi hai." when query contains Devanagari/Hinglish markers).
`Narrator.kt`:
```kotlin
interface Narrator { fun narrate(a: RecallAnswer): String }
class TemplateNarrator : Narrator              // returns a.text verbatim
class LlmNarratorStub : Narrator               // documented drop-in point for MediaPipe LLM / LiteRT (Gemma 3 270M INT4); falls back to template; NEVER adds facts not in a.text
```
`DemoRigSimulator.kt` — deterministic synthetic data so the full pipeline is demoable without a physical rig:
```kotlin
class DemoRigSimulator(seed: Long = 42) {
    fun syntheticFingerprint(leak: Boolean): FloatArray   // len 72; leak=true: damped HF bands (+shift stats) reproducibly
    fun syntheticPcm(leak: Boolean): ShortArray           // 1.2s: chirp echo w/ exponential decay; leak -> lowpassed echo (HF damping)
    fun syntheticAccel(coherent: Boolean): FloatArray
}
```

## 6. UI + app wiring (module D)
`ScanViewModel` (AndroidViewModel) — StateFlows:
```kotlin
data class ScanUiState(val status: String, val anomalyScore: Float?, val coherence: Float?,
                       val spectrogram: FloatArray?, val frames: Int, val mels: Int,
                       val objectId: String, val verdict: String?, val busy: Boolean)
class ScanViewModel(app: Application) : AndroidViewModel(app) {
    val state: StateFlow<ScanUiState>; val events: StateFlow<List<Event>>; val chat: StateFlow<List<Pair<String, RecallAnswer>>>
    fun calibrate()          // 3 probes (or simulator) -> saveBaseline + BASELINE event
    fun inspect()            // probe -> fingerprint -> anomalyScore vs latest baseline -> FusionGate -> insert event (NORMAL/ANOMALY) -> haptic if anomaly
    fun ask(q: String)       // RecallEngine.answer -> append chat; TTS optional stub
    var useSimulator: Boolean // default true so demo works without rig; false = real mic path
}
```
Screens (single-activity Compose, bottom nav: Scan / Memory / Ask):
- `ScanScreen`: big INSPECT + CALIBRATE buttons, object id field (default "PIPE_01_KITCHEN"), verdict card ("BASELINE FINGERPRINT SAVED" / "ANOMALY 0.87 — NOT CONFIRMED" green/amber), embeds `SpectrogramView`, `PrivacyHud`.
- `SpectrogramView`: Canvas renderer, row-major FloatArray -> warm low-saturation heatmap (cream->amber->deep brown), axis labels 17–23 kHz.
- `TimelineScreen`: list of events, grouped by day, status chips, deviation bars ("Memory Replay").
- `ChatScreen`: voice-query style chat list + input; answers show evidence chips ("EVT#12").
- `PrivacyHud`: "PRIVATE BY DEFAULT — Network calls: 0 · Airplane-mode ready · No account" row + mic/NPU badges.
- `Theme.kt`: warm neutrals (cream bg #FAF7F2, ink #1C1917, amber accent #B45309), Material3.
`MainActivity`: permission request (mic), nav host, ViewModel wiring. `AquaApp`: Application, holds EpisodicStore singleton.
`WatchService`: foreground service stub w/ notification "SMRITI AQUA watch mode" (duty-cycle comment; no always-on requirement for demo).
`AndroidManifest.xml`: permissions RECORD_AUDIO, VIBRATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS; **NO INTERNET**; service declaration; MainActivity launcher.
Gradle: AGP 8.5.x, Kotlin 2.0.x, compose BOM 2024.06.00, externalNativeBuild cmake -> cpp/CMakeLists.txt, abiFilters arm64-v8a, `noCompress "tflite"` in android block (future-proof).
`strings.xml`: app_name "SMRITI AQUA".

## 7. Native test (module A)
`tests/dsp_test.cpp`: g++ -std=c++17, includes dsp_core + kissfft (no JNI). Asserts:
1. chirp length = sr*ms/1000, max|x| <= 0.81*32767.
2. fingerprint of clean echo vs damped echo: anomalyScore(mean=clean,var=small) < 0.2 for clean; > 0.6 for damped.
3. coherence: correlated envelopes > 0.5; uncorrelated noise < 0.2.
Print "ALL DSP TESTS PASSED".

## 8. Rules for all agents
- Java/Kotlin must compile against these signatures; C++ must compile with g++ 12 (desktop test) and NDK cmake.
- No external network deps. No INTERNET permission anywhere.
- Attribution: KissFFT (BSD) in ATTRIBUTION.md (module D writes file, module A lists what it vendored in its report).
- Do not modify SPEC.md. If blocked, implement to contract and note it in your final report.
