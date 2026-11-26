package com.sysadmindoc.ruledeck.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.sysadmindoc.ruledeck.i18n.tr

/**
 * "Lift, float, drop" reorder state for a scrolling column of variable-height rows. The dragged row is
 * translated visually while the others stay put; on release [targetKey] resolves the drop slot from
 * the rows' measured places, so it works regardless of row heights.
 *
 * [delta] is the row's travel in CONTENT coordinates: the finger's movement plus whatever the box has
 * auto-scrolled meanwhile, which is what keeps the row under the finger while the list moves beneath it.
 */
private class ListReorderState {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    var delta by mutableFloatStateOf(0f)
        private set

    /** Each row's top and height in the column's content coordinates — untouched by the scroll. */
    val places = HashMap<Any, Pair<Float, Int>>()

    fun start(key: Any?) { draggingKey = key; delta = 0f }
    fun drag(amountY: Float) { delta += amountY }
    fun reset() { draggingKey = null; delta = 0f }

    /** The key of the row the dragged one sits over; past either end it is the first or last row. */
    fun targetKey(): Any? {
        val (top, height) = places[draggingKey ?: return null] ?: return null
        val center = top + delta + height / 2f
        places.entries.firstOrNull { (_, p) -> center >= p.first && center <= p.first + p.second }?.let { return it.key }
        val first = places.minByOrNull { it.value.first } ?: return null
        return if (center < first.value.first) first.key else places.maxByOrNull { it.value.first }?.key
    }
}

/**
 * A scrolling column whose rows are reordered by their leading ⠿ handle. A touch that lands on a
 * handle picks the row up AT ONCE — no long-press (白い熊, 2026-11-20) — and while the row is held at
 * the top or bottom edge of the box, the box scrolls under it, so a row can travel past the rows that
 * were visible when it was picked up. On release the new order goes to [onReorder]; persisting it is
 * the caller's job.
 *
 * A plain [Column], not a LazyColumn, on purpose: a lazy list disposes a row that scrolls out of
 * view, and with it the gesture that is dragging it. Meant for short lists (the islands).
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    keyOf: (T) -> Any,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (T) -> Unit,
) {
    val state = remember { ListReorderState() }
    val scroll = rememberScrollState()
    val currentItems by rememberUpdatedState(items)
    val currentOnReorder by rememberUpdatedState(onReorder)

    // A row deleted mid-drag must not leave the state holding a key nothing will ever release.
    LaunchedEffect(items) {
        val keys = items.map(keyOf).toSet()
        state.places.keys.retainAll(keys)
        if (state.draggingKey != null && state.draggingKey !in keys) state.reset()
    }
    AutoScrollWhileDragging(state, scroll)

    Column(modifier.verticalScroll(scroll)) {
        items.forEach { item ->
            val key = keyOf(item)
            androidx.compose.runtime.key(key) {
                val dragging = state.draggingKey == key
                // The place is measured on this outer box, outside the layer that floats the row.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (dragging) 1f else 0f)
                        .onGloballyPositioned { state.places[key] = it.positionInParent().y to it.size.height },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { translationY = if (dragging) state.delta else 0f }
                            .alpha(if (dragging) 0.92f else 1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Box(
                            Modifier
                                .defaultMinSize(minWidth = 36.dp, minHeight = 48.dp)
                                .pointerInput(key) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        state.start(key)
                                        // Every move is consumed, so the box's own scrolling never takes the gesture.
                                        val released = drag(down.id) { change ->
                                            state.drag(change.positionChange().y)
                                            change.consume()
                                        }
                                        if (released) {
                                            val list = currentItems
                                            val from = list.indexOfFirst { keyOf(it) == key }
                                            val target = state.targetKey()
                                            val to = list.indexOfFirst { keyOf(it) == target }
                                            if (from >= 0 && to >= 0 && to != from) {
                                                currentOnReorder(list.toMutableList().apply { add(to, removeAt(from)) })
                                            }
                                        }
                                        state.reset()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.DragIndicator,
                                contentDescription = tr("Drag to reorder"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(Modifier.weight(1f)) { row(item) }
                    }
                }
            }
        }
    }
}

/**
 * While a row is held, scrolls the box whenever the row's top is at or above the box's top edge, or
 * its bottom at or below the bottom edge — faster the further the finger has pushed past the edge.
 * What the box actually scrolled is added to the row's travel, so the row stays under the finger and
 * stops with the box at either end of the list.
 */
@Composable
private fun AutoScrollWhileDragging(state: ListReorderState, scroll: ScrollState) {
    val key = state.draggingKey ?: return
    val density = LocalDensity.current
    LaunchedEffect(key) {
        val base = with(density) { 240.dp.toPx() }      // px/s the moment the edge is reached
        val max = with(density) { 1400.dp.toPx() }
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1e9f
            last = now
            val (top, height) = state.places[key] ?: continue
            val visualTop = top + state.delta - scroll.value
            val bottomOver = visualTop + height - scroll.viewportSize
            val (direction, over) = when {
                visualTop <= 0f -> -1f to -visualTop
                bottomOver >= 0f -> 1f to bottomOver
                else -> continue
            }
            val speed = (base + over * 8f).coerceAtMost(max)
            state.drag(scroll.scrollBy(direction * speed * dt))
        }
    }
}
