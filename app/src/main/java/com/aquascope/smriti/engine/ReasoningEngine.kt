package com.aquascope.smriti.engine

import com.aquascope.smriti.llm.AskAnswerCleaner
import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.EventType
import com.aquascope.smriti.model.MemoryQuery
import com.aquascope.smriti.model.PhysicalEvent
import com.aquascope.smriti.model.QueryIntent
import com.aquascope.smriti.model.SmritiAnswer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local rule-based reasoning over retrieved memories.
 * Never fabricates timestamps or claims confirmed leaks without CONFIRMED status.
 */
class ReasoningEngine(
    private val evidenceEngine: EvidenceEngine
) {

    private val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

    fun answer(query: MemoryQuery, retrieved: List<PhysicalEvent>): SmritiAnswer {
        return when (query.intent) {
            QueryIntent.IS_DEFINITE_LEAK -> answerLeakQuestion(retrieved)
            QueryIntent.FIRST_OCCURRENCE -> answerFirst(retrieved)
            QueryIntent.HAS_HAPPENED_BEFORE -> answerBefore(retrieved)
            QueryIntent.COUNT_THIS_WEEK -> answerCount(retrieved)
            QueryIntent.WHAT_CHANGED_TODAY -> answerToday(retrieved)
            QueryIntent.IS_GETTING_WORSE -> answerWorse(retrieved)
            QueryIntent.SHOW_EVIDENCE -> answerEvidence(retrieved)
            QueryIntent.WHAT_IS_NORMAL -> answerNormal(retrieved)
            QueryIntent.GENERAL -> answerGeneral(query, retrieved)
        }
    }

    private fun answerLeakQuestion(events: List<PhysicalEvent>): SmritiAnswer {
        val deviations = events.filter { isDev(it) }
        if (deviations.isEmpty()) {
            return SmritiAnswer(
                text = "I don't have a record of a confirmed leak, and I found no acoustic deviation events matching your question.",
                evidenceState = EvidenceState.UNKNOWN,
                relatedEvents = emptyList(),
                evidence = evidenceEngine.forEvents(
                    emptyList(),
                    "No leak confirmation in memory.",
                    EvidenceState.UNKNOWN
                ),
                suggestedActions = listOf("Run an AquaScope scan", "Ask about a specific location")
            )
        }
        val repeated = deviations.filter {
            it.eventType == EventType.POSSIBLE_LEAK || it.eventType == EventType.REPEATED_ANOMALY
        }
        val text = buildString {
            append("I detected ${deviations.size} acoustic deviation record(s)")
            if (repeated.isNotEmpty()) append(", including repeated abnormal patterns")
            append(". ")
            append("This is consistent with abnormal acoustic behavior")
            append(", but I do not have enough evidence to confirm a physical leak. ")
            append("Status remains UNCONFIRMED / POSSIBLE — not CONFIRMED.")
        }
        return SmritiAnswer(
            text = text,
            evidenceState = EvidenceState.POSSIBLE,
            relatedEvents = deviations.take(5),
            evidence = evidenceEngine.forEvents(
                deviations.take(5),
                "Possible abnormal water flow (unconfirmed).",
                EvidenceState.POSSIBLE
            ),
            suggestedActions = listOf("View evidence", "Rescan location", "Inspect pipe visually")
        )
    }

    private fun answerFirst(events: List<PhysicalEvent>): SmritiAnswer {
        val first = events.filter { isDev(it) }.minByOrNull { it.timestampMs }
            ?: return none("I don't have a record of an acoustic deviation for that query.")
        val text =
            "I found ${events.count { isDev(it) }} relevant deviation memories. " +
                "The earliest recorded deviation was on ${fmt.format(Date(first.timestampMs))} " +
                "at ${first.locationLabel} " +
                "(score ${first.anomalyScore.toInt()}%). " +
                "I cannot confirm that this is a leak."
        return SmritiAnswer(
            text = text,
            evidenceState = EvidenceState.OBSERVED,
            relatedEvents = listOf(first) + events.filter { isDev(it) }.take(3),
            evidence = evidenceEngine.forEvent(first),
            suggestedActions = listOf("View evidence", "Compare later events")
        )
    }

    private fun answerBefore(events: List<PhysicalEvent>): SmritiAnswer {
        val devs = events.filter { isDev(it) }
        if (devs.size < 2) {
            return if (devs.isEmpty()) {
                none("No similar acoustic anomalies are stored in memory yet.")
            } else {
                SmritiAnswer(
                    text = "I have only one stored deviation (${fmt.format(Date(devs[0].timestampMs))} at ${devs[0].locationLabel}). I cannot say it has happened before based on memory.",
                    evidenceState = EvidenceState.OBSERVED,
                    relatedEvents = devs,
                    evidence = evidenceEngine.forEvent(devs[0])
                )
            }
        }
        val latest = devs[0]
        val previous = devs[1]
        val text =
            "Yes. A similar acoustic anomaly was recorded on ${fmt.format(Date(previous.timestampMs))} " +
                "at ${previous.locationLabel} (score ${previous.anomalyScore.toInt()}%). " +
                "The latest observation is ${fmt.format(Date(latest.timestampMs))} " +
                "(score ${latest.anomalyScore.toInt()}%)."
        return SmritiAnswer(
            text = text,
            evidenceState = EvidenceState.OBSERVED,
            relatedEvents = listOf(latest, previous),
            evidence = evidenceEngine.forEvents(listOf(latest, previous), text, EvidenceState.OBSERVED),
            suggestedActions = listOf("Compare events", "View evidence")
        )
    }

    private fun answerCount(events: List<PhysicalEvent>): SmritiAnswer {
        val n = events.count { isDev(it) }
        val text = if (n == 0) {
            "I found 0 anomaly records for this period in local memory."
        } else {
            "I found $n anomaly-related record(s) in the retrieved window."
        }
        return SmritiAnswer(
            text = text,
            evidenceState = if (n == 0) EvidenceState.UNKNOWN else EvidenceState.OBSERVED,
            relatedEvents = events.filter { isDev(it) },
            evidence = evidenceEngine.forEvents(
                events.filter { isDev(it) },
                text,
                if (n == 0) EvidenceState.UNKNOWN else EvidenceState.OBSERVED
            )
        )
    }

    private fun answerToday(events: List<PhysicalEvent>): SmritiAnswer {
        if (events.isEmpty()) {
            return none("I don't have records of activity today yet.")
        }
        val lines = events.take(6).joinToString("\n") {
            "• ${fmt.format(Date(it.timestampMs))} at ${it.locationLabel}: " +
                AskAnswerCleaner.userFacingSummary(it.summary)
        }
        return SmritiAnswer(
            text = "Observed today (${events.size} memories):\n$lines",
            evidenceState = EvidenceState.OBSERVED,
            relatedEvents = events.take(6),
            evidence = evidenceEngine.forEvents(events.take(6), "Today's observations", EvidenceState.OBSERVED)
        )
    }

    private fun answerWorse(events: List<PhysicalEvent>): SmritiAnswer {
        val devs = events.filter { isDev(it) }.sortedBy { it.timestampMs }
        if (devs.size < 2) {
            return none("I need at least two stored deviations to judge whether it is getting worse.")
        }
        val first = devs.first()
        val last = devs.last()
        val delta = last.anomalyScore - first.anomalyScore
        val text = if (delta > 5) {
            "Compared with the earliest retrieved deviation (${first.anomalyScore.toInt()}% on ${fmt.format(Date(first.timestampMs))}), " +
                "the latest is ${last.anomalyScore.toInt()}% on ${fmt.format(Date(last.timestampMs))} " +
                "(${"%+.0f".format(delta)} points). The stored scores have increased."
        } else if (delta < -5) {
            "Latest score (${last.anomalyScore.toInt()}%) is lower than the earlier retrieved deviation (${first.anomalyScore.toInt()}%). Stored scores have not worsened."
        } else {
            "Latest (${last.anomalyScore.toInt()}%) and earlier (${first.anomalyScore.toInt()}%) scores are similar; I do not see a clear worsening trend in memory."
        }
        return SmritiAnswer(
            text = text,
            evidenceState = EvidenceState.INFERRED,
            relatedEvents = listOf(first, last),
            evidence = evidenceEngine.forEvents(listOf(first, last), text, EvidenceState.INFERRED)
        )
    }

    private fun answerEvidence(events: List<PhysicalEvent>): SmritiAnswer {
        val focus = events.firstOrNull()
            ?: return none("No records available to show as evidence.")
        val bundle = evidenceEngine.forEvent(focus)
        return SmritiAnswer(
            text = AskAnswerCleaner.userFacingSummary(bundle.claim),
            evidenceState = bundle.evidenceState,
            relatedEvents = listOf(focus),
            evidence = bundle,
            suggestedActions = listOf("Open evidence detail")
        )
    }

    private fun answerNormal(events: List<PhysicalEvent>): SmritiAnswer {
        val baselines = events.filter { it.eventType == EventType.BASELINE_ESTABLISHED }
        val normals = events.filter { it.eventType == EventType.NORMAL }
        if (baselines.isEmpty() && normals.isEmpty()) {
            return none("I don't have a stored baseline or normal scan for that yet. Save an AquaScope dry baseline first.")
        }
        val b = baselines.firstOrNull()
        val text = if (b != null) {
            "Normal for ${b.objectLabel} is defined by the baseline established on ${fmt.format(Date(b.timestampMs))}. " +
                "Later NORMAL scans are observations within threshold of that personal baseline."
        } else {
            "I have ${normals.size} NORMAL scan(s) in memory; the most recent is ${fmt.format(Date(normals.first().timestampMs))} at ${normals.first().locationLabel}."
        }
        return SmritiAnswer(
            text = text,
            evidenceState = EvidenceState.OBSERVED,
            relatedEvents = (baselines + normals).take(5),
            evidence = evidenceEngine.forEvents((baselines + normals).take(5), text, EvidenceState.OBSERVED)
        )
    }

    private fun answerGeneral(query: MemoryQuery, events: List<PhysicalEvent>): SmritiAnswer {
        if (events.isEmpty()) {
            return none(
                "I don't have a scan matching “${query.raw.trim()}” yet. " +
                    "Run a scan or ask about a place you've already scanned."
            )
        }
        val focus = events.take(6)
        val preview = focus.joinToString("\n") {
            "• ${fmt.format(Date(it.timestampMs))} at ${it.locationLabel}: " +
                AskAnswerCleaner.userFacingSummary(it.summary)
        }
        return SmritiAnswer(
            text = "About “${query.raw.trim()}”, here is what your scans show (${events.size} related):\n" + preview,
            evidenceState = EvidenceState.OBSERVED,
            relatedEvents = focus,
            evidence = evidenceEngine.forEvents(focus, "Retrieved scans", EvidenceState.OBSERVED),
            suggestedActions = listOf("Ask: Has this happened before?", "Ask: Is it definitely a leak?")
        )
    }

    private fun none(message: String) = SmritiAnswer(
        text = message,
        evidenceState = EvidenceState.UNKNOWN,
        relatedEvents = emptyList(),
        evidence = evidenceEngine.forEvents(emptyList(), message, EvidenceState.UNKNOWN)
    )

    private fun isDev(e: PhysicalEvent) = e.eventType in setOf(
        EventType.ACOUSTIC_DEVIATION,
        EventType.ANOMALY,
        EventType.REPEATED_ANOMALY,
        EventType.POSSIBLE_LEAK
    )
}
