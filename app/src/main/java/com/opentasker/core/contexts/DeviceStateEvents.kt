package com.opentasker.core.contexts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Wi-Fi and connectivity facts, published by their monitors and read by every STATE matcher.
 *
 * What is exposed is the *merged* state of both families, not the most recent patch. The two
 * families used to share one replay slot, so a matcher created after both had published saw only
 * whichever spoke last. That is not a rare case: every profile edit, enable, or disable rebuilds
 * all matchers, and both monitors suppress repeats, so a rebuilt `wifi=Home` matcher could read
 * `wifi_connected` as absent and stay unmatched until the next real Wi-Fi transition. Because the
 * engine still believed the profile was matched, the following disconnect produced no exit event
 * and the exit task never ran.
 */
object DeviceStateEvents {
    private val state = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Everything published so far, so a late collector starts with the full picture. */
    val events: StateFlow<Map<String, String>> = state.asStateFlow()

    fun publishWifi(
        ssid: String,
        connected: Boolean,
    ) = publish(wifiPatch(ssid, connected))

    fun publishConnectivity(
        internet: Boolean,
        networkType: String,
        vpn: Boolean,
    ) = publish(connectivityPatch(internet, networkType, vpn))

    private fun publish(patch: Map<String, String>) {
        state.update { current -> current + patch }
    }

    /** Audio-record state, so a profile STATE context can gate on `recording=true` / `recording=false`. */
    fun publishRecording(active: Boolean): Boolean =
        statePatches.tryEmit(mapOf("recording" to active.toString()))

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
