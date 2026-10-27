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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Sync
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
import com.opentasker.core.gengoshima.GengoshimaLanguage
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
 * * **A word** (tap it) can be given its furigana, cut in two or joined with the next. The sentence
 *   is then split by hand, and no run splits it again ([com.opentasker.core.gengoshima.WordEdit]).
 *
 * Each sentence card plays both recordings and says in words and colour what it waits for
 * ([SentenceCard], 2026-10-26). 「仕上げる」 on a card or the island runs just those sentences —
 * translate, split, voice, check, 暗記 — while the page stays open; 「分けるだけ」 stops after the split
 * so the words can be corrected before anything is voiced. 「訳して音声を作る」 at the bottom still
 * runs the whole workspace, in the progress window.
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
                        onClose = { closeToBoard() },
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
            HeaderPill("閉じる", onClose)
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
    val working by GenerationRunner.working.collectAsState()
    val progress by GenerationRunner.progress.collectAsState()
    val settings = remember { GengoshimaSettings.last(context) }
    val player = remember { CardPlayer() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { player.stop() } }
    var editIsland by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf<GengoshimaSentenceEntity?>(null) }
    var editingJapanese by remember { mutableStateOf<GengoshimaSentenceEntity?>(null) }
    var moving by remember { mutableStateOf<GengoshimaSentenceEntity?>(null) }
    var deleting by remember { mutableStateOf<GengoshimaSentenceEntity?>(null) }
    var deletingIsland by remember { mutableStateOf(false) }
    var word by remember { mutableStateOf<Pair<GengoshimaSentenceEntity, Int>?>(null) }
    var discarding by remember { mutableStateOf<Pair<GengoshimaSentenceEntity, SentenceAction>?>(null) }
    var studyMessage by remember { mutableStateOf<String?>(null) }
    studyMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { studyMessage = null },
            title = { Text("辞書で学ぶ / Study in 辞書") },
            text = { Text(msg) },
            confirmButton = { Button(onClick = { studyMessage = null }) { Text("閉じる") } },
        )
    }
    // 暗記 waits for a sentence whose note no longer says what the sentence says.
    val ankiWaiting: (GengoshimaSentenceEntity) -> Boolean = { s ->
        settings.ankiSync && com.opentasker.core.gengoshima.AnkiIslands.eligible(s) &&
            com.opentasker.core.gengoshima.AnkiIslands.ankiHash(s, island) != s.ankiHash
    }
    val running = progress?.running == true

    /** A run limited to [ids]; the page stays open and the cards show it working. */
    fun launch(ids: Set<Long>, splitOnly: Boolean = false, ankiOnly: Boolean = false) {
        val started = if (ankiOnly) {
            GenerationRunner.start(context, settings, GenerationRunner.Mode.EDITOR, only = ids)
        } else {
            GenerationRunner.start(context, settings, only = ids, splitOnly = splitOnly)
        }
        if (!started) {
            android.widget.Toast.makeText(context, "別の作業が走っています / another run is going", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun act(s: GengoshimaSentenceEntity, a: SentenceAction) {
        scope.launch {
            val now = System.currentTimeMillis()
            when (a) {
                SentenceAction.SPLIT_ONLY -> launch(setOf(s.id), splitOnly = true)
                // A fresh translation and a fresh split stop before voicing, so the new words can be
                // checked and corrected first; 「声にする」 then finishes it.
                SentenceAction.TRANSLATE_AGAIN -> {
                    dao.updateSentence(s.copy(state = GengoshimaSentenceEntity.STATE_NEW, jaEdited = false, wordsEdited = false, updatedAt = now))
                    launch(setOf(s.id), splitOnly = true)
                }
                SentenceAction.RESPLIT -> {
                    dao.updateSentence(s.copy(state = GengoshimaSentenceEntity.STATE_ANNOTATE, wordsEdited = false, updatedAt = now))
                    launch(setOf(s.id), splitOnly = true)
                }
                // Forgetting what the files say makes both stale: the run voices them again.
                SentenceAction.REVOICE -> {
                    dao.updateSentence(s.copy(audioHash = "", enAudioHash = "", updatedAt = now))
                    launch(setOf(s.id))
                }
                // Forgetting what 暗記 holds makes the note stale: the delta sync sends it again.
                SentenceAction.ANKI -> {
                    dao.updateSentence(s.copy(ankiHash = "", updatedAt = now))
                    launch(setOf(s.id), ankiOnly = true)
                }
            }
        }
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
                    val waiting = sentences.filter { it.state != GengoshimaSentenceEntity.STATE_READY || ankiWaiting(it) }
                    ActionPill(
                        "島を仕上げる / finish island" + if (waiting.isNotEmpty()) " (${waiting.size})" else "",
                        Icons.Filled.DoneAll,
                        onClick = {
                            if (waiting.any { it.state != GengoshimaSentenceEntity.STATE_READY }) launch(waiting.map { it.id }.toSet())
                            else launch(waiting.map { it.id }.toSet(), ankiOnly = true)
                        },
                        enabled = waiting.isNotEmpty() && !running,
                    )
                    ActionPill(
                        "島を暗記で更新 / update island in 暗記", Icons.Filled.Sync,
                        onClick = {
                            scope.launch {
                                val now = System.currentTimeMillis()
                                val ready = sentences.filter { com.opentasker.core.gengoshima.AnkiIslands.eligible(it) }
                                ready.forEach { dao.updateSentence(it.copy(ankiHash = "", updatedAt = now)) }
                                launch(ready.map { it.id }.toSet(), ankiOnly = true)
                            }
                        },
                        enabled = settings.ankiSync && !running,
                    )
                    ActionPill("島を消す / delete island", Icons.Filled.Delete, onClick = { deletingIsland = true })
                }
                // The run going now, one line; tap it for the whole progress window.
                progress?.takeIf { it.running }?.let { p ->
                    val now = p.steps.firstOrNull { it.state == GenerationRunner.StepState.RUN }
                    Text(
                        "作業中 / working — " + (now?.let { "${it.title}: ${it.detail.lineSequence().firstOrNull().orEmpty()}" } ?: "…"),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp).clickable { context.startActivity(GengoshimaProgressActivity.intent(context)) },
                    )
                }
            }
        }
        items(sentences, key = { it.id }) { s ->
            SentenceCard(
                s = s,
                first = s == sentences.first(),
                last = s == sentences.last(),
                canMove = islands.size > 1,
                working = running && (working.isEmpty() || s.id in working),
                ankiWaiting = ankiWaiting(s),
                player = player,
                onUp = { scope.launch { swapSentences(dao, sentences, s, -1) } },
                onDown = { scope.launch { swapSentences(dao, sentences, s, +1) } },
                onMove = { moving = s },
                onDelete = { deleting = s },
                onEditSource = { editingSource = s },
                onEditJapanese = { editingJapanese = s },
                onWord = { k -> word = s to k },
                onFinish = {
                    if (s.state == GengoshimaSentenceEntity.STATE_READY) launch(setOf(s.id), ankiOnly = true) else launch(setOf(s.id))
                },
                onAction = { a ->
                    val loses = (a == SentenceAction.TRANSLATE_AGAIN && (s.jaEdited || s.wordsEdited)) ||
                        (a == SentenceAction.RESPLIT && s.wordsEdited)
                    if (loses) discarding = s to a else act(s, a)
                },
            )
        }
    }

    if (editIsland) IslandEditDialog(island, onDismiss = { editIsland = false }) { en, ja, register ->
        editIsland = false
        scope.launch {
            dao.updateIsland(island.copy(nameEn = en, nameJa = ja, register = register))
            // A new register changes how every line should be said: translate the island again,
            // except the lines 白い熊 wrote or split by hand.
            if (register != island.register) {
                val now = System.currentTimeMillis()
                dao.sentences(island.id).filter { !it.jaEdited && !it.wordsEdited }.forEach {
                    dao.updateSentence(it.copy(state = GengoshimaSentenceEntity.STATE_NEW, updatedAt = now))
                }
            }
        }
    }
    editingSource?.let { s ->
        SourceEditDialog(s, onDismiss = { editingSource = null }) { en, language ->
            editingSource = null
            scope.launch {
                val now = System.currentTimeMillis()
                when {
                    // A new language means the source was read wrong: translate it again from the
                    // right one, and drop the old source reading — it is the wrong voice, and its
                    // file name carries the old language.
                    language != GengoshimaLanguage.normalize(s.language) -> {
                        if (s.enAudioPath.isNotEmpty()) File(s.enAudioPath).delete()
                        dao.updateSentence(
                            s.copy(
                                en = en, language = language, jaEdited = false, wordsEdited = false,
                                state = GengoshimaSentenceEntity.STATE_NEW,
                                enAudioPath = "", enAudioHash = "", enDurationMs = 0, updatedAt = now,
                            ),
                        )
                    }
                    en != s.en -> dao.updateSentence(
                        s.copy(en = en, jaEdited = false, wordsEdited = false, state = GengoshimaSentenceEntity.STATE_NEW, updatedAt = now),
                    )
                }
            }
        }
    }
    editingJapanese?.let { s ->
        JapaneseEditDialog(s, onDismiss = { editingJapanese = null }) { ja ->
            editingJapanese = null
            if (ja != s.ja) scope.launch {
                // The words no longer spell it: split again, keeping 白い熊's readings on the same words.
                dao.updateSentence(
                    s.copy(ja = ja, jaEdited = true, wordsEdited = false, state = GengoshimaSentenceEntity.STATE_ANNOTATE, updatedAt = System.currentTimeMillis()),
                )
            }
        }
    }
    discarding?.let { (s, a) ->
        AlertDialog(
            onDismissRequest = { discarding = null },
            title = { Text(if (a == SentenceAction.RESPLIT) "分け直しますか？ / Re-split?" else "訳し直しますか？ / Translate again?") },
            text = {
                Text(
                    if (a == SentenceAction.RESPLIT) {
                        "手で分けた語は自動の分け方に戻ります。同じ語に付けた読みは残ります。\n" +
                            "Your split goes back to the automatic one. Readings you set stay on the same words."
                    } else {
                        "手で書いた日本語と分け方は新しい訳に置き換わります。\nYour own Japanese and split are replaced by a new translation."
                    },
                )
            },
            confirmButton = { Button(onClick = { discarding = null; act(s, a) }) { Text("進める / go ahead") } },
            dismissButton = { TextButton(onClick = { discarding = null }) { Text("やめる") } },
        )
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
    word?.let { (s, k) ->
        val tokens = Translator.tokens(s.tokensJson)
        if (k !in tokens.indices) word = null else WordDialog(tokens, k, onDismiss = { word = null }) { updated ->
            word = null
            scope.launch {
                dao.updateSentence(
                    s.copy(
                        tokensJson = Translator.tokensJson(updated),
                        wordsEdited = true,
                        // Only the voice can change: a ready sentence waits to be voiced again (the
                        // run re-voices it only if the spoken text moved).
                        state = if (s.state == GengoshimaSentenceEntity.STATE_READY) GengoshimaSentenceEntity.STATE_TRANSLATED else s.state,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
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
