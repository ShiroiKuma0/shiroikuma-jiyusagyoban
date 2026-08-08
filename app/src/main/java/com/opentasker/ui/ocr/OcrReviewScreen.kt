package com.opentasker.ui.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import com.opentasker.core.ocr.OcrShareIntake
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import java.io.File
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.OutlinedButton
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
import com.opentasker.core.ocr.OcrModels
import com.opentasker.core.ocr.OcrTuning
import com.opentasker.core.ocr.OcrTrust
import com.opentasker.core.ocr.linesToCheck
import com.opentasker.core.ocr.lineConfidences
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.screens.FOCUS_OCR
import com.opentasker.ui.screens.UiCustomizationActivity
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
fun OcrReviewScreen(
    initialImage: File?,
    decode: (File) -> Bitmap?,
    onImagePicked: (File) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // The image can arrive three ways: shared in, picked here, or absent because a task opened the
    // window empty. All three are the same state once decoded.
    var source by remember { mutableStateOf(initialImage) }
    var page by remember { mutableStateOf<OcrEngine.Page?>(null) }
    val bitmap = remember(source) { source?.let(decode) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val copied = runCatching { OcrShareIntake.copyToCache(context, uri) }.getOrNull()
        if (copied != null) {
            onImagePicked(copied)
            // A different image means the cached detection is about the old one.
            page = null
            source = copied
        }
    }
    val prefs by ThemeStore.state.collectAsState()

    var script by remember { mutableStateOf(OcrScript.DEFAULT) }
    var blocks by remember { mutableStateOf<List<OcrBlock>>(emptyList()) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    // Per output line, the confidence of its WORST block — what drives the shading below.
    var lineConfidence by remember { mutableStateOf<List<Float>>(emptyList()) }
    // Bumped by 再認識. Detection is cached in `page`, so re-running needs both a new key here and
    // that cache cleared — otherwise the button would only re-run the recogniser over old boxes.
    var rerunToken by remember { mutableStateOf(0) }
    // Set when a weight file is missing. Its own state rather than a string, because this is a
    // do-something condition and has to be shown as one.
    var missingSlot by remember { mutableStateOf<com.opentasker.core.ocr.ModelSlot?>(null) }

    // Detection is script-independent and is the expensive half, so it runs once; changing the chip
    // re-runs only the recogniser over crops already in hand.
    // A knob change re-detects, because these are DETECTION settings — re-running recognition alone
    // over crops the old thresholds produced would show the old boxes with new confidence.
    val tuning = remember(
        prefs.ocrDetectionLongSide, prefs.ocrBinarisePercent,
        prefs.ocrBoxScorePercent, prefs.ocrUnclipTenths,
    ) {
        OcrTuning.from(
            prefs.ocrDetectionLongSide, prefs.ocrBinarisePercent,
            prefs.ocrBoxScorePercent, prefs.ocrUnclipTenths,
        )
    }

    LaunchedEffect(source, script, prefs.ocrHighAccuracy, tuning, rerunToken) {
        val image = bitmap
        if (image == null) {
            busy = false
            blocks = emptyList()
            lineConfidence = emptyList()
            field = TextFieldValue("")
            status = null
            return@LaunchedEffect
        }
        busy = true
        status = null
        runCatching {
            val detected = page ?: withContext(Dispatchers.Default) {
                OcrEngine.detect(context, image.toOcrImage(), tuning)
            }.also { page = it }
            OcrEngine.recognise(context, detected, script, prefs.ocrHighAccuracy)
        }.onSuccess { result ->
            missingSlot = null
            blocks = result.blocks
            lineConfidence = result.lineConfidences()
            // Caret at the START, not the end: the box is three lines tall now, so parking the
            // caret at the end opens it scrolled to the last three lines of the text.
            field = TextFieldValue(result.text, TextRange.Zero)
            // The count is the label the reserved status roles require: the shading must never be the
            // only way to know something needs checking.
            val toCheck = result.linesToCheck()
            status = when {
                result.isEmpty -> "文字が見つかりませんでした"
                toCheck > 0 -> "${result.blocks.size} 行 · ${result.elapsedMs} ms · 要確認 $toCheck 行"
                else -> "${result.blocks.size} 行 · ${result.elapsedMs} ms"
            }
        }.onFailure { failure ->
            lineConfidence = emptyList()
            blocks = emptyList()
            // A missing weight file is the expected state on a fresh install, not a crash. Say which
            // one and where to fix it, because "recognition failed" would send 白い熊 looking for a bug.
            missingSlot = (failure as? OcrModels.MissingModel)?.slot
            status = when (failure) {
                is OcrModels.MissingModel -> null   // the panel below says it properly
                else -> "認識に失敗しました: ${failure.message ?: failure.javaClass.simpleName}"
            }
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
        if (bitmap == null) {
            // No image yet — a task opened the window empty, or a picked file would not decode.
            EmptyPane(
                modifier = Modifier.fillMaxWidth().weight(1f),
                message = "画像がありません",
                detail = "「画像を選ぶ」で認識する画像を選んでください。スクリーンショットを共有しても開きます。",
            )
        } else ImagePane(
            bitmap = bitmap,
            blocks = blocks,
            modifier = Modifier.fillMaxWidth().weight(1f),
            onBlockTapped = { block ->
                // Put the caret on the tapped block — this is what makes checking a suspect character
                // against the original a single gesture rather than a hunt.
                field = field.copy(selection = TextRange(block.start, block.end))
            },
        )

        val missing = missingSlot
        if (missing != null) {
            MissingModelPanel(
                slot = missing,
                onAdjust = { UiCustomizationActivity.open(context, FOCUS_OCR) },
            )
        } else Box(Modifier.fillMaxWidth()) {
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
            onPickImage = { imagePicker.launch(arrayOf("image/*")) },
            onAdjust = { UiCustomizationActivity.open(context, FOCUS_OCR) },
            onRerun = { page = null; rerunToken++ },
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
                    val check = OcrTrust.of(block.lowestCharacter) == OcrTrust.CHECK
                    drawPath(
                        path,
                        color = if (check) ChartPalette.BAND_WARN.copy(alpha = 0.85f)
                        else outline.copy(alpha = 0.45f),
                        style = Stroke(width = if (check) 4f else 2f),
                    )
                }
            }
        }
    }
}

/** Height of the recognised-text box, in lines. */
private const val TEXT_BOX_LINES = 3

/**
 * The models are not set — said where it cannot be missed, with the way to fix it attached.
 *
 * This replaced a one-line status message under an empty text box, which 白い熊 read as "nothing was
 * recognised" rather than "the models have never been chosen" — a reasonable reading of a blank box
 * and a footnote, and the wrong conclusion entirely.
 */
@Composable
private fun MissingModelPanel(slot: com.opentasker.core.ocr.ModelSlot, onAdjust: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${slot.label}のモデルが未設定です",
            style = MaterialTheme.typography.titleMedium,
            color = ChartPalette.BAND_WARN,
        )
        Text(
            "認識には ${slot.fileName} が要ります。通常は 〇/[227] 日本語/[227][66] 辞書/[227][66][362] " +
                "文字認識モデル/ に置けば自動で見つかります。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onAdjust) {
            Icon(Icons.Filled.Tune, contentDescription = null)
            Text("  モデルを設定する")
        }
    }
}

/**
 * What fills the image half when there is nothing to show.
 *
 * It exists because the alternative failed in practice: with the models unset, the window showed an
 * empty text box and a one-line status underneath, and that reads as "nothing was recognised" rather
 * than "you have something to do" (白い熊, 2026-08-08). A blank panel is not an answer.
 */
@Composable
private fun EmptyPane(modifier: Modifier, message: String, detail: String, action: (@Composable () -> Unit)? = null) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(message, style = MaterialTheme.typography.titleMedium, color = ChartPalette.BAND_WARN)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action?.invoke()
        }
    }
}

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
    onPickImage: () -> Unit,
    onAdjust: () -> Unit,
    onRerun: () -> Unit,
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
        // 検出設定 and 再認識 are one loop: change a knob, come back, look again. Back from the
        // settings lands here because they are a separate window on the stack, and the knobs are
        // hoisted to the top of that screen so they are the first thing under the finger.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onPickImage, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Image, contentDescription = null)
                Text("  画像を選ぶ")
            }
            OutlinedButton(onClick = onAdjust, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Tune, contentDescription = null)
                Text("  検出設定")
            }
            OutlinedButton(onClick = onRerun, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text("  再認識")
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
                if (OcrTrust.of(confidence) == OcrTrust.CHECK) {
                    shaded.addStyle(CHECK_STYLE, lineStart, lineEnd)
                }
            }
            if (newline == -1) break
            lineStart = newline + 1
            lineIndex++
        }
        return TransformedText(shaded.toAnnotatedString(), OffsetMapping.Identity)
    }

    private companion object {
        // Amber, not red: this is "worth a glance", and the measurement says roughly one in two of
        // these is fine. Red would overstate what the number knows.
        val CHECK_STYLE = SpanStyle(
            color = ChartPalette.BAND_WARN,
            textDecoration = TextDecoration.Underline,
        )
    }
}
