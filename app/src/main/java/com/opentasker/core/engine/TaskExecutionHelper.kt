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
    val held: Boolean = false,
    val execution: ExecutionEnvelope,
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
    admissionController: ExecutionAdmissionController = ExecutionAdmissionController.Default,
    profileId: Long? = null,
    execution: ExecutionEnvelope = ExecutionEnvelope.create(task, source, profileId = profileId),
): TaskExecutionResult = withContext(Dispatchers.IO) {
    val accepted = ExecutionCommandLedger.accept(execution)
    if (!accepted.isNew) {
        val reason = ExecutionTerminalReason(
            ExecutionTerminalReasonCode.DUPLICATE_DELIVERY,
            "Duplicate execution delivery ignored.",
        ).render()
        return@withContext TaskExecutionResult(
            report = collisionSkippedReport(task, reason),
            logInserted = false,
            skippedReason = reason,
            execution = execution,
        )
    }
    val admission = admissionController.tryAcquire(profileId)
    if (!admission.accepted) {
        val reason = admission.reason ?: "Execution admission rejected this run."
        val terminalReason = ExecutionTerminalReason(ExecutionTerminalReasonCode.ADMISSION_REJECTED, reason)
        ExecutionCommandLedger.transition(
            execution.executionId,
            ExecutionLedgerState.HELD,
            terminalReason,
        )
        val inserted = logHeldRun(
            db = db,
            task = task,
            source = execution.source,
            reason = reason,
            metadata = metadata,
            initialVariables = initialVariables,
            execution = execution,
            terminalReason = terminalReason,
        )
        return@withContext TaskExecutionResult(
            report = collisionSkippedReport(task, reason),
            logInserted = inserted,
            skippedReason = reason,
            held = true,
            execution = execution,
        )
    }
    val admissionLease = requireNotNull(admission.lease)
    // Only admitted work becomes a causal parent. A capacity or collision skip must not make a
    // later unrelated profile appear to be a child of work that never ran.
    ExecutionCausality.remember(execution)
    try {
    ExecutionCommandLedger.transition(execution.executionId, ExecutionLedgerState.RUNNING)
    // Run the whole task off the caller's thread. Manual runs (ViewModel), widget/shortcut, and
    // notification-action paths call this from the main thread; without this hop, blocking actions
    // (HTTP, file, ping) would throw NetworkOnMainThreadException and fail silently.
    val variables = VariableStore()
    val variableRepository = VariableRepository(db.variableDao())
    val persistedGlobals = runCatching {
        variableRepository.runtimeGlobals(task.projectId)
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
    val collisionCoordinator = TaskCollisionCoordinator.Default
    var executionId: Long? = null
    val collisionOutcome = try {
        collisionCoordinator.execute(task) {
            // Publish only admitted work. A WAIT invocation is not shown as active until it owns
            // the task slot, and ABORT_NEW never flashes a run that will immediately be skipped.
            val admittedExecutionId = ActiveExecutionRegistry.register(
                taskId = task.id,
                taskName = task.name,
                source = execution.source,
                job = currentCoroutineContext()[Job],
                startedAtMs = System.currentTimeMillis(),
                executionId = execution.executionId,
                parentExecutionId = execution.parentExecutionId,
                producer = execution.producer.wireValue,
            )
            executionId = admittedExecutionId
            try {
                TaskRunner(
                    ctx,
                    resolveTask = dbSubTaskResolver(db),
                    onStep = { index, label -> ActiveExecutionRegistry.reportStep(admittedExecutionId, index, label) },
                    collisionCoordinator = collisionCoordinator,
                    executionChain = setOf(task.id).filterTo(linkedSetOf()) { it > 0L },
                ).run(task)
            } finally {
                ActiveExecutionRegistry.unregister(admittedExecutionId)
            }
        }
    } catch (cancellation: CancellationException) {
        // withContext(NonCancellable): the surrounding scope is already cancelled, so an ordinary
        // suspending write here would be dropped and the run would vanish without a trace.
        withContext(NonCancellable) {
            executionId?.let(ActiveExecutionRegistry::unregister)
            val terminalReason = ExecutionTerminalReason(
                ExecutionTerminalReasonCode.CANCELLED,
                cancellation.message ?: ActiveExecutionRegistry.CANCELLED_BY_USER,
            )
            ExecutionCommandLedger.transition(
                execution.executionId,
                ExecutionLedgerState.CANCELLED,
                terminalReason,
            )
            insertRunLog(
                db,
                RunLogEntry(
                    taskId = task.id,
                    taskName = task.name,
                    timestamp = System.currentTimeMillis(),
                    durationMs = 0,
                    success = false,
                    message = cancelledRunLogMessage(
                        source = execution.source,
                        reason = cancellation.message ?: ActiveExecutionRegistry.CANCELLED_BY_USER,
                        execution = execution,
                        terminalReason = terminalReason,
                        metadata = metadata,
                    ),
                    source = RunLogSource.classify(execution.source).key,
                    sourceLabel = RunLogSource.classify(execution.source).label,
                    executionId = execution.executionId,
                    replayOf = execution.replayOf,
                ),
            )
        }
        throw cancellation
    }
    val report = when (collisionOutcome) {
        is TaskCollisionOutcome.Executed -> collisionOutcome.value
        is TaskCollisionOutcome.Skipped -> {
            val terminalReason = ExecutionTerminalReason(
                ExecutionTerminalReasonCode.COLLISION_SKIPPED,
                collisionOutcome.reason,
            )
            ExecutionCommandLedger.transition(
                execution.executionId,
                ExecutionLedgerState.SKIPPED,
                terminalReason,
            )
            val inserted = logSkippedRun(
                db = db,
                task = task,
                source = execution.source,
                reason = collisionOutcome.reason,
                metadata = metadata,
                execution = execution,
                terminalReason = terminalReason,
            )
            return@withContext TaskExecutionResult(
                report = collisionSkippedReport(task, collisionOutcome.reason),
                logInserted = inserted,
                skippedReason = collisionOutcome.reason,
                execution = execution,
            )
        }
    }
    val globalCommitMetadata = persistChangedGlobals(
        variableRepository,
        persistedBaseline,
        baselineGlobals,
        variables.globalSnapshot(),
        baselineSensitiveGlobals,
        variables.globalSensitiveSnapshot(),
        logTag,
        projectId = task.projectId,
    )
    AppLogger.info(logTag, "Task ${report.taskName} completed: ${report.success} (${report.durationMs}ms)")
    val terminalReason = ExecutionTerminalReason(
        if (report.success) ExecutionTerminalReasonCode.COMPLETED else ExecutionTerminalReasonCode.TASK_FAILED,
    )
    ExecutionCommandLedger.transition(
        execution.executionId,
        if (report.success) ExecutionLedgerState.SUCCEEDED else ExecutionLedgerState.FAILED,
        terminalReason,
    )
    val classified = RunLogSource.classify(execution.source)
    val riskMetadata = taskPowerRunLogMetadata(task)
    val logEntry = RunLogEntry(
        taskId = task.id,
        taskName = task.name,
        timestamp = report.startedAt,
        durationMs = report.durationMs,
        success = report.success,
        message = runLogMessage(
            source = execution.source,
            execution = execution,
            terminalReason = terminalReason,
            metadata = riskMetadata + metadata + globalCommitMetadata,
            traces = report.traces,
        ),
        source = classified.key,
        sourceLabel = classified.label,
        executionId = execution.executionId,
        replayOf = execution.replayOf,
    )
    val inserted = insertRunLog(db, logEntry)
    TaskExecutionResult(report, inserted, execution = execution)
    } finally {
        admissionLease.release()
    }
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
    projectId: Long = com.opentasker.core.model.DEFAULT_PROJECT_ID,
): List<RuntimeVariableValue> =
    after.asSequence()
        .filter { (name, value) -> before[name] != value || (name in beforeSensitive) != (name in afterSensitive) }
        .sortedBy { it.key }
        .map { (name, value) -> RuntimeVariableValue(name, value, isSecret = name in afterSensitive, projectId = projectId) }
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
    projectId: Long = com.opentasker.core.model.DEFAULT_PROJECT_ID,
): List<String> {
    val changed = changedGlobals(before, after, beforeSensitive, afterSensitive, projectId)
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
    execution: ExecutionEnvelope? = null,
    terminalReason: ExecutionTerminalReason? = null,
): Boolean {
    val envelope = execution ?: ExecutionEnvelope.create(task, source)
    ExecutionCommandLedger.accept(envelope)
    val resolvedReason = terminalReason ?: ExecutionTerminalReason(
        ExecutionTerminalReasonCode.UNKNOWN,
        reason,
    )
    ExecutionCommandLedger.transition(envelope.executionId, ExecutionLedgerState.SKIPPED, resolvedReason)
    val classified = RunLogSource.classify(envelope.source)
    return insertRunLog(
        db,
        RunLogEntry(
            taskId = task.id,
            taskName = task.name,
            durationMs = 0,
            success = false,
            message = skippedRunLogMessage(
                source = envelope.source,
                reason = reason,
                execution = envelope,
                terminalReason = resolvedReason,
                metadata = metadata,
            ),
            source = classified.key,
            sourceLabel = classified.label,
            executionId = envelope.executionId,
            replayOf = envelope.replayOf,
        ),
    )
}

suspend fun logHeldRun(
    db: AppDatabase,
    task: Task,
    source: String,
    reason: String,
    metadata: List<String> = emptyList(),
    initialVariables: Map<String, String> = emptyMap(),
    execution: ExecutionEnvelope? = null,
    terminalReason: ExecutionTerminalReason? = null,
): Boolean {
    val envelope = execution ?: ExecutionEnvelope.create(task, source)
    ExecutionCommandLedger.accept(envelope)
    val resolvedReason = terminalReason ?: ExecutionTerminalReason(
        ExecutionTerminalReasonCode.ADMISSION_REJECTED,
        reason,
    )
    ExecutionCommandLedger.transition(envelope.executionId, ExecutionLedgerState.HELD, resolvedReason)
    val classified = RunLogSource.classify(envelope.source)
    return insertRunLog(
        db,
        RunLogEntry(
            taskId = task.id,
            taskName = task.name,
            durationMs = 0,
            success = false,
            message = heldRunLogMessage(
                source = envelope.source,
                reason = reason,
                execution = envelope,
                terminalReason = resolvedReason,
                metadata = metadata,
            ),
            source = classified.key,
            sourceLabel = classified.label,
            executionId = envelope.executionId,
            replayOf = envelope.replayOf,
            held = true,
            heldPayload = HeldExecutionPayloadCodec.encode(task, envelope, metadata, initialVariables),
            heldPolicy = reason,
        ),
    )
}

/** Re-admits a held trigger against the current task definition and creates a new command id. */
suspend fun replayHeldExecution(
    appContext: Context,
    db: AppDatabase,
    heldEntry: RunLogEntry,
    visibleActivity: Boolean = false,
    audioForegroundService: AudioForegroundServiceEligibility = AudioForegroundServiceEligibility.NONE,
    admissionController: ExecutionAdmissionController = ExecutionAdmissionController.Default,
): TaskExecutionResult {
    require(heldEntry.held) { "Only held executions can be replayed." }
    val originalExecutionId = requireNotNull(heldEntry.executionId) { "Held execution has no command id." }
    val payload = requireNotNull(HeldExecutionPayloadCodec.decode(heldEntry.heldPayload)) {
        "Held execution payload is invalid or unavailable."
    }
    require(payload.taskId == heldEntry.taskId) { "Held execution task identity does not match its payload." }
    val task = db.taskDao().getById(payload.taskId)
        ?.toDomainDecodeResult()
        ?.takeIf { it.issue == null }
        ?.value
        ?: error("The task for this held execution no longer exists or is corrupt.")
    val replayEnvelope = ExecutionEnvelope.create(
        task = task,
        source = payload.source,
        profileId = payload.profileId,
        replayOf = originalExecutionId,
    )
    return executeAndLogTask(
        appContext = appContext,
        db = db,
        task = task,
        source = payload.source,
        metadata = payload.metadata,
        initialVariables = payload.initialVariables,
        visibleActivity = visibleActivity,
        audioForegroundService = audioForegroundService,
        admissionController = admissionController,
        profileId = payload.profileId,
        execution = replayEnvelope,
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
