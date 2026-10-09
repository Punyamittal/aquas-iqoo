package com.aquascope.ui

import com.aquascope.smriti.model.EventStatus
import com.aquascope.smriti.model.EventType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** User-facing wording for memory events, shared by the Memory, detail and evidence screens. */
object EventCopy {

    fun type(type: EventType): String = when (type) {
        EventType.NORMAL -> "Normal reading"
        EventType.ANOMALY -> "Unusual reading"
        EventType.ACOUSTIC_DEVIATION -> "Echo changed from baseline"
        EventType.WATER_FLOW -> "Water flow heard"
        EventType.FLOW_STOPPED -> "Water flow stopped"
        EventType.POSSIBLE_LEAK -> "Possible leak"
        EventType.REPEATED_ANOMALY -> "Unusual again"
        EventType.BASELINE_ESTABLISHED -> "Dry baseline saved"
        EventType.BASELINE_CHANGED -> "Baseline updated"
        EventType.UNKNOWN -> "Reading"
    }

    fun status(status: EventStatus): String = when (status) {
        EventStatus.UNCONFIRMED -> "Not confirmed yet"
        EventStatus.UNRESOLVED -> "Still open"
        EventStatus.USER_CONFIRMED -> "Confirmed by you"
        EventStatus.USER_DISMISSED -> "Dismissed by you"
        EventStatus.USER_CORRECTED -> "Corrected by you"
        EventStatus.RESOLVED -> "Resolved"
    }

    fun isAnomaly(type: EventType): Boolean =
        type == EventType.ANOMALY ||
            type == EventType.ACOUSTIC_DEVIATION ||
            type == EventType.REPEATED_ANOMALY ||
            type == EventType.POSSIBLE_LEAK

    fun day(timestampMs: Long): String {
        val today = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val sameYear = today.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val dayDelta = today.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDelta == 0 -> "Today"
            sameYear && dayDelta == 1 -> "Yesterday"
            else -> SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date(timestampMs))
        }
    }

    fun time(timestampMs: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))

    fun dateTime(timestampMs: Long): String =
        SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(timestampMs))
}
