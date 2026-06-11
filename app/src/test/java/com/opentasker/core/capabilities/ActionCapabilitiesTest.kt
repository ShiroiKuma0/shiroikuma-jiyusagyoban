package com.opentasker.core.capabilities

import com.opentasker.app.BuildConfig
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
    fun smsCapabilityFollowsDistributionPolicy() {
        val capability = ActionCapabilityRegistry.get("sms.send")

        if (BuildConfig.SMS_ACTION_AVAILABLE) {
            assertTrue(capability.canAdd)
            assertTrue("SMS permission" in capability.reason)
        } else {
            assertFalse(capability.canAdd)
            assertTrue("Play policy" in capability.reason)
        }
    }

    @Test
    fun unknownActionsDefaultToSupportedForPluginCompatibility() {
        assertTrue(ActionCapabilityRegistry.get("plugin.example").canAdd)
    }
}
