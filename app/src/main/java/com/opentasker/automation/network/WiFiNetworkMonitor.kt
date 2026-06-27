package com.opentasker.automation.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.opentasker.core.contexts.DeviceStateEvents
import com.opentasker.core.logging.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

class WiFiNetworkMonitor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val connectivityManager: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)
    private val started = AtomicBoolean(false)
    @Volatile private var lastState: WiFiState? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            emitCurrentState(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            emitCurrentState(network, networkCapabilities)
        }

        override fun onLost(network: Network) {
            emitCurrentState()
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val cm = connectivityManager
        if (cm == null) {
            started.set(false)
            AppLogger.warn(TAG, "ConnectivityManager unavailable; WiFi monitoring disabled")
            emitState(WiFiState(connected = false, ssid = UNKNOWN_SSID))
            return
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        try {
            cm.registerNetworkCallback(request, callback)
            emitCurrentState()
            AppLogger.debug(TAG, "WiFi NetworkCallback registered")
        } catch (ex: RuntimeException) {
            started.set(false)
            AppLogger.error(TAG, "Failed to register WiFi NetworkCallback", ex)
            emitState(WiFiState(connected = false, ssid = UNKNOWN_SSID))
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
            AppLogger.debug(TAG, "WiFi NetworkCallback unregistered")
        } catch (ex: RuntimeException) {
            AppLogger.warn(TAG, "WiFi NetworkCallback was already unregistered", ex)
        }
    }

    private fun emitCurrentState(
        network: Network? = connectivityManager?.activeNetwork,
        capabilities: NetworkCapabilities? = network?.let { connectivityManager?.getNetworkCapabilities(it) },
    ) {
        val connected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ssid = if (connected) readSsid(capabilities) else UNKNOWN_SSID
        emitState(WiFiState(connected = connected, ssid = ssid))
    }

    private fun emitState(state: WiFiState) {
        if (state == lastState) return
        lastState = state
        AppLogger.debug(TAG, "WiFi event: connected=${state.connected}, ssid=${state.ssid}")
        DeviceStateEvents.publishWifi(state.ssid, state.connected)
    }

    @Suppress("DEPRECATION")
    private fun readSsid(capabilities: NetworkCapabilities?): String {
        val transportSsid = if (Build.VERSION.SDK_INT >= 31) {
            (capabilities?.transportInfo as? WifiInfo)?.ssid
        } else {
            null
        }

        if (!transportSsid.isNullOrBlank()) {
            return normalizeSsid(transportSsid)
        }

        return try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            normalizeSsid(wifiManager?.connectionInfo?.ssid)
        } catch (ex: SecurityException) {
            AppLogger.warn(TAG, "WiFi SSID unavailable because permission or location access is denied", ex)
            UNKNOWN_SSID
        }
    }

    private data class WiFiState(
        val connected: Boolean,
        val ssid: String,
    )

    companion object {
        private const val TAG = "WiFiNetworkMonitor"
        const val UNKNOWN_SSID = "Unknown"

        internal fun normalizeSsid(rawSsid: String?): String {
            val cleaned = rawSsid
                ?.trim()
                ?.removeSurrounding("\"")
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

            return cleaned ?: UNKNOWN_SSID
        }
    }
}
