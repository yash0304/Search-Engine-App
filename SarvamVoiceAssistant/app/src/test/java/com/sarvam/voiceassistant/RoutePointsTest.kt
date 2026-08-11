package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePointsTest {

    private val ahmedabad = Coordinates(23.0225, 72.5714)
    private val vadodara = Coordinates(22.3072, 73.1812)
    private val surat = Coordinates(21.1702, 72.8311)

    @Test
    fun `distance matches the known road corridor`() {
        // Ahmedabad to Vadodara is about 100 km straight line.
        val km = RoutePoints.distanceKm(ahmedabad, vadodara)
        assertTrue("was $km", km in 90.0..110.0)
    }

    @Test
    fun `distance is symmetric`() {
        assertEquals(
            RoutePoints.distanceKm(ahmedabad, surat),
            RoutePoints.distanceKm(surat, ahmedabad),
            0.001,
        )
    }

    @Test
    fun `distance to self is zero`() {
        assertEquals(0.0, RoutePoints.distanceKm(ahmedabad, ahmedabad), 0.001)
    }

    @Test
    fun `interpolation includes both endpoints`() {
        val points = RoutePoints.interpolate(ahmedabad, vadodara, 5)
        assertEquals(5, points.size)
        assertEquals(ahmedabad, points.first())
        assertEquals(vadodara.latitude, points.last().latitude, 0.0001)
        assertEquals(vadodara.longitude, points.last().longitude, 0.0001)
    }

    @Test
    fun `interpolated points are evenly spaced`() {
        val points = RoutePoints.interpolate(ahmedabad, surat, 5)
        val gaps = points.zipWithNext { a, b -> RoutePoints.distanceKm(a, b) }
        val first = gaps.first()
        gaps.forEach { assertEquals(first, it, 1.0) }
    }

    @Test
    fun `midpoint of three samples sits halfway`() {
        val points = RoutePoints.interpolate(ahmedabad, vadodara, 3)
        val toMid = RoutePoints.distanceKm(ahmedabad, points[1])
        val midToEnd = RoutePoints.distanceKm(points[1], vadodara)
        assertEquals(toMid, midToEnd, 1.0)
    }

    @Test
    fun `sample count is clamped to something meaningful`() {
        assertEquals(2, RoutePoints.interpolate(ahmedabad, vadodara, 1).size)
        assertEquals(2, RoutePoints.interpolate(ahmedabad, vadodara, 0).size)
    }

    @Test
    fun `identical endpoints do not divide by zero`() {
        val points = RoutePoints.interpolate(ahmedabad, ahmedabad, 5)
        assertTrue(points.isNotEmpty())
        points.forEach { assertEquals(ahmedabad, it) }
    }

    // ── Spoken distances ─────────────────────────────────────────────────

    @Test
    fun `spoken distance avoids false precision`() {
        assertEquals(35, RoutePoints.spokenDistanceKm(34.7183))
        assertEquals(100, RoutePoints.spokenDistanceKm(102.4))
        assertEquals(7, RoutePoints.spokenDistanceKm(7.6))
        assertEquals(0, RoutePoints.spokenDistanceKm(0.3))
    }

    @Test
    fun `nearby coordinates count as the same place`() {
        val nearby = Coordinates(ahmedabad.latitude + 0.001, ahmedabad.longitude)
        assertTrue(RoutePoints.isSamePlace(ahmedabad, nearby))
        assertTrue(!RoutePoints.isSamePlace(ahmedabad, vadodara))
    }
}
