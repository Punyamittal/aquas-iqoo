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
        val cleanedRule = fallbackAnswer(
            ruleAnswer.copy(text = AskAnswerCleaner.cleanForDisplay(ruleAnswer.text)),
            target
        )

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
            ruleAnswer = cleanedRule,
            events = cleanedRule.relatedEvents,
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
            cleanedRule.copy(usedLocalModel = false, modelName = null)
        }

        val cleaned = AskAnswerCleaner.cleanModelOutput(polished)
        val hasScreenOcr = com.aquascope.smriti.brain.ScreenOcrSession.lastScreenOcr.isNotBlank()

        // Hard reject only: blank / clear prompt scaffolding leak.
        if (cleaned.isBlank() || AskAnswerCleaner.looksLikePromptLeak(cleaned)) {
            Log.w(TAG, "discarded model reply (blank/leak), len=${cleaned.length}")
            return if (!skillMatch?.toolResult.isNullOrBlank() && !hasScreenOcr) {
                cleanedRule.copy(
                    text = AskAnswerCleaner.cleanForDisplay(skillMatch!!.toolResult!!),
                    usedLocalModel = false,
                    modelName = "skill:${skillMatch!!.skill.name}"
                )
            } else {
                cleanedRule.copy(usedLocalModel = false, modelName = null)
            }
        }

        if (!passesGroundingCheck(cleaned, cleanedRule, intent)) {
            Log.w(TAG, "discarded model reply (grounding), preview=${cleaned.take(80)}")
            return cleanedRule.copy(usedLocalModel = false, modelName = null)
        }

        // Soft screen check: log only — paraphrases often lack OCR tokens but are still useful.
        val screenQa = looksLikeScreenOrMemoryQa(question, cleanedRule)
        if (hasScreenOcr && screenQa && !overlapsScreenFacts(cleaned, cleanedRule)) {
            Log.w(TAG, "screen overlap weak, keeping Qwen reply: ${cleaned.take(80)}")
        }

        // Prefer keeping a clean Qwen reply for normal Ask questions.
        return cleanedRule.copy(
            text = cleaned,
            usedLocalModel = true,
            modelName = llm.modelLabel
        )
    }

    private fun fallbackAnswer(ruleAnswer: SmritiAnswer, target: String): SmritiAnswer {
        if (target != "hi" && target != "hinglish") return ruleAnswer
        val translated = when (target) {
            "hi" -> when (ruleAnswer.evidenceState) {
                EvidenceState.UNKNOWN -> "मुझे मेमोरी में इसका कोई रिकॉर्ड नहीं मिला है। आप एक नया एक्वास्कोप स्कैन चला सकते हैं।"
                EvidenceState.OBSERVED -> "मेमोरी में असामान्य आवाज़ के रिकॉर्ड मिले हैं (${ruleAnswer.relatedEvents.size} घटनाएँ)। अभी किसी पक्के लीक की पुष्टि नहीं हुई है, कृपया पाइप की जांच करें।"
                EvidenceState.POSSIBLE, EvidenceState.INFERRED -> "संभावित असामान्य बहाव या रिसाव का संकेत मिला है। पाइप और फिटिंग्स की जांच करने की सलाह दी जाती है।"
                EvidenceState.CONFIRMED -> "पुष्ट पानी के रिसाव का रिकॉर्ड मौजूद है। कृपया तुरंत समस्या को ठीक करें।"
                else -> "मेमोरी में असामान्य आवाज़ के रिकॉर्ड मिले हैं। कृपया एक बार जांच कर लें।"
            }
            "hinglish" -> when (ruleAnswer.evidenceState) {
                EvidenceState.UNKNOWN -> "Mujhe memory me iska koi record nahi mila hai. Aap ek naya AquaScope scan chala sakte hain."
                EvidenceState.OBSERVED -> "Memory me abnormal sound ke records mile hain (${ruleAnswer.relatedEvents.size} events). Abhi kisi pakke leak ki pushti nahi hui hai, kripya pipe check karein."
                EvidenceState.POSSIBLE, EvidenceState.INFERRED -> "Sambhavit abnormal flow ya leak ka signal mila hai. Pipe aur fittings check karne ki salaah di jaati hai."
                EvidenceState.CONFIRMED -> "Confirmed leak ka record maujood hai. Kripya turant inspect karein."
                else -> "Memory me abnormal sound ke records mile hain. Kripya check kar lijiye."
            }
            else -> ruleAnswer.text
        }
        return ruleAnswer.copy(text = translated)
    }

    companion object {
        private const val TAG = "SmritiAnswerComposer"

        private fun looksLikeScreenOrMemoryQa(question: String, ruleAnswer: SmritiAnswer): Boolean {
            val q = question.lowercase()
            if (q.contains("screen") || q.contains("clip") || q.contains("recording") ||
                q.contains("ocr") || q.contains("what was") || q.contains("what did") ||
                q.contains("what game") || q.contains("which game") ||
                (q.contains("game") && q.contains("open"))
            ) {
                return true
            }
            return ruleAnswer.relatedEvents.any {
                it.source.equals("SMRITI_PLAY", true) ||
                    it.source.equals("SCREENMIND", true) ||
                    it.source.equals("OCR", true) ||
                    it.source.equals("NEURAL_CORE", true) ||
                    it.summary.contains("Seen on screen", ignoreCase = true) ||
                    it.summary.contains("ScreenMind", ignoreCase = true) ||
                    it.summary.contains("Game/App opened", ignoreCase = true)
            }
        }

        /** Require at least one token from SCREEN_OCR / related screen summaries. */
        private fun overlapsScreenFacts(modelText: String, ruleAnswer: SmritiAnswer): Boolean {
            val ocr = com.aquascope.smriti.brain.ScreenOcrSession.lastScreenOcr
            val hay = buildString {
                append(ocr)
                append('\n')
                append(ruleAnswer.text)
                ruleAnswer.relatedEvents.forEach { append('\n').append(it.summary) }
            }.lowercase()
            if (hay.isBlank()) return true
            val tokens = Regex("[a-z0-9]{3,}")
                .findAll(modelText.lowercase())
                .map { it.value }
                .filterNot {
                    it in setOf(
                        "the", "and", "you", "your", "from", "with", "that", "this", "have",
                        "was", "were", "are", "for", "not", "did", "what", "game", "app",
                        "opened", "screen", "clip", "memory", "latest", "recording"
                    )
                }
                .distinct()
                .take(24)
                .toList()
            if (tokens.isEmpty()) return true
            val hits = tokens.count { hay.contains(it) }
            return hits >= 1 || modelText.lowercase().contains("don't have") ||
                modelText.lowercase().contains("do not have") ||
                modelText.lowercase().contains("no record")
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
