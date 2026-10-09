package com.aquascope.smriti.engine

import com.aquascope.baseline.AnomalyThresholds
import com.aquascope.dsp.AcousticFeatures
import com.aquascope.smriti.memory.EpisodicMemoryStore
import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.EventStatus
import com.aquascope.smriti.model.EventType
import com.aquascope.smriti.model.HomeLocation
import com.aquascope.smriti.model.HomeObject
import com.aquascope.smriti.model.ObjectBaseline
import com.aquascope.smriti.model.PhysicalEvent
import com.aquascope.smriti.model.SensorType
import java.util.UUID
import kotlin.math.abs

/**
 * [ROLE] Converts AquaScope scans into SMRITI PhysicalEvents.
 * [WHY] Memory must stay honest — OBSERVED deviations only; never invent confirmed leaks.
 * [IQOO] Feeds Ask + timeline after every successful contact scan.
 */
class EventNormalizer(
    private val store: EpisodicMemoryStore
) {

    fun ensureHomeObject(locationId: String, locationLabel: String): HomeObject {
        val home = store.loadHome()
        var loc = home.locations.find { it.id == locationId }
        if (loc == null) {
            loc = HomeLocation(id = locationId, label = locationLabel)
            home.locations.add(loc)
        } else if (loc.label != locationLabel) {
            // keep label fresh
            val idx = home.locations.indexOf(loc)
            loc = loc.copy(label = locationLabel, objects = loc.objects)
            home.locations[idx] = loc
        }
        var obj = loc.objects.find { it.locationId == locationId }
        if (obj == null) {
            obj = HomeObject(
                id = "OBJ_$locationId",
                label = locationLabel,
                locationId = locationId,
                locationLabel = locationLabel,
                baselineId = "BASE_$locationId"
            )
            loc.objects.add(obj)
        }
        store.saveHome(home)
        return obj
    }

    fun fromBaselineSaved(
        locationId: String,
        locationLabel: String,
        features: AcousticFeatures,
        sampleCount: Int
    ): PhysicalEvent {
        val obj = ensureHomeObject(locationId, locationLabel)
        val baseline = ObjectBaseline(
            id = "BASE_$locationId",
            objectId = obj.id,
            locationId = locationId,
            establishedAtMs = System.currentTimeMillis(),
            sampleCount = sampleCount,
            meanFeatures = features.toMap(),
            notes = "Dry acoustic baseline from AquaScope"
        )
        store.upsertBaseline(baseline)

        return PhysicalEvent(
            id = newId(),
            timestampMs = System.currentTimeMillis(),
            locationId = locationId,
            locationLabel = locationLabel,
            objectId = obj.id,
            objectLabel = obj.label,
            sensorType = SensorType.ACOUSTIC,
            eventType = EventType.BASELINE_ESTABLISHED,
            anomalyScore = 0.0,
            confidence = 1.0,
            baselineId = baseline.id,
            features = features.toMap(),
            summary = "Baseline established for $locationLabel.",
            status = EventStatus.RESOLVED,
            evidenceState = EvidenceState.OBSERVED,
            evidenceNotes = listOf("AquaScope dry calibration saved ($sampleCount sample(s))."),
            unknownNotes = emptyList()
        )
    }

    fun fromObservation(
        locationLabel: String,
        summary: String,
        source: String,
        eventType: EventType,
        anomalyScore: Double,
        evidenceNotes: List<String>
    ): PhysicalEvent {
        val locationId = "LOC_" + locationLabel.uppercase()
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
            .take(24)
            .ifBlank { "PHONE" }
        val obj = ensureHomeObject(locationId, locationLabel)
        val notes = evidenceNotes.ifEmpty { listOf(summary) }
        return PhysicalEvent(
            id = newId(),
            timestampMs = System.currentTimeMillis(),
            locationId = locationId,
            locationLabel = locationLabel,
            objectId = obj.id,
            objectLabel = obj.label,
            sensorType = SensorType.ACOUSTIC,
            eventType = eventType,
            anomalyScore = anomalyScore,
            confidence = (anomalyScore / 100.0).coerceIn(0.0, 1.0),
            baselineId = null,
            summary = summary,
            status = EventStatus.UNCONFIRMED,
            evidenceState = EvidenceState.OBSERVED,
            evidenceNotes = notes,
            unknownNotes = listOf("Observation only — not a confirmed leak."),
            source = source
        )
    }

    fun fromScan(
        locationId: String,
        locationLabel: String,
        features: AcousticFeatures,
        anomalyScore: Double,
        hasBaseline: Boolean
    ): PhysicalEvent {
        val obj = ensureHomeObject(locationId, locationLabel)
        val prior = store.eventsForObject(obj.id)
            .filter {
                it.eventType == EventType.ACOUSTIC_DEVIATION ||
                    it.eventType == EventType.ANOMALY ||
                    it.eventType == EventType.REPEATED_ANOMALY ||
                    it.eventType == EventType.POSSIBLE_LEAK
            }
        val dayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val yesterday = prior.firstOrNull { it.timestampMs >= dayAgo }
        val deviationVsYesterday = yesterday?.let { anomalyScore - it.anomalyScore }

        val eventType = classify(anomalyScore, prior.size, hasBaseline)
        val evidenceState = when (eventType) {
            EventType.NORMAL, EventType.ACOUSTIC_DEVIATION, EventType.ANOMALY,
            EventType.REPEATED_ANOMALY, EventType.BASELINE_ESTABLISHED -> EvidenceState.OBSERVED
            EventType.POSSIBLE_LEAK -> EvidenceState.POSSIBLE
            else -> EvidenceState.UNKNOWN
        }

        val evidenceNotes = buildList {
            add("AquaScope anomaly score ${anomalyScore.toInt()}% vs local baseline.")
            add(
                "Features: resonance ${features.resonanceFreqHz.toInt()} Hz, " +
                    "decay ${"%.1f".format(features.decayTimeMs)} ms, " +
                    "centroid ${features.spectralCentroidHz.toInt()} Hz."
            )
            if (prior.isNotEmpty()) add("${prior.size} prior deviation(s) recorded for this object.")
            deviationVsYesterday?.let {
                add("Score change vs last 24h observation: ${"%+.0f".format(it)} points.")
            }
        }

        val unknownNotes = buildList {
            add("No visual confirmation of a physical leak.")
            add("No external flow meter reading.")
            if (eventType == EventType.POSSIBLE_LEAK) {
                add("Anomaly ≠ confirmed leak; user inspection recommended.")
            }
        }

        val summary = when (eventType) {
            EventType.NORMAL ->
                "Acoustic scan at $locationLabel within normal range vs baseline."
            EventType.ACOUSTIC_DEVIATION, EventType.ANOMALY ->
                "Acoustic behavior at $locationLabel deviated from baseline (${anomalyScore.toInt()}%)."
            EventType.REPEATED_ANOMALY ->
                "Repeated acoustic deviation at $locationLabel (${anomalyScore.toInt()}%; ${prior.size} prior)."
            EventType.POSSIBLE_LEAK ->
                "Repeated abnormal acoustics at $locationLabel — possible abnormal water flow (unconfirmed)."
            else ->
                "Acoustic observation at $locationLabel (${anomalyScore.toInt()}%)."
        }

        return PhysicalEvent(
            id = newId(),
            timestampMs = System.currentTimeMillis(),
            locationId = locationId,
            locationLabel = locationLabel,
            objectId = obj.id,
            objectLabel = obj.label,
            sensorType = SensorType.ACOUSTIC,
            eventType = eventType,
            anomalyScore = anomalyScore,
            confidence = (anomalyScore / 100.0).coerceIn(0.0, 1.0),
            baselineId = "BASE_$locationId",
            features = features.toMap(),
            previousOccurrenceCount = prior.size,
            deviationVsYesterday = deviationVsYesterday,
            summary = summary,
            status = EventStatus.UNCONFIRMED,
            evidenceState = evidenceState,
            evidenceNotes = evidenceNotes,
            unknownNotes = unknownNotes
        )
    }

    private fun classify(score: Double, priorAnomalies: Int, hasBaseline: Boolean): EventType {
        if (!hasBaseline) return EventType.UNKNOWN
        return when {
            score < AnomalyThresholds.GREEN_MAX -> EventType.NORMAL
            score >= AnomalyThresholds.YELLOW_MAX && priorAnomalies >= 2 -> EventType.POSSIBLE_LEAK
            score >= AnomalyThresholds.YELLOW_MAX && priorAnomalies >= 1 -> EventType.REPEATED_ANOMALY
            score >= AnomalyThresholds.YELLOW_MAX -> EventType.ANOMALY
            abs(score - AnomalyThresholds.GREEN_MAX) < 0.01 -> EventType.ACOUSTIC_DEVIATION
            else -> EventType.ACOUSTIC_DEVIATION
        }
    }

    private fun AcousticFeatures.toMap() = mapOf(
        "resonanceFreqHz" to resonanceFreqHz,
        "decayTimeMs" to decayTimeMs,
        "spectralCentroidHz" to spectralCentroidHz,
        "spectralSpreadHz" to spectralSpreadHz,
        "spectralFlatness" to spectralFlatness
    )

    private fun newId() = "EVT_${UUID.randomUUID().toString().take(8).uppercase()}"
}
