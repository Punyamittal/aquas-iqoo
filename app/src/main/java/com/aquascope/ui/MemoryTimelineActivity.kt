package com.aquascope.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aquascope.R
import com.aquascope.databinding.ActivityMemoryTimelineBinding
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.model.PhysicalEvent
import java.util.Calendar

class MemoryTimelineActivity : SmritiScreenActivity() {

    private lateinit var binding: ActivityMemoryTimelineBinding
    private var range = RANGE_TODAY

    companion object {
        const val RANGE_TODAY = 0
        const val RANGE_WEEK = 1
        const val RANGE_MONTH = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoryTimelineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = getColor(R.color.paper_sky)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        SmritiNav.bind(this, binding.bottomNav, SmritiNav.TAB_MEMORY)
        binding.recyclerMemory.layoutManager = LinearLayoutManager(this)
        binding.chipToday.setOnClickListener { setRange(RANGE_TODAY) }
        binding.chipWeek.setOnClickListener { setRange(RANGE_WEEK) }
        binding.chipMonth.setOnClickListener { setRange(RANGE_MONTH) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun setRange(value: Int) {
        range = value
        styleChip(binding.chipToday, range == RANGE_TODAY)
        styleChip(binding.chipWeek, range == RANGE_WEEK)
        styleChip(binding.chipMonth, range == RANGE_MONTH)
        refresh()
    }

    private fun styleChip(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_chip_light_selected else R.drawable.bg_chip_light
        )
        view.setTextColor(
            ContextCompat.getColor(this, if (selected) R.color.warm_white else R.color.paper_ink)
        )
        view.isSelected = selected
    }

    private fun refresh() {
        val since = when (range) {
            RANGE_TODAY -> startDaysAgo(0)
            RANGE_WEEK -> startDaysAgo(7)
            else -> startDaysAgo(30)
        }
        val events = SmritiCore.get(this).eventsSince(since)
        val periodAnomalies = events.count { EventCopy.isAnomaly(it.eventType) }
        val disturbance = if (periodAnomalies == 0) 0.08f else (periodAnomalies / 5f).coerceIn(0.2f, 1f)
        val density = (events.size / 12f).coerceIn(0.2f, 1f)
        binding.memoryFieldBg.setField(emptyList(), disturbance, density)

        binding.textEmptyMemory.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerMemory.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
        binding.recyclerMemory.adapter = MemoryAdapter(buildRows(events)) { e ->
            startActivity(Intent(this, MemoryDetailActivity::class.java).putExtra("event_id", e.id))
        }
    }

    private fun startDaysAgo(days: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (days > 0) cal.add(Calendar.DAY_OF_YEAR, -days)
        return cal.timeInMillis
    }

    private fun buildRows(events: List<PhysicalEvent>): List<MemoryRow> {
        val rows = mutableListOf<MemoryRow>()
        var lastDay = ""
        events.forEach { e ->
            val day = EventCopy.day(e.timestampMs)
            if (day != lastDay) {
                rows += MemoryRow.Header(day)
                lastDay = day
            }
            rows += MemoryRow.Event(e)
        }
        return rows
    }
}

sealed class MemoryRow {
    data class Header(val label: String) : MemoryRow()
    data class Event(val event: PhysicalEvent) : MemoryRow()
}

class MemoryAdapter(
    private val rows: List<MemoryRow>,
    private val onClick: (PhysicalEvent) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_EVENT = 1
    }

    override fun getItemViewType(position: Int) =
        if (rows[position] is MemoryRow.Header) TYPE_HEADER else TYPE_EVENT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inf.inflate(R.layout.item_memory_date, parent, false))
        } else {
            EventVH(inf.inflate(R.layout.item_memory_event, parent, false))
        }
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is MemoryRow.Header -> (holder as HeaderVH).title.text = row.label
            is MemoryRow.Event -> {
                val e = row.event
                val h = holder as EventVH
                h.time.text = EventCopy.time(e.timestampMs)
                h.place.text = e.locationLabel
                h.summary.text = EventCopy.type(e.eventType)
                val anomaly = EventCopy.isAnomaly(e.eventType)
                h.score.text = when {
                    e.anomalyScore > 0 -> "%d%%".format(e.anomalyScore.toInt())
                    else -> "Normal"
                }
                h.score.contentDescription = if (e.anomalyScore > 0) {
                    "Anomaly ${e.anomalyScore.toInt()} percent"
                } else "Normal"
                h.score.setTextColor(
                    ContextCompat.getColor(
                        h.itemView.context,
                        if (anomaly) R.color.amber else R.color.cyan
                    )
                )
                h.itemView.setOnClickListener { onClick(e) }
            }
        }
    }

    class HeaderVH(root: View) : RecyclerView.ViewHolder(root) {
        val title: TextView = root.findViewById(R.id.textDateHeader)
    }

    class EventVH(root: View) : RecyclerView.ViewHolder(root) {
        val time: TextView = root.findViewById(R.id.textMemTime)
        val place: TextView = root.findViewById(R.id.textMemPlace)
        val summary: TextView = root.findViewById(R.id.textMemSummary)
        val score: TextView = root.findViewById(R.id.textMemScore)
    }
}
