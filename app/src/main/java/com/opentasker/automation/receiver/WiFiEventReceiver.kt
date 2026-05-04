package com.opentasker.automation.receiver

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.opentasker.automation.model.AutomationEvent

/**
 * WiFi connection/disconnection event receiver.
 * Dispatches WiFiEvent when WiFi state changes.
 */
class WiFiEventReceiver : AutomationBroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
            try {
                val connected = isWiFiConnected(context)
                val ssid = getCurrentSSID(context) ?: "Unknown"

                log("WiFi event: connected=$connected, ssid=$ssid")

                // TODO: Post event to automation engine
                // val engine = AutomationEngine.getInstance(context)
                // engine.onEvent(AutomationEvent.WiFiEvent(ssid, connected))
            } catch (e: Exception) {
                log("Error processing WiFi event", e)
            }
        }
    }

    private fun isWiFiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun getCurrentSSID(context: Context): String? {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return null

        val connectionInfo = wifiManager.connectionInfo
        return connectionInfo?.ssid?.removeSurrounding("\"")
    }
}
