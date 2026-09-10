package com.opentasker.core.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tasks to run when the app is shut down from the top-bar overflow ("Exit app fully") — the mirror of
 * [AutoStartSettings]. Lets the user tear their own state down (overlays, bubbles, wakelocks) with their
 * master teardown task (e.g. 起動完了 ⇨ 終了) before the engine itself is stopped, so the app never has
 * to know a project name. Persisted as a tab-separated list of task NAMES, run in list order — see [taskNames].
 */

object ShutdownSettings {
    private const val PREFS = "shutdown_settings"

    /**
     * The key, and the whole point of this file.
     *
     * **Names, not ids.** A task id is a Room `autoGenerate` row number: stable inside one database
     * and meaningless outside it. This list lives in SharedPreferences — inside the device but
     * OUTSIDE the database — so it gets none of the database's referential integrity, and an id
     * here survives the row it names being deleted and re-created. That has now cost 白い熊 twice:
     * once when a restore carried `task_ids = "1511"` from a database that had numbered its rows
     * differently and the phone ran nothing at all on startup (2026-09-07), and again when
     * re-importing a task re-numbered it and left this list pointing at nothing (2026-09-10).
     *
     * The archive has carried names since the first of those. This is the other half: names on disk
     * as well, so the two agree and the id never enters the picture.
     */
    private const val KEY = "task_names"

    /** The pre-2026-09-10 key. Read once by [migrate], never written. */
    private const val LEGACY_KEY = "task_ids"

    /**
     * A tab, not a comma: task names routinely carry ` -- [727]` and commas are ordinary inside
     * them, while a tab is not a character any of 白い熊's names contains. Matches the separator
     * the archive already uses, so the stored string and the exported one are the same string.
     */
    private const val SEP = "\t"

    private val _names = MutableStateFlow<List<String>>(emptyList())
    val names: StateFlow<List<String>> = _names

    /** Direct prefs read — for the engine, whose process may not have loaded the StateFlow. */
    fun taskNames(context: Context): List<String> =
        prefs(context).getString(KEY, "").orEmpty()
            .split(SEP).map(String::trim).filter(String::isNotEmpty)

    fun load(context: Context) { _names.value = taskNames(context) }

    fun set(context: Context, names: List<String>) {
        val clean = names.map(String::trim).filter(String::isNotEmpty).distinct()
        prefs(context).edit().putString(KEY, clean.joinToString(SEP)).apply()
        _names.value = clean
    }

    fun add(context: Context, name: String) = set(context, _names.value + name)
    fun remove(context: Context, name: String) = set(context, _names.value - name)

    /**
     * Convert a pre-names install, once, resolving each id against THIS database.
     *
     * Idempotent and cheap: it returns immediately once the names key exists. An id that no longer
     * resolves is dropped rather than carried as a number nothing can look up — it was already
     * doing nothing, and keeping it would only preserve the ambiguity this change exists to end.
     */
    suspend fun migrate(context: Context, db: AppDatabase) {
        val p = prefs(context)
        if (p.contains(KEY)) return
        val ids = p.getString(LEGACY_KEY, "").orEmpty()
            .split(",").mapNotNull { it.trim().toLongOrNull() }
        val resolved = ids.mapNotNull { id -> db.taskDao().getById(id)?.name }
        p.edit().putString(KEY, resolved.joinToString(SEP)).apply()
        _names.value = resolved
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
