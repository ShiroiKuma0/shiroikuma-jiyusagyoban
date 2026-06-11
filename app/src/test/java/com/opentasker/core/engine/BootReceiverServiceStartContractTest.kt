package com.opentasker.core.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverServiceStartContractTest {
    @Test
    fun bootReceiverRequestsBootTriggerActionThroughGuardedServiceStart() {
        val source = repoFile("src/main/java/com/opentasker/core/engine/BootReceiver.kt").readText()

        assertTrue("BootReceiver should guard boot service startup", "runCatching" in source)
        assertTrue("BootReceiver should use foreground service startup", "ContextCompat.startForegroundService" in source)
        assertTrue(
            "BootReceiver should tag the service start as a boot trigger",
            "AutomationService.ACTION_BOOT_COMPLETED_TRIGGER" in source,
        )
    }

    @Test
    fun automationServicePublishesBootEventAfterReloadingProfiles() {
        val source = repoFile("src/main/java/com/opentasker/core/engine/AutomationService.kt").readText()

        assertTrue("AutomationService should expose a boot trigger action", "ACTION_BOOT_COMPLETED_TRIGGER" in source)
        assertTrue("AutomationService should reload profiles on start", "reloadProfiles()" in source)
        assertTrue("AutomationService should publish boot event pulses", "BootContextEvents.publishBootCompleted()" in source)
        assertTrue(
            "AutomationService should publish boot only after profile reload",
            source.indexOf("reloadProfiles()") < source.indexOf("BootContextEvents.publishBootCompleted()"),
        )
    }

    private fun repoFile(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() }
}
