package com.aquascope.dsp

import kotlin.math.*

/**
 * [ROLE] Moisture-sensitive acoustic feature vector from impulse / frequency response.
 * [WHY] Resonance, decay, centroid, spread, flatness shift when hidden water adds mass and damping.
 * [IQOO] Fed into AnomalyScorer after each contact chirp on iQOO 15.
 */
data class AcousticFeatures(
    val resonanceFreqHz: Double,
    val decayTimeMs: Double,
    val spectralCentroidHz: Double,
    val spectralSpreadHz: Double,
    val spectralFlatness: Double
) {
    fun toDoubleArray() = doubleArrayOf(
        resonanceFreqHz, decayTimeMs, spectralCentroidHz, spectralSpreadHz, spectralFlatness
    )

    companion object {
        fun fromDoubleArray(arr: DoubleArray) = AcousticFeatures(
            resonanceFreqHz = arr[0],
            decayTimeMs = arr[1],
            spectralCentroidHz = arr[2],
            spectralSpreadHz = arr[3],
            spectralFlatness = arr[4]
        )

        const val NUM_FEATURES = 5
    }
}

object FeatureExtractor {

    /** Contact / wall sensing is unreliable outside this analysis band. */
    private const val BAND_MIN_HZ = 200.0
    private const val BAND_MAX_HZ = 8000.0

    /**
     * Full feature extraction pipeline.
     * @param impulseResponse time-domain impulse response from deconvolution
     * @param sampleRate sample rate in Hz
     */
    fun extract(impulseResponse: DoubleArray, sampleRate: Int): AcousticFeatures {
        val n = FFT.nextPowerOf2(impulseResponse.size)
        val re = FFT.zeroPad(impulseResponse, n)
        val im = DoubleArray(n)
        FFT.fft(re, im)

        val mag = FFT.magnitude(re, im)
        val halfN = n / 2
        val (lo, hi) = analysisBins(halfN, sampleRate)

        val resonance = findResonancePeak(mag, halfN, sampleRate)
        val decay = estimateDecayTime(impulseResponse, sampleRate)
        val centroid = spectralCentroidBand(mag, lo, hi, halfN, sampleRate)
        val spread = spectralSpreadBand(mag, lo, hi, halfN, sampleRate, centroid)
        val flatness = spectralFlatnessBand(mag, lo, hi)

        return AcousticFeatures(resonance, decay, centroid, spread, flatness)
    }

    private fun analysisBins(halfN: Int, sampleRate: Int): Pair<Int, Int> {
        val maxHz = min(BAND_MAX_HZ, sampleRate / 2.0 * 0.9)
        val minBin = (BAND_MIN_HZ * halfN * 2 / sampleRate).toInt().coerceAtLeast(1)
        val maxBin = (maxHz * halfN * 2 / sampleRate).toInt().coerceIn(minBin + 1, halfN - 1)
        return minBin to maxBin
    }

    /**
     * Dominant peak inside the contact analysis band (not full Nyquist).
     * Full-band max often jumps to HF mic noise and forces ~99% anomaly scores.
     */
    fun findResonancePeak(mag: DoubleArray, halfN: Int, sampleRate: Int): Double {
        val (minBin, maxBin) = analysisBins(halfN, sampleRate)
        var peakBin = minBin
        var peakVal = mag[minBin]
        for (i in minBin + 1..maxBin) {
            if (mag[i] > peakVal) {
                peakVal = mag[i]
                peakBin = i
            }
        }
        // Parabolic interpolation for sub-bin stability between scans
        if (peakBin in (minBin + 1) until maxBin) {
            val y0 = mag[peakBin - 1]
            val y1 = mag[peakBin]
            val y2 = mag[peakBin + 1]
            val denom = (y0 - 2 * y1 + y2)
            if (abs(denom) > 1e-18) {
                val delta = 0.5 * (y0 - y2) / denom
                if (delta in -0.5..0.5) {
                    return (peakBin + delta) * sampleRate / (halfN * 2.0)
                }
            }
        }
        return peakBin.toDouble() * sampleRate / (halfN * 2)
    }

    /**
     * Estimate decay time using the envelope of the impulse response.
     * Uses a simple RMS-windowed approach: find when the envelope drops to -60dB (T60-style)
     * or -20dB and extrapolate.
     */
    fun estimateDecayTime(impulseResponse: DoubleArray, sampleRate: Int): Double {
        val envelope = hilbertEnvelope(impulseResponse)
        if (envelope.isEmpty()) return 0.0

        val peakIdx = envelope.indices.maxByOrNull { envelope[it] } ?: 0
        val peakVal = envelope[peakIdx]
        if (peakVal <= 0.0) return 0.0

        // Find -20dB point and extrapolate to -60dB (T60 = 3 * T20).
        // If the envelope never crosses -20dB, use the last sample as T20 lower bound
        // so decay does not collapse to 0 ms on long / truncated IRs.
        val threshold20dB = peakVal * 0.1 // -20dB
        var t20Idx = -1
        for (i in peakIdx until envelope.size) {
            if (envelope[i] < threshold20dB) {
                t20Idx = i
                break
            }
        }
        if (t20Idx < 0) {
            t20Idx = envelope.lastIndex.coerceAtLeast(peakIdx)
        }

        val t20Samples = (t20Idx - peakIdx).toDouble().coerceAtLeast(1.0)
        val t60Samples = t20Samples * 3.0
        return t60Samples / sampleRate * 1000.0 // ms
    }

    /** Spectral centroid over the contact analysis band (public API keeps halfN signature). */
    fun spectralCentroid(mag: DoubleArray, halfN: Int, sampleRate: Int): Double {
        val (lo, hi) = analysisBins(halfN, sampleRate)
        return spectralCentroidBand(mag, lo, hi, halfN, sampleRate)
    }

    private fun spectralCentroidBand(
        mag: DoubleArray,
        lo: Int,
        hi: Int,
        halfN: Int,
        sampleRate: Int
    ): Double {
        var weightedSum = 0.0
        var totalWeight = 0.0
        val n = halfN * 2
        for (i in lo..hi) {
            val freq = i.toDouble() * sampleRate / n
            weightedSum += freq * mag[i]
            totalWeight += mag[i]
        }
        return if (totalWeight > 0) weightedSum / totalWeight else 0.0
    }

    /** Spectral spread around centroid over the analysis band. */
    fun spectralSpread(mag: DoubleArray, halfN: Int, sampleRate: Int, centroid: Double): Double {
        val (lo, hi) = analysisBins(halfN, sampleRate)
        return spectralSpreadBand(mag, lo, hi, halfN, sampleRate, centroid)
    }

    private fun spectralSpreadBand(
        mag: DoubleArray,
        lo: Int,
        hi: Int,
        halfN: Int,
        sampleRate: Int,
        centroid: Double
    ): Double {
        var weightedSqSum = 0.0
        var totalWeight = 0.0
        val n = halfN * 2
        for (i in lo..hi) {
            val freq = i.toDouble() * sampleRate / n
            val diff = freq - centroid
            weightedSqSum += diff * diff * mag[i]
            totalWeight += mag[i]
        }
        return if (totalWeight > 0) sqrt(weightedSqSum / totalWeight) else 0.0
    }

    /** Spectral flatness over the analysis band. */
    fun spectralFlatness(mag: DoubleArray, halfN: Int): Double {
        // halfN alone does not encode sampleRate; use default phone rate for bin limits.
        val (lo, hi) = analysisBins(halfN, 44_100)
        return spectralFlatnessBand(mag, lo, hi)
    }

    private fun spectralFlatnessBand(mag: DoubleArray, lo: Int, hi: Int): Double {
        var logSum = 0.0
        var linSum = 0.0
        var count = 0
        for (i in lo..hi) {
            if (mag[i] > 1e-12) {
                logSum += ln(mag[i])
                linSum += mag[i]
                count++
            }
        }
        if (count == 0) return 0.0
        val geometricMean = exp(logSum / count)
        val arithmeticMean = linSum / count
        return if (arithmeticMean > 0) geometricMean / arithmeticMean else 0.0
    }

    /**
     * Approximate Hilbert envelope using the analytic signal approach:
     * envelope = |signal + j * hilbert(signal)|
     * Implemented via FFT: zero out negative frequencies, IFFT, take magnitude.
     */
    fun hilbertEnvelope(signal: DoubleArray): DoubleArray {
        if (signal.isEmpty()) return doubleArrayOf()
        val n = FFT.nextPowerOf2(signal.size)
        val re = FFT.zeroPad(signal, n)
        val im = DoubleArray(n)
        FFT.fft(re, im)

        // Zero negative frequencies, double positive (analytic signal construction)
        // Bin 0 and N/2 stay unchanged
        for (i in 1 until n / 2) {
            re[i] *= 2.0
            im[i] *= 2.0
        }
        for (i in n / 2 + 1 until n) {
            re[i] = 0.0
            im[i] = 0.0
        }

        FFT.fft(re, im, inverse = true)
        return DoubleArray(signal.size) { sqrt(re[it] * re[it] + im[it] * im[it]) }
    }
}
