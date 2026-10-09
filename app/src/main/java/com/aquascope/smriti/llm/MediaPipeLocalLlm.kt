package com.aquascope.smriti.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * [ROLE] MediaPipe GenAI engine for on-device Ask (Qwen / Gemma .task).
 * [WHY] Local inference with aggressive prompt clipping so ekv1280 models still return text.
 * [IQOO] Loaded only in :smriti_llm sandbox process — never crash the UI.
 */
class MediaPipeLocalLlm(
    private val context: Context,
    private val modelFile: File
) : LocalLlmEngine {

    private val inference = AtomicReference<LlmInference?>(null)
    private var loadError: String? = null
    private var maxTokens: Int = 1024

    override val isReady: Boolean
        get() = inference.get() != null

    override val modelLabel: String?
        get() = if (isReady) modelFile.name else null

    fun warmUp(): Boolean {
        if (inference.get() != null) return true
        if (modelFile.name.endsWith(".litertlm", ignoreCase = true)) {
            loadError = LocalModelStore.GALLERY_NPU_MESSAGE
            Log.w(TAG, "Refusing Gallery NPU bundle: ${modelFile.name}")
            return false
        }
        // maxTokens is prompt + reply. Match exported KV window for ekv1280.
        maxTokens = when {
            modelFile.name.contains("1280", ignoreCase = true) -> 1280
            modelFile.length() > 900_000_000L -> 768
            else -> 1024
        }
        return try {
            System.gc()
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(maxTokens)
                .setMaxTopK(40)
                .setPreferredBackend(LlmInference.Backend.DEFAULT)
                .build()
            val engine = LlmInference.createFromOptions(context, options)
            inference.set(engine)
            loadError = null
            Log.i(TAG, "Local LLM ready: ${modelFile.name} maxTokens=$maxTokens")
            true
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "Local LLM load failed: $loadError", t)
            inference.set(null)
            false
        }
    }

    fun lastError(): String? = loadError

    override fun generate(prompt: String): String? {
        val engine = inference.get() ?: return null
        return try {
            val clipped = clipToContext(engine, prompt)
            val text = generateWithSession(engine, clipped)
                ?: generateWithSession(engine, shortenForRetry(clipped))
            if (text.isNullOrBlank()) {
                val empty = "Model returned empty text (prompt may still exceed the $maxTokens token window)"
                loadError = empty
                Log.w(TAG, empty)
                null
            } else {
                loadError = null
                text
            }
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "Local LLM generate failed: $loadError", t)
            null
        }
    }

    private fun generateWithSession(engine: LlmInference, prompt: String): String? {
        val session = try {
            LlmInferenceSession.createFromOptions(engine, sessionOptions())
        } catch (t: Throwable) {
            Log.w(TAG, "session options failed, retrying defaults: ${t.message}")
            LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(40)
                    .setTemperature(if (isQwen()) 0.45f else 0.3f)
                    .build()
            )
        }
        return try {
            session.addQueryChunk(prompt)
            val raw = session.generateResponse()?.trim()?.takeIf { it.isNotEmpty() }
            raw?.let { BpeDecoder.cleanModelOutput(it) }
        } finally {
            try {
                session.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun isQwen(): Boolean = modelFile.name.contains("qwen", ignoreCase = true)

    private fun sessionOptions(): LlmInferenceSession.LlmInferenceSessionOptions {
        val builder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTopP(0.9f)
            .setTemperature(if (isQwen()) 0.45f else 0.3f)
        // GraphOptions break some Qwen MediaPipe .task builds — skip for Qwen.
        if (!isQwen()) {
            builder.setGraphOptions(
                GraphOptions.builder()
                    .setEnableVisionModality(false)
                    .setEnableAudioModality(false)
                    .build()
            )
        }
        return builder.build()
    }

    private fun clipToContext(engine: LlmInference, prompt: String): String {
        // Leave real room for the spoken answer — empty replies usually mean prompt ate the window.
        val reserve = if (isQwen()) 360 else 220
        val budget = (maxTokens - reserve).coerceAtLeast(256)
        val body = if (prompt.contains("<|im_start|>") || prompt.contains("<start_of_turn>")) {
            prompt
        } else {
            prompt
        }
        var text = formatPrompt(body)
        val tokens = runCatching { engine.sizeInTokens(text) }.getOrNull()
        if (tokens != null && tokens > budget) {
            text = keepQuestionAndScreenFacts(prompt, budget)
            val after = runCatching { engine.sizeInTokens(text) }.getOrNull()
            Log.w(TAG, "Clipped prompt $tokens → ${after ?: "~$budget"} tokens (budget=$budget)")
            if (after != null && after > budget) {
                text = keepQuestionAndScreenFacts(prompt, (budget * 0.65f).toInt().coerceAtLeast(200))
            }
        } else if (tokens == null && text.length > budget * 3) {
            text = keepQuestionAndScreenFacts(prompt, budget)
        }
        return text
    }

    /** Prefer USER_QUESTION + APP_DATA facts over prefix-only take() which dropped the clip. */
    private fun keepQuestionAndScreenFacts(raw: String, tokenBudget: Int): String {
        val charBudget = (tokenBudget * 2.6f).toInt().coerceAtLeast(700)
        val q = Regex("USER_QUESTION:\\s*(.+)").find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val ocr = Regex("SCREEN_OCR:\\s*([\\s\\S]*?)(?:\\nEVIDENCE_STATE:|\\nAPP_HINT|\\nRULES_HINT|\\nCANONICAL_ANSWER:|\\nMEMORY_EVENTS|\\nNEURAL_MEMORY|\\nHOME_STATUS:|\\nGUARDIAN:)")
            .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val home = Regex("HOME_STATUS:\\s*([\\s\\S]*?)(?:\\nGUARDIAN:|\\nSCAN_LOCATIONS:|\\nSCREEN_OCR:|\\nNEURAL_MEMORY:|\\nEVIDENCE_STATE:)")
            .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val guardian = Regex("GUARDIAN:\\s*([\\s\\S]*?)(?:\\nSCAN_LOCATIONS:|\\nSCREEN_OCR:|\\nNEURAL_MEMORY:|\\nEVIDENCE_STATE:|\\nRELATED_EVENT)")
            .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val scans = Regex("SCAN_LOCATIONS:\\s*([\\s\\S]*?)(?:\\nSCREEN_OCR:|\\nNEURAL_MEMORY:|\\nEVIDENCE_STATE:|\\nRELATED_EVENT|\\nAPP_HINT|\\nRULES_HINT)")
            .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val neural = Regex("NEURAL_MEMORY[^\\n]*:\\s*([\\s\\S]*?)(?:\\nSCREEN_OCR:|\\nEVIDENCE_STATE:|\\nAPP_HINT|\\nRULES_HINT|\\nMEMORY_EVENTS|\\nRELATED_EVENT|\\nANSWER:)")
            .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val hint = Regex("(?:APP_HINT[^:\\n]*:|RULES_HINT[^:\\n]*:|CANONICAL_ANSWER:)\\s*([\\s\\S]*?)(?:\\nSCREEN_OCR:|\\nMEMORY_EVENTS|\\nEVIDENCE_STATE:|\\nSUGGESTED)")
            .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            .ifBlank {
                Regex("CANONICAL_ANSWER:\\s*([\\s\\S]*?)(?:\\nMEMORY_EVENTS)")
                    .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            }
        val events = Regex("MEMORY_EVENTS[^\\n]*:\\s*([\\s\\S]*?)(?:\\nSUGGESTED_ACTIONS:|\\nANSWER:|$)")
            .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val compact = buildString {
            appendLine("Answer ONLY from APP_DATA. Under 100 words. Do not invent.")
            appendLine("USER_QUESTION: ${q.take(160)}")
            appendLine()
            if (home.isNotBlank()) {
                appendLine("HOME_STATUS:")
                appendLine(home.take(280))
                appendLine()
            }
            if (guardian.isNotBlank()) {
                appendLine("GUARDIAN:")
                appendLine(guardian.take(200))
                appendLine()
            }
            if (scans.isNotBlank()) {
                appendLine("SCAN_LOCATIONS:")
                appendLine(scans.take(280))
                appendLine()
            }
            if (neural.isNotBlank()) {
                appendLine("NEURAL_MEMORY:")
                appendLine(neural.take(360))
                appendLine()
            }
            appendLine("SCREEN_OCR:")
            appendLine(ocr.take(500).ifBlank { "(none)" })
            appendLine()
            appendLine("APP_HINT:")
            appendLine(hint.take(360).ifBlank { "(none)" })
            if (events.isNotBlank()) {
                appendLine()
                appendLine("MEMORY_EVENTS:")
                appendLine(events.take(420))
            }
            appendLine()
            append("ANSWER:")
        }
        return formatPrompt(compact.take(charBudget))
    }

    private fun formatPrompt(raw: String): String {
        if (raw.contains("<start_of_turn>") || raw.contains("<|im_start|>")) return raw
        val name = modelFile.name.lowercase()
        return if (name.contains("gemma")) {
            "<start_of_turn>user\n$raw<end_of_turn>\n<start_of_turn>model\n"
        } else if (name.contains("qwen")) {
            // ChatML — keep system short so ekv1280 has room for APP_DATA + answer.
            "<|im_start|>system\nYou are SMRITI. Use only APP_DATA. Never invent confirmed leaks.<|im_end|>\n" +
                "<|im_start|>user\n$raw<|im_end|>\n" +
                "<|im_start|>assistant\n"
        } else {
            raw
        }
    }

    private fun shortenForRetry(prompt: String): String {
        return keepQuestionAndScreenFacts(prompt, maxTokens / 2)
    }

    override fun close() {
        try {
            inference.getAndSet(null)?.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "SmritiLocalLlm"

        fun tryCreate(context: Context, store: LocalModelStore): MediaPipeLocalLlm? {
            val file = store.findInstalledMediaPipe() ?: return null
            val llm = MediaPipeLocalLlm(context.applicationContext, file)
            llm.warmUp()
            return llm
        }
    }
}
