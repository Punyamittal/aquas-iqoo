package com.aquascope.smriti.llm

/**
 * Recommended on-device models for SMRITI Ask (iQOO 15 / Snapdragon 8 Elite class).
 * Models are not bundled. Install via in-app download, file picker, or adb.
 */
object LocalModelCatalog {

    enum class Kind {
        /** Ask rephrase models (MediaPipe .task / LiteRT-LM .litertlm). */
        LLM,
        /** Speech-to-text assets (Whisper .tflite). Never loaded as Ask LLM. */
        SPEECH
    }

    enum class RuntimeKind {
        MEDIAPIPE,
        LITERT_LM,
        SPEECH
    }

    data class Entry(
        val id: String,
        val displayName: String,
        val fileName: String,
        val approxRamGb: Double,
        val notes: String,
        val downloadHint: String,
        val downloadUrl: String? = null,
        val licenseUrl: String? = null,
        val sizeBytes: Long = 0L,
        val requiresAccessToken: Boolean = false,
        val kind: Kind = Kind.LLM,
        val runtime: RuntimeKind = RuntimeKind.MEDIAPIPE
    ) {
        val canDownload: Boolean get() = !downloadUrl.isNullOrBlank()
        val isSpeech: Boolean get() = kind == Kind.SPEECH
        val isLlm: Boolean get() = kind == Kind.LLM
    }

    /** Prefer this for 12 GB iQOO 15. Gated — needs Hugging Face license + token. */
    val gemma3_1b = Entry(
        id = "gemma3-1b-it-int4",
        displayName = "Gemma 3 1B (INT4)",
        fileName = "gemma3-1b-it-int4.task",
        approxRamGb = 1.5,
        notes = "Light MediaPipe Ask model. Prefer Qwen 2.5 when you want fuller APP_DATA answers.",
        downloadHint = "Hugging Face: litert-community / Gemma3-1B-IT INT4 (.task)",
        downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true",
        licenseUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT",
        sizeBytes = 554_661_246L,
        requiresAccessToken = true,
        runtime = RuntimeKind.MEDIAPIPE
    )

    /**
     * Gemma 4 E4B via LiteRT-LM (.litertlm). Stronger than Gemma 3; gated on Hugging Face.
     * Prefer GPU build on Snapdragon 8 Elite.
     */
    val gemma4_e4b = Entry(
        id = "gemma4-e4b-it-gpu",
        displayName = "Gemma 4 E4B (LiteRT-LM GPU)",
        fileName = "gemma-4-E4B-it-gpu.litertlm",
        approxRamGb = 4.0,
        notes = "Downloads Gemma 4 (~3 GB GPU .litertlm). Ask still runs MediaPipe Gemma 3/Qwen until LiteRT-LM (Kotlin 2.x) is enabled.",
        downloadHint = "Hugging Face: litert-community / gemma-4-E4B-it-litert-lm GPU (.litertlm)",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-gpu.litertlm?download=true",
        licenseUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
        sizeBytes = 2_969_059_328L,
        requiresAccessToken = true,
        runtime = RuntimeKind.LITERT_LM
    )

    /** Full CPU / general Gemma 4 E4B (~3.7 GB). Same repo as GPU build; larger on disk. */
    val gemma4_e4b_full = Entry(
        id = "gemma4-e4b-it",
        displayName = "Gemma 4 E4B (~3.7 GB)",
        fileName = "gemma-4-E4B-it.litertlm",
        approxRamGb = 5.0,
        notes = "Full Gemma 4 E4B (~3.7 GB .litertlm). Prefer the GPU build on Snapdragon if you want the smaller file. Ask still needs a MediaPipe .task until LiteRT-LM is enabled.",
        downloadHint = "Hugging Face: litert-community / gemma-4-E4B-it-litert-lm (.litertlm)",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
        licenseUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
        sizeBytes = 3_659_530_240L,
        requiresAccessToken = true,
        runtime = RuntimeKind.LITERT_LM
    )

    /** Comfortable on 16 GB iQOO 15. Import if you already have the GPU INT4 build. */
    val gemma2_2b = Entry(
        id = "gemma2-2b-it-gpu-int4",
        displayName = "Gemma 2 2B (GPU INT4)",
        fileName = "gemma-2-2b-it-gpu-int4.bin",
        approxRamGb = 2.5,
        notes = "Stronger phrasing; still keep retrieval as source of truth.",
        downloadHint = "MediaPipe / Kaggle Gemma-2 2B IT GPU INT4 — import the file",
        runtime = RuntimeKind.MEDIAPIPE
    )

    /** Public MediaPipe .task — preferred Ask model; works without a Hugging Face token. */
    val qwen25_15b = Entry(
        id = "qwen25-15b-instruct-q8",
        displayName = "Qwen 2.5 1.5B (INT8)",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        approxRamGb = 2.5,
        notes = "Preferred for Ask SMRITI — answers from APP_DATA when installed. No Hugging Face login.",
        downloadHint = "Hugging Face: litert-community / Qwen2.5-1.5B-Instruct q8 (.task)",
        downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task?download=true",
        licenseUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct",
        sizeBytes = 1_597_913_616L,
        requiresAccessToken = false,
        runtime = RuntimeKind.MEDIAPIPE
    )

    /**
     * Multilingual Whisper Tiny (int8, 30s window) for speech.
     * Public LiteRT Community build — no Hugging Face token.
     */
    val whisperTiny = Entry(
        id = "whisper-tiny-30s-i8",
        displayName = "Whisper Tiny (multilingual)",
        fileName = "whisper_tiny_30s_i8.tflite",
        approxRamGb = 0.5,
        notes = "On-device speech-to-text. If speech is not English, Ollama can translate before recall.",
        downloadHint = "Hugging Face: litert-community / whisper-tiny int8 (.tflite)",
        downloadUrl = "https://huggingface.co/litert-community/whisper-tiny/resolve/main/whisper_tiny_30s_i8.tflite?download=true",
        licenseUrl = "https://huggingface.co/litert-community/whisper-tiny",
        sizeBytes = 41_116_288L,
        requiresAccessToken = false,
        kind = Kind.SPEECH,
        runtime = RuntimeKind.SPEECH
    )

    val recommended = listOf(qwen25_15b, gemma3_1b, gemma4_e4b, gemma4_e4b_full, gemma2_2b, whisperTiny)

    val downloadable: List<Entry> = recommended.filter { it.canDownload }

    val downloadableLlm: List<Entry> = downloadable.filter { it.isLlm }

    val downloadableSpeech: List<Entry> = downloadable.filter { it.isSpeech }

    const val HF_TOKEN_URL = "https://huggingface.co/settings/tokens"

    fun preferredFileNames(): List<String> = listOf(
        // Ask prefers on-device Qwen when installed (MediaPipe .task).
        qwen25_15b.fileName,
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        "Qwen2.5-1.5B-Instruct_q8_ekv1280.task",
        gemma3_1b.fileName,
        "gemma-3-1B-it-int4.task",
        "Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
        "Gemma3-1B-IT_multi-prefill-seq_q8_ekv1280.task",
        "Gemma3-1B-IT_q4_ekv1280.task",
        "Gemma3-1B-IT.task",
        gemma4_e4b.fileName,
        gemma4_e4b_full.fileName,
        "gemma-4-E2B-it.litertlm",
        gemma2_2b.fileName,
        "model.task",
        "gemma.task",
        "llm.task"
    )

    fun entryForFileName(name: String): Entry? =
        recommended.find { it.fileName.equals(name, ignoreCase = true) }
}
