package com.opentasker.ui.gengoshima

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.opentasker.ui.charts.SectionCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One tile of the 言語島 board: a picture ([key] picks it), a task run by name, a Japanese title with
 * the English under it. [wide] tiles take the whole row — the play tile, so the thing used most is
 * the thing seen first.
 */
data class GsTile(
    val key: String,
    val task: String,
    val ja: String,
    val en: String,
    val wide: Boolean = false,
)

/**
 * The default order is the order of the work (白い熊, 2026-10-04): what came in from a walk — review it
 * in kxkb, file it here — then writing by hand and translating, then the big play tile and the two
 * practice modes, then looking after the islands, the 暗記 sync, and settings. Drag to change it.
 */
val GS_TILES = listOf(
    GsTile("review", "言語島 録音確認 -- [227]", "録音確認", "Review recordings"),
    GsTile("inbox", "言語島 未分類 -- [227]", "未分類", "File sentences"),
    GsTile("entry", "言語島 文入力 -- [227]", "文入力", "Write sentences"),
    GsTile("generate", "言語島 生成 -- [227]", "訳して音声", "Translate & voice"),
    GsTile("listen", "言語島 聴く -- [227]", "聴く", "Listen", wide = true),
    GsTile("shadow", "言語島 シャドーイング -- [227]", "シャドーイング", "Shadow"),
    GsTile("recall", "言語島 思い出す -- [227]", "思い出す", "Recall"),
    GsTile("edit", "言語島 編集 -- [227]", "編集", "Edit islands"),
    GsTile("stats", "言語島 統計 -- [227]", "統計", "Statistics"),
    GsTile("anki", "暗記と同期 -- [227]", "暗記と同期", "Sync with 暗記"),
    GsTile("settings", "日本語の設定 -- [227][01]", "設定", "Settings"),
    GsTile("adopt", "暗記から取り込む -- [227]", "暗記から取り込む", "Take from 暗記"),
)

/**
 * The order 白い熊 dragged the tiles into — stored by KEY and merged, as the 健康 board's
 * [com.opentasker.ui.charts.huawei.BoardOrder] is and for the same reason: a tile added in a later
 * version lands at the end instead of vanishing, and a stored key that no longer exists is dropped.
 */
object GsBoardOrder {
    private const val PREFS = "gengoshima_board"
    private const val KEY = "tile_order"
    private const val SEP = "\t"

    private val _keys = MutableStateFlow<List<String>>(emptyList())
    val keys: StateFlow<List<String>> = _keys

    fun load(context: Context) {
        _keys.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .split(SEP).map(String::trim).filter(String::isNotEmpty)
    }

    fun set(context: Context, keys: List<String>) {
        val clean = keys.distinct()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, clean.joinToString(SEP)).apply()
        _keys.value = clean
    }

    fun apply(tiles: List<GsTile>, stored: List<String>): List<GsTile> {
        if (stored.isEmpty()) return tiles
        val byKey = tiles.associateBy { it.key }
        return stored.mapNotNull(byKey::get) + tiles.filterNot { it.key in stored }
    }
}

private const val HEADER_KEY = "__header"

/**
 * The board: a grid of picture tiles, long-press and drag to reorder.
 *
 * The drag hit-tests the pointer against the grid's laid-out items rather than stepping by a fixed cell
 * (the 健康 board's way), because this grid has a WIDE tile: a full-row item breaks "one column = one
 * cell". The tile under the finger is where the dragged one goes; its drawn offset is the finger's
 * travel minus wherever the grid has since laid it out, so it stays under the finger through swaps.
 */
@Composable
fun GengoshimaBoardScreen(
    contentPadding: PaddingValues,
    busy: String?,
    inboxCount: Int,
    onRun: (GsTile) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // Loaded and laid out at once, not in an effect: an effect runs after the first frame, so the board
    // opened empty for a frame — and the screenshot previews, which render one frame, showed no tiles.
    remember { GsBoardOrder.load(context) }
    val stored by GsBoardOrder.keys.collectAsState()
    var dragKey by remember { mutableStateOf<String?>(null) }
    val order = remember { mutableStateListOf<GsTile>().apply { addAll(GsBoardOrder.apply(GS_TILES, GsBoardOrder.keys.value)) } }
    LaunchedEffect(stored) {
        if (dragKey == null) { order.clear(); order.addAll(GsBoardOrder.apply(GS_TILES, stored)) }
    }
    val grid = rememberLazyGridState()
    var startOffset by remember { mutableStateOf(Offset.Zero) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    var travel by remember { mutableStateOf(Offset.Zero) }

    fun itemOffset(key: String): Offset? =
        grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { Offset(it.offset.x.toFloat(), it.offset.y.toFloat()) }

    fun commit() {
        dragKey = null
        travel = Offset.Zero
        GsBoardOrder.set(context, order.map { it.key })
    }

    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = HEADER_KEY, span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "言語島 — Language islands",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                    Text(
                        "長押しで並べ替え / long-press and drag to reorder",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClose) { Text("閉じる", fontSize = 18.sp) }
            }
        }
        items(order, key = { it.key }, span = { t -> GridItemSpan(if (t.wide) maxLineSpan else 1) }) { tile ->
            val dragging = dragKey == tile.key
            GsCard(
                tile = tile,
                busy = busy == tile.task,
                enabled = busy == null,
                badge = if (tile.key == "inbox" && inboxCount > 0) inboxCount else null,
                onOpen = { onRun(tile) },
                modifier = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        if (dragging) {
                            val now = itemOffset(tile.key) ?: startOffset
                            val d = startOffset + travel - now
                            translationX = d.x
                            translationY = d.y
                            scaleX = 1.05f
                            scaleY = 1.05f
                        }
                    }
                    .pointerInput(tile.key) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { pos ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragKey = tile.key
                                startOffset = itemOffset(tile.key) ?: Offset.Zero
                                touch = pos
                                travel = Offset.Zero
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                travel += amount
                                val finger = startOffset + touch + travel
                                val over = grid.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                    info.key != HEADER_KEY && info.key != dragKey &&
                                        finger.x >= info.offset.x && finger.x < info.offset.x + info.size.width &&
                                        finger.y >= info.offset.y && finger.y < info.offset.y + info.size.height
                                } ?: return@detectDragGesturesAfterLongPress
                                val from = order.indexOfFirst { it.key == dragKey }
                                val to = order.indexOfFirst { it.key == over.key }
                                if (from >= 0 && to >= 0 && from != to) order.add(to, order.removeAt(from))
                            },
                            // On cancel too: the tiles have already moved on screen.
                            onDragEnd = { commit() },
                            onDragCancel = { commit() },
                        )
                    },
            )
        }
    }
}

@Composable
private fun GsCard(
    tile: GsTile,
    busy: Boolean,
    enabled: Boolean,
    badge: Int?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (tile.wide) MaterialTheme.colorScheme.primary else Color(0xFF8A8A85)
    SectionCard(accent = accent, modifier = modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (tile.wide) 2.4f else 4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.TopEnd,
        ) {
            GsBoardArt(tile.key, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
            // The waiting count, as a number, not a colour: it reads the same to every eye.
            if (badge != null) {
                Text(
                    "$badge",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier.padding(8.dp)
                        .background(Color(0xFFFFFF00), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
        }
        Button(
            onClick = onOpen,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (tile.wide) 64.dp else 52.dp),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // One line always: a long name (シャドーイング, 暗記から取り込む) steps down rather than breaking mid-word.
                    Text(
                        tile.ja,
                        fontSize = when { tile.wide -> 22.sp; tile.ja.length > 6 -> 13.sp; tile.ja.length > 4 -> 15.sp; else -> 17.sp },
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                    )
                    Text(tile.en, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
