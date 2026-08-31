package com.opentasker.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Minimal "lift, float, drop" reorder state for a LazyColumn of variable-height rows. The dragged row
 * is translated visually while the others stay put; on release [targetIndex] resolves the drop slot
 * from the current layout, so it works regardless of row heights. Persisting the move is the caller's
 * job — it receives the new order and hands it to the repository.
 */
class ListReorderState {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    var delta by mutableFloatStateOf(0f)
        private set

    fun start(key: Any?) { draggingKey = key; delta = 0f }
    fun drag(amountY: Float) { delta += amountY }
    fun reset() { draggingKey = null; delta = 0f }

    /**
     * The KEY of the row the dragged item would drop onto given the current layout, or null. Keyed
     * (not indexed) so it's correct even when the list has header items before the reorderable rows.
     */
    fun targetKey(listState: LazyListState): Any? {
        val key = draggingKey ?: return null
        val info = listState.layoutInfo.visibleItemsInfo
        val dragged = info.firstOrNull { it.key == key } ?: return null
        val center = dragged.offset + delta + dragged.size / 2f
        return info.firstOrNull { center.toInt() in it.offset..(it.offset + it.size) }?.key
    }
}

@Composable
fun rememberListReorderState(): ListReorderState = remember { ListReorderState() }

/**
 * Wraps a list row with manual drag-to-reorder. When [enabled], a leading drag handle long-press-drags
 * the row; on release the new order is computed and passed to [onReorder]. When disabled it renders
 * [content] unchanged.
 */
@Composable
fun <T> ReorderableRow(
    state: ListReorderState,
    listState: LazyListState,
    items: List<T>,
    item: T,
    keyOf: (T) -> Any,
    enabled: Boolean,
    onReorder: (List<T>) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val key = keyOf(item)
    val dragging = state.draggingKey == key
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (dragging) state.delta else 0f }
            .alpha(if (dragging) 0.92f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            Icons.Filled.DragIndicator,
            contentDescription = "Drag to reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(end = 2.dp)
                .pointerInput(key, items) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { state.start(key) },
                        onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
                        onDragEnd = {
                            val from = items.indexOfFirst { keyOf(it) == key }
                            val targetKey = state.targetKey(listState)
                            val to = items.indexOfFirst { keyOf(it) == targetKey }
                            if (from >= 0 && to >= 0 && to != from) {
                                onReorder(items.toMutableList().apply { add(to, removeAt(from)) })
                            }
                            state.reset()
                        },
                        onDragCancel = { state.reset() },
                    )
                },
        )
        Box(Modifier.weight(1f)) { content() }
    }
}
