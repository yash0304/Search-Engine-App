package com.sarvam.voiceassistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The device's current position, foreground only.
 *
 * No background location permission is requested: the assistant only needs to know where you
 * are at the moment you ask it something, and background access is both intrusive and
 * heavily scrutinised on the Play Store.
 */
class LocationProvider(private val context: Context) {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    fun hasPermission(): Boolean = PERMISSIONS.any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns null when permission is missing or no fix can be obtained — callers should
     * degrade to asking the user to name a place, not fail the turn.
     */
    @SuppressLint("MissingPermission") // Guarded by hasPermission above.
    suspend fun current(): Coordinates? {
        if (!hasPermission()) return null

        val fresh = requestFresh()
        if (fresh != null) return fresh

        // A cold start may have no fix yet; the last known position is still useful.
        return suspendCancellableCoroutine { continuation ->
            client.lastLocation
                .addOnSuccessListener { location ->
                    val coordinates: Coordinates? =
                        location?.let { Coordinates(it.latitude, it.longitude) }
                    continuation.resume(coordinates)
                }
                .addOnFailureListener { continuation.resume(null) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFresh(): Coordinates? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationTokenSource()

        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token)
            .addOnSuccessListener { location ->
                val coordinates: Coordinates? =
                    location?.let { Coordinates(it.latitude, it.longitude) }
                continuation.resume(coordinates)
            }
            .addOnFailureListener { continuation.resume(null) }

        continuation.invokeOnCancellation { cancellation.cancel() }
    }

    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
