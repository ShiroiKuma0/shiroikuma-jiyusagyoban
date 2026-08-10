package com.opentasker.core.scheduling

import android.content.Context

enum class ExpectedTriggerKind(val wireValue: String) {
    MINUTE_TICK("minute_tick"),
    RECOVERY("recovery"),
}

data class ExpectedTrigger(
    val kind: ExpectedTriggerKind,
    val expectedAtMillis: Long,
    val delivered: Boolean = false,
    val reported: Boolean = false,
)

data class ExpectedTriggerLedgerState(
    val current: ExpectedTrigger? = null,
    val deferredOverdue: ExpectedTrigger? = null,
)

data class MissedTrigger(
    val kind: ExpectedTriggerKind,
    val expectedAtMillis: Long,
    val detectedAtMillis: Long,
    val delayMillis: Long,
)

/** One full minute-tick interval of slack before a tick counts as dropped. */
private const val OVERDUE_GRACE_MILLIS = 60_000L

/** Pure state machine behind the persisted expected-fire ledger. */
class ExpectedTriggerTracker(initialState: ExpectedTriggerLedgerState = ExpectedTriggerLedgerState()) {
    private var state = initialState

    fun recordExpected(
        kind: ExpectedTriggerKind,
        expectedAtMillis: Long,
        nowMillis: Long,
    ) {
        require(expectedAtMillis > 0L) { "Expected trigger time must be positive." }
        val existing = state.current
        if (existing?.kind == kind && existing.expectedAtMillis == expectedAtMillis) return
        val deferred = if (
            existing != null &&
            existing.isOverdueAt(nowMillis) &&
            !existing.delivered &&
            !existing.reported
        ) {
            state.deferredOverdue ?: existing
        } else {
            state.deferredOverdue
        }
        state = ExpectedTriggerLedgerState(
            current = ExpectedTrigger(kind, expectedAtMillis),
            deferredOverdue = deferred,
        )
    }

    fun markDelivered(actualAtMillis: Long) {
        val existing = state.current ?: return
        if (existing.expectedAtMillis > actualAtMillis || existing.reported) return
        state = state.copy(current = existing.copy(delivered = true))
    }

    fun consumeMissed(nowMillis: Long): MissedTrigger? {
        val deferred = state.deferredOverdue
            ?.takeIf { it.isOverdueAt(nowMillis) && !it.delivered && !it.reported }
        if (deferred != null) {
            state = state.copy(deferredOverdue = null)
            return deferred.missed(nowMillis)
        }

        val current = state.current
            ?.takeIf { it.isOverdueAt(nowMillis) && !it.delivered && !it.reported }
            ?: return null
        state = state.copy(current = current.copy(reported = true))
        return current.missed(nowMillis)
    }

    fun requeue(missed: MissedTrigger) {
        val current = state.current
        if (current?.expectedAtMillis == missed.expectedAtMillis && current.kind == missed.kind) {
            state = state.copy(current = current.copy(reported = false, delivered = false))
        } else if (state.deferredOverdue == null) {
            state = state.copy(
                deferredOverdue = ExpectedTrigger(
                    kind = missed.kind,
                    expectedAtMillis = missed.expectedAtMillis,
                ),
            )
        }
    }

    fun snapshot(): ExpectedTriggerLedgerState = state

    /**
     * A trigger is only missed once the following tick should already have arrived. Delivery takes
     * a moment after the alarm time, so treating `expectedAt == now` as overdue reported a healthy
     * tick as missed whenever the watchdog happened to run in that gap.
     */
    private fun ExpectedTrigger.isOverdueAt(nowMillis: Long): Boolean =
        expectedAtMillis + OVERDUE_GRACE_MILLIS <= nowMillis

    private fun ExpectedTrigger.missed(nowMillis: Long): MissedTrigger = MissedTrigger(
        kind = kind,
        expectedAtMillis = expectedAtMillis,
        detectedAtMillis = nowMillis,
        delayMillis = (nowMillis - expectedAtMillis).coerceAtLeast(0L),
    )
}

/** Durable state of record for the ledger; the pure tracker is rebuilt from it on every use. */
interface ExpectedTriggerStateStore {
    fun load(): ExpectedTriggerLedgerState
    fun save(state: ExpectedTriggerLedgerState)
}

/** In-memory store used by tests; mirrors the persistence contract without Android. */
class InMemoryExpectedTriggerStateStore : ExpectedTriggerStateStore {
    private var state = ExpectedTriggerLedgerState()

    override fun load(): ExpectedTriggerLedgerState = synchronized(this) { state }

    override fun save(state: ExpectedTriggerLedgerState) = synchronized(this) { this.state = state }
}

/**
 * Persistence wrapper around the pure trigger ledger.
 *
 * The store - not any in-memory copy - is the state of record. The scheduler owned by the
 * long-lived service, the per-tick broadcast receiver, and the watchdog worker each construct
 * their own ledger over the same file, so every operation reloads before it mutates and all of
 * them share one process-wide lock. Caching the tracker per instance made the service's copy miss
 * the receiver's "delivered" write and re-file each healthy tick as an overdue missed trigger.
 */
class ExpectedTriggerLedger(private val store: ExpectedTriggerStateStore) {
    constructor(context: Context) : this(SharedPreferencesExpectedTriggerStateStore(context))

    fun recordExpected(kind: ExpectedTriggerKind, expectedAtMillis: Long, nowMillis: Long) =
        mutate { it.recordExpected(kind, expectedAtMillis, nowMillis) }

    fun markDelivered(actualAtMillis: Long) = mutate { it.markDelivered(actualAtMillis) }

    fun consumeMissed(nowMillis: Long): MissedTrigger? =
        mutate(persistWhen = { missed -> missed != null }) { it.consumeMissed(nowMillis) }

    fun requeue(missed: MissedTrigger) = mutate { it.requeue(missed) }

    private fun <T> mutate(persistWhen: (T) -> Boolean = { true }, block: (ExpectedTriggerTracker) -> T): T =
        synchronized(FILE_LOCK) {
            val tracker = ExpectedTriggerTracker(store.load())
            val result = block(tracker)
            if (persistWhen(result)) store.save(tracker.snapshot())
            result
        }

    companion object {
        /** Shared across instances: three call sites construct their own ledger over one file. */
        private val FILE_LOCK = Any()
    }
}

/** SharedPreferences-backed [ExpectedTriggerStateStore]. */
class SharedPreferencesExpectedTriggerStateStore(context: Context) : ExpectedTriggerStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): ExpectedTriggerLedgerState = ExpectedTriggerLedgerState(
        current = readTrigger(KEY_CURRENT_AT, KEY_CURRENT_KIND, KEY_CURRENT_DELIVERED, KEY_CURRENT_REPORTED),
        deferredOverdue = readTrigger(KEY_DEFERRED_AT, KEY_DEFERRED_KIND, KEY_DEFERRED_DELIVERED, KEY_DEFERRED_REPORTED),
    )

    override fun save(state: ExpectedTriggerLedgerState) {
        preferences.edit()
            .writeTrigger(KEY_CURRENT_AT, KEY_CURRENT_KIND, KEY_CURRENT_DELIVERED, KEY_CURRENT_REPORTED, state.current)
            .writeTrigger(KEY_DEFERRED_AT, KEY_DEFERRED_KIND, KEY_DEFERRED_DELIVERED, KEY_DEFERRED_REPORTED, state.deferredOverdue)
            .commit()
    }

    private fun readTrigger(
        atKey: String,
        kindKey: String,
        deliveredKey: String,
        reportedKey: String,
    ): ExpectedTrigger? {
        val atMillis = preferences.getLong(atKey, 0L).takeIf { it > 0L } ?: return null
        val kind = preferences.getString(kindKey, null)
            ?.let { value -> ExpectedTriggerKind.entries.firstOrNull { it.wireValue == value } }
            ?: return null
        return ExpectedTrigger(
            kind = kind,
            expectedAtMillis = atMillis,
            delivered = preferences.getBoolean(deliveredKey, false),
            reported = preferences.getBoolean(reportedKey, false),
        )
    }

    private fun android.content.SharedPreferences.Editor.writeTrigger(
        atKey: String,
        kindKey: String,
        deliveredKey: String,
        reportedKey: String,
        trigger: ExpectedTrigger?,
    ): android.content.SharedPreferences.Editor = if (trigger == null) {
        remove(atKey).remove(kindKey).remove(deliveredKey).remove(reportedKey)
    } else {
        putLong(atKey, trigger.expectedAtMillis)
            .putString(kindKey, trigger.kind.wireValue)
            .putBoolean(deliveredKey, trigger.delivered)
            .putBoolean(reportedKey, trigger.reported)
    }

    private companion object {
        private const val PREFS = "expected_trigger_ledger"
        private const val KEY_CURRENT_AT = "current_at"
        private const val KEY_CURRENT_KIND = "current_kind"
        private const val KEY_CURRENT_DELIVERED = "current_delivered"
        private const val KEY_CURRENT_REPORTED = "current_reported"
        private const val KEY_DEFERRED_AT = "deferred_at"
        private const val KEY_DEFERRED_KIND = "deferred_kind"
        private const val KEY_DEFERRED_DELIVERED = "deferred_delivered"
        private const val KEY_DEFERRED_REPORTED = "deferred_reported"
    }
}
