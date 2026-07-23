package com.opentasker.core.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notification-button receiver must not execute tasks inside its short broadcast window; it
 * hands the run to the foreground AutomationService so long tasks complete and log reliably.
 */
class NotificationActionReceiverTest {
    private fun source(relativePath: String): String =
        listOf(File("src/main/java/$relativePath"), File("app/src/main/java/$relativePath"))
            .first { it.exists() }
            .readText()

    @Test
    fun receiverForwardsToServiceInsteadOfRunningInline() {
        val receiver = source("com/opentasker/core/actions/NotificationActionReceiver.kt")
        assertFalse(
            "Receiver must not run tasks inside its goAsync window",
            receiver.contains("goAsync()"),
        )
        assertFalse(
            "Receiver must not execute the task inline",
            receiver.contains("executeAndLogTask"),
        )
        assertTrue(
            "Receiver must start the AutomationService run action",
            receiver.contains("AutomationService.ACTION_RUN_NOTIFICATION_TASK"),
        )
        assertTrue(
            "Receiver must launch the service as a foreground service",
            receiver.contains("startForegroundService"),
        )
    }
}
