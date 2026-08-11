package com.opentasker.core.engine

import com.opentasker.core.model.AutomationMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slot registry exists to make "decide, then store" atomic. These tests drive the same
 * sequence `AutomationService.dispatchTask` runs, from many threads at once, and assert the
 * outcomes the service depends on.
 */
class ProfileTaskSlotsTest {

    private val slotKey = 42L

    /** Mirrors the service's decide-and-store, minus the coroutine launch. */
    private fun ProfileTaskSlots.dispatch(mode: AutomationMode, started: AtomicInteger): DispatchStep =
        exclusively { slots ->
            val plan = TaskDispatchPolicy.plan(
                mode = mode,
                isExit = false,
                slotActive = slots.isActive(slotKey),
            )
            when (plan.step) {
                DispatchStep.START -> {
                    started.incrementAndGet()
                    slots.store(slotKey, Job())
                }
                DispatchStep.RESTART -> {
                    slots.cancel(slotKey)
                    started.incrementAndGet()
                    slots.store(slotKey, Job())
                }
                else -> Unit
            }
            plan.step
        }

    private fun race(threads: Int, action: () -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                try {
                    action()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("threads did not finish — suspected deadlock", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test
    fun singleModeStartsExactlyOnceUnderConcurrentDispatch() {
        val slots = ProfileTaskSlots()
        val started = AtomicInteger()
        val skipped = AtomicInteger()

        race(threads = 16) {
            if (slots.dispatch(AutomationMode.SINGLE, started) == DispatchStep.SKIP_ALREADY_RUNNING) {
                skipped.incrementAndGet()
            }
        }

        assertEquals("SINGLE must start exactly one run for a busy slot", 1, started.get())
        assertEquals(15, skipped.get())
        assertEquals(1, slots.snapshot().size)
    }

    @Test
    fun restartLeavesNoOrphanedJob() {
        val slots = ProfileTaskSlots()
        val started = AtomicInteger()

        race(threads = 16) { slots.dispatch(AutomationMode.RESTART, started) }

        assertEquals(16, started.get())
        // Every superseded job must have been cancelled; only the last one survives, tracked.
        val remaining = slots.snapshot()
        assertEquals(1, remaining.size)
        assertTrue("the surviving job must still be tracked and active", remaining.single().isActive)
    }

    @Test
    fun releaseIfCurrentClearsOnlyTheRecordedJob() {
        val slots = ProfileTaskSlots()
        val first = Job()
        val second = Job()
        slots.exclusively { it.store(slotKey, first) }

        // A job that lost its slot to a RESTART must not clear its successor on the way out.
        slots.releaseIfCurrent(slotKey, second)
        assertEquals(1, slots.snapshot().size)

        slots.releaseIfCurrent(slotKey, first)
        assertTrue(slots.snapshot().isEmpty())
    }

    @Test
    fun completedJobDoesNotKeepTheSlotBusy() {
        val slots = ProfileTaskSlots()
        val finished = Job().apply { complete() }
        slots.exclusively { it.store(slotKey, finished) }

        assertFalse(slots.exclusively { it.isActive(slotKey) })

        val started = AtomicInteger()
        assertEquals(DispatchStep.START, slots.dispatch(AutomationMode.SINGLE, started))
        assertEquals(1, started.get())
    }

    @Test
    fun snapshotIsStableWhileDispatchesRace() {
        val slots = ProfileTaskSlots()
        val started = AtomicInteger()

        race(threads = 8) {
            repeat(50) {
                slots.dispatch(AutomationMode.RESTART, started)
                // Shutdown reads the same lock; it must never see a torn map.
                slots.snapshot().forEach { job -> job.isActive }
            }
        }

        assertEquals(1, slots.snapshot().size)
    }
}
