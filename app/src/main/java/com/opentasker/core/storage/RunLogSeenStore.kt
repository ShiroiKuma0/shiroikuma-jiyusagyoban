package com.opentasker.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers the timestamp of the newest run-log failure 白い熊 has already seen (by opening the Log
 * tab). The bottom-nav Log icon shows an unread dot whenever a failure newer than this exists.
 * SharedPreferences-backed so the dot survives restarts; [init] in Application.onCreate.
 */
object RunLogSeenStore {
    private const val PREFS = "shiroikuma_runlog_seen"
    private const val K_LAST_SEEN = "last_seen_failure_ts"

    private lateinit var prefs: SharedPreferences

    private val _state = MutableStateFlow(0L)
    /** Epoch-ms of the newest failure already seen. */
    val state: StateFlow<Long> = _state.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = prefs.getLong(K_LAST_SEEN, 0L)
    }

    /** Mark every failure up to [timestamp] as seen (no-op if already current). */
    fun markSeen(timestamp: Long) {
        if (timestamp <= _state.value) return
        prefs.edit { putLong(K_LAST_SEEN, timestamp) }
        _state.value = timestamp
    }
}
