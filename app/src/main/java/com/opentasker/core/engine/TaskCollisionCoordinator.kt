package com.opentasker.core.engine

import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface TaskCollisionOutcome<out T> {
    data class Executed<T>(val value: T) : TaskCollisionOutcome<T>
    data class Skipped(val reason: String) : TaskCollisionOutcome<Nothing>
}

/**
 * Applies a task's collision policy at the common execution boundary used by every top-level
 * source and by nested `task.run` actions. Profile re-trigger modes decide whether a profile emits
 * another invocation; this coordinator then applies the referenced task's global collision rule.
 */
class TaskCollisionCoordinator {
    private class Slot {
        val activeJobs = linkedSetOf<Job>()
        val waitMutex = Mutex()
    }

    private val slots = ConcurrentHashMap<Long, Slot>()

    suspend fun <T> execute(task: Task, block: suspend () -> T): TaskCollisionOutcome<T> {
        if (task.id <= 0L) return TaskCollisionOutcome.Executed(block())
        val job = currentCoroutineContext()[Job] ?: return TaskCollisionOutcome.Executed(block())
        val slot = slots.computeIfAbsent(task.id) { Slot() }

        return when (task.collisionMode) {
            CollisionMode.RUN_BOTH -> runRegistered(slot, job, block = block)
            CollisionMode.WAIT -> slot.waitMutex.withLock { runRegistered(slot, job, block = block) }
            CollisionMode.ABORT_NEW -> {
                val admitted = synchronized(slot) {
                    slot.activeJobs.removeAll { !it.isActive }
                    if (slot.activeJobs.isNotEmpty()) {
                        false
                    } else {
                        slot.activeJobs += job
                        true
                    }
                }
                if (!admitted) {
                    TaskCollisionOutcome.Skipped("Task is already running; Abort new skipped this invocation.")
                } else {
                    runRegistered(slot, job, alreadyRegistered = true, block = block)
                }
            }
            CollisionMode.ABORT_EXISTING -> {
                val previous = synchronized(slot) {
                    slot.activeJobs.removeAll { !it.isActive }
                    val active = slot.activeJobs.filterNot { it === job }
                    slot.activeJobs.clear()
                    slot.activeJobs += job
                    active
                }
                previous.forEach { activeJob ->
                    activeJob.cancel(CancellationException("Replaced by a newer run under Abort existing."))
                }
                runRegistered(slot, job, alreadyRegistered = true, block = block)
            }
        }
    }

    private suspend fun <T> runRegistered(
        slot: Slot,
        job: Job,
        alreadyRegistered: Boolean = false,
        block: suspend () -> T,
    ): TaskCollisionOutcome.Executed<T> {
        if (!alreadyRegistered) synchronized(slot) { slot.activeJobs += job }
        return try {
            TaskCollisionOutcome.Executed(block())
        } finally {
            synchronized(slot) { slot.activeJobs.remove(job) }
        }
    }

    companion object {
        val Default = TaskCollisionCoordinator()
    }
}
