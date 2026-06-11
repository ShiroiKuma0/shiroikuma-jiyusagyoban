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
}
