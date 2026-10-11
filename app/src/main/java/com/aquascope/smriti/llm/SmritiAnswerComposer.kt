package com.aquascope.smriti.llm

import android.util.Log
import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.QueryIntent
import com.aquascope.smriti.model.SmritiAnswer
import com.aquascope.smriti.skills.SkillMatch

/**
 * [ROLE] Ask answer composer — Qwen-primary when MediaPipe model is ready.
 * [WHY] Spoken replies must stay grounded in APP_DATA (home, scans, Guardian, OCR, memory).
 * [IQOO] Soft-fail to rules/skills only if model off, missing, or hard-fails — never invent confirmed leaks.
 */
class SmritiAnswerComposer(
    private val llm: LocalLlmEngine?,
    private val prefs: LocalLlmPreferences
) {

    fun compose(
        question: String,
        ruleAnswer: SmritiAnswer,
        intent: QueryIntent = QueryIntent.GENERAL,
        skillMatch: SkillMatch? = null,
        skillCatalogBlurb: String = "",
        language: String = prefs.targetLanguage,
        appContext: String = ""
    ): SmritiAnswer {
        val target = GroundedPromptBuilder.detectLanguage(question, language)
        val baseRule = ruleAnswer.copy(text = AskAnswerCleaner.cleanForDisplay(ruleAnswer.text))
        val cleanedRule = localizeFallback(baseRule, target)
        // Rules answer in the selected language: model translation first, canned wording if that fails.
        val ruleReply: () -> SmritiAnswer = {
            val alreadyThere = baseRule.language == target ||
                (target == "en" && (baseRule.language == null || baseRule.language == "en"))
            when {
                alreadyThere -> baseRule
                else -> translate(baseRule.text, target)?.let { baseRule.copy(text = it, language = target) }
                    ?: cleanedRule
            }.copy(usedLocalModel = false, modelName = null)
        }

        // Pure tool skills (hash / email / wikipedia) can answer without a model when LLM is off.
        if (skillMatch != null &&
            !skillMatch.toolResult.isNullOrBlank() &&
            (llm == null || !llm.isReady || !prefs.enabled)
        ) {
            return cleanedRule.copy(
                text = AskAnswerCleaner.cleanForDisplay(skillMatch.toolResult!!),
                usedLocalModel = false,
                modelName = "skill:${skillMatch.skill.name}"
            )
        }

        if (!prefs.enabled || llm == null || !llm.isReady) {
            return cleanedRule.copy(usedLocalModel = false, modelName = null)
        }

        val prompt = GroundedPromptBuilder.buildAsk(
            question = question,
            ruleAnswer = baseRule,
            events = baseRule.relatedEvents,
            skillMatch = skillMatch,
            skillCatalogBlurb = skillCatalogBlurb,
            language = target,
            appContext = appContext
        )

        val polished = try {
            val raw = llm.generate(prompt)
            raw?.let { BpeDecoder.cleanModelOutput(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Qwen generate failed: ${t.message}")
            null
        } ?: return if (!skillMatch?.toolResult.isNullOrBlank()) {
            cleanedRule.copy(
                text = AskAnswerCleaner.cleanForDisplay(skillMatch!!.toolResult!!),
                usedLocalModel = false,
                modelName = "skill:${skillMatch!!.skill.name}"
            )
        } else {
            ruleReply()
        }

        val cleaned = AskAnswerCleaner.cleanModelOutput(polished)

        // Hard reject only: blank / clear prompt scaffolding leak.
        if (cleaned.isBlank() || AskAnswerCleaner.looksLikePromptLeak(cleaned)) {
            Log.w(TAG, "discarded model reply (blank/leak), len=${cleaned.length}")
            return if (!skillMatch?.toolResult.isNullOrBlank()) {
                cleanedRule.copy(
                    text = AskAnswerCleaner.cleanForDisplay(skillMatch!!.toolResult!!),
                    usedLocalModel = false,
                    modelName = "skill:${skillMatch!!.skill.name}"
                )
            } else {
                ruleReply()
            }
        }

        if (com.aquascope.smriti.engine.AquaScopeTopic.isScreenTopic(cleaned)) {
            Log.w(TAG, "discarded model reply (off AquaScope topic), preview=${cleaned.take(80)}")
            return ruleReply()
        }

        if (!passesGroundingCheck(cleaned, baseRule, intent)) {
            Log.w(TAG, "discarded model reply (grounding), preview=${cleaned.take(80)}")
            return ruleReply()
        }

        if (!numbersGrounded(cleaned, prompt + "\n" + question + "\n" + skillMatch?.toolResult.orEmpty())) {
            Log.w(TAG, "discarded model reply (number not in app data), preview=${cleaned.take(80)}")
            return ruleReply()
        }

        val inTarget = if (matchesLanguage(cleaned, target)) {
            cleaned
        } else {
            Log.w(TAG, "model reply not in $target, translating: ${cleaned.take(80)}")
            translate(cleaned, target) ?: return ruleReply()
        }

        // Prefer keeping a clean Qwen reply for normal Ask questions.
        return baseRule.copy(
            text = inTarget,
            usedLocalModel = true,
            modelName = llm.modelLabel,
            language = target
        )
    }

    /**
     * Translates [text] into [target] with the local model. Returns null when no model is ready
     * or the result is not in [target], changes numbers, or leaks the prompt.
     */
    fun translate(text: String, target: String): String? {
        val engine = llm ?: return null
        if (!prefs.enabled) return null
        return AnswerLanguage.translate(engine, text, target).also {
            if (it == null) Log.w(TAG, "no usable $target translation for: ${text.take(80)}")
        }
    }

    companion object {
        private const val TAG = "SmritiAnswerComposer"

        fun matchesLanguage(reply: String, target: String): Boolean = AnswerLanguage.matches(reply, target)

        /**
         * Hindi / Hinglish wrapper for English rules answers when no model can translate them.
         * Keeps the original details (dates, places, scores) below a short localized summary.
         */
        fun localizeFallback(answer: SmritiAnswer, target: String): SmritiAnswer {
            val lang = target.lowercase()
            if (lang != "hi" && lang != "hinglish") return answer
            if (answer.language == lang) return answer
            val n = answer.relatedEvents.size
            val hi = lang == "hi"
            val summary = when (answer.evidenceState) {
                EvidenceState.UNKNOWN ->
                    if (hi) "मुझे मेमोरी में इसका कोई रिकॉर्ड नहीं मिला।" else "Mujhe memory me iska koi record nahi mila."
                EvidenceState.OBSERVED -> when {
                    n > 0 && hi -> "मेमोरी में इससे जुड़े $n रिकॉर्ड मिले हैं। किसी लीक की पुष्टि नहीं हुई है।"
                    n > 0 -> "Memory me isse jude $n records mile hain. Kisi leak ki pushti nahi hui hai."
                    hi -> "मेमोरी में इससे जुड़ी जानकारी मिली है।"
                    else -> "Memory me isse judi jaankari mili hai."
                }
                EvidenceState.POSSIBLE, EvidenceState.INFERRED ->
                    if (hi) "असामान्य आवाज़ का संकेत मिला है, पर लीक की पुष्टि नहीं हुई है। पाइप और फिटिंग्स की जांच करें।"
                    else "Abnormal awaaz ka signal mila hai, par leak ki pushti nahi hui hai. Pipe aur fittings check karein."
                EvidenceState.CONFIRMED ->
                    if (hi) "पुष्ट पानी के रिसाव का रिकॉर्ड मौजूद है। कृपया तुरंत जांच करें।"
                    else "Confirmed leak ka record maujood hai. Kripya turant check karein."
            }
            val details = answer.text.trim()
            val text = if (details.isBlank()) summary else {
                summary + "\n\n" + (if (hi) "विवरण (अंग्रेज़ी में):\n" else "Details (English me):\n") + details
            }
            return answer.copy(text = text, language = lang)
        }

        /**
         * Soft guard: reject answers that assert confirmation when evidence is weaker,
         * or invent "confirmed leak" language the rules never used.
         */
        fun passesGroundingCheck(
            modelText: String,
            ruleAnswer: SmritiAnswer,
            intent: QueryIntent = QueryIntent.GENERAL
        ): Boolean {
            val t = modelText.lowercase()
            if (t.isBlank()) return false
            if (AskAnswerCleaner.looksLikePromptLeak(modelText)) return false

            val modelClaimsConfirmedLeak = positiveLeakClaim(t)
            val rulesAllowConfirmed =
                ruleAnswer.evidenceState == EvidenceState.CONFIRMED ||
                    positiveLeakClaim(ruleAnswer.text.lowercase())

            if (modelClaimsConfirmedLeak && !rulesAllowConfirmed) return false

            val maxLen = if (intent == QueryIntent.GENERAL) {
                1_800
            } else {
                maxOf(ruleAnswer.text.length * 5 + 280, 720)
            }
            if (modelText.length > maxLen) return false

            return true
        }

        /**
         * Every number of two or more digits in [reply] must also appear in [sources] (the prompt).
         * Small models invent scores and counts; single digits are allowed as ordinary wording.
         */
        fun numbersGrounded(reply: String, sources: String): Boolean {
            val allowed = numberTokens(sources).toSet()
            return numberTokens(reply).all { it.length < 2 || it in allowed }
        }

        private val NUMBER = Regex("""\d+(?:[.,]\d+)*""")

        private fun numberTokens(text: String): List<String> {
            val ascii = buildString(text.length) {
                text.forEach { c -> append(if (c in '\u0966'..'\u096F') '0' + (c - '\u0966') else c) }
            }
            return NUMBER.findAll(ascii).map { m ->
                m.value.replace(",", "").trimEnd('.').let { v ->
                    if (v.contains('.')) v.trimEnd('0').trimEnd('.') else v
                }
            }.toList()
        }

        /** True only for affirmative confirmed-leak language (ignores hedging / "possible"). */
        private fun positiveLeakClaim(text: String): Boolean {
            val t = text.lowercase()
            if (Regex("""\b(not|no|never|without)\b[^.?]{0,24}\b(confirmed\s+)?leak\b""").containsMatchIn(t)) {
                return false
            }
            if (t.contains("unconfirmed") || t.contains("not confirmed") || t.contains("not a confirmed") ||
                t.contains("possible") || t.contains("may be") || t.contains("might be")
            ) {
                val stripped = t
                    .replace("not a confirmed leak", " ")
                    .replace("not confirmed", " ")
                    .replace("unconfirmed", " ")
                    .replace("no confirmed leak", " ")
                return Regex("""\b(confirmed\s+leak|leak\s+is\s+confirmed|definitely\s+(a\s+|the\s+)?leak)\b""")
                    .containsMatchIn(stripped)
            }
            // Do NOT treat plain "there is a leak" as confirmation — Qwen often uses that for POSSIBLE.
            return Regex("""\b(confirmed\s+leak|leak\s+is\s+confirmed|definitely\s+(a\s+|the\s+)?leak)\b""")
                .containsMatchIn(t)
        }
    }
}
