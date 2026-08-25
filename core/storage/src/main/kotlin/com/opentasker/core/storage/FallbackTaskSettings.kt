package com.opentasker.core.storage

import android.content.Context
import androidx.core.content.edit

/** Per-install fallback task configuration; the global setting is intentionally not bundled. */
class FallbackTaskSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadTaskId(): Long? = prefs.getLong(KEY_TASK_ID, 0L).takeIf { it > 0L }

    fun saveTaskId(taskId: Long?) {
        prefs.edit {
            if (taskId != null && taskId > 0L) putLong(KEY_TASK_ID, taskId) else remove(KEY_TASK_ID)
        }
    }

    companion object {
        private const val PREFS_NAME = "fallback_task_settings"
        private const val KEY_TASK_ID = "global_fallback_task_id"
    }
}
