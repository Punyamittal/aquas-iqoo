package com.aquascope.dsp

import kotlin.math.*

/**
 * [ROLE] FFT deconvolution → surface impulse response H(f).
 * [WHY] Isolates material dynamics from the known chirp so moisture-sensitive features are measurable.
 * [IQOO] Pure Kotlin / native-friendly sizes; Wiener regularization avoids divide-by-near-zero.
 */
object Deconvolution {

    // TODO: Tune regularization epsilon against real recordings — too low = noise blowup, too high = loss of detail
    const val DEFAULT_REGULARIZATION_EPSILON = 1e-3

    /**
     * Recover the impulse response via spectral division.
     * @param recorded the microphone recording
     * @param reference the known chirp signal
     * @param epsilon regularization parameter for Wiener-style deconvolution
     * @return estimated impulse response (time-domain)
     */
    fun deconvolve(
        recorded: DoubleArray,
        reference: DoubleArray,
        epsilon: Double = DEFAULT_REGULARIZATION_EPSILON
    ): DoubleArray {
        val n = FFT.nextPowerOf2(maxOf(recorded.size, reference.size) * 2)

        val recRe = FFT.zeroPad(recorded, n)
        val recIm = DoubleArray(n)
        val refRe = FFT.zeroPad(reference, n)
        val refIm = DoubleArray(n)

        FFT.fft(recRe, recIm)
        FFT.fft(refRe, refIm)

        // H = Rec * conj(Ref) / (|Ref|^2 + epsilon^2)
        val hRe = DoubleArray(n)
        val hIm = DoubleArray(n)
        for (i in 0 until n) {
            val refMagSq = refRe[i] * refRe[i] + refIm[i] * refIm[i]
            val denom = refMagSq + epsilon * epsilon
            // Rec * conj(Ref)
            hRe[i] = (recRe[i] * refRe[i] + recIm[i] * refIm[i]) / denom
            hIm[i] = (recIm[i] * refRe[i] - recRe[i] * refIm[i]) / denom
        }

        FFT.fft(hRe, hIm, inverse = true)
        return hRe.copyOf(maxOf(recorded.size, reference.size))
    }

    /**
     * Simple matched filter: cross-correlation via FFT.
     * Returns the cross-correlation of recorded with reference.
     */
    fun matchedFilter(recorded: DoubleArray, reference: DoubleArray): DoubleArray {
        val n = FFT.nextPowerOf2(recorded.size + reference.size - 1)

        val recRe = FFT.zeroPad(recorded, n)
        val recIm = DoubleArray(n)
        val refRe = FFT.zeroPad(reference, n)
        val refIm = DoubleArray(n)

        FFT.fft(recRe, recIm)
        FFT.fft(refRe, refIm)

        // Cross-correlation = IFFT(Rec * conj(Ref))
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        for (i in 0 until n) {
            outRe[i] = recRe[i] * refRe[i] + recIm[i] * refIm[i]
            outIm[i] = recIm[i] * refRe[i] - recRe[i] * refIm[i]
        }

        FFT.fft(outRe, outIm, inverse = true)
        return outRe
    }
}
