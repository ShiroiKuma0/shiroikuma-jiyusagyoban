package com.opentasker.core.capabilities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCapabilitiesTest {
    @Test
    fun unsupportedActionsCannotBeAddedFromUi() {
        assertFalse(ActionCapabilityRegistry.get("reboot").canAdd)
        assertFalse(ActionCapabilityRegistry.get("wifi.toggle").canAdd)
        assertFalse(ActionCapabilityRegistry.get("script.termux.run").canAdd)
    }

    @Test
    fun termuxScriptActionExplainsBlockedBackend() {
        val capability = ActionCapabilityRegistry.get("script.termux.run")

        assertFalse(capability.canAdd)
        assertTrue("Termux:Tasker" in capability.reason)
        assertTrue("output capture" in capability.reason)
    }

    @Test
    fun unknownActionsDefaultToSupportedForPluginCompatibility() {
        assertTrue(ActionCapabilityRegistry.get("plugin.example").canAdd)
    }
}
