package com.aquascope.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoMatchTest {

    @Test
    fun knownScanIdMatchesExactly() {
        val result = SurfacePhotoArchive.matchPhotos(
            listOf(5_000L to "1000_place", 9_000L to null),
            listOf("1000_place", "8000_place")
        )
        assertEquals(listOf("1000_place", "8000_place"), result)
    }

    @Test
    fun oldRecordTakesNearestRecordingJustBeforeIt() {
        val result = SurfacePhotoArchive.matchPhotos(
            listOf(100_000L to null),
            listOf("40000_place", "97000_place")
        )
        assertEquals(listOf("97000_place"), result)
    }

    @Test
    fun photoAfterTheRecordOrTooEarlyIsNotMatched() {
        val result = SurfacePhotoArchive.matchPhotos(
            listOf(500_000L to null),
            listOf("501000_place", "100000_place")
        )
        assertEquals(listOf<String?>(null), result)
    }

    @Test
    fun eachPhotoIsUsedOnce() {
        val result = SurfacePhotoArchive.matchPhotos(
            listOf(10_000L to null, 11_000L to null),
            listOf("9000_place")
        )
        assertEquals(1, result.count { it != null })
    }
}
