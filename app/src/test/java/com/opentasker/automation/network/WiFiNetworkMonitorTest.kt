package com.opentasker.automation.network

import org.junit.Assert.assertEquals
import org.junit.Test

class WiFiNetworkMonitorTest {
    @Test
    fun normalizeSsidRemovesPlatformQuotes() {
        assertEquals("OfficeWiFi", WiFiNetworkMonitor.normalizeSsid("\"OfficeWiFi\""))
    }

    @Test
    fun normalizeSsidFallsBackForUnknownPlatformValue() {
        assertEquals(WiFiNetworkMonitor.UNKNOWN_SSID, WiFiNetworkMonitor.normalizeSsid("<unknown ssid>"))
    }
}
