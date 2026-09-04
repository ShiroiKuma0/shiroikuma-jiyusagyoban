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

    @Test
    fun allDisconnectedEventMatchesItsExplicitPreset() {
        val event = BluetoothContextEvents.buildAllDisconnectedEvent()
        val allDisconnected = spec(
            mapOf(
                "event" to BluetoothContextEvents.EVENT_ALL_DISCONNECTED,
                "state" to BluetoothContextEvents.STATE_ALL_DISCONNECTED,
            ),
        )

        assertEquals("0", event.metadata["connectedCount"])
        assertTrue(ContextMatchEvaluator.matches(allDisconnected, event))
    }

    @Test
    fun trackerEmitsOnlyWhenTheFinalConnectedDeviceLeaves() {
        val tracker = BluetoothConnectionTracker()
        assertTrue(tracker.onConnected("headset"))
        assertFalse(tracker.onConnected("watch"))

        assertFalse(tracker.onDisconnected("headset"))
        assertTrue(tracker.onDisconnected("watch"))
        assertFalse(tracker.onDisconnected("watch"))
        assertEquals(0, tracker.connectedCount())
        assertTrue(tracker.onConnected("watch"))
        assertEquals("bluetooth_some_connected", BluetoothContextEvents.buildSomeConnectedEvent().metadata["event"])
    }

    @Test
    fun keyMissingEventCarriesBondLossReason() {
        val event = BluetoothContextEvents.buildKeyMissingEvent(
            deviceName = "Headset",
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondLossReason = 2,
        )

        assertEquals(BluetoothContextEvents.EVENT_KEY_MISSING, event.metadata["event"])
        assertEquals(BluetoothContextEvents.STATE_KEY_MISSING, event.metadata["state"])
        assertEquals("Headset", event.metadata["device"])
        assertEquals("AA:BB:CC:DD:EE:FF", event.metadata["address"])
        assertEquals("2", event.metadata["bondLossReason"])
    }

    @Test
    fun encryptionEventCarriesSanitizedLinkAttributes() {
        val event = BluetoothContextEvents.buildEncryptionChangeEvent(
            deviceName = "Keyboard",
            enabled = true,
            status = 0,
            algorithm = 2,
            keySize = 16,
            transport = 1,
        )

        assertEquals(BluetoothContextEvents.EVENT_ENCRYPTION_CHANGE, event.metadata["event"])
        assertEquals(BluetoothContextEvents.STATE_ENCRYPTED, event.metadata["state"])
        assertEquals("true", event.metadata["enabled"])
        assertEquals("0", event.metadata["status"])
        assertEquals("2", event.metadata["algorithm"])
        assertEquals("16", event.metadata["keySize"])
        assertEquals("1", event.metadata["transport"])
    }

    @Test
    fun securityBroadcastsAreGatedToAndroid16() {
        assertFalse(BluetoothContextEvents.supportsSecurityTriggers(35))
        assertTrue(BluetoothContextEvents.supportsSecurityTriggers(36))
    }

    /**
     * The receiver is unregistered while no Bluetooth profile is enabled, so anything that
     * disconnects in that window is never seen. Carrying the old set into the next registration
     * made the first connect report no "some connected" transition and the following disconnect
     * report no "all disconnected" one, for the life of the process.
     */
    @Test
    fun trackerStartsFromNothingWhenTheReceiverIsRegisteredAgain() {
        val tracker = BluetoothConnectionTracker()
        assertTrue(tracker.onConnected("headset"))

        tracker.reset()

        assertEquals(0, tracker.connectedCount())
        assertTrue("the first connect after a restart is a transition again", tracker.onConnected("headset"))
        assertTrue("and the last disconnect still reports one", tracker.onDisconnected("headset"))
    }

    private fun STATE() = BluetoothContextEvents.STATE_CONNECTED
}
