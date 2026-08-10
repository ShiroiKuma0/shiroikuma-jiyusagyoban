package com.opentasker.core.engine

import com.opentasker.core.model.Task
import java.util.UUID

/** Stable producer identity for every way a task can enter the execution engine. */
enum class ExecutionProducer(val wireValue: String) {
    PROFILE("profile"),
    MANUAL("manual"),
    WIDGET("widget"),
    SHORTCUT("shortcut"),
    QUICK_SETTINGS("quick_settings"),
    NOTIFICATION("notification"),
    EXTERNAL("external"),
    LOCALE_PLUGIN("locale_plugin"),
    SCENE_OVERLAY("scene_overlay"),
    WORKER("worker"),
    OTHER("other"),
    ;

    companion object {
        fun fromSource(source: String): ExecutionProducer {
            val value = source.trim()
            return when {
                value.startsWith("Profile:", ignoreCase = true) -> PROFILE
                value.startsWith("Quick Settings Tile:", ignoreCase = true) -> QUICK_SETTINGS
                value.equals("Manual run", ignoreCase = true) || value.equals("Manual", ignoreCase = true) -> MANUAL
                value.equals("Widget", ignoreCase = true) -> WIDGET
                value.equals("Shortcut", ignoreCase = true) -> SHORTCUT
                value.equals("Notification action", ignoreCase = true) -> NOTIFICATION
                value.equals("Locale plugin", ignoreCase = true) ||
                    value.equals("locale_plugin", ignoreCase = true) -> LOCALE_PLUGIN
                value.equals("Scene overlay", ignoreCase = true) ||
                    value.equals("scene_overlay", ignoreCase = true) -> SCENE_OVERLAY
                value.equals("External intent", ignoreCase = true) -> EXTERNAL
                value.equals("Worker", ignoreCase = true) -> WORKER
                else -> OTHER
            }
        }

        fun fromWireValue(value: String?): ExecutionProducer =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: OTHER
    }
}

/** Machine-readable terminal outcome reason, with an optional safe human detail. */
enum class ExecutionTerminalReasonCode {
    COMPLETED,
    TASK_FAILED,
    ADMISSION_REJECTED,
    COLLISION_SKIPPED,
    CAUSAL_LOOP,
    MISSED_TRIGGER,
    CANCELLED,
    TASK_NOT_FOUND,
    TASK_CORRUPT,
    SERVICE_START_FAILED,
    ENGINE_RESTARTED,
    DUPLICATE_DELIVERY,
    UNKNOWN,
}

data class ExecutionTerminalReason(
    val code: ExecutionTerminalReasonCode,
    val detail: String? = null,
) {
    fun render(): String = buildString {
        append(code.name)
        detail?.let {
            val safe = it.replace(Regex("[\\r\\n]+"), " ").trim().take(MAX_DETAIL_CHARS)
            if (safe.isNotBlank()) append(": ").append(safe)
        }
    }

    companion object {
        private const val MAX_DETAIL_CHARS = 256
    }
}

/**
 * The canonical command identity carried from an admission point through execution and logging.
 * The id is deliberately independent of the numeric active-run id, which is only process-local.
 */
data class ExecutionEnvelope(
    val executionId: String,
    val producer: ExecutionProducer,
    val taskId: Long,
    val taskName: String,
    val source: String,
    val profileId: Long? = null,
    val parentExecutionId: String? = null,
    val causalDepth: Int = 0,
    val causalProfileChain: List<String> = emptyList(),
    val createdAtMs: Long,
) {
    init {
        require(isValidExecutionId(executionId)) { "Invalid execution id." }
        require(causalDepth >= 0) { "Causal depth cannot be negative." }
        require(causalProfileChain.size <= MAX_CAUSAL_PROFILE_CHAIN_LENGTH) {
            "Causal profile chain is too long."
        }
    }

    fun metadataLines(): List<String> = buildList {
        add("Execution ID: $executionId")
        add("Producer: ${producer.wireValue}")
        profileId?.let { add("Profile ID: $it") }
        parentExecutionId?.let { add("Parent execution ID: $it") }
        if (causalDepth > 0 || causalProfileChain.isNotEmpty()) add("Causal depth: $causalDepth")
        if (causalProfileChain.isNotEmpty()) {
            add("Causal profile chain: ${causalProfileChain.joinToString(" -> ")}")
        }
    }

    companion object {
        private val idPattern = Regex("^[A-Za-z0-9._:-]{1,128}$")

        fun create(
            task: Task,
            source: String,
            profileId: Long? = null,
            parentExecutionId: String? = null,
            causalDepth: Int = 0,
            causalProfileChain: List<String> = emptyList(),
            executionId: String = UUID.randomUUID().toString(),
            nowMs: Long = System.currentTimeMillis(),
        ): ExecutionEnvelope = ExecutionEnvelope(
            executionId = executionId,
            producer = ExecutionProducer.fromSource(source),
            taskId = task.id,
            taskName = task.name,
            source = source.trim(),
            profileId = profileId,
            parentExecutionId = parentExecutionId,
            causalDepth = causalDepth,
            causalProfileChain = causalProfileChain,
            createdAtMs = nowMs,
        )

        fun isValidExecutionId(value: String?): Boolean = value != null && idPattern.matches(value.trim())

        private const val MAX_CAUSAL_PROFILE_CHAIN_LENGTH = 32
    }
}

enum class ExecutionLedgerState {
    ACCEPTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED,
    ;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED
}

data class ExecutionLedgerRecord(
    val envelope: ExecutionEnvelope,
    val state: ExecutionLedgerState,
    val acceptedAtMs: Long,
    val updatedAtMs: Long,
    val terminalReason: ExecutionTerminalReason? = null,
)

data class ExecutionLedgerAcceptance(
    val record: ExecutionLedgerRecord,
    val isNew: Boolean,
)

/** Bounded process-local event ledger used to make delivery idempotent and transitions observable. */
class ExecutionLedger(private val capacity: Int = DEFAULT_CAPACITY) {
    private val records = LinkedHashMap<String, ExecutionLedgerRecord>()

    fun accept(envelope: ExecutionEnvelope, nowMs: Long = envelope.createdAtMs): ExecutionLedgerAcceptance =
        synchronized(records) {
            records[envelope.executionId]?.let { return@synchronized ExecutionLedgerAcceptance(it, false) }
            val record = ExecutionLedgerRecord(
                envelope = envelope,
                state = ExecutionLedgerState.ACCEPTED,
                acceptedAtMs = nowMs,
                updatedAtMs = nowMs,
            )
            records[envelope.executionId] = record
            evictOverflow()
            ExecutionLedgerAcceptance(record, true)
        }

    fun transition(
        executionId: String,
        state: ExecutionLedgerState,
        reason: ExecutionTerminalReason? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): ExecutionLedgerRecord? = synchronized(records) {
        val existing = records[executionId] ?: return@synchronized null
        if (existing.state.isTerminal) return@synchronized existing
        val updated = existing.copy(
            state = state,
            updatedAtMs = nowMs,
            terminalReason = if (state.isTerminal) reason else null,
        )
        records[executionId] = updated
        updated
    }

    fun get(executionId: String): ExecutionLedgerRecord? = synchronized(records) { records[executionId] }

    fun snapshot(): List<ExecutionLedgerRecord> = synchronized(records) { records.values.toList() }

    internal fun reset() = synchronized(records) { records.clear() }

    private fun evictOverflow() {
        while (records.size > capacity) records.remove(records.keys.firstOrNull() ?: return)
    }

    companion object {
        const val DEFAULT_CAPACITY = 128
    }
}

/** One process-wide ledger covers manual, profile, widget, notification, and external delivery. */
object ExecutionCommandLedger {
    private val ledger = ExecutionLedger()

    fun accept(envelope: ExecutionEnvelope): ExecutionLedgerAcceptance = ledger.accept(envelope)

    fun transition(
        executionId: String,
        state: ExecutionLedgerState,
        reason: ExecutionTerminalReason? = null,
    ): ExecutionLedgerRecord? = ledger.transition(executionId, state, reason)

    internal fun reset() = ledger.reset()
}
