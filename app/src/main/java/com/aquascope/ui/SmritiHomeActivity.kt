package com.aquascope.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.aquascope.R
import com.aquascope.databinding.ActivitySmritiHomeBinding
import com.aquascope.baseline.AnomalyThresholds
import com.aquascope.halo.SmritiLightMapper
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.model.HomeMemorySnapshot
import com.aquascope.smriti.model.MemoryNodeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmritiHomeActivity : SmritiScreenActivity() {

    private lateinit var binding: ActivitySmritiHomeBinding
    private lateinit var smriti: SmritiCore
    private lateinit var haloBind: HaloBinding
    private var selectedEventId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmritiHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = getColor(R.color.paper_sky)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        smriti = SmritiCore.get(this)
        SmritiNav.bind(this, binding.bottomNav, SmritiNav.TAB_HOME)
        haloBind = HaloBinding(this, binding.haloIndicator)

        binding.memoryField.onNodeTap = { node ->
            val snap = smriti.homeMemorySnapshot()
            val state = snap.nodes.find { it.locationId == node.id }
            showNode(state)
        }
        binding.btnExploreMemory.setOnClickListener {
            val id = selectedEventId
            if (id != null) {
                startActivity(Intent(this, MemoryDetailActivity::class.java).putExtra("event_id", id))
            } else {
                startActivity(Intent(this, MemoryTimelineActivity::class.java))
            }
        }
        binding.btnScanWall.setOnClickListener {
            SmritiNav.goToTab(this, SmritiNav.TAB_HOME, SmritiNav.TAB_SCAN)
        }
        binding.btnAskHome.setOnClickListener {
            SmritiNav.goToTab(this, SmritiNav.TAB_HOME, SmritiNav.TAB_ASK)
        }
        binding.btnSmritiAqua.setOnClickListener { SmritiNav.openSmritiAqua(this) }
        binding.btnScreenMind.setOnClickListener { SmritiNav.openScreenMind(this) }
        binding.headerHome.bringToFront()
        binding.haloIndicator.bringToFront()
        binding.actionDock.bringToFront()
        binding.bottomNav.bringToFront()
    }

    override fun onResume() {
        super.onResume()
        haloBind.start()
        refresh()
    }

    override fun onPause() {
        haloBind.stop()
        super.onPause()
    }

    private fun refresh() {
        val snap = smriti.homeMemorySnapshot()
        binding.textHomeDate.text =
            SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
        bindStatus(snap)

        val fieldNodes = snap.nodes.map {
            MemoryFieldView.FieldNode(
                id = it.locationId,
                label = it.label,
                state = it.pulse,
                xFrac = it.xFrac,
                yFrac = it.yFrac
            )
        }
        binding.memoryField.setField(fieldNodes, snap.disturbance, snap.density)

        val maxScore = snap.nodes.mapNotNull { node ->
            node.latest?.anomalyScore?.takeIf { node.pulse == MemoryNodeState.PULSE_ANOMALY }
        }.maxOrNull() ?: 0.0
        val light = SmritiLightMapper.fromHomeStatus(
            snap.statusLine,
            snap.anomaliesObserved,
            maxScore
        )
        haloBind.controller().setState(light)
        binding.memoryField.syncExpression(light)

        if (snap.nodes.isEmpty()) {
            binding.nodePanel.visibility = View.GONE
        }
    }

    private fun bindStatus(snap: HomeMemorySnapshot) {
        val status = snap.statusLine.uppercase(Locale.US)
        val flagged = snap.nodes
            .filter { it.pulse == MemoryNodeState.PULSE_ANOMALY }
            .maxByOrNull { it.latest?.anomalyScore ?: 0.0 }
        when {
            status.startsWith("AWAITING") -> {
                binding.textHomeStatus.text = getString(R.string.home_status_empty)
                binding.textHomeStatus.setTextColor(getColor(R.color.paper_ink))
                binding.textHomeSupporting.text = getString(R.string.home_status_empty_support)
            }
            status.contains("OBSERVATION") -> {
                binding.textHomeStatus.text = flagged
                    ?.let { getString(R.string.home_status_attention_named, it.label) }
                    ?: getString(R.string.home_status_attention)
                binding.textHomeStatus.setTextColor(getColor(R.color.status_alert))
                binding.textHomeSupporting.text = getString(R.string.home_support_attention)
            }
            else -> {
                binding.textHomeStatus.text = getString(R.string.home_status_normal)
                binding.textHomeStatus.setTextColor(getColor(R.color.paper_ink))
                val uncertain = snap.nodes.filter { it.uncertain }
                binding.textHomeSupporting.text = if (uncertain.isNotEmpty()) {
                    getString(R.string.home_support_uncertain, uncertain.joinToString(", ") { it.label })
                } else if (snap.memoriesToday == 0) {
                    getString(R.string.home_support_no_scans_today)
                } else {
                    resources.getQuantityString(
                        R.plurals.home_support_scans_today,
                        snap.memoriesToday,
                        snap.memoriesToday
                    )
                }
            }
        }
    }

    private fun showNode(state: MemoryNodeState?) {
        if (state == null) {
            binding.nodePanel.visibility = View.GONE
            return
        }
        val wasHidden = binding.nodePanel.visibility != View.VISIBLE
        binding.nodePanel.visibility = View.VISIBLE
        if (wasHidden) revealPanel()
        binding.memoryField.setSelected(state.locationId)
        binding.textNodeTitle.text = state.label
        binding.textNodeObject.text = state.objectLabel
        val latest = state.latest
        selectedEventId = latest?.id

        val (stateText, stateColor) = when {
            latest == null -> R.string.home_node_not_scanned to R.color.warm_muted
            state.uncertain -> R.string.scan_state_unreliable to R.color.warm_muted
            state.pulse == MemoryNodeState.PULSE_ANOMALY ->
                R.string.home_node_unusual to
                    if (latest.anomalyScore >= AnomalyThresholds.HALO_HIGH) R.color.status_alert
                    else R.color.amber
            state.pulse == MemoryNodeState.PULSE_ACTIVE -> R.string.home_node_today to R.color.cyan
            else -> R.string.home_node_normal to R.color.cyan
        }
        binding.textNodeState.setText(stateText)
        binding.textNodeState.setTextColor(getColor(stateColor))
        binding.textNodeDeviation.visibility = if (latest != null) View.VISIBLE else View.GONE
        if (latest != null) {
            val whenText = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
                .format(Date(latest.timestampMs))
            binding.textNodeDeviation.text = getString(
                R.string.home_node_score,
                latest.anomalyScore.toInt(),
                whenText
            )
        }
        binding.btnExploreMemory.visibility = if (latest != null) View.VISIBLE else View.GONE

        val nodeLight = when (state.pulse) {
            MemoryNodeState.PULSE_ANOMALY ->
                SmritiLightMapper.fromScore(
                    (latest?.anomalyScore ?: 0.0).coerceAtLeast(AnomalyThresholds.GREEN_MAX)
                )
            MemoryNodeState.PULSE_ACTIVE -> SmritiLightState.MEMORY_RECALL
            else -> SmritiLightState.NORMAL
        }
        haloBind.controller().setState(nodeLight)
        binding.memoryField.syncExpression(nodeLight)
    }

    private fun revealPanel() {
        val panel = binding.nodePanel
        panel.alpha = 0f
        panel.translationY = 12f * resources.displayMetrics.density
        panel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200L)
            .setInterpolator(android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
            .start()
    }
}
