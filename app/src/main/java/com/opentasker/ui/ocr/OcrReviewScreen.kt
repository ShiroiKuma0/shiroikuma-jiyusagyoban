package com.opentasker.ui.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.opentasker.core.actions.FlashOverlay
import com.opentasker.core.ocr.OcrBlock
import com.opentasker.core.ocr.OcrEngine
import com.opentasker.core.ocr.OcrImage
import com.opentasker.core.ocr.OcrScript
import com.opentasker.core.ocr.OcrTrust
import com.opentasker.core.ocr.doubtfulLineCount
import com.opentasker.core.ocr.lineConfidences
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.theme.ThemePrefs
import com.opentasker.ui.theme.ThemeStore
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The 文字認識 review surface: the screenshot above, the text it produced below, and a copy button
 * that puts the (possibly corrected) text on the clipboard and closes.
 *
 * Split pane rather than an overlay, because the two things you do here — comparing a character
 * against the original, and fixing it — want different gestures. A single text field rather than a
 * form of per-block editors, because fixing a wrong character is a text gesture.
 */
@Composable
fun OcrReviewScreen(bitmap: Bitmap, onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs by ThemeStore.state.collectAsState()

    var script by remember { mutableStateOf(OcrScript.DEFAULT) }
    var page by remember { mutableStateOf<OcrEngine.Page?>(null) }
    var blocks by remember { mutableStateOf<List<OcrBlock>>(emptyList()) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    // Per output line, the confidence of its WORST block — what drives the shading below.
    var lineConfidence by remember { mutableStateOf<List<Float>>(emptyList()) }

    // Detection is script-independent and is the expensive half, so it runs once; changing the chip
    // re-runs only the recogniser over crops already in hand.
    LaunchedEffect(script, prefs.ocrHighAccuracy) {
        busy = true
        status = null
        runCatching {
            val detected = page ?: withContext(Dispatchers.Default) {
                OcrEngine.detect(context, bitmap.toOcrImage())
            }.also { page = it }
            OcrEngine.recognise(context, detected, script, prefs.ocrHighAccuracy)
        }.onSuccess { result ->
            blocks = result.blocks
            lineConfidence = result.lineConfidences()
            // Caret at the START, not the end: the box is three lines tall now, so parking the
            // caret at the end opens it scrolled to the last three lines of the text.
            field = TextFieldValue(result.text, TextRange.Zero)
            // The count is the label the reserved status roles require: the shading must never be the
            // only way to know something needs checking.
            val doubtful = result.doubtfulLineCount()
            status = when {
                result.isEmpty -> "文字が見つかりませんでした"
                doubtful > 0 -> "${result.blocks.size} 行 · ${result.elapsedMs} ms · 要確認 $doubtful 行"
                else -> "${result.blocks.size} 行 · ${result.elapsedMs} ms · 全行あんしん"
            }
        }.onFailure { failure ->
            lineConfidence = emptyList()
            status = "認識に失敗しました: ${failure.message ?: failure.javaClass.simpleName}"
        }
        busy = false
    }

    // The window draws edge to edge, so the soft keyboard does NOT resize it — `adjustResize` alone
    // achieves nothing once decorFitsSystemWindows is false. imePadding() is what lifts the text box and
    // the buttons above the keyboard; without it you type into a field the keyboard is sitting on top of.
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding(),
    ) {
        // The image takes everything the text box and the controls do not (白い熊, 2026-08-08): you are
        // here to read a screenshot, so the screenshot gets the screen. It shrinks as the keyboard opens.
        ImagePane(
            bitmap = bitmap,
            blocks = blocks,
            modifier = Modifier.fillMaxWidth().weight(1f),
            onBlockTapped = { block ->
                // Put the caret on the tapped block — this is what makes checking a suspect character
                // against the original a single gesture rather than a hunt.
                field = field.copy(selection = TextRange(block.start, block.end))
            },
        )

        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                label = { Text("認識結果") },
                // Exactly three lines, always: it scrolls internally for longer text rather than eating
                // the image. minLines pins the height so the layout does not jump as the text changes.
                minLines = TEXT_BOX_LINES,
                maxLines = TEXT_BOX_LINES,
                // Shading only, never a change of text: the mapping is the identity, so editing,
                // selection and the tap-a-box-to-move-the-caret offsets all behave exactly as before.
                visualTransformation = remember(lineConfidence) { LineConfidenceShading(lineConfidence) },
            )
            if (busy) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }

        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        BottomBar(
            script = script,
            busy = busy,
            onScript = { script = it },
            onCopy = {
                copyToClipboard(context, field.text)
                // The app's own flash, not a system Toast: a Toast renders in the OS palette, which on
                // this phone is a white-on-black slab that has nothing to do with 白い熊's black-yellow.
                flash(context, prefs, "コピーしました")
                onClose()
            },
        )
    }
}

/** The screenshot, pinch-zoomable, with the detected lines drawn over it. */
@Composable
private fun ImagePane(
    bitmap: Bitmap,
    blocks: List<OcrBlock>,
    modifier: Modifier,
    onBlockTapped: (OcrBlock) -> Unit,
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val outline = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 12f)
                        // At 1x there is nothing to pan to; letting it drift then would just lose the image.
                        offset = if (scale <= 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(blocks) {
                    detectTapGestures { tap ->
                        val displayed = fitRect(size.width.toFloat(), size.height.toFloat(),
                            bitmap.width.toFloat(), bitmap.height.toFloat(), scale, offset)
                        val imageX = (tap.x - displayed.left) / displayed.scale
                        val imageY = (tap.y - displayed.top) / displayed.scale
                        blocks.firstOrNull { block ->
                            val xs = block.quad.map { it.x }
                            val ys = block.quad.map { it.y }
                            imageX >= xs.min() && imageX <= xs.max() &&
                                imageY >= ys.min() && imageY <= ys.max()
                        }?.let(onBlockTapped)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val displayed = fitRect(size.width, size.height,
                    bitmap.width.toFloat(), bitmap.height.toFloat(), scale, offset)
                drawImage(
                    image = bitmap.asImageBitmap(),
                    dstOffset = IntOffset(
                        displayed.left.roundToInt(), displayed.top.roundToInt(),
                    ),
                    dstSize = IntSize(
                        (bitmap.width * displayed.scale).roundToInt(),
                        (bitmap.height * displayed.scale).roundToInt(),
                    ),
                )
                blocks.forEach { block ->
                    val path = androidx.compose.ui.graphics.Path().apply {
                        block.quad.forEachIndexed { index, point ->
                            val x = displayed.left + point.x * displayed.scale
                            val y = displayed.top + point.y * displayed.scale
                            if (index == 0) moveTo(x, y) else lineTo(x, y)
                        }
                        close()
                    }
                    // The box agrees with the text: a line the recogniser was unsure of is marked on
                    // the image too, so the two halves of the window never disagree about what to check.
                    // Weight carries the state as well as hue — a heavier outline reads at a glance and
                    // survives being looked at by someone who does not separate amber from red.
                    val trust = OcrTrust.of(block.confidence)
                    drawPath(
                        path,
                        color = when (trust) {
                            OcrTrust.SOLID -> outline.copy(alpha = 0.45f)
                            OcrTrust.UNSURE -> ChartPalette.BAND_WARN.copy(alpha = 0.85f)
                            OcrTrust.DOUBTFUL -> ChartPalette.BAND_CRITICAL.copy(alpha = 0.9f)
                        },
                        style = Stroke(width = if (trust == OcrTrust.SOLID) 2f else 4f),
                    )
                }
            }
        }
    }
}

/** Height of the recognised-text box, in lines. */
private const val TEXT_BOX_LINES = 3

/** Where the image lands inside the pane, after fit-to-box plus the user's zoom and pan. */
private data class Displayed(val left: Float, val top: Float, val scale: Float)

private fun fitRect(
    paneWidth: Float,
    paneHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
    zoom: Float,
    pan: Offset,
): Displayed {
    val base = minOf(paneWidth / imageWidth, paneHeight / imageHeight)
    val scale = base * zoom
    return Displayed(
        left = (paneWidth - imageWidth * scale) / 2f + pan.x,
        top = (paneHeight - imageHeight * scale) / 2f + pan.y,
        scale = scale,
    )
}

@Composable
private fun BottomBar(
    script: OcrScript,
    busy: Boolean,
    onScript: (OcrScript) -> Unit,
    onCopy: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OcrScript.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == script,
                    onClick = { if (!busy && candidate != script) onScript(candidate) },
                    label = { Text(candidate.label) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
        Button(
            onClick = onCopy,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
            Text("  コピーして閉じる")
        }
    }
}

/** The branded flash every other surface in the app uses, styled from the same prefs. */
internal fun flash(context: Context, prefs: ThemePrefs, text: String) {
    FlashOverlay.show(
        context = context.applicationContext,
        text = text,
        backgroundColor = prefs.flashBackground,
        textColor = prefs.flashText,
        borderColor = prefs.flashBorder,
        borderWidthDp = prefs.flashBorderWidthDp,
        cornerRadiusDp = prefs.flashCornerRadiusDp,
        textSizeSp = prefs.flashTextSizeSp,
        fontWeight = prefs.flashFontWeight,
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        xDp = 0,
        yDp = 64,
        longDuration = false,
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("文字認識", text))
}

/** Android bitmap to the engine's plain pixel buffer — the one place the two worlds meet. */
private fun Bitmap.toOcrImage(): OcrImage {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return OcrImage(pixels, width, height)
}

/**
 * Tints each line of the recognised text by how sure the recogniser was of it.
 *
 * Keyed by **line index**, deliberately, not by the character offsets the blocks carry. Offsets go
 * stale the moment 白い熊 corrects a character — which is exactly when the shading is most wanted — so
 * the ranges are recomputed from the text in front of us on every pass. Editing inside a line keeps
 * that line's marking; adding or removing lines degrades to unmarked rather than to wrong.
 *
 * Two channels, never hue alone: an underline says "this needs a look" and the colour says how badly,
 * using the reserved status roles from [ChartPalette]. The status line under the field carries the
 * count in words, which is the label those roles are documented as always shipping beside.
 *
 * [OffsetMapping.Identity] is safe because not one character is added, removed or reordered.
 */
private class LineConfidenceShading(private val lineConfidence: List<Float>) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (lineConfidence.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val shaded = AnnotatedString.Builder(text.text)
        var lineStart = 0
        var lineIndex = 0
        val raw = text.text
        while (lineStart <= raw.length) {
            val newline = raw.indexOf('\n', lineStart)
            val lineEnd = if (newline == -1) raw.length else newline
            val confidence = lineConfidence.getOrNull(lineIndex)
            if (confidence != null && lineEnd > lineStart) {
                when (OcrTrust.of(confidence)) {
                    OcrTrust.SOLID -> Unit
                    OcrTrust.UNSURE -> shaded.addStyle(UNSURE_STYLE, lineStart, lineEnd)
                    OcrTrust.DOUBTFUL -> shaded.addStyle(DOUBTFUL_STYLE, lineStart, lineEnd)
                }
            }
            if (newline == -1) break
            lineStart = newline + 1
            lineIndex++
        }
        return TransformedText(shaded.toAnnotatedString(), OffsetMapping.Identity)
    }

    private companion object {
        val UNSURE_STYLE = SpanStyle(
            color = ChartPalette.BAND_WARN,
            textDecoration = TextDecoration.Underline,
        )
        val DOUBTFUL_STYLE = SpanStyle(
            color = ChartPalette.BAND_CRITICAL,
            textDecoration = TextDecoration.Underline,
        )
    }
}
