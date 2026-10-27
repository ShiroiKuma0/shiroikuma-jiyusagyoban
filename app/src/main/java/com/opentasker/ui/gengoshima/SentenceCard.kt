package com.opentasker.ui.gengoshima

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.core.gengoshima.GengoshimaLanguage
import com.opentasker.core.gengoshima.Translator
import com.opentasker.core.gengoshima.WordEdit
import com.opentasker.core.storage.GengoshimaSentenceEntity
import com.opentasker.ui.charts.ActionPill
import com.opentasker.ui.components.AlertDialog
import java.io.File

/**
 * Where a sentence stands, on its card (白い熊, 2026-10-26: "the edited state should then be colour
 * indicated"). Every state is a border colour AND a pill that says it in words — 白い熊 is red-green
 * colour-blind, so a colour alone is never the message. Orange and blue are the pair that stays apart
 * for him; yellow is the app's own ink, for "to voice"; red only for a failure.
 */
internal enum class CardState(val colour: Color?, val label: String) {
    TO_TRANSLATE(Color(0xFFFF8C00), "訳し直し待ち / to translate"),
    TO_SPLIT(Color(0xFF1E5AFF), "分け直し待ち / to re-split"),
    TO_VOICE(Color(0xFFFFFF00), "音声待ち / to voice"),
    FAILED(Color(0xFFFF0000), "失敗 / failed"),
    READY(null, ""),
    ;

    companion object {
        fun of(s: GengoshimaSentenceEntity): CardState = when (s.state) {
            GengoshimaSentenceEntity.STATE_NEW -> TO_TRANSLATE
            GengoshimaSentenceEntity.STATE_ANNOTATE -> TO_SPLIT
            GengoshimaSentenceEntity.STATE_TRANSLATED -> TO_VOICE
            GengoshimaSentenceEntity.STATE_ERROR -> FAILED
            else -> READY
        }
    }
}

/** What the ⋯ menu asks for. */
internal enum class SentenceAction { SPLIT_ONLY, TRANSLATE_AGAIN, RESPLIT, REVOICE, ANKI }

/**
 * One MediaPlayer for the whole page: starting a recording stops the one playing, and tapping the
 * playing pill stops it. [playing] is (sentence id, source side) or null.
 *
 * The end of a recording is caught twice: by the completion listener, and by a check every half
 * second that the player is still playing. On 白い熊's phone the listener alone left the pill on
 * ■ after the sentence had ended (2026-10-27).
 */
internal class CardPlayer {
    var playing by mutableStateOf<Pair<Long, Boolean>?>(null)
        private set
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val watch = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (runCatching { p.isPlaying }.getOrDefault(false)) main.postDelayed(this, WATCH_MS) else stop()
        }
    }

    fun toggle(id: Long, source: Boolean, path: String) {
        val was = playing
        stop()
        if (was == id to source || path.isEmpty() || !File(path).isFile) return
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { done -> if (done === player) stop() }
                setOnErrorListener { failed, _, _ -> if (failed === player) stop(); true }
                prepare()
                start()
            }
        }.getOrNull()
        if (player != null) {
            playing = id to source
            main.postDelayed(watch, WATCH_MS)
        }
    }

    fun stop() {
        main.removeCallbacks(watch)
        runCatching { player?.release() }
        player = null
        playing = null
    }

    private companion object {
        const val WATCH_MS = 500L
    }
}

@Composable
internal fun SentenceCard(
    s: GengoshimaSentenceEntity,
    first: Boolean,
    last: Boolean,
    canMove: Boolean,
    working: Boolean,
    ankiWaiting: Boolean,
    player: CardPlayer,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onEditSource: () -> Unit,
    onEditJapanese: () -> Unit,
    onWord: (Int) -> Unit,
    onFinish: () -> Unit,
    onAction: (SentenceAction) -> Unit,
) {
    val state = CardState.of(s)
    val outline = state.colour ?: MaterialTheme.colorScheme.outlineVariant
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .border(if (state.colour != null) 2.dp else 1.dp, outline, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The source line, with its own edit pill.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (GengoshimaLanguage.normalize(s.language) == GengoshimaLanguage.CS) {
                LanguageChip(s.language)
                Box(Modifier.padding(end = 6.dp))
            }
            Text("${s.position}　${s.en}", fontSize = 16.sp, modifier = Modifier.weight(1f))
            SmallEditPill(enabled = !working, onClick = onEditSource)
        }
        // The Japanese: each word a button for the word editor; the edit pill edits the text itself.
        val tokens = Translator.tokens(s.tokensJson)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (tokens.isNotEmpty() && tokens.joinToString("") { it.surface } == s.ja) {
                    FlowRow {
                        tokens.forEachIndexed { k, t ->
                            val over = !t.reading_override.isNullOrBlank()
                            Column(
                                Modifier.clickable(enabled = !working) { onWord(k) }.padding(horizontal = 1.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    hiragana(WordEdit.shown(t)),
                                    fontSize = 11.sp,
                                    color = if (over) MaterialTheme.colorScheme.primary else dim,
                                    fontWeight = if (over) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(t.surface, fontSize = 20.sp)
                            }
                        }
                    }
                } else if (s.ja.isNotBlank()) {
                    Text(s.ja, fontSize = 20.sp)
                }
            }
            if (s.ja.isNotBlank()) SmallEditPill(enabled = !working, onClick = onEditJapanese)
        }
        if (s.error.isNotBlank()) Text("✕ ${s.error}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        // What it is waiting for, and what was done by hand — in words.
        val marks = listOfNotNull(
            (state.label to state.colour).takeIf { state != CardState.READY },
            ("作業中… / working…" to MaterialTheme.colorScheme.primary).takeIf { working },
            ("暗記待ち / to 暗記" to dim).takeIf { ankiWaiting && state == CardState.READY },
            ("手書きの日本語 / hand-written" to dim).takeIf { s.jaEdited },
            ("手で分けた / split by hand" to dim).takeIf { s.wordsEdited },
        )
        if (marks.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                marks.forEach { (text, colour) -> StateMark(text, colour ?: dim) }
            }
        }
        // Hear both recordings.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val src = GengoshimaLanguage.chip(s.language)
            val srcOn = player.playing == s.id to true
            val jaOn = player.playing == s.id to false
            ActionPill(
                src, if (srcOn) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                onClick = { player.toggle(s.id, true, s.enAudioPath) }, enabled = s.enAudioPath.isNotEmpty(),
            )
            ActionPill(
                "日本語", if (jaOn) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                onClick = { player.toggle(s.id, false, s.audioPath) }, enabled = s.audioPath.isNotEmpty(),
            )
            val finish = when {
                state == CardState.TO_VOICE -> "声にする / voice it"
                state != CardState.READY -> "仕上げる / finish"
                ankiWaiting -> "暗記へ / to 暗記"
                else -> null
            }
            if (finish != null) ActionPill(finish, Icons.Filled.DoneAll, onClick = onFinish, enabled = !working)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onUp, enabled = !first) { Icon(Icons.Filled.ArrowUpward, "上へ") }
            IconButton(onClick = onDown, enabled = !last) { Icon(Icons.Filled.ArrowDownward, "下へ") }
            IconButton(onClick = onMove, enabled = canMove && !working) { Icon(Icons.Filled.DriveFileMove, "別の島へ / move") }
            IconButton(onClick = onDelete, enabled = !working) { Icon(Icons.Filled.Delete, "消す / delete", tint = MaterialTheme.colorScheme.error) }
            Box(Modifier.weight(1f))
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, enabled = !working) { Icon(Icons.Filled.MoreVert, "その他 / more") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val items = listOfNotNull(
                        ("分けるだけ / split only — check before voicing" to SentenceAction.SPLIT_ONLY)
                            .takeIf { state == CardState.TO_TRANSLATE || state == CardState.TO_SPLIT },
                        ("訳し直す / translate again" to SentenceAction.TRANSLATE_AGAIN).takeIf { s.en.isNotBlank() },
                        ("分け直す / re-split" to SentenceAction.RESPLIT).takeIf { s.ja.isNotBlank() },
                        ("声を作り直す / re-voice both" to SentenceAction.REVOICE).takeIf { s.ja.isNotBlank() },
                        ("暗記を更新 / update in 暗記" to SentenceAction.ANKI).takeIf { state == CardState.READY },
                    )
                    items.forEach { (label, a) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onAction(a) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallEditPill(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(Icons.Filled.Edit, "直す / edit") }
}

@Composable
private fun StateMark(text: String, colour: Color) {
    Text(
        text,
        fontSize = 12.sp,
        color = colour,
        modifier = Modifier
            .border(1.dp, colour, RoundedCornerShape(100.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// ── dialogs ─────────────────────────────────────────────────────────────────────────────────────

/** The source sentence and its language. A change sends the sentence back to be translated. */
@Composable
internal fun SourceEditDialog(s: GengoshimaSentenceEntity, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var en by remember { mutableStateOf(s.en) }
    var language by remember { mutableStateOf(GengoshimaLanguage.normalize(s.language)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("原文を直す / Edit the source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("原文の言語 / source language", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LanguageChip(language, onClick = { language = GengoshimaLanguage.toggle(language) })
                }
                OutlinedTextField(en, { en = it }, label = { Text("原文（${GengoshimaLanguage.japaneseName(language)}） / Source") })
                Text(
                    "直すと訳し直し待ちになります。「仕上げる」で訳して声にします。\n" +
                        "Saved, it waits to be translated again; 「仕上げる」 translates and voices it.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(en.trim(), language) }, enabled = en.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/** The Japanese, kept exactly as typed; only split into words again and voiced. */
@Composable
internal fun JapaneseEditDialog(s: GengoshimaSentenceEntity, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var ja by remember { mutableStateOf(s.ja) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("日本語を直す / Edit the Japanese") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ja, { ja = it }, label = { Text("日本語 / Japanese") })
                Text(
                    "その通りに残します。単語分けと振り仮名は作り直し、手で直した読みは同じ語に残ります。\n" +
                        "Kept exactly. The words and furigana are made again; a reading you set stays on the same word.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(ja.trim()) }, enabled = ja.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/**
 * One word: its furigana, a cut into two, or a join with the next. Every change keeps the words
 * spelling the sentence and marks it split by hand, so no run splits it again.
 */
@Composable
internal fun WordDialog(tokens: List<Translator.Token>, k: Int, onDismiss: () -> Unit, onSave: (List<Translator.Token>) -> Unit) {
    val t = tokens[k]
    var reading by remember { mutableStateOf(hiragana(WordEdit.shown(t))) }
    var cutAt by remember { mutableStateOf<Int?>(null) }
    var readingA by remember { mutableStateOf("") }
    var readingB by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("語を直す / Edit the word — ${t.surface}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.gloss.isNotBlank() || t.base != t.surface) {
                    Text("${t.base} · ${t.gloss}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val cut = cutAt
                if (cut == null) {
                    OutlinedTextField(reading, { reading = it }, label = { Text("振り仮名 / furigana") }, singleLine = true)
                    Text(
                        "声もこの読みで作ります（例: 生 → なま）。\nThe voice reads it this way too (e.g. 生 → なま).",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (t.surface.length > 1) {
                        Text("ここで切る / cut after:", fontSize = 14.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (at in 1 until t.surface.length) {
                                StateMarkButton("${t.surface.take(at)}｜${t.surface.drop(at)}") {
                                    val (a, b) = WordEdit.guessSplit(t, at)
                                    readingA = hiragana(a)
                                    readingB = hiragana(b)
                                    cutAt = at
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(readingA, { readingA = it }, label = { Text("${t.surface.take(cut)} の振り仮名") }, singleLine = true)
                    OutlinedTextField(readingB, { readingB = it }, label = { Text("${t.surface.drop(cut)} の振り仮名") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val cut = cutAt
                onSave(
                    if (cut == null) WordEdit.setReading(tokens, k, reading)
                    else WordEdit.split(tokens, k, cut, readingA, readingB),
                )
            }) { Text(if (cutAt == null) "保存" else "切る / cut") }
        },
        dismissButton = {
            Row {
                if (cutAt != null) {
                    TextButton(onClick = { cutAt = null }) { Text("戻る / back") }
                } else if (k + 1 < tokens.size) {
                    TextButton(onClick = { onSave(WordEdit.joinNext(tokens, k)) }) { Text("次とつなぐ / join next") }
                }
                TextButton(onClick = onDismiss) { Text("やめる") }
            }
        },
    )
}

@Composable
private fun StateMarkButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 18.sp,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(100.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
