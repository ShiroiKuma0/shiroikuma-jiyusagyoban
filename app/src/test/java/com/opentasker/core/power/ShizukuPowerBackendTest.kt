package com.opentasker.core.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuPowerBackendTest {
    @Test
    fun statusForReportsManagerPresenceWithoutClaimingApiLink() {
        val installed = ShizukuPowerBackend.statusFor(managerInstalled = true)
        val missing = ShizukuPowerBackend.statusFor(managerInstalled = false)

        assertEquals(ShizukuPowerState.ManagerInstalled, installed.state)
        assertTrue(installed.managerInstalled)
        assertTrue("not linked" in installed.summary)
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
}
