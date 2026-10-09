package com.aquascope.smriti.llm

import com.aquascope.smriti.brain.ScreenOcrSession
import com.aquascope.smriti.model.PhysicalEvent
import com.aquascope.smriti.model.SmritiAnswer
import com.aquascope.smriti.skills.SkillMatch
import com.aquascope.smriti.skills.SkillPromptInjector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [ROLE] Builds grounded Ask prompts for on-device Qwen / Gemma.
 * [WHY] Model must answer USER_QUESTION using only APP_DATA — no invented events or confirmed leaks.
 * [IQOO] Keeps prompts short for ekv1280 MediaPipe windows; multilingual directives supported.
 */
object GroundedPromptBuilder {

    private val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US)

    fun detectLanguage(question: String, selectedPref: String = "auto"): String {
        if (selectedPref.isNotBlank() && selectedPref.lowercase() != "auto") {
            return selectedPref.lowercase()
        }
        val q = question.lowercase(Locale.getDefault())
        if (question.any { it in '\u0900'..'\u097F' }) return "hi"
        val hinglishPattern = Regex("""\b(kya|hai|hain|yeh|ye|mujhe|batao|ho|raha|rahi|kab|shuru|nahi|pehle|aaj|kaun|kaise|pani|paani|kitna|gusal|rasoi|futa|badh|theek|sahi|saboot|praman|sunao|kuch|geela|gila|geeli|nami|seelan|jagah|jagahon|pichla|pichhla|aakhri|akhri|kitne|batao|bataiye|sookha|sukha|kahan|konsi|kaunsi)\b""")
        if (hinglishPattern.containsMatchIn(q)) return "hinglish"
        val spanishPattern = Regex("""\b(qu[eé]|est[aá]|fuga|agua|cu[aá]ndo|hoy|ayer|semana|por\s+qu[eé])\b""")
        if (spanishPattern.containsMatchIn(q)) return "es"
        val frenchPattern = Regex("""\b(fuite|eau|quand|aujourd'hui|hier|pourquoi|est-ce)\b""")
        if (frenchPattern.containsMatchIn(q)) return "fr"
        return "en"
    }

    /**
     * Primary Ask prompt: Qwen fully answers from app data (not a rewrite of rules).
     */
    fun buildAsk(
        question: String,
        ruleAnswer: SmritiAnswer,
        events: List<PhysicalEvent>,
        skillMatch: SkillMatch? = null,
        skillCatalogBlurb: String = "",
        language: String = "auto",
        appContext: String = ""
    ): String {
        val target = detectLanguage(question, language)
        val facts = factsBlock(ruleAnswer, events, target, appContext)
        val skills = skillSections(skillMatch, skillCatalogBlurb)
        val adventure = skillMatch?.skill?.id == "kitchen-adventure"

        if (adventure) {
            return """
You are running an Edge Gallery skill.

$skills

USER_QUESTION: $question

Follow ACTIVE_SKILL instructions exactly. Keep replies short.

ANSWER:
""".trimIndent()
        }

        val (langRule, examples) = getLanguageDirectives(target)
        val hasTool = skillMatch?.toolResult != null
        // Keep preamble short — Qwen MediaPipe ekv1280 has a tiny prompt+reply budget.
        return """
You are SMRITI for AquaScope. Answer USER_QUESTION using ONLY APP_DATA. Speak naturally.
TARGET LANGUAGE: $target
$langRule
$examples
RULES: Use APP_DATA only (HOME_STATUS, GUARDIAN, SCAN_LOCATIONS, SCREEN_OCR, MEMORY_EVENTS, NEURAL_MEMORY, APP_HINT${if (hasTool) ", SKILL_TOOL_RESULT" else ""}). Prefer SCREEN_OCR for screen/clip questions. Never invent events, numbers or a confirmed leak. Only call a scanned place dry or damp if its reliability is RELIABLE; otherwise say its result can't be trusted yet and why. If data is missing, say so. Under 120 words. No markdown, no label names, no file paths.

USER_QUESTION: $question

$skills
$facts

ANSWER:
""".trimIndent()
    }

    /** @deprecated Prefer [buildAsk]. Kept for older call sites / tests. */
    fun build(
        question: String,
        ruleAnswer: SmritiAnswer,
        events: List<PhysicalEvent>,
        skillMatch: SkillMatch? = null,
        skillCatalogBlurb: String = "",
        language: String = "auto"
    ): String = buildAsk(question, ruleAnswer, events, skillMatch, skillCatalogBlurb, language)

    /** @deprecated Prefer [buildAsk]. */
    fun buildChat(
        question: String,
        ruleAnswer: SmritiAnswer,
        events: List<PhysicalEvent>,
        skillMatch: SkillMatch? = null,
        skillCatalogBlurb: String = "",
        language: String = "auto"
    ): String = buildAsk(question, ruleAnswer, events, skillMatch, skillCatalogBlurb, language)

    private fun skillSections(skillMatch: SkillMatch?, catalogBlurb: String): String =
        buildString {
            if (catalogBlurb.isNotBlank()) {
                appendLine(catalogBlurb)
                appendLine()
            }
            val active = SkillPromptInjector.activeBlock(skillMatch)
            if (active.isNotBlank()) {
                appendLine(active)
                appendLine()
            }
        }.trim()

    private fun getLanguageDirectives(target: String): Pair<String, String> {
        return when (target) {
            "hi" -> Pair(
                "Answer fully in natural Hindi (Devanagari). No English sentences.",
                ""
            )
            "hinglish" -> Pair(
                "Answer fully in conversational Hinglish (Hindi in Latin letters).",
                ""
            )
            "es" -> Pair("- You MUST answer COMPLETELY in natural Spanish (Español).", "")
            "fr" -> Pair("- You MUST answer COMPLETELY in natural French (Français).", "")
            "de" -> Pair("- You MUST answer COMPLETELY in natural German (Deutsch).", "")
            else -> Pair("- Respond in clear, calm spoken English.", "")
        }
    }

    private fun factsBlock(
        ruleAnswer: SmritiAnswer,
        events: List<PhysicalEvent>,
        target: String = "en",
        appContext: String = ""
    ): String = buildString {
        appendLine("APP_DATA:")
        appendLine()

        if (appContext.isNotBlank()) {
            appendLine(appContext.trim())
            appendLine()
        }

        appendScreenOcr()

        val stateTranslated = when (target) {
            "hi" -> when (ruleAnswer.evidenceState.name) {
                "OBSERVED" -> "OBSERVED (देखा गया / मिला है)"
                "POSSIBLE" -> "POSSIBLE (संभावित / पक्का नहीं)"
                "CONFIRMED" -> "CONFIRMED (पुष्ट / पक्का लीक)"
                else -> "UNKNOWN (कोई रिकॉर्ड नहीं / अज्ञात)"
            }
            "hinglish" -> when (ruleAnswer.evidenceState.name) {
                "OBSERVED" -> "OBSERVED (record mila hai)"
                "POSSIBLE" -> "POSSIBLE (sambhavit hai, pakka nahi)"
                "CONFIRMED" -> "CONFIRMED (confirmed leak)"
                else -> "UNKNOWN (koi record nahi mila)"
            }
            else -> ruleAnswer.evidenceState.name
        }

        appendLine("EVIDENCE_STATE: $stateTranslated")
        appendLine("APP_HINT (optional retrieval notes — do not copy; answer the user yourself from all APP_DATA):")
        appendLine(ruleAnswer.text.trim().ifBlank { "(none)" })
        appendLine()

        val screenFirst = events.sortedByDescending { e ->
            when {
                e.id == "screen-ocr-latest" -> 3
                e.source.equals("SCREENMIND", true) ||
                    e.source.equals("SMRITI_PLAY", true) ||
                    e.source.equals("OCR", true) ||
                    e.source.equals("NEURAL_CORE", true) ||
                    e.source.equals("GUARDIAN", true) -> 2
                else -> 0
            }
        }

        appendLine("MEMORY_EVENTS (${screenFirst.size}):")
        if (screenFirst.isEmpty()) {
            appendLine("- (none)")
        } else {
            screenFirst.take(6).forEach { e ->
                appendLine(
                    "- ${fmt.format(Date(e.timestampMs))} at ${e.locationLabel} [${e.source}]: " +
                        eventFact(e)
                )
            }
        }

        if (ruleAnswer.suggestedActions.isNotEmpty()) {
            appendLine()
            appendLine("SUGGESTED_ACTIONS: ${ruleAnswer.suggestedActions.joinToString("; ")}")
        }
    }

    private fun StringBuilder.appendScreenOcr() {
        val ocr = ScreenOcrSession.lastScreenOcr.trim()
        appendLine("SCREEN_OCR:")
        if (ocr.isBlank()) {
            appendLine("(none)")
        } else {
            appendLine(ocr.take(800))
        }
        appendLine()
    }

    private fun eventFact(e: PhysicalEvent): String {
        val raw = e.summary.trim()
        val cleaned = AskAnswerCleaner.userFacingSummary(raw)
        val limit = when {
            e.source.equals("SMRITI_PLAY", true) ||
                e.source.equals("SCREENMIND", true) ||
                e.source.equals("OCR", true) ||
                e.source.equals("NEURAL_CORE", true) ||
                e.source.equals("GUARDIAN", true) ||
                raw.contains("Seen on screen", ignoreCase = true) ||
                raw.contains("ScreenMind", ignoreCase = true) ||
                raw.contains("Game/App opened", ignoreCase = true) -> 400
            else -> 180
        }
        return cleaned.take(limit)
    }
}
