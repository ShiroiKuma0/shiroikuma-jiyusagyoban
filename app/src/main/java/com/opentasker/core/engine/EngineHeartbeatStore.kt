package com.opentasker.core.engine

import android.content.Context

data class EngineHeartbeatSnapshot(
    val lastAliveAtMillis: Long,
    val stoppedCleanly: Boolean,
    val foregroundServiceTypes: Int = 0,
)

enum class EngineExitCorrelationState {
    MATCHED,
    NO_GAP,
    NO_MATCH,
    UNAVAILABLE,
}

/** Small, testable projection of Android's ApplicationExitInfo. */
data class HistoricalProcessExit(
    val timestampMillis: Long,
    val reason: String,
    val description: String? = null,
)

data class EngineExitCorrelation(
    val state: EngineExitCorrelationState,
    val reason: String? = null,
    val description: String? = null,
    val timestampMillis: Long? = null,
    val gapMillis: Long? = null,
)

data class EnginePersistedHealth(
    val heartbeat: EngineHeartbeatSnapshot,
    val lastMatcherError: String?,
    val lastMatcherErrorAtMillis: Long,
    val processExitCorrelation: EngineExitCorrelation? = null,
)

class EngineHeartbeatStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordAlive(
        nowMillis: Long = System.currentTimeMillis(),
        foregroundServiceTypes: Int? = null,
    ) {
        val edit = preferences.edit()
            .putLong(KEY_LAST_ALIVE, nowMillis)
            .putBoolean(KEY_STOPPED_CLEANLY, false)
        foregroundServiceTypes?.let { edit.putInt(KEY_FOREGROUND_SERVICE_TYPES, it) }
        edit.apply()
    }

    fun recordStopped(nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putLong(KEY_LAST_ALIVE, nowMillis)
            .putBoolean(KEY_STOPPED_CLEANLY, true)
            .apply()
    }

    fun read(): EngineHeartbeatSnapshot = EngineHeartbeatSnapshot(
        lastAliveAtMillis = preferences.getLong(KEY_LAST_ALIVE, 0L),
        stoppedCleanly = preferences.getBoolean(KEY_STOPPED_CLEANLY, true),
        foregroundServiceTypes = preferences.getInt(KEY_FOREGROUND_SERVICE_TYPES, 0),
    )

    fun recordMatcherError(message: String, nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putString(KEY_LAST_MATCHER_ERROR, message.take(MAX_HEALTH_MESSAGE_CHARS))
            .putLong(KEY_LAST_MATCHER_ERROR_AT, nowMillis)
            .apply()
    }

    fun recordProcessExitCorrelation(correlation: EngineExitCorrelation) {
        val edit = preferences.edit()
            .putString(KEY_PROCESS_EXIT_STATE, correlation.state.name)
            .putString(KEY_PROCESS_EXIT_REASON, correlation.reason)
            .putString(KEY_PROCESS_EXIT_DESCRIPTION, correlation.description)
            .putLong(KEY_PROCESS_EXIT_TIMESTAMP, correlation.timestampMillis ?: 0L)
            .putLong(KEY_PROCESS_EXIT_GAP, correlation.gapMillis ?: 0L)
        edit.apply()
    }

    fun readPersistedHealth(): EnginePersistedHealth = EnginePersistedHealth(
        heartbeat = read(),
        lastMatcherError = preferences.getString(KEY_LAST_MATCHER_ERROR, null),
        lastMatcherErrorAtMillis = preferences.getLong(KEY_LAST_MATCHER_ERROR_AT, 0L),
        processExitCorrelation = readProcessExitCorrelation(),
    )

    private fun readProcessExitCorrelation(): EngineExitCorrelation? {
        val state = preferences.getString(KEY_PROCESS_EXIT_STATE, null)
            ?.let { value -> runCatching { EngineExitCorrelationState.valueOf(value) }.getOrNull() }
            ?: return null
        return EngineExitCorrelation(
            state = state,
            reason = preferences.getString(KEY_PROCESS_EXIT_REASON, null),
            description = preferences.getString(KEY_PROCESS_EXIT_DESCRIPTION, null),
            timestampMillis = preferences.getLong(KEY_PROCESS_EXIT_TIMESTAMP, 0L).takeIf { it > 0L },
            gapMillis = preferences.getLong(KEY_PROCESS_EXIT_GAP, 0L).takeIf { it > 0L },
        )
    }

    companion object {
        // Public because the health reader explains staleness with it; core:engine is a module now.
        const val STALE_AFTER_MS = 5 * 60_000L
        private const val PREFS = "engine_heartbeat"
        private const val KEY_LAST_ALIVE = "last_alive_at"
        private const val KEY_STOPPED_CLEANLY = "stopped_cleanly"
        private const val KEY_FOREGROUND_SERVICE_TYPES = "foreground_service_types"
        private const val KEY_LAST_MATCHER_ERROR = "last_matcher_error"
        private const val KEY_LAST_MATCHER_ERROR_AT = "last_matcher_error_at"
        private const val KEY_PROCESS_EXIT_STATE = "process_exit_state"
        private const val KEY_PROCESS_EXIT_REASON = "process_exit_reason"
        private const val KEY_PROCESS_EXIT_DESCRIPTION = "process_exit_description"
        private const val KEY_PROCESS_EXIT_TIMESTAMP = "process_exit_timestamp"
        private const val KEY_PROCESS_EXIT_GAP = "process_exit_gap"
        private const val MAX_HEALTH_MESSAGE_CHARS = 1_000
    }
}

/**
 * Pairs the first historical process exit after the last heartbeat with an unexpected heartbeat
 * gap. Kept pure so a JVM test can supply a fake historical exit source without an Android device.
 */
internal fun correlateProcessExit(
    heartbeat: EngineHeartbeatSnapshot,
    nowMillis: Long,
    platformAvailable: Boolean,
    exits: List<HistoricalProcessExit>,
    staleAfterMillis: Long = EngineHeartbeatStore.STALE_AFTER_MS,
): EngineExitCorrelation {
    if (!platformAvailable) return EngineExitCorrelation(EngineExitCorrelationState.UNAVAILABLE)
    if (heartbeat.lastAliveAtMillis <= 0L || heartbeat.stoppedCleanly) {
        return EngineExitCorrelation(EngineExitCorrelationState.NO_GAP)
    }
    val gapMillis = nowMillis - heartbeat.lastAliveAtMillis
    if (gapMillis < staleAfterMillis) return EngineExitCorrelation(EngineExitCorrelationState.NO_GAP)

    val matchingExit = exits
        .asSequence()
        .filter { it.timestampMillis > heartbeat.lastAliveAtMillis && it.timestampMillis <= nowMillis }
        .minByOrNull(HistoricalProcessExit::timestampMillis)
        ?: return EngineExitCorrelation(
            state = EngineExitCorrelationState.NO_MATCH,
            gapMillis = gapMillis,
        )

    return EngineExitCorrelation(
        state = EngineExitCorrelationState.MATCHED,
        reason = matchingExit.reason,
        description = matchingExit.description,
        timestampMillis = matchingExit.timestampMillis,
        gapMillis = gapMillis,
    )
}

internal fun EngineHeartbeatSnapshot.needsRecovery(
    nowMillis: Long,
    staleAfterMillis: Long = EngineHeartbeatStore.STALE_AFTER_MS,
): Boolean = stoppedCleanly ||
    lastAliveAtMillis <= 0L ||
    nowMillis - lastAliveAtMillis >= staleAfterMillis
