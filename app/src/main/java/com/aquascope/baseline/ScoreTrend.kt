package com.aquascope.baseline

import kotlin.math.abs

/** Direction of a place's scores over its most recent scans, for the History trend chart. */
object ScoreTrend {

    enum class Direction { TOO_FEW, RISING, FALLING, STEADY }

    data class Summary(val direction: Direction, val recentMedian: Double?, val earlierMedian: Double?)

    const val WINDOW = 5
    /** Median change (points) between the last [WINDOW] scans and the [WINDOW] before them that counts as a trend. */
    const val MIN_CHANGE = 10.0

    fun summarize(chronological: List<Double>): Summary {
        if (chronological.size < 2 * WINDOW) return Summary(Direction.TOO_FEW, null, null)
        val recent = median(chronological.takeLast(WINDOW))
        val earlier = median(chronological.dropLast(WINDOW).takeLast(WINDOW))
        val change = recent - earlier
        val direction = when {
            abs(change) < MIN_CHANGE -> Direction.STEADY
            change > 0 -> Direction.RISING
            else -> Direction.FALLING
        }
        return Summary(direction, recent, earlier)
    }

    /** Median of each scan and the up-to-[window] − 1 scans before it. */
    fun rollingMedian(values: List<Double>, window: Int = WINDOW): List<Double> =
        values.indices.map { i -> median(values.subList((i - window + 1).coerceAtLeast(0), i + 1)) }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }
}
