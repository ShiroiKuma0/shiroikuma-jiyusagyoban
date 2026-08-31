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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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

/** What the sync dialog is showing, read live from the globals the sync action publishes. */
data class BoardSync(
    val phase: String = "",
    val pct: String = "",
    val records: String = "",
    val summary: String = "",
    val running: Boolean = false,
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
    val anyBusy: Boolean get() = busy != null || sync?.running == true || language?.busy == true
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
    BoardTile("walks", "運動（Huawei） -- [727]", Loc("Walks", "運動")),
    BoardTile("faces", "バンド文字盤（Huawei） -- [727]", Loc("Watch faces", "文字盤")),
    BoardTile("sat", "衛星予測（Huawei） -- [727]", Loc("Satellites", "衛星"),
        reopenTask = "衛星予測 開"),
    // Beside the predicted set, because they are the two halves of the same job and 白い熊 reaches
    // for them at different moments: 衛星 loads three days of orbits ahead of time, 即時 hands the
    // band the small broadcast file at the moment a fix is actually wanted.
    BoardTile("satnow", "衛星送信（Huawei） -- [727]", Loc("Fix now", "即時")),
    BoardTile("weather", "天気送信（Huawei） -- [727]", Loc("Weather", "天気")),
    BoardTile("place", "天気地点（Huawei） -- [727]", Loc("Location", "地点")),
    BoardTile("source", "天気提供元（Huawei） -- [727]", Loc("Weather source", "提供元")),
    BoardTile("sensors", "バンド計測設定（Huawei） -- [727]", Loc("Sensors", "計測設定")),
    BoardTile("lang", "バンド言語（Huawei） -- [727]", Loc("Language", "言語")),
    BoardTile("settings", "健康の設定 -- [727][01]", Loc("Settings", "設定")),
    BoardTile("pair", "バンド接続（Huawei） -- [727]", Loc("Pair", "接続")),
    BoardTile("unpair", "バンド解除（Huawei） -- [727]", Loc("Unpair", "解除")),
    BoardTile("probe", "バンド診断（Huawei） -- [727]", Loc("Probe", "診断")),
    BoardTile("census", "バンド棚卸し（Huawei） -- [727]", Loc("Census", "棚卸し")),
    BoardTile("files", "バンド書類（Huawei） -- [727]", Loc("Raw files", "書類")),
)

private val BOARD_REOPEN = Loc("Show", "画面")
private val BOARD_TITLE = Loc("Health — HUAWEI Band 11 Pro", "健康 — HUAWEI Band 11 Pro")
private val SYNC_TITLE = Loc("Syncing", "同期しています")
private val SYNC_OK = Loc("OK", "OK")
private val SYNC_FULL = Loc("Full sync", "全部同期")
private val SYNC_STATE = Loc("Sync state", "同期状態")
private val LANG_TITLE = Loc("Band language", "バンドの言語")

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
    "The band has no way to report its language — it has no language menu, and the phone owns the setting.",
    "バンドには言語を答える手段がありません — バンド側に言語の設定項目は無く、設定は端末が持ちます。",
)
private val LANG_TOLD = Loc(
    "Sent. Look at the band to see it.",
    "送信しました。バンドを見て確認してください。",
)
private val LANG_UNKNOWN = Loc(
    "This phone has never set the band's language.",
    "この端末はまだ一度もバンドの言語を設定していません。",
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
private val LANG_PICK = Loc("Set the band to:", "バンドの言語を：")
private val LANG_SWITCHING = Loc("Switching…", "切り替えています…")

val SYNC_FULL_TASK = "同期（Huawei） ⇨ 全部 -- [727]"
val SYNC_STATE_TASK = "同期状態（Huawei） -- [727]"

@Composable
fun HuaweiBoardScreen(
    state: HuaweiBoardState,
    contentPadding: PaddingValues,
    onRun: (BoardTile) -> Unit,
    onSyncAction: (String) -> Unit = {},
    onCloseSync: () -> Unit = {},
    onSwitchLanguage: (BandLanguage) -> Unit = {},
    onCloseLanguage: () -> Unit = {},
) {
    val lang = state.lang
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = Modifier.fillMaxSize(),
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
        items(BOARD_TILES, key = { it.key }) { tile ->
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
            )
        }
    }
    state.sync?.let { SyncDialog(it, lang, onSyncAction, onCloseSync) }
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
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
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
) {
    val accent = if (busy) ChartPalette.HEART_RATE else ChartPalette.AXIS_TEXT
    SectionCard(accent = accent) {
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

/**
 * The sync, watched rather than guessed at.
 *
 * A sync takes tens of seconds over Bluetooth and the old board simply started one and closed, which
 * left 白い熊 with no way of telling a working sync from a band that never answered. The action
 * already publishes its phase, its percentage and its record count as it goes, so this reads them.
 *
 * 全部同期 and 同期状態 live here because they are the same job at a different depth — the full
 * backfill when the charts show gaps, and the state readout when the question is whether syncing is
 * keeping up at all. Putting them on the board would have been three cards for one idea.
 */
@Composable
private fun SyncDialog(
    sync: BoardSync,
    lang: BandLanguage,
    onAction: (String) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        // Same frame as every other dialog in the app; see the note on the faces one.
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onClose,
        title = { Text(SYNC_TITLE[lang]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sync.running) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            listOf(sync.phase, sync.pct.takeIf { it.isNotBlank() }?.let { "$it %" })
                                .filterNot { it.isNullOrBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (sync.records.isNotBlank()) NoteText(sync.records)
                }
                if (sync.summary.isNotBlank()) {
                    Text(
                        sync.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onAction(SYNC_FULL_TASK) },
                        enabled = !sync.running,
                        shape = RoundedCornerShape(10.dp),
                    ) { Text(SYNC_FULL[lang]) }
                    OutlinedButton(
                        onClick = { onAction(SYNC_STATE_TASK) },
                        enabled = !sync.running,
                        shape = RoundedCornerShape(10.dp),
                    ) { Text(SYNC_STATE[lang]) }
                }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text(SYNC_OK[lang]) } },
    )
}
