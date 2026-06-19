package com.opentasker.core.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class ShizukuPowerBackendTest {
    @After
    fun resetKillSwitch() {
        ShizukuPowerBackend.killSwitchEnabled = false
    }

    @Test
    fun statusForReportsManagerPresence() {
        val installed = ShizukuPowerBackend.statusFor(managerInstalled = true)
        val missing = ShizukuPowerBackend.statusFor(managerInstalled = false)

        assertEquals(ShizukuPowerState.ManagerInstalled, installed.state)
        assertTrue(installed.managerInstalled)
        assertEquals(ShizukuPowerState.NotInstalled, missing.state)
        assertFalse(missing.managerInstalled)
    }

    @Test
    fun elevatedActionHintsOnlyCoverRestrictedCandidates() {
        assertNotNull(ShizukuPowerBackend.hintForAction("reboot"))
        assertNotNull(ShizukuPowerBackend.hintForAction("airplane.toggle"))
        assertNull(ShizukuPowerBackend.hintForAction("notify.show"))
    }

    @Test
    fun managerPackageIsStableForPackageVisibilityQueries() {
        assertEquals("moe.shizuku.privileged.api", ShizukuPowerBackend.MANAGER_PACKAGE)
    }

    @Test
    fun killSwitchDisablesBackend() {
        ShizukuPowerBackend.killSwitchEnabled = true
        assertFalse(ShizukuPowerBackend.isReady())
    }

    @Test
    fun shellRunnerRejectsUnknownAction() {
        val result = ShizukuShellRunner.execute("unknown.action")
        assertTrue(result is ShellResult.Failure)
        assertTrue((result as ShellResult.Failure).reason.contains("not in the Shizuku allowlist"))
    }

    @Test
    fun shellRunnerRejectsWhenKillSwitchActive() {
        ShizukuPowerBackend.killSwitchEnabled = true
        val result = ShizukuShellRunner.execute("reboot")
        assertTrue(result is ShellResult.Failure)
        assertTrue((result as ShellResult.Failure).reason.contains("kill-switch"))
    }

    @Test
    fun allElevatedActionsAreInAllowlist() {
        ShizukuPowerBackend.elevatedActionIds.forEach { actionId ->
            assertTrue("$actionId should be in allowlist", ShizukuShellRunner.isAllowed(actionId))
            assertTrue("$actionId should have variants", ShizukuShellRunner.allowedVariantCount(actionId) > 0)
        }
    }

    @Test
    fun statusForDisabledShowsKillSwitchState() {
        ShizukuPowerBackend.killSwitchEnabled = true
        val status = ShizukuPowerBackend.statusFor(managerInstalled = true)
        assertEquals(ShizukuPowerState.ManagerInstalled, status.state)
    }
}
