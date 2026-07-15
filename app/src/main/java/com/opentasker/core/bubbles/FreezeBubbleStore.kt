package com.opentasker.core.bubbles

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One pending "freeze" bubble: an app that was thawed-and-launched by a freeze-enabled task and is now a
 * candidate for re-freezing. Position is anchored to the screen's **top + right** edges (in dp) so it
 * keeps its relative spot across rotation / fold / geometry changes.
 */
@Serializable
data class BubbleEntry(
    val pkg: String,
    val label: String,
    val iconPath: String? = null,
    val dxFromRightDp: Int = DEFAULT_RIGHT_DP,
    val dyFromTopDp: Int = DEFAULT_TOP_DP,
) {
    companion object {
        const val DEFAULT_RIGHT_DP = 12
        const val DEFAULT_TOP_DP = 72
        const val STACK_STEP_DP = 84
    }
}

/**
 * Process-wide, SharedPreferences-backed set of pending freeze bubbles, deduped by package and surviving
 * restarts. UI/overlay reads [bubbles]; producers call [enqueue]; the overlay calls [updatePosition] /
 * [remove]. Mirrors the ThemeStore / TemplateStore pattern. [init] runs once in Application.onCreate.
 */
object FreezeBubbleStore {
    private const val PREFS_NAME = "shiroikuma_freeze_bubbles"
    private const val K_BUBBLES = "bubbles_json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var prefs: SharedPreferences
    private val _bubbles = MutableStateFlow<List<BubbleEntry>>(emptyList())
    val bubbles: StateFlow<List<BubbleEntry>> = _bubbles.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _bubbles.value = load()
    }

    /** Add a bubble for [pkg] (dedup — refresh label/icon if it already exists), stacked down the right edge. */
    fun enqueue(pkg: String, label: String, iconPath: String?) {
        if (!::prefs.isInitialized || pkg.isBlank()) return
        val current = _bubbles.value
        val existing = current.firstOrNull { it.pkg == pkg }
        val next = if (existing != null) {
            current.map { if (it.pkg == pkg) it.copy(label = label, iconPath = iconPath ?: it.iconPath) else it }
        } else {
            current + BubbleEntry(
                pkg = pkg,
                label = label,
                iconPath = iconPath,
                dxFromRightDp = BubbleEntry.DEFAULT_RIGHT_DP,
                dyFromTopDp = BubbleEntry.DEFAULT_TOP_DP + current.size * BubbleEntry.STACK_STEP_DP,
            )
        }
        commit(next)
    }

    fun updatePosition(pkg: String, dxFromRightDp: Int, dyFromTopDp: Int) {
        if (!::prefs.isInitialized) return
        commit(_bubbles.value.map {
            if (it.pkg == pkg) it.copy(dxFromRightDp = dxFromRightDp.coerceAtLeast(0), dyFromTopDp = dyFromTopDp.coerceAtLeast(0)) else it
        })
    }

    fun remove(pkg: String) {
        if (!::prefs.isInitialized) return
        commit(_bubbles.value.filterNot { it.pkg == pkg })
    }

    fun clearAll() {
        if (!::prefs.isInitialized) return
        commit(emptyList())
    }

    private fun commit(list: List<BubbleEntry>) {
        _bubbles.value = list
        prefs.edit { putString(K_BUBBLES, json.encodeToString(list)) }
    }

    private fun load(): List<BubbleEntry> {
        val raw = prefs.getString(K_BUBBLES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<BubbleEntry>>(raw) }.getOrDefault(emptyList())
    }
}
