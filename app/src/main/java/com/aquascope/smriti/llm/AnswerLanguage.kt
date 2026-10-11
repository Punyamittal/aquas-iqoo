package com.aquascope.smriti.llm

/**
 * Checks which language an answer is written in and builds translation prompts,
 * so Ask replies come out in the language the user selected.
 */
object AnswerLanguage {

    /** Hindi answers keep English place names, so they are not 100% Devanagari. */
    private const val HINDI_SHARE = 0.4

    private val COMMON_WORDS = mapOf(
        "en" to setOf("the", "is", "are", "and", "to", "of", "in", "that", "it", "for", "on", "with", "was", "this", "you", "your", "not", "be", "has", "have"),
        "hinglish" to setOf("hai", "hain", "ka", "ki", "ke", "nahi", "aur", "kya", "yeh", "ye", "mein", "me", "se", "ko", "tha", "thi", "par", "abhi", "kar", "aap", "aapka", "aapki", "lagta", "raha", "rahi"),
        "es" to setOf("el", "la", "los", "las", "es", "y", "de", "que", "en", "un", "una", "por", "para", "con", "no", "su", "está", "son", "del", "lugar"),
        "fr" to setOf("le", "la", "les", "est", "et", "des", "une", "un", "du", "que", "pour", "dans", "pas", "sur", "vous", "votre", "sont", "il", "ce", "au"),
        "de" to setOf("der", "die", "das", "ist", "und", "nicht", "ein", "eine", "zu", "mit", "den", "auf", "es", "sie", "ihr", "ihre", "sind", "wird", "noch", "im")
    )

    fun displayName(target: String): String = when (target.lowercase()) {
        "hi" -> "Hindi (Devanagari script)"
        "hinglish" -> "Hinglish (Hindi written in Latin letters)"
        "es" -> "Spanish"
        "fr" -> "French"
        "de" -> "German"
        else -> "English"
    }

    private fun isDevanagari(c: Char) = c in '\u0900'..'\u097F'

    /** Share of script characters that are Devanagari; vowel signs are not letters, so count the whole block. */
    private fun devanagariShare(text: String): Double {
        val script = text.filter { it.isLetter() || isDevanagari(it) }
        if (script.isEmpty()) return 0.0
        return script.count { isDevanagari(it) }.toDouble() / script.length
    }

    /** Best guess at the language of [text], or null when it is too short or unclear. */
    fun detect(text: String): String? {
        if (devanagariShare(text) >= HINDI_SHARE) return "hi"
        val words = Regex("""[\p{L}']+""").findAll(text.lowercase()).map { it.value }.toList()
        if (words.size < 4) return null
        val scores = COMMON_WORDS.mapValues { (_, set) -> words.count { it in set } }
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked[0]
        if (best.value < 2 || best.value == ranked[1].value) return null
        return best.key
    }

    /** True when [reply] reads as [target]; short or unclear replies are given the benefit of the doubt. */
    fun matches(reply: String, target: String): Boolean {
        val lang = target.lowercase()
        if (reply.count { it.isLetter() } < 8) return true
        val share = devanagariShare(reply)
        if (lang == "hi") return share >= HINDI_SHARE
        if (share >= 0.2) return false
        val detected = detect(reply) ?: return true
        return detected == lang || (lang == "auto")
    }

    /**
     * Translates [text] into [target] with [engine]. Null when the engine is not ready or the
     * result is blank, leaks the prompt, is in the wrong language, or changes any number.
     */
    fun translate(engine: LocalLlmEngine, text: String, target: String): String? {
        if (!engine.isReady || text.isBlank()) return null
        val raw = runCatching { engine.generate(translationPrompt(text, target)) }.getOrNull() ?: return null
        val out = AskAnswerCleaner.cleanModelOutput(BpeDecoder.cleanModelOutput(raw))
        return out.takeIf {
            it.isNotBlank() &&
                !AskAnswerCleaner.looksLikePromptLeak(it) &&
                matches(it, target) &&
                SmritiAnswerComposer.numbersGrounded(it, text)
        }
    }

    fun translationPrompt(text: String, target: String): String = """
Translate the text below into ${displayName(target)}.
Keep place names, numbers and percentages exactly as they are. Do not add or remove information.
Output only the translation.

TEXT:
${text.trim()}

TRANSLATION:
""".trimIndent()
}
