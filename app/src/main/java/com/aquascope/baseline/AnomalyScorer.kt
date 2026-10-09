package com.aquascope.baseline

import com.aquascope.dsp.AcousticFeatures
import kotlin.math.*

/**
 * [ROLE] Anomaly scorer — dry baseline (+ optional moist teaching) → 0–100 moisture risk %.
 * [WHY] Turns impulse-response features into actionable green/yellow/red hazard bands.
 * [IQOO] Runs on-device after each chirp capture; loads optional trained_weights.json.
 * Uses weighted distance and/or 1-D LDA on the dry→moist axis. No staged demo score bands.
 */
object AnomalyScorer {

    private val DEFAULT_FEATURE_WEIGHTS = doubleArrayOf(
        2.5,  // resonanceFreqHz
        1.2,  // decayTimeMs
        2.0,  // spectralCentroidHz
        0.5,  // spectralSpreadHz
        1.5   // spectralFlatness
    )

    private val DEFAULT_FEATURE_SCALES = doubleArrayOf(
        800.0,
        80.0,
        3500.0,
        2500.0,
        0.45
    )

    /** Absolute near-duplicate thresholds (same units as AcousticFeatures). */
    private val DEDUPE_EPS = doubleArrayOf(80.0, 1.5, 80.0, 80.0, 0.02)

    private fun featureWeights(): DoubleArray =
        UltrasonicModelWeights.featureWeights ?: DEFAULT_FEATURE_WEIGHTS

    private fun defaultScales(): DoubleArray =
        UltrasonicModelWeights.featureScales ?: DEFAULT_FEATURE_SCALES

    fun score(scan: AcousticFeatures, baseline: AcousticFeatures): Double =
        score(scan, listOf(baseline), emptyList())

    /**
     * Score using dry baseline samples and optional moist teaching examples.
     * Always uses real LDA / distance scoring (no staged demo bands).
     *
     * [priorCompareCount] is ignored; kept for call-site compatibility.
     */
    fun score(
        scan: AcousticFeatures,
        drySamples: List<AcousticFeatures>,
        moistSamples: List<AcousticFeatures> = emptyList(),
        @Suppress("UNUSED_PARAMETER") priorCompareCount: Int = -1
    ): Double {
        if (drySamples.isEmpty()) return 0.0
        val dryMean = averageFeatures(drySamples)
        val scales = adaptiveScales(drySamples, moistSamples)

        if (moistSamples.isNotEmpty()) {
            val (dryClean, moistClean) = prepareTeaching(drySamples, moistSamples)
            val moistness = ldaMoistness(scan, dryClean, moistClean)
            val dryVals = dryClean.map { ldaMoistness(it, dryClean, moistClean) }
            val moistVals = moistClean.map { ldaMoistness(it, dryClean, moistClean) }
            val dq = UltrasonicModelWeights.dryPercentile.coerceIn(0.50, 0.90)
            val mq = UltrasonicModelWeights.moistPercentile.coerceIn(0.10, 0.50)
            val dryRef = percentile(dryVals, dq)
            val moistRef = percentile(moistVals, mq).coerceAtLeast(dryRef + 0.08)
            return calibratedPercent(moistness, dryRef, moistRef)
        }

        val distance = weightedDistance(scan, dryMean, scales)
        val gDry = UltrasonicModelWeights.dryRef
        val gMoist = UltrasonicModelWeights.moistRef
        if (gDry != null && gMoist != null && drySamples.size >= 2) {
            val dryDists = drySamples.map { weightedDistance(it, dryMean, scales) }
            val dryRef = max(gDry, percentile(dryDists, 0.75)).coerceAtLeast(0.08)
            val moistRef = max(gMoist, dryRef * 1.25)
            return calibratedPercent(distance, dryRef, moistRef)
        }
        return distanceToPercent(distance)
    }

    /**
     * Dedupe near-copies and drop samples closer to the other class than their own.
     * Falls back to deduped-only sets if cleaning would empty a class.
     */
    fun prepareTeaching(
        drySamples: List<AcousticFeatures>,
        moistSamples: List<AcousticFeatures>
    ): Pair<List<AcousticFeatures>, List<AcousticFeatures>> {
        val dry = dedupe(drySamples)
        val moist = dedupe(moistSamples)
        if (dry.isEmpty() || moist.isEmpty()) return dry to moist
        val dryMu = centerFeatures(dry).toDoubleArray()
        val moistMu = centerFeatures(moist).toDoubleArray()
        val scales = teachingNormScales(dry, moist)
        fun dist(sample: AcousticFeatures, mu: DoubleArray): Double {
            val a = sample.toDoubleArray()
            var s = 0.0
            for (i in a.indices) {
                val n = (a[i] - mu[i]) / scales[i]
                s += n * n
            }
            return sqrt(s)
        }
        val dryClean = dry.filter { dist(it, dryMu) <= dist(it, moistMu) }
        val moistClean = moist.filter { dist(it, moistMu) <= dist(it, dryMu) }
        return if (dryClean.isNotEmpty() && moistClean.isNotEmpty()) {
            dryClean to moistClean
        } else {
            dry to moist
        }
    }

    fun dedupe(samples: List<AcousticFeatures>): List<AcousticFeatures> {
        if (samples.size <= 1) return samples
        val kept = ArrayList<AcousticFeatures>(samples.size)
        for (s in samples) {
            val a = s.toDoubleArray()
            val dup = kept.any { k ->
                val b = k.toDoubleArray()
                var near = true
                for (i in a.indices) {
                    if (abs(a[i] - b[i]) > DEDUPE_EPS[i]) {
                        near = false
                        break
                    }
                }
                near
            }
            if (!dup) kept.add(s)
        }
        return kept
    }

    /**
     * 1-D LDA moistness. ~0 at dry mean, ~1 at moist mean (may go outside [0,1]).
     */
    fun ldaMoistness(
        sample: AcousticFeatures,
        drySamples: List<AcousticFeatures>,
        moistSamples: List<AcousticFeatures>
    ): Double {
        val dryMu = centerFeatures(drySamples).toDoubleArray()
        val moistMu = centerFeatures(moistSamples).toDoubleArray()
        val vd = featureVars(drySamples, dryMu)
        val vm = featureVars(moistSamples, moistMu)
        val nD = drySamples.size
        val nM = moistSamples.size
        val floors = defaultScales()
        val w = featureWeights()
        val x = sample.toDoubleArray()
        var num = 0.0
        var halfSep = 0.0
        for (i in x.indices) {
            val pooled = maxOf(
                (((nD - 1).coerceAtLeast(0) * vd[i] + (nM - 1).coerceAtLeast(0) * vm[i]) /
                    (nD + nM - 2).coerceAtLeast(1).toDouble()),
                (floors[i] * 0.15).pow(2.0),
                1e-9
            )
            val dir = (moistMu[i] - dryMu[i]) / pooled
            val mid = 0.5 * (dryMu[i] + moistMu[i])
            num += w[i] * dir * (x[i] - mid)
            halfSep += w[i] * dir * (moistMu[i] - dryMu[i]) * 0.5
        }
        if (abs(halfSep) < 1e-12) {
            // Means coincide — fall back to relative distance.
            val dryF = AcousticFeatures.fromDoubleArray(dryMu)
            val moistF = AcousticFeatures.fromDoubleArray(moistMu)
            val scales = adaptiveScales(drySamples, moistSamples)
            val dDry = weightedDistance(sample, dryF, scales)
            val dMoist = weightedDistance(sample, moistF, scales)
            return dDry / (dDry + dMoist + 1e-9)
        }
        // Map so dry centroid → 0, moist centroid → 1.
        val lda = num / halfSep // dry≈-1, moist≈+1
        return 0.5 * (lda + 1.0)
    }

    /** Legacy projection API kept for tests / callers. */
    fun moistureProjection(
        sample: AcousticFeatures,
        dryMean: AcousticFeatures,
        moistMean: AcousticFeatures,
        scales: DoubleArray
    ): Double {
        val a = sample.toDoubleArray()
        val d = dryMean.toDoubleArray()
        val m = moistMean.toDoubleArray()
        val w = featureWeights()
        var dirNormSq = 0.0
        var proj = 0.0
        for (i in a.indices) {
            val scale = scales[i].coerceAtLeast(1e-9)
            val dir = (m[i] - d[i]) / scale
            val x = (a[i] - d[i]) / scale
            dirNormSq += w[i] * dir * dir
            proj += w[i] * x * dir
        }
        if (dirNormSq < 1e-6) {
            val dDry = weightedDistance(sample, dryMean, scales)
            val dMoist = weightedDistance(sample, moistMean, scales)
            return dDry / (dDry + dMoist + 1e-9)
        }
        return proj / dirNormSq
    }

    fun blendedMoistness(
        sample: AcousticFeatures,
        dryMean: AcousticFeatures,
        moistMean: AcousticFeatures,
        scales: DoubleArray
    ): Double {
        val proj = moistureProjection(sample, dryMean, moistMean, scales)
        val blend = UltrasonicModelWeights.distanceBlend.coerceIn(0.0, 1.0)
        if (blend <= 0.0) return proj
        val dDry = weightedDistance(sample, dryMean, scales)
        val dMoist = weightedDistance(sample, moistMean, scales)
        val rel = dDry / (dDry + dMoist + 1e-9)
        return (1.0 - blend) * proj + blend * rel
    }

    fun averageFeatures(samples: List<AcousticFeatures>): AcousticFeatures {
        if (samples.isEmpty()) throw IllegalArgumentException("Empty sample list")
        val sums = DoubleArray(AcousticFeatures.NUM_FEATURES)
        for (s in samples) {
            val arr = s.toDoubleArray()
            for (i in sums.indices) sums[i] += arr[i]
        }
        for (i in sums.indices) sums[i] = sums[i] / samples.size
        return AcousticFeatures.fromDoubleArray(sums)
    }

    fun medianFeatures(samples: List<AcousticFeatures>): AcousticFeatures {
        if (samples.isEmpty()) throw IllegalArgumentException("Empty sample list")
        val cols = Array(AcousticFeatures.NUM_FEATURES) { DoubleArray(samples.size) }
        for ((si, s) in samples.withIndex()) {
            val a = s.toDoubleArray()
            for (i in a.indices) cols[i][si] = a[i]
        }
        val out = DoubleArray(AcousticFeatures.NUM_FEATURES) { i ->
            val sorted = cols[i].sorted()
            val mid = sorted.size / 2
            if (sorted.size % 2 == 1) sorted[mid]
            else 0.5 * (sorted[mid - 1] + sorted[mid])
        }
        return AcousticFeatures.fromDoubleArray(out)
    }

    fun centerFeatures(samples: List<AcousticFeatures>): AcousticFeatures =
        if (UltrasonicModelWeights.useMedian) medianFeatures(samples) else averageFeatures(samples)

    fun adaptiveScales(
        drySamples: List<AcousticFeatures>,
        moistSamples: List<AcousticFeatures> = emptyList()
    ): DoubleArray {
        val floor = defaultScales()
        if (drySamples.isEmpty()) return floor.copyOf()
        val dryMean = averageFeatures(drySamples).toDoubleArray()
        val std = if (drySamples.size >= 2) {
            val varSum = DoubleArray(AcousticFeatures.NUM_FEATURES)
            for (s in drySamples) {
                val a = s.toDoubleArray()
                for (i in a.indices) {
                    val d = a[i] - dryMean[i]
                    varSum[i] += d * d
                }
            }
            val denom = (drySamples.size - 1).toDouble()
            DoubleArray(AcousticFeatures.NUM_FEATURES) { i -> sqrt(varSum[i] / denom) }
        } else {
            DoubleArray(AcousticFeatures.NUM_FEATURES) { 0.0 }
        }

        val sep = if (moistSamples.isNotEmpty()) {
            val moistMean = averageFeatures(moistSamples).toDoubleArray()
            DoubleArray(AcousticFeatures.NUM_FEATURES) { i -> abs(moistMean[i] - dryMean[i]) }
        } else {
            DoubleArray(AcousticFeatures.NUM_FEATURES) { 0.0 }
        }

        return DoubleArray(AcousticFeatures.NUM_FEATURES) { i ->
            val fromStd = if (drySamples.size >= 2) std[i] * 1.75 else floor[i]
            val fromSep = if (sep[i] > 0.0) sep[i] * 0.85 else Double.POSITIVE_INFINITY
            val raw = if (moistSamples.isNotEmpty()) {
                min(fromStd, fromSep).coerceAtLeast(floor[i] * 0.25)
            } else {
                max(floor[i] * 0.4, fromStd)
            }
            raw.coerceAtLeast(1e-6)
        }
    }

    private fun teachingNormScales(
        dry: List<AcousticFeatures>,
        moist: List<AcousticFeatures>
    ): DoubleArray {
        val floor = defaultScales()
        val all = dry + moist
        val mu = averageFeatures(all).toDoubleArray()
        val v = featureVars(all, mu)
        return DoubleArray(AcousticFeatures.NUM_FEATURES) { i ->
            maxOf(sqrt(v[i]), floor[i] * 0.25, 1e-6)
        }
    }

    private fun featureVars(samples: List<AcousticFeatures>, mu: DoubleArray): DoubleArray {
        if (samples.size < 2) {
            return DoubleArray(AcousticFeatures.NUM_FEATURES) { i ->
                (defaultScales()[i] * 0.5).pow(2.0)
            }
        }
        val acc = DoubleArray(AcousticFeatures.NUM_FEATURES)
        for (s in samples) {
            val a = s.toDoubleArray()
            for (i in a.indices) {
                val d = a[i] - mu[i]
                acc[i] += d * d
            }
        }
        val denom = (samples.size - 1).toDouble()
        return DoubleArray(AcousticFeatures.NUM_FEATURES) { i -> acc[i] / denom }
    }

    private fun weightedDistance(
        sample: AcousticFeatures,
        mean: AcousticFeatures,
        scales: DoubleArray
    ): Double {
        val a = sample.toDoubleArray()
        val m = mean.toDoubleArray()
        val w = featureWeights()
        var sumSq = 0.0
        for (i in a.indices) {
            val scale = scales[i].coerceAtLeast(1e-9)
            val n = (a[i] - m[i]) / scale
            sumSq += w[i] * n * n
        }
        return sqrt(sumSq)
    }

    /**
     * Map value using dry/moist anchors.
     * dryRef → ~22% (green), moistRef → ~78% (clear moist/anomaly).
     */
    fun calibratedPercent(distance: Double, dryRef: Double, moistRef: Double): Double {
        val d = distance.coerceAtLeast(0.0)
        val low = dryRef.coerceAtLeast(1e-6)
        val high = moistRef.coerceAtLeast(low * 1.01)
        return when {
            d <= low -> (22.0 * (d / low)).coerceIn(0.0, 25.0)
            d >= high -> {
                val extra = (d - high) / high
                (78.0 + 22.0 * (1.0 - exp(-extra))).coerceIn(78.0, 100.0)
            }
            else -> {
                val t = (d - low) / (high - low)
                (22.0 + t * (78.0 - 22.0)).coerceIn(22.0, 78.0)
            }
        }
    }

    private fun distanceToPercent(distance: Double): Double {
        val k = 0.40
        val raw = 100.0 * (1.0 - exp(-k * distance.coerceAtLeast(0.0)))
        return raw.coerceIn(0.0, 100.0)
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val idx = ((sorted.size - 1) * p.coerceIn(0.0, 1.0))
        val lo = floor(idx).toInt()
        val hi = ceil(idx).toInt()
        if (lo == hi) return sorted[lo]
        val t = idx - lo
        return sorted[lo] * (1 - t) + sorted[hi] * t
    }
}

object AnomalyThresholds {
    const val GREEN_MAX = 30.0
    /** Monster Halo splits mild amber vs high amber-red at this score. */
    const val HALO_HIGH = 50.0
    const val YELLOW_MAX = 70.0
}
