package com.opentasker.ui.article

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.core.content.FileProvider
import com.opentasker.core.ocr.OcrModels
import com.opentasker.core.ocr.OcrScript
import com.opentasker.core.ocr.OcrShareIntake
import com.opentasker.core.ocr.OcrTuning
import com.opentasker.core.ocr.article.ArticleHtml
import com.opentasker.core.ocr.article.ArticleProgress
import com.opentasker.core.ocr.article.ArticleReader
import com.opentasker.core.ocr.article.ArticleWriter
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.components.SelectionChip
import com.opentasker.ui.ocr.flash
import com.opentasker.ui.theme.ThemeStore
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One screenshot queued to be read, with enough of itself shown to tell it from the next one. */
private data class Page(val file: File, val width: Int, val height: Int, val thumbnail: Bitmap?)

@Composable
fun ArticleBuildScreen(
    initialPages: List<File>,
    outputDirectory: String?,
    onOwnCopy: (File) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by ThemeStore.state.collectAsState()

    val pages = remember { mutableStateListOf<Page>() }
    remember(initialPages) {
        if (pages.isEmpty()) initialPages.forEach { file -> describe(file)?.let(pages::add) }
        true
    }

    var accurate by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ArticleProgress?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var startedAt by remember { mutableStateOf(0L) }
    var reportedAt by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(0L) }
    val running = job != null

    // The elapsed time and the estimate have to move on their own. Progress arrives when a step
    // finishes, which during detection is seconds apart, and a frozen clock reads as a frozen app.
    LaunchedEffect(running) {
        while (running) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.orEmpty().forEach { uri ->
            // A picker grant dies with this window, so the bytes have to be taken now.
            val copied = runCatching { OcrShareIntake.copyToCache(context, uri) }.getOrNull() ?: return@forEach
            onOwnCopy(copied)
            describe(copied)?.let(pages::add)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            "記事変換",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )
        Text(
            if (pages.isEmpty()) "読む順にスクリーンショットを並べてください"
            else "${pages.size} ページ · 上から順に読み、重なった部分は自動で片方を落とします",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(pages, key = { it.file.absolutePath }) { page ->
                val index = pages.indexOf(page)
                PageRow(
                    page = page,
                    index = index,
                    count = pages.size,
                    enabled = !running,
                    onUp = { if (index > 0) pages.add(index - 1, pages.removeAt(index)) },
                    onDown = { if (index < pages.size - 1) pages.add(index + 1, pages.removeAt(index)) },
                    onRemove = { pages.removeAt(index) },
                )
            }
        }

        OutlinedButton(
            onClick = { picker.launch(arrayOf("image/*")) },
            enabled = !running,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("  ページを追加")
        }

        // Mobile by default: an article is dozens of recognition passes, and measured over 26 000
        // characters of the sample the two models disagree by 0.53 % — nearly all of it quote marks.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("認識モデル", style = MaterialTheme.typography.bodyMedium)
            // Short labels and a scrolling row: folded, this panel is 1008 px wide and "正確 (81 MB)"
            // wrapped onto two lines inside its own chip.
            SelectionChip("速い 16MB", selected = !accurate, enabled = !running) { accurate = false }
            SelectionChip("正確 81MB", selected = accurate, enabled = !running) { accurate = true }
        }

        progress?.let { report ->
            // Carried forward since the report landed, so every bar keeps moving through a step
            // rather than only between steps. See ArticleProgress.advanced.
            val within = if (report.stepExpectedMs <= 0L) 0f
            else (now - reportedAt).toFloat() / report.stepExpectedMs
            ProgressPanel(
                progress = report.advanced(within),
                elapsedMs = (now - startedAt).coerceAtLeast(0L),
            )
        }

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (running) {
            Button(
                onClick = {
                    status = "中止しています…"
                    job?.cancel()
                },
                colors = ButtonDefaults.buttonColors(containerColor = ChartPalette.BAND_WARN),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
                Text("  中止")
            }
        } else {
            Button(
                onClick = {
                    status = null
                    progress = ArticleProgress.start(pages.size, 0)
                    startedAt = SystemClock.elapsedRealtime()
                    reportedAt = startedAt
                    now = startedAt
                    val files = pages.map { it.file }
                    val directory = File(outputDirectory ?: ArticleWriter.DEFAULT_DIRECTORY)
                    job = scope.launch {
                        try {
                            val document = ArticleReader.read(
                                context = context,
                                files = files,
                                options = ArticleReader.Options(
                                    script = OcrScript.DEFAULT,
                                    highAccuracy = accurate,
                                    tuning = OcrTuning.from(
                                        prefs.ocrDetectionLongSide, prefs.ocrBinarisePercent,
                                        prefs.ocrBoxScorePercent, prefs.ocrUnclipTenths,
                                    ),
                                ),
                            ) {
                                progress = it
                                reportedAt = SystemClock.elapsedRealtime()
                            }
                            // NOT saved here. The article goes to 記事編集 to be checked against the
                            // screenshots, and only 保存 there puts a file on disk (白い熊, 2026-08-09)
                            // — a file written at this point is one every later correction has to
                            // chase.
                            val handover = withContext(Dispatchers.IO) {
                                File(context.cacheDir, "article").apply { mkdirs() }
                                    .resolve("handover-${System.currentTimeMillis()}.html")
                                    .apply { writeText(ArticleHtml.render(document, stamp())) }
                            }
                            status = "${document.blocks} 段落 · 図 ${document.figures} 枚 · " +
                                "${document.characters} 文字 — 記事編集で確認してください"
                            ArticleEditActivity.open(context, handover, directory.absolutePath)
                        } catch (cancelled: CancellationException) {
                            status = "中止しました"
                            throw cancelled
                        } catch (missing: OcrModels.MissingModel) {
                            status = "${missing.slot.label}のモデルが未設定です (${missing.slot.fileName})"
                        } catch (failure: Throwable) {
                            status = "失敗しました: ${failure.message ?: failure.javaClass.simpleName}"
                        } finally {
                            job = null
                            progress = null
                        }
                    }
                },
                enabled = pages.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text("  変換")
            }
        }
    }
}

/**
 * The three bars, and the clock.
 *
 * Top to bottom they go from fastest to slowest: the slice in hand, the page in hand, the whole job.
 * The top one exists because the middle one moves once every seven seconds and that reads as stuck
 * (白い熊, 2026-08-09) — it is watching the steps inside a slice, so it advances several times in
 * between. The bottom one counts the part-finished slice too, which is what stops the remaining-time
 * estimate lurching each time a slice lands.
 */
@Composable
private fun ProgressPanel(progress: ArticleProgress, elapsedMs: Long) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        val scanning = progress.phase == ArticleProgress.Phase.SCANNING

        Bar(
            label = when (progress.phase) {
                ArticleProgress.Phase.SCANNING ->
                    "スライス ${progress.sliceInPage + 1}/${progress.slicesInPage} · ${progress.step.label}"
                ArticleProgress.Phase.ASSEMBLING -> "組み立てています…"
                ArticleProgress.Phase.FIGURES -> "図を切り出しています…"
                ArticleProgress.Phase.DONE -> "書き出しています…"
            },
            fraction = if (scanning) progress.sliceProgress else 1f,
            color = MaterialTheme.colorScheme.primary,
        )
        Bar(
            label = "ページ ${progress.page + 1}/${progress.pages} · ${progress.lines} 行",
            fraction = progress.pageFraction,
            color = MaterialTheme.colorScheme.primary,
            muted = true,
        )
        Bar(
            label = "全体 ${progress.slicesDone}/${progress.slicesTotal} · ${progress.percent} %",
            fraction = progress.totalFraction,
            color = MaterialTheme.colorScheme.secondary,
        )

        val remaining = progress.remainingMs(elapsedMs)
        Text(
            "経過 ${clock(elapsedMs)}" +
                if (remaining == null) " · 残り 計測中…" else " · 残り 約 ${clock(remaining)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Bar(label: String, fraction: Float, color: Color, muted: Boolean = false) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 6.dp),
    )
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(6.dp),
        color = color,
    )
}

/** m:ss, or h:mm:ss once it has been going that long. */
private fun clock(ms: Long): String {
    val total = ms / 1000
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun PageRow(
    page: Page,
    index: Int,
    count: Int,
    enabled: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            val thumbnail = page.thumbnail
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("${index + 1}", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // Two lines, ending in an ellipsis: screenshot names differ in the MIDDLE (the
            // timestamp), so squeezing one onto a line turned every page into "Screen…roid.jpg".
            Text(
                "${index + 1}. ${page.file.name}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${page.width} × ${page.height}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onUp, enabled = enabled && index > 0) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = "上へ")
        }
        IconButton(onClick = onDown, enabled = enabled && index < count - 1) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = "下へ")
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Filled.Close, contentDescription = "外す")
        }
    }
}

/**
 * Size and a thumbnail, without decoding a 342 MB page.
 *
 * The subsample is chosen from the width, then the TOP of the result is cropped square — a scrolling
 * screenshot is twenty times taller than it is wide, so a whole-page thumbnail would be an unreadable
 * thread. The top of the page is also the part that says which page it is.
 */
private fun describe(file: File): Page? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > THUMBNAIL) sample *= 2
    val small = runCatching {
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    val square = small?.let {
        val side = minOf(it.width, it.height)
        runCatching { Bitmap.createBitmap(it, 0, 0, side, max(1, side)) }.getOrNull()
    }
    if (square != null && square !== small) small.recycle()
    return Page(file, bounds.outWidth, bounds.outHeight, square)
}

private const val THUMBNAIL = 128

/** Hands the finished file to whatever on the phone reads HTML. */
private fun open(context: android.content.Context, file: File) {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

/** The build time stamped into the handover file — replaced by the real one when 保存 writes it. */
private fun stamp(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
