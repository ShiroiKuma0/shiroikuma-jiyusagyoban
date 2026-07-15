package com.opentasker.core.engine

import android.content.Context

data class EngineHeartbeat(
    val lastAliveAtMillis: Long,
    val stoppedCleanly: Boolean,
)

class EngineHeartbeatStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordAlive(nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putLong(KEY_LAST_ALIVE, nowMillis)
            .putBoolean(KEY_STOPPED_CLEANLY, false)
            .apply()
    }

    fun recordStopped(nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putLong(KEY_LAST_ALIVE, nowMillis)
            .putBoolean(KEY_STOPPED_CLEANLY, true)
            .apply()
    }

    fun read(): EngineHeartbeat = EngineHeartbeat(
        lastAliveAtMillis = preferences.getLong(KEY_LAST_ALIVE, 0L),
        stoppedCleanly = preferences.getBoolean(KEY_STOPPED_CLEANLY, true),
    )

    companion object {
        internal const val STALE_AFTER_MS = 5 * 60_000L
        private const val PREFS = "engine_heartbeat"
        private const val KEY_LAST_ALIVE = "last_alive_at"
        private const val KEY_STOPPED_CLEANLY = "stopped_cleanly"
    }
}

internal fun EngineHeartbeat.needsRecovery(
    nowMillis: Long,
    staleAfterMillis: Long = EngineHeartbeatStore.STALE_AFTER_MS,
): Boolean = stoppedCleanly ||
    lastAliveAtMillis <= 0L ||
    nowMillis - lastAliveAtMillis >= staleAfterMillis
