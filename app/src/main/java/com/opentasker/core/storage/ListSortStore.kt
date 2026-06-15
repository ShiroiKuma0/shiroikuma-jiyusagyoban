package com.opentasker.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** How a tab's items are ordered. ALPHABETICAL = by name; MANUAL = by the saved per-item position. */
@Serializable
enum class SortMethod { ALPHABETICAL, MANUAL }

/** The orderable tabs that carry their own sort method. */
enum class SortTab { PROFILES, TASKS, SCENES }

data class SortPrefs(
    val profiles: SortMethod = SortMethod.ALPHABETICAL,
    val tasks: SortMethod = SortMethod.ALPHABETICAL,
    val scenes: SortMethod = SortMethod.ALPHABETICAL,
) {
    fun of(tab: SortTab): SortMethod = when (tab) {
        SortTab.PROFILES -> profiles
        SortTab.TASKS -> tasks
        SortTab.SCENES -> scenes
    }

    fun with(tab: SortTab, method: SortMethod): SortPrefs = when (tab) {
        SortTab.PROFILES -> copy(profiles = method)
        SortTab.TASKS -> copy(tasks = method)
        SortTab.SCENES -> copy(scenes = method)
    }
}

/**
 * Per-tab sort-method preference (Alphabetical vs Manual), SharedPreferences-backed and exposed as a
 * [StateFlow] so the lists re-sort live. [init] runs once in Application.onCreate. Default is
 * ALPHABETICAL, which preserves the app's prior always-sorted-by-name behaviour.
 */
object ListSortStore {
    private const val PREFS_NAME = "shiroikuma_list_sort"
    private const val K_PROFILES = "profiles"
    private const val K_TASKS = "tasks"
    private const val K_SCENES = "scenes"

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(SortPrefs())
    val state: StateFlow<SortPrefs> = _state.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _state.value = load()
    }

    fun set(tab: SortTab, method: SortMethod) = persistAndPublish(_state.value.with(tab, method))

    /** Replace all tabs at once (used when an imported bundle carries sort methods). */
    fun setAll(prefs: SortPrefs) = persistAndPublish(prefs)

    private fun persistAndPublish(next: SortPrefs) {
        if (::prefs.isInitialized) {
            prefs.edit {
                putString(K_PROFILES, next.profiles.name)
                putString(K_TASKS, next.tasks.name)
                putString(K_SCENES, next.scenes.name)
            }
        }
        _state.value = next
    }

    private fun load(): SortPrefs = SortPrefs(
        profiles = readMethod(K_PROFILES),
        tasks = readMethod(K_TASKS),
        scenes = readMethod(K_SCENES),
    )

    private fun readMethod(key: String): SortMethod =
        runCatching { SortMethod.valueOf(prefs.getString(key, null) ?: return SortMethod.ALPHABETICAL) }
            .getOrDefault(SortMethod.ALPHABETICAL)
}
