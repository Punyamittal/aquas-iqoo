package com.aquascope.baseline

import com.aquascope.dsp.AcousticFeatures
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TeachingSelfCheckTest {

    private fun cloud(rnd: Random, n: Int, centroid: Double, flatness: Double) = List(n) {
        AcousticFeatures(
            resonanceFreqHz = 6200 + rnd.nextDouble(-400.0, 400.0),
            decayTimeMs = 1.5 + rnd.nextDouble(-0.5, 0.5),
            spectralCentroidHz = centroid + rnd.nextDouble(-120.0, 120.0),
            spectralSpreadHz = 1850 + rnd.nextDouble(-60.0, 60.0),
            spectralFlatness = flatness + rnd.nextDouble(-0.02, 0.02)
        )
    }

    @Test
    fun `separable place passes the self-check`() {
        val rnd = Random(1)
        val acc = AnomalyScorer.teachingSelfCheck(cloud(rnd, 8, 4850.0, 0.81), cloud(rnd, 8, 5300.0, 0.73))!!
        assertTrue("got $acc", acc >= AnomalyScorer.MIN_RELIABLE_SELF_CHECK)
    }

    @Test
    fun `overlapping place fails the self-check`() {
        val rnd = Random(2)
        val acc = AnomalyScorer.teachingSelfCheck(cloud(rnd, 12, 4800.0, 0.81), cloud(rnd, 12, 4800.0, 0.81))!!
        assertTrue("got $acc", acc < AnomalyScorer.MIN_RELIABLE_SELF_CHECK)
    }

    @Test
    fun `too few examples gives no verdict`() {
        val rnd = Random(3)
        assertNull(AnomalyScorer.teachingSelfCheck(cloud(rnd, 5, 4850.0, 0.81), cloud(rnd, 2, 5300.0, 0.73)))
    }
}
