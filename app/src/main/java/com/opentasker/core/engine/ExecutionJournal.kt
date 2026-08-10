package com.opentasker.core.engine

import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.ExecutionJournalEntity
import kotlin.math.max

/** Wire states stored in the durable execution journal. */
enum class ExecutionJournalState(val wireValue: String) {
    ACTIVE("ACTIVE"),
    SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED"),
    SKIPPED("SKIPPED"),
    HELD("HELD"),
    CANCELLED("CANCELLED"),
    INTERRUPTED("INTERRUPTED"),
    ;

    companion object {
        fun fromWireValue(value: String): ExecutionJournalState =
            entries.firstOrNull { it.wireValue == value } ?: INTERRUPTED
    }
}

/**
 * The durable counterpart to [ExecutionCommandLedger]. The journal deliberately stores no action
 * arguments, variable values, or arbitrary metadata. It is a recovery record, not an audit dump.
 */
internal object ExecutionJournal {
    suspend fun start(
        db: AppDatabase,
        execution: ExecutionEnvelope,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val classified = RunLogSource.classify(execution.source)
        val inserted = db.executionJournalDao().insert(
            ExecutionJournalEntity(
                executionId = execution.executionId,
                taskId = execution.taskId,
                taskName = boundedJournalText(execution.taskName) ?: "Unnamed task",
                source = boundedJournalText(execution.source) ?: "Unknown source",
                sourceLabel = boundedJournalText(classified.label),
                profileId = execution.profileId,
                replayOf = boundedJournalText(execution.replayOf),
                parentExecutionId = boundedJournalText(execution.parentExecutionId),
                producer = boundedJournalText(execution.producer.wireValue) ?: ExecutionProducer.OTHER.wireValue,
                startedAtMs = nowMs,
                updatedAtMs = nowMs,
                lastStepIndex = null,
                lastStepLabel = null,
                state = ExecutionJournalState.ACTIVE.wireValue,
                terminalReason = null,
                terminalAtMs = null,
            ),
        )
        check(inserted != -1L) { "Execution ${execution.executionId} was already journaled." }
    }

    suspend fun recordStep(
        db: AppDatabase,
        executionId: String,
        stepIndex: Int,
        stepLabel: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        db.executionJournalDao().recordStep(
            executionId = executionId,
            stepIndex = stepIndex.coerceAtLeast(0),
            stepLabel = boundedJournalText(stepLabel),
            updatedAtMs = nowMs,
        )
    }

    suspend fun markTerminal(
        db: AppDatabase,
        executionId: String,
        state: ExecutionJournalState,
        reason: ExecutionTerminalReason?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = db.executionJournalDao().markTerminal(
        executionId = executionId,
        state = state.wireValue,
        terminalReason = reason?.render()?.let(::boundedJournalText),
        terminalAtMs = nowMs,
    ) == 1

    suspend fun markRunLogWritten(
        db: AppDatabase,
        executionId: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        db.executionJournalDao().markRunLogWritten(executionId, nowMs)
    }
}

/**
 * Converts journal rows left by an earlier process into one explicit run-log row each. This never
 * retries the task: restart recovery is evidence-only and therefore safe for non-idempotent work.
 */
suspend fun reconcileExecutionJournal(
    db: AppDatabase,
    nowMs: Long = System.currentTimeMillis(),
): ExecutionJournalRecoverySummary {
    val dao = db.executionJournalDao()
    val stale = dao.active()
    var interrupted = 0
    stale.forEach { row ->
        val marked = dao.markTerminal(
            executionId = row.executionId,
            state = ExecutionJournalState.INTERRUPTED.wireValue,
            terminalReason = ExecutionTerminalReason(
                ExecutionTerminalReasonCode.ENGINE_RESTARTED,
                "Process ended before a terminal execution record was written.",
            ).render(),
            terminalAtMs = nowMs,
        )
        if (marked == 1) interrupted++
    }

    var logsWritten = 0
    dao.unloggedTerminal(MAX_RECOVERY_BATCH).forEach { row ->
        // The journal flag is set only after the insert. If the process dies between those two
        // writes, this lookup makes the next startup idempotent instead of duplicating a row.
        val existing = db.runLogDao().getByExecutionId(row.executionId)
        val inserted = existing != null || insertRunLog(db, row.toRecoveryLog(nowMs))
        if (inserted && dao.markRunLogWritten(row.executionId, nowMs) == 1) logsWritten++
    }

    val pruned = dao.pruneTerminal(MAX_RETAINED_JOURNAL_ENTRIES)
    return ExecutionJournalRecoverySummary(
        inspected = stale.size,
        interrupted = interrupted,
        logsWritten = logsWritten,
        pruned = pruned,
    )
}

data class ExecutionJournalRecoverySummary(
    val inspected: Int,
    val interrupted: Int,
    val logsWritten: Int,
    val pruned: Int,
)

internal fun ExecutionJournalEntity.toRecoveryLog(nowMs: Long): RunLogEntry {
    val journalState = ExecutionJournalState.fromWireValue(state)
    val terminalTimestamp = terminalAtMs ?: updatedAtMs.takeIf { it > 0L } ?: nowMs
    val decision = journalState.runLogDecision()
    val duration = max(0L, terminalTimestamp - startedAtMs)
    return RunLogEntry(
        taskId = taskId,
        taskName = taskName,
        timestamp = terminalTimestamp,
        durationMs = duration,
        success = journalState == ExecutionJournalState.SUCCEEDED,
        message = buildJournalRecoveryMessage(this, decision),
        source = RunLogSource.classify(source).key,
        sourceLabel = sourceLabel,
        executionId = executionId,
        replayOf = replayOf,
        held = journalState == ExecutionJournalState.HELD,
    )
}

private fun ExecutionJournalState.runLogDecision(): String = when (this) {
    ExecutionJournalState.ACTIVE -> "Interrupted"
    ExecutionJournalState.SUCCEEDED -> "Succeeded"
    ExecutionJournalState.FAILED -> "Failed"
    ExecutionJournalState.SKIPPED -> "Skipped"
    ExecutionJournalState.HELD -> "Held"
    ExecutionJournalState.CANCELLED -> "Cancelled"
    ExecutionJournalState.INTERRUPTED -> "Interrupted"
}

private fun buildJournalRecoveryMessage(row: ExecutionJournalEntity, decision: String): String = buildList {
    add("Source: ${row.source}")
    add("Decision: $decision")
    add("Reason: ${row.terminalReason ?: "The process ended before this execution was fully recorded."}")
    add("Execution ID: ${row.executionId}")
    add("Producer: ${row.producer}")
    row.profileId?.let { add("Profile ID: $it") }
    row.replayOf?.let { add("Replay of: $it") }
    row.parentExecutionId?.let { add("Parent execution ID: $it") }
    val step = row.lastStepIndex?.let { index ->
        "${index + 1}. ${row.lastStepLabel ?: "unnamed step"}"
    } ?: "none recorded"
    add("Last known step: $step")
    if (decision.equals("Interrupted", ignoreCase = true)) {
        add("Recovery: no automatic retry was attempted.")
    }
}.joinToString("\n")

private fun boundedJournalText(value: String?, maxChars: Int = 256): String? = value
    ?.replace(Regex("[\\r\\n\\t]+"), " ")
    ?.trim()
    ?.take(maxChars)
    ?.takeIf { it.isNotBlank() }

private const val MAX_RECOVERY_BATCH = 1_024
private const val MAX_RETAINED_JOURNAL_ENTRIES = 256
