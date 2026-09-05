package com.opentasker.core.engine

import android.content.Context
import com.opentasker.core.capabilities.AutomationSensitivityRegistry
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.storage.FallbackTaskSettings
import com.opentasker.core.model.Task
import com.opentasker.core.platform.AudioForegroundServiceEligibility
import com.opentasker.core.platform.AudioRuntimeEligibility
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.TaskEntity
import com.opentasker.core.storage.RuntimeVariableSeed
import com.opentasker.core.storage.RuntimeVariableValue
import com.opentasker.core.storage.VariableRepository
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

data class TaskExecutionResult(
    val report: TaskRunReport,
    val logInserted: Boolean,
    val skippedReason: String? = null,
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
    eventLocals: Map<String, String> = emptyMap(),
    logTag: String = TAG,
    admissionController: ExecutionAdmissionController = ExecutionAdmissionController.Default,
    profileId: Long? = null,
    /** The profile's own active/burst overrides, when it sets any; null falls back to the global limits. */
    profileLimits: ExecutionAdmissionProfileLimits? = null,
    /** Whether a rejected run leaves a visible run-log entry (LOG) or is dropped quietly (SILENT). */
    overflowPolicy: ProfileOverflowPolicy = ProfileOverflowPolicy.LOG,
    /** The profile's own recovery task, tried before the global one. */
    profileFallbackTaskId: Long? = null,
    /**
     * False inside a fallback run, so a failing recovery task cannot trigger its own recovery. One
     * level is the whole contract — recursion here would turn one broken task into a loop.
     */
    allowFallback: Boolean = true,
): TaskExecutionResult = withContext(Dispatchers.IO) {
    val admission = admissionController.tryAcquire(profileId, profileLimits)
    if (!admission.accepted) {
        val reason = admission.reason ?: "Execution admission rejected this run."
        // SILENT is for a profile that is *expected* to overflow — a fast pulse whose rejected runs
        // are noise rather than news. It suppresses the row, not the rejection itself.
        val inserted = if (overflowPolicy == ProfileOverflowPolicy.SILENT) {
            false
        } else {
            // A HELD row rather than a plain skipped one: it carries enough of the trigger to run the
            // work later, so a run refused by a burst limit is a postponement the user can act on
            // instead of a loss they can only read about.
            logHeldRun(
                db = db,
                task = task,
                source = source,
                reason = reason,
                metadata = metadata,
                profileId = profileId,
                initialVariables = initialVariables + eventLocals,
                policy = admission.rejection?.kind?.name ?: "ADMISSION",
            )
        }
        return@withContext TaskExecutionResult(
            report = collisionSkippedReport(task, reason),
            logInserted = inserted,
            skippedReason = reason,
        )
    }
    val admissionLease = requireNotNull(admission.lease)
    try {
    // Run the whole task off the caller's thread. Manual runs (ViewModel), widget/shortcut, and
    // notification-action paths call this from the main thread; without this hop, blocking actions
    // (HTTP, file, ping) would throw NetworkOnMainThreadException and fail silently.
    //
    // Fork: globals are durable through the DB-backed PersistentGlobalScope (every global set commits
    // live, per project bucket), so upstream's hydrate/seed + post-run snapshot-commit machinery is
    // not used — a whole-namespace snapshot commit would collapse the per-project buckets.
    val variables = VariableStore(com.opentasker.core.engine.variables.PersistentGlobalScope, task.projectId)
    initialVariables.forEach { (name, value) -> variables.set(name, value) }
    // Force-local so this invocation's event snapshot shadows the (possibly since-overwritten) super-global.
    eventLocals.forEach { (name, value) -> variables.setLocal(name, value) }
    val audioEligibility = AudioRuntimeEligibility(
        appVisible = visibleActivity,
        foregroundService = audioForegroundService,
    )
    val ctx = ActionContext(
        app = appContext,
        variables = variables,
        audioEligibility = audioEligibility,
    ) { msg -> AppLogger.info(logTag, msg) }
    val collisionCoordinator = TaskCollisionCoordinator.Default
    var executionId: Long? = null
    val collisionOutcome = try {
        collisionCoordinator.execute(task) {
            // Publish only admitted work. A WAIT invocation is not shown as active until it owns
            // the task slot, and ABORT_NEW never flashes a run that will immediately be skipped.
            val admittedExecutionId = ActiveExecutionRegistry.register(
                taskId = task.id,
                taskName = task.name,
                source = source,
                job = currentCoroutineContext()[Job],
                startedAtMs = System.currentTimeMillis(),
            )
            executionId = admittedExecutionId
            try {
                val runner = TaskRunner(
                    ctx,
                    resolveTask = dbSubTaskResolver(db),
                    onStep = { index, label -> ActiveExecutionRegistry.reportStep(admittedExecutionId, index, label) },
                    collisionCoordinator = collisionCoordinator,
                    executionChain = setOf(task.id).filterTo(linkedSetOf()) { it > 0L },
                    projectNameResolver = { pid -> pid?.let { db.projectDao().getById(it)?.name } },
                )
                // Fork: RunningTasks is the funnel the Monitor's "Live now" list and the shutdown
                // report read, so every admitted run registers in both inventories.
                RunningTasks.track(task.id, task.name, source) { runner.run(task) }
            } finally {
                ActiveExecutionRegistry.unregister(admittedExecutionId)
            }
        }
    } catch (cancellation: CancellationException) {
        // withContext(NonCancellable): the surrounding scope is already cancelled, so an ordinary
        // suspending write here would be dropped and the run would vanish without a trace.
        withContext(NonCancellable) {
            executionId?.let(ActiveExecutionRegistry::unregister)
            insertRunLog(
                db,
                RunLogEntry(
                    taskId = task.id,
                    taskName = task.name,
                    timestamp = System.currentTimeMillis(),
                    durationMs = 0,
                    success = false,
                    message = cancelledRunLogMessage(
                        source = source,
                        reason = cancellation.message ?: ActiveExecutionRegistry.CANCELLED_BY_USER,
                        metadata = metadata,
                    ),
                    source = RunLogSource.classify(source).key,
                    sourceLabel = RunLogSource.classify(source).label,
                ),
            )
        }
        throw cancellation
    }
    val report = when (collisionOutcome) {
        is TaskCollisionOutcome.Executed -> collisionOutcome.value
        is TaskCollisionOutcome.Skipped -> {
            val inserted = logSkippedRun(
                db = db,
                task = task,
                source = source,
                reason = collisionOutcome.reason,
                metadata = metadata,
            )
            return@withContext TaskExecutionResult(
                report = collisionSkippedReport(task, collisionOutcome.reason),
                logInserted = inserted,
                skippedReason = collisionOutcome.reason,
            )
        }
    }
    AppLogger.info(logTag, "Task ${report.taskName} completed: ${report.success} (${report.durationMs}ms)")
    maybeQueueFreezeBubble(appContext, task, variables)
    // A run that failed with a nameable error gets one recovery attempt. The lease is released first:
    // a profile capped at one active run would otherwise refuse the very task meant to diagnose it.
    val fallback = if (allowFallback && !report.success && report.structuredError != null) {
        admissionLease.release()
        runFallbackTask(
            appContext = appContext,
            db = db,
            failedTask = task,
            error = requireNotNull(report.structuredError),
            profileFallbackTaskId = profileFallbackTaskId,
            admissionController = admissionController,
            profileId = profileId,
            profileLimits = profileLimits,
            overflowPolicy = overflowPolicy,
            visibleActivity = visibleActivity,
            audioForegroundService = audioForegroundService,
            logTag = logTag,
        )
    } else {
        null
    }
    val classified = RunLogSource.classify(source)
    val riskMetadata = taskPowerRunLogMetadata(task)
    val fallbackMetadata = fallback?.let {
        listOf(
            "Fallback task: ${it.taskName} (${it.source})",
            "Fallback result: ${if (it.success) "succeeded" else "failed"}",
        ) + (it.reason?.let { reason -> listOf("Fallback reason: ${reason.take(256)}") } ?: emptyList())
    } ?: emptyList()
    val logEntry = RunLogEntry(
        taskId = task.id,
        taskName = task.name,
        timestamp = report.startedAt,
        durationMs = report.durationMs,
        success = report.success,
        message = runLogMessage(
            source = source,
            metadata = riskMetadata + metadata + fallbackMetadata,
            traces = report.traces,
        ),
        source = classified.key,
        sourceLabel = classified.label,
    )
    val inserted = insertRunLog(db, logEntry)
    TaskExecutionResult(report, inserted)
    } finally {
        admissionLease.release()
    }
}

/** What a recovery attempt did, folded into the failed run's own log entry. */
private data class FallbackExecutionResult(
    val taskId: Long,
    val taskName: String,
    val source: String,
    val success: Boolean,
    val reason: String? = null,
)

private data class FallbackTaskSelection(val task: Task, val source: String)

/**
 * Runs the recovery task for a failed run, if one is configured and usable.
 *
 * The profile's own fallback wins over the global one, and a task never falls back to ITSELF — that
 * would re-run the thing that just failed, with its own failure as input.
 */
private suspend fun runFallbackTask(
    appContext: Context,
    db: AppDatabase,
    failedTask: Task,
    error: StructuredTaskError,
    profileFallbackTaskId: Long?,
    admissionController: ExecutionAdmissionController,
    profileId: Long?,
    profileLimits: ExecutionAdmissionProfileLimits?,
    overflowPolicy: ProfileOverflowPolicy,
    visibleActivity: Boolean,
    audioForegroundService: AudioForegroundServiceEligibility,
    logTag: String,
): FallbackExecutionResult? {
    val selection = selectFallbackTask(
        db = db,
        failedTask = failedTask,
        profileFallbackTaskId = profileFallbackTaskId,
        globalFallbackTaskId = runCatching { FallbackTaskSettings(appContext).loadTaskId() }.getOrNull(),
    ) ?: return null
    val fallbackSource = "Fallback: ${selection.task.name}"
    return try {
        val result = executeAndLogTask(
            appContext = appContext,
            db = db,
            task = selection.task,
            source = fallbackSource,
            metadata = listOf(
                "Fallback source: ${selection.source}",
                "Original task: ${failedTask.name} (${failedTask.id})",
            ),
            // The failure arrives as ordinary task-local variables, so the recovery task can branch
            // on what broke without parsing a log line.
            initialVariables = error.toFailureVariables(),
            visibleActivity = visibleActivity,
            audioForegroundService = audioForegroundService,
            logTag = logTag,
            admissionController = admissionController,
            profileId = profileId,
            profileLimits = profileLimits,
            overflowPolicy = overflowPolicy,
            allowFallback = false,
        )
        FallbackExecutionResult(
            taskId = selection.task.id,
            taskName = selection.task.name,
            source = selection.source,
            success = result.report.success,
            reason = result.skippedReason ?: result.report.structuredError?.message,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        FallbackExecutionResult(
            taskId = selection.task.id,
            taskName = selection.task.name,
            source = selection.source,
            success = false,
            reason = failure.message ?: "Fallback task failed before it could run.",
        )
    }
}

/**
 * The recovery tasks to try, in order, for a task that just failed.
 *
 * Pure, and separate from the lookup, so the rule is testable without a database: the profile's own
 * choice outranks the global one, the same id is not tried twice under two names, and a task is never
 * its own recovery — that would re-run the thing that just failed, handed its own failure as input.
 */
internal fun fallbackCandidateIds(
    profileFallbackTaskId: Long?,
    globalFallbackTaskId: Long?,
    failedTaskId: Long,
): List<Pair<Long, String>> = listOfNotNull(
    profileFallbackTaskId?.takeIf { it > 0L }?.let { it to "profile" },
    globalFallbackTaskId?.takeIf { it > 0L }?.let { it to "global" },
).distinctBy { it.first }.filterNot { (id, _) -> id == failedTaskId }

private suspend fun selectFallbackTask(
    db: AppDatabase,
    failedTask: Task,
    profileFallbackTaskId: Long?,
    globalFallbackTaskId: Long?,
): FallbackTaskSelection? {
    for ((id, origin) in fallbackCandidateIds(profileFallbackTaskId, globalFallbackTaskId, failedTask.id)) {
        val task = runCatching { db.taskDao().getById(id)?.toDomain() }.getOrNull()
        if (task != null) return FallbackTaskSelection(task, origin)
    }
    return null
}

private fun collisionSkippedReport(task: Task, reason: String): TaskRunReport {
    val startedAt = System.currentTimeMillis()
    return TaskRunReport(
        taskId = task.id,
        taskName = task.name,
        startedAt = startedAt,
        durationMs = 0,
        results = listOf(ActionResult.Skip),
        traces = listOf(
            ActionExecutionTrace(
                index = 0,
                actionType = "admission",
                label = "collision policy",
                durationMs = 0,
                status = ActionTraceStatus.SKIPPED,
                message = reason,
            ),
        ),
        success = false,
    )
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
 * If [task] is freeze-enabled, queue a re-freeze bubble for the app it launches/unfreezes. The package is
 * read from the task's `app.launch` (preferred) or `app.unfreeze` action, expanded against the run's
 * variables; an unresolved (`%var`-still-present) or blank package is skipped.
 */
private fun maybeQueueFreezeBubble(appContext: Context, task: Task, variables: VariableStore) {
    if (!task.freezeBubble) return
    // The same rule the `tasks.freezebubbles` picker lists by — kept in one place on purpose.
    val pkg = com.opentasker.core.bubbles.FreezeBubbleTarget
        .packageOf(task.actions) { variables.expand(it) } ?: return
    val label = runCatching {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: task.name
    com.opentasker.core.bubbles.FreezeBubbleStore.enqueue(pkg, label, task.iconPath)
}

/**
 * Writes an admission-refused run as a HELD entry: a skipped row that also carries a redacted
 * snapshot of what would have run, so the Run Log can offer to replay it.
 *
 * The payload goes through HeldExecutionPayloadCodec, which caps its size and redacts anything whose
 * name or value looks like a credential — a held row is durable and readable, so it must not become
 * the place a token comes to rest.
 */
suspend fun logHeldRun(
    db: AppDatabase,
    task: Task,
    source: String,
    reason: String,
    metadata: List<String>,
    profileId: Long?,
    initialVariables: Map<String, String>,
    policy: String,
): Boolean {
    val classified = RunLogSource.classify(source)
    val payload = runCatching {
        HeldExecutionPayloadCodec.encode(
            HeldExecutionPayload(
                taskId = task.id,
                taskName = task.name,
                source = source,
                profileId = profileId,
                metadata = metadata,
                initialVariables = initialVariables,
            ),
        )
    }.onFailure { AppLogger.error(TAG, "Could not encode held payload for task ${task.id}", it) }
        .getOrNull()
    return insertRunLog(
        db,
        RunLogEntry(
            taskId = task.id,
            taskName = task.name,
            timestamp = System.currentTimeMillis(),
            durationMs = 0,
            success = false,
            message = runLogMessage(source = source, metadata = metadata + listOf("Skipped: $reason"), traces = emptyList()),
            source = classified.key,
            sourceLabel = classified.label,
            // Only a row that actually carries a payload is offered for replay; without one there is
            // nothing to re-run, and a Replay button that cannot work is worse than none.
            held = payload != null,
            heldPayload = payload,
            heldPolicy = policy,
        ),
    )
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

/** Fail-closed decode: a corrupt stored task resolves to `null` instead of an empty action list. */
private fun TaskEntity.decodedOrNull(ref: String): Task? {
    val result = toDomainDecodeResult()
    val issue = result.issue
    if (issue != null) {
        AppLogger.error(TAG, "Task '$ref' (id=$id) is corrupt: ${issue.message}")
        return null
    }
    return result.value
}

/**
 * Resolve a task reference NAME-first (the id is only a legacy fallback), scoped to [projectId] when
 * given — a `(project, name)` match wins, then any-project name match (deterministic: lowest position
 * then id), then the numeric id. Used by scene elements (tap / long-press / gesture) so a link survives
 * re-imports that re-id the task and disambiguates same-name tasks across projects. Mirrors the scene
 * resolver in SceneActions.
 */
suspend fun resolveTaskByName(db: AppDatabase, ref: String, projectId: Long?): Task? {
    if (ref.isBlank()) return null
    val all = db.taskDao().getAll()
    if (projectId != null) {
        all.firstOrNull { (it.projectId ?: 0L) == projectId && it.name.equals(ref, ignoreCase = true) }
            ?.let { return it.decodedOrNull(ref) }
    }
    all.filter { it.name.equals(ref, ignoreCase = true) }
        .minByOrNull { it.position.toLong() * 10_000_000L + it.id }
        ?.let { return it.decodedOrNull(ref) }
    return ref.toLongOrNull()?.let { id -> all.firstOrNull { it.id == id }?.decodedOrNull(ref) }
}

/**
 * Resolves a sub-task by NAME first (exact, then case-insensitive); the numeric id is only a legacy
 * fallback. Used by `task.run` — matches the name-first resolution scenes use, so re-imports that re-id
 * a task don't strand callers that reference it. Corrupt tasks (whose stored payload fails to decode)
 * resolve to `null` so `task.run` fails closed instead of silently running an empty action list.
 */
fun dbSubTaskResolver(db: AppDatabase): SubTaskResolver = resolver@{ ref ->
    db.taskDao().getByName(ref)?.let { return@resolver it.decodedOrNull(ref) }
    db.taskDao().getAll().firstOrNull { it.name.equals(ref, ignoreCase = true) }?.let { return@resolver it.decodedOrNull(ref) }
    ref.toLongOrNull()?.let { db.taskDao().getById(it) }?.decodedOrNull(ref)
}

suspend fun insertRunLog(db: AppDatabase, entry: RunLogEntry): Boolean =
    runCatching { db.runLogDao().insert(entry.toEntity()) }
        .onFailure { e -> AppLogger.error(TAG, "Failed to write run log for task ${entry.taskId}", e) }
        .isSuccess

private const val TAG = "OpenTasker"
