package com.opentasker.core.engine

import android.content.Context
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.TaskEntity
import com.opentasker.core.storage.VariableEntity
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
): TaskExecutionResult = withContext(Dispatchers.IO) {
    // Run the whole task off the caller's thread. Manual runs (ViewModel), widget/shortcut, and
    // notification-action paths call this from the main thread; without this hop, blocking actions
    // (HTTP, file, ping) would throw NetworkOnMainThreadException and fail silently.
    val variables = VariableStore()
    val persistedGlobals = runCatching {
        db.variableDao().getAllGlobal().associate { it.name to it.value }
    }.getOrElse { error ->
        AppLogger.error(logTag, "Failed to hydrate global variables", error)
        emptyMap()
    }
    variables.seedGlobals(persistedGlobals)
    initialVariables.forEach { (name, value) -> variables.set(name, value) }
    // Baseline after seeding + event vars, so only globals actually changed during the run persist.
    val baselineGlobals = variables.globalSnapshot()
    val ctx = ActionContext(appContext, variables) { msg -> AppLogger.info(logTag, msg) }
    val runner = TaskRunner(ctx, resolveTask = dbSubTaskResolver(db))
    val report = runner.run(task)
    persistChangedGlobals(db, baselineGlobals, variables.globalSnapshot(), logTag)
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
    TaskExecutionResult(report, inserted)
}

/**
 * Globals whose value changed during a run (added or modified), relative to the run's baseline.
 * Deterministic and order-stable so parallel runs converge on a well-defined last-write-wins result
 * once each commits. Pure for testability.
 */
fun changedGlobals(before: Map<String, String>, after: Map<String, String>): List<VariableEntity> =
    after.asSequence()
        .filter { (name, value) -> before[name] != value }
        .sortedBy { it.key }
        .map { (name, value) -> VariableEntity(name = name, value = value, isGlobal = true) }
        .toList()

/**
 * Commits globals changed during the run to [com.opentasker.core.storage.VariableDao] before the
 * task's success is reported, so `%UPPERCASE` globals and explicit `var.persist` values survive
 * across separate runs and process restarts. Local (lowercase) variables never reach this path.
 */
private suspend fun persistChangedGlobals(
    db: AppDatabase,
    before: Map<String, String>,
    after: Map<String, String>,
    logTag: String,
) {
    for (entity in changedGlobals(before, after)) {
        runCatching { db.variableDao().insert(entity) }
            .onFailure { AppLogger.error(logTag, "Failed to persist global ${entity.name}", it) }
    }
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
 * Corrupt tasks (whose stored payload fails to decode) resolve to `null` so `task.run` fails
 * closed instead of silently running an empty action list.
 */
fun dbSubTaskResolver(db: AppDatabase): SubTaskResolver = resolver@{ ref ->
    fun TaskEntity.decodedOrNull(): Task? {
        val result = toDomainDecodeResult()
        if (result.issue != null) {
            AppLogger.error(TAG, "Sub-task '$ref' (id=$id) is corrupt: ${result.issue.message}")
            return null
        }
        return result.value
    }
    val byId = ref.toLongOrNull()?.let { db.taskDao().getById(it) }
    if (byId != null) return@resolver byId.decodedOrNull()
    val exact = db.taskDao().getByName(ref)
    if (exact != null) return@resolver exact.decodedOrNull()
    db.taskDao().getAll().firstOrNull { it.name.equals(ref, ignoreCase = true) }?.decodedOrNull()
}

suspend fun insertRunLog(db: AppDatabase, entry: RunLogEntry): Boolean =
    runCatching { db.runLogDao().insert(entry.toEntity()) }
        .onFailure { e -> AppLogger.error(TAG, "Failed to write run log for task ${entry.taskId}", e) }
        .isSuccess

private const val TAG = "OpenTasker"
