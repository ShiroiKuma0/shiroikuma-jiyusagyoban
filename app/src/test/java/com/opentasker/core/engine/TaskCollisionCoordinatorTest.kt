package com.opentasker.core.engine

import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCollisionCoordinatorTest {
    @Test
    fun abortNewKeepsTheActiveRunAndSkipsTheNewRequest() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val task = task(CollisionMode.ABORT_NEW)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            coordinator.execute(task) {
                started.complete(Unit)
                release.await()
                "first"
            }
        }
        started.await()

        val second = coordinator.execute(task) { "second" }

        assertTrue(second is TaskCollisionOutcome.Skipped)
        release.complete(Unit)
        assertEquals("first", executedValue(first.await()))
    }

    @Test
    fun abortExistingCancelsTheActiveRunBeforeAdmittingTheReplacement() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val task = task(CollisionMode.ABORT_EXISTING)
        val started = CompletableDeferred<Unit>()
        val first = async {
            coordinator.execute(task) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        val second = coordinator.execute(task) { "replacement" }

        assertEquals("replacement", executedValue(second))
        withTimeout(2_000) { first.cancelAndJoin() }
        assertTrue(first.isCancelled)
    }

    @Test
    fun waitSerializesRequestsWhileRunBothAllowsOverlap() = runBlocking {
        val coordinator = TaskCollisionCoordinator()
        val waitTask = task(CollisionMode.WAIT)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val first = async {
            coordinator.execute(waitTask) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                "first"
            }
        }
        firstStarted.await()
        val second = async {
            coordinator.execute(waitTask) {
                secondStarted.complete(Unit)
                "second"
            }
        }
        yield()
        assertFalse(secondStarted.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
        assertEquals("second", executedValue(second.await()))

        val runBothTask = task(CollisionMode.RUN_BOTH)
        val overlapping = CompletableDeferred<Unit>()
        val releaseOverlap = CompletableDeferred<Unit>()
        val active = async {
            coordinator.execute(runBothTask) {
                overlapping.complete(Unit)
                releaseOverlap.await()
            }
        }
        overlapping.await()
        assertEquals("overlap", executedValue(coordinator.execute(runBothTask) { "overlap" }))
        releaseOverlap.complete(Unit)
        active.await()
        Unit
    }

    private fun task(mode: CollisionMode) = Task(id = 42, name = "Collision", collisionMode = mode)

    @Suppress("UNCHECKED_CAST")
    /**
     * Two WAIT-mode tasks that sub-run each other acquire their mutexes in opposite orders. Without
     * a bound on acquisition this is a classic AB-BA deadlock, and each stuck run held an admission
     * lease until the engine's global cap wedged.
     */
    @Test
    fun crossRecursiveWaitTasksDoNotDeadlock() = runBlocking {
        val coordinator = TaskCollisionCoordinator(waitAcquisitionTimeoutMs = 250L)
        val outer = task(CollisionMode.WAIT).copy(id = 1)
        val inner = task(CollisionMode.WAIT).copy(id = 2)
        val bothHoldTheirFirstLock = CompletableDeferred<Unit>()
        val holders = java.util.concurrent.atomic.AtomicInteger()

        suspend fun chain(first: Task, second: Task): String =
            executedValue(
                coordinator.execute(first) {
                    if (holders.incrementAndGet() == 2) bothHoldTheirFirstLock.complete(Unit)
                    bothHoldTheirFirstLock.await()
                    when (val nested = coordinator.execute(second) { "nested" }) {
                        is TaskCollisionOutcome.Executed -> nested.value
                        is TaskCollisionOutcome.Skipped -> "skipped"
                    }
                },
            )

        // The whole interleave must finish well inside this bound; before the fix it never returned.
        val results = withTimeout(10_000L) {
            val a = async { chain(outer, inner) }
            val b = async { chain(inner, outer) }
            listOf(a.await(), b.await())
        }

        assertTrue("at least one side must give up rather than block forever", results.contains("skipped"))
    }

    private fun <T> executedValue(outcome: TaskCollisionOutcome<T>): T {
        assertTrue(outcome is TaskCollisionOutcome.Executed<*>)
        return (outcome as TaskCollisionOutcome.Executed<T>).value
    }
}
