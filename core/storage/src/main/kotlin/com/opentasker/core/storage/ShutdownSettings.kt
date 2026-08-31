package com.opentasker.core.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tasks to run when the app is shut down from the top-bar overflow ("Exit app fully") — the mirror of
 * [AutoStartSettings]. Lets the user tear their own state down (overlays, bubbles, wakelocks) with their
 * master teardown task (e.g. 起動完了 ⇨ 終了) before the engine itself is stopped, so the app never has
 * to know a project name. Persisted as a comma-separated list of task ids, run in list order.
 */
object ShutdownSettings {
    private const val PREFS = "shutdown_settings"
    private const val KEY = "task_ids"

    private val _ids = MutableStateFlow<List<Long>>(emptyList())
    val ids: StateFlow<List<Long>> = _ids

    /** Direct prefs read — for the shutdown sequence (the StateFlow may not be loaded yet). */
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
