package com.opentasker.core.actions

import com.opentasker.ProductionSources
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.power.ShizukuCommandPolicy
import com.opentasker.core.power.ShizukuPowerBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnDisplayActionTest {
    @Test
    fun theActionIsRegisteredAsAnElevatedSettingsWrite() {
        assertNotNull("aod.set must be in the catalog", ActionCatalog.get("aod.set"))
        assertTrue("aod.set must be an elevated action", "aod.set" in ShizukuPowerBackend.elevatedActionIds)
        assertEquals("Only the two writes are allowed", 2, ShizukuCommandPolicy.variantCount("aod.set"))
        assertEquals(
            listOf("settings", "put", "secure", "doze_always_on", "1"),
            ShizukuCommandPolicy.command("aod.set", 0),
        )
        assertEquals(
            listOf("settings", "put", "secure", "doze_always_on", "0"),
            ShizukuCommandPolicy.command("aod.set", 1),
        )
        assertFalse(
            "The write must not accept another secure key",
            ShizukuCommandPolicy.isExact("aod.set", listOf("settings", "put", "secure", "doze_enabled", "1")),
        )
    }

    @Test
    fun itDeclaresItselfUnsupportedUntilTheElevatedTransportIsReady() {
        // Same level the other elevated writes carry: the transport has to be set up first.
        assertEquals(
            ActionCapabilityRegistry.get("airplane.toggle").level,
            ActionCapabilityRegistry.get("aod.set").level,
        )
        assertEquals(CapabilityLevel.RequiresSetup, ActionCapabilityRegistry.get("aod.set").level)
    }

    @Test
    fun aBuildThatIgnoresTheKeyIsReportedAsAFailure() {
        // The behaviour needs a device, so this pins the shape that makes it honest: the value is
        // read before and after, and a value that did not move is a Failure.
        val action = ProductionSources.block(
            "com/opentasker/core/actions/SettingsActions.kt",
            "class AlwaysOnDisplayAction",
            "internal const val ALWAYS_ON_DISPLAY_KEY",
        )

        assertTrue("The action must read the current value", "readAlwaysOnDisplay(ctx)" in action)
        assertTrue(
            "An absent key must fail rather than write blindly",
            "does not expose the always-on display setting" in action,
        )
        assertTrue(
            "The write must be verified rather than trusted",
            "val applied = readAlwaysOnDisplay(ctx)" in action && "applied == target" in action,
        )
        assertTrue(
            "A build that accepted and ignored the write must report a failure",
            "accepted the write and ignored it" in action,
        )
        assertFalse(
            "The action must not report success straight off the shell exit code",
            "return ctx.runShizukuAction(\"aod.set\"" in action,
        )
    }
}
