package com.aquascope.smriti.model

/**
 * Evidence grounding states — HARD REQUIREMENT for SMRITI answers.
 * OBSERVED = recorded by sensors; INFERRED/POSSIBLE = interpretation; never invent CONFIRMED leaks.
 */
enum class EvidenceState {
    CONFIRMED,
    OBSERVED,
    INFERRED,
    POSSIBLE,
    UNKNOWN
}

enum class EventType {
    NORMAL,
    ANOMALY,
    ACOUSTIC_DEVIATION,
    WATER_FLOW,
    FLOW_STOPPED,
    POSSIBLE_LEAK,
    REPEATED_ANOMALY,
    BASELINE_ESTABLISHED,
    BASELINE_CHANGED,
    UNKNOWN
}

enum class EventStatus {
    UNCONFIRMED,
    UNRESOLVED,
    USER_CONFIRMED,
    USER_DISMISSED,
    USER_CORRECTED,
    RESOLVED
}

enum class SensorType {
    ACOUSTIC
}

/**
 * Structured physical observation — source of truth for SMRITI memory.
 * Natural language is derived from this; never the other way around.
 */
data class PhysicalEvent(
    val id: String,
    val timestampMs: Long,
    val locationId: String,
    val locationLabel: String,
    val objectId: String,
    val objectLabel: String,
    val sensorType: SensorType = SensorType.ACOUSTIC,
    val eventType: EventType,
    val anomalyScore: Double,
    val confidence: Double,
    val baselineId: String?,
    val features: Map<String, Double> = emptyMap(),
    val previousOccurrenceCount: Int = 0,
    val deviationVsYesterday: Double? = null,
    val summary: String,
    val status: EventStatus = EventStatus.UNCONFIRMED,
    val evidenceState: EvidenceState = EvidenceState.OBSERVED,
    val evidenceNotes: List<String> = emptyList(),
    val unknownNotes: List<String> = emptyList(),
    val source: String = "AQUASCOPE",
    val userCorrection: String? = null
)

data class HomeObject(
    val id: String,
    val label: String,
    val locationId: String,
    val locationLabel: String,
    val baselineId: String? = null
)

data class HomeLocation(
    val id: String,
    val label: String,
    val objects: MutableList<HomeObject> = mutableListOf()
)

data class HomeModel(
    val locations: MutableList<HomeLocation> = mutableListOf()
)

data class ObjectBaseline(
    val id: String,
    val objectId: String,
    val locationId: String,
    val establishedAtMs: Long,
    val sampleCount: Int,
    val meanFeatures: Map<String, Double>,
    val notes: String = ""
)

data class EvidenceBundle(
    val claim: String,
    val evidenceState: EvidenceState,
    val supporting: List<String>,
    val unknown: List<String>,
    val relatedEventIds: List<String>
)

data class SmritiAnswer(
    val text: String,
    val evidenceState: EvidenceState,
    val relatedEvents: List<PhysicalEvent>,
    val evidence: EvidenceBundle?,
    val suggestedActions: List<String> = emptyList(),
    /** True only when an on-device LLM rephrased the rule-grounded answer. */
    val usedLocalModel: Boolean = false,
    val modelName: String? = null,
    /** Language [text] is already written in ("en", "hi", "hinglish"); null means English rules text. */
    val language: String? = null
)

data class MemoryNodeState(
    val locationId: String,
    val label: String,
    val objectLabel: String,
    val pulse: Int,
    val latest: PhysicalEvent?,
    val xFrac: Float,
    val yFrac: Float,
    /** Latest reading looked unusual, but this place can't tell dry from damp yet. */
    val uncertain: Boolean = false
) {
    companion object {
        const val PULSE_NORMAL = 0
        const val PULSE_ACTIVE = 1
        const val PULSE_ANOMALY = 2
    }
}

data class HomeMemorySnapshot(
    val statusLine: String,
    val memoriesToday: Int,
    val anomaliesObserved: Int,
    val nodes: List<MemoryNodeState>,
    val disturbance: Float,
    val density: Float
)

data class MemoryQuery(
    val raw: String,
    val locationHint: String? = null,
    val objectHint: String? = null,
    val eventTypes: List<EventType> = emptyList(),
    val sinceMs: Long? = null,
    val untilMs: Long? = null,
    val intent: QueryIntent = QueryIntent.GENERAL
)

enum class QueryIntent {
    GENERAL,
    FIRST_OCCURRENCE,
    HAS_HAPPENED_BEFORE,
    COUNT_THIS_WEEK,
    WHAT_CHANGED_TODAY,
    IS_GETTING_WORSE,
    SHOW_EVIDENCE,
    IS_DEFINITE_LEAK,
    WHAT_IS_NORMAL
}
