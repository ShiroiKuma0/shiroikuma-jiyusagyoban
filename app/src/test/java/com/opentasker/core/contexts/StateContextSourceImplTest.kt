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
}
