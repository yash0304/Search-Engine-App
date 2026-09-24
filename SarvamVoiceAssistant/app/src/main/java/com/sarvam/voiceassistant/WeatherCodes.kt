package com.sarvam.voiceassistant

/**
 * Maps WMO weather codes, which is what Open-Meteo returns, to plain words.
 *
 * The assistant speaks its answers, so "code 63" is useless — it has to be able to say
 * "moderate rain". Kept free of Android so it can be unit tested.
 */
object WeatherCodes {

    private val DESCRIPTIONS = mapOf(
        0 to "clear sky",
        1 to "mainly clear",
        2 to "partly cloudy",
        3 to "overcast",
        45 to "fog",
        48 to "freezing fog",
        51 to "light drizzle",
        53 to "drizzle",
        55 to "heavy drizzle",
        56 to "freezing drizzle",
        57 to "heavy freezing drizzle",
        61 to "light rain",
        63 to "moderate rain",
        65 to "heavy rain",
        66 to "freezing rain",
        67 to "heavy freezing rain",
        71 to "light snow",
        73 to "moderate snow",
        75 to "heavy snow",
        77 to "snow grains",
        80 to "light rain showers",
        81 to "rain showers",
        82 to "violent rain showers",
        85 to "light snow showers",
        86 to "heavy snow showers",
        95 to "a thunderstorm",
        96 to "a thunderstorm with hail",
        99 to "a thunderstorm with heavy hail",
    )

    /** Codes that mean water is falling from the sky. */
    private val WET_CODES = setOf(
        51, 53, 55, 56, 57,
        61, 63, 65, 66, 67,
        71, 73, 75, 77,
        80, 81, 82, 85, 86,
        95, 96, 99,
    )

    fun describe(code: Int): String = DESCRIPTIONS[code] ?: "unsettled weather"

    fun isWet(code: Int): Boolean = code in WET_CODES

    /**
     * Whether this reading counts as rain. Open-Meteo can report a wet code with zero
     * accumulation, so the measured amount is the tie-breaker.
     */
    fun isRaining(code: Int, precipitationMm: Double): Boolean =
        precipitationMm > 0.0 || isWet(code)

    /** Describes intensity from the measured amount, for when the code is uninformative. */
    fun intensity(precipitationMm: Double): String = when {
        precipitationMm <= 0.0 -> "none"
        precipitationMm < 0.5 -> "very light"
        precipitationMm < 2.5 -> "light"
        precipitationMm < 7.6 -> "moderate"
        else -> "heavy"
    }
}
