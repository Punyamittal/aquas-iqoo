package com.aquascope.smriti.llm

/**
 * [ROLE] Strip prompt scaffolding from model replies for spoken Ask UI.
 * [WHY] Users see English answers; forensic labels stay on Evidence screen.
 * [IQOO] Soft leak detection — do not discard valid Qwen sentences.
 */
object AskAnswerCleaner {

    private val PROMPT_LABELS = listOf(
        "EVIDENCE_STATE",
        "CANONICAL_ANSWER",
        "MEMORY_EVENTS",
        "SCREEN_OCR",
        "USER_QUESTION",
        "HARD RULES",
        "SUGGESTED_ACTIONS",
        "You are SMRITI"
    )

    fun looksLikePromptLeak(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false
        // Need several scaffold markers — a single casual word must not kill Qwen replies.
        val hits = PROMPT_LABELS.count { label ->
            t.contains(label, ignoreCase = true)
        }
        if (hits >= 2) return true
        if (t.contains("HARD RULES", ignoreCase = true)) return true
        if (t.contains("CANONICAL_ANSWER:", ignoreCase = true)) return true
        if (t.contains("USER_QUESTION:", ignoreCase = true) && t.contains("SCREEN_OCR:", ignoreCase = true)) {
            return true
        }
        // Echoed system prompt as the whole answer.
        if (t.startsWith("You are SMRITI", ignoreCase = true) && t.length < 220) return true
        return false
    }

    fun cleanModelOutput(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("<start_of_turn>\\w*\\s*"), "")
        t = t.replace("<end_of_turn>", "")
        t = t.replace(Regex("<\\|im_start\\|>\\w*\\s*"), "")
        t = t.replace("<|im_end|>", "")
        t = t.replace(Regex("(?i)^ANSWER:\\s*"), "")
        if (looksLikePromptLeak(t) && t.contains("ANSWER:", ignoreCase = true)) {
            t = t.substringAfterLast("ANSWER:", t).trim()
        }
        t = t.lineSequence()
            .filterNot { isScaffoldLine(it) }
            .joinToString("\n")
        return cleanForDisplay(t)
    }

    fun cleanForDisplay(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("(?i)Recording saved:\\s*\\S+"), "a clip is saved on this phone")
        t = t.replace(Regex("(?i)OCR file[=:]\\s*\\S+"), "")
        t = t.replace(Regex("(?i)(/data/|/storage/|files/smriti)[^\\s]+"), "")
        t = t.replace(Regex("(?i)\\bEvidence state:\\s*[A-Z_]+\\.?"), "")
        t = t.replace(Regex("(?m)^\\s*EVIDENCE_STATE:.*$"), "")
        t = t.lineSequence().joinToString("\n") { line ->
            if (line.contains("Guardian rms=", ignoreCase = true) ||
                (line.contains("Guardian") && line.contains("peak="))
            ) {
                spokenGuardian(line)
            } else {
                line.replace(Regex("""\bscore=\d+(\.\d+)?\b"""), "")
                    .replace(Regex("""Features: resonance[^.\n]*\.?"""), "")
            }
        }
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        t = t.replace(Regex("\\n{3,}"), "\n\n")
        return t.trim()
    }

    fun userFacingSummary(raw: String): String {
        val cleaned = cleanForDisplay(raw)
        return cleaned.ifBlank { "an observation on this phone" }
    }

    fun spokenGuardian(raw: String): String {
        if (raw.contains("Guardian heard", ignoreCase = true) ||
            raw.contains("IR pulse", ignoreCase = true) ||
            raw.contains("IR armed", ignoreCase = true) ||
            raw.contains("Guardian listening", ignoreCase = true)
        ) {
            return raw
                .replace(Regex("""\bloudness\s+\w+,\s*bands high/mid/low\s+\d+%/\d+%/\d+%"""), "")
                .replace(Regex("[ \\t]{2,}"), " ")
                .trim()
        }
        val counts = Regex("""([A-Za-z][A-Za-z0-9_]*)×(\d+)""").findAll(raw).map { m ->
            val name = m.groupValues[1].replace('_', ' ')
            val n = m.groupValues[2]
            if (n == "1") name else "$name $n times"
        }.toList()
        val ir = raw.contains("last IR", ignoreCase = true) ||
            raw.contains("IR command", ignoreCase = true) ||
            raw.contains("IR pulse", ignoreCase = true)
        return buildString {
            append("Guardian was listening")
            if (counts.isNotEmpty()) {
                append(" and noted ")
                append(counts.joinToString(", "))
            }
            if (ir) append(". An IR command was involved")
            append(".")
        }
    }

    private fun isScaffoldLine(line: String): Boolean {
        val l = line.trim()
        if (l.isEmpty()) return false
        val u = l.uppercase()
        if (PROMPT_LABELS.any { u.startsWith(it) || u.startsWith("- $it") }) return true
        if (l.startsWith("You are SMRITI")) return true
        if (l.startsWith("- ") && l.contains("|") && l.contains("score=", ignoreCase = true)) return true
        if (l.startsWith("- (none)")) return true
        return false
    }
}
