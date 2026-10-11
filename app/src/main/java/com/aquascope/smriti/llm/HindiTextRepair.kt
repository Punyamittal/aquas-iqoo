package com.aquascope.smriti.llm

import java.text.Normalizer

/**
 * Rule-based repair for broken Hindi in small-model output: byte-garbled Devanagari,
 * split or orphaned vowel signs, stray zero-width characters, danda punctuation and
 * common misspellings. Text without Devanagari passes through unchanged.
 */
object HindiTextRepair {

    private fun isDevanagari(c: Char) = c in '\u0900'..'\u097F'

    /** Vowel signs, nukta, halant and nasal/visarga marks — they can never start a word. */
    private fun isCombining(c: Char) =
        c in '\u0900'..'\u0903' || c in '\u093A'..'\u094F' || c in '\u0951'..'\u0957' || c in '\u0962'..'\u0963'

    private fun isVowelSign(c: Char) = c in '\u093E'..'\u094C'

    private const val COMBINING = "\u0900-\u0903\u093A-\u094F\u0951-\u0957\u0962\u0963"
    private const val LETTER = "\u0904-\u0939\u0958-\u0961\u0972-\u097F"

    /** Common misspellings and non-standard forms produced by the model, whole words only. */
    private val WORD_FIXES = mapOf(
        "नही" to "नहीं", "नहि" to "नहीं", "नहिं" to "नहीं", "नहीँ" to "नहीं",
        "मे" to "में", "मेँ" to "में", "हे" to "है", "हैे" to "है",
        "मैने" to "मैंने", "अभि" to "अभी", "भि" to "भी", "कोइ" to "कोई",
        "हुवा" to "हुआ", "किजिए" to "कीजिए", "किजिये" to "कीजिए",
        "कृप्या" to "कृपया", "क्रप्या" to "कृपया", "क्रपया" to "कृपया",
        "दिवार" to "दीवार", "दिवारों" to "दीवारों", "दिवारें" to "दीवारें",
        "गिली" to "गीली", "गिला" to "गीला", "गिले" to "गीले", "नमि" to "नमी",
        "सुखा" to "सूखा", "सुखी" to "सूखी", "सुखे" to "सूखे", "पानि" to "पानी",
        "लिक" to "लीक", "लिकेज" to "लीकेज", "लीकेज़" to "लीकेज",
        "स्केन" to "स्कैन", "स्कन" to "स्कैन", "स्केनिंग" to "स्कैनिंग",
        "रिडिंग" to "रीडिंग", "रीडींग" to "रीडिंग", "सेंसोर" to "सेंसर",
        "जगाह" to "जगह", "उपर" to "ऊपर", "निचे" to "नीचे"
    )

    /** GPT-2 byte-level BPE maps bytes 0x00–0x20, 0x7F–0xA0 and 0xAD to U+0100 onwards. */
    private val BYTE_FOR_CHAR: Map<Char, Int> = buildMap {
        var next = 256
        for (b in 0..255) {
            val printable = b in 0x21..0x7E || b in 0xA1..0xAC || b in 0xAE..0xFF
            if (printable) put(b.toChar(), b) else put((next++).toChar(), b)
        }
        // Plain Latin-1 mojibake keeps the C1 control range as-is.
        for (b in 0x80..0xA0) putIfAbsent(b.toChar(), b)
        put('\u00AD', 0xAD)
    }

    /** UTF-8 Devanagari (E0 A4 xx / E0 A5 xx) shown as byte characters, e.g. "à¤¹à¥Ī". */
    private val MOJIBAKE = Regex("(?:\u00E0[\u00A4\u00A5][\u0080-\u00BF\u0100-\u0143])+")

    fun needsRepair(text: String): Boolean =
        text.any { isDevanagari(it) } || MOJIBAKE.containsMatchIn(text)

    fun repair(text: String): String {
        if (!needsRepair(text)) return text
        var t = fixMojibake(text)
        t = Normalizer.normalize(t, Normalizer.Form.NFC)
        t = t.replace(Regex("[\u200B\u2060\uFEFF]"), "")
        t = t.replace(Regex("(?<![$LETTER$COMBINING])[\u200C\u200D]|[\u200C\u200D](?![$LETTER])"), "")
        // "ह ै" -> "है": a sign split from its letter by a space.
        t = t.replace(Regex("([$LETTER$COMBINING])[ \\t]+([$COMBINING])"), "$1$2")
        // "क् ष" -> "क्ष": a conjunct split after the halant.
        t = t.replace(Regex("\u094D[ \\t]+([$LETTER])"), "\u094D$1")
        t = dropOrphanAndRepeatedSigns(t)
        t = fixWords(t)
        t = fixDanda(t)
        return t
    }

    fun fixMojibake(text: String): String = MOJIBAKE.replace(text) { m ->
        val bytes = m.value.map { BYTE_FOR_CHAR[it] }
        if (bytes.any { it == null }) return@replace m.value
        val buffer = java.nio.ByteBuffer.wrap(ByteArray(bytes.size) { bytes[it]!!.toByte() })
        runCatching { Charsets.UTF_8.newDecoder().decode(buffer).toString() }.getOrNull() ?: m.value
    }

    private fun dropOrphanAndRepeatedSigns(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            if (isCombining(c)) {
                val prev = out.lastOrNull()
                if (prev == null || !(isDevanagari(prev) || prev == '\u200D' || prev == '\u200C')) continue
                if (prev == c) continue
                if (isVowelSign(c) && isVowelSign(prev)) continue
                if (c == '\u094D' && isVowelSign(prev)) continue
            }
            out.append(c)
        }
        return out.toString()
    }

    private fun fixWords(text: String): String =
        Regex("[\u0900-\u097F\u200C\u200D]+").replace(text) { m ->
            if (m.value.endsWith("\u0964") || m.value.endsWith("\u0965")) {
                val word = m.value.trimEnd('\u0964', '\u0965')
                (WORD_FIXES[word] ?: word) + m.value.substring(word.length)
            } else {
                WORD_FIXES[m.value] ?: m.value
            }
        }

    private fun fixDanda(text: String): String {
        var t = text
        // "है |" or "है." at a sentence end -> "है।"
        t = t.replace(Regex("([$LETTER$COMBINING])[ \\t]*[|.](?=\\s|$)"), "$1\u0964")
        t = t.replace(Regex("[ \\t]+\u0964"), "\u0964")
        t = t.replace(Regex("\u0964{2,}"), "\u0964")
        t = t.replace(Regex("\u0964(?=[$LETTER])"), "\u0964 ")
        return t
    }
}
