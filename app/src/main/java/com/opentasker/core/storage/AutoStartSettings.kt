package com.opentasker.core.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tasks to run automatically when the engine starts — after an app update, a reboot, or a heartbeat
 * resurrect. Lets the user re-establish their overlays/state (e.g. pick their master "起動完了 ⇨ 起動")
 * without manually running it each time. Persisted as a comma-separated list of task ids.
 */
object AutoStartSettings {
    private const val PREFS = "auto_start_settings"
    private const val KEY = "task_ids"

    private val _ids = MutableStateFlow<List<Long>>(emptyList())
    val ids: StateFlow<List<Long>> = _ids

    /** Direct prefs read — for the engine on startup (the StateFlow may not be loaded in its process). */
    fun taskIds(context: Context): List<Long> =
        prefs(context).getString(KEY, "").orEmpty()
            .split(",").mapNotNull { it.trim().toLongOrNull() }

    fun load(context: Context) { _ids.value = taskIds(context) }

    fun set(context: Context, ids: List<Long>) {
        val clean = ids.distinct()
        prefs(context).edit().putString(KEY, clean.joinToString(",")).apply()
        _ids.value = clean
    }

    fun add(context: Context, id: Long) = set(context, _ids.value + id)
    fun remove(context: Context, id: Long) = set(context, _ids.value - id)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
