package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceContextEventsTest {
    @Test
    fun buildEventCarriesStateAndUsbIdentity() {
        val event = UsbDeviceContextEvents.buildEvent(
            state = UsbDeviceContextEvents.STATE_ATTACHED,
            deviceName = "Keyboard",
            vendorId = 0x046d,
            productId = 0xc31c,
            deviceClass = 3,
        )

        assertEquals("usb", event.metadata["event"])
        assertEquals("attached", event.metadata["state"])
        assertEquals("Keyboard", event.metadata["device"])
        assertEquals("1133", event.metadata["vendorId"])
        assertEquals("49948", event.metadata["productId"])
        assertEquals("3", event.metadata["class"])
        assertTrue(event.matched)
    }

    @Test
    fun blankNameUsesStableFallback() {
        val event = UsbDeviceContextEvents.buildEvent(
            state = UsbDeviceContextEvents.STATE_DETACHED,
            deviceName = "",
            vendorId = 1,
            productId = 2,
            deviceClass = 0,
        )

        assertEquals(UsbDeviceContextEvents.UNKNOWN_DEVICE, event.metadata["deviceName"])
    }
}
