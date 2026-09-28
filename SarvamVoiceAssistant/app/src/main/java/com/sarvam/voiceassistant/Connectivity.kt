package com.sarvam.voiceassistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether the phone can reach the internet right now, for choosing where answers come from. */
class Connectivity(context: Context) {

    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /**
     * Requires a *validated* network: connected to Wi-Fi behind a login page, or with mobile
     * data that has no signal, counts as offline — which is what matters for a request.
     */
    fun isOnline(): Boolean = runCatching {
        val network = manager?.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true) // If the check itself fails, try the network rather than refuse.
}
