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
    private fun <T> executedValue(outcome: TaskCollisionOutcome<T>): T {
        assertTrue(outcome is TaskCollisionOutcome.Executed<*>)
        return (outcome as TaskCollisionOutcome.Executed<T>).value
    }
}
