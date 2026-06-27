package com.opentasker.core.engine

import android.content.Context
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.toEntity

data class TaskExecutionResult(
    val report: TaskRunReport,
    val logInserted: Boolean,
)

suspend fun executeAndLogTask(
    appContext: Context,
    db: AppDatabase,
    task: Task,
    source: String,
    metadata: List<String> = emptyList(),
    initialVariables: Map<String, String> = emptyMap(),
    logTag: String = TAG,
): TaskExecutionResult {
    val variables = VariableStore()
    initialVariables.forEach { (name, value) -> variables.set(name, value) }
    val ctx = ActionContext(appContext, variables) { msg -> AppLogger.info(logTag, msg) }
    val runner = TaskRunner(ctx, resolveTask = dbSubTaskResolver(db))
    val report = runner.run(task)
    AppLogger.info(logTag, "Task ${report.taskName} completed: ${report.success} (${report.durationMs}ms)")
    val classified = RunLogSource.classify(source)
    val logEntry = RunLogEntry(
        taskId = task.id,
        taskName = task.name,
        timestamp = report.startedAt,
        durationMs = report.durationMs,
        success = report.success,
        message = runLogMessage(
            source = source,
            metadata = metadata,
            traces = report.traces,
        ),
        source = classified.key,
        sourceLabel = classified.label,
    )
    val inserted = insertRunLog(db, logEntry)
    return TaskExecutionResult(report, inserted)
}

suspend fun logSkippedRun(
    db: AppDatabase,
    task: Task,
    source: String,
    reason: String,
    metadata: List<String> = emptyList(),
): Boolean {
    val classified = RunLogSource.classify(source)
    return insertRunLog(
        db,
        RunLogEntry(
            taskId = task.id,
            taskName = task.name,
            durationMs = 0,
            success = false,
            message = skippedRunLogMessage(
                source = source,
                reason = reason,
                metadata = metadata,
            ),
            source = classified.key,
            sourceLabel = classified.label,
        ),
    )
}

/**
 * Resolves a sub-task by numeric id first, then by exact name (case-insensitive), for `task.run`.
 */
fun dbSubTaskResolver(db: AppDatabase): SubTaskResolver = resolver@{ ref ->
    val byId = ref.toLongOrNull()?.let { db.taskDao().getById(it) }
    if (byId != null) return@resolver byId.toDomain()
    val exact = db.taskDao().getByName(ref)
    if (exact != null) return@resolver exact.toDomain()
    db.taskDao().getAll().firstOrNull { it.name.equals(ref, ignoreCase = true) }?.toDomain()
}

suspend fun insertRunLog(db: AppDatabase, entry: RunLogEntry): Boolean =
    runCatching { db.runLogDao().insert(entry.toEntity()) }
        .onFailure { e -> AppLogger.error(TAG, "Failed to write run log for task ${entry.taskId}", e) }
        .isSuccess

private const val TAG = "OpenTasker"
