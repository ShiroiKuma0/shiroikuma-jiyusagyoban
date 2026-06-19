package com.opentasker.core.capabilities

import com.opentasker.app.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCapabilitiesTest {
    @Test
    fun unsupportedActionsCannotBeAddedFromUi() {
        assertFalse(ActionCapabilityRegistry.get("reboot").canAdd)
        assertFalse(ActionCapabilityRegistry.get("wifi.toggle").canAdd)
    }

    @Test
    fun termuxScriptActionRequiresSetup() {
        val capability = ActionCapabilityRegistry.get("script.termux.run")

        assertTrue(capability.canAdd)
        assertEquals(CapabilityLevel.RequiresSetup, capability.level)
        assertTrue("Termux" in capability.reason)
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
    fun android17AudioCapabilitiesFailClosed() {
        val output = ActionCapabilityRegistry.audioOutputCapabilityForSdk(37, "Uses Android TTS.")
        val mediaKey = ActionCapabilityRegistry.mediaKeyCapabilityForSdk(37, "Dispatches a media key.")
        val volume = ActionCapabilityRegistry.volumeCapabilityForSdk(37, "Changes a media stream.")

        assertEquals(CapabilityLevel.Unsupported, output.level)
        assertEquals(CapabilityLevel.Unsupported, mediaKey.level)
        assertEquals(CapabilityLevel.Unsupported, volume.level)
        assertFalse(output.canAdd)
        assertTrue(output.reason.contains("media foreground-service type"))
        assertTrue(mediaKey.reason.contains("media key dispatch"))
        assertTrue(volume.reason.contains("background volume changes"))
    }

    @Test
    fun preAndroid17AudioCapabilitiesRemainAvailable() {
        val output = ActionCapabilityRegistry.audioOutputCapabilityForSdk(36, "Uses Android TTS.")
        val mediaKey = ActionCapabilityRegistry.mediaKeyCapabilityForSdk(36, "Dispatches a media key.")
        val volume = ActionCapabilityRegistry.volumeCapabilityForSdk(36, "Changes a media stream.")

        assertEquals(CapabilityLevel.Supported, output.level)
        assertEquals(CapabilityLevel.Supported, mediaKey.level)
        assertEquals(CapabilityLevel.RequiresSetup, volume.level)
        assertTrue(output.canAdd)
        assertTrue(mediaKey.canAdd)
        assertTrue(volume.canAdd)
    }

    @Test
    fun unknownActionsDefaultToSupportedForPluginCompatibility() {
        assertTrue(ActionCapabilityRegistry.get("plugin.example").canAdd)
    }
}
