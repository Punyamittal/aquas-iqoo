package com.aquascope.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

/**
 * [ROLE] Cuts the impulse response down to the direct echo and grades its quality.
 * [WHY] Features computed over the whole 2 s response mostly measure room noise; the
 *       surface signature lives in the first tens of milliseconds after the direct arrival.
 */
object ImpulseWindow {

    const val PRE_MS = 1.0
    const val POST_MS = 40.0
    private const val FADE_MS = 2.0

    /** Below this the echo is not clearly above the noise floor; ask for a rescan. */
    const val MIN_PEAK_TO_NOISE_DB = 50.0

    /** Audio output→input latency is never this short; a peak here is an edge artefact. */
    private const val MIN_PEAK_MS = 1.0

    data class Result(
        val windowed: DoubleArray,
        val peakIndex: Int,
        val peakToNoiseDb: Double
    ) {
        fun isUsable(sampleRate: Int): Boolean =
            peakToNoiseDb >= MIN_PEAK_TO_NOISE_DB && peakIndex >= MIN_PEAK_MS * sampleRate / 1000.0
    }

    fun aroundDirectPath(impulseResponse: DoubleArray, sampleRate: Int): Result {
        if (impulseResponse.isEmpty()) return Result(doubleArrayOf(), 0, 0.0)
        val env = FeatureExtractor.hilbertEnvelope(impulseResponse)
        val peak = env.indices.maxByOrNull { env[it] } ?: 0
        val floor = median(env) + 1e-12
        val pnrDb = 20 * log10(env[peak] / floor)

        val start = (peak - (PRE_MS * sampleRate / 1000).toInt()).coerceAtLeast(0)
        val end = (peak + (POST_MS * sampleRate / 1000).toInt()).coerceAtMost(impulseResponse.size)
        val seg = impulseResponse.copyOfRange(start, end)
        val fade = minOf(seg.size / 8, (FADE_MS * sampleRate / 1000).toInt())
        if (fade > 0) {
            // Must stay identical to windowed() in tools/evaluate_extra_features.py (numpy.hanning).
            val m = 2 * fade
            for (k in 0 until fade) {
                val w = 0.5 - 0.5 * cos(2 * PI * (fade + k) / (m - 1))
                seg[seg.size - fade + k] *= w
            }
        }
        return Result(seg, peak, pnrDb)
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }
}
