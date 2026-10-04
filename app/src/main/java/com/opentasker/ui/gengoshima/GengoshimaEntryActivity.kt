package com.opentasker.ui.gengoshima

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.opentasker.ui.components.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.gengoshima.AudioTree
import com.opentasker.core.gengoshima.GenerationRunner
import com.opentasker.core.gengoshima.GengoshimaSettings
import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import com.opentasker.ui.components.SelectionChip
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * 「言語島 文入力」 — writing the English, sentence after sentence.
 *
 * Ends on one of two buttons: 「訳して音声を作る」 starts the run and opens its progress window in
 * this one's place; 「閉じる」 only closes, and the sentences wait for the next run.
 *
 * One island at a time, chosen from the chips at the top (or a new one). The field adds a sentence
 * to the END of that island on Enter, because an island is spoken in the order it was written. The
 * island's sentences so far stand below, in order, so the next one can follow on from them; while
 * typing, any sentence in ANY island that already says the same shows above them, so nothing is
 * written twice.
 *
 * The run — translation, then voicing, then 整理 — goes on in the background, on the settings the
 * opening task left behind; nothing here waits for Claude or for 音声. It is started by an explicit
 * button, not by closing: 白い熊 wants to see it happen, step by step (2026-09-30).
 */
class GengoshimaEntryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    EntryScreen(
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

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, GengoshimaEntryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
private fun EntryScreen(onClose: () -> Unit, onGenerate: () -> Unit) {
    val dao = remember { OpenTaskerApp_NoHilt.db.gengoshimaDao() }
    val scope = rememberCoroutineScope()
    val islands by dao.observeIslands().collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<Long?>(null) }
    // The newest island is where writing most likely continues.
    LaunchedEffect(islands) {
        if (selected == null || islands.none { it.id == selected }) selected = islands.lastOrNull()?.id
    }
    val island = islands.firstOrNull { it.id == selected }
    val sentences by remember(selected) {
        selected?.let { dao.observeSentences(it) } ?: emptyFlow()
    }.collectAsState(initial = emptyList())
    var text by remember { mutableStateOf("") }
    val query = text.trim()
    val matches by remember(query) {
        if (query.length >= 3) dao.observeMatches(query) else emptyFlow()
    }.collectAsState(initial = emptyList())
    val status by GenerationRunner.status.collectAsState()
    // Re-counted whenever the visible island's rows change, which is when an add could have made it grow.
    var pendingAnywhere by remember { mutableStateOf(0) }
    LaunchedEffect(sentences) { pendingAnywhere = dao.pendingWork() }
    var newIsland by remember { mutableStateOf(false) }

    fun add() {
        val en = text.trim()
        val target = island ?: return
        if (en.isEmpty()) return
        text = ""
        scope.launch {
            val now = System.currentTimeMillis()
            dao.insertSentence(
                GengoshimaSentenceEntity(
                    islandId = target.id,
                    position = dao.lastSentencePosition(target.id) + 1,
                    en = en,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("言語島 — 文を入れる", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(
                    status ?: "書き終えたら下の「訳して音声を作る」/ when done, press Translate & voice below",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClose) { Text("閉じる", fontSize = 18.sp) }
        }

        // Walk-captured sentences waiting for an island (kxkb → 未分類).
        val inboxCount by dao.observeInboxCount().collectAsState(initial = 0)
        if (inboxCount > 0) {
            val context = androidx.compose.ui.platform.LocalContext.current
            OutlinedButton(onClick = { context.startActivity(GengoshimaInboxActivity.intent(context)) }, modifier = Modifier.fillMaxWidth()) {
                Text("未分類 ($inboxCount) — 島に入れる / file captured sentences")
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(islands, key = { it.id }) { i ->
                SelectionChip(label = islandLabel(i), selected = i.id == selected, onSelect = { selected = i.id })
            }
            item { OutlinedButton(onClick = { newIsland = true }) { Text("＋ 新しい島") } }
        }

        if (island == null) {
            Text(
                "まず島を作ってください — 話題ひとつにつき島ひとつ。\nMake an island first: one topic, one island.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> if (v.endsWith("\n")) { text = v.trimEnd('\n'); add() } else text = v },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                label = { Text("英文 — Enter で追加 / English sentence, Enter adds it") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Button(onClick = { add() }, enabled = query.isNotEmpty()) { Text("追加 / Add") }
                Spacer(Modifier.width(12.dp))
                Text("${sentences.size} 文", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (matches.isNotEmpty()) {
                item {
                    Text("似た文がもうあります / already written:", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
                items(matches, key = { "m${it.id}" }) { m ->
                    val where = islands.firstOrNull { it.id == m.islandId }
                    Text(
                        "${where?.let { islandLabel(it) } ?: "?"} · ${m.position}　${m.en}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                item { HorizontalDivider() }
            }
            items(sentences, key = { it.id }) { s -> SentenceRow(s) }
        }

        // The way out that does the work. Enabled whenever anything is waiting — in ANY island,
        // not just this one — or while a run is already going, when it simply shows that run.
        val waiting = (islands.isNotEmpty() && (sentences.any { it.state != GengoshimaSentenceEntity.STATE_READY } || pendingAnywhere > 0)) ||
            status != null
        Button(
            onClick = onGenerate,
            enabled = waiting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("訳して音声を作る / Translate & voice", fontSize = 18.sp) }
    }

    if (newIsland) {
        NewIslandDialog(
            onDismiss = { newIsland = false },
            onCreate = { en, register ->
                newIsland = false
                scope.launch {
                    val id = dao.insertIsland(
                        GengoshimaIslandEntity(
                            position = dao.lastIslandPosition() + 1,
                            nameEn = en,
                            register = register,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    selected = id
                }
            },
        )
    }
}

internal fun islandLabel(i: GengoshimaIslandEntity): String =
    "${AudioTree.number(i.position, 3)} ${i.nameJa.ifBlank { i.nameEn }}"

@Composable
private fun SentenceRow(s: GengoshimaSentenceEntity) {
    Column(Modifier.fillMaxWidth()) {
        Text("${s.position}　${s.en}", fontSize = 17.sp)
        val (mark, color) = when (s.state) {
            GengoshimaSentenceEntity.STATE_READY -> "♪ " to MaterialTheme.colorScheme.primary
            GengoshimaSentenceEntity.STATE_TRANSLATED -> "… " to MaterialTheme.colorScheme.onSurfaceVariant
            GengoshimaSentenceEntity.STATE_ERROR -> "✕ " to MaterialTheme.colorScheme.error
            else -> "" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        when {
            s.ja.isNotBlank() -> Text(mark + s.ja, fontSize = 17.sp, color = color)
            s.error.isNotBlank() -> Text("✕ " + s.error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            else -> Text("訳待ち / to translate", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun NewIslandDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var en by remember { mutableStateOf("") }
    var register by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新しい島 / New island") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(en, { en = it }, label = { Text("話題（英語） / Topic") }, singleLine = true)
                OutlinedTextField(
                    register, { register = it },
                    label = { Text("話し方 / Register") },
                    placeholder = { Text("例: 同僚に、丁寧に / to a colleague, polite") },
                )
                // Said up front, not only as a placeholder: a hint that appears once the field is
                // tapped explains nothing to someone deciding whether to tap it (白い熊, 2026-09-30).
                Text(
                    "誰に・どんな場面で話すか。島の全文の丁寧さがこれで決まる。空なら丁寧（です／ます）。\n" +
                        "Who you're talking to, and how formally: it sets the politeness of every sentence " +
                        "in the island. Blank means polite (です／ます).",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Quick picks that FILL the field — it stays editable, so "polite" can become
                // "to a colleague, polite" without starting over.
                // Wraps rather than clips: the folded screen at 白い熊's font scale is narrow.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    REGISTER_PRESETS.forEach { (label, value) ->
                        SelectionChip(label = label, selected = register == value, onSelect = { register = value })
                    }
                }
                Text("日本語の島名は訳と一緒に付きます。\nThe Japanese name comes with the translation.", fontSize = 13.sp)
            }
        },
        confirmButton = { Button(onClick = { onCreate(en.trim(), register.trim()) }, enabled = en.isNotBlank()) { Text("作る") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/** The three registers most islands want; each chip writes its text into the Register field. */
private val REGISTER_PRESETS = listOf(
    "丁寧" to "polite (です／ます), everyday",
    "くだけた" to "casual, plain form, to a friend",
    "敬語" to "formal, with honorific and humble 敬語",
)
