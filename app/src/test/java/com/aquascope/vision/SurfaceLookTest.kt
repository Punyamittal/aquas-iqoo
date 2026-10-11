package com.aquascope.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceLookTest {

    private fun grey(level: Int, w: Int = 20, h: Int = 20) =
        IntArray(w * h) { (0xFF shl 24) or (level shl 16) or (level shl 8) or level }

    @Test
    fun `flat grey has no texture, glare or colour`() {
        val look = SurfaceLookAnalyzer.analyze(grey(128), 20, 20, 10_000_000L, 100)
        assertEquals(0.0, look.texture, 1e-9)
        assertEquals(0.0, look.glarePercent, 1e-9)
        assertEquals(0.0, look.saturation, 1e-9)
        assertNotNull(look.brightness)
    }

    @Test
    fun `brightness is corrected for exposure`() {
        val short = SurfaceLookAnalyzer.analyze(grey(128), 20, 20, 10_000_000L, 100)
        val long = SurfaceLookAnalyzer.analyze(grey(128), 20, 20, 20_000_000L, 100)
        assertEquals(short.brightness!! / 2, long.brightness!!, 1e-9)
    }

    @Test
    fun `no exposure report means no brightness`() {
        assertNull(SurfaceLookAnalyzer.analyze(grey(128), 20, 20, null, null).brightness)
    }

    @Test
    fun `glare counts torch highlights in the centre`() {
        val px = grey(100)
        px[10 * 20 + 10] = 0xFFFFFFFF.toInt()
        assertTrue(SurfaceLookAnalyzer.analyze(px, 20, 20, null, null).glarePercent > 0)
    }

    private fun look(level: Int, exposureNs: Long = 10_000_000L) =
        SurfaceLookAnalyzer.analyze(grey(level), 20, 20, exposureNs, 100)

    @Test
    fun `darker photo near the damp ones reads as damp`() {
        val c = SurfaceLookAnalyzer.compare(look(95), listOf(look(160), look(165)), listOf(look(100), look(90)))!!
        assertTrue(c.darkerPercent > 15)
        assertEquals(SurfaceLookAnalyzer.Verdict.LIKE_DAMP, c.verdict)
        val text = SurfaceLookAnalyzer.describe(c)
        assertTrue(text, text.contains("darker") && text.contains("closer to the damp"))
    }

    @Test
    fun `photo like the dry ones reads as dry`() {
        val c = SurfaceLookAnalyzer.compare(look(158), listOf(look(160)), listOf(look(90)))!!
        assertEquals(SurfaceLookAnalyzer.Verdict.LIKE_DRY, c.verdict)
    }

    @Test
    fun `photo taken a different way is not compared`() {
        // Same surface, but exposure 1000x shorter: like a photo from a distance in room light.
        val c = SurfaceLookAnalyzer.compare(look(160, 10_000L), listOf(look(160)), listOf(look(90)))!!
        assertEquals(SurfaceLookAnalyzer.Verdict.NOT_COMPARABLE, c.verdict)
        assertTrue(SurfaceLookAnalyzer.describe(c).contains("can't be compared"))
    }

    @Test
    fun `black frame is too dark`() {
        assertEquals(SurfaceLookAnalyzer.Quality.TOO_DARK, SurfaceLookAnalyzer.quality(look(2)))
    }

    @Test
    fun `light from one side only is uneven`() {
        val px = grey(10).also { p -> for (y in 0 until 10) for (x in 0 until 10) p[y * 20 + x] = grey(200)[0] }
        val look = SurfaceLookAnalyzer.analyze(px, 20, 20, 10_000_000L, 100)
        assertEquals(SurfaceLookAnalyzer.Quality.UNEVEN, SurfaceLookAnalyzer.quality(look))
    }

    @Test
    fun `evenly lit frame is ok`() {
        assertEquals(SurfaceLookAnalyzer.Quality.OK, SurfaceLookAnalyzer.quality(look(120)))
    }

    @Test
    fun `no dry photos gives no comparison`() {
        assertNull(SurfaceLookAnalyzer.compare(look(128), emptyList(), listOf(look(90))))
    }
}
