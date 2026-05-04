package com.opentasker.core.capabilities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCapabilitiesTest {
    @Test
    fun unsupportedActionsCannotBeAddedFromUi() {
        assertFalse(ActionCapabilityRegistry.get("reboot").canAdd)
        assertFalse(ActionCapabilityRegistry.get("wifi.toggle").canAdd)
    }

    @Test
    fun unknownActionsDefaultToSupportedForPluginCompatibility() {
        assertTrue(ActionCapabilityRegistry.get("plugin.example").canAdd)
    }
}
