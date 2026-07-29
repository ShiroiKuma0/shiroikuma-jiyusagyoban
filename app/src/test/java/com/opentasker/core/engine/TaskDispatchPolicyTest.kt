package com.opentasker.core.engine

import com.opentasker.core.model.AutomationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral coverage for the automation-mode dispatch rules. These are the concurrency decisions
 * that decide whether a re-trigger runs, waits, preempts, or is dropped, and they previously had no
 * direct test at all.
 */
class TaskDispatchPolicyTest {

    private fun plan(
        mode: AutomationMode,
        isExit: Boolean = false,
        slotActive: Boolean = false,
        queuedCount: Int = 0,
        queueCap: Int = 8,
    ) = TaskDispatchPolicy.plan(mode, isExit, slotActive, queuedCount, queueCap)

    @Test
    fun singleSuppressesARetriggerWhileTheProfileIsStillRunning() {
        assertEquals(DispatchStep.START, plan(AutomationMode.SINGLE).step)
        val suppressed = plan(AutomationMode.SINGLE, slotActive = true)
        assertEquals(DispatchStep.SKIP_ALREADY_RUNNING, suppressed.step)
        assertFalse(suppressed.startsRun)
        // A suppressed retrigger must not burn the cooldown window; otherwise the next legitimate
        // trigger after the run finishes would be dropped as well.
        assertFalse(suppressed.reservesCooldown)
    }

    @Test
    fun restartPreemptsTheRunningJobEveryTime() {
        assertEquals(DispatchStep.RESTART, plan(AutomationMode.RESTART).step)
        assertEquals(DispatchStep.RESTART, plan(AutomationMode.RESTART, slotActive = true).step)
        assertTrue(plan(AutomationMode.RESTART, slotActive = true).reservesCooldown)
    }

    @Test
    fun queuedStartsWhenIdleAndQueuesBehindARunningTask() {
        val idle = plan(AutomationMode.QUEUED)
        assertEquals(DispatchStep.START_QUEUE, idle.step)
        assertTrue(idle.reservesCooldown)

        val queued = plan(AutomationMode.QUEUED, slotActive = true, queuedCount = 3)
        assertEquals(DispatchStep.ENQUEUE, queued.step)
        assertFalse(queued.startsRun)
        // Queuing behind a running task must not reserve cooldown: reserving at enqueue time
        // dropped a later distinct trigger as "cooldown active" that should have queued.
        assertFalse(queued.reservesCooldown)
    }

    @Test
    fun queuedDropsTheRetriggerOnceTheQueueIsAtItsCap() {
        val atCap = plan(AutomationMode.QUEUED, slotActive = true, queuedCount = 8, queueCap = 8)
        assertEquals(DispatchStep.SKIP_QUEUE_FULL, atCap.step)
        assertFalse(atCap.reservesCooldown)

        val overCap = plan(AutomationMode.QUEUED, slotActive = true, queuedCount = 9, queueCap = 8)
        assertEquals(DispatchStep.SKIP_QUEUE_FULL, overCap.step)

        val underCap = plan(AutomationMode.QUEUED, slotActive = true, queuedCount = 7, queueCap = 8)
        assertEquals(DispatchStep.ENQUEUE, underCap.step)
    }

    @Test
    fun parallelAlwaysLaunchesAlongsideTheRunningJob() {
        assertEquals(DispatchStep.LAUNCH_PARALLEL, plan(AutomationMode.PARALLEL).step)
        assertEquals(DispatchStep.LAUNCH_PARALLEL, plan(AutomationMode.PARALLEL, slotActive = true).step)
    }

    @Test
    fun noExitTaskEverConsumesTheProfileCooldown() {
        AutomationMode.entries.forEach { mode ->
            listOf(false, true).forEach { slotActive ->
                val exit = plan(mode, isExit = true, slotActive = slotActive)
                assertFalse(
                    "$mode exit dispatch (slotActive=$slotActive) must not reserve cooldown",
                    exit.reservesCooldown,
                )
            }
        }
    }

    @Test
    fun everyEnterDispatchThatStartsARunReservesCooldown() {
        AutomationMode.entries.forEach { mode ->
            val enter = plan(mode)
            assertEquals(
                "$mode enter dispatch cooldown reservation must follow whether it starts a run",
                enter.startsRun,
                enter.reservesCooldown,
            )
        }
    }
}
