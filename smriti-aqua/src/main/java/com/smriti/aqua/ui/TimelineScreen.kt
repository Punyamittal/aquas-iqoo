package com.smriti.aqua.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smriti.aqua.memory.Event
import com.smriti.aqua.memory.EventStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Memory tab: day-grouped replay of stored episodic events. */
@Composable
fun TimelineScreen(vm: ScanViewModel) {
    val events by vm.events.collectAsState()
    val dayFormat = remember { SimpleDateFormat("EEE, MMM d", Locale.US) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.US) }
    val grouped = remember(events) { events.groupBy { dayFormat.format(Date(it.timestamp)) } }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            "Memory replay",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier
                .padding(bottom = 16.dp)
                .semantics { heading() },
        )
        if (events.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No memories yet. Calibrate, then inspect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                grouped.forEach { (day, dayEvents) ->
                    item(key = "day-$day") {
                        Text(
                            day,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = WarmMuted,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(dayEvents, key = { it.id }) { event ->
                        EventRow(event, timeFormat)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: Event, timeFormat: SimpleDateFormat) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Hairline),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(event.status)
                Text(
                    timeFormat.format(Date(event.timestamp)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Box(modifier = Modifier.weight(1f))
                Text(
                    "Event ${event.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (event.status == EventStatus.NORMAL || event.status == EventStatus.ANOMALY
                || event.status == EventStatus.CONFIRMED
            ) {
                DeviationBar(event.deviation)
                Text(
                    "Deviation ${pct(event.deviation)}, coherence ${pct(event.coherence)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (event.note.isNotEmpty()) {
                Text(event.note, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun StatusChip(status: EventStatus) {
    val (bg, fg) = when (status) {
        EventStatus.BASELINE -> NavyMid to WarmMuted
        EventStatus.NORMAL -> CyanSoft to Cyan
        EventStatus.ANOMALY -> AmberSoft to Amber
        EventStatus.CONFIRMED -> AlertSoft to Alert
    }
    val label = when (status) {
        EventStatus.BASELINE -> "Baseline"
        EventStatus.NORMAL -> "Normal"
        EventStatus.ANOMALY -> "Unusual"
        EventStatus.CONFIRMED -> "Confirmed"
    }
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Text(
            label,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun DeviationBar(deviation: Float) {
    val frac = deviation.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(NavyMid),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(frac)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (frac > 0.45f) Amber else Cyan),
        )
    }
}

private fun pct(v: Float): String = String.format(Locale.US, "%.2f", v)
