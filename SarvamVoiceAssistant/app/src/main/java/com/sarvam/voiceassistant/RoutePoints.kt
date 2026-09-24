package com.sarvam.voiceassistant

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class Coordinates(val latitude: Double, val longitude: Double)

/**
 * Turns a journey into a handful of points to check the weather at.
 *
 * Points are interpolated along a straight line between the endpoints, not along the actual
 * road — there is no routing provider here. Over the few hundred kilometres of a typical
 * day's travel the error is small, but on a winding route the samples will sit off the road.
 *
 * Kept free of Android so the maths can be unit tested.
 */
object RoutePoints {

    private const val EARTH_RADIUS_KM = 6371.0

    /** How many points to check along a route. One request covers all of them. */
    const val DEFAULT_SAMPLES = 10

    /** Great-circle distance in kilometres. */
    fun distanceKm(from: Coordinates, to: Coordinates): Double {
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)

        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(h)))
    }

    /**
     * [samples] evenly spaced points from [from] to [to], both endpoints included.
     * Fewer than two samples is meaningless, so it is clamped.
     */
    fun interpolate(from: Coordinates, to: Coordinates, samples: Int = DEFAULT_SAMPLES): List<Coordinates> {
        val count = maxOf(2, samples)
        if (from == to) return listOf(from, to)

        return (0 until count).map { i ->
            val fraction = i.toDouble() / (count - 1)
            Coordinates(
                latitude = from.latitude + (to.latitude - from.latitude) * fraction,
                longitude = from.longitude + (to.longitude - from.longitude) * fraction,
            )
        }
    }

    /** Rounds a distance to something worth saying aloud: "about 35 km", not "34.7183 km". */
    fun spokenDistanceKm(km: Double): Int = when {
        km < 1 -> 0
        km < 10 -> km.toInt()
        // Nearest 5 km — false precision helps nobody. Round, don't truncate: 34.7 km is
        // "35 km" to a driver, and truncating would call it 30.
        else -> (km / 5.0).roundToInt() * 5
    }

    /** True when two coordinates are close enough to be treated as the same place. */
    fun isSamePlace(a: Coordinates, b: Coordinates, toleranceKm: Double = 1.0): Boolean =
        abs(distanceKm(a, b)) <= toleranceKm
}
