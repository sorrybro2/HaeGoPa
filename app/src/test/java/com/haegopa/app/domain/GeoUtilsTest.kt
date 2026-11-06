package com.haegopa.app.domain

import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.util.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoUtilsTest {

    private val origin = GeoPoint.DEFAULT_MOCK_ORIGIN

    @Test
    fun `offset and distance round trip`() {
        listOf(0.0, 45.0, 90.0, 180.0, 270.0, 333.0).forEach { bearing ->
            val target = GeoUtils.offset(origin, 1_500.0, bearing)
            assertTrue(abs(GeoUtils.distanceMeters(origin, target) - 1_500.0) < 0.5)
        }
    }

    @Test
    fun `north offset projects to positive north meters`() {
        val (east, north) = GeoUtils.toLocalMeters(origin, GeoUtils.offset(origin, 1_000.0, 0.0))
        assertTrue(abs(east) < 1.0)
        assertTrue(abs(north - 1_000.0) < 10.0)
    }

    @Test
    fun `format distance`() {
        assertEquals("320m", GeoUtils.formatDistance(320))
        assertEquals("1.4km", GeoUtils.formatDistance(1_420))
        assertEquals("320미터", GeoUtils.spokenDistance(320))
    }

    @Test
    fun `eta is at least one minute and grows with distance`() {
        assertEquals(1, GeoUtils.etaMinutes(100))
        assertEquals(5, GeoUtils.etaMinutes(2_000))
    }

    @Test
    fun `bearing to korean`() {
        assertEquals("북", GeoUtils.bearingToKorean(5.0))
        assertEquals("동", GeoUtils.bearingToKorean(95.0))
        assertEquals("남서", GeoUtils.bearingToKorean(225.0))
        assertEquals("북서", GeoUtils.bearingToKorean(-40.0))
    }
}
