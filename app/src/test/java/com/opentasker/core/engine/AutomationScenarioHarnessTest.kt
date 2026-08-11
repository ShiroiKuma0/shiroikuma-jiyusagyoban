package com.opentasker.core.engine

import android.content.Context
import android.content.ContextWrapper
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.ContextMatchEvaluator
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.Task
import com.opentasker.core.storage.DatabaseMigrations
import com.opentasker.core.storage.OPEN_TASKER_DATABASE_SCHEMA_VERSION
import com.opentasker.core.model.RunLogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A deterministic, JVM-only execution fixture.
 *
 * The fixture deliberately keeps persistence in a small Room-shaped store: JVM tests cannot open
 * Android SQLite, but they can still exercise the production profile evaluator, admission policy,
 * TaskRunner, variable commit/redaction boundary, and the journal state machine together. The
 * Android migration suite separately opens Room and runs the same 14 -> 15 migration.
 */
private class AutomationScenarioHarness(
    val clock: ScenarioClock = ScenarioClock(1_700_000_000_000L),
) {
    val store = ScenarioStore()
    val sideEffects = ScenarioSideEffectLedger(clock)
    val admission = ExecutionAdmissionController(
        limits = ExecutionAdmissionLimits(
            globalMaxActive = 1,
            perProfileMaxActive = 1,
            globalBurstLimit = 4,
            perProfileBurstLimit = 4,
        ),
        now = clock::now,
    )
    private val commandLedger = ExecutionLedger()
    private val platform = ScenarioPlatform()

    init {
        ActionRegistry.register(ScenarioRecordAction(sideEffects))
        ActionRegistry.register(ScenarioRetryableAction())
        ActionRegistry.register(ScenarioNeverRetryAction(sideEffects))
        ActionRegistry.register(ScenarioCancellationAction())
    }

    suspend fun run(
        profile: Profile,
        task: Task,
        event: ContextEvent,
        executionId: String,
        initialVariables: Map<String, String> = emptyMap(),
    ): ScenarioRun {
        val execution = ExecutionEnvelope.create(
            task = task,
            source = "Profile: ${profile.name}",
            profileId = profile.id,
            executionId = executionId,
            nowMs = clock.now(),
        )
        if (!matches(profile, event)) return ScenarioRun(ScenarioOutcome.NOT_MATCHED, execution)
        if (!commandLedger.accept(execution).isNew) {
            return ScenarioRun(ScenarioOutcome.DUPLICATE, execution)
        }

        val admission = admission.tryAcquire(profile.id, profile.toExecutionAdmissionProfileLimits())
        if (!admission.accepted) {
            val held = profile.overflowPolicy == ProfileOverflowPolicy.LOG
            val state = if (held) ExecutionLedgerState.HELD else ExecutionLedgerState.SKIPPED
            val reason = ExecutionTerminalReason(
                ExecutionTerminalReasonCode.ADMISSION_REJECTED,
                admission.reason,
            )
            commandLedger.transition(execution.executionId, state, reason, clock.now())
            val log = if (held) {
                store.appendRunLog(
                    RunLogEntry(
                        taskId = task.id,
                        taskName = task.name,
                        timestamp = clock.now(),
                        durationMs = 0,
                        success = false,
                        message = "Decision: Held\nReason: ${reason.render()}",
                        source = "profile",
                        sourceLabel = profile.name,
                        executionId = execution.executionId,
                        held = true,
                        heldPolicy = admission.reason,
                    ),
                )
            } else {
                null
            }
            return ScenarioRun(
                outcome = if (held) ScenarioOutcome.HELD else ScenarioOutcome.SKIPPED,
                execution = execution,
                log = log,
            )
        }

        val lease = requireNotNull(admission.lease)
        try {
            commandLedger.transition(execution.executionId, ExecutionLedgerState.RUNNING, nowMs = clock.now())
            if (!store.journal.start(execution, clock.now())) {
                val reason = ExecutionTerminalReason(
                    ExecutionTerminalReasonCode.DUPLICATE_DELIVERY,
                    "This execution was already journaled.",
                )
                commandLedger.transition(execution.executionId, ExecutionLedgerState.SKIPPED, reason, clock.now())
                return ScenarioRun(ScenarioOutcome.DUPLICATE, execution)
            }

            val variables = VariableStore().apply {
                seedGlobals(store.variables, store.secretVariables)
                initialVariables.forEach { (name, value) -> set(name, value) }
            }
            val report = try {
                TaskRunner(
                    ctx = ActionContext(
                        app = platform.context,
                        variables = variables,
                        eventVariables = event.metadata,
                    ),
                    onStepCompleted = { index, label ->
                        store.journal.recordStep(execution.executionId, index, label, clock.now())
                    },
                    now = clock::now,
                ).run(task)
            } catch (cancellation: CancellationException) {
                val reason = ExecutionTerminalReason(
                    ExecutionTerminalReasonCode.CANCELLED,
                    cancellation.message,
                )
                commandLedger.transition(execution.executionId, ExecutionLedgerState.CANCELLED, reason, clock.now())
                store.journal.markTerminal(execution.executionId, ExecutionJournalState.CANCELLED, reason, clock.now())
                store.appendRunLog(
                    RunLogEntry(
                        taskId = task.id,
                        taskName = task.name,
                        timestamp = clock.now(),
                        durationMs = 0,
                        success = false,
                        message = "Decision: Cancelled\nReason: ${reason.render()}",
                        source = "profile",
                        sourceLabel = profile.name,
                        executionId = execution.executionId,
                    ),
                )
                throw cancellation
            }

            store.variables.clear()
            store.variables.putAll(variables.globalSnapshot())
            store.secretVariables.clear()
            store.secretVariables += variables.globalSensitiveSnapshot()

            val state = if (report.success) ExecutionJournalState.SUCCEEDED else ExecutionJournalState.FAILED
            val reason = ExecutionTerminalReason(
                if (report.success) ExecutionTerminalReasonCode.COMPLETED else ExecutionTerminalReasonCode.TASK_FAILED,
                report.structuredError?.message,
            )
            commandLedger.transition(
                execution.executionId,
                if (report.success) ExecutionLedgerState.SUCCEEDED else ExecutionLedgerState.FAILED,
                reason,
                clock.now(),
            )
            store.journal.markTerminal(execution.executionId, state, reason, clock.now())
            val log = store.appendRunLog(
                RunLogEntry(
                    taskId = task.id,
                    taskName = task.name,
                    timestamp = report.startedAt,
                    durationMs = report.durationMs,
                    success = report.success,
                    message = report.traces.toRunLogMessage(),
                    source = "profile",
                    sourceLabel = profile.name,
                    executionId = execution.executionId,
                ),
            )
            store.journal.markRunLogWritten(execution.executionId)
            return ScenarioRun(
                outcome = if (report.success) ScenarioOutcome.SUCCEEDED else ScenarioOutcome.FAILED,
                execution = execution,
                report = report,
                log = log,
            )
        } finally {
            lease.release()
        }
    }

    /** Starts a durable command and leaves it active, simulating a process killed mid-action. */
    fun beginInterrupted(
        profile: Profile,
        task: Task,
        executionId: String,
    ): ExecutionEnvelope {
        val execution = ExecutionEnvelope.create(
            task = task,
            source = "Profile: ${profile.name}",
            profileId = profile.id,
            executionId = executionId,
            nowMs = clock.now(),
        )
        check(commandLedger.accept(execution).isNew)
        check(store.journal.start(execution, clock.now()))
        store.journal.recordStep(execution.executionId, 0, task.actions.firstOrNull()?.label ?: "first action", clock.now())
        return execution
    }

    fun recoverAfterRestart(): ScenarioRecovery = store.recover(clock.now())

    private fun matches(profile: Profile, event: ContextEvent): Boolean {
        val updates = profile.contexts.map { spec ->
            val raw = ContextMatchEvaluator.matches(spec, event)
            ContextMatchUpdate(
                matched = if (spec.invert) !raw else raw,
                pulseContext = spec.type == ContextType.EVENT,
                pulseSequence = if (spec.type == ContextType.EVENT && raw) 1L else 0L,
                event = event.takeIf { raw },
            )
        }
        return evaluateContextExpression(
            contextMatches = updates.toTypedArray(),
            specs = profile.contexts,
            expression = profile.contextExpression,
        )
    }
}

private enum class ScenarioOutcome {
    NOT_MATCHED,
    DUPLICATE,
    HELD,
    SKIPPED,
    SUCCEEDED,
    FAILED,
}

private data class ScenarioRun(
    val outcome: ScenarioOutcome,
    val execution: ExecutionEnvelope,
    val report: TaskRunReport? = null,
    val log: RunLogEntry? = null,
)

private class ScenarioClock(startMs: Long) {
    private var currentMs = startMs

    fun now(): Long = currentMs

    fun advanceBy(deltaMs: Long) {
        require(deltaMs >= 0L)
        currentMs += deltaMs
    }
}

private class ScenarioSideEffectLedger(private val clock: ScenarioClock) {
    data class Entry(val value: String, val timestampMs: Long)

    val entries = mutableListOf<Entry>()

    fun record(value: String) {
        entries += Entry(value, clock.now())
    }
}

private class ScenarioPlatform {
    val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}

private class ScenarioStore {
    var roomSchemaVersion: Int = OPEN_TASKER_DATABASE_SCHEMA_VERSION - 1
    val variables = linkedMapOf<String, String>()
    val secretVariables = linkedSetOf<String>()
    val runLogs = mutableListOf<RunLogEntry>()
    val journal = ScenarioJournal()

    fun appendRunLog(entry: RunLogEntry): RunLogEntry {
        runLogs += entry.copy(id = runLogs.size.toLong() + 1L)
        return runLogs.last()
    }

    fun migrateRoomToCurrent() {
        check(
            DatabaseMigrations.getAllMigrations().any { migration ->
                migration.startVersion == roomSchemaVersion && migration.endVersion == OPEN_TASKER_DATABASE_SCHEMA_VERSION
            },
        ) { "The current Room schema has no migration from $roomSchemaVersion" }
        roomSchemaVersion = OPEN_TASKER_DATABASE_SCHEMA_VERSION
    }

    fun recover(nowMs: Long): ScenarioRecovery {
        val stale = journal.activeStartedBefore(nowMs)
        stale.forEach { row ->
            journal.markTerminal(
                executionId = row.execution.executionId,
                state = ExecutionJournalState.INTERRUPTED,
                reason = ExecutionTerminalReason(
                    ExecutionTerminalReasonCode.ENGINE_RESTARTED,
                    "Process ended before a terminal execution record was written.",
                ),
                nowMs = nowMs,
            )
            appendRunLog(
                RunLogEntry(
                    taskId = row.execution.taskId,
                    taskName = row.execution.taskName,
                    timestamp = nowMs,
                    durationMs = (nowMs - row.startedAtMs).coerceAtLeast(0L),
                    success = false,
                    message = buildString {
                        append("Decision: Interrupted\n")
                        append("Reason: Process ended before a terminal execution record was written.\n")
                        append("Last known step: ")
                        append(row.lastStepLabel ?: "none recorded")
                        append("\nRecovery: no automatic retry was attempted.")
                    },
                    source = "profile",
                    sourceLabel = row.execution.source,
                    executionId = row.execution.executionId,
                ),
            )
            journal.markRunLogWritten(row.execution.executionId)
        }
        return ScenarioRecovery(stale.size)
    }
}

private data class ScenarioRecovery(val interrupted: Int)

private class ScenarioJournal {
    data class Row(
        val execution: ExecutionEnvelope,
        val startedAtMs: Long,
        var state: ExecutionJournalState,
        var lastStepLabel: String? = null,
        var terminalReason: ExecutionTerminalReason? = null,
        var runLogWritten: Boolean = false,
    )

    private val rows = linkedMapOf<String, Row>()

    fun start(execution: ExecutionEnvelope, nowMs: Long): Boolean {
        if (rows.containsKey(execution.executionId)) return false
        rows[execution.executionId] = Row(execution, nowMs, ExecutionJournalState.ACTIVE)
        return true
    }

    fun recordStep(executionId: String, index: Int, label: String, nowMs: Long) {
        rows[executionId]?.takeIf { it.state == ExecutionJournalState.ACTIVE }?.let { row ->
            row.lastStepLabel = "${index + 1}. $label"
        }
    }

    fun markTerminal(
        executionId: String,
        state: ExecutionJournalState,
        reason: ExecutionTerminalReason,
        nowMs: Long,
    ): Boolean {
        val row = rows[executionId] ?: return false
        if (row.state != ExecutionJournalState.ACTIVE) return false
        row.state = state
        row.terminalReason = reason
        return true
    }

    fun markRunLogWritten(executionId: String) {
        rows[executionId]?.runLogWritten = true
    }

    fun activeStartedBefore(nowMs: Long): List<Row> = rows.values.filter {
        it.state == ExecutionJournalState.ACTIVE && it.startedAtMs < nowMs
    }

    fun row(executionId: String): Row? = rows[executionId]
}

private class ScenarioRecordAction(
    private val ledger: ScenarioSideEffectLedger,
) : Action {
    override val id = "scenario.record"
    override val category = ActionCategory.FLOW
    override val retrySafety = ActionRetrySafety.NEVER

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ledger.record(args["value"].orEmpty())
        return if (args["fail"] == "true") {
            ActionResult.Failure("side effect failed")
        } else {
            ActionResult.Success
        }
    }
}

private class ScenarioRetryableAction : Action {
    override val id = "scenario.retryable"
    override val category = ActionCategory.FLOW
    override val retrySafety = ActionRetrySafety.IDEMPOTENT
    private var calls = 0

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        calls++
        return if (calls <= args["failures"].orEmpty().toInt()) {
            ActionResult.Failure("retryable failure $calls")
        } else {
            ActionResult.Success
        }
    }
}

private class ScenarioNeverRetryAction(
    private val ledger: ScenarioSideEffectLedger,
) : Action {
    override val id = "scenario.never_retry"
    override val category = ActionCategory.FLOW
    override val retrySafety = ActionRetrySafety.NEVER

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ledger.record("non-retry-safe")
        return ActionResult.Failure("permanent failure")
    }
}

private class ScenarioCancellationAction : Action {
    override val id = "scenario.cancel"
    override val category = ActionCategory.FLOW

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        throw CancellationException("scenario cancelled")
}

private fun scenarioProfile(
    id: Long = 7L,
    overflowPolicy: ProfileOverflowPolicy = ProfileOverflowPolicy.LOG,
) = Profile(
    id = id,
    name = "Scenario profile",
    enterTaskId = 11L,
    contexts = listOf(
        ContextSpec(ContextType.EVENT, config = mapOf("event" to "push")),
    ),
    overflowPolicy = overflowPolicy,
)

private fun scenarioEvent(matched: Boolean = true) = ContextEvent(
    type = "event",
    matched = matched,
    metadata = mapOf("event" to "push", "message" to "hello"),
)

private fun scenarioTask(vararg actions: ActionSpec) = Task(
    id = 11L,
    name = "Scenario task",
    actions = actions.toList(),
)

class AutomationScenarioHarnessTest {
    @Test
    fun triggerDeliveryCommitsVariableAndRunEvidence() = runBlocking {
        val harness = AutomationScenarioHarness()
        val result = harness.run(
            profile = scenarioProfile(),
            task = scenarioTask(
                ActionSpec(
                    type = "scenario.record",
                    args = mapOf("value" to "{{ event.message }}"),
                ),
                ActionSpec(
                    type = "scenario.record",
                    args = mapOf("value" to "{{ event.message }}"),
                    condition = "{{ event.message }} == hello",
                ),
            ),
            event = scenarioEvent(),
            executionId = "scenario-success",
        )

        assertEquals(ScenarioOutcome.SUCCEEDED, result.outcome)
        assertEquals(listOf("hello", "hello"), harness.sideEffects.entries.map { it.value })
        assertEquals(1, harness.store.runLogs.size)
        assertEquals(ExecutionJournalState.SUCCEEDED, harness.store.journal.row("scenario-success")?.state)
        assertTrue(harness.store.journal.row("scenario-success")?.runLogWritten == true)
        assertTrue(result.log?.message?.contains("success") == true)
    }

    @Test
    fun duplicateDeliveryDoesNotRepeatSideEffectOrRunLog() = runBlocking {
        val harness = AutomationScenarioHarness()
        val task = scenarioTask(ActionSpec(type = "scenario.record", args = mapOf("value" to "once")))
        val first = harness.run(scenarioProfile(), task, scenarioEvent(), "scenario-duplicate")
        val second = harness.run(scenarioProfile(), task, scenarioEvent(), "scenario-duplicate")

        assertEquals(ScenarioOutcome.SUCCEEDED, first.outcome)
        assertEquals(ScenarioOutcome.DUPLICATE, second.outcome)
        assertEquals(1, harness.sideEffects.entries.size)
        assertEquals(1, harness.store.runLogs.size)
    }

    @Test
    fun admissionOverflowIsHeldWithoutRunningAnAction() = runBlocking {
        val harness = AutomationScenarioHarness()
        val heldLease = requireNotNull(harness.admission.tryAcquire(7L).lease)
        try {
            val result = harness.run(
                profile = scenarioProfile(),
                task = scenarioTask(ActionSpec(type = "scenario.record", args = mapOf("value" to "never"))),
                event = scenarioEvent(),
                executionId = "scenario-held",
            )

            assertEquals(ScenarioOutcome.HELD, result.outcome)
            assertTrue(result.log?.held == true)
            assertTrue(harness.sideEffects.entries.isEmpty())
        } finally {
            heldLease.release()
        }
    }

    @Test
    fun retryableFailuresRetryButNonRetrySafeFailureRunsOnce() = runBlocking {
        val retryHarness = AutomationScenarioHarness()
        val retryResult = retryHarness.run(
            scenarioProfile(),
            scenarioTask(
                ActionSpec(type = FlowControl.TRY, args = mapOf("max_attempts" to "3")),
                ActionSpec(type = "scenario.retryable", args = mapOf("failures" to "2")),
                ActionSpec(type = FlowControl.CATCH),
                ActionSpec(type = FlowControl.ENDTRY),
            ),
            scenarioEvent(),
            "scenario-retry",
        )
        assertEquals(ScenarioOutcome.SUCCEEDED, retryResult.outcome)
        assertEquals(3, retryResult.report?.traces?.count { it.actionType == "scenario.retryable" })

        val unsafeHarness = AutomationScenarioHarness()
        val unsafeResult = unsafeHarness.run(
            scenarioProfile(),
            scenarioTask(
                ActionSpec(type = FlowControl.TRY, args = mapOf("max_attempts" to "3")),
                ActionSpec(type = "scenario.never_retry"),
                ActionSpec(type = FlowControl.CATCH),
                ActionSpec(type = FlowControl.ENDTRY),
            ),
            scenarioEvent(),
            "scenario-no-retry",
        )
        assertEquals(ScenarioOutcome.SUCCEEDED, unsafeResult.outcome)
        assertEquals(1, unsafeHarness.sideEffects.entries.size)
    }

    @Test
    fun secretDerivedFailureIsRedactedBeforeRunLogPersistence() = runBlocking {
        val harness = AutomationScenarioHarness()
        harness.store.variables["API_TOKEN"] = "top-secret-value"
        harness.store.secretVariables += "API_TOKEN"
        val result = harness.run(
            scenarioProfile(),
            scenarioTask(
                ActionSpec(
                    type = "scenario.record",
                    args = mapOf("value" to "%API_TOKEN", "fail" to "true"),
                ),
            ),
            scenarioEvent(),
            "scenario-secret",
        )

        assertEquals(ScenarioOutcome.FAILED, result.outcome)
        assertEquals("top-secret-value", harness.sideEffects.entries.single().value)
        assertFalse(result.log?.message.orEmpty().contains("top-secret-value"))
        assertTrue(result.log?.message?.contains("<redacted>") == true)
    }

    @Test
    fun cancellationWritesTerminalEvidenceAndDoesNotCommitAFalseSuccess() {
        val harness = AutomationScenarioHarness()
        val thrown = runCatching {
            runBlocking {
                harness.run(
                    scenarioProfile(),
                    scenarioTask(ActionSpec(type = "scenario.cancel")),
                    scenarioEvent(),
                    "scenario-cancel",
                )
            }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(ExecutionJournalState.CANCELLED, harness.store.journal.row("scenario-cancel")?.state)
        assertEquals(1, harness.store.runLogs.size)
        assertFalse(harness.store.runLogs.single().success)
    }

    @Test
    fun restartReconciliationRecordsLastStepWithoutAutomaticRetry() {
        val harness = AutomationScenarioHarness()
        val task = scenarioTask(ActionSpec(type = "scenario.record", args = mapOf("value" to "would-run")))
        harness.beginInterrupted(scenarioProfile(), task, "scenario-interrupted")
        harness.clock.advanceBy(5_000L)

        val recovery = harness.recoverAfterRestart()

        assertEquals(1, recovery.interrupted)
        assertEquals(ExecutionJournalState.INTERRUPTED, harness.store.journal.row("scenario-interrupted")?.state)
        assertEquals(1, harness.store.runLogs.size)
        assertTrue(harness.store.runLogs.single().message.contains("Last known step: 1."))
        assertTrue(harness.store.runLogs.single().message.contains("no automatic retry"))
        assertTrue(harness.sideEffects.entries.isEmpty())
    }

    @Test
    fun roomMigrationScenarioCarriesExistingStateIntoTheCurrentJournalSchema() {
        val harness = AutomationScenarioHarness()
        harness.store.variables["COUNT"] = "3"

        harness.store.migrateRoomToCurrent()

        assertEquals(OPEN_TASKER_DATABASE_SCHEMA_VERSION, harness.store.roomSchemaVersion)
        assertEquals("3", harness.store.variables["COUNT"])
        assertNotNull(DatabaseMigrations.MIGRATION_14_15)
        assertEquals(14, DatabaseMigrations.MIGRATION_14_15.startVersion)
        assertEquals(15, DatabaseMigrations.MIGRATION_14_15.endVersion)
    }

    @Test
    fun unmatchedTriggerNeverAcquiresAdmissionOrWritesEvidence() = runBlocking {
        val harness = AutomationScenarioHarness()
        val result = harness.run(
            scenarioProfile(),
            scenarioTask(ActionSpec(type = "scenario.record", args = mapOf("value" to "no"))),
            scenarioEvent(matched = false),
            "scenario-unmatched",
        )

        assertEquals(ScenarioOutcome.NOT_MATCHED, result.outcome)
        assertTrue(harness.sideEffects.entries.isEmpty())
        assertTrue(harness.store.runLogs.isEmpty())
        assertNull(harness.store.journal.row("scenario-unmatched"))
    }
}
