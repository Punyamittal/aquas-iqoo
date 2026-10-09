package com.aquascope.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aquascope.R
import com.aquascope.databinding.ActivityLocalModelBinding
import com.aquascope.databinding.ItemModelDownloadBinding
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.llm.DownloadState
import com.aquascope.smriti.llm.LocalModelCatalog
import com.aquascope.smriti.llm.LocalModelDownloadPolicy
import com.aquascope.smriti.llm.OllamaPreferences
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LocalModelActivity : SmritiScreenActivity() {

    private lateinit var binding: ActivityLocalModelBinding
    private lateinit var smriti: SmritiCore
    private lateinit var haloBind: HaloBinding
    private val handler = Handler(Looper.getMainLooper())
    private var previewStep = 0
    private var showHaloDetails = false
    private val downloadCards = linkedMapOf<String, ItemModelDownloadBinding>()

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
        try {
            val file = smriti.modelStore.importFromUri(this, uri)
            finishModelImport(file)
        } catch (t: Throwable) {
            Toast.makeText(this, t.message ?: "Import failed", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalModelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        smriti = SmritiCore.get(this)
        SmritiNav.bind(this, binding.bottomNav, SmritiNav.TAB_SYSTEM)
        haloBind = HaloBinding(this, binding.haloIndicator)
        val halo = haloBind.controller()
        binding.switchHaloEnabled.isChecked = halo.prefs.enabled
        binding.switchHaloNight.isChecked = halo.prefs.nightMode
        binding.switchHaloSim.isChecked = halo.prefs.showSimulator
        when (halo.prefs.brightnessLevel) {
            0 -> binding.toggleBrightness.check(R.id.btnBrightLow)
            2 -> binding.toggleBrightness.check(R.id.btnBrightHigh)
            else -> binding.toggleBrightness.check(R.id.btnBrightMed)
        }

        binding.switchHaloEnabled.setOnCheckedChangeListener { _, checked ->
            halo.prefs.enabled = checked
            halo.refresh()
        }
        binding.switchHaloNight.setOnCheckedChangeListener { _, checked ->
            halo.prefs.nightMode = checked
            halo.refresh()
        }
        binding.switchHaloSim.setOnCheckedChangeListener { _, checked ->
            halo.prefs.showSimulator = checked
            halo.refresh()
        }
        binding.toggleBrightness.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            halo.prefs.brightnessLevel = when (checkedId) {
                R.id.btnBrightLow -> 0
                R.id.btnBrightHigh -> 2
                else -> 1
            }
            halo.refresh()
        }
        binding.btnPreviewHalo.setOnClickListener { previewCycle() }
        binding.btnOpenHaloSettings.setOnClickListener { openRearLightSettings() }
        binding.btnTestHalo.setOnClickListener {
            if (!halo.prefs.enabled || halo.prefs.nightMode) {
                Toast.makeText(this, "Turn Monster Halo ON and disable night mode first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Testing Monster Halo…", Toast.LENGTH_SHORT).show()
            halo.testSolidCyan(this)
            handler.postDelayed({
                refreshHaloStatus()
                Toast.makeText(
                    this@LocalModelActivity,
                    if (halo.lastHardwareOk) "CONNECTED · effect sent (check phone back)"
                    else "Not lighting — open rear light settings and enable effects",
                    Toast.LENGTH_LONG
                ).show()
            }, 900L)
        }

        binding.switchUseLocal.isChecked = smriti.llmPrefs.enabled
        binding.switchUseLocal.setOnCheckedChangeListener { _, checked ->
            smriti.llmPrefs.enabled = checked
            if (!checked) {
                lifecycleScope.launch {
                    withContext(Dispatchers.Default) { smriti.refreshLocalLlm() }
                    bindModelStatus(smriti.modelStatusLight())
                }
            } else {
                refreshUi(reloadModel = !smriti.isLocalLlmReady())
            }
        }
        binding.btnImportModel.setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        binding.btnImportDownloads.setOnClickListener {
            val found = runCatching { smriti.modelStore.findInPublicDownloads() }.getOrNull()
            if (found != null) {
                try {
                    finishModelImport(smriti.modelStore.importFromFile(found))
                } catch (t: Throwable) {
                    Toast.makeText(this, t.message ?: "Import failed", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(
                    this,
                    "Edge Gallery keeps its own copy. Pick gemma3-1b-it-int4.task from Files.",
                    Toast.LENGTH_LONG
                ).show()
                pickModel.launch(arrayOf("*/*"))
            }
        }
        binding.btnReload.setOnClickListener {
            binding.textModelStatus.text = "Loading local model…"
            lifecycleScope.launch {
                val status = withContext(Dispatchers.Default) { smriti.refreshLocalLlm() }
                bindModelStatus(status)
                Toast.makeText(
                    this@LocalModelActivity,
                    if (smriti.isLocalLlmReady()) "Local model ready" else status.message,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        binding.btnRemove.setOnClickListener {
            smriti.modelDownloader.cancel()
            smriti.modelStore.deleteAll()
            lifecycleScope.launch {
                withContext(Dispatchers.Default) { smriti.refreshLocalLlm() }
                refreshUi(reloadModel = false)
                Toast.makeText(this@LocalModelActivity, "Model removed — Ask uses rules only", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnSaveToken.setOnClickListener { saveTokenFromField() }
        binding.btnClearToken.setOnClickListener { clearSavedToken() }
        binding.btnGetToken.setOnClickListener { openUrl(LocalModelCatalog.HF_TOKEN_URL) }
        binding.btnAcceptLicense.setOnClickListener {
            openUrl(LocalModelCatalog.gemma3_1b.licenseUrl ?: LocalModelCatalog.HF_TOKEN_URL)
        }
        binding.inputHfToken.setOnEditorActionListener { _, _, _ ->
            if (!binding.inputHfToken.text.isNullOrBlank()) saveTokenFromField()
            true
        }

        bindOllamaControls()
        bindScreenMindControls()

        inflateDownloadCards()
        inflateSkillToggles()
        observeDownloads()
        refreshUi(reloadModel = false)
        refreshHaloStatus()
        halo.setState(SmritiLightState.NORMAL)
    }

    override fun onResume() {
        super.onResume()
        haloBind.start()
        refreshHaloStatus()
        applyDownloadState(smriti.modelDownloader.state.value)
    }

    override fun onPause() {
        haloBind.stop()
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun inflateDownloadCards() {
        val inflater = LayoutInflater.from(this)
        binding.containerDownloadModels.removeAllViews()
        downloadCards.clear()
        LocalModelCatalog.downloadable.forEach { entry ->
            val card = ItemModelDownloadBinding.inflate(inflater, binding.containerDownloadModels, true)
            downloadCards[entry.id] = card
            card.btnDownload.setOnClickListener { requestDownload(entry) }
            card.btnCancelDownload.setOnClickListener { smriti.modelDownloader.cancel() }
        }
    }

    private fun inflateSkillToggles() {
        binding.containerSkills.removeAllViews()
        binding.switchSkillsMaster.setOnCheckedChangeListener(null)
        binding.switchSkillsMaster.isChecked = smriti.skillPrefs.skillsMasterEnabled
        binding.switchSkillsMaster.setOnCheckedChangeListener { _, checked ->
            smriti.skillPrefs.skillsMasterEnabled = checked
            inflateSkillToggles()
        }
        if (!smriti.skillPrefs.skillsMasterEnabled) return
        smriti.skillCatalog.all().forEach { skill ->
            val row = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
                text = "${skill.name} — ${skill.description.take(72)}"
                isChecked = smriti.skillPrefs.isEnabled(skill.id)
                setTextColor(getColor(R.color.warm_muted))
                setOnCheckedChangeListener { _, checked ->
                    smriti.skillPrefs.setEnabled(skill.id, checked)
                }
            }
            binding.containerSkills.addView(row)
        }
    }

    private fun observeDownloads() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                smriti.modelDownloader.state.collect { state ->
                    applyDownloadState(state)
                    when (state) {
                        is DownloadState.Succeeded -> {
                            val entry = LocalModelCatalog.downloadable.find { it.id == state.entryId }
                            if (entry?.isSpeech == true) {
                                Toast.makeText(
                                    this@LocalModelActivity,
                                    "Installed Whisper: ${state.fileName}",
                                    Toast.LENGTH_LONG
                                ).show()
                                smriti.modelDownloader.consumeTerminal()
                                refreshSpeechStatus()
                                refreshUi(reloadModel = false)
                            } else {
                                smriti.llmPrefs.enabled = true
                                binding.switchUseLocal.isChecked = true
                                val status = withContext(Dispatchers.Default) { smriti.refreshLocalLlm() }
                                val installed = smriti.modelStore.isPresent(state.fileName) ||
                                    smriti.modelStore.findInstalled() != null
                                Toast.makeText(
                                    this@LocalModelActivity,
                                    when {
                                        smriti.isLocalLlmReady() ->
                                            "Installed and ready: ${state.fileName}"
                                        installed ->
                                            "Installed ${state.fileName}. Tap Reload if Ask still uses rules."
                                        else ->
                                            "Downloaded ${state.fileName} — ${status.message}"
                                    },
                                    Toast.LENGTH_LONG
                                ).show()
                                smriti.modelDownloader.consumeTerminal()
                                refreshUi(reloadModel = false)
                            }
                        }
                        is DownloadState.Failed -> {
                            val retry = LocalModelCatalog.downloadable.find { it.id == state.entryId }
                            if (state.needsToken) promptForToken(state.message, retry)
                            else Toast.makeText(this@LocalModelActivity, state.message, Toast.LENGTH_LONG).show()
                            smriti.modelDownloader.consumeTerminal()
                            applyDownloadState(smriti.modelDownloader.state.value)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun requestDownload(entry: LocalModelCatalog.Entry) {
        persistTokenFromField()
        if (entry.requiresAccessToken && !smriti.llmPrefs.hasHfAccessToken()) {
            promptForToken(getString(R.string.local_model_need_token), entry)
            return
        }
        if (smriti.modelStore.isPresent(entry.fileName)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.local_model_replace_title)
                .setMessage(R.string.local_model_replace_body)
                .setPositiveButton(R.string.local_model_redownload) { _, _ ->
                    smriti.modelStore.fileFor(entry.fileName).delete()
                    smriti.modelStore.stagingFile(entry.fileName).delete()
                    startDownload(entry)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        startDownload(entry)
    }

    private fun startDownload(entry: LocalModelCatalog.Entry) {
        persistTokenFromField()
        if (entry.requiresAccessToken && !smriti.llmPrefs.hasHfAccessToken()) {
            promptForToken(getString(R.string.local_model_need_token), entry)
            return
        }
        val error = smriti.modelDownloader.start(entry, smriti.llmPrefs.hfAccessToken)
        if (error != null) {
            if (error.contains("token", ignoreCase = true)) {
                promptForToken(error, entry)
            } else {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
        applyDownloadState(smriti.modelDownloader.state.value)
    }

    private fun applyDownloadState(state: DownloadState) {
        val busyId = (state as? DownloadState.Running)?.entryId
        LocalModelCatalog.downloadable.forEach { entry ->
            val card = downloadCards[entry.id] ?: return@forEach
            val installed = smriti.modelStore.isPresent(entry.fileName)
            val running = state is DownloadState.Running && state.entryId == entry.id
            card.textModelName.text = entry.displayName
            card.textModelMeta.text = buildString {
                append(entry.notes)
                append("\n")
                if (entry.sizeBytes > 0) {
                    append(LocalModelDownloadPolicy.formatBytes(entry.sizeBytes))
                    append(" · ")
                }
                append(String.format(Locale.US, "~%.1f GB RAM", entry.approxRamGb))
                append(
                    when (entry.runtime) {
                        LocalModelCatalog.RuntimeKind.LITERT_LM -> " · LiteRT-LM"
                        LocalModelCatalog.RuntimeKind.SPEECH -> " · Whisper speech"
                        else -> " · MediaPipe"
                    }
                )
                if (entry.requiresAccessToken) append(" · Hugging Face token")
            }
            card.progressDownload.visibility = if (running) View.VISIBLE else View.GONE
            card.textDownloadProgress.visibility = if (running) View.VISIBLE else View.GONE
            card.btnCancelDownload.visibility = if (running) View.VISIBLE else View.GONE
            card.btnDownload.isEnabled = busyId == null
            when {
                running && state is DownloadState.Running -> {
                    val total = state.total
                    val pct = if (total > 0) ((state.bytes * 100) / total).toInt().coerceIn(0, 100) else 0
                    card.progressDownload.isIndeterminate = total <= 0
                    if (total > 0) card.progressDownload.progress = pct
                    card.btnDownload.text = getString(R.string.local_model_downloading)
                    card.textDownloadProgress.text = if (total > 0) {
                        "${LocalModelDownloadPolicy.formatBytes(state.bytes)} / ${LocalModelDownloadPolicy.formatBytes(total)}"
                    } else {
                        LocalModelDownloadPolicy.formatBytes(state.bytes)
                    }
                }
                installed -> {
                    card.btnDownload.text = getString(R.string.local_model_installed)
                    card.textDownloadProgress.visibility = View.VISIBLE
                    val size = smriti.modelStore.fileFor(entry.fileName).length()
                    card.textDownloadProgress.text =
                        "On this phone · ${LocalModelDownloadPolicy.formatBytes(size)}"
                }
                else -> card.btnDownload.text = getString(R.string.local_model_download)
            }
        }
    }

    private fun persistTokenFromField(): Boolean {
        val extracted = LocalModelDownloadPolicy.extractHfToken(binding.inputHfToken.text?.toString())
            ?: return smriti.llmPrefs.hasHfAccessToken()
        smriti.llmPrefs.hfAccessToken = extracted
        binding.inputHfToken.setText("")
        refreshTokenHint()
        return true
    }

    private fun saveTokenFromField() {
        val extracted = LocalModelDownloadPolicy.extractHfToken(binding.inputHfToken.text?.toString())
        if (extracted.isNullOrBlank()) {
            val msg = if (smriti.llmPrefs.hasHfAccessToken()) {
                R.string.local_model_token_already
            } else {
                R.string.local_model_token_empty
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            refreshTokenHint()
            return
        }
        smriti.llmPrefs.hfAccessToken = extracted
        binding.inputHfToken.setText("")
        refreshTokenHint()
        Toast.makeText(
            this,
            getString(R.string.local_model_token_status_saved, smriti.llmPrefs.maskedToken()),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun clearSavedToken() {
        smriti.llmPrefs.clearHfAccessToken()
        binding.inputHfToken.setText("")
        refreshTokenHint()
        Toast.makeText(this, R.string.local_model_token_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun refreshTokenHint() {
        binding.inputHfTokenLayout.hint = getString(R.string.local_model_hf_token_hint)
        binding.textTokenStatus.text = if (smriti.llmPrefs.hasHfAccessToken()) {
            getString(R.string.local_model_token_status_saved, smriti.llmPrefs.maskedToken())
        } else {
            getString(R.string.local_model_token_status_none)
        }
    }

    private fun promptForToken(message: String, retry: LocalModelCatalog.Entry? = null) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = android.widget.EditText(this).apply {
            hint = "hf_..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(pad, pad, pad, pad)
            setText(binding.inputHfToken.text)
        }
        binding.inputHfTokenLayout.requestFocus()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_model_hf_token_hint)
            .setMessage(message)
            .setView(input)
            .setPositiveButton(R.string.local_model_save_token) { _, _ ->
                val extracted = LocalModelDownloadPolicy.extractHfToken(input.text?.toString())
                if (extracted.isNullOrBlank()) {
                    Toast.makeText(this, R.string.local_model_token_empty, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                smriti.llmPrefs.hfAccessToken = extracted
                binding.inputHfToken.setText("")
                refreshTokenHint()
                Toast.makeText(
                    this,
                    getString(R.string.local_model_token_status_saved, smriti.llmPrefs.maskedToken()),
                    Toast.LENGTH_LONG
                ).show()
                if (retry != null) startDownload(retry)
            }
            .setNeutralButton(R.string.local_model_get_token) { _, _ ->
                openUrl(LocalModelCatalog.HF_TOKEN_URL)
            }
            .setNegativeButton(R.string.local_model_accept_license) { _, _ ->
                openUrl(LocalModelCatalog.gemma3_1b.licenseUrl ?: LocalModelCatalog.HF_TOKEN_URL)
            }
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    private fun openRearLightSettings() {
        val attempts = listOf(
            com.aquascope.halo.VivoLightServiceClient.rearLightSettingsIntent(),
            com.aquascope.halo.VivoLightServiceClient.rearAtmosphereSettingsIntent()
        )
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(
            this,
            "Open Settings → search “rear light” / Dynamic Light and turn effects ON",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshHaloStatus() {
        val halo = haloBind.controller()
        binding.textHaloHardware.setOnClickListener {
            showHaloDetails = !showHaloDetails
            refreshHaloStatus()
        }
        binding.textHaloHardware.text = buildString {
            if (halo.hardwareAvailable) {
                append("The rear light is connected. ")
                append("If the camera ring stays dark, turn on effects in OriginOS rear light settings, then test the light again.")
            } else {
                append("The rear light is not available on this phone, so SMRITI shows its state on screen instead.")
            }
            if (showHaloDetails) {
                append("\n\n")
                append(halo.diagnose())
                if (halo.lastStatus.isNotBlank()) {
                    append("\n")
                    append(halo.lastStatus)
                }
                append("\n\nTap to hide technical details")
            } else {
                append("\n\nTap for technical details")
            }
        }
    }

    private fun previewCycle() {
        val halo = haloBind.controller()
        if (!halo.prefs.enabled || halo.prefs.nightMode) {
            Toast.makeText(this, "Enable Monster Halo and turn night mode off", Toast.LENGTH_LONG).show()
            return
        }
        val states = listOf(
            SmritiLightState.NORMAL,
            SmritiLightState.SCANNING,
            SmritiLightState.PROCESSING,
            SmritiLightState.NEW_MEMORY,
            SmritiLightState.ANOMALY,
            SmritiLightState.HIGH_ANOMALY,
            SmritiLightState.PERSISTENT_ANOMALY,
            SmritiLightState.CONFIRMED,
            SmritiLightState.MEMORY_RECALL,
            SmritiLightState.UNKNOWN
        )
        previewStep = 0
        handler.removeCallbacksAndMessages(null)
        fun step() {
            if (previewStep >= states.size) {
                halo.setState(SmritiLightState.NORMAL)
                return
            }
            val s = states[previewStep]
            halo.setState(s)
            previewStep++
            handler.postDelayed({ step() }, 1800L)
        }
        step()
    }

    private fun refreshUi(reloadModel: Boolean = false) {
        if (!reloadModel) {
            bindModelStatus(smriti.modelStatusLight())
            if (smriti.llmPrefs.enabled &&
                !smriti.isLocalLlmReady() &&
                smriti.modelStore.findInstalled() != null
            ) {
                binding.textModelStatus.text = "Connecting local model…"
                lifecycleScope.launch {
                    val status = withContext(Dispatchers.Default) {
                        smriti.ensureLocalLlm()
                        smriti.modelStatusLight()
                    }
                    bindModelStatus(status)
                }
            }
            return
        }
        binding.textModelStatus.text = "Loading local model…"
        lifecycleScope.launch {
            val status = withContext(Dispatchers.Default) { smriti.refreshLocalLlm() }
            bindModelStatus(status)
        }
    }

    private fun finishModelImport(file: java.io.File) {
        smriti.llmPrefs.enabled = true
        binding.switchUseLocal.isChecked = true
        binding.textModelStatus.text = "Loading ${file.name}…"
        lifecycleScope.launch {
            val status = withContext(Dispatchers.Default) { smriti.refreshLocalLlm() }
            val hint = smriti.llmFailureHint()
            val msg = when {
                smriti.isLocalLlmReady() ->
                    "Ready for Ask SMRITI: ${file.name}"
                file.name.endsWith(".litertlm", ignoreCase = true) ->
                    "Copied ${file.name}, but Edge Gallery NPU bundles will not run here. Download Gemma 3 1B (.task) on this System screen."
                !hint.isNullOrBlank() ->
                    "Imported ${file.name}. Load failed: $hint"
                else -> status.message
            }
            Toast.makeText(this@LocalModelActivity, msg, Toast.LENGTH_LONG).show()
            refreshUi(reloadModel = false)
        }
    }

    private fun bindModelStatus(status: com.aquascope.smriti.llm.LocalModelStatus) {
        val sizeMb = if (status.sizeBytes > 0) {
            String.format(Locale.US, "%.0f MB", status.sizeBytes / (1024.0 * 1024.0))
        } else "—"
        val rec = LocalModelCatalog.gemma3_1b
        binding.textModelStatus.text = buildString {
            append(status.message)
            append("\n\n")
            append("Path: ${status.path ?: smriti.modelStore.rootDir().absolutePath}")
            append("\nSize: $sizeMb")
            append("\nRephrase: ${if (smriti.llmPrefs.enabled && smriti.isLocalLlmReady()) "ON" else "OFF (rules only)"}")
            append("\n\nScreen OCR: ML Kit on-device; optional Gemma 4 vision via Ollama (gemma4:e4b).")
            append(" Toggle “Use Gemma 4 for OCR” below after pulling the model on your PC.")
            append("\n\nRecommended for iQOO 15 (12 GB): ${rec.displayName}")
            append("\nFile name: ${rec.fileName}")
        }
        refreshTokenHint()
        refreshSpeechStatus()
        applyDownloadState(smriti.modelDownloader.state.value)
    }

    private fun bindOllamaControls() {
        val prefs = smriti.ollamaPrefs
        // Prefer Qwen (same family as on-device Ask) if still on the old default.
        if (prefs.model == "llama3.2" && !prefs.enabled) {
            prefs.model = OllamaPreferences.DEFAULT_MODEL
        }
        binding.switchOllamaTranslate.isChecked = prefs.enabled
        binding.switchGemmaOcr.isChecked = prefs.ocrEnabled
        binding.inputOllamaUrl.setText(prefs.baseUrl)
        binding.inputOllamaModel.setText(prefs.model)
        inflateOllamaModelChips(prefs.model)
        inflateGemmaOcrChips(prefs.ocrModel)
        binding.switchOllamaTranslate.setOnCheckedChangeListener { _, checked ->
            prefs.enabled = checked
            refreshSpeechStatus()
        }
        binding.switchGemmaOcr.setOnCheckedChangeListener { _, checked ->
            prefs.ocrEnabled = checked
            if (checked && prefs.ocrModel.isBlank()) {
                prefs.ocrModel = OllamaPreferences.DEFAULT_OCR_MODEL
            }
            refreshSpeechStatus()
            Toast.makeText(
                this,
                if (checked) {
                    "Gemma 4 OCR ON — pull ${prefs.ocrModel} on the PC"
                } else {
                    "Gemma OCR off — using ML Kit only"
                },
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.btnSaveOllama.setOnClickListener {
            prefs.baseUrl = binding.inputOllamaUrl.text?.toString().orEmpty()
            prefs.model = binding.inputOllamaModel.text?.toString().orEmpty()
                .ifBlank { OllamaPreferences.DEFAULT_MODEL }
            prefs.ocrEnabled = binding.switchGemmaOcr.isChecked
            binding.inputOllamaUrl.setText(prefs.baseUrl)
            binding.inputOllamaModel.setText(prefs.model)
            inflateOllamaModelChips(prefs.model)
            inflateGemmaOcrChips(prefs.ocrModel)
            Toast.makeText(this, R.string.ollama_saved, Toast.LENGTH_SHORT).show()
            refreshSpeechStatus()
        }
        binding.btnTestOllama.setOnClickListener {
            prefs.baseUrl = binding.inputOllamaUrl.text?.toString().orEmpty()
            prefs.model = binding.inputOllamaModel.text?.toString().orEmpty()
                .ifBlank { OllamaPreferences.DEFAULT_MODEL }
            binding.inputOllamaModel.setText(prefs.model)
            binding.textOllamaStatus.text = "Testing Ollama…"
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { smriti.ollamaClient.ping() }
                binding.textOllamaStatus.text = result.getOrElse {
                    getString(R.string.ollama_unreachable) + "\n${it.message ?: ""}"
                }
                Toast.makeText(
                    this@LocalModelActivity,
                    if (result.isSuccess) result.getOrNull() else getString(R.string.ollama_unreachable),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        refreshSpeechStatus()
    }

    private fun bindScreenMindControls() {
        val prefs = com.aquascope.smriti.brain.screenmind.ScreenMindPreferences(this)
        binding.switchScreenMind.isChecked = prefs.enabled
        binding.switchScreenMindPc.isChecked = prefs.pcEnabled
        binding.inputScreenMindPcUrl.setText(prefs.pcBaseUrl)
        binding.switchScreenMind.setOnCheckedChangeListener { _, checked ->
            prefs.enabled = checked
            Toast.makeText(
                this,
                if (checked) "ScreenMind ON" else "ScreenMind OFF",
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.switchScreenMindPc.setOnCheckedChangeListener { _, checked ->
            prefs.pcEnabled = checked
            prefs.pcBaseUrl = binding.inputScreenMindPcUrl.text?.toString().orEmpty()
            binding.inputScreenMindPcUrl.setText(prefs.pcBaseUrl)
            Toast.makeText(
                this,
                if (checked) "PC ScreenMind ON · ${prefs.pcBaseUrl}" else "PC ScreenMind OFF",
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.inputScreenMindPcUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefs.pcBaseUrl = binding.inputScreenMindPcUrl.text?.toString().orEmpty()
                binding.inputScreenMindPcUrl.setText(prefs.pcBaseUrl)
            }
        }
    }

    private fun inflateOllamaModelChips(selectedTag: String) {
        val group = binding.chipOllamaModels
        group.removeAllViews()
        OllamaPreferences.suggestedModels.forEach { sug ->
            val chip = Chip(this).apply {
                text = if (sug.matchesOnDeviceAsk) "${sug.label} · Ask" else sug.label
                isCheckable = true
                isChecked = sug.ollamaTag.equals(selectedTag, ignoreCase = true)
                setOnClickListener {
                    binding.inputOllamaModel.setText(sug.ollamaTag)
                    smriti.ollamaPrefs.model = sug.ollamaTag
                    inflateOllamaModelChips(sug.ollamaTag)
                    refreshSpeechStatus()
                    Toast.makeText(
                        this@LocalModelActivity,
                        "Ollama translate → ${sug.ollamaTag}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            group.addView(chip)
        }
    }

    private fun inflateGemmaOcrChips(selectedTag: String) {
        val group = binding.chipGemmaOcrModels
        group.removeAllViews()
        OllamaPreferences.suggestedOcrModels.forEach { sug ->
            val chip = Chip(this).apply {
                text = sug.label
                isCheckable = true
                isChecked = sug.ollamaTag.equals(selectedTag, ignoreCase = true)
                setOnClickListener {
                    smriti.ollamaPrefs.ocrModel = sug.ollamaTag
                    smriti.ollamaPrefs.ocrEnabled = true
                    binding.switchGemmaOcr.isChecked = true
                    inflateGemmaOcrChips(sug.ollamaTag)
                    refreshSpeechStatus()
                    Toast.makeText(
                        this@LocalModelActivity,
                        "OCR model → ${sug.ollamaTag}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            group.addView(chip)
        }
    }

    private fun refreshSpeechStatus() {
        val whisper = smriti.modelStore.whisperStatus()
        val prefs = smriti.ollamaPrefs
        binding.textWhisperStatus.text = whisper.message
        binding.textOllamaStatus.text = buildString {
            append(if (prefs.enabled) "Translate: ON" else "Translate: OFF")
            append(" · ")
            append(prefs.model)
            append("\n")
            append(if (prefs.ocrEnabled) "Gemma OCR: ON" else "Gemma OCR: OFF (ML Kit)")
            append(" · ")
            append(prefs.ocrModel)
            append("\n")
            append(prefs.baseUrl)
        }
    }
}
