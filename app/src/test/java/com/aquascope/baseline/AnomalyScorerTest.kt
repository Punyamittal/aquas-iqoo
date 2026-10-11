package com.aquascope.baseline

import com.aquascope.dsp.AcousticFeatures
import org.junit.Assert.*
import org.junit.Test

class AnomalyScorerTest {

    @Test
    fun `identical features yield near zero anomaly score`() {
        val features = AcousticFeatures(1000.0, 20.0, 3000.0, 1500.0, 0.5)
        val score = AnomalyScorer.score(features, features)
        assertEquals("Identical features should score ~0, got $score", 0.0, score, 0.01)
    }

    @Test
    fun `mild phone contact variance stays in green band`() {
        val baseline = AcousticFeatures(2500.0, 30.0, 3500.0, 1800.0, 0.45)
        // Typical re-seat / contact noise — must NOT look like a 99% leak
        val reseat = AcousticFeatures(2700.0, 34.0, 4100.0, 2100.0, 0.50)
        val score = AnomalyScorer.score(reseat, baseline)
        assertTrue("Mild contact variance should stay under 30%, got $score", score < 30.0)
    }

    @Test
    fun `second dry example does not make a normal dry scan look damp`() {
        val first = AcousticFeatures(6200.0, 1.1, 5400.0, 1900.0, 0.70)
        val second = AcousticFeatures(6210.0, 1.2, 5420.0, 1910.0, 0.71)
        // Chirp-to-chirp noise measured on a still phone: about ±500 Hz, ±0.6 ms, ±300 Hz, ±130 Hz, ±0.08
        val nextDry = AcousticFeatures(6700.0, 1.7, 5700.0, 2030.0, 0.78)
        val withOne = AnomalyScorer.score(nextDry, listOf(first), emptyList())
        val withTwo = AnomalyScorer.score(nextDry, listOf(first, second), emptyList())
        assertTrue("Two dry examples: $withTwo", withTwo < 50.0)
        assertEquals(withOne, withTwo, 5.0)
    }

    @Test
    fun `very different features yield high anomaly score`() {
        val baseline = AcousticFeatures(2500.0, 30.0, 4000.0, 2000.0, 0.6)
        val anomalous = AcousticFeatures(1500.0, 10.0, 2000.0, 800.0, 0.2)
        val score = AnomalyScorer.score(anomalous, baseline)
        assertTrue("Very different features should score high, got $score", score > 50.0)
    }

    @Test
    fun `extreme feature jumps do not instantly saturate at 99`() {
        val baseline = AcousticFeatures(2500.0, 30.0, 4000.0, 2000.0, 0.5)
        // Large but still finite shift (old curve would hit ~99%)
        val noisy = AcousticFeatures(4500.0, 55.0, 7000.0, 3500.0, 0.75)
        val score = AnomalyScorer.score(noisy, baseline)
        assertTrue("Score should stay below 95 for large-but-finite noise, got $score", score < 95.0)
        assertTrue("Score should still be elevated, got $score", score > 40.0)
    }

    @Test
    fun `score is between 0 and 100`() {
        val a = AcousticFeatures(500.0, 5.0, 1000.0, 500.0, 0.1)
        val b = AcousticFeatures(5000.0, 100.0, 10000.0, 5000.0, 0.9)
        val score = AnomalyScorer.score(a, b)
        assertTrue("Score should be in [0,100], got $score", score in 0.0..100.0)
    }

    @Test
    fun `averaging features works correctly`() {
        val samples = listOf(
            AcousticFeatures(1000.0, 20.0, 3000.0, 1500.0, 0.5),
            AcousticFeatures(1100.0, 22.0, 3100.0, 1600.0, 0.6)
        )
        val avg = AnomalyScorer.averageFeatures(samples)
        assertEquals(1050.0, avg.resonanceFreqHz, 0.01)
        assertEquals(21.0, avg.decayTimeMs, 0.01)
    }

    @Test
    fun `dry and moist teaching calibrates the score scale`() {
        val dry = listOf(
            AcousticFeatures(2500.0, 30.0, 3500.0, 1800.0, 0.45),
            AcousticFeatures(2550.0, 31.0, 3600.0, 1850.0, 0.46),
            AcousticFeatures(2480.0, 29.0, 3400.0, 1750.0, 0.44)
        )
        val moist = listOf(
            AcousticFeatures(1800.0, 12.0, 2800.0, 1400.0, 0.25),
            AcousticFeatures(1750.0, 11.0, 2700.0, 1350.0, 0.22)
        )
        val dryProbe = AcousticFeatures(2520.0, 30.5, 3520.0, 1820.0, 0.45)
        val moistProbe = AcousticFeatures(1780.0, 11.5, 2750.0, 1380.0, 0.24)

        val dryScore = AnomalyScorer.score(dryProbe, dry, moist)
        val moistScore = AnomalyScorer.score(moistProbe, dry, moist)

        assertTrue("Dry probe should stay green after teaching, got $dryScore", dryScore < 30.0)
        assertTrue("Moist probe should score high after teaching, got $moistScore", moistScore > 60.0)
        assertTrue("Moist should outrank dry, $moistScore vs $dryScore", moistScore > dryScore + 25.0)
    }

    @Test
    fun `relative moistness finds moist even when absolute distance overlaps dry`() {
        // Wide dry scatter; moist cluster sits near dry mean in absolute distance
        // but systematically lower resonance / higher flatness.
        val dry = listOf(
            AcousticFeatures(9000.0, 12.0, 9000.0, 3000.0, 0.40),
            AcousticFeatures(2000.0, 12.0, 4000.0, 2200.0, 0.55),
            AcousticFeatures(11000.0, 10.0, 10000.0, 3200.0, 0.35),
            AcousticFeatures(4000.0, 14.0, 5000.0, 2500.0, 0.50)
        )
        val moist = listOf(
            AcousticFeatures(2800.0, 11.0, 4200.0, 2100.0, 0.82),
            AcousticFeatures(3000.0, 12.0, 4300.0, 2150.0, 0.80),
            AcousticFeatures(2600.0, 10.0, 4100.0, 2050.0, 0.85)
        )
        val moistProbe = AcousticFeatures(2900.0, 11.5, 4250.0, 2120.0, 0.83)
        val dryProbe = AcousticFeatures(9500.0, 12.0, 9200.0, 3050.0, 0.38)

        val moistScore = AnomalyScorer.score(moistProbe, dry, moist)
        val dryScore = AnomalyScorer.score(dryProbe, dry, moist)
        assertTrue("Moist probe must not look dry, got $moistScore", moistScore > 50.0)
        assertTrue("Dry probe should stay low, got $dryScore", dryScore < 40.0)
        assertTrue("Moist > dry, $moistScore vs $dryScore", moistScore > dryScore + 20.0)
    }

    @Test
    fun `calibrated percent anchors dry and moist references`() {
        assertEquals(0.0, AnomalyScorer.calibratedPercent(0.0, 1.0, 3.0), 0.01)
        assertTrue(AnomalyScorer.calibratedPercent(1.0, 1.0, 3.0) in 20.0..25.0)
        assertTrue(AnomalyScorer.calibratedPercent(3.0, 1.0, 3.0) in 75.0..80.0)
        assertTrue(AnomalyScorer.calibratedPercent(6.0, 1.0, 3.0) > 85.0)
    }

    @Test
    fun `same inputs yield stable scores without staged demo bands`() {
        val dry = AcousticFeatures(2500.0, 30.0, 3500.0, 1800.0, 0.45)
        val similar = AcousticFeatures(2550.0, 29.0, 3600.0, 1850.0, 0.44)

        val a = AnomalyScorer.score(similar, listOf(dry), emptyList(), priorCompareCount = 0)
        val b = AnomalyScorer.score(similar, listOf(dry), emptyList(), priorCompareCount = 1)
        val c = AnomalyScorer.score(similar, listOf(dry), emptyList(), priorCompareCount = 2)

        assertEquals(a, b, 0.01)
        assertEquals(b, c, 0.01)
        assertTrue("Score should be a normal 0–100 value, got $a", a in 0.0..100.0)
    }
}
