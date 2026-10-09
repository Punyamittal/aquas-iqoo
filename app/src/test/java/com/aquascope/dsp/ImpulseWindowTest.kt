package com.aquascope.dsp

import com.aquascope.audio.ChirpGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class ImpulseWindowTest {

    private val sampleRate = 48_000

    /** Chirp delayed by [delay] samples through a 1.2 kHz resonator, plus hiss above the chirp band. */
    private fun recording(delay: Int, hiss: Double): Pair<DoubleArray, DoubleArray> {
        val chirp = ChirpGenerator.generate(
            startFreq = 80.0, endFreq = 16_000.0, sampleRate = sampleRate, durationSec = 0.5
        )
        val ir = DoubleArray(1024) { i ->
            val t = i.toDouble() / sampleRate
            exp(-300.0 * t) * sin(2 * PI * 1200.0 * t)
        }
        val out = DoubleArray(chirp.size + 9600)
        for (i in chirp.indices) {
            for (j in ir.indices) {
                val k = i + j + delay
                if (k < out.size) out[k] += chirp[i] * ir[j]
            }
        }
        val rnd = Random(7)
        for (i in out.indices) {
            out[i] += hiss * sin(2 * PI * 20_000.0 * i / sampleRate) + hiss * 0.1 * (rnd.nextDouble() - 0.5)
        }
        return out to chirp
    }

    @Test
    fun `band-limited deconvolution finds the delayed echo despite hiss above the chirp`() {
        val delay = 4500
        val (rec, chirp) = recording(delay, hiss = 0.05)
        val ir = Deconvolution.deconvolveBandLimited(rec, chirp, sampleRate)
        val echo = ImpulseWindow.aroundDirectPath(ir, sampleRate)
        assertTrue("peak ${echo.peakIndex} should be near $delay", abs(echo.peakIndex - delay) < 96)
        assertTrue("PNR ${echo.peakToNoiseDb} dB should pass", echo.isUsable(sampleRate))
        val expectedLen = ((ImpulseWindow.PRE_MS + ImpulseWindow.POST_MS) * sampleRate / 1000).toInt()
        assertTrue(abs(echo.windowed.size - expectedLen) <= 1)
    }

    @Test
    fun `pure noise is rejected`() {
        val rnd = Random(3)
        val noise = DoubleArray(48_000) { rnd.nextDouble() - 0.5 }
        val echo = ImpulseWindow.aroundDirectPath(noise, sampleRate)
        assertFalse(echo.isUsable(sampleRate))
    }
}
