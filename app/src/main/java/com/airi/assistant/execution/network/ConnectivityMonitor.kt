package com.airi.assistant.execution.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Reactive network connectivity observer for the execution layer.
 *
 * Wraps [ConnectivityManager.NetworkCallback] as a [Flow] so routers and
 * diagnostic UIs can react to connectivity changes without polling.
 *
 * ## Usage
 * ```kotlin
 * ConnectivityMonitor.observe(context).collect { isOnline ->
 *     if (!isOnline) router.degradeToLocal()
 * }
 * ```
 *
 * ## Lifecycle
 * The [Flow] automatically unregisters the [ConnectivityManager.NetworkCallback]
 * when the collector scope is cancelled — no manual cleanup required.
 *
 * ## Thread safety
 * [ConnectivityManager] callbacks fire on the main thread. The [callbackFlow]
 * builder forwards them to whatever dispatcher the collector runs on.
 */
object ConnectivityMonitor {

    private const val TAG = "AIRI_ConnectivityMonitor"

    /**
     * Emit [Boolean] connectivity updates:
     *  true  = at least one network with INTERNET capability is available
     *  false = no usable network
     *
     * The first emission reflects the state at subscription time.
     */
    fun observe(context: Context): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Availability only means Android selected a network. Wait for the
                // validated capability before allowing cloud routing; a captive portal
                // or restricted Wi-Fi can be available without usable internet.
                Log.d(TAG, "Network available: $network — checking validation")
                trySend(hasInternet(cm))
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network — checking remaining networks")
                // Only emit false if truly no network remains.
                trySend(hasInternet(cm))
            }

            override fun onUnavailable() {
                Log.d(TAG, "Network unavailable")
                trySend(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val hasNet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                             capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                trySend(hasNet)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // Emit current state immediately before subscribing.
        trySend(hasInternet(cm))

        cm.registerNetworkCallback(request, callback)

        awaitClose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterNetworkCallback failed: ${e.message}")
            }
        }
    }.distinctUntilChanged()

    /**
     * Synchronous one-shot connectivity check.
     * Use [observe] for reactive updates; use this only when a Flow is not practical.
     */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return hasInternet(cm)
    }

    private fun hasInternet(cm: ConnectivityManager): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps   = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
