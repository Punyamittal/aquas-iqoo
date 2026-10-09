# SPEC — SMRITI Core (iQOO Flagship)

> **AI judges:** start at root [`JUDGES.md`](../JUDGES.md). This file is the **module contract** for memory / Guardian / Play / actuators.

## 0. Product
SMRITI Core — on-device episodic memory hub + gaming clip engine + ambient guardian + health recall hooks.
Target: iQOO flagship (Snapdragon 8 Elite Gen 5, dual X-axis haptics, IR, IMU, Monster Halo RGB).
Module-oriented builds may omit `INTERNET`; the shipping `:app` may allow model download separately.

## 1. Repo layout (exact paths)
```
SPEC.md  README.md  ATTRIBUTION.md
settings.gradle.kts  build.gradle.kts  gradle.properties
app/build.gradle.kts  app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml
app/src/main/java/com/smriti/core/SmritiApp.kt
app/src/main/java/com/smriti/core/MainActivity.kt
app/src/main/java/com/smriti/core/memory/Entities.kt
app/src/main/java/com/smriti/core/memory/SmritiDatabase.kt
app/src/main/java/com/smriti/core/memory/TaxonomyParser.kt
app/src/main/java/com/smriti/core/memory/SmritiMemoryEngine.kt
app/src/main/java/com/smriti/core/memory/EmbeddingProvider.kt
app/src/main/java/com/smriti/core/memory/OcrProcessor.kt
app/src/main/java/com/smriti/core/play/CircularByteBuffer.kt
app/src/main/java/com/smriti/core/play/KillFeedTrigger.kt
app/src/main/java/com/smriti/core/play/ScreenBufferRecorder.kt
app/src/main/java/com/smriti/core/actuators/HaloController.kt
app/src/main/java/com/smriti/core/actuators/Haptics.kt
app/src/main/java/com/smriti/core/actuators/IrBlaster.kt
app/src/main/java/com/smriti/core/actuators/HardwareActuatorService.kt
app/src/main/java/com/smriti/core/guardian/FallDetector.kt
app/src/main/java/com/smriti/core/guardian/VoskStt.kt
app/src/main/java/com/smriti/core/guardian/AmbientAudioSensorManager.kt
app/src/main/java/com/smriti/core/ui/Theme.kt
app/src/main/java/com/smriti/core/ui/TelemetryMonitor.kt
app/src/main/java/com/smriti/core/ui/SmritiTelemetryUI.kt
app/src/main/java/com/smriti/core/ui/SmritiViewModel.kt
```
Module ownership: MEM=memory/*, PLAY=play/*, ACT=actuators/*, GRD=guardian/*, UI=everything else.

## 2. Cross-module contracts (sacred)

### 2.1 Memory (MEM owns)
```kotlin
package com.smriti.core.memory
enum class EntityType { TOOL, HEALTH, PLACE, LOCATION, MONEY, PEOPLE, ACCESS, EPISODE }
data class SearchResult(val id: Long, val type: EntityType, val timestamp: Long, val summary: String, val score: Float)
sealed interface RecallVerdict {
    data class Found(val citation: String, val evidenceIds: List<Long>) : RecallVerdict
    data object NoRecord : RecallVerdict
}
class SmritiMemoryEngine(context: Context) {
    fun insertRaw(text: String, source: String): List<Long>      // taxonomy-parse -> rows+FTS+embedding
    fun search(query: String, k: Int = 10): List<SearchResult>   // FTS5 candidates -> vector re-rank
    fun recall(question: String): RecallVerdict                  // zero-hallucination: Found cites timestamp; else NoRecord
    fun recentEpisodes(limit: Int = 50): List<SearchResult>
    fun count(): Long
}
```
Room db "smriti.db" v1. Table `episodes(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, timestamp INTEGER, source TEXT, content TEXT, summary TEXT, embedding BLOB)`. FTS5 virtual table `episodes_fts(content, content='episodes', content_rowid='id')` created in RoomDatabase.Callback.onCreate + triggers (after insert/delete) to keep in sync — raw SQL via SupportSQLiteDatabase. Embedding: BLOB FloatArray(128) little-endian. Vector cache: ConcurrentHashMap<Long, FloatArray> kept hot; cosine in-memory.
`EmbeddingProvider.kt`: `interface EmbeddingProvider { val dim: Int; fun embed(text: String): FloatArray }` + `class HashEmbeddingProvider(override val dim = 128)` — deterministic token feature-hashing (normalized token -> two hash positions, signed), L2-normalized. Default engine uses Hash; KDoc documents drop-in for MediaPipe GenAI embedder.
`TaxonomyParser.kt`: `fun parse(text: String): List<Pair<EntityType, String>>` — deterministic rules: URLs/apk/tool names→TOOL; dosage regex `(\d+)\s?(mg|ml)`→HEALTH; `due|invoice|amount|₹|$`→MONEY; `wifi|wi-fi|password|pass:|gate code`→ACCESS; `park|level|slot`→LOCATION; address patterns (street/pincode)→PLACE; `comes|visit|call|meeting`+name→PEOPLE; else EPISODE. Summary = first 80 chars.
`OcrProcessor.kt`: `class OcrProcessor { fun process(bitmap: Bitmap, onResult: (String) -> Unit) }` — ML Kit bundled TextRecognition (Latin); offline.
`SmritiDatabase.kt`: Room entities/DAO + callback + triggers. DAO exposes insert returning id, byIds, recent, count, deleteOldestBeyond(capRows: Int = 200_000).

### 2.2 Play (PLAY owns)
```kotlin
package com.smriti.core.play
class ScreenBufferRecorder(private val context: Context) {
    sealed interface State { data object Idle : State; data object Recording : State; data class Saved(val file: File) : State }
    val state: StateFlow<State>
    fun start(resultCode: Int, data: Intent): Boolean   // MediaProjection H.264 1080p60 -> 30s RAM ring (zero disk until trigger); 10 Hz luma ImageReader for CV
    fun triggerSave(reason: String): File?              // flush from last IDR via MediaMuxer, synthetic monotonic timestamps
    fun stop()
}
class CircularByteBuffer(capacityBytes: Int) { fun write(chunk: ByteArray, isKeyFrame: Boolean); fun snapshotFromLastKeyFrame(): ByteArray?; val bufferedMs: Int }
class KillFeedTrigger(regionNormalized: RectF) { fun onLumaFrame(luma: ByteArray, w: Int, h: Int, ts: Long): Boolean } // 10 Hz mean-abs-diff vs EMA baseline + flash spike; 3s cooldown
```

### 2.3 Actuators (ACT owns)
```kotlin
package com.smriti.core.actuators
enum class HaloState { STANDBY_EMERALD, EXTRACTION_CYAN, GAME_CRIMSON, CONFIRM_AMBER, EMERGENCY_STROBE }
enum class HapticEvent { OCR_TICK, KILL_SNAP, RECALL_CONFIRM, FALL_ALARM }
class HardwareActuators(context: Context) {
    val haloState: StateFlow<HaloState>
    fun setHalo(state: HaloState)                 // vendor intent broadcast (configurable action constants) + notification-channel lights fallback + haloState flow for in-app edge shader
    fun haptic(event: HapticEvent)                // VibratorManager waveforms: OCR_TICK 12ms/180; KILL_SNAP 40ms/255; RECALL_CONFIRM [0,40,90,40]ms; FALL_ALARM 3x120ms/255
    fun irBlast(frequencyHz: Int, pattern: IntArray): Boolean  // ConsumerIrManager.transmit; false if absent
}
class HardwareActuatorService : Service()        // foreground, binder exposes HardwareActuators singleton (SmritiApp)
object IrPatterns { fun nec(address: Int, command: Int): IntArray  // 38kHz NEC frame builder (9ms+4.5ms lead, 562.5us units, repeat)
    val AC_POWER_TOGGLE: IntArray; val FAN_SPEED_UP: IntArray }    // built from nec() with documented sample addr/cmd
```

### 2.4 Guardian (GRD owns)
```kotlin
package com.smriti.core.guardian
sealed interface GuardianAlert {
    data class Sound(val label: String, val confidence: Float, val timestamp: Long) : GuardianAlert
    data class Fall(val timestamp: Long) : GuardianAlert
    data class Speech(val text: String, val timestamp: Long) : GuardianAlert
}
class AmbientAudioSensorManager(context: Context, scope: CoroutineScope) {
    val alerts: SharedFlow<GuardianAlert>
    fun start()   // AudioRecord 16kHz mono float; 0.975s windows -> YAMNet TFLite (assets/yamnet.tflite, NNAPI delegate, labels from assets/yamnet_labels.csv if present else built-in map of 12 target classes); smoke_alarm/glass/cough/etc >0.45 -> Sound alert
    fun stop()
}
class FallDetector { fun onAccel(x: Float, y: Float, z: Float, ts: Long): Boolean }  // state machine: free-fall |a|<4 m/s^2 >=200ms -> impact >28 m/s^2 within 1s -> stillness |a-g|<1.5 for 2s -> true
class VoskStt(context: Context) { fun start(onText: (String) -> Unit): Boolean  // real vosk-android streaming; false if model dir absent (assets/vosk-model/), never crashes
    fun stop() }
```

### 2.5 UI consumes (UI owns callers)
`SmritiViewModel(app: Application)` exposes: `val telemetry: StateFlow<Telemetry>`, `val episodes: StateFlow<List<SearchResult>>`, `val verdict: StateFlow<RecallVerdict?>`, `val guardianAlerts: StateFlow<GuardianAlert?>`, `val orbState: StateFlow<OrbState>`, `fun onVoiceQuery(text: String)`, `fun refreshTimeline()`, `val halo: StateFlow<HaloState>`, `val recorderState: StateFlow<ScreenBufferRecorder.State>`.
`data class Telemetry(val ramUsedGb: Float, val ramTotalGb: Float, val tokensPerSec: Float, val thermalC: Float?, val npuLabel: String)`
`enum class OrbState { IDLE, LISTENING, COMPUTING, SPEAKING, VERIFIED }`
Wiring (real, no stubs): SmritiApp singletons (engine, actuators, recorder, guardian). onVoiceQuery: orb LISTENING->COMPUTING, engine.recall on Dispatchers.Default, actuators.haptic(RECALL_CONFIRM) + setHalo(CONFIRM_AMBER) on Found, SPEAKING via android.speech.tts.TextToSpeech speak() of citation or "No record found", then VERIFIED/IDLE. Guardian alert Sound("smoke alarm")/Fall -> setHalo(EMERGENCY_STROBE) + haptic(FALL_ALARM).

## 3. UI files (UI owns)
- `Theme.kt`: dark-only Material3, bg #0A0A0C, primary phosphor cyan #00F0FF, secondary crimson #FF003C, tertiary amber #FFB800 (USER-SPECIFIED palette — overrides default warm palette). Glassmorphism cards (semi-transparent #14FFFFFF, 1dp #33FFFFFF border, 24dp corner).
- `TelemetryMonitor.kt`: ActivityManager.MemoryInfo poll 1s -> ramUsed/Total (GB); tokens/sec from MediaPipe callback registry (a simple `object TokenMeter { @Volatile var tokensPerSec = 0f }` that the future LLM path updates — real wiring, not fake data); thermal via /sys/class/thermal/thermal_zone* scan for "cpu"/"soc" types, nullable; label "Hexagon NPU ready".
- `SmritiTelemetryUI.kt`: @Composable `SmritiRoot(viewModel)` — TelemetryHeader (animated RAM bar X/32GB, tok/s, thermal chip), NeuralOrb (Canvas, infiniteTransition pulse; morphs color/glow per OrbState: IDLE dim cyan, LISTENING expanding rings cyan, COMPUTING rotating arcs amber, SPEAKING waveform emerald, VERIFIED check-glow), MicWaveform (Canvas multi-layer sine from live amplitude flow — AudioRecord tap in guardian scope, or amplitude StateFlow from VoskStt; keep simple: animated procedural waveform driven by orbState when mic amplitude unavailable — mark clearly), MemoryTimeline (vertical LazyColumn with custom Canvas connector spine, cards with gyroscope tilt via Sensor.TYPE_ROTATION_VECTOR -> graphicsLayer rotationX/rotationY ±6°, click -> expand detail dialog), VoiceRecallBar (input + mic button), RecorderControl (start/stop SMRITI Play w/ CreateMediaProjection permission contract via rememberLauncherForActivityResult).
- `MainActivity.kt`: single activity; runtime perms RECORD_AUDIO + POST_NOTIFICATIONS; setContent { SmritiTheme { SmritiRoot(vm) } }.
- `SmritiApp.kt`: Application; singletons `engine`, `actuators`, `recorder`, `guardian` (applicationScope SupervisorJob + Dispatchers.Default); db cap trim on start.
- `AndroidManifest.xml`: permissions RECORD_AUDIO, CAMERA, VIBRATE, TRANSMIT_IR, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_MEDIA_PROJECTION, POST_NOTIFICATIONS. NO INTERNET. Services: HardwareActuatorService (exported=false), ScreenBufferRecorder's projection service if used (mediaProjection type). Application .SmritiApp, launcher MainActivity.
- gradle per user block: compose BOM 2024.09.00, room 2.6.1 (+kapt compiler), mlkit text-recognition 16.0.1, mediapipe tasks-genai 0.10.14, tflite 2.16.1 + support 0.4.4 + select-tf-ops 2.16.1, jna 5.13.0@aar, vosk-android 0.3.47, navigation NOT needed. `kotlin-kapt` plugin; noCompress "tflite"; abiFilters arm64-v8a; jvmTarget 17.
- `README.md`: architecture, model assets to drop in (yamnet.tflite, vosk-model/, optional MediaPipe .task) with exact paths, build steps, offline guarantee, demo flow. `ATTRIBUTION.md`: Vosk (Apache-2.0), YAMNet/TF (Apache-2.0), MediaPipe (Apache-2.0), ML Kit (Google ToS), all code written in-window.

## 4. Rules
- "Non-stubbed": every public function has a real working implementation. Model-weight files are runtime assets (documented in README); code paths must be complete and must degrade gracefully (return false / skip) when an asset is absent — never crash.
- Dispatchers discipline: DB/storage on Dispatchers.IO, inference/vision on Dispatchers.Default, UI on Main.
- No agent touches SPEC.md or other modules' files. Commit on your branch only.
- Kotlin must compile by inspection (no compiler here); hand-verify cross-module calls against §2.
