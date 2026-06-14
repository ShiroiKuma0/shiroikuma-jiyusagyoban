package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothContextEventsTest {

    private fun spec(config: Map<String, String>) =
        ContextSpec(type = ContextType.EVENT, config = config)

    @Test
    fun buildEventCarriesStateDeviceAndAddress() {
        val event = BluetoothContextEvents.buildEvent(
            state = BluetoothContextEvents.STATE_CONNECTED,
            deviceName = "Car Audio",
            deviceAddress = "AA:BB:CC:DD:EE:FF",
        )
        assertEquals("event", event.type)
        assertTrue(event.matched)
        assertEquals("bluetooth", event.metadata["event"])
        assertEquals("connected", event.metadata["state"])
        assertEquals("Car Audio", event.metadata["device"])
        assertEquals("AA:BB:CC:DD:EE:FF", event.metadata["address"])
    }

    @Test
    fun blankDeviceNameFallsBackToUnknown() {
        val event = BluetoothContextEvents.buildEvent(STATE(), "", "")
        assertEquals(BluetoothContextEvents.UNKNOWN_DEVICE, event.metadata["device"])
        assertFalse(event.metadata.containsKey("address"))
    }

    @Test
    fun matchesByEventTypeOnly() {
        val event = BluetoothContextEvents.buildEvent(STATE(), "Headset", "11:22:33:44:55:66")
        assertTrue(ContextMatchEvaluator.matches(spec(mapOf("event" to "bluetooth")), event))
    }

    @Test
    fun stateFilterDistinguishesConnectFromDisconnect() {
        val connected = BluetoothContextEvents.buildEvent(BluetoothContextEvents.STATE_CONNECTED, "Speaker")
        val disconnected = BluetoothContextEvents.buildEvent(BluetoothContextEvents.STATE_DISCONNECTED, "Speaker")
        val connectSpec = spec(mapOf("event" to "bluetooth", "state" to "connected"))
        assertTrue(ContextMatchEvaluator.matches(connectSpec, connected))
        assertFalse(ContextMatchEvaluator.matches(connectSpec, disconnected))
    }

    @Test
    fun filterMatchesDeviceNameOrAddress() {
        val event = BluetoothContextEvents.buildEvent(STATE(), "Car Audio", "AA:BB:CC:DD:EE:FF")
        assertTrue(ContextMatchEvaluator.matches(spec(mapOf("event" to "bluetooth", "filter" to "Car")), event))
        assertTrue(ContextMatchEvaluator.matches(spec(mapOf("event" to "bluetooth", "filter" to "AA:BB")), event))
        assertFalse(ContextMatchEvaluator.matches(spec(mapOf("event" to "bluetooth", "filter" to "Kitchen")), event))
    }

    private fun STATE() = BluetoothContextEvents.STATE_CONNECTED
}
