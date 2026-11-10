package com.opentasker.ui.charts.huawei

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.opentasker.ui.components.AlertDialog
import com.opentasker.ui.components.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.composed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import com.opentasker.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.opentasker.ui.charts.BandLanguage
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard

/**
 * 健康 — the band's board.
 *
 * A page rather than a scene, and that is not an implementation detail: a scene is a fixed-size
 * overlay that has to fit what it shows, so sixteen cards meant sixteen thumbnails. A page scrolls,
 * so each card can be the size the watch-face library uses, and every other page — the report, the
 * walks, the faces — opens ON TOP of this one and comes back to it.
 *
 * ## Why a card is a picture and a yellow button
 *
 * Taken from the watch-face library rather than invented, because 白い熊 asked for the same thing and
 * because that grid has already been through the argument: the picture is what you recognise, and the
 * yellow slab is what you press. This app's settled grammar is that a filled yellow control is the
 * thing you are about to do — an outline is a live control you may toggle. So the card's button is
 * filled, and the language chip beside the title is not.
 */

/**
 * One card. [task] is a workspace task name; [key] picks the drawn artwork.
 *
 * [reopenTask] is the way BACK into something already running. A task that puts a panel on screen
 * keeps running when that panel is closed — deliberately, since the transfer must not be cut — and
 * until now there was no route back to it: the tile showed a spinner, and tapping it did nothing
 * because the board was busy (白い熊, 2026-08-30). With this set, a tap on a busy tile re-shows the
 * panel instead of refusing.
 */
data class BoardTile(
    val key: String,
    val task: String,
    val title: Loc,
    val reopenTask: String? = null,
)

/**
 * The four things the sync dialog can start. Nothing starts on opening it (白い熊, 2026-11-03): the
 * tile opens the dialog, and a button in it is what talks to the watch.
 */
enum class SyncJob { NORMAL, WORKOUTS, FULL, STATE }

/**
 * One step of a run as it happened: [atSec] after the press, a phase key and its detail. An [alarm]
 * line is the link being cut mid-sync, written in red where the run is being watched.
 */
data class SyncLine(val atSec: Int, val phase: String, val message: String = "", val alarm: Boolean = false)

/**
 * What the sync dialog is showing.
 *
 * Fed from the runner's own [com.opentasker.core.huawei.HuaweiSyncState] flow, not from the task's
 * globals. The globals were the old source and could not work: the action writes `starting` / `0`
 * once at the top and nothing more until the end, so the dialog sat on "starting · 0 %" for the
 * whole fifty seconds and then jumped to the result (白い熊, 2026-11-03).
 */
data class BoardSync(
    /** The job now running, or null when the dialog is idle. */
    val running: SyncJob? = null,
    /** The job whose result [summary] is. */
    val last: SyncJob? = null,
    val phase: String = "",
    val message: String = "",
    /** A real fraction of the work, 0–100, or null while the step has no count to measure. */
    val percent: Int? = null,
    /** "12 / 48 records · 300 samples" — the counter behind [percent]. */
    val count: String = "",
    val lines: List<SyncLine> = emptyList(),
    val startedAtMs: Long = 0L,
    val summary: String = "",
    /** Which button's explanation is open under the buttons. */
    val info: SyncJob? = null,
    /** How many times this run's link was cut and the sync restarted — the red banner. */
    val cuts: Int = 0,
)

/**
 * What the band-language dialog is showing.
 *
 * There is only one source, and it is this phone's own record — **the band cannot be asked**. That
 * is settled, not assumed: `0x0C` has no read command, and the full product-info sweep added to the
 * diagnostic on 2026-08-29 came back with 34 tags, not one of them a language. Huawei's own position
 * is that the phone owns the setting and pushes it, so the band never needs to answer the question.
 *
 * The dialog therefore says whose word it is showing, every time, rather than presenting our record
 * as the band's state. It matters because the two really can part: any companion may push its own
 * locale, which is how this band went English behind our back on a trip to another phone.
 */
data class BoardLanguage(
    val remembered: String? = null,
    val switching: Boolean = false,
    /** The language we last asked the band for in this dialog, if any. */
    val told: String? = null,
    val failed: Loc? = null,
) {
    val busy: Boolean get() = switching

}

data class HuaweiBoardState(
    val lang: BandLanguage = BandLanguage.EN,
    /** The real earth watch face, and a piece of a real walk's map; null until read, or if absent. */
    val faceArt: ByteArray? = null,
    val walkArt: ByteArray? = null,
    /** The task now running from a card, by name — every card goes inert while one runs. */
    val busy: String? = null,
    val sync: BoardSync? = null,
    val language: BoardLanguage? = null,
) {
    /**
     * Whether anything is in flight — a task handed over, OR a sync still running.
     *
     * The sync used to be left out, so every tile stayed live underneath the sync dialog and the
     * sync tile itself could be pressed again while its own sync was still going (白い熊,
     * 2026-08-29). The band layer refuses the second one — [HuaweiSyncRunner] holds a mutex and
     * answers "a Huawei sync is already running" — but a button that can be pressed to be told no
     * is a button that should have been disabled.
     */
    val anyBusy: Boolean get() = busy != null || sync?.running != null || language?.busy == true
}

/**
 * The order is how the band is used, not the alphabet: get the data in, look at it, feed the band,
 * set it up, then the tools that answer questions about it.
 *
 * 全部同期 and 同期状態 are deliberately NOT here — they belong to syncing and live in its dialog, so
 * the board stays the sixteen things worth their own picture (白い熊, 2026-08-28).
 */
val BOARD_TILES = listOf(
    BoardTile("sync", "同期（Huawei） -- [727]", Loc("Sync", "同期")),
    BoardTile("report", "健康（Huawei） -- [727]", Loc("Report", "健康")),
    // Between the report and the walks, where 白い熊 put it: the three "look at what was recorded"
    // cards sit together, and lifting is nearer the report than the walks are — it has no route,
    // so what it holds is the same heart rate and energy the report is made of.
    BoardTile("lift", "重量挙げ（Huawei） -- [727]", Loc("Lifting", "重量挙げ")),
    BoardTile("rehab", "機能訓練（Huawei） -- [727]", Loc("Rehab", "機能訓練")),
    BoardTile("walks", "運動（Huawei） -- [727]", Loc("Walks", "運動")),
    BoardTile("faces", "時計文字盤（Huawei） -- [727]", Loc("Watch faces", "文字盤")),
    // Beside the faces, because it is what a face is looked at WITH: a face can only be judged at
    // the hour it draws, and some hours come round slowly. The card opens the same window as the
    // task — calendar, dial, 丁度 and 半, and a switch that hands the band the phone's time back.
    // Nothing here is destructive: every connection announces the phone's time anyway.
    BoardTile("clock", "時 合わせる -- [727]", Loc("Watch time", "時 合わせる")),
    BoardTile("sat", "衛星予測（Huawei） -- [727]", Loc("Satellites", "衛星"),
        reopenTask = "衛星予測 開"),
    // Beside the predicted set, because they are the two halves of the same job and 白い熊 reaches
    // for them at different moments: 衛星 loads three days of orbits ahead of time, 即時 hands the
    // band the small broadcast file at the moment a fix is actually wanted.
    BoardTile("satnow", "衛星送信（Huawei） -- [727]", Loc("Fix now", "即時")),
    BoardTile("weather", "天気送信（Huawei） -- [727]", Loc("Weather", "天気")),
    BoardTile("place", "天気地点（Huawei） -- [727]", Loc("Location", "地点")),
    BoardTile("source", "天気提供元（Huawei） -- [727]", Loc("Weather source", "提供元")),
    BoardTile("sensors", "時計計測設定（Huawei） -- [727]", Loc("Sensors", "計測設定")),
    BoardTile("lang", "時計 日本語 -- [727]", Loc("Language", "言語")),
    BoardTile("settings", "健康の設定 -- [727][01]", Loc("Settings", "設定")),
    BoardTile("pair", "時計接続（D3） -- [727]", Loc("Pair", "接続")),
    BoardTile("unpair", "時計解除（Huawei） -- [727]", Loc("Unpair", "解除")),
    BoardTile("probe", "時計診断（Huawei） -- [727]", Loc("Probe", "診断")),
    BoardTile("census", "時計棚卸し（Huawei） -- [727]", Loc("Census", "棚卸し")),
    BoardTile("files", "時計書類（Huawei） -- [727]", Loc("Raw files", "書類")),
)

private val BOARD_REOPEN = Loc("Show", "画面")
private val BOARD_TITLE = Loc("Health — HUAWEI WATCH D3", "健康 — HUAWEI WATCH D3")
private val SYNC_TITLE = Loc("Sync", "同期")
private val SYNC_OK = Loc("OK", "OK")
private val SYNC_CLOSE = Loc("Close", "閉じる")
private val SYNC_HIDE = Loc("Hide", "隠す")
private val SYNC_CANCEL = Loc("Cancel", "中止")
private val SYNC_NORMAL = Loc("Sync", "同期")
private val SYNC_WORKOUTS = Loc("Walks, lifting, rehab", "運動・重量挙げ・機能訓練")
private val SYNC_FULL = Loc("Full sync", "全部同期")
private val SYNC_STATE = Loc("Sync state", "同期状態")
private val SYNC_IDLE = Loc(
    "Nothing runs until you press a button.",
    "ボタンを押すまで何も始まりません。",
)
private val SYNC_SECONDS = Loc("%d s", "%d 秒")

/**
 * The link cut mid-sync. On this phone that has been Bluetooth itself crashing (`com.android.bluetooth`,
 * SIGSEGV in its input-device code, 2026-11-10) — the sync only ever sees “Broken pipe”.
 */
private val SYNC_ALARM_INK = androidx.compose.ui.graphics.Color(0xFFFF3B30)
private val SYNC_CUTS = Loc(
    "The Bluetooth link was cut %d× during this run (Broken pipe). The sync restarts by itself.",
    "この実行中に Bluetooth 接続が %d 回切れました（Broken pipe）。同期は自動で再開します。",
)

/** What each button does — behind its ⓘ, because the names alone did not say (白い熊, 2026-11-03). */
private val SYNC_INFO = mapOf(
    SyncJob.NORMAL to Loc(
        "The everyday sync: the watch's records since the last successful sync, plus the last few " +
            "nights of sleep, the beat series, blood pressure, skin temperature, ECG and the " +
            "satellite data the watch asks for. Tens of seconds.",
        "ふだんの同期：前回成功してから今までの記録と、ここ数晩の睡眠・心拍の系列・血圧・皮膚温・" +
            "心電図、それに時計が求める衛星データ。数十秒かかります。",
    ),
    SyncJob.WORKOUTS to Loc(
        "The workouts the watch recorded in the last 30 days — walks with their GPS tracks, lifting " +
            "and rehab sessions with their heart rate. A separate fetch: the everyday sync does not " +
            "bring them. Already-stored workouts are refreshed, never doubled.",
        "時計が記録したここ 30 日の運動 — 散歩は GPS の経路ごと、重量挙げと機能訓練は心拍ごと。" +
            "ふだんの同期では取れない別の取り込みです。保存済みのものは上書きされ、二重にはなりません。",
    ),
    SyncJob.FULL to Loc(
        "Everything the watch still holds, walked back in 24-hour windows — not only what is new " +
            "since the last sync. Use it when the charts show gaps: records that closed after a " +
            "sync are never asked for again by the everyday one. Safe to repeat. Takes minutes.",
        "時計がまだ持っている履歴を全部、24 時間ずつ遡って取り込みます — 前回からの分だけではありません。" +
            "グラフに空白があるときに。同期の後で閉じた記録はふだんの同期では二度と要求されないためです。" +
            "何度走らせても安全。数分かかります。",
    ),
    SyncJob.STATE to Loc(
        "Does not touch the watch. Checks whether syncing is keeping up: when the last success was, " +
            "and whether the gap is longer than the warning threshold or than the history the watch " +
            "can reach back to. Raises or clears the 「同期できていません」 notification.",
        "時計には触れません。同期が追いついているかを確かめます — 最後の成功はいつか、その間隔が" +
            "警告のしきい値や時計が遡れる深さを超えていないか。「同期できていません」の通知を出すか消します。",
    ),
)
private val LANG_TITLE = Loc("Watch language", "時計の言語")

/** Said plainly, because it is a weaker claim than the band's own answer and must read as one. */
/**
 * Weaker than the band's own answer, and worded so it reads that way.
 *
 * `0x0C` has no read command — the empty-tag probe was tried and answered nothing — so on a band
 * that keeps its locale out of product info this is the only thing there is to show. Saying "the
 * band is in Japanese" on this evidence would be a guess in the exact case that goes wrong: any
 * companion can push its own language, which is how this band went English behind our back.
 */
private val LANG_FROM_US = Loc(
    "This phone last set:",
    "この端末が最後に設定した言語：",
)
/** Stated once, plainly, so the line above is never mistaken for the band's own answer. */
private val LANG_NO_READ = Loc(
    "The watch has no way to report its language over this link, so this phone's record is all there is.",
    "時計にはこの接続で言語を答える手段がありません — 分かるのはこの端末の記録だけです。",
)
private val LANG_TOLD = Loc(
    "Sent. Look at the watch to see it.",
    "送信しました。時計を見て確認してください。",
)
private val LANG_UNKNOWN = Loc(
    "This phone has never set the watch's language.",
    "この端末はまだ一度も時計の言語を設定していません。",
)
private val LANG_SWITCH = Loc("Switch to", "切り替え：")
/**
 * Both languages, always, side by side (白い熊, 2026-08-29).
 *
 * This is what makes the dialog correct rather than merely honest. A single "switch to the other
 * one" button has to know which one it is in — and the band cannot be asked, so that knowledge is
 * only ever this phone's record, which is wrong exactly when another companion has touched the band.
 * Offering both removes the dependency altogether: whatever the band is actually showing, the button
 * for the language 白い熊 wants is on screen and does the right thing.
 */
private val LANG_PICK = Loc("Set the watch to:", "時計の言語を：")
private val LANG_SWITCHING = Loc("Switching…", "切り替えています…")

val SYNC_FULL_TASK = "同期（Huawei） ⇨ 全部 -- [727]"
val SYNC_STATE_TASK = "同期状態（Huawei） -- [727]"

@Composable
fun HuaweiBoardScreen(
    state: HuaweiBoardState,
    contentPadding: PaddingValues,
    onRun: (BoardTile) -> Unit,
    onSyncAction: (SyncJob) -> Unit = {},
    onSyncInfo: (SyncJob?) -> Unit = {},
    onCloseSync: () -> Unit = {},
    /** Stop the running sync — the dialog stays, showing that it was cancelled. */
    onCancelSync: () -> Unit = {},
    onSwitchLanguage: (BandLanguage) -> Unit = {},
    onCloseLanguage: () -> Unit = {},
) {
    val lang = state.lang
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // The arrangement, and the drag that changes it.
    //
    // Held here rather than in the store while a finger is down: a drag rewrites the order many
    // times a second, and writing each of those to disk would be a preference file churned for one
    // gesture. It is committed once, on release.
    val stored by BoardOrder.keys.collectAsState()
    LaunchedEffect(Unit) { BoardOrder.load(context) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    val order = remember { mutableStateListOf<BoardTile>() }
    LaunchedEffect(stored) {
        // Never while a finger is down: the store catching up mid-drag would yank the list out from
        // under the gesture and drop the tile somewhere nobody aimed at.
        if (dragKey == null) {
            order.clear()
            order.addAll(BoardOrder.apply(BOARD_TILES, stored))
        }
    }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // Measured, not assumed: the grid is Adaptive, so the column count depends on the panel it is
    // opened on and a hard-coded four would put the vertical step wrong on every other width.
    var cellSize by remember { mutableStateOf(IntSize.Zero) }
    var gridWidth by remember { mutableIntStateOf(0) }
    val spacingPx = with(density) { 10.dp.toPx() }
    val columns = remember(cellSize, gridWidth) {
        if (cellSize.width <= 0 || gridWidth <= 0) 1
        else ((gridWidth + spacingPx) / (cellSize.width + spacingPx)).toInt().coerceAtLeast(1)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = Modifier.fillMaxSize().onGloballyPositioned { gridWidth = it.size.width },
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            // A heading, not a card. The card was a SectionCard's full padding wrapped round one
            // line of text, which on a wide panel is a hand's breadth of empty box before the first
            // picture — the thing 白い熊 opened the board to see (2026-08-28).
            Text(
                BOARD_TITLE[lang],
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
            )
        }
        items(order, key = { it.key }) { tile ->
            val dragging = dragKey == tile.key
            BoardCard(
                tile = tile,
                lang = lang,
                busy = state.busy == tile.task,
                // A card that is running AND knows how to show itself again stays live. Everything
                // else goes inert while something holds the band.
                anyBusy = state.anyBusy &&
                    !(state.busy == tile.task && tile.reopenTask != null),
                art = when (tile.key) {
                    "faces" -> state.faceArt
                    "walks" -> state.walkArt
                    else -> null
                },
                onOpen = { onRun(tile) },
                modifier = Modifier
                    .onGloballyPositioned { if (cellSize == IntSize.Zero) cellSize = it.size }
                    // Lifted while held, so it is obvious which tile is travelling.
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        if (dragging) {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                            scaleX = 1.06f
                            scaleY = 1.06f
                        }
                    }
                    .pointerInput(tile.key, columns) {
                        detectDragGesturesAfterLongPress(
                            // The haptic is the moment it latches — without it a long-press that
                            // has taken hold is indistinguishable from one that has not.
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragKey = tile.key
                                dragOffset = Offset.Zero
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount
                                val di = order.indexOfFirst { it.key == dragKey }
                                if (di !in order.indices) return@detectDragGesturesAfterLongPress
                                // One position at a time, past HALF a step — the same rule the
                                // project chips use, given a second axis. Sideways moves by one,
                                // up or down moves by a whole row.
                                val stepX = cellSize.width + spacingPx
                                val stepY = cellSize.height + spacingPx
                                if (dragOffset.x > stepX / 2 && di < order.lastIndex) {
                                    order.add(di + 1, order.removeAt(di)); dragOffset -= Offset(stepX, 0f)
                                } else if (dragOffset.x < -stepX / 2 && di > 0) {
                                    order.add(di - 1, order.removeAt(di)); dragOffset += Offset(stepX, 0f)
                                } else if (dragOffset.y > stepY / 2 && di + columns <= order.lastIndex) {
                                    order.add(di + columns, order.removeAt(di)); dragOffset -= Offset(0f, stepY)
                                } else if (dragOffset.y < -stepY / 2 && di - columns >= 0) {
                                    order.add(di - columns, order.removeAt(di)); dragOffset += Offset(0f, stepY)
                                }
                            },
                            // Committed once, on release — and on CANCEL too, because a gesture the
                            // system takes away (a call, a notification shade) has still moved the
                            // tiles on screen, and leaving the screen and the store disagreeing is
                            // how an arrangement silently reverts on the next open.
                            onDragEnd = {
                                dragKey = null; dragOffset = Offset.Zero
                                BoardOrder.set(context, order.map { it.key })
                            },
                            onDragCancel = {
                                dragKey = null; dragOffset = Offset.Zero
                                BoardOrder.set(context, order.map { it.key })
                            },
                        )
                    },
            )
        }
    }
    state.sync?.let { SyncDialog(it, lang, onSyncAction, onSyncInfo, onCloseSync, onCancelSync) }
    state.language?.let { LanguageDialog(it, lang, onSwitchLanguage, onCloseLanguage) }
}

/**
 * "The band is in English — switch it to Japanese?"
 *
 * A dialog rather than a task, because the old one could not be right: it pushed whatever
 * `%Huawei_BandLocale` happened to hold, so it only ever set ONE language and pressing it a second
 * time did nothing visible (白い熊, 2026-08-29 — "it keeps switching the band's language to
 * Japanese"). A toggle has to know what it is toggling FROM, and this band's language is not
 * something the phone can assume it owns: the band has no language menu, every companion pushes its
 * own, and 白い熊's went English behind our back on a trip to another phone.
 *
 * So it asks first, and shows whose answer it got. One button, offering the language it is not in.
 */
@Composable
private fun LanguageDialog(
    lang: BoardLanguage,
    ui: BandLanguage,
    onSwitch: (BandLanguage) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!lang.busy) onClose() },
        title = { Text(LANG_TITLE[ui]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lang.remembered == null) {
                    BodyText(LANG_UNKNOWN[ui])
                } else {
                    NoteText(LANG_FROM_US[ui])
                    Text(
                        "${nameOfLocale(lang.remembered, ui)}  ·  ${lang.remembered}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                NoteText(LANG_NO_READ[ui])
                NoteText(LANG_PICK[ui])
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Equal weight, so neither reads as the default. Both stay enabled even when
                    // one matches our record: if the record is wrong, disabling it would disable
                    // the very button that fixes the band.
                    for (target in listOf(BandLanguage.JA, BandLanguage.EN)) {
                        Button(
                            onClick = { onSwitch(target) },
                            enabled = !lang.busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(nameOfLocale(target.tag, ui))
                        }
                    }
                }
                if (lang.told != null && !lang.switching) NoteText(LANG_TOLD[ui])
                lang.failed?.let { NoteText(it[ui], warn = true) }
                if (lang.switching) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            LANG_SWITCHING[ui],
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onClose, enabled = !lang.busy) { Text(SYNC_OK[ui]) }
        },
    )
}

/** A BCP-47 tag as something to read. An unrecognised one is shown as itself, not guessed at. */
private fun nameOfLocale(tag: String, ui: BandLanguage): String = when {
    tag.equals(BandLanguage.JA.tag, ignoreCase = true) -> Loc("Japanese", "日本語")[ui]
    tag.equals(BandLanguage.EN.tag, ignoreCase = true) -> Loc("English", "英語")[ui]
    else -> tag
}

@Composable
private fun BoardCard(
    tile: BoardTile,
    lang: BandLanguage,
    busy: Boolean,
    anyBusy: Boolean,
    art: ByteArray?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (busy) ChartPalette.HEART_RATE else ChartPalette.AXIS_TEXT
    SectionCard(accent = accent, modifier = modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // A real photograph where one exists — the earth face, a walk's own map — and drawn
            // artwork everywhere else. Decoding is cheap and the bytes are already in hand; a failed
            // decode falls through to the drawing rather than leaving a hole.
            val bmp: ImageBitmap? = art?.let {
                runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
            }
            if (bmp != null) {
                // Crop, not Fit: a photograph that does not share the card's shape would otherwise
                // sit in bars of dead space. The bytes are already cut square before they get here,
                // so cropping takes the edges of a square rather than the middle of a tall face.
                Image(
                    bmp, tile.title[lang],
                    Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                BoardArt(tile.key, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
            }
        }
        // No caption above the button. The watch-face card needs one because its button says
        // "Install" while the card is A Silent Night — two different things to say. Here the button
        // IS the name, so a caption above it printed every card's name twice.
        Button(
            onClick = onOpen,
            // Every card goes inert while one runs: the band serves a single connection, so a second
            // tap cannot queue — it would be refused, and a live button that answers "busy" reads as
            // a broken button rather than as a busy band.
            // Disabled by `anyBusy` — which the caller already relaxes for a running card that has
            // a way back. Without that, the tap never arrives at all: a disabled Compose button
            // swallows the click, so the reopen handler behind it could never fire and the spinning
            // tile simply could not be pressed (白い熊, 2026-08-30).
            enabled = !anyBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    // A spinner alone says "running" and nothing else. When there IS a way back into
                    // the panel, say so on the button — 白い熊 had a card spinning with no indication
                    // that pressing it would do anything, and it could not have (2026-08-30).
                    if (tile.reopenTask != null) Text(BOARD_REOPEN[lang])
                }
            } else {
                Text(tile.title[lang])
            }
        }
    }
}

/** The run's place in its whole sequence, 0–1, and the step it is on; null for a job with no steps. */
private fun overall(sync: BoardSync): Pair<Float, Int>? {
    val steps = when (sync.running) {
        SyncJob.NORMAL, SyncJob.FULL -> SyncSteps.SYNC
        SyncJob.WORKOUTS -> SyncSteps.WORKOUTS
        else -> return null
    }
    return SyncSteps.overall(steps, sync.phase, sync.percent)
}

/** A thin yellow frame around a block of the dialog. [thick] for the running portal. */
private fun Modifier.framed(thick: Boolean = false) = composed {
    border(
        if (thick) 2.dp else 1.dp,
        MaterialTheme.colorScheme.primary.copy(alpha = if (thick) 1f else 0.7f),
        RoundedCornerShape(12.dp),
    ).padding(horizontal = 12.dp, vertical = 10.dp)
}

/**
 * The sync, watched rather than guessed at.
 *
 * A sync takes tens of seconds over Bluetooth, so every step the runner announces is kept as a line
 * with the second it arrived at — which part is slow is then on screen rather than guessed. The
 * lines run inside their own yellow frame, a window onto the run that scrolls itself to the newest
 * line (白い熊, 2026-11-03). Under them two bars: the current step (filled while records are read,
 * whose count is known up front; running while a step has nothing to count) and the whole run,
 * counted in steps.
 *
 * Every word is yellow — the dialog had blue notes and grey lines, and on this theme only yellow
 * reads as text. An explanation behind an ⓘ sits in its own thin frame.
 *
 * Opening the dialog starts nothing (白い熊, 2026-11-03). The four buttons are the same job at
 * different depths, and each carries an ⓘ that says what it does.
 */
@Composable
private fun SyncDialog(
    sync: BoardSync,
    lang: BandLanguage,
    onAction: (SyncJob) -> Unit,
    onInfo: (SyncJob?) -> Unit,
    /** Close when idle; Hide while a sync runs — the run goes on without the dialog. */
    onClose: () -> Unit,
    onCancel: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.primary
    // The seconds counter: ticks only while something runs.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sync.running) {
        while (sync.running != null) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val portal = rememberScrollState()
    // Follow the newest line, the way a terminal does.
    LaunchedEffect(sync.lines.size) { portal.animateScrollTo(portal.maxValue) }
    AlertDialog(
        // Same frame as every other dialog in the app; see the note on the faces one.
        onDismissRequest = onClose,
        title = { Text(SYNC_TITLE[lang]) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sync.lines.isEmpty() && sync.running == null && sync.summary.isBlank()) {
                    Text(SYNC_IDLE[lang], style = MaterialTheme.typography.bodyMedium, color = ink)
                }
                // Loud, and above everything else, for as long as the dialog is open.
                if (sync.cuts > 0) {
                    Text(
                        SYNC_CUTS[lang].format(sync.cuts),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(3.dp, SYNC_ALARM_INK, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SYNC_ALARM_INK,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (sync.lines.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().framed(thick = true), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(portal),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (line in sync.lines) {
                                Text(
                                    "+" + SYNC_SECONDS[lang].format(line.atSec) + "  " +
                                        listOf(if (line.alarm) "" else phaseLabel(line.phase)[lang], line.message)
                                            .filter { it.isNotBlank() }.joinToString(" — "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (line.alarm) SYNC_ALARM_INK else ink,
                                    fontWeight = if (line.alarm) FontWeight.Bold else null,
                                )
                            }
                        }
                        if (sync.running != null) {
                            val elapsed = ((now - sync.startedAtMs) / 1000).toInt().coerceAtLeast(0)
                            val whole = overall(sync)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(
                                    listOf(
                                        phaseLabel(sync.phase)[lang],
                                        sync.percent?.let { "$it %" },
                                        SYNC_SECONDS[lang].format(elapsed),
                                    ).filterNot { it.isNullOrBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ink,
                                )
                            }
                            // This step: measured while there is a count, running while there is not.
                            val pct = sync.percent
                            val bar = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                            if (pct != null) LinearProgressIndicator(progress = { pct / 100f }, modifier = bar)
                            else LinearProgressIndicator(modifier = bar)
                            if (sync.count.isNotBlank()) {
                                Text(sync.count, style = MaterialTheme.typography.bodySmall, color = ink)
                            }
                            // The whole run, in steps.
                            if (whole != null) {
                                LinearProgressIndicator(
                                    progress = { whole.first },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = ink.copy(alpha = 0.75f),
                                )
                                Text(
                                    SyncSteps.STEP_OF[lang].format(
                                        whole.second,
                                        if (sync.running == SyncJob.WORKOUTS) SyncSteps.WORKOUTS.size else SyncSteps.SYNC.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ink,
                                )
                            }
                        }
                    }
                }
                if (sync.summary.isNotBlank()) {
                    Text(sync.summary, style = MaterialTheme.typography.bodyMedium, color = ink)
                }
                for (job in SyncJob.entries) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val label = when (job) {
                            SyncJob.NORMAL -> SYNC_NORMAL
                            SyncJob.WORKOUTS -> SYNC_WORKOUTS
                            SyncJob.FULL -> SYNC_FULL
                            SyncJob.STATE -> SYNC_STATE
                        }
                        // The everyday sync is the main pill: it is what this dialog is for.
                        if (job == SyncJob.NORMAL) {
                            Button(
                                onClick = { onAction(job) },
                                enabled = sync.running == null,
                                modifier = Modifier.weight(1f),
                            ) { Text(label[lang]) }
                        } else {
                            OutlinedButton(
                                onClick = { onAction(job) },
                                enabled = sync.running == null,
                                modifier = Modifier.weight(1f),
                            ) { Text(label[lang]) }
                        }
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "info",
                            tint = ink,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onInfo(if (sync.info == job) null else job) }
                                .padding(8.dp)
                                .size(24.dp),
                        )
                    }
                    if (sync.info == job) {
                        Text(
                            SYNC_INFO.getValue(job)[lang],
                            modifier = Modifier.fillMaxWidth().framed(),
                            style = MaterialTheme.typography.bodySmall,
                            color = ink,
                        )
                    }
                }
            }
        },
        // No OK: while a sync runs there is nothing to confirm (白い熊, 2026-11-10). Hide leaves it
        // running — the notification, the report's Sync and this board's Sync bring it back — and
        // Cancel stops it. Idle, the one way out is Close; the dialog's main pill is Sync itself.
        confirmButton = {
            if (sync.running != null) {
                OutlinedButton(onClick = onClose) { Text(SYNC_HIDE[lang]) }
            } else {
                OutlinedButton(onClick = onClose) { Text(SYNC_CLOSE[lang]) }
            }
        },
        dismissButton = if (sync.running != null) {
            {
                OutlinedButton(
                    onClick = onCancel,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = SYNC_ALARM_INK),
                ) { Text(SYNC_CANCEL[lang]) }
            }
        } else {
            null
        },
    )
}
