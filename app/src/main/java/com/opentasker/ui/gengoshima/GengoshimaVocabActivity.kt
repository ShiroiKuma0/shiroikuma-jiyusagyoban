package com.opentasker.ui.gengoshima

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.gengoshima.Translator
import com.opentasker.core.gengoshima.VocabImport
import com.opentasker.core.gengoshima.VocabImporter
import com.opentasker.core.gengoshima.VocabJisho
import com.opentasker.core.gengoshima.VocabSearch
import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import com.opentasker.ui.components.AlertDialog
import com.opentasker.ui.components.Button
import com.opentasker.ui.components.OutlinedButton
import com.opentasker.ui.components.PaperTab
import com.opentasker.ui.components.PaperTabBar
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import com.opentasker.ui.components.IconRole
import com.opentasker.ui.components.RoleIcon
import com.opentasker.ui.components.destructiveButtonColors
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Edit

/**
 * 「言語島 語彙」 — 白い熊's own vocabulary, as a dictionary to read in.
 *
 * One paper tab per island and 全部 for all of them; a search field; and three choices that turn the
 * same terms into four lists: the direction (日→英, or 英→日 with one line per English sense — a
 * true reverse index), and the view (見出し, one line per heading, or 詳細, every entry in full, one
 * after another). A tapped heading opens the term alone, everything shown, and a swipe there goes to
 * the next term of the list it came from.
 *
 * An entry is 白い熊's own content first — translation, note, example sentences with their audio —
 * and what 白い熊の辞書 knows about the word under it ([VocabCard]). From an island's tab the island
 * plays at once in any of the three modes, without the player's picker.
 */
class GengoshimaVocabActivity : ComponentActivity() {

    /** The term another window asked for — a filed word tapped in the player. 0 = none. */
    private var openTerm by mutableStateOf(0L)

    /** Counts the askings, so the same term asked for twice opens twice. */
    private var openAsk by mutableStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTerm = intent.getLongExtra(EXTRA_TERM, 0L)
        openAsk++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openTerm = intent?.getLongExtra(EXTRA_TERM, 0L) ?: 0L
        enableEdgeToEdge()
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VocabScreen(openTerm = openTerm, openAsk = openAsk, onReturn = { finish() }, onClose = { closeToBoard() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_TERM = "gengoshima_term"

        /** [termId] opens that term alone, on 全部. */
        fun intent(context: Context, termId: Long? = null): Intent =
            Intent(context, GengoshimaVocabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply { if (termId != null) putExtra(EXTRA_TERM, termId) }
    }
}

/** The tab for every island together. Island ids start at 1, so 0 and below are free. */
private const val TAB_ALL = 0L

/** The starred terms of every island. */
private const val TAB_STAR = -1L

/** The terms opened last, newest first. */
private const val TAB_RECENT = -2L

/**
 * The order of the list. [ALPHA] is the dictionary's own and the one it opens in: 五十音 when the
 * Japanese leads, A–Z when the English does (白い熊, 2026-11-17: “entries must be sorted in both
 * listings”). It stands first so that a stored 0 — the old 島の順 default — now means it.
 */
private enum class VocabSort(val label: String) { ALPHA("五十音 / A–Z"), ISLAND("島の順"), NEWEST("新しい順") }

/** A foldable heading of the list with the lines under it. [key] is what its folded state is kept under. */
private data class VocabGroup(val key: String, val label: String, val rows: List<VocabRow>)

/** What the screen was last set to, kept between openings. */
private class VocabUi(context: Context) {
    private val prefs = context.getSharedPreferences("gengoshima_vocab_ui", Context.MODE_PRIVATE)
    var tab by mutableStateOf(prefs.getLong("tab", TAB_ALL))
    var englishFirst by mutableStateOf(prefs.getBoolean("englishFirst", false))
    var detailed by mutableStateOf(prefs.getBoolean("detailed", false))
    var sort by mutableStateOf(VocabSort.entries.getOrElse(prefs.getInt("sort", 0)) { VocabSort.ALPHA })
    var starredOnly by mutableStateOf(false)

    /** The headings folded shut, by their keys — kept, so the list reopens as it was left. */
    var folded by mutableStateOf(prefs.getStringSet("folded", emptySet()).orEmpty().toSet())
        private set

    fun fold(keys: Set<String>) {
        folded = keys
        prefs.edit().putStringSet("folded", keys).apply()
    }

    fun save() = prefs.edit().putLong("tab", tab).putBoolean("englishFirst", englishFirst)
        .putBoolean("detailed", detailed).putInt("sort", sort.ordinal).apply()
}

/** One line of the list: a term, and in 英→日 the one English sense the line stands for. */
internal data class VocabRow(val entry: VocabEntry, val sense: String?) {
    val key get() = "${entry.vocab.id}|${sense.orEmpty()}"
}

@Composable
private fun VocabScreen(openTerm: Long, openAsk: Int, onReturn: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { OpenTaskerApp_NoHilt.db.gengoshimaDao() }
    val scope = rememberCoroutineScope()
    val ui = remember { VocabUi(context.applicationContext) }
    val islands by dao.observeIslands().collectAsState(initial = emptyList())
    val vocab by dao.observeVocab().collectAsState(initial = emptyList())
    val sentences by dao.observeAllSentences().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Long?>(null) }
    val recent = remember { VocabRecent(context.applicationContext) }
    var editing by remember { mutableStateOf<VocabEntry?>(null) }
    var exampleFor by remember { mutableStateOf<VocabEntry?>(null) }
    var menu by remember { mutableStateOf(false) }
    var islandMenu by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val player = remember { CardPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    val islandById = remember(islands) { islands.associateBy { it.id } }
    val entries = remember(vocab, sentences, islandById) {
        val examples = sentences.filter { it.vocabId != null }.groupBy { it.vocabId }
        vocab.map { VocabEntry(it, islandById[it.islandId], examples[it.id].orEmpty()) }
    }
    // Where else each term is said: every sentence whose word split holds it in dictionary form.
    val saidIn = remember(vocab, sentences) {
        val wanted = vocab.mapTo(HashSet()) { it.term }
        val found = HashMap<String, MutableList<GengoshimaSentenceEntity>>()
        if (wanted.isNotEmpty()) for (s in sentences) {
            if (s.tokensJson.isBlank()) continue
            Translator.tokens(s.tokensJson).mapNotNullTo(HashSet()) { t -> t.base.ifBlank { t.surface }.takeIf { it in wanted } }
                .forEach { found.getOrPut(it) { ArrayList() } += s }
        }
        found
    }
    fun others(entry: VocabEntry) = saidIn[entry.vocab.term].orEmpty()
        .filter { s -> entry.examples.none { it.id == s.id } }
        .map { islandById[it.islandId] to it }

    // A tab whose island is gone falls back to 全部.
    val tab = ui.tab.takeIf { it <= TAB_ALL || islandById.containsKey(it) } ?: TAB_ALL
    val tabs = remember(islands, vocab, recent.ids) {
        val counts = vocab.groupingBy { it.islandId }.eachCount()
        val known = vocab.mapTo(HashSet()) { it.id }
        listOf(
            PaperTab(TAB_ALL, "全部", vocab.size),
            PaperTab(TAB_STAR, "★", vocab.count { it.starred }),
            PaperTab(TAB_RECENT, "最近", recent.ids.count { it in known }),
        ) +
            islands.map { PaperTab(it.id, it.nameJa.ifBlank { it.nameEn }, counts[it.id] ?: 0) }
    }
    fun select(id: Long) { ui.tab = id; ui.save(); open = null }
    // A term asked for from outside opens alone on 全部, where every term is.
    // Back from it returns to the window that asked — the player — not to the list.
    var asked by remember { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(openTerm, openAsk, vocab.isNotEmpty()) {
        if (openTerm > 0 && vocab.any { it.id == openTerm }) {
            ui.tab = TAB_ALL; query = ""; ui.starredOnly = false; open = openTerm; asked = openTerm
        }
    }

    val shown = remember(entries, tab, query, ui.sort, ui.englishFirst, ui.starredOnly, recent.ids) {
        val position = islands.associate { it.id to it.position }
        val recency = recent.ids.withIndex().associate { (k, id) -> id to k }
        entries
            .filter {
                when (tab) {
                    TAB_ALL -> true
                    TAB_STAR -> it.vocab.starred
                    TAB_RECENT -> it.vocab.id in recency
                    else -> it.vocab.islandId == tab
                }
            }
            .filter { !ui.starredOnly || it.vocab.starred }
            .filter { VocabSearch.matches(it.vocab, it.examples, query) }
            .let { list ->
                // 最近 is its own order — the last one opened first — whatever the sort says.
                if (tab == TAB_RECENT) list.sortedBy { recency[it.vocab.id] } else when (ui.sort) {
                    VocabSort.ISLAND -> list.sortedWith(compareBy({ position[it.vocab.islandId] ?: Int.MAX_VALUE }, { it.vocab.position }))
                    VocabSort.ALPHA ->
                        if (ui.englishFirst) list.sortedBy { VocabSearch.senseKey(it.vocab.meaning) }
                        else list.sortedWith(
                            compareBy(
                                { VocabSearch.groupOrder(VocabSearch.kanaGroup(VocabSearch.kanaKey(it.vocab))) },
                                { VocabSearch.kanaKey(it.vocab) },
                            ),
                        )
                    VocabSort.NEWEST -> list.sortedByDescending { it.vocab.createdAt }
                }
            }
    }
    val rows = remember(shown, ui.englishFirst, ui.detailed, ui.sort) {
        if (!ui.englishFirst || ui.detailed) shown.map { VocabRow(it, null) }
        else shown.flatMap { e -> VocabSearch.senses(e.vocab.meaning).map { VocabRow(e, it) } }
            .let { list -> if (ui.sort == VocabSort.ALPHA && tab != TAB_RECENT) list.sortedBy { VocabSearch.senseKey(it.sense.orEmpty()) } else list }
    }
    // The headings: 五十音 rows or letters in dictionary order, islands in island order on the tabs
    // that hold several. None on 最近 and 新しい順, which are orders of time.
    val groups = remember(rows, ui.sort, ui.englishFirst, tab, islandById) {
        when {
            tab == TAB_RECENT -> null
            ui.sort == VocabSort.ALPHA -> rows
                .groupBy {
                    if (ui.englishFirst) VocabSearch.letterGroup(it.sense ?: it.entry.vocab.meaning)
                    else VocabSearch.kanaGroup(VocabSearch.kanaKey(it.entry.vocab))
                }
                .entries.sortedBy { VocabSearch.groupOrder(it.key) }
                .map { (g, list) -> VocabGroup((if (ui.englishFirst) "en:" else "ja:") + g, g, list) }
            ui.sort == VocabSort.ISLAND && tab <= TAB_ALL -> rows
                .groupBy { it.entry.vocab.islandId }
                .map { (id, list) -> VocabGroup("is:$id", islandById[id]?.let { it.nameJa.ifBlank { it.nameEn } } ?: "—", list) }
            else -> null
        }
    }

    fun star(entry: VocabEntry) = scope.launch {
        dao.updateVocab(entry.vocab.copy(starred = !entry.vocab.starred, updatedAt = System.currentTimeMillis()))
    }
    fun share(text: String) {
        context.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    fun play(islandId: Long?, mode: ListenMode) {
        player.stop()
        context.startActivity(GengoshimaPlayerActivity.intent(context, mode, islandId))
    }
    fun export(islandId: Long?) = share(
        VocabImport.export(islands.filter { islandId == null || it.id == islandId }, vocab, sentences),
    )

    val listState = rememberLazyListState()
    val openIndex = open?.let { id -> shown.indexOfFirst { it.vocab.id == id } }?.takeIf { it >= 0 }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "言語島 — 語彙",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (openIndex != null) HeaderPill("一覧", onClick = { open = null }) else HeaderPill("メニュー", onClick = { menu = true })
            HeaderPill("閉じる", onClose)
        }

        PaperTabBar(tabs = tabs, selectedId = tab, onSelect = ::select, onLongPress = { if (it > TAB_ALL) islandMenu = it })

        if (openIndex != null) {
            BackHandler { if (open == asked) { asked = null; open = null; onReturn() } else open = null }
            SingleTerm(
                entries = shown,
                start = openIndex,
                others = ::others,
                englishFirst = ui.englishFirst,
                showIsland = tab <= TAB_ALL,
                player = player,
                // Not on 最近 itself: the list would reorder under the pager at every swipe.
                onPage = { open = it; if (tab != TAB_RECENT) recent.viewed(it) },
                onEdit = { editing = it },
                onAddExample = { exampleFor = it },
                onStar = ::star,
                onShare = { share(vocabShareText(it)) },
                onCopy = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText(it.vocab.term, vocabShareText(it)))
                },
                onJisho = { lookUp(context, it.vocab.term) },
                onDelete = { entry ->
                    scope.launch {
                        dao.unlinkExamples(entry.vocab.id)
                        dao.deleteVocabRow(entry.vocab.id)
                        dao.vocabOf(entry.vocab.islandId).forEachIndexed { k, v -> if (v.position != k + 1) dao.updateVocab(v.copy(position = k + 1)) }
                        open = null
                    }
                },
            )
            return@Column
        }

        // The island plays from here at once: no picker in between.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val islandId = tab.takeIf { it > TAB_ALL }
            item { Button(onClick = { play(islandId, ListenMode.LISTEN) }) { Text("▶ 聴く") } }
            item { OutlinedButton(onClick = { play(islandId, ListenMode.SHADOW) }) { Text("シャドーイング") } }
            item { OutlinedButton(onClick = { play(islandId, ListenMode.RECALL) }) { Text("思い出す") } }
            if (islandId != null) item {
                OutlinedButton(onClick = { islandById[islandId]?.let { message = studyInJisho(context, it) } }) { Text("辞書で学ぶ") }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "消す / clear") }
            },
            placeholder = { Text("検索 — 語・かな・romaji・English") },
        )

        // Each pill shows what the list is set to; a tap moves to the next choice.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedButton(onClick = { ui.englishFirst = !ui.englishFirst; ui.save() }) { Text(if (ui.englishFirst) "英→日" else "日→英") } }
            item { OutlinedButton(onClick = { ui.detailed = !ui.detailed; ui.save() }) { Text(if (ui.detailed) "詳細" else "見出し") } }
            item {
                OutlinedButton(onClick = { ui.sort = VocabSort.entries[(ui.sort.ordinal + 1) % VocabSort.entries.size]; ui.save() }) {
                    Text("順: ${ui.sort.label}")
                }
            }
            item { TogglePill("★ だけ", ui.starredOnly) { ui.starredOnly = !ui.starredOnly } }
        }

        if (rows.isEmpty()) {
            Text(
                when {
                    vocab.isEmpty() -> "語彙はまだありません —「メニュー」の「取り込む」から。\nNo vocabulary yet: Menu → Import."
                    query.isNotBlank() -> "「$query」は見つかりません / nothing found"
                    tab == TAB_STAR -> "★ を付けた語はありません / no starred terms"
                    tab == TAB_RECENT -> "まだ開いた語がありません / no term opened yet"
                    else -> "この島に語彙はありません / no terms in this island"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        // A sideways fling over the list goes to the neighbouring tab, round the ends.
        val jishoOpen = remember { mutableStateMapOf<Long, Boolean>() }
        Box(
            Modifier.fillMaxWidth().weight(1f).pointerInput(tabs, tab) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = {
                        if (abs(travelled) > 64.dp.toPx() && tabs.size > 1) {
                            val at = tabs.indexOfFirst { it.id == tab }.coerceAtLeast(0)
                            select(tabs[(at + (if (travelled < 0) 1 else -1) + tabs.size) % tabs.size].id)
                        }
                    },
                ) { _, dx -> travelled += dx }
            },
        ) {
            val line: @Composable (VocabRow) -> Unit = { row ->
                val entry = row.entry
                if (ui.detailed) {
                    VocabCard(
                        entry = entry,
                        others = others(entry),
                        englishFirst = ui.englishFirst,
                        showIsland = tab <= TAB_ALL,
                        jishoOpen = jishoOpen[entry.vocab.id] == true,
                        onJishoToggle = { jishoOpen[entry.vocab.id] = jishoOpen[entry.vocab.id] != true },
                        player = player,
                        onStar = { star(entry) },
                        onShare = { share(vocabShareText(entry)) },
                        onOpen = { open = entry.vocab.id },
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    HeadingLine(row, ui.englishFirst, tab <= TAB_ALL) { open = entry.vocab.id }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                val sections = groups
                if (sections == null) {
                    items(rows, key = { it.key }) { line(it) }
                } else sections.forEach { g ->
                    val shut = g.key in ui.folded
                    stickyHeader(key = "group:${g.key}") {
                        GroupHeader(g.label, g.rows.size, shut) { ui.fold(if (shut) ui.folded - g.key else ui.folded + g.key) }
                    }
                    if (!shut) items(g.rows, key = { it.key }) { line(it) }
                }
            }
        }
    }

    if (menu) {
        val last = remember(vocab, sentences) { VocabImporter.last(context) }
        MenuDialog(
            title = "語彙",
            onDismiss = { menu = false },
            actions = listOfNotNull(
                MenuAction("取り込む / Import", Icons.Filled.Download, IconRole.SHARE, opens = true) {
                    context.startActivity(GengoshimaImportActivity.intent(context))
                },
                MenuAction(
                    if (tab <= TAB_ALL) "全部を書き出す / Export everything" else "この島を書き出す / Export this island",
                    Icons.Filled.Upload, IconRole.SHARE,
                ) { export(tab.takeIf { it > TAB_ALL }); Unit },
                groups?.takeIf { it.size > 1 }?.let { gs ->
                    val keys = gs.mapTo(HashSet()) { it.key }
                    if (keys.all { it in ui.folded }) {
                        MenuAction("すべて開く / Unfold every heading", Icons.Filled.UnfoldMore, IconRole.VIEW) { ui.fold(ui.folded - keys) }
                    } else {
                        MenuAction("すべて畳む / Fold every heading", Icons.Filled.UnfoldLess, IconRole.VIEW) { ui.fold(ui.folded + keys) }
                    }
                },
                MenuAction("辞書を更新 / Ask 辞書 again", Icons.Filled.Refresh, IconRole.CONNECT) {
                    message = "辞書に聞いています… / asking 辞書…"
                    scope.launch {
                        message = runCatching { VocabJisho.fetch(context.applicationContext, dao, all = true).line }
                            .getOrElse { "辞書: ${it.message}" }
                    }
                    Unit
                },
                last?.let {
                    // It deletes what the import added, so it is marked as every deleting action is.
                    MenuAction("直前の取り込みを取り消す（${it.summary}）/ Undo the last import", Icons.AutoMirrored.Filled.Undo, IconRole.DELETE, destructive = true) {
                        scope.launch {
                            val gone = VocabImporter.undoLast(context.applicationContext)
                            message = gone?.let { g -> "取り消しました: ${g.summary.replace('+', '−')}" } ?: "取り消すものがありません"
                            // The deleted sentences' notes leave 暗記 with the editor's usual run.
                            com.opentasker.core.gengoshima.GenerationRunner.tidy(
                                context.applicationContext, com.opentasker.core.gengoshima.GengoshimaSettings.last(context),
                            )
                        }
                        Unit
                    }
                },
            ),
        )
    }

    islandMenu?.let { id ->
        val island = islandById[id]
        if (island == null) islandMenu = null
        else MenuDialog(
            title = island.nameJa.ifBlank { island.nameEn },
            onDismiss = { islandMenu = null },
            actions = listOf(
                MenuAction("聴く / Listen", Icons.Filled.Headphones, IconRole.CONNECT, opens = true) { play(id, ListenMode.LISTEN) },
                MenuAction("シャドーイング / Shadow", Icons.Filled.RecordVoiceOver, IconRole.CONNECT, opens = true) { play(id, ListenMode.SHADOW) },
                MenuAction("思い出す / Recall", Icons.Filled.Psychology, IconRole.CONNECT, opens = true) { play(id, ListenMode.RECALL) },
                MenuAction("辞書で学ぶ / Study in 辞書", Icons.Filled.School, IconRole.VIEW, opens = true) { message = studyInJisho(context, island) },
                MenuAction("島を編集 / Edit the island", Icons.Filled.Edit, IconRole.WRITE, opens = true) {
                    context.startActivity(GengoshimaIslandsActivity.intent(context))
                },
                MenuAction("書き出す / Export", Icons.Filled.Upload, IconRole.SHARE) { export(id); Unit },
            ),
        )
    }

    editing?.let { entry ->
        VocabEditDialog(
            title = "「${entry.vocab.term}」を編集",
            initial = VocabDraft(entry.vocab.term, entry.vocab.reading, entry.vocab.meaning, entry.vocab.note, entry.vocab.islandId),
            islands = islands,
            onDismiss = { editing = null },
            onSave = { draft ->
                editing = null
                scope.launch {
                    val clash = VocabEdits.existing(dao, draft.term)?.takeIf { it.id != entry.vocab.id }
                    if (clash != null) {
                        message = "「${draft.term}」は既にあります — 変えていません。\nThat term is filed already; nothing was changed."
                    } else {
                        VocabEdits.save(dao, entry.vocab, draft)
                        askJisho(context.applicationContext)
                    }
                }
            },
        )
    }

    exampleFor?.let { entry ->
        ExampleAddDialog(entry.vocab.term, onDismiss = { exampleFor = null }) { ja, en ->
            exampleFor = null
            scope.launch {
                val (what, id) = VocabEdits.addExample(dao, entry.vocab, ja, en)
                when (what) {
                    VocabEdits.Example.ADDED -> {
                        // Voiced and split by the usual run, for this one sentence only.
                        com.opentasker.core.gengoshima.GenerationRunner.start(
                            context.applicationContext, com.opentasker.core.gengoshima.GengoshimaSettings.last(context), only = setOf(id),
                        )
                        context.startActivity(GengoshimaProgressActivity.intent(context))
                    }
                    VocabEdits.Example.LINKED -> message = "その文は既に島にあります — この語の例文にしました。\nThat sentence was in an island already; it is now this term's example."
                    VocabEdits.Example.TAKEN -> message = "その文は既に別の語の例文です — 変えていません。\nThat sentence is another term's example already; nothing was changed."
                }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { Button(onClick = { message = null }) { Text("閉じる") } },
        )
    }
}

/**
 * A heading of the list — a 五十音 row, a letter, an island — that folds the lines under it away.
 * It stays at the top while its lines scroll beneath it. The letter is large, the count sits in a
 * small box beside it as the tabs' badges do, and a rule runs the whole width, so a column of folded
 * headings reads as an index and not as a stack of buttons.
 */
@Composable
private fun GroupHeader(label: String, count: Int, shut: Boolean, onToggle: () -> Unit) {
    val ink = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .clickable(onClickLabel = if (shut) "開く / unfold" else "畳む / fold", onClick = onToggle),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(
                "$count", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink,
                modifier = Modifier.border(1.5.dp, ink, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp),
            )
            Box(Modifier.weight(1f))
            Text(if (shut) "▸" else "▾", fontSize = 22.sp, color = ink)
        }
        HorizontalDivider(thickness = 2.dp, color = ink)
    }
}

/** 見出し: one line per heading. 日→英 leads with the word; 英→日 with one English sense. */
@Composable
internal fun HeadingLine(row: VocabRow, englishFirst: Boolean, showIsland: Boolean, onClick: () -> Unit) {
    val v = row.entry.vocab
    val soft = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (englishFirst) {
            Text(row.sense ?: v.meaning, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(v.term, fontSize = 19.sp, maxLines = 1)
            if (v.reading.isNotBlank()) Text(v.reading, fontSize = 14.sp, color = soft, maxLines = 1)
        } else {
            Text(v.term, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (v.reading.isNotBlank()) Text(v.reading, fontSize = 14.sp, color = soft, maxLines = 1)
            Text(v.meaning, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (v.starred) Text("★", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        if (showIsland) row.entry.island?.let {
            Text(it.nameJa.ifBlank { it.nameEn }, fontSize = 12.sp, color = soft, maxLines = 1)
        }
    }
}

/** One term alone, everything open; a swipe goes to the next or previous term of the list. */
@Composable
private fun SingleTerm(
    entries: List<VocabEntry>,
    start: Int,
    others: (VocabEntry) -> List<Pair<GengoshimaIslandEntity?, GengoshimaSentenceEntity>>,
    englishFirst: Boolean,
    showIsland: Boolean,
    player: CardPlayer,
    onPage: (Long) -> Unit,
    onEdit: (VocabEntry) -> Unit,
    onAddExample: (VocabEntry) -> Unit,
    onStar: (VocabEntry) -> Unit,
    onShare: (VocabEntry) -> Unit,
    onCopy: (VocabEntry) -> Unit,
    onJisho: (VocabEntry) -> Unit,
    onDelete: (VocabEntry) -> Unit,
) {
    val pager = rememberPagerState(initialPage = start) { entries.size }
    var deleting by remember { mutableStateOf<VocabEntry?>(null) }
    // The term in view is the one the list returns to, and the one a rotation reopens.
    androidx.compose.runtime.LaunchedEffect(pager.settledPage) {
        entries.getOrNull(pager.settledPage)?.let { onPage(it.vocab.id) }
        player.stop()
    }
    Text(
        "${pager.currentPage + 1} / ${entries.size}",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.Top, key = { entries[it].vocab.id }) { page ->
        val entry = entries[page]
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VocabCard(
                entry = entry,
                others = others(entry),
                englishFirst = englishFirst,
                showIsland = showIsland,
                jishoOpen = true,
                onJishoToggle = null,
                player = player,
                onStar = { onStar(entry) },
                onShare = { onShare(entry) },
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedButton(onClick = { onEdit(entry) }) { Text("編集") } }
                item { OutlinedButton(onClick = { onAddExample(entry) }) { Text("例文を追加") } }
                item { OutlinedButton(onClick = { onJisho(entry) }) { Text("辞書で開く") } }
                item { OutlinedButton(onClick = { onCopy(entry) }) { Text("コピー") } }
                item { OutlinedButton(onClick = { onShare(entry) }) { Text("共有") } }
                item { OutlinedButton(onClick = { deleting = entry }, colors = destructiveButtonColors()) { Text("削除") } }
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("「${entry.vocab.term}」を削除しますか？") },
            text = { Text("語だけを消します。例文は島に残ります。\nOnly the term is deleted; its example sentences stay in the island.") },
            confirmButton = { Button(onClick = { deleting = null; onDelete(entry) }, colors = destructiveButtonColors()) { Text("削除") } },
            dismissButton = { OutlinedButton(onClick = { deleting = null }) { Text("やめる") } },
        )
    }
}

/**
 * One line of a [MenuDialog]: what it says, the icon that shows what it does ([role] gives the
 * icon its colour), whether it [opens] a screen (a › at the right) and whether it deletes.
 */
private class MenuAction(
    val label: String,
    val icon: ImageVector,
    val role: IconRole,
    val opens: Boolean = false,
    val destructive: Boolean = false,
    val run: () -> Unit,
)

/**
 * A menu in the house style: every action a pill across the whole width, its icon inside at the
 * left and coloured by what the action does — a list is read by colour before it is read by word.
 */
@Composable
private fun MenuDialog(title: String, onDismiss: () -> Unit, actions: List<MenuAction>) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action ->
                    OutlinedButton(
                        onClick = { onDismiss(); action.run() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (action.destructive) destructiveButtonColors() else null,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        RoleIcon(action.icon, if (action.destructive) IconRole.DELETE else action.role, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(action.label, modifier = Modifier.weight(1f))
                        if (action.opens) Text("›", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
