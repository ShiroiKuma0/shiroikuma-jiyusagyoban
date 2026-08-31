package com.opentasker.ui.article

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.opentasker.core.ocr.OcrTrust
import com.opentasker.core.ocr.article.ArticleDocument
import com.opentasker.core.ocr.article.ArticleFigure
import com.opentasker.core.ocr.article.ArticleKind
import com.opentasker.core.ocr.article.ArticleNode
import com.opentasker.core.ocr.article.ArticleText
import com.opentasker.core.ocr.article.ArticleWriter
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.ocr.flash
import com.opentasker.ui.theme.ThemeStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

/**
 * 「記事編集」 — the article, against the screenshots it was read from.
 *
 * The shape is 文字認識's, scaled up to a whole article: the pixels above, the text below, and a tap
 * on the image putting the caret in the words it produced. What is new is that the top half is a
 * strip of whole pages rather than one cut-out, so an article can be checked end to end in the order
 * it was read.
 *
 * This is also where 記事変換 now lands. Nothing is written to disk until 保存 is pressed — the file
 * used to appear the moment recognition finished, which meant every correction was an edit to a file
 * already sitting in `/sdcard/tmp` (白い熊, 2026-08-09).
 */
@Composable
fun ArticleEditScreen(
    initial: ArticleDocument?,
    outputDirectory: String?,
    onOpenHtml: () -> Unit,
    onAddPages: (List<File>) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val prefs by ThemeStore.state.collectAsState()
    val scope = rememberCoroutineScope()

    val nodes = remember(initial) { mutableStateListOf<ArticleNode>().apply { initial?.let { addAll(it.nodes) } } }
    // Edited text, by node index. Absent means untouched — which is also what keeps the confidence
    // shading honest, since the run offsets it needs only describe the text as recognised.
    val edits = remember(initial) { mutableStateMapOf<Int, String>() }
    val pages = remember(initial) {
        mutableStateListOf<File>().apply {
            initial?.sources?.map(::File)?.filter { it.isFile }?.let { addAll(it) }
        }
    }

    var selected by remember { mutableStateOf<StripTarget?>(null) }
    var caret by remember { mutableStateOf<Pair<Int, Int>?>(null) }   // node index, offset
    var status by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // Prefilled once, from the headline, then 白い熊's to change. Not regenerated on every save: a
    // name that has been typed must not be quietly replaced by a fresh stamp.
    var name by remember(initial) {
        mutableStateOf(initial?.let { ArticleWriter.suggestedName(it) }.orEmpty())
    }
    var directory by remember(outputDirectory) {
        mutableStateOf(outputDirectory ?: ArticleWriter.DEFAULT_DIRECTORY)
    }

    val stripState = rememberLazyListState()
    val textState = rememberLazyListState()

    val targets = remember(nodes.toList()) { targetsOf(nodes) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (!uris.isNullOrEmpty()) onAddPages(uris.mapNotNull { copyIn(context, it) }) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        tree ?: return@rememberLauncherForActivityResult
        val path = treePath(tree)
        if (path == null) status = "その場所には書けません: $tree" else { directory = path; status = null }
    }

    /*
     * The keyboard moves the WHOLE column up rather than squeezing it.
     *
     * `imePadding()` — what 文字認識 uses — shrinks the pane above it, and here that would rescale the
     * page strip and throw away the place 白い熊 had scrolled to (白い熊, 2026-08-09). Translating
     * instead keeps the strip at its size and its scroll offset; it simply slides off the top, which
     * is the right thing to lose while typing.
     */
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    /*
     * …which means the top of the TEXT pane goes off the screen with it, taking whatever is being
     * edited along. Measured on the device: tapping a line near the start of the article focused it
     * correctly and then translated it out of sight (白い熊, 2026-08-09).
     *
     * So the pane is told how much of itself is hidden and starts its content below that. The pane's
     * own top is recorded UNSHIFTED — the offset is added back — or the two would chase each other.
     */
    var paneTop by remember { mutableStateOf(0) }
    val hidden = (imeBottom - paneTop).coerceAtLeast(0)
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }

    // The keyboard arrives after the tap that summoned it, so the block is placed twice: once on the
    // tap, and again once the inset is known and the pane knows how much of itself is covered.
    LaunchedEffect(caret, hidden, margin) {
        // A NEGATIVE scroll offset is what leaves room above the item. contentPadding cannot do it:
        // that only pads the two ends of the list, so scrolling to any item in the middle still puts
        // it flush against the viewport top — which here is the part hidden behind the keyboard.
        caret?.let { textState.animateScrollToItem(it.first, -(hidden + margin)) }
    }


    Column(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, -imeBottom) }
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("記事編集", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (nodes.isEmpty()) "HTML と元の画像を開いてください"
                    else "${nodes.count { it is ArticleText }} 段落 · ${pages.size} ページ" +
                        if (edits.isEmpty()) "" else " · ${edits.size} 箇所を直しました",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Behind a menu on purpose: opening a file is a once-per-session act and the screen is
            // already two panes and a save button.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "メニュー")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("HTML を開く") },
                        onClick = { menuOpen = false; onOpenHtml() },
                    )
                    DropdownMenuItem(
                        text = { Text("元の画像を開く") },
                        leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                        onClick = { menuOpen = false; imagePicker.launch(arrayOf("image/*")) },
                    )
                    DropdownMenuItem(text = { Text("閉じる") }, onClick = { menuOpen = false; onClose() })
                }
            }
        }

        if (pages.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "元の画像がありません。メニューから開いてください。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            PageStrip(
                pages = pages.toList(),
                targets = targets,
                selected = selected,
                state = stripState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onPick = { target ->
                    selected = target
                    caret = target.nodeIndex to target.caretOffset
                    scope.launch { textState.animateScrollToItem(target.nodeIndex) }
                },
            )
        }

        LazyColumn(
            state = textState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { paneTop = it.positionInWindow().y.toInt() + imeBottom }
                .padding(horizontal = 10.dp),
            // Room to scroll the last block clear of the keyboard as well.
            contentPadding = PaddingValues(bottom = with(LocalDensity.current) { hidden.toDp() }),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(nodes) { index, node ->
                when (node) {
                    is ArticleText -> BlockRow(
                        node = node,
                        text = edits[index] ?: node.plain,
                        edited = index in edits,
                        focusAt = caret?.takeIf { it.first == index }?.second,
                        onText = { edits[index] = it },
                        onFocused = { selected = targets.firstOrNull { it.nodeIndex == index } },
                        onDelete = { nodes.removeAt(index); edits.remove(index) },
                    )
                    is ArticleFigure -> FigureRow(node) { nodes.removeAt(index) }
                }
            }
        }

        Text(
            status ?: directory,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text("ファイル名", style = MaterialTheme.typography.labelSmall) },
            )
            IconButton(onClick = { folderPicker.launch(null) }) {
                Icon(Icons.Filled.Folder, contentDescription = "保存先を選ぶ")
            }
            Button(
                onClick = {
                    val document = ArticleDocument(
                        title = initial?.title ?: "記事",
                        sources = pages.map { it.absolutePath },
                        nodes = applyEdits(nodes, edits),
                    )
                    val target = File(directory, ArticleWriter.sanitiseName(name))
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { ArticleWriter.writeTo(document, target) }
                        }.onSuccess {
                            status = it.absolutePath
                            flash(context, prefs, "保存しました")
                        }.onFailure {
                            status = "保存できませんでした: ${it.message}"
                        }
                    }
                },
                enabled = nodes.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Text("  保存")
            }
        }
    }
}

/**
 * A picked folder as a path this app can write with `File`.
 *
 * The system picker answers with a tree URI, but everything else here speaks paths — the task's `out`
 * argument, the status line, what gets reported back to a task. With MANAGE_EXTERNAL_STORAGE granted
 * the app can write those paths directly, so the URI is converted once, here, and refused honestly if
 * it names a volume that has no path (a cloud provider, say) rather than half-working.
 */
private fun treePath(tree: Uri): String? {
    val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
    val parts = id.split(':', limit = 2)
    if (parts.size != 2) return null
    val base = if (parts[0] == "primary") "/sdcard" else "/storage/${parts[0]}"
    val path = "$base/${parts[1]}".trimEnd('/')
    return path.takeIf { File(it).isDirectory }
}

/** One editable block, shaded where the recogniser was unsure of it. */
@Composable
private fun BlockRow(
    node: ArticleText,
    text: String,
    edited: Boolean,
    focusAt: Int?,
    onText: (String) -> Unit,
    onFocused: () -> Unit,
    onDelete: () -> Unit,
) {
    var field by remember(node) { mutableStateOf(TextFieldValue(text)) }
    val focus = remember { FocusRequester() }

    // A tap on the image lands here: take the focus and put the caret on the word that was tapped.
    LaunchedEffect(focusAt) {
        if (focusAt != null) {
            field = field.copy(selection = TextRange(focusAt.coerceIn(0, field.text.length)))
            runCatching { focus.requestFocus() }
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = field,
            onValueChange = { field = it; onText(it.text) },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
            textStyle = MaterialTheme.typography.bodyMedium,
            label = { Text(node.kind.name.lowercase(), style = MaterialTheme.typography.labelSmall) },
            // Only while the block is as recognised: the offsets the shading uses describe THAT text,
            // and after a correction they would mark the wrong words rather than none.
            visualTransformation = remember(node, edited) {
                if (edited) VisualTransformation.None else RunConfidenceShading(node)
            },
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "この段落を消す")
        }
    }
}

@Composable
private fun FigureRow(node: ArticleFigure, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "図 ${node.width}×${node.height}" +
                (node.caption?.plain?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "この図を消す")
        }
    }
}

/**
 * Marks the words the recogniser was least sure of, by exact character range.
 *
 * 文字認識 has to key its shading by line index, because there the offsets go stale the moment a
 * character is corrected. Here the block knows precisely where each run sits in its own text, so the
 * ranges are exact — and the whole transformation is dropped as soon as the block is touched, rather
 * than left to drift.
 */
private class RunConfidenceShading(private val node: ArticleText) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val shaded = AnnotatedString.Builder(text.text)
        var at = 0
        node.runs.forEachIndexed { index, run ->
            if (index > 0 && at < text.text.length && text.text[at] == ' ') at++
            val end = (at + run.text.length).coerceAtMost(text.text.length)
            if (at < end && OcrTrust.of(run.confidence) == OcrTrust.CHECK) {
                shaded.addStyle(CHECK, at, end)
            }
            at = end
        }
        return TransformedText(shaded.toAnnotatedString(), OffsetMapping.Identity)
    }

    private companion object {
        val CHECK = SpanStyle(
            color = ChartPalette.BAND_WARN,
            textDecoration = TextDecoration.Underline,
        )
    }
}
