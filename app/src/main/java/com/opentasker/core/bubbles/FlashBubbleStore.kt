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
 * One "flash" bubble: an app whose notification is currently edge-flashing (通知明滅). Position is
 * anchored to the screen's **top + left** edges (in dp) — the mirror of the freeze bubbles' top+right —
 * so the two stacks never intermix and each keeps its relative spot across rotation / fold.
 */
@Serializable
data class FlashEntry(
    val pkg: String,
    val label: String,
    val dxFromLeftDp: Int = FlashBubbleStore.DEFAULT_LEFT_DP,
    val dyFromTopDp: Int = FlashBubbleStore.DEFAULT_TOP_DP,
)

/**
 * The whole flash-bubble layer state: the per-app bubbles plus the single kill-all icon (the app's own
 * icon; tapping it runs the kill-all task — same as tapping the flash-ongoing notification). The kill
 * icon is visible only while flashing is ongoing and is re-pinned below the lowest app bubble whenever
 * a new one arrives, so new apps stack above it and it always sits at the bottom.
 */
@Serializable
data class FlashBubbleState(
    val bubbles: List<FlashEntry> = emptyList(),
    val killVisible: Boolean = false,
    val killDxFromLeftDp: Int = FlashBubbleStore.DEFAULT_LEFT_DP,
    val killDyFromTopDp: Int = FlashBubbleStore.DEFAULT_TOP_DP + FlashBubbleStore.STACK_STEP_DP,
)

/**
 * Process-wide, SharedPreferences-backed flash-bubble state, deduped by package and surviving restarts.
 * The overlay reads [state]; the `bubble.flash_*` / `bubble.flashkill_*` actions are the producers; the
 * overlay calls [updatePosition] / [updateKillPosition] / [remove] / [hideKill]. Mirrors
 * [FreezeBubbleStore]. [init] runs once in Application.onCreate.
 */
object FlashBubbleStore {
    const val DEFAULT_LEFT_DP = 12
    const val DEFAULT_TOP_DP = 72
    const val STACK_STEP_DP = 84

    private const val PREFS_NAME = "shiroikuma_flash_bubbles"
    private const val K_STATE = "state_json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(FlashBubbleState())
    val state: StateFlow<FlashBubbleState> = _state.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _state.value = load()
    }

    /**
     * Add a bubble for [pkg] (dedup — refresh the label if it already exists). A new bubble is placed
     * below the lowest existing one, and the kill-all icon is pushed below it to stay at the bottom.
     */
    fun enqueue(pkg: String, label: String) {
        if (!::prefs.isInitialized || pkg.isBlank()) return
        val s = _state.value
        val existing = s.bubbles.firstOrNull { it.pkg == pkg }
        val bubbles = if (existing != null) {
            s.bubbles.map { if (it.pkg == pkg) it.copy(label = label) else it }
        } else {
            s.bubbles + FlashEntry(pkg = pkg, label = label, dyFromTopDp = bottomDp(s.bubbles) + STACK_STEP_DP)
        }
        commit(s.copy(bubbles = bubbles, killDyFromTopDp = bottomDp(bubbles) + STACK_STEP_DP))
    }

    /** Show the kill-all icon (idempotent), re-pinned below the lowest app bubble. */
    fun showKill() {
        if (!::prefs.isInitialized) return
        val s = _state.value
        commit(s.copy(killVisible = true, killDyFromTopDp = bottomDp(s.bubbles) + STACK_STEP_DP))
    }

    /** Hide the kill-all icon; the app bubbles stay. */
    fun hideKill() {
        if (!::prefs.isInitialized) return
        commit(_state.value.copy(killVisible = false))
    }

    fun remove(pkg: String) {
        if (!::prefs.isInitialized) return
        commit(_state.value.copy(bubbles = _state.value.bubbles.filterNot { it.pkg == pkg }))
    }

    /** Remove every app bubble and the kill-all icon (the 無効 / full-reset path). */
    fun clearAll() {
        if (!::prefs.isInitialized) return
        commit(FlashBubbleState())
    }

    fun updatePosition(pkg: String, dxFromLeftDp: Int, dyFromTopDp: Int) {
        if (!::prefs.isInitialized) return
        commit(_state.value.copy(bubbles = _state.value.bubbles.map {
            if (it.pkg == pkg) it.copy(dxFromLeftDp = dxFromLeftDp.coerceAtLeast(0), dyFromTopDp = dyFromTopDp.coerceAtLeast(0)) else it
        }))
    }

    fun updateKillPosition(dxFromLeftDp: Int, dyFromTopDp: Int) {
        if (!::prefs.isInitialized) return
        commit(_state.value.copy(killDxFromLeftDp = dxFromLeftDp.coerceAtLeast(0), killDyFromTopDp = dyFromTopDp.coerceAtLeast(0)))
    }

    /** The lowest app bubble's dy (dp), or the slot just above the default top when there are none. */
    private fun bottomDp(bubbles: List<FlashEntry>): Int =
        bubbles.maxOfOrNull { it.dyFromTopDp } ?: (DEFAULT_TOP_DP - STACK_STEP_DP)

    private fun commit(s: FlashBubbleState) {
        _state.value = s
        prefs.edit { putString(K_STATE, json.encodeToString(s)) }
    }

    private fun load(): FlashBubbleState {
        val raw = prefs.getString(K_STATE, null) ?: return FlashBubbleState()
        return runCatching { json.decodeFromString<FlashBubbleState>(raw) }.getOrDefault(FlashBubbleState())
    }
}
