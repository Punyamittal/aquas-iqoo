package com.aquascope.smriti.llm

import com.aquascope.baseline.PlaceReliability
import com.aquascope.data.ScanLocation
import com.aquascope.data.ScanRecord
import com.aquascope.data.SerializableFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AskReliabilityTest {

    @Test
    fun `numbers in the reply must come from app data`() {
        val data = "table2: last_score=99% · scans=39 · self_check=54%"
        assertTrue(SmritiAnswerComposer.numbersGrounded("table2 scored 99% but the self-check is only 54%.", data))
        assertFalse(SmritiAnswerComposer.numbersGrounded("table2 is 87% damp.", data))
        assertTrue(SmritiAnswerComposer.numbersGrounded("Teach 2 more damp spots.", data))
        assertTrue(SmritiAnswerComposer.numbersGrounded("It scored 99.0% across 39 scans.", data))
        assertTrue(SmritiAnswerComposer.numbersGrounded("स्कोर ९९% था", data))
    }

    @Test
    fun `place names match whole words and prefer the longer name`() {
        val places = listOf(ScanLocation(label = "wework1"), ScanLocation(label = "wework10"), ScanLocation(label = "table2"))
        assertEquals(listOf("wework10"), PlaceReliability.mentionedIn("is wework10 damp?", places).map { it.label })
        assertEquals(listOf("table2"), PlaceReliability.mentionedIn("what about Table2", places).map { it.label })
        assertTrue(PlaceReliability.mentionedIn("is the kitchen damp?", places).isEmpty())
    }

    private fun features(rnd: Random, centroid: Double) = SerializableFeatures(
        6200 + rnd.nextDouble(-300.0, 300.0),
        1.5 + rnd.nextDouble(-0.1, 0.1),
        centroid + rnd.nextDouble(-100.0, 100.0),
        1850.0 + rnd.nextDouble(-50.0, 50.0),
        (if (centroid > 5000) 0.73 else 0.81) + rnd.nextDouble(-0.02, 0.02)
    )

    @Test
    fun `overlapping place is described as unreliable and never given a verdict`() {
        val rnd = Random(4)
        val loc = ScanLocation(
            label = "table2",
            baselineFeatures = MutableList(10) { features(rnd, 4800.0) },
            moistFeatures = MutableList(10) { features(rnd, 4800.0) },
            scanHistory = mutableListOf(ScanRecord(features = features(rnd, 4800.0), anomalyScore = 99.0))
        )
        assertEquals(PlaceReliability.Level.UNRELIABLE, PlaceReliability.of(loc).level)
        val line = PlaceReliability.promptLine(loc)
        assertTrue(line, line.contains("UNRELIABLE"))
        assertFalse(line, line.contains("sounds damp"))
        assertTrue(PlaceReliability.sentence(loc).contains("can't be trusted"))
    }

    @Test
    fun `separable place gets a verdict`() {
        val rnd = Random(5)
        val loc = ScanLocation(
            label = "weworks6",
            baselineFeatures = MutableList(8) { features(rnd, 4800.0) },
            moistFeatures = MutableList(8) { features(rnd, 5400.0) },
            scanHistory = mutableListOf(ScanRecord(features = features(rnd, 5400.0), anomalyScore = 85.0))
        )
        assertEquals(PlaceReliability.Level.RELIABLE, PlaceReliability.of(loc).level)
        assertTrue(PlaceReliability.promptLine(loc).contains("sounds damp"))
    }

    @Test
    fun `place with one damp example is preliminary`() {
        val rnd = Random(6)
        val loc = ScanLocation(
            label = "wework11",
            baselineFeatures = MutableList(3) { features(rnd, 4800.0) },
            moistFeatures = mutableListOf(features(rnd, 5400.0))
        )
        assertEquals(PlaceReliability.Level.PRELIMINARY, PlaceReliability.of(loc).level)
    }
}
