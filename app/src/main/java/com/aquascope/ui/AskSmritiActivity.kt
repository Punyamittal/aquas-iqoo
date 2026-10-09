package com.aquascope.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.aquascope.R
import com.aquascope.databinding.ActivityAskSmritiBinding
import com.aquascope.halo.SmritiLightMapper
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.brain.SmritiMemoryEngine
import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.SmritiAnswer
import com.aquascope.smriti.llm.LocalModelCatalog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AskSmritiActivity : SmritiScreenActivity() {

    private lateinit var binding: ActivityAskSmritiBinding
    private lateinit var smriti: SmritiCore
    private lateinit var haloBind: HaloBinding
    private var lastEvidenceEventId: String? = null
    private var answering = false
    private var lastQueryWasVoice = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isSpeaking = false

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                binding.inputQuestion.setText(spoken)
                lastQueryWasVoice = true
                ask(spoken)
            }
        }
    }

    private val suggestions = listOf(
        "Has this happened before?",
        "What changed today?",
        "What's normal?",
        "Show unusual activity."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAskSmritiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        smriti = SmritiCore.get(this)
        SmritiNav.bind(this, binding.bottomNav, SmritiNav.TAB_ASK)
        haloBind = HaloBinding(this, binding.haloIndicator)

        initTts()
        updateLanguageBadge()

        binding.btnLanguage.setOnClickListener {
            showLanguageDialog()
        }

        suggestions.forEach { q ->
            val chip = Chip(this).apply {
                text = q
                isCheckable = false
                setChipBackgroundColorResource(R.color.navy)
                chipStrokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.ocean))
                chipStrokeWidth = resources.displayMetrics.density
                setTextColor(getColor(R.color.warm_muted))
                setOnClickListener {
                    binding.inputQuestion.setText(q)
                    lastQueryWasVoice = false
                    ask(q)
                }
            }
            binding.chipQuestions.addView(chip)
        }

        binding.textModelBadge.setOnClickListener {
            startActivity(Intent(this, LocalModelActivity::class.java))
        }

        binding.btnAsk.setOnClickListener {
            lastQueryWasVoice = false
            submitFromInput()
        }
        binding.btnMic.setOnClickListener {
            startVoiceRecognition()
        }
        binding.btnSpeak.setOnClickListener {
            val text = binding.textAnswer.text?.toString().orEmpty()
            if (text.isNotBlank()) speakText(text)
        }
        binding.inputQuestion.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) {
                lastQueryWasVoice = false
                submitFromInput()
                true
            } else false
        }

        binding.btnWhy.setOnClickListener {
            val id = lastEvidenceEventId
            if (id != null) {
                startActivity(Intent(this, EvidenceActivity::class.java).putExtra("event_id", id))
            }
        }

        showIdleHero()

        // Warm MediaPipe in :smriti_llm only. A native crash there must not kill Ask.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { smriti.ensureLocalLlm() }
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) refreshModelBadge()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.orbView.onResume()
        binding.orbView.setPaused(false)
        haloBind.start()
        if (!answering) {
            haloBind.controller().setState(SmritiLightState.NORMAL)
            binding.orbView.setActive(false)
            binding.orbView.setPulse(0.7f)
            binding.orbView.alpha = 1f
        } else {
            binding.orbView.setPulse(0.4f)
            binding.orbView.alpha = 0.45f
        }
        refreshModelBadge()
    }

    override fun onPause() {
        binding.orbView.onPause()
        haloBind.stop()
        super.onPause()
    }

    private fun showIdleHero() {
        answering = false
        binding.orbView.visibility = View.VISIBLE
        binding.orbView.alpha = 1f
        binding.orbView.onResume()
        binding.orbView.setPaused(false)
        binding.orbView.setActive(false)
        binding.orbView.setPulse(0.7f)
        binding.heroCopy.visibility = View.VISIBLE
        binding.scrollChips.visibility = View.VISIBLE
        binding.scrollAnswer.visibility = View.GONE
    }

    private fun showAnswerMode() {
        answering = true
        dimStarsForAnswer()
        binding.heroCopy.visibility = View.GONE
        binding.scrollChips.visibility = View.GONE
        binding.scrollAnswer.visibility = View.VISIBLE
    }

    /** Keep shooting stars as ambient background; never hide them after the first ask. */
    private fun dimStarsForAnswer() {
        try {
            binding.orbView.visibility = View.VISIBLE
            binding.orbView.alpha = 0.45f
            binding.orbView.setPaused(false)
            binding.orbView.setActive(false)
            binding.orbView.setPulse(0.4f)
            binding.orbView.onResume()
        } catch (t: Throwable) {
            Log.e(TAG, "dim stars failed", t)
        }
    }

    private fun refreshModelBadge() {
        val installed = smriti.modelStore.findInstalled()
        val isQwen = installed?.name?.contains("qwen", ignoreCase = true) == true ||
            LocalModelCatalog.entryForFileName(installed?.name.orEmpty())?.id?.contains("qwen") == true
        val modelName = if (isQwen) "Qwen" else "the local model"
        val mode = when {
            !smriti.llmPrefs.enabled -> getString(R.string.ask_mode_rules)
            smriti.isLocalLlmReady() -> getString(R.string.ask_mode_model, modelName)
            installed != null -> getString(R.string.ask_mode_loading, modelName)
            else -> getString(R.string.ask_mode_rules)
        }
        binding.textModelBadge.text = mode
    }

    private fun submitFromInput() {
        val q = binding.inputQuestion.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) {
            Toast.makeText(this, "Type a question about your home first", Toast.LENGTH_SHORT).show()
        } else ask(q)
    }

    private fun ask(question: String) {
        binding.btnAsk.isEnabled = false
        dimStarsForAnswer()
        binding.heroCopy.visibility = View.GONE
        binding.scrollChips.visibility = View.GONE
        haloBind.controller().setState(SmritiLightState.MEMORY_RECALL)
        if (smriti.llmPrefs.enabled && smriti.modelStore.findInstalled() != null && !smriti.isLocalLlmReady()) {
            Toast.makeText(this, "Loading local model… first answer may take a minute", Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            val answer = try {
                withContext(Dispatchers.Default) {
                    val engine = SmritiMemoryEngine(this@AskSmritiActivity)
                    val mem = runCatching { engine.recall(question) }.getOrNull()
                    val recent = runCatching { engine.timeline(12) }.getOrNull().orEmpty()
                    val episodes = when {
                        mem?.found == true && mem.matches.isNotEmpty() ->
                            (mem.matches + recent).distinctBy { it.id }.take(12)
                        else -> recent
                    }
                    smriti.ask(
                        question = question,
                        neuralEpisodes = episodes
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Ask failed", t)
                SmritiAnswer(
                    text = "I couldn't finish that with the local model. Try again, or open System and reload the model.",
                    evidenceState = EvidenceState.UNKNOWN,
                    relatedEvents = emptyList(),
                    evidence = null
                )
            } finally {
                if (!isFinishing && !isDestroyed) binding.btnAsk.isEnabled = true
            }

            if (isFinishing || isDestroyed) return@launch

            showAnswerMode()
            binding.textAsked.text = question

            val settle = SmritiLightMapper.fromAsk(
                answer.evidenceState,
                answer.relatedEvents.size,
                answer.relatedEvents.maxOfOrNull { it.anomalyScore } ?: 0.0
            )
            binding.textEvidenceState.text = when (answer.evidenceState) {
                EvidenceState.UNKNOWN -> getString(R.string.ask_state_unknown)
                EvidenceState.OBSERVED -> getString(R.string.ask_state_observed)
                EvidenceState.INFERRED -> getString(R.string.ask_state_inferred)
                EvidenceState.POSSIBLE -> getString(R.string.ask_state_possible)
                EvidenceState.CONFIRMED -> getString(R.string.ask_state_confirmed)
            }
            binding.textEvidenceState.setTextColor(
                getColor(
                    when (answer.evidenceState) {
                        EvidenceState.UNKNOWN -> R.color.warm_faint
                        EvidenceState.INFERRED, EvidenceState.POSSIBLE -> R.color.amber
                        else -> R.color.cyan
                    }
                )
            )
            binding.textAnswer.text = com.aquascope.smriti.llm.AskAnswerCleaner.cleanForDisplay(answer.text)
            lastEvidenceEventId = answer.relatedEvents.firstOrNull()?.id
            binding.btnWhy.visibility = if (lastEvidenceEventId != null) View.VISIBLE else View.GONE

            if (answer.relatedEvents.isNotEmpty()) {
                haloBind.controller().setStateBrief(SmritiLightState.NEW_MEMORY, settle, 500L)
            } else {
                haloBind.controller().setState(settle)
            }
            refreshModelBadge()
            if (smriti.llmPrefs.enabled && !answer.usedLocalModel && smriti.modelStore.findInstalled() != null) {
                val hint = smriti.llmFailureHint()
                    ?: smriti.modelStatusLight().message
                val msg = when {
                    !smriti.isLocalLlmReady() ->
                        "Rules answer — local model unavailable. $hint"
                    !hint.isNullOrBlank() && (
                        hint.contains("empty", true) ||
                            hint.contains("timed out", true) ||
                            hint.contains("failed", true)
                        ) ->
                        "Rules answer — model did not return usable text. $hint"
                    else ->
                        "Rules answer — model reply discarded. $hint"
                }
                Toast.makeText(this@AskSmritiActivity, msg, Toast.LENGTH_LONG).show()
            } else if (answer.usedLocalModel) {
                Toast.makeText(
                    this@AskSmritiActivity,
                    "Answered with ${answer.modelName ?: "local model"}",
                    Toast.LENGTH_SHORT
                ).show()
            }

            if (lastQueryWasVoice || smriti.llmPrefs.autoSpeak) {
                speakText(answer.text)
            }
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                applyTtsLanguage(smriti.llmPrefs.targetLanguage)
            }
        }
    }

    private fun applyTtsLanguage(langCode: String, text: String? = null) {
        val engine = tts ?: return
        if (!ttsReady) return
        val targetLocale = when (langCode.lowercase()) {
            "hi" -> Locale("hi", "IN")
            "es" -> Locale("es", "ES")
            "fr" -> Locale("fr", "FR")
            "de" -> Locale("de", "DE")
            "en" -> Locale("en", "US")
            "hinglish" -> Locale("hi", "IN")
            else -> {
                if (text != null && text.any { it in '\u0900'..'\u097F' }) {
                    Locale("hi", "IN")
                } else {
                    Locale.getDefault()
                }
            }
        }
        val res = engine.setLanguage(targetLocale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.language = Locale.getDefault()
        }
    }

    private fun speakText(text: String) {
        val engine = tts ?: return
        if (!ttsReady) {
            Toast.makeText(this, "Speech engine loading...", Toast.LENGTH_SHORT).show()
            return
        }
        if (isSpeaking) {
            engine.stop()
            isSpeaking = false
            binding.btnSpeak.setText(R.string.ask_read_aloud)
            return
        }
        applyTtsLanguage(smriti.llmPrefs.targetLanguage, text)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "ask_answer")
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = true
                    binding.btnSpeak.setText(R.string.ask_stop_reading)
                }
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = false
                    binding.btnSpeak.setText(R.string.ask_read_aloud)
                }
            }
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = false
                    binding.btnSpeak.setText(R.string.ask_read_aloud)
                }
            }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "ask_answer")
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask about your home, in any language")
            val langTag = when (smriti.llmPrefs.targetLanguage.lowercase()) {
                "hi" -> "hi-IN"
                "es" -> "es-ES"
                "fr" -> "fr-FR"
                "de" -> "de-DE"
                "en" -> "en-US"
                "hinglish" -> "hi-IN"
                else -> Locale.getDefault().toLanguageTag()
            }
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Voice input not available on device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLanguageBadge() {
        val name = when (smriti.llmPrefs.targetLanguage.lowercase()) {
            "hi" -> "हिन्दी"
            "hinglish" -> "Hinglish"
            "es" -> "Español"
            "fr" -> "Français"
            "de" -> "Deutsch"
            "en" -> "English"
            else -> null
        }
        binding.btnLanguage.text = name?.let { getString(R.string.ask_language_label, it) }
            ?: getString(R.string.ask_language_auto)
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(
            "Auto (Match question language)",
            "English",
            "हिन्दी (Hindi)",
            "Hinglish (Hindi in English script)",
            "Español (Spanish)",
            "Français (French)",
            "Deutsch (German)"
        )
        val codes = arrayOf("auto", "en", "hi", "hinglish", "es", "fr", "de")
        val currentIdx = codes.indexOf(smriti.llmPrefs.targetLanguage).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(this)
            .setTitle("Chat & Voice Language")
            .setSingleChoiceItems(languages, currentIdx) { dialog, which ->
                val selected = codes[which]
                smriti.llmPrefs.targetLanguage = selected
                updateLanguageBadge()
                applyTtsLanguage(selected)
                Toast.makeText(this, "Language: ${languages[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AskSmriti"
    }
}
