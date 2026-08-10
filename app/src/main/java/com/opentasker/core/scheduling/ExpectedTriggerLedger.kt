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
            existing.expectedAtMillis <= nowMillis &&
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
            ?.takeIf { it.expectedAtMillis <= nowMillis && !it.delivered && !it.reported }
        if (deferred != null) {
            state = state.copy(deferredOverdue = null)
            return deferred.missed(nowMillis)
        }

        val current = state.current
            ?.takeIf { it.expectedAtMillis <= nowMillis && !it.delivered && !it.reported }
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

    private fun ExpectedTrigger.missed(nowMillis: Long): MissedTrigger = MissedTrigger(
        kind = kind,
        expectedAtMillis = expectedAtMillis,
        detectedAtMillis = nowMillis,
        delayMillis = (nowMillis - expectedAtMillis).coerceAtLeast(0L),
    )
}

/** SharedPreferences-backed persistence for the pure trigger ledger. */
class ExpectedTriggerLedger(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val tracker = ExpectedTriggerTracker(loadState())

    @Synchronized
    fun recordExpected(kind: ExpectedTriggerKind, expectedAtMillis: Long, nowMillis: Long) {
        tracker.recordExpected(kind, expectedAtMillis, nowMillis)
        persist()
    }

    @Synchronized
    fun markDelivered(actualAtMillis: Long) {
        tracker.markDelivered(actualAtMillis)
        persist()
    }

    @Synchronized
    fun consumeMissed(nowMillis: Long): MissedTrigger? {
        val missed = tracker.consumeMissed(nowMillis)
        if (missed != null) persist()
        return missed
    }

    @Synchronized
    fun requeue(missed: MissedTrigger) {
        tracker.requeue(missed)
        persist()
    }

    private fun loadState(): ExpectedTriggerLedgerState = ExpectedTriggerLedgerState(
        current = readTrigger(KEY_CURRENT_AT, KEY_CURRENT_KIND, KEY_CURRENT_DELIVERED, KEY_CURRENT_REPORTED),
        deferredOverdue = readTrigger(KEY_DEFERRED_AT, KEY_DEFERRED_KIND, KEY_DEFERRED_DELIVERED, KEY_DEFERRED_REPORTED),
    )

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

    private fun persist() {
        val current = tracker.snapshot().current
        val deferred = tracker.snapshot().deferredOverdue
        preferences.edit()
            .writeTrigger(KEY_CURRENT_AT, KEY_CURRENT_KIND, KEY_CURRENT_DELIVERED, KEY_CURRENT_REPORTED, current)
            .writeTrigger(KEY_DEFERRED_AT, KEY_DEFERRED_KIND, KEY_DEFERRED_DELIVERED, KEY_DEFERRED_REPORTED, deferred)
            .commit()
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

    companion object {
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
