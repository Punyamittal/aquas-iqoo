package com.aquascope.baseline

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreTrendTest {

    @Test
    fun fewerThanTenScansHasNoTrend() {
        assertEquals(ScoreTrend.Direction.TOO_FEW, ScoreTrend.summarize(List(9) { 50.0 }).direction)
    }

    @Test
    fun scoresClimbingIsRising() {
        val s = ScoreTrend.summarize(List(5) { 20.0 } + List(5) { 70.0 })
        assertEquals(ScoreTrend.Direction.RISING, s.direction)
        assertEquals(70.0, s.recentMedian!!, 1e-9)
        assertEquals(20.0, s.earlierMedian!!, 1e-9)
    }

    @Test
    fun scoresDroppingIsFalling() {
        assertEquals(
            ScoreTrend.Direction.FALLING,
            ScoreTrend.summarize(List(5) { 80.0 } + List(5) { 30.0 }).direction
        )
    }

    @Test
    fun oneOutlierDoesNotMakeATrend() {
        val scores = listOf(40.0, 42.0, 38.0, 41.0, 39.0, 40.0, 43.0, 95.0, 39.0, 41.0)
        assertEquals(ScoreTrend.Direction.STEADY, ScoreTrend.summarize(scores).direction)
    }

    @Test
    fun rollingMedianUsesOnlyEarlierScans() {
        assertEquals(listOf(10.0, 15.0, 20.0, 20.0), ScoreTrend.rollingMedian(listOf(10.0, 20.0, 30.0, 20.0), 3))
    }
}
