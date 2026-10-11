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
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.aquascope.R
import com.aquascope.baseline.AnomalyThresholds
import com.aquascope.baseline.PlaceReliability
import com.aquascope.data.ScanLocation
import com.aquascope.data.ScanRepository
import com.aquascope.databinding.ActivityAskSmritiBinding
import com.aquascope.halo.SmritiLightMapper
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.AskChatHistory
import com.aquascope.smriti.ChatTurn
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.engine.AskChart
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
    private lateinit var chatHistory: AskChatHistory
    private val turns = mutableListOf<ChatTurn>()
    private var conversationId = ""
    private var lastEvidenceEventId: String? = null
    private var answering = false
    private var askInFlight = false
    private var lastQueryWasVoice = false
    private var places: List<ScanLocation> = emptyList()

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

    /** Resources in the chat language, so the Ask screen follows the language picker. */
    private fun ui(): android.content.res.Resources {
        val locale = when (smriti.llmPrefs.targetLanguage.lowercase()) {
            "hi" -> Locale("hi", "IN")
            "hinglish" -> Locale.forLanguageTag("hi-Latn")
            "en" -> Locale.ENGLISH
            else -> return resources
        }
        val config = android.content.res.Configuration(resources.configuration).apply { setLocale(locale) }
        return createConfigurationContext(config).resources
    }

    private fun applyUiLanguage() {
        val r = ui()
        binding.textHello.text = r.getString(R.string.ask_hello)
        binding.textPrompt.text = r.getString(R.string.ask_help_prompt)
        binding.inputQuestion.hint = r.getString(R.string.ask_anything_hint)
        binding.btnWhy.text = r.getString(R.string.view_evidence)
        binding.btnSpeak.text = r.getString(if (isSpeaking) R.string.ask_stop_reading else R.string.ask_read_aloud)
        binding.btnMic.contentDescription = r.getString(R.string.ask_voice_cd)
        binding.btnAsk.contentDescription = r.getString(R.string.ask_send_cd)
        binding.btnNewChat.text = r.getString(R.string.ask_new_chat)
        binding.btnChats.text = r.getString(R.string.ask_chats)
        binding.chipQuestions.removeAllViews()
        r.getStringArray(R.array.ask_suggestions).forEach { q ->
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
        updateLanguageBadge()
        if (::chatHistory.isInitialized && !askInFlight) renderChat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAskSmritiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        smriti = SmritiCore.get(this)
        SmritiNav.bind(this, binding.bottomNav, SmritiNav.TAB_ASK)
        haloBind = HaloBinding(this, binding.haloIndicator)

        initTts()
        chatHistory = AskChatHistory(this)
        conversationId = chatHistory.currentConversationId()
        turns.addAll(chatHistory.turnsOf(conversationId))
        applyUiLanguage()

        binding.btnLanguage.setOnClickListener {
            showLanguageDialog()
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
            lastEvidenceEventId?.let { openEvidence(it) }
        }
        binding.btnNewChat.setOnClickListener { startNewChat() }
        binding.btnChats.setOnClickListener { showChatsDialog() }

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
        val r = ui()
        val mode = when {
            !smriti.llmPrefs.enabled -> r.getString(R.string.ask_mode_rules)
            smriti.isLocalLlmReady() -> r.getString(R.string.ask_mode_model, modelName)
            installed != null -> r.getString(R.string.ask_mode_loading, modelName)
            else -> r.getString(R.string.ask_mode_rules)
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
        if (askInFlight) {
            Toast.makeText(this, ui().getString(R.string.ask_busy), Toast.LENGTH_SHORT).show()
            return
        }
        askInFlight = true
        binding.btnAsk.isEnabled = false
        showAnswerMode()
        turns.lastOrNull()?.let { addHistoryTurnView(it) }
        binding.textAsked.text = question
        binding.textEvidenceState.text = ""
        binding.textAnswer.text = ui().getString(R.string.ask_thinking)
        fillChart(binding.chartContainer, null)
        binding.btnWhy.visibility = View.GONE
        scrollToCurrent()
        binding.inputQuestion.text?.clear()
        binding.inputQuestion.clearFocus()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.inputQuestion.windowToken, 0)
        haloBind.controller().setState(SmritiLightState.MEMORY_RECALL)
        if (smriti.llmPrefs.enabled && smriti.modelStore.findInstalled() != null && !smriti.isLocalLlmReady()) {
            Toast.makeText(this, "Loading local model… first answer may take a minute", Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            val answer = try {
                withContext(Dispatchers.Default) { smriti.ask(question = question) }
            } catch (t: Throwable) {
                Log.e(TAG, "Ask failed", t)
                SmritiAnswer(
                    text = "I couldn't finish that with the local model. Try again, or open System and reload the model.",
                    evidenceState = EvidenceState.UNKNOWN,
                    relatedEvents = emptyList(),
                    evidence = null
                )
            } finally {
                askInFlight = false
                if (!isFinishing && !isDestroyed) binding.btnAsk.isEnabled = true
            }

            val latestPlaces = withContext(Dispatchers.IO) { loadPlaces() }
            if (isFinishing || isDestroyed) return@launch
            places = latestPlaces
            val chart = runCatching { AskChart.plan(question, latestPlaces) }
                .onFailure { Log.w(TAG, "chart plan failed", it) }
                .getOrNull()

            val settle = SmritiLightMapper.fromAsk(
                answer.evidenceState,
                answer.relatedEvents.size,
                answer.relatedEvents.maxOfOrNull { it.anomalyScore } ?: 0.0
            )
            val turn = ChatTurn(
                question = question,
                answer = com.aquascope.smriti.llm.AskAnswerCleaner.cleanForDisplay(answer.text),
                evidenceState = answer.evidenceState.name,
                evidenceEventId = answer.relatedEvents.firstOrNull()?.id,
                conversationId = conversationId,
                chart = chart
            )
            turns.add(turn)
            chatHistory.append(turn)
            showTurn(turn)
            scrollToCurrent()

            if (answer.relatedEvents.isNotEmpty()) {
                haloBind.controller().setStateBrief(SmritiLightState.NEW_MEMORY, settle, 500L)
            } else {
                haloBind.controller().setState(settle)
            }
            refreshModelBadge()
            val fromScanData = answer.modelName == SmritiCore.SCAN_ANSWER_SOURCE
            if (!fromScanData && smriti.llmPrefs.enabled && !answer.usedLocalModel && smriti.modelStore.findInstalled() != null) {
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

    private fun stateOf(turn: ChatTurn): EvidenceState =
        runCatching { EvidenceState.valueOf(turn.evidenceState) }.getOrDefault(EvidenceState.UNKNOWN)

    private fun stateLabel(state: EvidenceState): String = ui().getString(
        when (state) {
            EvidenceState.UNKNOWN -> R.string.ask_state_unknown
            EvidenceState.OBSERVED -> R.string.ask_state_observed
            EvidenceState.INFERRED -> R.string.ask_state_inferred
            EvidenceState.POSSIBLE -> R.string.ask_state_possible
            EvidenceState.CONFIRMED -> R.string.ask_state_confirmed
        }
    )

    private fun stateColor(state: EvidenceState): Int = getColor(
        when (state) {
            EvidenceState.UNKNOWN -> R.color.warm_faint
            EvidenceState.INFERRED, EvidenceState.POSSIBLE -> R.color.amber
            else -> R.color.cyan
        }
    )

    /** Saved turns above, the newest in the main card; the hero when nothing is saved. */
    private fun renderChat() {
        places = loadPlaces()
        binding.historyContainer.removeAllViews()
        val latest = turns.lastOrNull()
        if (latest == null) {
            showIdleHero()
            return
        }
        turns.dropLast(1).forEach { addHistoryTurnView(it) }
        showAnswerMode()
        showTurn(latest)
        scrollToCurrent()
    }

    private fun showTurn(turn: ChatTurn) {
        val state = stateOf(turn)
        binding.textAsked.text = turn.question
        binding.textEvidenceState.text = stateLabel(state)
        binding.textEvidenceState.setTextColor(stateColor(state))
        binding.textAnswer.text = turn.answer
        fillChart(binding.chartContainer, turn.chart)
        lastEvidenceEventId = turn.evidenceEventId
        binding.btnWhy.visibility = if (lastEvidenceEventId != null) View.VISIBLE else View.GONE
    }

    private fun loadPlaces(): List<ScanLocation> =
        runCatching { ScanRepository(this).loadLocations() }.getOrDefault(emptyList())

    /** Draws [chart] into [container] from current scan data; hides it when nothing is left to draw. */
    private fun fillChart(container: android.widget.LinearLayout, chart: AskChart?) {
        container.removeAllViews()
        container.visibility = View.GONE
        if (chart == null) return
        val dp = resources.displayMetrics.density
        val r = ui()
        val byId = places.associateBy { it.id }
        fun caption(text: String, topDp: Int) = android.widget.TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(getColor(R.color.warm_faint))
            setPadding(0, (topDp * dp).toInt(), 0, (8 * dp).toInt())
        }
        when (chart.kind) {
            AskChart.Kind.TREND -> {
                val place = chart.placeIds.firstNotNullOfOrNull { byId[it] }
                val scans = place?.scanHistory?.sortedBy { it.timestamp }.orEmpty()
                if (place != null && scans.size >= 2) {
                    container.addView(caption(r.getString(R.string.ask_chart_trend, place.label, scans.size), 16))
                    container.addView(
                        TrendChartView(this).apply {
                            setScores(scans.map { it.timestamp }, scans.map { it.anomalyScore }, place.moistFeatures.isNotEmpty())
                        },
                        android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, (150 * dp).toInt()
                        )
                    )
                }
            }
            AskChart.Kind.COMPARE -> {
                val bars = chart.placeIds.mapNotNull { byId[it] }.mapNotNull { place ->
                    val last = place.scanHistory.maxByOrNull { it.timestamp } ?: return@mapNotNull null
                    PlaceBarChartView.Bar(
                        label = place.label,
                        score = last.anomalyScore,
                        trusted = PlaceReliability.of(place).level == PlaceReliability.Level.RELIABLE,
                        againstDamp = place.moistFeatures.isNotEmpty()
                    )
                }.sortedByDescending { it.score }
                if (bars.isNotEmpty()) {
                    container.addView(caption(r.getString(R.string.ask_chart_compare, AnomalyThresholds.DAMP_SPLIT.toInt()), 16))
                    container.addView(PlaceBarChartView(this).apply { setBars(bars) })
                    if (bars.any { !it.trusted }) container.addView(caption(r.getString(R.string.ask_chart_untrusted), 4))
                }
            }
        }
        container.visibility = if (container.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun addHistoryTurnView(turn: ChatTurn) {
        val dp = resources.displayMetrics.density
        val state = stateOf(turn)
        val block = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, (28 * dp).toInt())
        }
        block.addView(android.widget.TextView(this).apply {
            text = turn.question
            textSize = 15f
            setTextColor(getColor(R.color.warm_muted))
        })
        block.addView(android.widget.TextView(this).apply {
            text = stateLabel(state)
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(stateColor(state))
            setPadding(0, (12 * dp).toInt(), 0, 0)
        })
        block.addView(android.widget.TextView(this).apply {
            text = turn.answer
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            setLineSpacing(0f, 1.3f)
            setTextColor(getColor(R.color.warm_white))
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })
        if (turn.chart != null) {
            block.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                fillChart(this, turn.chart)
            })
        }
        turn.evidenceEventId?.let { id ->
            block.addView(android.widget.TextView(this).apply {
                text = ui().getString(R.string.view_evidence)
                textSize = 14f
                setTextColor(getColor(R.color.cyan))
                minHeight = (48 * dp).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
                setOnClickListener { openEvidence(id) }
            })
        }
        binding.historyContainer.addView(block)
    }

    /** Puts the newest question at the top of the visible area. */
    private fun scrollToCurrent() {
        binding.scrollAnswer.post { binding.scrollAnswer.smoothScrollTo(0, binding.textAsked.top) }
    }

    private fun openEvidence(eventId: String) {
        startActivity(Intent(this, EvidenceActivity::class.java).putExtra("event_id", eventId))
    }

    private fun blockedByAsk(): Boolean {
        if (askInFlight) Toast.makeText(this, ui().getString(R.string.ask_busy), Toast.LENGTH_SHORT).show()
        return askInFlight
    }

    private fun startNewChat() {
        if (blockedByAsk()) return
        tts?.stop()
        conversationId = chatHistory.startNewConversation()
        turns.clear()
        renderChat()
    }

    private fun openChat(id: String) {
        if (blockedByAsk()) return
        tts?.stop()
        chatHistory.openConversation(id)
        conversationId = id
        turns.clear()
        turns.addAll(chatHistory.turnsOf(id))
        renderChat()
    }

    private fun dayLabel(at: Long): String {
        val r = ui()
        val day = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val today = java.util.Calendar.getInstance()
        fun sameDay(a: java.util.Calendar, b: java.util.Calendar) =
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        if (sameDay(day, today)) return r.getString(R.string.ask_today)
        today.add(java.util.Calendar.DAY_OF_YEAR, -1)
        if (sameDay(day, today)) return r.getString(R.string.ask_yesterday)
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, r.configuration.locales[0])
            .format(java.util.Date(at))
    }

    /** Past conversations grouped by day, newest first, with New chat on top. */
    private fun showChatsDialog() {
        val r = ui()
        val dp = resources.displayMetrics.density
        val pad = (24 * dp).toInt()
        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (8 * dp).toInt(), pad, (8 * dp).toInt())
        }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        var dialog: androidx.appcompat.app.AlertDialog? = null

        fun row(title: String, meta: String?, titleColor: Int, onClick: () -> Unit, onLong: (() -> Unit)? = null) =
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                minimumHeight = (48 * dp).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
                isClickable = true
                isFocusable = true
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0)
                ta.recycle()
                addView(android.widget.TextView(context).apply {
                    text = title
                    textSize = 15f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(titleColor)
                })
                if (meta != null) addView(android.widget.TextView(context).apply {
                    text = meta
                    textSize = 12f
                    setTextColor(getColor(R.color.warm_faint))
                })
                setOnClickListener { dialog?.dismiss(); onClick() }
                if (onLong != null) setOnLongClickListener { onLong(); true }
            }

        list.addView(row("+  " + r.getString(R.string.ask_new_chat), null, getColor(R.color.cyan), { startNewChat() }))
        val conversations = chatHistory.conversations()
        if (conversations.isEmpty()) {
            list.addView(android.widget.TextView(this).apply {
                text = r.getString(R.string.ask_chats_empty)
                textSize = 14f
                setTextColor(getColor(R.color.warm_muted))
                setPadding(0, (12 * dp).toInt(), 0, 0)
            })
        } else {
            list.addView(android.widget.TextView(this).apply {
                text = r.getString(R.string.ask_chats_hint)
                textSize = 12f
                setTextColor(getColor(R.color.warm_faint))
                setPadding(0, 0, 0, (4 * dp).toInt())
            })
            val timeFormat = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, r.configuration.locales[0])
            conversations.groupBy { dayLabel(it.lastAt) }.forEach { (day, group) ->
                list.addView(android.widget.TextView(this).apply {
                    text = day
                    textSize = 13f
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    setTextColor(getColor(R.color.warm_muted))
                    setPadding(0, (16 * dp).toInt(), 0, (2 * dp).toInt())
                })
                group.forEach { c ->
                    val meta = r.getString(R.string.ask_chat_meta, timeFormat.format(java.util.Date(c.lastAt)), c.turnCount)
                    val color = getColor(if (c.id == conversationId) R.color.cyan else R.color.warm_white)
                    list.addView(row(c.title, meta, color, { openChat(c.id) }, { confirmDeleteChat(c.id) { dialog?.dismiss(); showChatsDialog() } }))
                }
            }
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(r.getString(R.string.ask_chats_title))
            .setView(scroll)
            .setNegativeButton(r.getString(R.string.ask_close), null)
        if (conversations.isNotEmpty()) {
            builder.setNeutralButton(r.getString(R.string.ask_delete_all)) { _, _ -> confirmDeleteAll() }
        }
        dialog = builder.show()
    }

    private fun confirmDeleteChat(id: String, after: () -> Unit) {
        if (blockedByAsk()) return
        val r = ui()
        MaterialAlertDialogBuilder(this)
            .setMessage(r.getString(R.string.ask_delete_chat_confirm))
            .setPositiveButton(r.getString(R.string.ask_clear_chat_yes)) { _, _ ->
                chatHistory.deleteConversation(id)
                if (id == conversationId) {
                    conversationId = chatHistory.startNewConversation()
                    turns.clear()
                    renderChat()
                }
                after()
            }
            .setNegativeButton(r.getString(R.string.ask_clear_chat_no), null)
            .show()
    }

    private fun confirmDeleteAll() {
        if (blockedByAsk()) return
        val r = ui()
        MaterialAlertDialogBuilder(this)
            .setMessage(r.getString(R.string.ask_clear_chat_confirm))
            .setPositiveButton(r.getString(R.string.ask_clear_chat_yes)) { _, _ ->
                tts?.stop()
                chatHistory.clear()
                conversationId = chatHistory.startNewConversation()
                turns.clear()
                renderChat()
            }
            .setNegativeButton(r.getString(R.string.ask_clear_chat_no), null)
            .show()
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
            binding.btnSpeak.text = ui().getString(R.string.ask_read_aloud)
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
                    binding.btnSpeak.text = ui().getString(R.string.ask_stop_reading)
                }
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = false
                    binding.btnSpeak.text = ui().getString(R.string.ask_read_aloud)
                }
            }
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = false
                    binding.btnSpeak.text = ui().getString(R.string.ask_read_aloud)
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
        binding.btnLanguage.text = name?.let { ui().getString(R.string.ask_language_label, it) }
            ?: ui().getString(R.string.ask_language_auto)
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
                applyUiLanguage()
                refreshModelBadge()
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
