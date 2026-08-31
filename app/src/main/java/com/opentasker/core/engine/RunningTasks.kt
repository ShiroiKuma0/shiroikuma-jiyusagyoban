package com.opentasker.core.engine

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Every task run that is in flight right now, across all sources.
 *
 * [executeAndLogTask] is the single funnel through which the engine, manual runs, widgets, scenes,
 * bubbles, notification buttons, the dev bridge and sister-app intents all execute a task — so
 * registering there catches every run without touching the call sites. Read by
 * [RuntimeInventory] (the "Live now" list and the shutdown report) and by the shutdown teardown,
 * which cancels whatever is still going.
 */
object RunningTasks {

    /** One in-flight run. [runId] is unique per process, so two runs of the same task stay distinct. */
    data class Run(
        val runId: Long,
        val taskId: Long,
        val name: String,
        val source: String,
        val startedAt: Long,
    )

    private class Entry(val run: Run, val job: Job?)

    private val counter = AtomicLong(0)
    private val active = LinkedHashMap<Long, Entry>()

    /** In-flight runs, oldest first — a long-running one sorts to the top, which is what you want to see. */
    fun snapshot(): List<Run> = synchronized(active) { active.values.map { it.run } }

    fun count(): Int = synchronized(active) { active.size }

    /**
     * Register [block] as an in-flight run for its duration. The cancelling handle is the *caller's*
     * coroutine job, so [cancel]/[cancelAll] abort the run where it stands (mid-action), which is what
     * a shutdown needs — a task waiting 30 minutes in `flow.wait` must not hold the exit open.
     */
    suspend fun <T> track(taskId: Long, name: String, source: String, block: suspend () -> T): T {
        val runId = counter.incrementAndGet()
        val entry = Entry(
            Run(runId, taskId, name.ifBlank { "task #$taskId" }, source, System.currentTimeMillis()),
            currentCoroutineContext()[Job],
        )
        synchronized(active) { active[runId] = entry }
        return try {
            block()
        } finally {
            synchronized(active) { active.remove(runId) }
        }
    }

    /** Abort one run. The entry is removed by the run's own `finally`, not here. */
    fun cancel(runId: Long) {
        val entry = synchronized(active) { active[runId] } ?: return
        entry.job?.cancel()
    }

    /** Abort every in-flight run; returns how many were told to stop. */
    fun cancelAll(): Int {
        val entries = synchronized(active) { active.values.toList() }
        entries.forEach { it.job?.cancel() }
        return entries.size
    }
}
