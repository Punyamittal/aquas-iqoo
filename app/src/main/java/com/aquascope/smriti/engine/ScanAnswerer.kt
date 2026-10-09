package com.aquascope.smriti.engine

import com.aquascope.baseline.AnomalyThresholds
import com.aquascope.baseline.PlaceReliability
import com.aquascope.baseline.PlaceReliability.Level
import com.aquascope.baseline.PlaceReliability.pick
import com.aquascope.data.ScanLocation

/**
 * Answers moisture questions straight from the scanned places, so Ask is right even
 * without an on-device model. Returns null for questions that are not about scans.
 * Replies are written in [lang] ("en", "hi" or "hinglish"; others get English).
 */
object ScanAnswerer {

    private val TOPIC = listOf(
        "damp", "wet", "moist", "leak", "seep", "water", "dry", "scan", "place", "location",
        "spot", "wall", "reliab", "trust", "accura", "score", "mould", "mold",
        "nami", "geela", "gila", "seelan", "paani", "pani", "jagah", "sookha", "sukha", "rissav",
        "नमी", "गीला", "गीली", "सीलन", "लीक", "पानी", "स्कैन", "जगह", "सूखा", "सूखी", "रिसाव"
    )
    private val NOT_SCAN = listOf("screen", "game", "clip", "ocr", "guardian", "app open", "स्क्रीन", "गेम")
    private val LEAK = listOf("leak", "rissav", "लीक", "रिसाव")
    private val LATEST = listOf(
        "last scan", "latest", "recent scan", "most recent", "previous scan",
        "pichla scan", "pichhla scan", "aakhri scan", "akhri scan",
        "पिछला स्कैन", "आखिरी स्कैन", "आख़िरी स्कैन", "हाल का स्कैन"
    )
    private val TREND = listOf(
        "worse", "better", "increas", "decreas", "trend", "improv",
        "badh", "ghat", "kam ho", "बढ़", "बढ", "घट", "कम हो"
    )
    private val COUNT = listOf("how many", "kitne", "kitna", "कितने", "कितना")

    fun answer(
        question: String,
        locations: List<ScanLocation>,
        lang: String = "en",
        formatTime: (Long) -> String
    ): String? {
        val q = question.lowercase()
        if (NOT_SCAN.any { it in q }) return null
        val mentioned = PlaceReliability.mentionedIn(question, locations)
        if (mentioned.isEmpty() && TOPIC.none { it in q }) return null

        val known = locations.filter {
            it.label.isNotBlank() && (it.baselineFeatures.isNotEmpty() || it.scanHistory.isNotEmpty())
        }
        if (known.isEmpty()) {
            return pick(
                lang,
                "You haven't scanned any places yet. Open Scan, add a place and teach it a few dry scans first.",
                "आपने अभी तक कोई जगह स्कैन नहीं की है। Scan खोलें, एक जगह जोड़ें और पहले कुछ सूखे स्कैन सिखाएँ।",
                "Aapne abhi tak koi jagah scan nahi ki hai. Scan kholiye, ek jagah add kijiye aur pehle kuch dry scans sikhaiye."
            )
        }

        val parts = mutableListOf<String>()
        if (LEAK.any { it in q }) {
            parts += pick(
                lang,
                "AquaScope measures moisture risk from sound; it can't confirm a leak on its own.",
                "AquaScope आवाज़ से नमी का जोखिम मापता है; यह अपने आप लीक की पुष्टि नहीं कर सकता।",
                "AquaScope awaaz se nami ka risk maapta hai; yeh akele leak confirm nahi kar sakta."
            )
        }
        when {
            mentioned.isNotEmpty() -> mentioned.take(3).forEach { loc ->
                parts += PlaceReliability.sentence(loc, lang)
                if (TREND.any { it in q }) parts += trend(loc, lang)
                if (COUNT.any { it in q }) {
                    val n = loc.scanHistory.size
                    parts += pick(lang, "${loc.label} has $n scans.", "${loc.label} के $n स्कैन हैं।", "${loc.label} ke $n scans hain.")
                }
            }
            LATEST.any { it in q } -> parts += latest(known, lang, formatTime)
            COUNT.any { it in q } -> parts += count(known, lang)
            else -> parts += overview(known, lang)
        }
        return parts.joinToString("\n")
    }

    private fun lastScore(loc: ScanLocation): Int? =
        loc.scanHistory.maxByOrNull { it.timestamp }?.anomalyScore?.toInt()

    private fun trend(loc: ScanLocation, lang: String): String {
        val name = loc.label
        if (PlaceReliability.of(loc).level != Level.RELIABLE) {
            return pick(
                lang,
                "I can't judge a trend at $name until its scores are reliable.",
                "जब तक $name के स्कोर भरोसेमंद नहीं होते, मैं ट्रेंड नहीं बता सकता।",
                "Jab tak $name ke scores bharosemand nahi hote, main trend nahi bata sakta."
            )
        }
        val scores = loc.scanHistory.sortedBy { it.timestamp }.map { it.anomalyScore }
        if (scores.size < 4) {
            return pick(
                lang,
                "$name needs at least 4 scans to show a trend.",
                "$name का ट्रेंड देखने के लिए कम से कम 4 स्कैन चाहिए।",
                "$name ka trend dekhne ke liye kam se kam 4 scans chahiye."
            )
        }
        val recent = scores.takeLast(3).average().toInt()
        val before = scores.dropLast(3).takeLast(3).average().toInt()
        return when {
            recent - before > 10 -> pick(
                lang,
                "Its recent scans average $recent%, up from $before%, so it is getting damper.",
                "हाल के स्कैन का औसत $recent% है, पहले $before% था, यानी नमी बढ़ रही है।",
                "Recent scans ka average $recent% hai, pehle $before% tha, yaani nami badh rahi hai."
            )
            before - recent > 10 -> pick(
                lang,
                "Its recent scans average $recent%, down from $before%, so it is drying out.",
                "हाल के स्कैन का औसत $recent% है, पहले $before% था, यानी यह सूख रहा है।",
                "Recent scans ka average $recent% hai, pehle $before% tha, yaani yeh sookh raha hai."
            )
            else -> pick(
                lang,
                "Its recent scans average $recent%, about the same as before ($before%).",
                "हाल के स्कैन का औसत $recent% है, पहले जैसा ही ($before%)।",
                "Recent scans ka average $recent% hai, pehle jaisa hi ($before%)."
            )
        }
    }

    private fun latest(known: List<ScanLocation>, lang: String, formatTime: (Long) -> String): String {
        val (loc, scan) = known.flatMap { l -> l.scanHistory.map { l to it } }
            .maxByOrNull { it.second.timestamp }
            ?: return pick(
                lang,
                "No place has a scored scan yet.",
                "अभी किसी जगह का स्कोर किया गया स्कैन नहीं है।",
                "Abhi kisi jagah ka scored scan nahi hai."
            )
        val time = formatTime(scan.timestamp)
        return pick(
            lang,
            "Your last scan was at ${loc.label} on $time. ",
            "आपका पिछला स्कैन ${loc.label} पर $time को हुआ था। ",
            "Aapka last scan ${loc.label} par $time ko hua tha. "
        ) + PlaceReliability.sentence(loc, lang)
    }

    private fun count(known: List<ScanLocation>, lang: String): String {
        val total = known.sumOf { it.scanHistory.size }
        val n = known.size
        val perPlace = known.filter { it.scanHistory.isNotEmpty() }
            .sortedByDescending { it.scanHistory.size }
            .joinToString(", ") { "${it.label} ${it.scanHistory.size}" }
        val detail = if (perPlace.isNotEmpty()) " ($perPlace)" else ""
        return pick(
            lang,
            "You've run $total scored scans across $n places$detail.",
            "आपने $n जगहों पर कुल $total स्कोर किए गए स्कैन किए हैं$detail।",
            "Aapne $n jagahon par total $total scored scans kiye hain$detail."
        )
    }

    private fun overview(known: List<ScanLocation>, lang: String): String {
        val status = known.associateWith { PlaceReliability.of(it) }
        val reliable = known.filter { status.getValue(it).level == Level.RELIABLE && lastScore(it) != null }
        fun names(list: List<ScanLocation>, withScore: Boolean = false) = list.joinToString(", ") {
            if (withScore) "${it.label} (${lastScore(it)}%)" else it.label
        }
        val damp = reliable.filter { lastScore(it)!! >= AnomalyThresholds.YELLOW_MAX }
        val dry = reliable.filter { lastScore(it)!! < AnomalyThresholds.GREEN_MAX }
        val between = reliable - damp.toSet() - dry.toSet()
        val unreliable = known.filter { status.getValue(it).level == Level.UNRELIABLE }
        val learning = known.filter { status.getValue(it).level in setOf(Level.PRELIMINARY, Level.NOT_CHECKED) }
        val noBaseline = known.filter { status.getValue(it).level == Level.NO_BASELINE }
        val n = known.size

        return buildList {
            add(pick(lang, "Here is where your $n places stand:", "आपकी $n जगहों की स्थिति:", "Aapki $n jagahon ki sthiti:"))
            if (damp.isNotEmpty()) {
                add(pick(lang, "• Sounds damp: ", "• गीला लगता है: ", "• Damp lagta hai: ") + names(damp, true))
            }
            if (between.isNotEmpty()) {
                add(pick(lang, "• In between: ", "• बीच में: ", "• Beech me: ") + names(between, true))
            }
            if (dry.isNotEmpty()) {
                add(pick(lang, "• Sounds dry: ", "• सूखा लगता है: ", "• Dry lagta hai: ") + names(dry, true))
            }
            if (unreliable.isNotEmpty()) {
                val list = names(unreliable)
                add(
                    pick(
                        lang,
                        "• Can't tell dry from damp yet: $list. Dry and damp sound alike there, so ignore their scores.",
                        "• अभी सूखा या गीला नहीं बता सकते: $list। वहाँ दोनों की आवाज़ एक जैसी है, इसलिए उनके स्कोर को अनदेखा करें।",
                        "• Abhi dry ya damp nahi bata sakte: $list. Wahan dono ki awaaz ek jaisi hai, isliye unke scores ignore karein."
                    )
                )
            }
            if (learning.isNotEmpty()) {
                add(
                    pick(
                        lang,
                        "• Still learning (teach more dry and damp examples): ",
                        "• अभी सीख रहा है (और सूखे व गीले उदाहरण सिखाएँ): ",
                        "• Abhi seekh raha hai (aur dry aur damp examples sikhaiye): "
                    ) + names(learning)
                )
            }
            if (noBaseline.isNotEmpty()) {
                add(pick(lang, "• No dry baseline yet: ", "• अभी सूखा बेसलाइन नहीं: ", "• Abhi dry baseline nahi: ") + names(noBaseline))
            }
            if (reliable.isEmpty()) {
                add(
                    pick(
                        lang,
                        "None of your places gives a trustworthy reading yet.",
                        "अभी आपकी किसी भी जगह की रीडिंग भरोसेमंद नहीं है।",
                        "Abhi aapki kisi bhi jagah ki reading bharosemand nahi hai."
                    )
                )
            }
        }.joinToString("\n")
    }
}
