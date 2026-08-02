package com.opentasker.core.engine

import android.content.Context

data class EngineHeartbeatSnapshot(
    val lastAliveAtMillis: Long,
    val stoppedCleanly: Boolean,
    val foregroundServiceTypes: Int = 0,
)

data class EnginePersistedHealth(
    val heartbeat: EngineHeartbeatSnapshot,
    val lastMatcherError: String?,
    val lastMatcherErrorAtMillis: Long,
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

    fun readPersistedHealth(): EnginePersistedHealth = EnginePersistedHealth(
        heartbeat = read(),
        lastMatcherError = preferences.getString(KEY_LAST_MATCHER_ERROR, null),
        lastMatcherErrorAtMillis = preferences.getLong(KEY_LAST_MATCHER_ERROR_AT, 0L),
    )

    companion object {
        internal const val STALE_AFTER_MS = 5 * 60_000L
        private const val PREFS = "engine_heartbeat"
        private const val KEY_LAST_ALIVE = "last_alive_at"
        private const val KEY_STOPPED_CLEANLY = "stopped_cleanly"
        private const val KEY_FOREGROUND_SERVICE_TYPES = "foreground_service_types"
        private const val KEY_LAST_MATCHER_ERROR = "last_matcher_error"
        private const val KEY_LAST_MATCHER_ERROR_AT = "last_matcher_error_at"
        private const val MAX_HEALTH_MESSAGE_CHARS = 1_000
    }
}

internal fun EngineHeartbeatSnapshot.needsRecovery(
    nowMillis: Long,
    staleAfterMillis: Long = EngineHeartbeatStore.STALE_AFTER_MS,
): Boolean = stoppedCleanly ||
    lastAliveAtMillis <= 0L ||
    nowMillis - lastAliveAtMillis >= staleAfterMillis
