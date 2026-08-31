package com.opentasker.core.engine

import android.content.Context
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.contexts.NotificationTriggerService
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.storage.ShutdownSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Exit app fully" — the ordered teardown behind the top-bar overflow item.
 *
 * Two halves on purpose. [prepare] runs the user's own run-on-exit tasks and then takes stock of what
 * is *still* live; [finish] does the forced teardown and sets the stop flag. The report is shown in
 * between, because a dialog raised after the app has gone can't be read: anything [prepare] finds is
 * something that should already have been down, which is exactly the thing worth seeing before it is
 * swept away.
 */
object AppShutdown {

    /** How long a single run-on-exit task may take before the shutdown stops waiting for it. */
    private const val EXIT_TASK_TIMEOUT_MS = 30_000L
    private const val TAG = "AppShutdown"

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Report(
        /** Run-on-exit tasks that completed within the timeout. */
        val ran: List<String> = emptyList(),
        /** Run-on-exit tasks still going after the timeout — deliberately NOT cancelled, so they show up below. */
        val timedOut: List<String> = emptyList(),
        /** Configured task ids that no longer resolve — a stale "Run on exit" entry. */
        val missing: List<Long> = emptyList(),
        /** Everything still live after the exit tasks. Should be empty; each entry is a leak. */
        val leftovers: List<RuntimeInventory.LiveItem> = emptyList(),
    ) {
        val clean: Boolean get() = leftovers.isEmpty() && timedOut.isEmpty() && missing.isEmpty()
    }

    /**
     * Run the configured run-on-exit tasks in order, then report what survived. Nothing is torn down
     * here and the stop flag is not set — call [finish] to commit, or simply walk away to cancel.
     */
    suspend fun prepare(context: Context): Report {
        val app = context.applicationContext
        val db = OpenTaskerApp_NoHilt.db
        val ran = mutableListOf<String>()
        val timedOut = mutableListOf<String>()
        val missing = mutableListOf<Long>()

        for (id in ShutdownSettings.taskIds(app)) {
            val task = runCatching { db.taskDao().getById(id)?.toDomain() }.getOrNull()
            if (task == null) {
                missing += id
                continue
            }
            // Each task runs on an independent job so a timeout ABANDONS the wait without killing the
            // run: a task that hangs then appears in the leftovers below, which is the useful outcome.
            val job = io.launch {
                runCatching { executeAndLogTask(app, db, task, source = "Shutdown") }
                    .onFailure { AppLogger.warn(TAG, "Run-on-exit task “${task.name}” failed: ${it.message}") }
            }
            if (withTimeoutOrNull(EXIT_TASK_TIMEOUT_MS) { job.join() } == null) {
                timedOut += task.name
            } else {
                ran += task.name
            }
        }

        return Report(
            ran = ran,
            timedOut = timedOut,
            missing = missing,
            leftovers = RuntimeInventory.leftovers(app),
        )
    }

    /**
     * Commit the shutdown: force everything down, record the report where it survives the dialog, and
     * set the stop flag so no alarm, tile, widget or sister-app intent brings the app back by itself.
     *
     * The flag is set LAST: if the teardown throws, the app is left running rather than half-stopped
     * with nothing able to restart it.
     */
    fun finish(context: Context, report: Report) {
        val app = context.applicationContext
        logReport(app, report)
        runCatching { RuntimeInventory.teardown(app) }
            .onFailure { AppLogger.error(TAG, "Teardown failed during shutdown", it) }
        // Only the shutdown lets the notification listener go — an engine restart keeps it bound.
        NotificationTriggerService.unbindForShutdown()
        EngineShutdown.markStopped(app)
    }

    /**
     * Restart the engine: the same teardown, without the stop flag, then a fresh start. Day to day this
     * is the more useful of the pair — it re-establishes the whole context layer after a bad state
     * without the trip through exit-and-reopen.
     */
    fun restartEngine(context: Context) {
        val app = context.applicationContext
        runCatching { RuntimeInventory.teardown(app) }
            .onFailure { AppLogger.error(TAG, "Teardown failed during engine restart", it) }
        EngineShutdown.clear(app)
        AutomationService.start(app)
    }

    private fun logReport(context: Context, report: Report) {
        val message = buildString {
            append("Run on exit: ")
            append(if (report.ran.isEmpty()) "none completed" else report.ran.joinToString())
            if (report.timedOut.isNotEmpty()) {
                append(" · still running after ${EXIT_TASK_TIMEOUT_MS / 1000}s: ${report.timedOut.joinToString()}")
            }
            if (report.missing.isNotEmpty()) {
                append(" · configured but missing: ${report.missing.joinToString()}")
            }
            append(" · Left running: ")
            append(RuntimeInventory.describe(report.leftovers))
        }
        AppLogger.info(TAG, message)
        io.launch {
            runCatching {
                insertRunLog(
                    OpenTaskerApp_NoHilt.db,
                    RunLogEntry(
                        taskId = 0,
                        taskName = "終了 — app shut down",
                        timestamp = System.currentTimeMillis(),
                        durationMs = 0,
                        success = report.clean,
                        message = message,
                        source = "system",
                        sourceLabel = "Shutdown",
                    ),
                )
            }.onFailure { AppLogger.warn(TAG, "Could not write the shutdown report: ${it.message}") }
        }
    }
}
