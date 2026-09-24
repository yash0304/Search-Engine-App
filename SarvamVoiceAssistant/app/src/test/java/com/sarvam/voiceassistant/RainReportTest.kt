package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherCodesTest {

    @Test
    fun `describes codes in words a person would say`() {
        assertEquals("clear sky", WeatherCodes.describe(0))
        assertEquals("moderate rain", WeatherCodes.describe(63))
        assertEquals("a thunderstorm", WeatherCodes.describe(95))
    }

    @Test
    fun `an unknown code never leaks a number into speech`() {
        val described = WeatherCodes.describe(4242)
        assertFalse(described.any { it.isDigit() })
        assertTrue(described.isNotBlank())
    }

    @Test
    fun `recognises wet and dry codes`() {
        assertTrue(WeatherCodes.isWet(61))
        assertTrue(WeatherCodes.isWet(95))
        assertFalse(WeatherCodes.isWet(0))
        assertFalse(WeatherCodes.isWet(3))
    }

    @Test
    fun `measured rain counts even when the code looks dry`() {
        // Open-Meteo can report accumulation with an unremarkable code.
        assertTrue(WeatherCodes.isRaining(code = 3, precipitationMm = 0.4))
    }

    @Test
    fun `a wet code counts even with zero accumulation`() {
        assertTrue(WeatherCodes.isRaining(code = 61, precipitationMm = 0.0))
    }

    @Test
    fun `dry code and zero accumulation is not rain`() {
        assertFalse(WeatherCodes.isRaining(code = 1, precipitationMm = 0.0))
    }

    @Test
    fun `intensity scales with the amount`() {
        assertEquals("none", WeatherCodes.intensity(0.0))
        assertEquals("light", WeatherCodes.intensity(1.0))
        assertEquals("moderate", WeatherCodes.intensity(5.0))
        assertEquals("heavy", WeatherCodes.intensity(20.0))
    }
}

class RainReportTest {

    private fun sample(km: Double, mm: Double, code: Int) =
        RouteSample(Coordinates(23.0, 72.0), km, mm, code)

    // ── Single place ─────────────────────────────────────────────────────

    @Test
    fun `reports rain at a place with intensity`() {
        val text = RainReport.forPlace("Surat", precipitationMm = 5.0, weatherCode = 63)
        assertTrue(text.contains("Surat"))
        assertTrue(text.contains("moderate rain"))
        assertTrue(text.contains("raining"))
    }

    @Test
    fun `reports a dry place plainly`() {
        val text = RainReport.forPlace("Ahmedabad", precipitationMm = 0.0, weatherCode = 0)
        assertTrue(text.contains("no rain"))
        assertTrue(text.contains("clear sky"))
    }

    @Test
    fun `includes temperature when known`() {
        val text = RainReport.forPlace("Rajkot", 0.0, 2, temperatureC = 31.6)
        assertTrue(text.contains("31"))
    }

    // ── Along a route ────────────────────────────────────────────────────

    @Test
    fun `says clearly when the whole route is dry`() {
        val samples = listOf(sample(0.0, 0.0, 0), sample(50.0, 0.0, 0), sample(100.0, 0.0, 0))
        val text = RainReport.forRoute("Ahmedabad", "Vadodara", samples)
        assertTrue(text.contains("No rain"))
        assertTrue(text.contains("100 km"))
    }

    @Test
    fun `pinpoints where the rain starts and stops`() {
        val samples = listOf(
            sample(0.0, 0.0, 0),
            sample(20.0, 0.0, 1),
            sample(40.0, 3.0, 63),
            sample(60.0, 4.0, 63),
            sample(80.0, 0.0, 2),
            sample(100.0, 0.0, 0),
        )
        val text = RainReport.forRoute("Ahmedabad", "Vadodara", samples)
        assertTrue(text.contains("moderate rain"))
        assertTrue("expected the 40 km start, was: $text", text.contains("40 km"))
        assertTrue("expected the 60 km end, was: $text", text.contains("60 km"))
        assertTrue(text.contains("rest of the route is dry"))
    }

    @Test
    fun `reports two separate rain bands`() {
        val samples = listOf(
            sample(0.0, 2.0, 61),
            sample(20.0, 0.0, 0),
            sample(40.0, 0.0, 0),
            sample(60.0, 8.0, 65),
            sample(80.0, 0.0, 0),
        )
        val text = RainReport.forRoute("A", "B", samples)
        assertTrue(text.contains("light rain"))
        assertTrue(text.contains("heavy rain"))
        assertTrue("two bands should be separated", text.contains(";"))
    }

    @Test
    fun `a single wet point reads as a spot not a range`() {
        val samples = listOf(sample(0.0, 0.0, 0), sample(50.0, 2.0, 61), sample(100.0, 0.0, 0))
        val text = RainReport.forRoute("A", "B", samples)
        assertTrue("was: $text", text.contains("around 50 km in"))
    }

    @Test
    fun `rain right to the destination is still reported`() {
        val samples = listOf(sample(0.0, 0.0, 0), sample(50.0, 3.0, 63), sample(100.0, 4.0, 63))
        val text = RainReport.forRoute("A", "B", samples)
        assertTrue(text.contains("50 km"))
        assertTrue(text.contains("100 km"))
    }

    @Test
    fun `the heaviest reading names the stretch`() {
        val samples = listOf(sample(0.0, 0.5, 61), sample(20.0, 9.0, 65), sample(40.0, 0.0, 0))
        val text = RainReport.forRoute("A", "B", samples)
        assertTrue("should describe the worst of the stretch, was: $text", text.contains("heavy rain"))
    }

    @Test
    fun `no samples is reported rather than pretending`() {
        assertTrue(RainReport.forRoute("A", "B", emptyList()).contains("No weather data"))
    }
}
