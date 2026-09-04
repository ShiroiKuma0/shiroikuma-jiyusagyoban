package com.opentasker.core.contexts

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceStateEventsTest {
    /**
     * A matcher is rebuilt on every profile edit, so it almost always subscribes after both
     * monitors have already spoken. It has to see both families, not whichever published last.
     */
    @Test
    fun aCollectorThatSubscribesAfterBothMonitorsSeesBothFamilies() = runBlocking {
        DeviceStateEvents.publishWifi("OfficeWiFi", connected = true)
        DeviceStateEvents.publishConnectivity(internet = true, networkType = "wifi", vpn = false)

        val state = DeviceStateEvents.events.first()

        assertEquals("OfficeWiFi", state["wifi"])
        assertEquals("true", state["wifi_connected"])
        assertEquals("true", state["internet"])
        assertEquals("wifi", state["network_type"])
    }

    @Test
    fun aLaterWifiChangeReplacesOnlyTheWifiKeys() = runBlocking {
        DeviceStateEvents.publishConnectivity(internet = true, networkType = "cellular", vpn = true)
        DeviceStateEvents.publishWifi("OfficeWiFi", connected = true)
        DeviceStateEvents.publishWifi("HomeWiFi", connected = true)

        val state = DeviceStateEvents.events.first()

        assertEquals("HomeWiFi", state["wifi"])
        assertEquals("HomeWiFi", state["wifi_ssid"])
        assertEquals("cellular", state["network_type"])
        assertEquals("true", state["vpn"])
    }
}
