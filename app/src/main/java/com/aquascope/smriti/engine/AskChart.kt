package com.aquascope.smriti.engine

import com.aquascope.baseline.PlaceReliability
import com.aquascope.data.ScanLocation

/**
 * A chart drawn under an Ask answer, straight from scan data: one place's scores over time,
 * or the latest score of several places side by side. Stores place ids so a saved chat
 * redraws from current data.
 */
data class AskChart(val kind: Kind, val placeIds: List<String>) {

    enum class Kind { TREND, COMPARE }

    fun encode(): String = kind.name.lowercase() + ":" + placeIds.joinToString(",")

    companion object {
        val CHART_WORDS = listOf(
            "chart", "graph", "plot", "visual", "diagram",
            "grafh", "graf ", "ग्राफ", "चार्ट", "आरेख"
        )
        private val COMPARE_WORDS = listOf(
            "compare", "which", "all places", "every place", "each place", "my places", "rank",
            "kaun", "konsi", "kaunsi", "sab jagah", "sabhi", "tulna",
            "कौन", "सभी", "सब जगह", "तुलना"
        )
        /** Places shown in one bar chart; more would not fit on a phone. */
        const val MAX_BARS = 8

        fun decode(s: String?): AskChart? {
            if (s.isNullOrBlank()) return null
            val kind = Kind.values().firstOrNull { it.name.lowercase() == s.substringBefore(':') } ?: return null
            val ids = s.substringAfter(':', "").split(',').filter { it.isNotBlank() }
            return if (ids.isEmpty()) null else AskChart(kind, ids)
        }

        /** The chart that fits [question], or null when a chart would add nothing. */
        fun plan(question: String, locations: List<ScanLocation>): AskChart? {
            if (AquaScopeTopic.isScreenTopic(question)) return null
            val q = question.lowercase()
            val wantsChart = CHART_WORDS.any { it in q }
            if (!wantsChart && !ScanAnswerer.isAboutScans(question, locations)) return null

            val scanned = locations.filter { it.label.isNotBlank() && it.scanHistory.isNotEmpty() }
            if (scanned.isEmpty()) return null
            val mentioned = PlaceReliability.mentionedIn(question, scanned)

            if (mentioned.size >= 2) return AskChart(Kind.COMPARE, mentioned.take(MAX_BARS).map { it.id })
            mentioned.singleOrNull()?.let { place ->
                return if (place.scanHistory.size >= 2) AskChart(Kind.TREND, listOf(place.id)) else null
            }
            if (!wantsChart && COMPARE_WORDS.none { it in q }) return null
            if (scanned.size >= 2) {
                val byLatest = scanned.sortedByDescending { p -> p.scanHistory.maxByOrNull { it.timestamp }!!.anomalyScore }
                return AskChart(Kind.COMPARE, byLatest.take(MAX_BARS).map { it.id })
            }
            val only = scanned.single()
            return if (only.scanHistory.size >= 2) AskChart(Kind.TREND, listOf(only.id)) else null
        }
    }
}
