package com.jarvis.assistant.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide view of "does this phone have a working internet connection right now?".
 *
 * JARVIS uses it to switch between the cloud brain and the on-device Qwen3 brain the moment the
 * connection is lost or comes back, instead of only checking once when a conversation starts.
 * "Working" means Android has validated the connection (a Wi-Fi network with no internet, or a
 * captive portal, counts as offline).
 */
object NetworkStateMonitor {
    private const val TAG = "NetworkStateMonitor"

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start(context: Context) {
        if (callback != null) return
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        _isOnline.value = OfflineModelDownloader.isInternetAvailable(appContext)

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Capabilities (including "validated") arrive separately, so just re-read the current state.
                _isOnline.value = OfflineModelDownloader.isInternetAvailable(appContext)
            }

            override fun onLost(network: Network) {
                // A callback for the default network only fires onLost when no network replaced it.
                _isOnline.value = false
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _isOnline.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }

        try {
            manager.registerDefaultNetworkCallback(networkCallback)
            callback = networkCallback
        } catch (error: Exception) {
            // Without the callback the app still works; conversations just check the network when they start.
            Log.w(TAG, "Could not watch the network: ${error.message}")
        }
    }
}
