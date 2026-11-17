package com.opentasker.ui.gengoshima

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.opentasker.core.gengoshima.GenerationRunner
import com.opentasker.core.gengoshima.GengoshimaSettings
import com.opentasker.core.gengoshima.VocabImport
import com.opentasker.core.gengoshima.VocabImporter
import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.ui.components.AlertDialog
import com.opentasker.ui.components.Button
import com.opentasker.ui.components.OutlinedButton
import com.opentasker.ui.components.TextButton
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 「言語島 取り込む」 — the vocabulary and sentences another chat handed over as text
 * ([VocabImport], `docs/gengoshima-import-format.md`), from paste to voiced in one tap.
 *
 * The text arrives one of three ways: shared to this window from any app (`ACTION_SEND`), already
 * on the clipboard when the window opens (taken at once when it starts with the header), or pasted
 * into the field. What is shown is the PLAN — which island each block goes to, what is new and what
 * is already there, every line that could not be read with its number — and nothing is written
 * until 「取り込んで音声を作る」, which files it all and starts the usual run in its progress window.
 */
class GengoshimaImportActivity : ComponentActivity() {

    private val text = MutableStateFlow("")
    private var clipboardTried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        take(intent)
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val current by text.collectAsState()
                    ImportScreen(
                        text = current,
                        onText = { text.value = it },
                        onPaste = { clipboard()?.let { text.value = it } },
                        onClose = { leave() },
                        onImported = { generate ->
                            if (generate) {
                                GenerationRunner.start(applicationContext, GengoshimaSettings.last(applicationContext))
                                startActivity(GengoshimaProgressActivity.intent(applicationContext))
                            }
                            // With the run's window up, that window is where this one hands over to.
                            if (generate) finish() else leave()
                        },
                    )
                }
            }
        }
    }

    /** Opened from the board's tile: back to the board, which closed itself to hand over. */
    private fun leave() {
        if (intent?.getBooleanExtra(EXTRA_FROM_BOARD, false) == true) closeToBoard() else finish()
    }

    override fun onNewIntent(intent: Intent) {
        setIntent(intent)
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        val shared = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.getStringExtra(EXTRA_TEXT)
        if (!shared.isNullOrBlank()) {
            text.value = shared
            clipboardTried = true
        }
    }

    /** The clipboard is readable only once the window has focus — so this, not onCreate, looks at it. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || clipboardTried) return
        clipboardTried = true
        if (text.value.isBlank()) clipboard()?.takeIf { VocabImport.looksLikeImport(it) }?.let { text.value = it }
    }

    private fun clipboard(): String? = runCatching {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        private const val EXTRA_TEXT = "gengoshima_import_text"

        private const val EXTRA_FROM_BOARD = "gengoshima_import_from_board"

        fun intent(context: Context, text: String? = null, fromBoard: Boolean = false): Intent =
            Intent(context, GengoshimaImportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_FROM_BOARD, fromBoard)
                .apply { if (text != null) putExtra(EXTRA_TEXT, text) }
    }
}

@Composable
private fun ImportScreen(
    text: String,
    onText: (String) -> Unit,
    onPaste: () -> Unit,
    onClose: () -> Unit,
    onImported: (generate: Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dao = remember { OpenTaskerApp_NoHilt.db.gengoshimaDao() }
    val scope = rememberCoroutineScope()
    val islands by dao.observeIslands().collectAsState(initial = emptyList())
    // Where 白い熊 sent a block, by its index — over whatever its 島: line says.
    val choices = remember(text) { mutableStateMapOf<Int, IslandChoice>() }
    val written = remember(text) { VocabImport.parse(text) }
    // A block sent to a NEW island of another name is that block under that name; one sent to an
    // existing island keeps its own name and goes by id.
    val chosen = choices.toMap()
    val parsed = remember(written, chosen) {
        written.copy(
            blocks = written.blocks.mapIndexed { i, b ->
                (chosen[i] as? IslandChoice.New)?.let { n ->
                    val ja = com.opentasker.core.gengoshima.GengoshimaLanguage.islandNameFromTopic(n.name)
                    b.copy(nameJa = ja, nameEn = if (ja.isEmpty()) n.name else "", register = n.register)
                } ?: b
            },
        )
    }
    val targets = remember(chosen) { chosen.mapNotNull { (i, c) -> (c as? IslandChoice.Existing)?.let { i to it.id } }.toMap() }
    var plan by remember { mutableStateOf<VocabImport.Plan?>(null) }
    LaunchedEffect(parsed, islands, targets) { plan = VocabImporter.plan(parsed, targets) }
    var editing by remember { mutableStateOf(false) }
    // The block whose island is being chosen; [ALL_BLOCKS] = every block at once.
    var picking by remember { mutableStateOf<Int?>(null) }
    // A text file instead of the clipboard.
    val openFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                .onSuccess { read -> if (!read.isNullOrBlank()) { onText(read.removePrefix("\uFEFF")); editing = false } }
        }
    }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // What is already entered is never added again; it is also kept out of the list unless asked for.
    var showKnown by remember { mutableStateOf(false) }

    fun file(generate: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { VocabImporter.file(context.applicationContext, parsed, targets) }
                .onSuccess { onImported(generate) }
                .onFailure { failure = it.message ?: it.toString(); busy = false }
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("言語島 — 取り込む", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(
                    "語彙と文を貼り付けて取り込む / paste vocabulary and sentences",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HeaderPill("閉じる", onClose)
        }

        val p = plan
        if (text.isBlank() || editing || p == null || parsed.empty && parsed.problems.isEmpty()) {
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text("${VocabImport.HEADER} …") },
                placeholder = { Text("島: 仕事 | Work\n語: 会議【かいぎ】 = meeting\n  例: 会議は三時からです。 = The meeting starts at three.\n文: 明日は休みを取ります。 = I am taking tomorrow off.") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onPaste(); editing = false }) { Text("貼り付け / Paste") }
                OutlinedButton(onClick = { openFile.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("ファイルを開く / Open file") }
                if (editing) OutlinedButton(onClick = { editing = false }) { Text("内容を確かめる / Preview") }
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(
                    "島 +${p.newIslands} · 語 +${p.newTerms} · 文 +${p.newSentences}" +
                        (if (p.knownTerms + p.knownSentences > 0) "　（既にある: 語 ${p.knownTerms} · 文 ${p.knownSentences}）" else ""),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (parsed.problems.isNotEmpty()) {
                item {
                    Text(
                        "読めない行 ${parsed.problems.size} — これらは取り込まれません / these lines are left out:",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(parsed.problems, key = { "p${it.line}" }) { problem ->
                    Text("${problem.line} 行目　${problem.message}", color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
                }
            }
            if (p.knownTerms + p.knownSentences > 0) {
                item(key = "known") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "既に入っている語 ${p.knownTerms} · 文 ${p.knownSentences} は取り込みません / already entered — not added again",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TogglePill("既にあるものも表示 / show them too", showKnown) { showKnown = !showKnown }
                    }
                }
            }
            if (p.blocks.size > 1) {
                item(key = "all") {
                    OutlinedButton(onClick = { picking = ALL_BLOCKS }) { Text("すべての島を変える / Send everything to one island") }
                }
            }
            p.blocks.forEachIndexed { index, bp ->
                item(key = "b$index") {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    bp.needsIsland -> "島が決まっていません / no island yet"
                                    bp.islandId == null -> "＋ ${bp.islandLabel}"
                                    else -> bp.islandLabel
                                },
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (bp.needsIsland) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                            // Said outright, so nothing lands in an island 白い熊 did not see named.
                            if (!bp.needsIsland) Text(
                                if (bp.islandId == null) "新しい島を作ります / a new island will be created"
                                else "既にある島に追加します / added to this existing island",
                                fontSize = 14.sp,
                            )
                            Text(
                                "語 +${bp.newTerms} · 文 +${bp.newSentences}" +
                                    if (bp.knownTerms + bp.knownSentences > 0) " · 既に 語 ${bp.knownTerms} 文 ${bp.knownSentences}" else "",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { picking = index }) { Text("島を変える") }
                    }
                }
                // A term already entered still shows when it brings a new example; its line says so.
                val rows = bp.block.items.withIndex().filter { (_, item) ->
                    showKnown || when (item) {
                        is VocabImport.Term -> item.line !in bp.known || item.examples.any { it.line !in bp.known }
                        is VocabImport.Sentence -> item.text.line !in bp.known
                    }
                }
                items(rows, key = { "b$index-${it.index}" }) { (_, item) ->
                    val soft = MaterialTheme.colorScheme.onSurfaceVariant
                    when (item) {
                        is VocabImport.Term -> Column(Modifier.fillMaxWidth()) {
                            val where = bp.known[item.line]
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.term, fontSize = 20.sp, fontWeight = if (where == null) FontWeight.Bold else FontWeight.Normal)
                                if (item.reading.isNotEmpty()) {
                                    Text(item.reading, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                                }
                                if (where != null) Text("既にある（$where）", fontSize = 13.sp, color = soft)
                            }
                            Text(item.meaning, fontSize = 16.sp)
                            item.examples.filter { showKnown || it.line !in bp.known }.forEach { ex ->
                                val there = bp.known[ex.line]
                                Text("　${ex.ja}" + (there?.let { "　— 既にある（$it）" } ?: ""), fontSize = 15.sp, color = if (there == null) MaterialTheme.colorScheme.onSurface else soft)
                                Text("　${ex.en}", fontSize = 13.sp, color = soft)
                            }
                            if (item.note.isNotEmpty()) Text("　注: ${item.note}", fontSize = 13.sp, color = soft)
                        }
                        is VocabImport.Sentence -> Column(Modifier.fillMaxWidth()) {
                            val there = bp.known[item.text.line]
                            Text(item.text.ja + (there?.let { "　— 既にある（$it）" } ?: ""), fontSize = 16.sp, color = if (there == null) MaterialTheme.colorScheme.onSurface else soft)
                            Text(item.text.en, fontSize = 13.sp, color = soft)
                        }
                    }
                }
            }
        }

        failure?.let { Text("✕ $it", color = MaterialTheme.colorScheme.error) }
        val nothing = p.newTerms + p.newSentences + p.knownTerms == 0
        Button(
            onClick = { file(generate = true) },
            enabled = p.ready && !nothing && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("取り込んで音声を作る / Import & voice", fontSize = 18.sp)
                // Where it all goes, on the button that sends it there.
                val names = p.blocks.filter { !it.needsIsland }.map { (if (it.islandId == null) "＋" else "") + it.islandLabel.substringBefore(" — ") }.distinct()
                if (names.isNotEmpty()) Text("→ " + names.joinToString(" · "), fontSize = 14.sp, fontWeight = FontWeight.Normal)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { file(generate = false) }, enabled = p.ready && !nothing && !busy) { Text("取り込むだけ / Import only") }
            OutlinedButton(onClick = { editing = true }) { Text("文面を直す / Edit text") }
            OutlinedButton(onClick = { openFile.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("ファイルを開く / Open file") }
        }
    }

    picking?.let { index ->
        val all = index == ALL_BLOCKS
        val block = if (all) null else written.blocks.getOrNull(index)
        TargetDialog(
            islands = islands,
            title = if (all) "すべてをひとつの島へ / Everything to one island" else "島を選ぶ / Choose an island",
            // The island the text itself names — to go back to after sending the block elsewhere.
            named = block?.label.orEmpty(),
            onDismiss = { picking = null },
            onPick = { choice ->
                val which = if (all) written.blocks.indices else listOf(index)
                which.forEach { i -> if (choice == null) choices.remove(i) else choices[i] = choice }
                picking = null
            },
        )
    }
}

private const val ALL_BLOCKS = -1

/** Where 白い熊 sent a block of the text, in place of the island its `島:` line names. */
private sealed interface IslandChoice {
    data class Existing(val id: Long) : IslandChoice
    data class New(val name: String, val register: String) : IslandChoice
}

/**
 * Where a block goes: an existing island, a new island of another name, or — [named] — back to the
 * island its own `島:` line names (`null`).
 */
@Composable
private fun TargetDialog(
    islands: List<GengoshimaIslandEntity>,
    title: String,
    named: String,
    onDismiss: () -> Unit,
    onPick: (IslandChoice?) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NewIslandDialog(onDismiss = { creating = false }) { name, register ->
            creating = false
            if (name.isNotBlank()) onPick(IslandChoice.New(name.trim(), register.trim()))
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                if (named.isNotEmpty()) item {
                    TextButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("$named（文面のとおり / as written）", fontSize = 17.sp, modifier = Modifier.fillMaxWidth())
                    }
                }
                items(islands, key = { it.id }) { isl ->
                    TextButton(onClick = { onPick(IslandChoice.Existing(isl.id)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(islandLabel(isl), fontSize = 17.sp, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { creating = true }) { Text("＋ 新しい島") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("やめる") } },
    )
}
