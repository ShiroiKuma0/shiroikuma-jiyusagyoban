package com.opentasker.core.capabilities

import com.opentasker.app.BuildConfig
import com.opentasker.core.power.ShizukuPowerBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCapabilitiesTest {
    @Test
    fun unsupportedActionsCannotBeAddedFromUi() {
        // `wifi.toggle` is NOT in this list any more: the fork drives it through Shizuku, so it is
        // setup-gated and addable. These two genuinely cannot work — reboot needs device-owner
        // privilege, and the Quick Settings tile update is unimplemented.
        assertFalse(ActionCapabilityRegistry.get("reboot").canAdd)
        assertFalse(ActionCapabilityRegistry.get("tile.set").canAdd)
    }

    @Test
    /**
     * The fork ships privileged Shizuku execution (`runElevated`), so these actions are **setup-gated**
     * rather than unsupported: addable, and honest that they need Shizuku installed, running and
     * granted. Upstream's opposite assertion — that this app never ships a privileged transport — is
     * the thing the fork exists to change.
     */
    fun elevatedActionsAreSetupGatedOnShizuku() {
        // `reboot` is the exception and stays unsupported: shell access is not enough for it, it wants
        // device-owner or system-app privilege, so it fails closed rather than pretending Shizuku helps.
        (ShizukuPowerBackend.elevatedActionIds - "reboot").forEach { actionId ->
            val capability = ActionCapabilityRegistry.get(actionId)
            assertEquals("$actionId should be setup-gated", CapabilityLevel.RequiresSetup, capability.level)
            assertTrue("$actionId must remain addable", capability.canAdd)
            // Shizuku for most; `screen.off` is driven by the accessibility service's global action
            // instead. Either way the gate must be a real, named requirement the Setup tab can resolve.
            assertTrue(
                "$actionId must name a resolvable requirement",
                capability.requirement in setOf(CapabilityRequirement.Shizuku, CapabilityRequirement.Accessibility),
            )
        }
    }

    @Test
    fun rebootStaysUnsupportedBecauseShizukuIsNotEnoughForIt() {
        val capability = ActionCapabilityRegistry.get("reboot")

        assertEquals(CapabilityLevel.Unsupported, capability.level)
        assertFalse(capability.canAdd)
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
    fun android17AudioCapabilitiesRemainAddableWithRuntimeEligibilityWarning() {
        val output = ActionCapabilityRegistry.audioOutputCapabilityForSdk(37, "Uses Android TTS.")
        val mediaKey = ActionCapabilityRegistry.mediaKeyCapabilityForSdk(37, "Dispatches a media key.")
        val volume = ActionCapabilityRegistry.volumeCapabilityForSdk(37, "Changes a media stream.")

        assertEquals(CapabilityLevel.RequiresSetup, output.level)
        assertEquals(CapabilityLevel.RequiresSetup, mediaKey.level)
        assertEquals(CapabilityLevel.RequiresSetup, volume.level)
        assertTrue(output.canAdd)
        assertTrue(output.reason.contains("while-in-use eligible foreground service"))
        assertTrue(mediaKey.reason.contains("media-key dispatch"))
        assertTrue(volume.reason.contains("volume changes"))
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
    fun unknownActionsFailClosedUntilClassified() {
        assertFalse(ActionCapabilityRegistry.get("plugin.example").canAdd)
        assertEquals(CapabilityLevel.Unsupported, ActionCapabilityRegistry.get("plugin.example").level)
    }
}
