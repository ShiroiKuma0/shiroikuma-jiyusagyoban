package com.opentasker.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActiveExecutionRegistryTest {

    @Before
    fun setUp() = ActiveExecutionRegistry.reset()

    @After
    fun tearDown() = ActiveExecutionRegistry.reset()

    @Test
    fun aRegisteredRunIsVisibleWhileItIsInFlightAndGoneAfterwards() {
        val id = ActiveExecutionRegistry.register(
            taskId = 7,
            taskName = "Morning",
            source = "Profile: Home",
            job = Job(),
            startedAtMs = 1_000,
        )

        val execution = ActiveExecutionRegistry.active.value.single()
        assertEquals(id, execution.id)
        assertEquals("Morning", execution.taskName)
        assertEquals("Profile: Home", execution.source)
        assertFalse(execution.cancelling)

        ActiveExecutionRegistry.unregister(id)
        assertTrue(ActiveExecutionRegistry.active.value.isEmpty())
    }

    @Test
    fun theCurrentStepIsReportedSoAStuckRunCanBeIdentified() {
        val id = ActiveExecutionRegistry.register(1, "T", "Manual", Job(), 0)
        ActiveExecutionRegistry.reportStep(id, stepIndex = 3, stepLabel = "flow.wait")

        val execution = ActiveExecutionRegistry.active.value.single()
        assertEquals(3, execution.stepIndex)
        assertEquals("flow.wait", execution.stepLabel)
    }

    @Test
    fun cancellingMarksTheRunAndCancelsItsJob() {
        val job = Job()
        val id = ActiveExecutionRegistry.register(1, "T", "Manual", job, 0)

        assertTrue(ActiveExecutionRegistry.cancel(id))
        assertTrue(job.isCancelled)
        assertTrue(ActiveExecutionRegistry.active.value.single().cancelling)
    }

    @Test
    fun cancellingAFinishedRunReportsFalseInsteadOfClaimingSuccess() {
        val id = ActiveExecutionRegistry.register(1, "T", "Manual", Job(), 0)
        ActiveExecutionRegistry.unregister(id)

        assertFalse(ActiveExecutionRegistry.cancel(id))
        assertFalse(ActiveExecutionRegistry.cancel(999))
    }

    @Test
    fun parallelRunsAreTrackedIndependently() {
        val first = ActiveExecutionRegistry.register(1, "A", "Profile: X", Job(), 0)
        val second = ActiveExecutionRegistry.register(2, "B", "Profile: Y", Job(), 0)

        assertEquals(listOf(first, second), ActiveExecutionRegistry.active.value.map { it.id })

        ActiveExecutionRegistry.cancel(first)
        assertEquals(
            listOf(true, false),
            ActiveExecutionRegistry.active.value.map { it.cancelling },
        )

        ActiveExecutionRegistry.unregister(first)
        assertEquals(listOf(second), ActiveExecutionRegistry.active.value.map { it.id })
    }

    @Test
    fun cancellationReachesWorkSuspendedInsideTheRun() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val deferred = async {
            val id = ActiveExecutionRegistry.register(
                taskId = 1,
                taskName = "Long",
                source = "Manual",
                job = coroutineContext[Job],
                startedAtMs = 0,
            )
            try {
                started.complete(Unit)
                // Stands in for a bounded blocking action (a long flow.wait, a hung request).
                awaitCancellation()
            } finally {
                ActiveExecutionRegistry.unregister(id)
            }
        }

        started.await()
        val id = ActiveExecutionRegistry.active.value.single().id
        assertTrue(ActiveExecutionRegistry.cancel(id))

        val error = runCatching { deferred.await() }.exceptionOrNull()
        assertTrue("the run must unwind, not keep going", error is kotlinx.coroutines.CancellationException)
        assertTrue(ActiveExecutionRegistry.active.value.isEmpty())
    }

    @Test
    fun aCancelledRunIsRecordedAsCancelledNotSkippedOrFailed() {
        val message = cancelledRunLogMessage(
            source = "Manual",
            reason = ActiveExecutionRegistry.CANCELLED_BY_USER,
        )
        val diagnostics = message.toRunLogDiagnostics()

        assertTrue(diagnostics.isCancelled)
        assertFalse("a cancelled run started; it was not skipped", diagnostics.isSkipped)
        assertEquals(ActiveExecutionRegistry.CANCELLED_BY_USER, diagnostics.reason)

        val entry = com.opentasker.core.model.RunLogEntry(
            taskId = 1,
            taskName = "T",
            timestamp = 0,
            durationMs = 0,
            success = false,
            message = message,
        )
        assertEquals(RunLogOutcome.Cancelled, entry.outcome())
    }

    @Test
    fun anOrdinaryFailureIsStillReportedAsFailed() {
        val entry = com.opentasker.core.model.RunLogEntry(
            taskId = 1,
            taskName = "T",
            timestamp = 0,
            durationMs = 0,
            success = false,
            message = runLogMessage(source = "Manual"),
        )
        assertEquals(RunLogOutcome.Failed, entry.outcome())
        assertNull(entry.message.toRunLogDiagnostics().decision)
    }
}
