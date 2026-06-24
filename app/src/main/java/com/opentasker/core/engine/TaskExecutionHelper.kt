package com.opentasker.core.engine

import android.content.Context
import android.util.Log
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
    eventLocals: Map<String, String> = emptyMap(),
    logTag: String = TAG,
): TaskExecutionResult {
    val variables = VariableStore(com.opentasker.core.engine.variables.PersistentGlobalScope, task.projectId)
    initialVariables.forEach { (name, value) -> variables.set(name, value) }
    // Force-local so this invocation's event snapshot shadows the (possibly since-overwritten) super-global.
    eventLocals.forEach { (name, value) -> variables.setLocal(name, value) }
    val ctx = ActionContext(appContext, variables) { msg -> Log.i(logTag, msg) }
    val runner = TaskRunner(ctx, resolveTask = dbSubTaskResolver(db))
    val report = runner.run(task)
    Log.i(logTag, "Task ${report.taskName} completed: ${report.success} (${report.durationMs}ms)")
    maybeQueueFreezeBubble(appContext, task, variables)
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

/**
 * If [task] is freeze-enabled, queue a re-freeze bubble for the app it launches/unfreezes. The package is
 * read from the task's `app.launch` (preferred) or `app.unfreeze` action, expanded against the run's
 * variables; an unresolved (`%var`-still-present) or blank package is skipped.
 */
private fun maybeQueueFreezeBubble(appContext: Context, task: Task, variables: VariableStore) {
    if (!task.freezeBubble) return
    val pkgRaw = task.actions.firstOrNull { it.type == "app.launch" }?.args?.get("package")
        ?: task.actions.firstOrNull { it.type == "app.unfreeze" }?.args?.get("package")
        ?: return
    val pkg = variables.expand(pkgRaw).trim()
    if (pkg.isEmpty() || pkg.contains('%')) return
    val label = runCatching {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: task.name
    com.opentasker.core.bubbles.FreezeBubbleStore.enqueue(pkg, label, task.iconPath)
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
        .onFailure { e -> Log.e(TAG, "Failed to write run log for task ${entry.taskId}", e) }
        .isSuccess

private const val TAG = "OpenTasker"
