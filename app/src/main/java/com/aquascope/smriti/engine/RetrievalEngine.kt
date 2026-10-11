package com.aquascope.smriti.engine

import com.aquascope.smriti.memory.EpisodicMemoryStore
import com.aquascope.smriti.model.EventType
import com.aquascope.smriti.model.MemoryQuery
import com.aquascope.smriti.model.PhysicalEvent
import com.aquascope.smriti.model.QueryIntent
import java.util.Calendar
import java.util.Locale

/**
 * Structured + temporal retrieval over local episodic memory.
 * Does NOT dump the whole DB into an LLM.
 */
class RetrievalEngine(
    private val store: EpisodicMemoryStore
) {

    fun parse(question: String): MemoryQuery {
        val q = question.lowercase(Locale.getDefault())
        val intent = when {
            q.contains("definitely a leak") || q.contains("is it a leak") ||
                (q.contains("confirm") && q.contains("leak")) ||
                q.contains("leak hai") || q.contains("futa") || q.contains("pakka leak") ||
                q.contains("fuga") || q.contains("fuite") || q.contains("पक्का लीक") ||
                q.contains("लीक है") -> QueryIntent.IS_DEFINITE_LEAK

            q.contains("first") || q.contains("when did") || q.contains("start") ||
                q.contains("kab shuru") || q.contains("shuruat") || q.contains("pehle kab") ||
                q.contains("premier") || q.contains("cuándo empezó") -> QueryIntent.FIRST_OCCURRENCE

            q.contains("before") || q.contains("happened before") || q.contains("similar") ||
                q.contains("pehle bhi") || q.contains("pehle hua") || q.contains("dobara") ||
                q.contains("kabhi hua") || q.contains("antes") || q.contains("déjà") ||
                q.contains("पहले भी") || q.contains("पहले हुआ") -> QueryIntent.HAS_HAPPENED_BEFORE

            q.contains("this week") || q.contains("how many") ||
                q.contains("is hafte") || q.contains("kitni baar") ||
                q.contains("esta semana") || q.contains("cette semaine") -> QueryIntent.COUNT_THIS_WEEK

            q.contains("today") || q.contains("changed today") || q.contains("what changed") ||
                q.contains("aaj kya") || q.contains("aaj ka") || q.contains("kya badla") ||
                q.contains("hoy") || q.contains("aujourd'hui") ||
                q.contains("आज क्या") || q.contains("क्या बदला") -> QueryIntent.WHAT_CHANGED_TODAY

            q.contains("worse") || q.contains("getting worse") || q.contains("increasing") ||
                q.contains("badh raha") || q.contains("kharab ho") || q.contains("peor") ||
                q.contains("pire") -> QueryIntent.IS_GETTING_WORSE

            q.contains("evidence") || q.contains("why") || (q.contains("show me") && q.contains("record")) ||
                q.contains("saboot") || q.contains("praman") || q.contains("kyun") ||
                q.contains("evidencia") || q.contains("preuve") -> QueryIntent.SHOW_EVIDENCE

            q.contains("normal") || q.contains("baseline") ||
                q.contains("sahi") || q.contains("theek") ||
                q.contains("सामान्य क्या") || q.contains("ठीक") -> QueryIntent.WHAT_IS_NORMAL

            else -> QueryIntent.GENERAL
        }

        val locationHint = when {
            (q.contains("kitchen") || q.contains("rasoi") || q.contains("cocina") || q.contains("cuisine")) && !q.contains("alert") -> "kitchen"
            q.contains("bathroom") || q.contains("gusal") || q.contains("washroom") || q.contains("baño") || q.contains("bain") -> "bathroom"
            q.contains("pump") || q.contains("motor") -> "pump"
            q.contains("pipe") || q.contains("nal") || q.contains("tubería") || q.contains("tuyau") -> "pipe"
            q.contains("guardian") || q.contains("kitchen alert") ||
                q.contains("smoke") || q.contains("glass") -> "guardian"
            q.contains("ir blaster") || q.contains("infrared") ||
                (q.contains("ir") && (q.contains("blast") || q.contains("pulse") || q.contains("arm"))) -> "ir"
            else -> null
        }

        val since = when {
            intent == QueryIntent.WHAT_CHANGED_TODAY || q.contains("today") || q.contains("aaj") || q.contains("hoy") -> startOfDayMs()
            intent == QueryIntent.COUNT_THIS_WEEK || q.contains("week") || q.contains("hafte") || q.contains("semana") -> startOfWeekMs()
            q.contains("yesterday") || q.contains("kal") || q.contains("ayer") -> startOfDayMs() - 24L * 60 * 60 * 1000
            else -> null
        }
        val until = if (q.contains("yesterday") || q.contains("kal") || q.contains("ayer")) startOfDayMs() else null

        return MemoryQuery(
            raw = question,
            locationHint = locationHint,
            sinceMs = since,
            untilMs = until,
            intent = intent
        )
    }

    fun retrieve(query: MemoryQuery, limit: Int = 12): List<PhysicalEvent> {
        var events: List<PhysicalEvent> = store.loadEvents()

        query.locationHint?.let { hint ->
            events = events.filter { ev ->
                matchesHint(ev, hint)
            }
        }
        query.sinceMs?.let { since -> events = events.filter { it.timestampMs >= since } }
        query.untilMs?.let { until -> events = events.filter { it.timestampMs < until } }

        if (query.eventTypes.isNotEmpty()) {
            events = events.filter { it.eventType in query.eventTypes }
        }

        return when (query.intent) {
            QueryIntent.FIRST_OCCURRENCE ->
                events.filter { isDeviation(it) }.sortedBy { it.timestampMs }.take(limit)
            QueryIntent.HAS_HAPPENED_BEFORE, QueryIntent.IS_GETTING_WORSE,
            QueryIntent.SHOW_EVIDENCE, QueryIntent.IS_DEFINITE_LEAK ->
                events.filter { isDeviation(it) }.sortedByDescending { it.timestampMs }.take(limit)
            QueryIntent.WHAT_IS_NORMAL ->
                events.filter {
                    it.eventType == EventType.NORMAL || it.eventType == EventType.BASELINE_ESTABLISHED
                }.sortedByDescending { it.timestampMs }.take(limit)
            else -> events.sortedByDescending { it.timestampMs }.take(limit)
        }
    }

    fun anomalousForObject(objectId: String): List<PhysicalEvent> =
        store.eventsForObject(objectId).filter { isDeviation(it) }

    private fun matchesHint(ev: PhysicalEvent, hint: String): Boolean {
        val h = hint.lowercase(Locale.getDefault())
        if (ev.locationLabel.contains(h, ignoreCase = true) ||
            ev.objectLabel.contains(h, ignoreCase = true) ||
            ev.summary.contains(h, ignoreCase = true) ||
            ev.source.contains(h, ignoreCase = true)
        ) {
            return true
        }
        return when (h) {
            "guardian" ->
                ev.source.equals("GUARDIAN", true) ||
                    ev.summary.contains("Guardian", ignoreCase = true)
            "ir" ->
                ev.source.equals("IR", true) ||
                    ev.summary.contains("IR pulse", ignoreCase = true) ||
                    ev.summary.contains("IR armed", ignoreCase = true)
            else -> false
        }
    }

    private fun isDeviation(e: PhysicalEvent) = e.eventType in setOf(
        EventType.ACOUSTIC_DEVIATION,
        EventType.ANOMALY,
        EventType.REPEATED_ANOMALY,
        EventType.POSSIBLE_LEAK
    )

    private fun startOfDayMs(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun startOfWeekMs(): Long {
        val c = Calendar.getInstance()
        c.firstDayOfWeek = Calendar.MONDAY
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
