package com.sysadmindoc.ruledeck.automation.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.sysadmindoc.ruledeck.core.contexts.DeviceStateEvents
import com.sysadmindoc.ruledeck.core.logging.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

class ConnectivityMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val cm: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)
    private val started = AtomicBoolean(false)
    @Volatile private var lastState: ConnState? = null

    private fun newCallback() = object : ConnectivityManager.NetworkCallback() {
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

    private val callback = newCallback()

    /**
     * A plain request is answered only for networks that are NOT a VPN, so a VPN that comes up or
     * goes down over an unchanged Wi-Fi was never noticed and `vpn` stayed what it was (白い熊,
     * 2026-11-25: the profile on `vpn=true` never fired). Two more listeners close that: the
     * network this app itself uses — it changes when a VPN takes over or lets go — and VPN
     * networks as such.
     */
    private val defaultCallback = newCallback()
    private val vpnCallback = newCallback()

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return true
        val manager = cm
        if (manager == null) {
            started.set(false)
            AppLogger.warn(TAG, "ConnectivityManager unavailable; connectivity monitoring disabled")
            emitState(ConnState(internet = false, networkType = "none", vpn = false))
            return false
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.registerNetworkCallback(request, callback)
            // Each on its own: one refused by the system must not cost the other two.
            runCatching { manager.registerDefaultNetworkCallback(defaultCallback) }
                .onFailure { AppLogger.warn(TAG, "Default-network callback refused", it) }
            runCatching {
                val vpnRequest = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                manager.registerNetworkCallback(vpnRequest, vpnCallback)
            }.onFailure { AppLogger.warn(TAG, "VPN callback refused", it) }
            emitCurrentState()
            AppLogger.debug(TAG, "Connectivity callback registered")
            return true
        } catch (ex: RuntimeException) {
            started.set(false)
            AppLogger.error(TAG, "Failed to register connectivity callback", ex)
            emitState(ConnState(internet = false, networkType = "none", vpn = false))
            return false
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        try {
            cm?.unregisterNetworkCallback(callback)
            runCatching { cm?.unregisterNetworkCallback(defaultCallback) }
            runCatching { cm?.unregisterNetworkCallback(vpnCallback) }
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
