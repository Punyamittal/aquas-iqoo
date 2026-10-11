package com.aquascope.baseline

import com.aquascope.dsp.AcousticFeatures
import org.junit.Assert.assertEquals
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
    fun `a place told apart by one feature only passes the self-check`() {
        val rnd = Random(4)
        fun noisy(n: Int, flatness: Double) = List(n) {
            AcousticFeatures(
                resonanceFreqHz = 6200 + rnd.nextDouble(-3000.0, 3000.0),
                decayTimeMs = 2.0 + rnd.nextDouble(-15.0, 15.0),
                spectralCentroidHz = 4800 + rnd.nextDouble(-1800.0, 1800.0),
                spectralSpreadHz = 1850 + rnd.nextDouble(-600.0, 600.0),
                spectralFlatness = flatness + rnd.nextDouble(-0.02, 0.02)
            )
        }
        val acc = AnomalyScorer.teachingSelfCheck(noisy(10, 0.80), noisy(10, 0.60))!!
        assertTrue("got $acc", acc >= 90.0)
    }

    @Test
    fun `repeated self-check gives the same answer`() {
        val rnd = Random(5)
        val dry = cloud(rnd, 8, 4850.0, 0.81)
        val moist = cloud(rnd, 8, 5300.0, 0.73)
        assertEquals(AnomalyScorer.teachingSelfCheck(dry, moist), AnomalyScorer.teachingSelfCheck(dry.toList(), moist.toList()))
    }

    @Test
    fun `taught damp spot scores damp and taught dry spot scores dry`() {
        val rnd = Random(6)
        val dry = cloud(rnd, 8, 4850.0, 0.81)
        val moist = cloud(rnd, 8, 5300.0, 0.73)
        val wet = AcousticFeatures(6200.0, 1.5, 5320.0, 1850.0, 0.72)
        val dryScan = AcousticFeatures(6200.0, 1.5, 4840.0, 1850.0, 0.82)
        assertTrue(AnomalyScorer.score(wet, dry, moist) >= 50.0)
        assertTrue(AnomalyScorer.score(dryScan, dry, moist) < 50.0)
    }

    @Test
    fun `too few examples gives no verdict`() {
        val rnd = Random(3)
        assertNull(AnomalyScorer.teachingSelfCheck(cloud(rnd, 5, 4850.0, 0.81), cloud(rnd, 2, 5300.0, 0.73)))
    }
}
