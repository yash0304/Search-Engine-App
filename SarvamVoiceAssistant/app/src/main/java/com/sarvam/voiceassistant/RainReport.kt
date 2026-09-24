package com.sarvam.voiceassistant

/** One checked point along a journey. */
data class RouteSample(
    val coordinates: Coordinates,
    val distanceFromStartKm: Double,
    val precipitationMm: Double,
    val weatherCode: Int,
) {
    val isRaining: Boolean get() = WeatherCodes.isRaining(weatherCode, precipitationMm)
}

/**
 * Turns weather readings into the text handed back to the model.
 *
 * The point of the whole feature is precision: "rain from about 35 km to about 55 km" is
 * useful to someone driving, "it might rain" is not. So distances and place names go in
 * here, and the model is left to phrase them in the user's language.
 *
 * Kept free of Android so it can be unit tested.
 */
object RainReport {

    fun forPlace(
        place: String,
        precipitationMm: Double,
        weatherCode: Int,
        temperatureC: Double? = null,
    ): String {
        val conditions = WeatherCodes.describe(weatherCode)
        val temperature = temperatureC?.let { ", ${it.toInt()}°C" }.orEmpty()

        return if (WeatherCodes.isRaining(weatherCode, precipitationMm)) {
            val amount = WeatherCodes.intensity(precipitationMm)
            "$place: $conditions, currently raining ($amount, ${format(precipitationMm)} mm)$temperature."
        } else {
            "$place: $conditions, no rain right now$temperature."
        }
    }

    fun forRoute(from: String, to: String, samples: List<RouteSample>): String {
        if (samples.isEmpty()) return "No weather data was available for that route."

        val totalKm = samples.last().distanceFromStartKm
        val header = "Route $from to $to, about ${RoutePoints.spokenDistanceKm(totalKm)} km."

        val stretches = wetStretches(samples)
        if (stretches.isEmpty()) {
            val conditions = WeatherCodes.describe(samples.first().weatherCode)
            return "$header No rain anywhere along it. Conditions are $conditions."
        }

        val described = stretches.joinToString("; ") { describe(it) }
        return "$header Rain expected: $described. The rest of the route is dry."
    }

    /** Groups consecutive raining samples into contiguous stretches. */
    private fun wetStretches(samples: List<RouteSample>): List<List<RouteSample>> {
        val stretches = mutableListOf<List<RouteSample>>()
        var current = mutableListOf<RouteSample>()

        for (sample in samples) {
            if (sample.isRaining) {
                current.add(sample)
            } else if (current.isNotEmpty()) {
                stretches.add(current)
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) stretches.add(current)

        return stretches
    }

    private fun describe(stretch: List<RouteSample>): String {
        val heaviest = stretch.maxByOrNull { it.precipitationMm } ?: stretch.first()
        val label = WeatherCodes.describe(heaviest.weatherCode)

        val startKm = RoutePoints.spokenDistanceKm(stretch.first().distanceFromStartKm)
        val endKm = RoutePoints.spokenDistanceKm(stretch.last().distanceFromStartKm)

        return if (startKm == endKm) {
            "$label around $startKm km in"
        } else {
            "$label from about $startKm km to about $endKm km"
        }
    }

    private fun format(mm: Double): String =
        if (mm % 1.0 == 0.0) mm.toInt().toString() else String.format("%.1f", mm)
}
