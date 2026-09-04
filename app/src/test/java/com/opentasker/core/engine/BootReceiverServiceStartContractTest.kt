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

    /**
     * A run that arrives before the engine has loaded still has to load it.
     *
     * The notification-button and external RUN_TASK branches return before the general path that
     * reloads profiles. On a freshly started process that left the service running, heartbeat
     * current and matching nothing until the next minute tick, which the watchdog cannot see
     * because the heartbeat is healthy.
     */
    @Test
    fun aRunThatArrivesOnAColdProcessLoadsTheEngineFirst() {
        val source = repoFile("src/main/java/com/opentasker/core/engine/AutomationService.kt").readText()

        val notificationBranch = source.substringAfter("if (intent?.action == ACTION_RUN_NOTIFICATION_TASK)")
            .substringBefore("return START_STICKY")
        val externalBranch = source.substringAfter("if (intent?.action == ACTION_RUN_EXTERNAL_TASK)")
            .substringBefore("return START_STICKY")

        assertTrue(
            "a notification-button run must load the engine before running",
            "ensureEngineLoaded()" in notificationBranch,
        )
        assertTrue(
            "an external run must load the engine before running",
            "ensureEngineLoaded()" in externalBranch,
        )
        assertTrue(
            "ensureEngineLoaded must reload only when the engine is not already loaded",
            "if (!engineLoaded) reloadProfiles()" in source,
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
