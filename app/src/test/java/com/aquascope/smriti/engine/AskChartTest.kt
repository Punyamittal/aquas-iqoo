package com.aquascope.smriti.engine

import com.aquascope.data.ScanLocation
import com.aquascope.data.ScanRecord
import com.aquascope.data.SerializableFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskChartTest {

    private val f = SerializableFeatures(6200.0, 1.5, 4800.0, 1850.0, 0.8)

    private fun place(label: String, vararg scores: Double) = ScanLocation(
        id = label,
        label = label,
        baselineFeatures = mutableListOf(f),
        scanHistory = scores.mapIndexed { i, s -> ScanRecord(i.toLong(), f, s) }.toMutableList()
    )

    private val kitchen = place("kitchen", 20.0, 40.0, 70.0)
    private val bathroom = place("bathroom", 90.0)
    private val bedroom = place("bedroom", 10.0, 12.0)

    @Test
    fun `a named place gets its trend`() {
        assertEquals(AskChart(AskChart.Kind.TREND, listOf("kitchen")), AskChart.plan("Is the kitchen damp?", listOf(kitchen, bathroom)))
    }

    @Test
    fun `a named place with one scan gets no chart`() {
        assertNull(AskChart.plan("Is the bathroom damp?", listOf(kitchen, bathroom)))
    }

    @Test
    fun `two named places are compared`() {
        assertEquals(
            AskChart(AskChart.Kind.COMPARE, listOf("bathroom", "kitchen")),
            AskChart.plan("compare kitchen and bathroom", listOf(kitchen, bathroom, bedroom))
        )
    }

    @Test
    fun `asking for a chart compares every place by latest score`() {
        assertEquals(
            AskChart(AskChart.Kind.COMPARE, listOf("bathroom", "kitchen", "bedroom")),
            AskChart.plan("Show a chart of my places", listOf(bedroom, kitchen, bathroom))
        )
        assertEquals(AskChart.Kind.COMPARE, AskChart.plan("कौन सी जगहें गीली हैं?", listOf(kitchen, bathroom))?.kind)
    }

    @Test
    fun `chart with a single place shows its trend`() {
        assertEquals(AskChart(AskChart.Kind.TREND, listOf("kitchen")), AskChart.plan("show me a graph", listOf(kitchen)))
    }

    @Test
    fun `plain or off-topic questions get no chart`() {
        val all = listOf(kitchen, bathroom)
        assertNull(AskChart.plan("What was my last scan?", all))
        assertNull(AskChart.plan("What's the weather?", all))
        assertNull(AskChart.plan("Which game did I play on screen?", all))
        assertNull(AskChart.plan("Show a chart", emptyList()))
    }

    @Test
    fun `encodes and decodes`() {
        val chart = AskChart(AskChart.Kind.COMPARE, listOf("a-1", "b-2"))
        assertEquals(chart, AskChart.decode(chart.encode()))
        assertNull(AskChart.decode("pie:x"))
        assertNull(AskChart.decode("trend:"))
        assertNull(AskChart.decode(null))
    }
}
