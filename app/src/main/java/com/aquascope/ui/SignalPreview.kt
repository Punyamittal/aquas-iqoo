package com.aquascope.ui

import com.aquascope.dsp.FFT
import com.aquascope.dsp.FeatureExtractor
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Display-only downsampling of real AquaScope captures.
 * Never used for anomaly scoring.
 */
object SignalPreview {

    /** Per bin, the sample with the largest magnitude, sign kept, scaled by the capture's peak. */
    fun waveform(samples: DoubleArray, bins: Int = 96): FloatArray {
        if (samples.isEmpty() || bins <= 0) return FloatArray(0)
        val count = min(bins, samples.size)
        val out = FloatArray(count)
        var peak = 0.0
        for (i in 0 until count) {
            val start = (i.toLong() * samples.size / count).toInt()
            val end = max(start + 1, ((i + 1).toLong() * samples.size / count).toInt())
            var v = 0.0
            for (j in start until end) {
                if (abs(samples[j]) > abs(v)) v = samples[j]
            }
            out[i] = v.toFloat()
            if (abs(v) > peak) peak = abs(v)
        }
        if (peak <= 0.0) return out
        val inv = (1.0 / peak).toFloat()
        for (i in out.indices) out[i] *= inv
        return out
    }

    /**
     * Log-magnitude spectrum of [samples] over the same band the features are computed on,
     * split into [bands] equal-width slices in Hz.
     */
    fun spectrum(samples: DoubleArray, sampleRate: Int, bands: Int = 40): FloatArray {
        if (samples.isEmpty() || bands <= 0 || sampleRate <= 0) return FloatArray(0)
        val n = FFT.nextPowerOf2(samples.size)
        val re = FFT.zeroPad(samples, n)
        val im = DoubleArray(n)
        FFT.fft(re, im)
        val mag = FFT.magnitude(re, im)
        val half = n / 2
        val hzPerBin = sampleRate.toDouble() / n
        val (lowHz, highHz) = FeatureExtractor.analysisBandHz(sampleRate)
        val loBin = (lowHz / hzPerBin).toInt().coerceIn(1, half - 1)
        val hiBin = (highHz / hzPerBin).toInt().coerceIn(loBin + 1, half)
        val out = FloatArray(bands)
        for (b in 0 until bands) {
            val i0 = (loBin + b.toLong() * (hiBin - loBin) / bands).toInt()
            val i1 = max(i0 + 1, (loBin + (b + 1).toLong() * (hiBin - loBin) / bands).toInt())
            var sum = 0.0
            for (i in i0 until i1) sum += mag[i]
            out[b] = ln(1.0 + sum / (i1 - i0)).toFloat()
        }
        val maxV = out.maxOrNull() ?: 0f
        if (maxV <= 0f) return out
        for (i in out.indices) out[i] /= maxV
        return out
    }

    fun rms(samples: ShortArray, offset: Int, length: Int): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        val end = min(samples.size, offset + length)
        for (i in offset until end) {
            val s = samples[i] / 32768.0
            sum += s * s
        }
        return sqrt(sum / (end - offset).coerceAtLeast(1)).toFloat()
    }
}
