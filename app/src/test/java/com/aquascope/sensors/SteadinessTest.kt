package com.aquascope.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteadinessTest {

    private val flat = doubleArrayOf(0.0, 0.0, 9.81)

    @Test
    fun stillPhoneOnTableIsNotMoved() {
        val s = Steadiness.summarize(List(200) { 0.02 }, List(200) { flat })
        assertFalse(Steadiness.moved(s))
        assertEquals(0.0, s!!.tiltChangeDeg!!, 1e-9)
    }

    @Test
    fun oneSpeakerSpikeIsSmoothedAway() {
        val rotations = List(200) { if (it == 100) 1.5 else 0.02 }
        assertFalse(Steadiness.moved(Steadiness.summarize(rotations, List(200) { flat })))
    }

    @Test
    fun turningPhoneIsMoved() {
        val rotations = List(200) { if (it in 80..100) 1.2 else 0.02 }
        assertTrue(Steadiness.moved(Steadiness.summarize(rotations, List(200) { flat })))
    }

    @Test
    fun tippingPhoneIsMoved() {
        val tipped = doubleArrayOf(0.0, 9.81 * Math.sin(Math.toRadians(15.0)), 9.81 * Math.cos(Math.toRadians(15.0)))
        val gravities = List(100) { flat } + List(100) { tipped }
        val s = Steadiness.summarize(List(200) { 0.02 }, gravities)
        assertEquals(15.0, s!!.tiltChangeDeg!!, 0.5)
        assertTrue(Steadiness.moved(s))
    }

    @Test
    fun wallPoseDiffersFromTablePose() {
        val table = listOf(0.0, 0.0, 9.81)
        val wall = listOf(0.0, 9.81, 0.0)
        assertEquals(90.0, Steadiness.poseDifferenceDeg(wall, listOf(table, table))!!, 1e-6)
        assertEquals(0.0, Steadiness.poseDifferenceDeg(table, listOf(table, table))!!, 1e-6)
        assertNull(Steadiness.poseDifferenceDeg(wall, listOf(table)))
    }

    @Test
    fun noSensorsGivesNoSummary() {
        assertNull(Steadiness.summarize(emptyList(), emptyList()))
        assertFalse(Steadiness.moved(null))
    }
}
