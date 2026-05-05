package com.opentasker.core.engine

import com.opentasker.core.model.RunLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLogDiagnosticsTest {
    @Test
    fun runLogMessageIncludesSourceMetadataAndTraceDetails() {
        val message = runLogMessage(
            source = "Profile: Morning",
            metadata = listOf("Mode: single", "Cooldown: 30s"),
            traces = listOf(
                ActionExecutionTrace(
                    index = 0,
                    actionType = "notify.show",
                    label = "Notify",
                    durationMs = 12,
                    status = ActionTraceStatus.SUCCESS,
                    message = "Completed",
                )
            ),
        )

        val diagnostics = message.toRunLogDiagnostics()

        assertEquals("Profile: Morning", diagnostics.source)
        assertEquals(listOf("Mode: single", "Cooldown: 30s"), diagnostics.detailLines)
        assertEquals(1, diagnostics.traces.size)
        assertEquals("Notify", diagnostics.traces.single().label)
        assertEquals(ActionTraceStatus.SUCCESS, diagnostics.traces.single().status)
    }

    @Test
    fun skippedRunLogMessageClassifiesOutcomeAsSkipped() {
        val entry = RunLogEntry(
            taskId = 1,
            taskName = "Morning",
            durationMs = 0,
            success = false,
            message = skippedRunLogMessage(
                source = "Profile: Work",
                reason = "Cooldown active for 15 seconds.",
            ),
        )

        val diagnostics = entry.message.toRunLogDiagnostics()

        assertTrue(diagnostics.isSkipped)
        assertEquals("Cooldown active for 15 seconds.", diagnostics.reason)
        assertEquals(RunLogOutcome.Skipped, entry.outcome())
    }
}
