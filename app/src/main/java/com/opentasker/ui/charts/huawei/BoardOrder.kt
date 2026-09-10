package com.opentasker.ui.charts.huawei

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The order 白い熊 has put the 健康 board's tiles in.
 *
 * ## Why it is stored by KEY and merged rather than replaced
 *
 * The stored list names tiles; [BOARD_TILES] defines them. Those two drift every time a tile is
 * added, and the merge below is what decides what happens then: anything stored keeps its place,
 * anything new lands at the END, and anything stored that no longer exists is ignored. A stored
 * order that simply replaced the list would make a tile added in a later version invisible — the
 * board would silently lose a feature on upgrade, and nothing would say why.
 *
 * Device-local, like every other arrangement preference, and carried in the 健康 export so a
 * restored phone comes back with the board as it was rather than in factory order.
 */
object BoardOrder {
    private const val PREFS = "huawei_board"
    private const val KEY = "tile_order"

    /** A tab: tile keys are plain identifiers, but a separator that can never occur in one is free. */
    private const val SEP = "\t"

    private val _keys = MutableStateFlow<List<String>>(emptyList())
    val keys: StateFlow<List<String>> = _keys

    fun load(context: Context) {
        _keys.value = prefs(context).getString(KEY, "").orEmpty()
            .split(SEP).map(String::trim).filter(String::isNotEmpty)
    }

    fun set(context: Context, keys: List<String>) {
        val clean = keys.distinct()
        prefs(context).edit().putString(KEY, clean.joinToString(SEP)).apply()
        _keys.value = clean
    }

    /** [tiles] in the stored order, with anything unknown to the store appended in its own order. */
    fun apply(tiles: List<BoardTile>, stored: List<String>): List<BoardTile> {
        if (stored.isEmpty()) return tiles
        val byKey = tiles.associateBy { it.key }
        val ordered = stored.mapNotNull(byKey::get)
        return ordered + tiles.filterNot { it.key in stored }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
