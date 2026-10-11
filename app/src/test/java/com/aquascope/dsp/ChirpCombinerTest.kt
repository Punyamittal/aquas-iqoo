package com.aquascope.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class ChirpCombinerTest {

    private fun f(res: Double, decay: Double) = AcousticFeatures(res, decay, 3000.0, 1500.0, 0.2)

    @Test
    fun oneSpoiledChirpDoesNotMoveTheMedian() {
        val chirps = listOf(f(1000.0, 5.0), f(1010.0, 5.2), f(4000.0, 30.0))
        val m = ChirpCombiner.median(chirps)
        assertEquals(1010.0, m.resonanceFreqHz, 1e-9)
        assertEquals(5.2, m.decayTimeMs, 1e-9)
    }

    @Test
    fun twoChirpsAverage() {
        val m = ChirpCombiner.median(listOf(f(1000.0, 4.0), f(1100.0, 6.0)))
        assertEquals(1050.0, m.resonanceFreqHz, 1e-9)
        assertEquals(5.0, m.decayTimeMs, 1e-9)
    }

    @Test
    fun closestChirpIsTheOneNearTheMedian() {
        val chirps = listOf(f(4000.0, 30.0), f(1005.0, 5.1), f(1000.0, 5.0))
        assertEquals(1, ChirpCombiner.closestIndex(chirps, f(1005.0, 5.1)))
    }
}
