package com.aquascope.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aquascope.R
import com.aquascope.baseline.AnomalyScorer
import com.aquascope.baseline.AnomalyThresholds
import com.aquascope.baseline.PlaceReliability
import com.aquascope.data.ScanLocation
import com.aquascope.databinding.ItemLocationBinding

class LocationAdapter(
    private val onScan: (ScanLocation) -> Unit,
    private val onHistory: (ScanLocation) -> Unit,
    private val onDelete: (ScanLocation) -> Unit
) : ListAdapter<ScanLocation, LocationAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScanLocation>() {
            override fun areItemsTheSame(a: ScanLocation, b: ScanLocation) = a.id == b.id
            override fun areContentsTheSame(a: ScanLocation, b: ScanLocation) = a == b
        }
    }

    inner class VH(private val b: ItemLocationBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(loc: ScanLocation) {
            val ctx = b.root.context
            b.textLabel.text = loc.label
            val last = loc.scanHistory.maxByOrNull { it.timestamp }
            val baseline = loc.baselineFeatures.size
            val scans = loc.scanHistory.size
            val ago = last?.let { relativeTime(it.timestamp) }

            b.textInfo.text = when {
                baseline == 0 -> ctx.getString(R.string.scan_info_needs_baseline)
                last == null -> ctx.getString(R.string.scan_info_ready, baseline)
                else -> ctx.getString(
                    R.string.scan_info_observed,
                    scans,
                    last.anomalyScore.toInt(),
                    ago
                )
            }

            val (status, statusColor) = when {
                baseline == 0 -> R.string.scan_pill_baseline to R.color.warm_muted
                last != null && loc.moistFeatures.isNotEmpty() ->
                    if (last.anomalyScore >= AnomalyThresholds.DAMP_SPLIT) R.string.scan_pill_alert to R.color.status_alert
                    else R.string.scan_pill_ready to R.color.cyan
                last != null && last.anomalyScore >= AnomalyThresholds.YELLOW_MAX ->
                    R.string.scan_pill_alert to R.color.status_alert
                last != null && last.anomalyScore >= AnomalyThresholds.GREEN_MAX ->
                    R.string.scan_pill_watch to R.color.amber
                else -> R.string.scan_pill_ready to R.color.cyan
            }
            b.textStatusPill.setText(status)
            b.textStatusPill.setTextColor(ContextCompat.getColor(ctx, statusColor))
            bindReliability(reliability[loc.id], loc)

            b.cardPlace.setOnClickListener { onScan(loc) }
            b.btnScan.setOnClickListener { onScan(loc) }
            b.btnHistory.setOnClickListener { onHistory(loc) }
            b.btnDelete.setOnClickListener { onDelete(loc) }
        }

        private fun bindReliability(status: PlaceReliability.Status?, loc: ScanLocation) {
            val ctx = b.root.context
            val check = status?.selfCheckPercent?.toInt() ?: 0
            val badge = when (status?.level) {
                null, PlaceReliability.Level.NO_BASELINE -> null
                PlaceReliability.Level.RELIABLE ->
                    ctx.getString(R.string.badge_reliable, check) to R.color.status_ok
                PlaceReliability.Level.UNRELIABLE ->
                    ctx.getString(R.string.badge_unreliable, check) to R.color.amber
                PlaceReliability.Level.PRELIMINARY -> ctx.getString(
                    R.string.badge_needs_damp,
                    PlaceReliability.MIN_DAMP_FOR_RELIABLE_SCORE - loc.moistFeatures.size
                ) to R.color.warm_faint
                PlaceReliability.Level.NOT_CHECKED -> ctx.getString(
                    R.string.badge_not_checked, AnomalyScorer.MIN_PER_CLASS_FOR_SELF_CHECK
                ) to R.color.warm_faint
            }
            b.textReliability.visibility = if (badge == null) View.GONE else View.VISIBLE
            badge?.let { (text, color) ->
                b.textReliability.text = text
                b.textReliability.setTextColor(ContextCompat.getColor(ctx, color))
            }
        }
    }

    private var reliability: Map<String, PlaceReliability.Status> = emptyMap()

    /** Reliability is computed off the main thread (the self-check can take a while), then handed in here. */
    fun setReliability(statuses: Map<String, PlaceReliability.Status>) {
        reliability = statuses
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemLocationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}

internal fun relativeTime(timestamp: Long): String {
    val delta = System.currentTimeMillis() - timestamp
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
        days == 1L -> "yesterday"
        else -> "$days days ago"
    }
}
