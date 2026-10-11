package com.aquascope.baseline

import com.aquascope.dsp.AcousticFeatures
import kotlin.math.*

/**
 * [ROLE] Anomaly scorer — dry baseline (+ optional moist teaching) → 0–100 moisture risk %.
 * [WHY] Turns impulse-response features into actionable green/yellow/red hazard bands.
 * [IQOO] Runs on-device after each chirp capture; loads optional trained_weights.json.
 * Dry only: weighted distance from the dry centre. With damp examples: a hybrid of the 1-D LDA
 * dry→moist score and the distance to the dry and damp centres, on the feature combination that
 * separates that place best. No staged demo score bands.
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

        if (moistSamples.isNotEmpty()) {
            val dry = dedupe(drySamples).map { it.toDoubleArray() }
            val moist = dedupe(moistSamples).map { it.toDoubleArray() }
            val features = cachedSelection(dry, moist)
            return HybridModel.fit(dry, moist, features).percent(scan.toDoubleArray())
        }

        // Fixed default spreads at every dry count: spreads measured from 2-4 dry scans come out far
        // too tight (chirp-to-chirp noise alone exceeds them), which made the 2nd dry scan score ~90%.
        return distanceToPercent(weightedDistance(scan, averageFeatures(drySamples), defaultScales()))
    }

    /**
     * Leave-one-out balanced accuracy (0-100) of [score] on the place's own taught examples (near-copies
     * removed), or null with fewer than [MIN_PER_CLASS_FOR_SELF_CHECK] of either. The feature choice is
     * redone without the held-out scan, so the number is not flattered by it. Low values mean dry and
     * damp overlap at this place, so its scores are close to guesses.
     */
    fun teachingSelfCheck(drySamples: List<AcousticFeatures>, moistSamples: List<AcousticFeatures>): Double? {
        val dry = dedupe(drySamples).map { it.toDoubleArray() }
        val moist = dedupe(moistSamples).map { it.toDoubleArray() }
        if (dry.size < MIN_PER_CLASS_FOR_SELF_CHECK || moist.size < MIN_PER_CLASS_FOR_SELF_CHECK) return null
        val key = CacheKey(dry, moist)
        synchronized(selfCheckCache) { selfCheckCache[key]?.let { return it } }
        fun heldOut(test: DoubleArray, trainDry: List<DoubleArray>, trainMoist: List<DoubleArray>): Double =
            HybridModel.fit(trainDry, trainMoist, selectFeatures(trainDry, trainMoist)).percent(test)
        val dryHits = dry.indices.count { i -> heldOut(dry[i], dry.without(i), moist) < 50.0 }
        val moistHits = moist.indices.count { i -> heldOut(moist[i], dry, moist.without(i)) >= 50.0 }
        val check = 50.0 * (dryHits.toDouble() / dry.size + moistHits.toDouble() / moist.size)
        synchronized(selfCheckCache) { selfCheckCache[key] = check }
        return check
    }

    const val MIN_PER_CLASS_FOR_SELF_CHECK = 3

    /** Every non-empty combination of the five features, smallest first; ties go to the smaller one. */
    private val FEATURE_SETS: List<IntArray> = (1..AcousticFeatures.NUM_FEATURES).flatMap { size ->
        combinations(AcousticFeatures.NUM_FEATURES, size)
    }
    private val ALL_FEATURES = IntArray(AcousticFeatures.NUM_FEATURES) { it }
    private const val SELECTION_FOLDS = 10
    private const val CACHE_SIZE = 64

    private fun combinations(n: Int, k: Int): List<IntArray> {
        val out = mutableListOf<IntArray>()
        fun walk(start: Int, picked: List<Int>) {
            if (picked.size == k) {
                out += picked.toIntArray()
                return
            }
            for (i in start until n) walk(i + 1, picked + i)
        }
        walk(0, emptyList())
        return out
    }

    private fun List<DoubleArray>.without(i: Int): List<DoubleArray> = filterIndexed { j, _ -> j != i }

    private class CacheKey(val dry: List<DoubleArray>, val moist: List<DoubleArray>) {
        private val hash = dry.sumOf { it.contentHashCode() } * 31 + moist.sumOf { it.contentHashCode() }
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is CacheKey && other.hash == hash &&
            same(dry, other.dry) && same(moist, other.moist)
        private fun same(a: List<DoubleArray>, b: List<DoubleArray>) =
            a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }
    }

    private fun <V> lruCache() = object : LinkedHashMap<CacheKey, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, V>?) = size > CACHE_SIZE
    }

    private val selectionCache = lruCache<IntArray>()
    private val selfCheckCache = lruCache<Double>()

    private fun cachedSelection(dry: List<DoubleArray>, moist: List<DoubleArray>): IntArray {
        val key = CacheKey(dry, moist)
        synchronized(selectionCache) { selectionCache[key]?.let { return it } }
        val features = selectFeatures(dry, moist)
        synchronized(selectionCache) { selectionCache[key] = features }
        return features
    }

    /**
     * The feature combination that best separates this place's dry and damp examples, by
     * [SELECTION_FOLDS]-fold cross-validated balanced accuracy. All five when either class is too small.
     */
    private fun selectFeatures(dry: List<DoubleArray>, moist: List<DoubleArray>): IntArray {
        if (dry.size < MIN_PER_CLASS_FOR_SELF_CHECK || moist.size < MIN_PER_CLASS_FOR_SELF_CHECK) return ALL_FEATURES
        var best = ALL_FEATURES
        var bestAcc = -1.0
        for (features in FEATURE_SETS) {
            val acc = crossValidated(dry, moist, features)
            if (acc > bestAcc) {
                best = features
                bestAcc = acc
            }
        }
        return best
    }

    private fun crossValidated(dry: List<DoubleArray>, moist: List<DoubleArray>, features: IntArray): Double {
        var dryHits = 0
        var moistHits = 0
        for (fold in 0 until SELECTION_FOLDS) {
            val testDry = dry.indices.filter { it % SELECTION_FOLDS == fold }
            val testMoist = moist.indices.filter { it % SELECTION_FOLDS == fold }
            if (testDry.isEmpty() && testMoist.isEmpty()) continue
            val model = HybridModel.fit(
                dry.filterIndexed { i, _ -> i % SELECTION_FOLDS != fold },
                moist.filterIndexed { i, _ -> i % SELECTION_FOLDS != fold },
                features
            )
            dryHits += testDry.count { model.percent(dry[it]) < 50.0 }
            moistHits += testMoist.count { model.percent(moist[it]) >= 50.0 }
        }
        return 50.0 * (dryHits.toDouble() / dry.size + moistHits.toDouble() / moist.size)
    }

    /**
     * Score on a subset of features: the mean of the calibrated LDA score (cleaned teaching, median
     * centres, percentile anchors) and 100 × the scan's relative distance to the dry and damp centres.
     * Mirrored by tools/hybrid_check.py.
     */
    private class HybridModel(
        private val features: IntArray,
        private val ldaDir: DoubleArray,
        private val ldaMid: DoubleArray,
        private val ldaHalf: Double,
        private val dryRef: Double,
        private val moistRef: Double,
        private val zMean: DoubleArray,
        private val zStd: DoubleArray,
        private val dryCentre: DoubleArray,
        private val moistCentre: DoubleArray
    ) {
        fun percent(sample: DoubleArray): Double {
            val x = DoubleArray(features.size) { sample[features[it]] }
            return 0.5 * calibratedPercent(moistness(x).coerceAtLeast(0.0), dryRef, moistRef) + 50.0 * centreRatio(x)
        }

        private fun moistness(x: DoubleArray): Double {
            if (abs(ldaHalf) < 1e-12) return 0.5
            var num = 0.0
            for (i in x.indices) num += ldaDir[i] * (x[i] - ldaMid[i])
            return 0.5 * (num / ldaHalf + 1.0)
        }

        private fun centreRatio(x: DoubleArray): Double {
            var dDry = 0.0
            var dMoist = 0.0
            for (i in x.indices) {
                val z = (x[i] - zMean[i]) / zStd[i]
                dDry += (z - dryCentre[i]).pow(2)
                dMoist += (z - moistCentre[i]).pow(2)
            }
            dDry = sqrt(dDry)
            dMoist = sqrt(dMoist)
            return dDry / (dDry + dMoist + 1e-12)
        }

        companion object {
            fun fit(dryAll: List<DoubleArray>, moistAll: List<DoubleArray>, features: IntArray): HybridModel {
                val k = features.size
                val w = featureWeights().let { all -> DoubleArray(k) { all[features[it]] } }
                val sc = defaultScales().let { all -> DoubleArray(k) { all[features[it]] } }
                val eps = DoubleArray(k) { DEDUPE_EPS[features[it]] }
                val dryRaw = dryAll.map { s -> DoubleArray(k) { s[features[it]] } }
                val moistRaw = moistAll.map { s -> DoubleArray(k) { s[features[it]] } }

                val (dry, moist) = clean(dedupeRows(dryRaw, eps), dedupeRows(moistRaw, eps), sc)
                val muD = medians(dry)
                val muM = medians(moist)
                val vd = vars(dry, muD, sc)
                val vm = vars(moist, muM, sc)
                val dir = DoubleArray(k)
                val mid = DoubleArray(k)
                var half = 0.0
                for (i in 0 until k) {
                    val pooled = maxOf(
                        ((dry.size - 1).coerceAtLeast(0) * vd[i] + (moist.size - 1).coerceAtLeast(0) * vm[i]) /
                            (dry.size + moist.size - 2).coerceAtLeast(1).toDouble(),
                        (sc[i] * 0.15).pow(2),
                        1e-9
                    )
                    val d = (muM[i] - muD[i]) / pooled
                    dir[i] = w[i] * d
                    mid[i] = 0.5 * (muD[i] + muM[i])
                    half += w[i] * d * (muM[i] - muD[i]) * 0.5
                }

                val both = dryRaw + moistRaw
                val zMean = DoubleArray(k) { i -> both.sumOf { it[i] } / both.size }
                val zStd = DoubleArray(k) { i ->
                    val s = if (both.size < 2) 0.0 else sqrt(both.sumOf { (it[i] - zMean[i]).pow(2) } / (both.size - 1))
                    if (s < 1e-9) 1.0 else s
                }
                val dryCentre = DoubleArray(k) { i -> (dryRaw.sumOf { it[i] } / dryRaw.size - zMean[i]) / zStd[i] }
                val moistCentre = DoubleArray(k) { i -> (moistRaw.sumOf { it[i] } / moistRaw.size - zMean[i]) / zStd[i] }

                val model = HybridModel(features, dir, mid, half, 0.0, 0.0, zMean, zStd, dryCentre, moistCentre)
                val dryRef = percentile(dry.map { model.moistness(it) }, UltrasonicModelWeights.dryPercentile.coerceIn(0.50, 0.90))
                val moistRef = percentile(moist.map { model.moistness(it) }, UltrasonicModelWeights.moistPercentile.coerceIn(0.10, 0.50))
                    .coerceAtLeast(dryRef + 0.08)
                return HybridModel(features, dir, mid, half, dryRef, moistRef, zMean, zStd, dryCentre, moistCentre)
            }

            private fun dedupeRows(rows: List<DoubleArray>, eps: DoubleArray): List<DoubleArray> {
                val kept = ArrayList<DoubleArray>(rows.size)
                for (r in rows) {
                    if (kept.none { k -> r.indices.all { abs(r[it] - k[it]) <= eps[it] } }) kept += r
                }
                return kept
            }

            /** Drops examples nearer the other class's centre; keeps everything if that would empty a class. */
            private fun clean(
                dry: List<DoubleArray>,
                moist: List<DoubleArray>,
                sc: DoubleArray
            ): Pair<List<DoubleArray>, List<DoubleArray>> {
                if (dry.isEmpty() || moist.isEmpty()) return dry to moist
                val muD = medians(dry)
                val muM = medians(moist)
                val both = dry + moist
                val mean = DoubleArray(sc.size) { i -> both.sumOf { it[i] } / both.size }
                val v = vars(both, mean, sc)
                val scale = DoubleArray(sc.size) { i -> maxOf(sqrt(v[i]), sc[i] * 0.25, 1e-6) }
                fun dist(a: DoubleArray, mu: DoubleArray) = sqrt(a.indices.sumOf { ((a[it] - mu[it]) / scale[it]).pow(2) })
                val dryClean = dry.filter { dist(it, muD) <= dist(it, muM) }
                val moistClean = moist.filter { dist(it, muM) <= dist(it, muD) }
                return if (dryClean.isNotEmpty() && moistClean.isNotEmpty()) dryClean to moistClean else dry to moist
            }

            private fun medians(rows: List<DoubleArray>): DoubleArray {
                val k = rows.first().size
                return DoubleArray(k) { i ->
                    val col = DoubleArray(rows.size) { rows[it][i] }.also { it.sort() }
                    val m = col.size / 2
                    if (col.size % 2 == 1) col[m] else 0.5 * (col[m - 1] + col[m])
                }
            }

            private fun vars(rows: List<DoubleArray>, mu: DoubleArray, sc: DoubleArray): DoubleArray =
                if (rows.size < 2) DoubleArray(sc.size) { (sc[it] * 0.5).pow(2) }
                else DoubleArray(sc.size) { i -> rows.sumOf { (it[i] - mu[i]).pow(2) } / (rows.size - 1) }
        }
    }

    /** Below this self-check accuracy the app tells the user it cannot separate dry from damp here. */
    const val MIN_RELIABLE_SELF_CHECK = 60.0

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
    /** Hybrid dry/damp score: below this is closer to the dry examples, at or above to damp. */
    const val DAMP_SPLIT = 50.0
}
