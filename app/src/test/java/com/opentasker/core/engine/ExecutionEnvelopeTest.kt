package com.opentasker.core.engine

import com.opentasker.core.model.Task
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionEnvelopeTest {
    @After
    fun tearDown() = ExecutionCommandLedger.reset()

    @Test
    fun allSupportedSourcesMapToStableProducers() {
        val task = Task(id = 7, name = "Morning")
        val cases = listOf(
            "Profile: Home" to ExecutionProducer.PROFILE,
            "Quick Settings Tile: 1" to ExecutionProducer.QUICK_SETTINGS,
            "Manual run" to ExecutionProducer.MANUAL,
            "Widget" to ExecutionProducer.WIDGET,
            "Shortcut" to ExecutionProducer.SHORTCUT,
            "Notification action" to ExecutionProducer.NOTIFICATION,
            "External intent" to ExecutionProducer.EXTERNAL,
            "Locale plugin" to ExecutionProducer.LOCALE_PLUGIN,
            "Scene overlay" to ExecutionProducer.SCENE_OVERLAY,
            "Worker" to ExecutionProducer.WORKER,
        )

        cases.forEach { (source, expectedProducer) ->
            val envelope = ExecutionEnvelope.create(task, source, executionId = "id-${expectedProducer.wireValue}")
            assertEquals(expectedProducer, envelope.producer)
            assertTrue(envelope.metadataLines().single { it.startsWith("Execution ID:") }.contains(envelope.executionId))
            assertTrue(envelope.metadataLines().single { it.startsWith("Producer:") }.endsWith(expectedProducer.wireValue))
        }
    }

    @Test
    fun parentAndTerminalReasonAreStructuredAndSafe() {
        val envelope = ExecutionEnvelope.create(
            task = Task(id = 1, name = "Child"),
            source = "External intent",
            executionId = "child-1",
            parentExecutionId = "parent-1",
            causalDepth = 2,
            causalProfileChain = listOf("Home", "Work", "Home"),
        )
        val message = skippedRunLogMessage(
            source = envelope.source,
            reason = "not admitted",
            execution = envelope,
            terminalReason = ExecutionTerminalReason(
                ExecutionTerminalReasonCode.ADMISSION_REJECTED,
                "line one\nline two",
            ),
        )
        val diagnostics = message.toRunLogDiagnostics()

        assertEquals("child-1", diagnostics.executionId)
        assertEquals("parent-1", diagnostics.parentExecutionId)
        assertEquals(2, diagnostics.causalDepth)
        assertEquals(listOf("Home", "Work", "Home"), diagnostics.causalProfileChain)
        assertEquals("external", diagnostics.producer)
        assertEquals("ADMISSION_REJECTED: line one line two", diagnostics.terminalReason)
    }

    @Test
    fun replayEnvelopeUsesFreshIdAndRecordsOriginalCommand() {
        val original = ExecutionEnvelope.create(
            task = Task(id = 3, name = "Replayable"),
            source = "Profile: Home",
            executionId = "original-1",
            nowMs = 100,
        )

        val replay = original.forReplay(nowMs = 200)

        assertFalse(replay.executionId == original.executionId)
        assertEquals(original.executionId, replay.replayOf)
        assertEquals(200, replay.createdAtMs)
        assertTrue(replay.metadataLines().contains("Replay of: original-1"))
    }

    @Test
    fun duplicateDeliveryIsAcceptedOnceAndTerminalStateCannotMoveBackwards() {
        val envelope = ExecutionEnvelope.create(
            task = Task(id = 2, name = "Once"),
            source = "Manual run",
            executionId = "once-1",
            nowMs = 10,
        )

        val first = ExecutionCommandLedger.accept(envelope)
        val duplicate = ExecutionCommandLedger.accept(envelope)
        assertTrue(first.isNew)
        assertFalse(duplicate.isNew)
        assertEquals(first.record, duplicate.record)

        ExecutionCommandLedger.transition(
            envelope.executionId,
            ExecutionLedgerState.SUCCEEDED,
            ExecutionTerminalReason(ExecutionTerminalReasonCode.COMPLETED),
        )
        ExecutionCommandLedger.transition(envelope.executionId, ExecutionLedgerState.RUNNING)

        val record = requireNotNull(ExecutionCommandLedger.transition(envelope.executionId, ExecutionLedgerState.FAILED))
        assertEquals(ExecutionLedgerState.SUCCEEDED, record.state)
        assertEquals(ExecutionTerminalReasonCode.COMPLETED, record.terminalReason?.code)
    }
}
