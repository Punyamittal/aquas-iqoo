package com.aquascope.baseline

import com.aquascope.data.ScanLocation

/**
 * How far a place's moisture score can be trusted, from what the user has taught there.
 * Shared by the scan screen and the Ask prompt so both say the same thing.
 */
object PlaceReliability {

    const val MIN_DAMP_FOR_RELIABLE_SCORE = 2

    enum class Level { NO_BASELINE, PRELIMINARY, NOT_CHECKED, UNRELIABLE, RELIABLE }

    data class Status(val level: Level, val selfCheckPercent: Double?)

    fun of(location: ScanLocation): Status {
        val dry = location.baselineFeatures.map { it.toAcousticFeatures() }
        val moist = location.moistFeatures.map { it.toAcousticFeatures() }
        if (dry.isEmpty()) return Status(Level.NO_BASELINE, null)
        if (moist.size < MIN_DAMP_FOR_RELIABLE_SCORE) return Status(Level.PRELIMINARY, null)
        val check = AnomalyScorer.teachingSelfCheck(dry, moist) ?: return Status(Level.NOT_CHECKED, null)
        val level = if (check < AnomalyScorer.MIN_RELIABLE_SELF_CHECK) Level.UNRELIABLE else Level.RELIABLE
        return Status(level, check)
    }

    /**
     * Plain-language status of a place, used when Ask answers without the model.
     * [lang] is "en", "hi" or "hinglish"; anything else gets English.
     */
    fun sentence(location: ScanLocation, lang: String = "en"): String {
        val status = of(location)
        val name = location.label
        val last = location.scanHistory.maxByOrNull { it.timestamp }?.anomalyScore?.toInt()
        val check = status.selfCheckPercent?.toInt()
        val k = AnomalyScorer.MIN_PER_CLASS_FOR_SELF_CHECK
        val minDamp = MIN_DAMP_FOR_RELIABLE_SCORE
        return when (status.level) {
            Level.NO_BASELINE -> pick(
                lang,
                "$name has no dry baseline yet, so it can't be judged.",
                "$name के लिए अभी कोई सूखा बेसलाइन नहीं है, इसलिए इसे जांचा नहीं जा सकता।",
                "$name ka abhi koi dry baseline nahi hai, isliye ise judge nahi kiya ja sakta."
            )
            Level.PRELIMINARY -> if (last != null) pick(
                lang,
                "$name last scored $last%, but that is only a rough guess until at least $minDamp damp spots are taught there.",
                "$name का पिछला स्कोर $last% था, लेकिन जब तक वहाँ कम से कम $minDamp गीले उदाहरण नहीं सिखाए जाते, यह केवल अनुमान है।",
                "$name ka last score $last% tha, lekin jab tak wahan kam se kam $minDamp damp examples nahi sikhaye jaate, yeh sirf andaaza hai."
            ) else pick(
                lang,
                "$name has a dry baseline but no scored scans yet.",
                "$name का सूखा बेसलाइन है, पर अभी कोई स्कोर किया गया स्कैन नहीं है।",
                "$name ka dry baseline hai, par abhi koi scored scan nahi hai."
            )
            Level.NOT_CHECKED -> pick(
                lang,
                "$name last scored ${last ?: 0}%. Teach $k dry and $k damp examples there so AquaScope can check how reliable that is.",
                "$name का पिछला स्कोर ${last ?: 0}% था। वहाँ $k सूखे और $k गीले उदाहरण सिखाएँ ताकि AquaScope जांच सके कि यह कितना भरोसेमंद है।",
                "$name ka last score ${last ?: 0}% tha. Wahan $k dry aur $k damp examples sikhaiye taaki AquaScope check kar sake ki yeh kitna bharosemand hai."
            )
            Level.UNRELIABLE -> pick(
                lang,
                "At $name, dry and damp sound alike (self-check $check%), so its last score of ${last ?: 0}% can't be trusted. " +
                    "Keep the phone in the same spot and orientation, and wet the damp spot thoroughly.",
                "$name पर सूखी और गीली आवाज़ एक जैसी लगती है (सेल्फ-चेक $check%), इसलिए इसके पिछले स्कोर ${last ?: 0}% पर भरोसा नहीं किया जा सकता। " +
                    "फ़ोन को हर बार एक ही जगह और दिशा में रखें, और गीली जगह को अच्छी तरह भिगोएँ।",
                "$name par dry aur damp ki awaaz ek jaisi lagti hai (self-check $check%), isliye iske last score ${last ?: 0}% par bharosa nahi kiya ja sakta. " +
                    "Phone ko har baar ek hi jagah aur direction me rakhein, aur damp jagah ko achhe se geela karein."
            )
            Level.RELIABLE -> {
                val score = last ?: 0
                val band = when {
                    score < AnomalyThresholds.GREEN_MAX -> 0
                    score < AnomalyThresholds.YELLOW_MAX -> 1
                    else -> 2
                }
                pick(
                    lang,
                    "$name last scored $score%, so it ${listOf("sounds dry", "is in between dry and damp", "sounds damp")[band]} (self-check $check%).",
                    "$name का पिछला स्कोर $score% था, यानी यह ${listOf("सूखा लगता है", "सूखे और गीले के बीच है", "गीला लगता है")[band]} (सेल्फ-चेक $check%)।",
                    "$name ka last score $score% tha, yaani yeh ${listOf("dry lagta hai", "dry aur damp ke beech hai", "damp lagta hai")[band]} (self-check $check%)."
                )
            }
        }
    }

    /** Chooses the Hindi or Hinglish wording when [lang] asks for it, English otherwise. */
    fun pick(lang: String, en: String, hi: String, hinglish: String): String = when (lang.lowercase()) {
        "hi" -> hi
        "hinglish" -> hinglish
        else -> en
    }

    /** Places whose name appears in [question], longest names first so "wework10" beats "wework1". */
    fun mentionedIn(question: String, locations: List<ScanLocation>): List<ScanLocation> {
        val q = question.lowercase()
        val hits = mutableListOf<ScanLocation>()
        var remaining = q
        locations.filter { it.label.trim().length >= 3 }
            .sortedByDescending { it.label.length }
            .forEach { loc ->
                val name = loc.label.trim().lowercase()
                if (Regex("""(?<![\p{L}\p{N}])${Regex.escape(name)}(?![\p{L}\p{N}])""").containsMatchIn(remaining)) {
                    hits += loc
                    remaining = remaining.replace(name, " ")
                }
            }
        return hits
    }

    /** One APP_DATA line for the Ask prompt. Verdict words appear only when the place is reliable. */
    fun promptLine(location: ScanLocation): String {
        val status = of(location)
        val last = location.scanHistory.maxByOrNull { it.timestamp }
        val check = status.selfCheckPercent?.let { " self_check=${it.toInt()}%" } ?: ""
        val reliability = when (status.level) {
            Level.NO_BASELINE -> "NO_BASELINE (never taught; cannot judge)"
            Level.PRELIMINARY -> "PRELIMINARY (under $MIN_DAMP_FOR_RELIABLE_SCORE damp examples; score is a rough guess)"
            Level.NOT_CHECKED -> "NOT_CHECKED (needs ${AnomalyScorer.MIN_PER_CLASS_FOR_SELF_CHECK} dry and ${AnomalyScorer.MIN_PER_CLASS_FOR_SELF_CHECK} damp examples)"
            Level.UNRELIABLE -> "UNRELIABLE (dry and damp sound alike here; do not call it dry or damp)"
            Level.RELIABLE -> "RELIABLE"
        }
        val lastBit = last?.let {
            val pct = it.anomalyScore.toInt()
            val verdict = if (status.level == Level.RELIABLE) {
                when {
                    it.anomalyScore < AnomalyThresholds.GREEN_MAX -> " (sounds dry)"
                    it.anomalyScore < AnomalyThresholds.YELLOW_MAX -> " (in between)"
                    else -> " (sounds damp)"
                }
            } else ""
            "last_score=$pct%$verdict · scans=${location.scanHistory.size}"
        } ?: "no scored scans yet"
        return "- ${location.label}: dry_taught=${location.baselineFeatures.size} · " +
            "damp_taught=${location.moistFeatures.size} · reliability=$reliability$check · $lastBit"
    }
}
