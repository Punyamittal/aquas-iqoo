package com.aquascope.report

import com.aquascope.dsp.AcousticFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportChartRendererTest {

    private fun features(res: Double = 2000.0) = AcousticFeatures(
        resonanceFreqHz = res,
        decayTimeMs = 25.0,
        spectralCentroidHz = 3500.0,
        spectralSpreadHz = 1200.0,
        spectralFlatness = 0.4
    )

    @Test
    fun `thermal field peaks near high-score points`() {
        val points = listOf(
            ReportPoint("A", 10.0, features()),
            ReportPoint("B", 90.0, features(1500.0)),
            ReportPoint("C", 20.0, features())
        )
        val field = ReportChartRenderer.thermalField(points, 32, 20)
        val flat = field.flatMap { it.toList() }
        assertTrue(flat.maxOrNull()!! > 50f)
        assertTrue(flat.minOrNull()!! < 40f)
    }

    @Test
    fun `density bins sum to point count`() {
        val points = listOf(
            ReportPoint("A", 5.0, features()),
            ReportPoint("B", 35.0, features()),
            ReportPoint("C", 75.0, features()),
            ReportPoint("D", 95.0, features())
        )
        val bins = ReportChartRenderer.densityBins(points, 10)
        assertEquals(4, bins.sum())
    }

    @Test
    fun `thermal color encodes full alpha`() {
        val c = ReportChartRenderer.thermalColor(80f)
        assertEquals(255, (c ushr 24) and 0xFF)
    }
}
