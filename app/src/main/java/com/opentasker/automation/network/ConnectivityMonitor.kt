package com.opentasker.automation.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.opentasker.core.contexts.DeviceStateEvents
import com.opentasker.core.logging.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

class ConnectivityMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val cm: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)
    private val started = AtomicBoolean(false)
    @Volatile private var lastState: ConnState? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            emitCurrentState()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            emitCurrentState()
        }

        override fun onLost(network: Network) {
            emitCurrentState()
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val manager = cm
        if (manager == null) {
            started.set(false)
            AppLogger.warn(TAG, "ConnectivityManager unavailable; connectivity monitoring disabled")
            emitState(ConnState(internet = false, networkType = "none", vpn = false))
            return
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.registerNetworkCallback(request, callback)
            emitCurrentState()
            AppLogger.debug(TAG, "Connectivity callback registered")
        } catch (ex: RuntimeException) {
            started.set(false)
            AppLogger.error(TAG, "Failed to register connectivity callback", ex)
            emitState(ConnState(internet = false, networkType = "none", vpn = false))
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        try {
            cm?.unregisterNetworkCallback(callback)
            AppLogger.debug(TAG, "Connectivity callback unregistered")
        } catch (ex: RuntimeException) {
            AppLogger.warn(TAG, "Connectivity callback was already unregistered", ex)
        }
    }

    private fun emitCurrentState() {
        val manager = cm ?: return
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val networkType = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        emitState(ConnState(internet, networkType, vpn))
    }

    private fun emitState(state: ConnState) {
        if (state == lastState) return
        lastState = state
        AppLogger.debug(TAG, "Connectivity: internet=${state.internet}, type=${state.networkType}, vpn=${state.vpn}")
        DeviceStateEvents.publishConnectivity(state.internet, state.networkType, state.vpn)
    }

    private data class ConnState(
        val internet: Boolean,
        val networkType: String,
        val vpn: Boolean,
    )

    companion object {
        private const val TAG = "ConnectivityMonitor"
    }
}
