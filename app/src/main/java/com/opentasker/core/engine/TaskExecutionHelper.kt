package com.opentasker.core.engine

import android.content.Context
import com.opentasker.core.capabilities.AutomationSensitivityRegistry
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.core.platform.AudioForegroundServiceEligibility
import com.opentasker.core.platform.AudioRuntimeEligibility
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.TaskEntity
import com.opentasker.core.storage.RuntimeVariableSeed
import com.opentasker.core.storage.RuntimeVariableValue
import com.opentasker.core.storage.VariableRepository
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
    visibleActivity: Boolean = false,
    audioForegroundService: AudioForegroundServiceEligibility = AudioForegroundServiceEligibility.NONE,
    logTag: String = TAG,
): TaskExecutionResult = withContext(Dispatchers.IO) {
    // Run the whole task off the caller's thread. Manual runs (ViewModel), widget/shortcut, and
    // notification-action paths call this from the main thread; without this hop, blocking actions
    // (HTTP, file, ping) would throw NetworkOnMainThreadException and fail silently.
    val variables = VariableStore()
    val variableRepository = VariableRepository(db.variableDao())
    val persistedGlobals = runCatching {
        variableRepository.runtimeGlobals()
    }.getOrElse { error ->
        AppLogger.error(logTag, "Failed to hydrate global variables", error)
        RuntimeVariableSeed(emptyMap(), emptySet(), emptySet())
    }
    variables.seedGlobals(persistedGlobals.values, persistedGlobals.secretNames)
    val persistedBaselineValues = variables.globalSnapshot()
    val persistedBaselineSecretNames = variables.globalSensitiveSnapshot()
    val persistedBaseline = RuntimeVariableSeed(
        values = persistedBaselineValues,
        secretNames = persistedBaselineSecretNames,
        unavailableSecretNames = persistedBaselineSecretNames - persistedBaselineValues.keys,
    )
    if (persistedGlobals.unavailableSecretNames.isNotEmpty()) {
        AppLogger.warn(
            logTag,
            "Secret variables require re-entry: ${persistedGlobals.unavailableSecretNames.sorted().joinToString()}",
        )
    }
    initialVariables.forEach { (name, value) -> variables.set(name, value) }
    // Baseline after seeding + event vars, so only globals actually changed during the run persist.
    val baselineGlobals = variables.globalSnapshot()
    val baselineSensitiveGlobals = variables.globalSensitiveSnapshot()
    val audioEligibility = AudioRuntimeEligibility(
        appVisible = visibleActivity,
        foregroundService = audioForegroundService,
    )
    val ctx = ActionContext(
        app = appContext,
        variables = variables,
        audioEligibility = audioEligibility,
    ) { msg -> AppLogger.info(logTag, msg) }
    val runner = TaskRunner(ctx, resolveTask = dbSubTaskResolver(db))
    val report = runner.run(task)
    val globalCommitMetadata = persistChangedGlobals(
        variableRepository,
        persistedBaseline,
        baselineGlobals,
        variables.globalSnapshot(),
        baselineSensitiveGlobals,
        variables.globalSensitiveSnapshot(),
        logTag,
    )
    AppLogger.info(logTag, "Task ${report.taskName} completed: ${report.success} (${report.durationMs}ms)")
    val classified = RunLogSource.classify(source)
    val riskMetadata = taskPowerRunLogMetadata(task)
    val logEntry = RunLogEntry(
        taskId = task.id,
        taskName = task.name,
        timestamp = report.startedAt,
        durationMs = report.durationMs,
        success = report.success,
        message = runLogMessage(
            source = source,
            metadata = riskMetadata + metadata + globalCommitMetadata,
            traces = report.traces,
        ),
        source = classified.key,
        sourceLabel = classified.label,
    )
    val inserted = insertRunLog(db, logEntry)
    TaskExecutionResult(report, inserted)
}

internal fun taskPowerRunLogMetadata(task: Task): List<String> {
    val riskPowers = AutomationSensitivityRegistry.summarize(task).powers
        .map { it.name.lowercase().replace('_', ' ') }
    return if (riskPowers.isEmpty()) emptyList() else listOf("Powers: ${riskPowers.joinToString()}")
}

/**
 * Globals whose value changed during a run (added or modified), relative to the run's baseline.
 * Deterministic and order-stable so parallel runs converge on a well-defined last-write-wins result
 * once each commits. Pure for testability.
 */
fun changedGlobals(
    before: Map<String, String>,
    after: Map<String, String>,
    beforeSensitive: Set<String> = emptySet(),
    afterSensitive: Set<String> = emptySet(),
): List<RuntimeVariableValue> =
    after.asSequence()
        .filter { (name, value) -> before[name] != value || (name in beforeSensitive) != (name in afterSensitive) }
        .sortedBy { it.key }
        .map { (name, value) -> RuntimeVariableValue(name, value, isSecret = name in afterSensitive) }
        .toList()

/**
 * Commits globals changed during the run to [com.opentasker.core.storage.VariableDao] before the
 * task's success is reported, so names containing uppercase letters and explicit `var.persist`
 * values survive across separate runs and process restarts. All-lowercase locals never reach this path.
 */
private suspend fun persistChangedGlobals(
    variableRepository: VariableRepository,
    persistedBaseline: RuntimeVariableSeed,
    before: Map<String, String>,
    after: Map<String, String>,
    beforeSensitive: Set<String>,
    afterSensitive: Set<String>,
    logTag: String,
): List<String> {
    val changed = changedGlobals(before, after, beforeSensitive, afterSensitive)
    if (changed.isEmpty()) return emptyList()
    val commit = runCatching {
        variableRepository.persistRuntimeAtomically(persistedBaseline, changed)
    }.getOrElse { error ->
        AppLogger.error(
            logTag,
            "Failed to persist ${changed.size} global variable change(s) atomically",
            error,
        )
        return listOf("Global commit failed: ${changed.map(RuntimeVariableValue::name).joinToString()}")
    }
    if (commit.conflictedNames.isEmpty()) return emptyList()
    val names = commit.conflictedNames.joinToString()
    AppLogger.warn(logTag, "Global commit preserved newer concurrent value(s): $names")
    return listOf("Global write conflict (newer value kept): $names")
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
