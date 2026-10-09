package com.aquascope.smriti.engine

import com.aquascope.data.ScanLocation
import com.aquascope.data.ScanRecord
import com.aquascope.data.SerializableFeatures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScanAnswererTest {

    private fun features(rnd: Random, damp: Boolean) = SerializableFeatures(
        6200 + rnd.nextDouble(-300.0, 300.0),
        1.5 + rnd.nextDouble(-0.1, 0.1),
        (if (damp) 5400.0 else 4800.0) + rnd.nextDouble(-100.0, 100.0),
        1850.0 + rnd.nextDouble(-50.0, 50.0),
        (if (damp) 0.73 else 0.81) + rnd.nextDouble(-0.02, 0.02)
    )

    private fun place(label: String, separable: Boolean, lastScore: Double, at: Long, seed: Int): ScanLocation {
        val rnd = Random(seed)
        return ScanLocation(
            label = label,
            baselineFeatures = MutableList(8) { features(rnd, false) },
            moistFeatures = MutableList(8) { features(rnd, separable) },
            scanHistory = mutableListOf(ScanRecord(at, features(rnd, true), lastScore))
        )
    }

    private val places = listOf(
        place("weworks6", separable = true, lastScore = 85.0, at = 2_000, seed = 1),
        place("tabl3", separable = false, lastScore = 82.0, at = 3_000, seed = 2)
    )

    private fun ask(q: String) = ScanAnswerer.answer(q, places) { "t$it" }

    @Test
    fun `overview trusts only reliable places`() {
        val a = ask("Which places are damp?")!!
        assertTrue(a, a.contains("Sounds damp: weworks6 (85%)"))
        assertTrue(a, a.contains("Can't tell dry from damp yet: tabl3"))
        assertFalse(a, a.contains("tabl3 (82%)"))
    }

    @Test
    fun `named unreliable place is not given a verdict`() {
        val a = ask("is tabl3 wet?")!!
        assertTrue(a, a.contains("can't be trusted"))
        assertFalse(a, a.contains("sounds damp"))
    }

    @Test
    fun `leak question never confirms a leak`() {
        val a = ask("Is there a leak?")!!
        assertTrue(a, a.contains("can't confirm a leak"))
    }

    @Test
    fun `latest scan names the place and time`() {
        val a = ask("What was my last scan?")!!
        assertTrue(a, a.startsWith("Your last scan was at tabl3 on t3000."))
    }

    @Test
    fun `unrelated and screen questions are left to memory`() {
        assertNull(ask("What changed today?"))
        assertNull(ask("what game did I open on screen?"))
    }
}
