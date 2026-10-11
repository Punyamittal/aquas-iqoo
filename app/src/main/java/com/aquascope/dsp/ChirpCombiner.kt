package com.aquascope.dsp

import kotlin.math.abs

/**
 * A scan plays several chirps; each gives its own features. The scan's features are the
 * per-feature median, so one chirp spoiled by a knock or a slip doesn't move the result.
 */
object ChirpCombiner {

    fun median(chirps: List<AcousticFeatures>): AcousticFeatures {
        require(chirps.isNotEmpty())
        val rows = chirps.map { it.toDoubleArray() }
        return AcousticFeatures.fromDoubleArray(DoubleArray(AcousticFeatures.NUM_FEATURES) { i ->
            val sorted = rows.map { it[i] }.sorted()
            val mid = sorted.size / 2
            if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        })
    }

    /** Index of the chirp closest to [target], measured in relative change per feature. */
    fun closestIndex(chirps: List<AcousticFeatures>, target: AcousticFeatures): Int {
        val t = target.toDoubleArray()
        return chirps.indices.minByOrNull { k ->
            val x = chirps[k].toDoubleArray()
            x.indices.sumOf { abs(x[it] - t[it]) / (abs(t[it]) + 1e-9) }
        } ?: 0
    }
}
