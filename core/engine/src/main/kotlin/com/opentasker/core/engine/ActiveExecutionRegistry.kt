package com.opentasker.core.engine

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** A task run that is in flight right now. */
data class ActiveExecution(
    val id: Long,
    val taskId: Long,
    val taskName: String,
    /** Human-readable origin: "Profile: Home", "Manual", "Notification action", "External intent". */
    val source: String,
    val startedAtMs: Long,
    val stepIndex: Int = 0,
    val stepLabel: String? = null,
    val cancelling: Boolean = false,
    val executionId: String = id.toString(),
    val parentExecutionId: String? = null,
    val producer: String = ExecutionProducer.OTHER.wireValue,
)

/**
 * Process-wide view of what the engine is running right now, and the only way to stop it.
 *
 * The service tracked its jobs privately while the UI showed only completed run logs, so a runaway
 * automation — a long `flow.wait`, a hung request, an accidental loop — was invisible and
 * unstoppable short of force-stopping the app.
 *
 * Cancellation is cooperative: cancelling the run's [Job] unwinds the whole coroutine tree,
 * including nested `task.run` sub-tasks and any bounded blocking action suspended inside it, and
 * the executor records a terminal Cancelled outcome in the run log.
 */
object ActiveExecutionRegistry {
    private val nextId = AtomicLong(1)
    private val executions = MutableStateFlow<List<ActiveExecution>>(emptyList())
    private val jobs = mutableMapOf<Long, Job>()

    val active: StateFlow<List<ActiveExecution>> = executions.asStateFlow()

    fun register(
        taskId: Long,
        taskName: String,
        source: String,
        job: Job?,
        startedAtMs: Long,
        executionId: String? = null,
        parentExecutionId: String? = null,
        producer: String? = null,
    ): Long {
        val id = nextId.getAndIncrement()
        synchronized(jobs) {
            if (job != null) jobs[id] = job
            executions.value = executions.value + ActiveExecution(
                id = id,
                taskId = taskId,
                taskName = taskName,
                source = source,
                startedAtMs = startedAtMs,
                executionId = executionId ?: id.toString(),
                parentExecutionId = parentExecutionId,
                producer = producer ?: ExecutionProducer.fromSource(source).wireValue,
            )
        }
        return id
    }

    fun reportStep(id: Long, stepIndex: Int, stepLabel: String) = synchronized(jobs) {
        executions.value = executions.value.map { execution ->
            if (execution.id == id) execution.copy(stepIndex = stepIndex, stepLabel = stepLabel) else execution
        }
    }

    fun unregister(id: Long) = synchronized(jobs) {
        jobs.remove(id)
        executions.value = executions.value.filterNot { it.id == id }
    }

    /**
     * Requests cancellation. Returns false when the execution has already finished, so the caller
     * can say so instead of reporting a cancellation that never happened.
     */
    fun cancel(id: Long): Boolean = synchronized(jobs) {
        val job = jobs[id] ?: return false
        executions.value = executions.value.map { execution ->
            if (execution.id == id) execution.copy(cancelling = true) else execution
        }
        job.cancel(java.util.concurrent.CancellationException(CANCELLED_BY_USER))
        true
    }

    /** Test seam: drops all tracked state without touching the jobs. */
    // Public because the registry's tests live with the run-log helpers they assert against,
    // which are still app-side. core:engine is a module now, so internal no longer reaches them.
    fun reset() = synchronized(jobs) {
        jobs.clear()
        executions.value = emptyList()
    }

    const val CANCELLED_BY_USER = "Cancelled by user"
}
