package com.opentasker.ui.gengoshima

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.gengoshima.AudioTree
import com.opentasker.core.gengoshima.GenerationRunner
import com.opentasker.core.gengoshima.GengoshimaSettings
import com.opentasker.core.gengoshima.Translator
import com.opentasker.core.storage.GengoshimaDao
import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import com.opentasker.ui.charts.ActionPill
import com.opentasker.ui.components.AlertDialog
import com.opentasker.ui.components.SelectionChip
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import java.io.File
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * 「言語島 編集」 — the islands and everything in them, changed after the fact.
 *
 * Every edit states what it will cost, because the costs differ by a lot:
 * * **Order, a move to another island, a rename, a delete** only rename or remove files — nothing
 *   is re-voiced. They are applied (整理) the moment this window closes.
 * * **Editing the English** sends the sentence back to Claude: its Japanese is written again.
 * * **Editing the Japanese** keeps 白い熊's text exactly; Claude only splits it into words again, and
 *   the sentence is re-voiced.
 * * **A reading** (tap a word) changes only how VOICEVOX says it: the word is sent in katakana, and
 *   only that sentence is re-voiced.
 *
 * Whatever needs Claude or 音声 waits for 「訳して音声を作る」 at the bottom, which opens the same
 * progress window as the entry screen.
 */
class GengoshimaIslandsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    EditorRoot(
                        onClose = { finish() },
                        onGenerate = {
                            GenerationRunner.start(applicationContext, GengoshimaSettings.last(applicationContext))
                            startActivity(GengoshimaProgressActivity.intent(applicationContext))
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Renames and removals are free: apply them now rather than at the next full run.
        if (isFinishing) GenerationRunner.tidy(applicationContext, GengoshimaSettings.last(applicationContext))
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, GengoshimaIslandsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
private fun EditorRoot(onClose: () -> Unit, onGenerate: () -> Unit) {
    val dao = remember { OpenTaskerApp_NoHilt.db.gengoshimaDao() }
    val scope = rememberCoroutineScope()
    val islands by dao.observeIslands().collectAsState(initial = emptyList())
    val counts by dao.observeCounts().collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<Long?>(null) }
    val island = islands.firstOrNull { it.id == open }
    BackHandler(enabled = open != null) { open = null }
    val status by GenerationRunner.status.collectAsState()

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (island != null) TextButton(onClick = { open = null }) { Text("← 島一覧", fontSize = 17.sp) }
            Text(
                if (island == null) "言語島 — 編集" else island.nameJa.ifBlank { island.nameEn },
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("閉じる", fontSize = 18.sp) }
        }
        Column(Modifier.weight(1f)) {
            if (island == null) {
                IslandList(dao, islands, counts.associate { it.islandId to it.total }, onOpen = { open = it })
            } else {
                IslandDetail(dao, island, islands, onDeleted = { open = null })
            }
        }
        val pending = counts.sumOf { it.total - it.ready } > 0
        Button(
            onClick = onGenerate,
            enabled = pending || status != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("訳して音声を作る / Translate & voice", fontSize = 18.sp) }
    }
}

// ── the list of islands ─────────────────────────────────────────────────────────────────────────

@Composable
private fun IslandList(dao: GengoshimaDao, islands: List<GengoshimaIslandEntity>, totals: Map<Long, Int>, onOpen: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(islands, key = { it.id }) { i ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable { onOpen(i.id) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${AudioTree.number(i.position, 3)} ${i.nameJa.ifBlank { i.nameEn }}", fontSize = 18.sp)
                    Text(
                        "${i.nameEn} · ${totals[i.id] ?: 0} 文" + (if (i.register.isNotBlank()) " · ${i.register}" else ""),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { scope.launch { swapIslands(dao, islands, i, -1) } }, enabled = i != islands.first()) {
                    Icon(Icons.Filled.ArrowUpward, "上へ / up")
                }
                IconButton(onClick = { scope.launch { swapIslands(dao, islands, i, +1) } }, enabled = i != islands.last()) {
                    Icon(Icons.Filled.ArrowDownward, "下へ / down")
                }
            }
        }
    }
}

private suspend fun swapIslands(dao: GengoshimaDao, islands: List<GengoshimaIslandEntity>, i: GengoshimaIslandEntity, by: Int) {
    val k = islands.indexOf(i)
    val other = islands.getOrNull(k + by) ?: return
    dao.updateIsland(i.copy(position = other.position))
    dao.updateIsland(other.copy(position = i.position))
}

// ── one island ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun IslandDetail(dao: GengoshimaDao, island: GengoshimaIslandEntity, islands: List<GengoshimaIslandEntity>, onDeleted: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val sentences by remember(island.id) { dao.observeSentences(island.id) }.collectAsState(initial = emptyList())
    var editIsland by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GengoshimaSentenceEntity?>(null) }
    var moving by remember { mutableStateOf<GengoshimaSentenceEntity?>(null) }
    var deleting by remember { mutableStateOf<GengoshimaSentenceEntity?>(null) }
    var deletingIsland by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<Pair<GengoshimaSentenceEntity, Int>?>(null) }
    var studyMessage by remember { mutableStateOf<String?>(null) }
    studyMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { studyMessage = null },
            title = { Text("辞書で学ぶ / Study in 辞書") },
            text = { Text(msg) },
            confirmButton = { Button(onClick = { studyMessage = null }) { Text("閉じる") } },
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(10.dp)) {
                Text(island.nameEn, fontSize = 16.sp)
                Text("話し方 / Register: ${island.register.ifBlank { "（丁寧・既定 / polite, default）" }}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The app's action pills (健康's language): glyph + what pressing does, all yellow.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    ActionPill("島を直す / edit island", Icons.Filled.Edit, onClick = { editIsland = true })
                    ActionPill("辞書で学ぶ / study in 辞書", Icons.Filled.MenuBook, onClick = { studyMessage = studyInJisho(context, island) })
                    ActionPill("島を消す / delete island", Icons.Filled.Delete, onClick = { deletingIsland = true })
                }
            }
        }
        items(sentences, key = { it.id }) { s ->
            Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(8.dp)) {
                Text("${s.position}　${s.en}", fontSize = 16.sp)
                val tokens = Translator.tokens(s.tokensJson)
                if (tokens.isNotEmpty() && tokens.joinToString("") { it.surface } == s.ja) {
                    // Each word a button: tap it to set how VOICEVOX should read it.
                    FlowRow {
                        tokens.forEachIndexed { k, t ->
                            val over = !t.reading_override.isNullOrBlank()
                            Column(
                                Modifier.clickable { reading = s to k }.padding(horizontal = 1.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    hiragana(t.reading_override?.takeIf { it.isNotBlank() } ?: t.reading),
                                    fontSize = 11.sp,
                                    color = if (over) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (over) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(t.surface, fontSize = 20.sp)
                            }
                        }
                    }
                } else if (s.ja.isNotBlank()) {
                    Text(s.ja, fontSize = 20.sp)
                }
                if (s.error.isNotBlank()) Text("✕ ${s.error}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                if (s.state != GengoshimaSentenceEntity.STATE_READY) {
                    Text(stateLabel(s.state), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    IconButton(onClick = { scope.launch { swapSentences(dao, sentences, s, -1) } }, enabled = s != sentences.first()) { Icon(Icons.Filled.ArrowUpward, "上へ") }
                    IconButton(onClick = { scope.launch { swapSentences(dao, sentences, s, +1) } }, enabled = s != sentences.last()) { Icon(Icons.Filled.ArrowDownward, "下へ") }
                    IconButton(onClick = { editing = s }) { Icon(Icons.Filled.Edit, "直す / edit") }
                    IconButton(onClick = { moving = s }, enabled = islands.size > 1) { Icon(Icons.Filled.DriveFileMove, "別の島へ / move") }
                    IconButton(onClick = { deleting = s }) { Icon(Icons.Filled.Delete, "消す / delete", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (editIsland) IslandEditDialog(island, onDismiss = { editIsland = false }) { en, ja, register ->
        editIsland = false
        scope.launch {
            dao.updateIsland(island.copy(nameEn = en, nameJa = ja, register = register))
            // A new register changes how every line should be said: translate the island again,
            // except the lines 白い熊 wrote in Japanese by hand.
            if (register != island.register) {
                val now = System.currentTimeMillis()
                dao.sentences(island.id).filter { !it.jaEdited }.forEach {
                    dao.updateSentence(it.copy(state = GengoshimaSentenceEntity.STATE_NEW, updatedAt = now))
                }
            }
        }
    }
    editing?.let { s ->
        SentenceEditDialog(s, onDismiss = { editing = null }) { en, ja ->
            editing = null
            scope.launch {
                val now = System.currentTimeMillis()
                when {
                    en != s.en -> dao.updateSentence(s.copy(en = en, jaEdited = false, state = GengoshimaSentenceEntity.STATE_NEW, updatedAt = now))
                    ja != s.ja -> dao.updateSentence(s.copy(ja = ja, jaEdited = true, state = GengoshimaSentenceEntity.STATE_ANNOTATE, updatedAt = now))
                }
            }
        }
    }
    moving?.let { s ->
        MoveDialog(islands.filter { it.id != island.id }, onDismiss = { moving = null }) { target ->
            moving = null
            scope.launch {
                dao.updateSentence(s.copy(islandId = target.id, position = dao.lastSentencePosition(target.id) + 1, updatedAt = System.currentTimeMillis()))
                renumber(dao, island.id)
            }
        }
    }
    deleting?.let { s ->
        ConfirmDialog("この文を消しますか？ / Delete this sentence?", "${s.en}\n${s.ja}", onDismiss = { deleting = null }) {
            deleting = null
            scope.launch {
                dao.deleteSentence(s.id)
                // 暗記 still holds its note: the next sync is told, by uuid.
                dao.insertTombstone(com.opentasker.core.storage.GengoshimaTombstoneEntity(s.uuid, "sentence", System.currentTimeMillis()))
                if (s.audioPath.isNotEmpty()) File(s.audioPath).delete()
                if (s.enAudioPath.isNotEmpty()) File(s.enAudioPath).delete()
                renumber(dao, island.id)
            }
        }
    }
    if (deletingIsland) {
        ConfirmDialog(
            "島ごと消しますか？ / Delete the whole island?",
            "${island.nameJa.ifBlank { island.nameEn }} — ${sentences.size} 文と音声がすべて消えます。\nIts ${sentences.size} sentences and their audio go with it.",
            onDismiss = { deletingIsland = false },
        ) {
            deletingIsland = false
            scope.launch {
                val settings = GengoshimaSettings.last(context)
                AudioTree.islandDir(island, settings).takeIf { it.isDirectory }?.let { dir ->
                    // Only the audio this app made; anything else in there is left where it is.
                    dir.listFiles()?.filter { it.name.endsWith(".ogg") || it.name.endsWith(".part") || it.name.endsWith(".srt") }?.forEach { it.delete() }
                    dir.delete()
                }
                val now = System.currentTimeMillis()
                dao.sentences(island.id).forEach { dao.insertTombstone(com.opentasker.core.storage.GengoshimaTombstoneEntity(it.uuid, "sentence", now)) }
                dao.insertTombstone(com.opentasker.core.storage.GengoshimaTombstoneEntity(island.uuid, "island", now))
                dao.deleteSentencesOf(island.id)
                dao.deleteIsland(island.id)
                dao.islands().forEachIndexed { k, i -> if (i.position != k + 1) dao.updateIsland(i.copy(position = k + 1)) }
                onDeleted()
            }
        }
    }
    reading?.let { (s, k) ->
        val tokens = Translator.tokens(s.tokensJson)
        val t = tokens.getOrNull(k)
        if (t == null) reading = null else ReadingDialog(t, onDismiss = { reading = null }) { newReading ->
            reading = null
            scope.launch {
                val updated = tokens.toMutableList().also { it[k] = t.copy(reading_override = newReading?.let(::katakana)) }
                dao.updateSentence(
                    s.copy(
                        tokensJson = Translator.tokensJson(updated),
                        // Only the voice changes: the next run hears the new reading and re-voices this line.
                        state = if (s.state == GengoshimaSentenceEntity.STATE_READY) GengoshimaSentenceEntity.STATE_TRANSLATED else s.state,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
}

private fun stateLabel(state: String) = when (state) {
    GengoshimaSentenceEntity.STATE_NEW -> "訳待ち / to translate"
    GengoshimaSentenceEntity.STATE_ANNOTATE -> "手直し済み・単語分け待ち / edited, to re-split"
    GengoshimaSentenceEntity.STATE_TRANSLATED -> "音声待ち / to voice"
    GengoshimaSentenceEntity.STATE_ERROR -> "失敗 / failed"
    else -> state
}

private suspend fun swapSentences(dao: GengoshimaDao, list: List<GengoshimaSentenceEntity>, s: GengoshimaSentenceEntity, by: Int) {
    val k = list.indexOf(s)
    val other = list.getOrNull(k + by) ?: return
    dao.updateSentence(s.copy(position = other.position))
    dao.updateSentence(other.copy(position = s.position))
}

private suspend fun renumber(dao: GengoshimaDao, islandId: Long) {
    dao.sentences(islandId).forEachIndexed { k, s -> if (s.position != k + 1) dao.updateSentence(s.copy(position = k + 1)) }
}

/** ひらがな → カタカナ, so an override typed on a kana keyboard reaches VOICEVOX as katakana. */
internal fun katakana(text: String): String = buildString {
    for (ch in text.trim()) append(if (ch in 'ぁ'..'ゖ') (ch + 0x60) else ch)
}

// ── dialogs ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun IslandEditDialog(island: GengoshimaIslandEntity, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var en by remember { mutableStateOf(island.nameEn) }
    var ja by remember { mutableStateOf(island.nameJa) }
    var register by remember { mutableStateOf(island.register) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("島を直す / Edit island") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(en, { en = it }, label = { Text("話題（英語） / Topic") }, singleLine = true)
                OutlinedTextField(ja, { ja = it }, label = { Text("日本語の島名 / Japanese name") }, singleLine = true)
                OutlinedTextField(register, { register = it }, label = { Text("話し方 / Register") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    REGISTERS.forEach { (label, value) -> SelectionChip(label = label, selected = register == value, onSelect = { register = value }) }
                }
                Text(
                    "話し方を変えると、手で直した行を除き島全体を訳し直します。\nChanging the register re-translates the island, except lines you wrote in Japanese.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(en.trim(), ja.trim(), register.trim()) }, enabled = en.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/** Same three quick picks as the new-island dialog. */
private val REGISTERS = listOf(
    "丁寧" to "polite (です／ます), everyday",
    "くだけた" to "casual, plain form, to a friend",
    "敬語" to "formal, with honorific and humble 敬語",
)

@Composable
private fun SentenceEditDialog(s: GengoshimaSentenceEntity, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var en by remember { mutableStateOf(s.en) }
    var ja by remember { mutableStateOf(s.ja) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文を直す / Edit sentence") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(en, { en = it }, label = { Text("英文 / English") })
                OutlinedTextField(ja, { ja = it }, label = { Text("日本語 / Japanese") })
                Text(
                    "英文を直す → 訳し直し。日本語を直す → その通りに残し、単語分けと音声だけ作り直す。\n" +
                        "Edit the English → translated again. Edit the Japanese → kept exactly; only re-split and re-voiced.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(en.trim(), ja.trim()) }, enabled = en.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

@Composable
private fun ReadingDialog(t: Translator.Token, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var r by remember { mutableStateOf(hiragana(t.reading_override?.takeIf { it.isNotBlank() } ?: t.reading)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("読み / Reading — ${t.surface}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${t.base} · ${t.gloss}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(r, { r = it }, label = { Text("読み（かな） / reading in kana") }, singleLine = true)
                Text(
                    "音声はこの読みで作り直します（例: 生 → なま）。\nThe line is re-voiced with this reading (e.g. 生 → なま).",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(r.trim().ifBlank { null }) }) { Text("保存") } },
        dismissButton = {
            Row {
                if (!t.reading_override.isNullOrBlank()) TextButton(onClick = { onSave(null) }) { Text("元に戻す / reset") }
                TextButton(onClick = onDismiss) { Text("やめる") }
            }
        },
    )
}

@Composable
private fun MoveDialog(targets: List<GengoshimaIslandEntity>, onDismiss: () -> Unit, onPick: (GengoshimaIslandEntity) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("どの島へ / Move to which island") },
        text = {
            LazyColumn {
                items(targets, key = { it.id }) { i ->
                    Text(
                        "${AudioTree.number(i.position, 3)} ${i.nameJa.ifBlank { i.nameEn }}",
                        fontSize = 18.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 10.dp),
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { Button(onClick = onConfirm) { Text("消す / delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/**
 * Open the island's whole-island file in 白い熊の辞書's study player, its subtitles beside it — every
 * word tappable for the pop-up dictionary and Anki. Returns a message to show, or null when 辞書
 * opened it.
 */
private fun studyInJisho(context: Context, island: GengoshimaIslandEntity): String? {
    val s = GengoshimaSettings.last(context)
    val ogg = com.opentasker.core.gengoshima.IslandExport.oggFile(island, s)
    if (!ogg.isFile) {
        return "この島の「${s.islandFileName}」はまだありません — 「訳して音声を作る」を一度走らせてください。\n" +
            "This island's whole-island file does not exist yet — run 「訳して音声を作る」 once."
    }
    val intent = Intent(STUDY_AUDIO)
        .setPackage("shiroikuma.jisho")
        .putExtra("path", ogg.absolutePath)
        .putExtra("title", island.nameJa.ifBlank { island.nameEn })
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (context.packageManager.queryIntentActivities(intent, 0).isEmpty()) {
        return "白い熊の辞書 はまだこの受け口（STUDY_AUDIO）を持っていません。辞書側の更新を待ってください。\n" +
            "白い熊の辞書 does not accept STUDY_AUDIO yet; it needs an update on its side.\n\n${ogg.absolutePath}"
    }
    return runCatching { context.startActivity(intent); null }.getOrElse { "開けませんでした / could not open: ${it.message}" }
}

/** The intent 白い熊の辞書 answers with its study player (hand-off: hand-off-study-audio.md in its repo). */
private const val STUDY_AUDIO = "shiroikuma.jisho.intent.action.STUDY_AUDIO"
