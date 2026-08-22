package com.sarvam.voiceassistant

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class GeocodedPlace(val name: String, val coordinates: Coordinates)

/**
 * Live weather from Open-Meteo.
 *
 * Chosen because it needs no API key and no account, covers India at roughly 1 km
 * resolution, and — crucially for route questions — accepts many coordinates in a single
 * request, so checking ten points along a journey costs one HTTP call rather than ten.
 */
class WeatherService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
        const val CURRENT_FIELDS = "precipitation,weather_code,temperature_2m"

        // Nominatim's usage policy requires an identifying User-Agent.
        const val USER_AGENT = "SarvamVoiceAssistant/1.0 (Android; personal project)"
    }

    /**
     * Resolves a place name to coordinates. Null only when neither source recognises it.
     *
     * Open-Meteo's geocoder indexes populated places only, so it cannot find a neighbourhood
     * or a planned sector — "Connaught Place" and "Noida Sector 126" both come back empty.
     * OpenStreetMap does index those, so it is used as a fallback. Open-Meteo stays first
     * because it is purpose-built for this and has no usage policy to respect.
     */
    suspend fun geocode(place: String): GeocodedPlace? = withContext(Dispatchers.IO) {
        val trimmed = place.trim()
        if (trimmed.isEmpty()) return@withContext null

        openMeteoGeocode(trimmed) ?: openStreetMapGeocode(trimmed)
    }

    private fun openMeteoGeocode(place: String): GeocodedPlace? {
        val url = "$GEOCODE_URL?name=${encode(place)}&count=1&language=en&format=json"
        val first = runCatching { JSONObject(get(url)).optJSONArray("results")?.optJSONObject(0) }
            .getOrNull() ?: return null

        val latitude = first.optDouble("latitude", Double.NaN)
        val longitude = first.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) return null

        // Include the admin area so "Vadodara, Gujarat" is distinguishable from a namesake.
        val label = listOfNotNull(
            first.stringOrNullValue("name"),
            first.stringOrNullValue("admin1"),
        ).joinToString(", ").ifBlank { place }

        return GeocodedPlace(label, Coordinates(latitude, longitude))
    }

    /** Finds neighbourhoods, sectors and landmarks that the weather geocoder does not index. */
    private fun openStreetMapGeocode(place: String): GeocodedPlace? {
        val url = "$NOMINATIM_URL?q=${encode(place)}&format=jsonv2&limit=1&accept-language=en"
        val first = runCatching { JSONArray(get(url)).optJSONObject(0) }.getOrNull() ?: return null

        val latitude = first.stringOrNullValue("lat")?.toDoubleOrNull() ?: return null
        val longitude = first.stringOrNullValue("lon")?.toDoubleOrNull() ?: return null

        // display_name is the full postal-style string; the leading parts are the useful ones.
        val label = first.stringOrNullValue("display_name")
            ?.split(",")
            ?.take(3)
            ?.joinToString(",") { it.trim() }
            ?: place

        return GeocodedPlace(label, Coordinates(latitude, longitude))
    }

    /** Current conditions at one point, already phrased for the model. */
    suspend fun conditionsAt(place: String, coordinates: Coordinates): String =
        withContext(Dispatchers.IO) {
            val readings = currentAt(listOf(coordinates))
            val reading = readings.firstOrNull()
                ?: return@withContext "No weather data was available for $place."

            RainReport.forPlace(
                place = place,
                precipitationMm = reading.precipitationMm,
                weatherCode = reading.weatherCode,
                temperatureC = reading.temperatureC,
            )
        }

    /** Where it is raining between two places, as a sentence the model can speak. */
    suspend fun rainAlongRoute(from: GeocodedPlace, to: GeocodedPlace): String =
        withContext(Dispatchers.IO) {
            if (RoutePoints.isSamePlace(from.coordinates, to.coordinates)) {
                return@withContext conditionsAt(to.name, to.coordinates)
            }

            val points = RoutePoints.interpolate(from.coordinates, to.coordinates)
            val readings = currentAt(points)
            if (readings.size != points.size) {
                return@withContext "No weather data was available for that route."
            }

            val samples = points.mapIndexed { index, point ->
                RouteSample(
                    coordinates = point,
                    distanceFromStartKm = RoutePoints.distanceKm(from.coordinates, point),
                    precipitationMm = readings[index].precipitationMm,
                    weatherCode = readings[index].weatherCode,
                )
            }

            RainReport.forRoute(from.name, to.name, samples)
        }

    // ── Requesting ───────────────────────────────────────────────────────

    private data class Reading(
        val precipitationMm: Double,
        val weatherCode: Int,
        val temperatureC: Double?,
    )

    private fun currentAt(points: List<Coordinates>): List<Reading> {
        if (points.isEmpty()) return emptyList()

        val latitudes = points.joinToString(",") { trim(it.latitude) }
        val longitudes = points.joinToString(",") { trim(it.longitude) }
        val url = "$FORECAST_URL?latitude=$latitudes&longitude=$longitudes" +
            "&current=$CURRENT_FIELDS&timezone=auto"

        val body = get(url)

        // Open-Meteo returns an object for one location and an array for several.
        val entries = runCatching { JSONArray(body) }.getOrNull()
            ?: JSONArray().put(JSONObject(body))

        return (0 until entries.length()).mapNotNull { index ->
            entries.optJSONObject(index)?.optJSONObject("current")?.let { current ->
                Reading(
                    precipitationMm = current.optDouble("precipitation", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    weatherCode = current.optInt("weather_code", -1),
                    temperatureC = current.optDouble("temperature_2m", Double.NaN)
                        .takeIf { !it.isNaN() },
                )
            }
        }
    }

    private fun trim(value: Double): String = String.format("%.4f", value)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", USER_AGENT)
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from Open-Meteo")
            response.body?.string().orEmpty()
        }
    }
}

/** Same JSON-null guard as elsewhere: Android's optString renders null as "null". */
private fun JSONObject.stringOrNullValue(key: String): String? {
    if (isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
}
