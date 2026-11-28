package com.sysadmindoc.ruledeck.core.capabilities

import com.sysadmindoc.ruledeck.core.model.ActionSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Run Shell` in root mode must not be held up for Shizuku by the pre-run check: on 2026-11-28 both
 * Slaves had no Shizuku and every root task was refused before its first step.
 */
class RootModeShellPreflightTest {
    @Test
    fun rootModeShellIsNotAskedForShizuku() {
        assertTrue(CapabilityState.runsAsRoot(ActionSpec(type = "shell.run", args = mapOf("mode" to "root"))))
        assertTrue(CapabilityState.runsAsRoot(ActionSpec(type = "shell.run", args = mapOf("mode" to " Root "))))
    }

    @Test
    fun everythingElseKeepsItsRequirement() {
        assertFalse(CapabilityState.runsAsRoot(ActionSpec(type = "shell.run")))
        assertFalse(CapabilityState.runsAsRoot(ActionSpec(type = "shell.run", args = mapOf("mode" to "shizuku"))))
        assertFalse(CapabilityState.runsAsRoot(ActionSpec(type = "shell.run", args = mapOf("mode" to "plain"))))
        assertFalse(CapabilityState.runsAsRoot(ActionSpec(type = "shell.run", args = mapOf("mode" to "%mode"))))
        assertFalse(CapabilityState.runsAsRoot(ActionSpec(type = "wifi.toggle", args = mapOf("mode" to "root"))))
    }
}
