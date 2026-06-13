package com.opentasker.core.contexts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object DeviceStateEvents {
    private val statePatches = MutableSharedFlow<Map<String, String>>(
        replay = 1,
        extraBufferCapacity = 16,
    )

    val events: SharedFlow<Map<String, String>> = statePatches.asSharedFlow()

    fun publishWifi(
        ssid: String,
        connected: Boolean,
    ): Boolean = statePatches.tryEmit(wifiPatch(ssid, connected))

    fun publishConnectivity(
        internet: Boolean,
        networkType: String,
        vpn: Boolean,
    ): Boolean = statePatches.tryEmit(connectivityPatch(internet, networkType, vpn))

    internal fun wifiPatch(
        ssid: String,
        connected: Boolean,
    ): Map<String, String> {
        val normalizedSsid = ssid.trim().ifBlank { "Unknown" }
        return mapOf(
            "wifi" to if (connected) normalizedSsid else "disconnected",
            "wifi_ssid" to if (connected) normalizedSsid else "",
            "wifi_connected" to connected.toString(),
        )
    }

    internal fun connectivityPatch(
        internet: Boolean,
        networkType: String,
        vpn: Boolean,
    ): Map<String, String> = mapOf(
        "internet" to internet.toString(),
        "network_type" to networkType,
        "vpn" to vpn.toString(),
    )
}
