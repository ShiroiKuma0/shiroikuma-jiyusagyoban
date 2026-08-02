package com.opentasker.automation.network

import com.opentasker.automation.MonitorLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun failedCallbackRegistrationCanRetryAfterPermissionOrPlatformRecovery() {
        val lifecycle = MonitorLifecycle()
        var attempt = 0

        assertEquals(false, lifecycle.start { attempt++; attempt > 1 })
        assertTrue(lifecycle.start { attempt++; attempt > 1 })
        assertEquals(2, attempt)
    }
}
