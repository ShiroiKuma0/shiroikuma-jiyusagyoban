package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StateContextSourceImplTest {
    @Test
    fun mergeStatePatchKeepsExistingFactsAcrossPartialBroadcasts() {
        val initial = mapOf("screen" to "on", "headphones" to "true")

        val merged = mergeStatePatch(initial, mapOf("battery_level" to "42", "charging" to "false"))

        assertEquals(
            mapOf(
                "screen" to "on",
                "headphones" to "true",
                "battery_level" to "42",
                "charging" to "false",
            ),
            merged,
        )
    }

    @Test
    fun mergeStatePatchReplacesOnlyKeysPresentInPatch() {
        val merged = mergeStatePatch(
            mapOf("screen" to "on", "battery_level" to "80", "charging" to "true"),
            mapOf("screen" to "off"),
        )

        assertEquals(
            mapOf("screen" to "off", "battery_level" to "80", "charging" to "true"),
            merged,
        )
    }

    @Test
    fun mergeStatePatchReturnsSameMapForEmptyPatch() {
        val initial = mapOf("screen" to "on")

        assertSame(initial, mergeStatePatch(initial, emptyMap()))
    }

    @Test
    fun wifiStatePatchSupportsSsidAndConnectedPredicates() {
        val state = DeviceStateEvents.wifiPatch("OfficeWiFi", connected = true)

        assertEquals("OfficeWiFi", state["wifi"])
        assertEquals("true", state["wifi_connected"])
        assertTrue(stateMatches("wifi=OfficeWiFi", state))
        assertTrue(stateMatches("wifi=connected", state))
        assertTrue(stateMatches("wifi_ssid=OfficeWiFi", state))
        assertFalse(stateMatches("wifi=HomeWiFi", state))
    }

    @Test
    fun disconnectedWifiStateSupportsDisconnectedPredicate() {
        val state = DeviceStateEvents.wifiPatch("OfficeWiFi", connected = false)

        assertEquals("disconnected", state["wifi"])
        assertEquals("false", state["wifi_connected"])
        assertTrue(stateMatches("wifi=disconnected", state))
        assertFalse(stateMatches("wifi=OfficeWiFi", state))
    }

    @Test
    fun unlockedPredicateMatchesTrueFalse() {
        val state = mapOf("unlocked" to "true")
        assertTrue(stateMatches("unlocked=true", state))
        assertTrue(stateMatches("unlocked=unlocked", state))
        assertFalse(stateMatches("unlocked=locked", state))
        assertFalse(stateMatches("unlocked=false", state))
    }

    @Test
    fun powerSavePredicateMatchesWithAliases() {
        val on = mapOf("power_save" to "true")
        assertTrue(stateMatches("power_save=true", on))
        assertTrue(stateMatches("power_save=on", on))
        assertTrue(stateMatches("power_save=enabled", on))
        assertTrue(stateMatches("battery_saver=true", on))
        assertFalse(stateMatches("power_save=off", on))

        val off = mapOf("power_save" to "false")
        assertTrue(stateMatches("power_save=false", off))
        assertTrue(stateMatches("power_save=disabled", off))
        assertFalse(stateMatches("power_save=true", off))
    }

    @Test
    fun airplanePredicateMatchesWithAliases() {
        val on = mapOf("airplane" to "true")
        assertTrue(stateMatches("airplane=true", on))
        assertTrue(stateMatches("airplane=on", on))
        assertTrue(stateMatches("airplane_mode=enabled", on))
        assertTrue(stateMatches("flight_mode=true", on))
        assertFalse(stateMatches("airplane=off", on))

        val off = mapOf("airplane" to "false")
        assertTrue(stateMatches("airplane=false", off))
        assertTrue(stateMatches("airplane=disabled", off))
        assertFalse(stateMatches("airplane=true", off))
    }

    @Test
    fun normalizeStateKeyMapsNewAliases() {
        assertEquals("power_save", normalizeStateKey("battery_saver"))
        assertEquals("power_save", normalizeStateKey("powersave"))
        assertEquals("power_save", normalizeStateKey("power_saver"))
        assertEquals("airplane", normalizeStateKey("airplane_mode"))
        assertEquals("airplane", normalizeStateKey("flight_mode"))
        assertEquals("unlocked", normalizeStateKey("device_unlocked"))
    }

    @Test
    fun connectivityPatchSupportsInternetAndVpnPredicates() {
        val state = DeviceStateEvents.connectivityPatch(
            internet = true,
            networkType = "wifi",
            vpn = false,
        )

        assertTrue(stateMatches("internet=true", state))
        assertTrue(stateMatches("network_type=wifi", state))
        assertTrue(stateMatches("vpn=false", state))
        assertFalse(stateMatches("internet=false", state))
    }
}
