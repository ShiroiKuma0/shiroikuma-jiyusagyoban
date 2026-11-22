package com.opentasker.ui.screens

import com.opentasker.ui.theme.ThemedSwitch
import com.opentasker.ui.components.Button
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import android.net.Uri
import com.opentasker.core.ocr.ModelSlot
import com.opentasker.core.ocr.OcrModels
import com.opentasker.core.ocr.ocrModelPath
import com.opentasker.core.ocr.withOcrModelPath
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import com.opentasker.ui.components.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import com.opentasker.ui.components.ThemedDropdownMenu
import com.opentasker.ui.components.ThemedSnackbarHost
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.opentasker.ui.components.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.opentasker.app.BuildConfig
import com.opentasker.app.OpenTaskerApp_NoHilt
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.opentasker.core.transfer.AutomationAuth
import com.opentasker.core.transfer.SettingsBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.sp
import com.opentasker.ui.theme.FontOption
import com.opentasker.ui.charts.ChartPaletteVerdict
import com.opentasker.ui.charts.ChartStylePreview
import com.opentasker.ui.theme.ThemePrefs
import com.opentasker.core.ocr.OcrTuning
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BorderOuter
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Task
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import com.opentasker.ui.components.DropdownMenuItem
import com.opentasker.ui.components.IconRole
import com.opentasker.ui.components.RoleIcon
import com.opentasker.ui.components.destructiveButtonColors
import com.opentasker.ui.components.dialogFrame
import com.opentasker.ui.theme.RoleSection
import com.opentasker.ui.theme.UiRole
import com.opentasker.ui.theme.UiRoles
import com.opentasker.ui.theme.role
import kotlinx.coroutines.flow.first

/**
 * Indent unit. Section headings and their rows share one left edge at 16 dp, so that the heading's
 * icon and the rows' rules line up; a row's own gutter (rule, icon) then sets its text in — which is
 * the step the kxkb cascade used to make with padding alone.
 */
private fun rowStartPadding(level: Int) = (level * 16).dp

/**
 * "白い熊 自由作業盤 UI" — the appearance-customization page, mirroring the sister apps' theme page:
 * logically sectioned, with individual items indented under their section headers, exposing the
 * colours, borders, and fonts that make up the default black-yellow look (all settable).
 */

/**
 * The 「文字認識」 settings, as their own block so that the review window can send 白い熊 straight to
 * them to adjust a knob: the section is unfolded and scrolled to (it used to be hoisted to the top
 * of the page, before the page folded).
 *
 * That path matters more than it looks: the point of these knobs is to change one, go back, and
 * re-recognise the same screenshot. Making 白い熊 scroll past nine hundred rows of chart settings to
 * find them each time would kill the loop the feature exists for.
 */

/**
 * Where to get the weights. Two repositories, because the Latin and Cyrillic recognisers live apart
 * from the Chinese/Japanese ones — sending 白い熊 to one page and letting them hunt would be worse than
 * two honest buttons.
 */
@Composable
private fun ModelDownloadRow() {
    val context = LocalContext.current
    fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    // Two pills that hug their labels, each with the › of a row that leads away (to the browser).
    PillRow(
        level = 1, label = "日本語・検出器", description = "", opens = true,
        icon = Icons.Filled.Public, iconRole = IconRole.VIEW,
        onClick = { open(ModelSlot.DOWNLOAD_PAGE) },
    )
    PillRow(
        level = 1, label = "Latin・Кириллица", description = "", opens = true,
        icon = Icons.Filled.Public, iconRole = IconRole.VIEW,
        onClick = { open(ModelSlot.DOWNLOAD_PAGE_MULTILINGUAL) },
    )
}

/**
 * One weight file: what it is, whether it is set, and a picker.
 *
 * Each row owns its own launcher so there is no "which slot am I picking for" state to get wrong. The
 * read permission is taken persistably, because a URI that works today and not after a reboot would
 * look exactly like a broken model.
 */
@Composable
private fun ModelRow(slot: ModelSlot) {
    val context = LocalContext.current
    val prefs by ThemeStore.state.collectAsState()
    val current = prefs.ocrModelPath(slot)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        ThemeStore.update { it.withOcrModelPath(slot, uri.toString()) }
        // The old session still points at the old weights until it is dropped.
        OcrModels.close()
    }

    IconRow(1, Icons.Filled.Memory, IconRole.VIEW, ruled = true) {
        Column(Modifier.weight(1f)) {
            RowLabel(slot.label)
            if (current.isBlank()) RowNote("未設定 — ${slot.fileName}", warn = true) else RowValue("設定済み")
            // The pill opens the file picker, so it carries the ›; its explanation sits beneath it.
            HuggingPill(
                label = if (current.isBlank()) "選ぶ" else "変更",
                opens = true,
                modifier = Modifier.padding(vertical = 3.dp),
                onClick = { picker.launch(arrayOf("*/*")) },
            )
            RowNote(slot.about)
        }
    }
}

/** Focus key for [UiCustomizationScreen] — the 文字認識 detection rows. */
const val FOCUS_OCR = "ocr"

private fun SectionRows.ocrSectionRows(prefs: ThemePrefs) {
    item {
                SwitchRow(
                    level = 1, label = "High-accuracy model",
                    description = "On: PP-OCRv5 server (81 MB). Off: mobile (16 MB), about 2.5x faster — " +
                        "roughly 2 s instead of 5 s on a full-width screenshot. Measured on clean screenshot " +
                        "text the two are equivalent; the server model's headroom is for photographed and " +
                        "handwritten text. Japanese and English only — there is no server-sized Latin or " +
                        "Cyrillic recogniser, so those chips are unaffected.",
                    checked = prefs.ocrHighAccuracy,
                    onCheckedChange = { v -> ThemeStore.update { it.copy(ocrHighAccuracy = v) } },
                )
            }

    item { SubHeader(1, "Models") }
    item {
        SettingNote(
            "The recognition weights are about 100 MB and never change, so they are NOT in the APK — " +
                "every kept build would have carried another copy. Fetch them once, point each row at " +
                "the file, and they stay. The character dictionaries do ship, because each one has to " +
                "match its model exactly."
        )
    }
    item { ModelDownloadRow() }
    items(ModelSlot.entries.toList(), key = { "ocr-model-${it.id}" }) { slot -> ModelRow(slot) }

    item { SubHeader(1, "Detection") }
    item {
                SliderRow(
                    level = 1, label = "Detection long side",
                    value = prefs.ocrDetectionLongSide, valueText = "${prefs.ocrDetectionLongSide} px",
                    range = OcrTuning.LONG_SIDE_MIN.toFloat()..OcrTuning.LONG_SIDE_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(ocrDetectionLongSide = v) } },
                )
            }
    item {
                SettingNote(
                    "The size the detector actually sees. 1600 px is measured, not inherited — against " +
                        "PP-OCR's own 960 default it halved the character error on a full-width screenshot, " +
                        "and the errors it removed were exactly the small text. Higher costs time with the area."
                )
            }
    item {
                SliderRow(
                    level = 1, label = "Binarisation threshold",
                    value = prefs.ocrBinarisePercent, valueText = "0.${prefs.ocrBinarisePercent}",
                    range = OcrTuning.BINARY_PERCENT_MIN.toFloat()..OcrTuning.BINARY_PERCENT_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(ocrBinarisePercent = v) } },
                )
            }
    item {
                SettingNote(
                    "Where the probability map becomes text. Lower finds fainter text and starts joining " +
                        "neighbouring lines into one box; higher splits words off the ends of lines."
                )
            }
    item {
                SliderRow(
                    level = 1, label = "Box score threshold",
                    value = prefs.ocrBoxScorePercent, valueText = "0.${prefs.ocrBoxScorePercent}",
                    range = OcrTuning.BOX_SCORE_PERCENT_MIN.toFloat()..OcrTuning.BOX_SCORE_PERCENT_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(ocrBoxScorePercent = v) } },
                )
            }
    item {
                SettingNote(
                    "The confidence a detected region needs to survive. Raising it drops marginal boxes — " +
                        "useful on a noisy photograph, and a good way to lose real faint lines on a clean screenshot."
                )
            }
    item {
                SliderRow(
                    level = 1, label = "Unclip ratio",
                    value = prefs.ocrUnclipTenths, valueText = "%.1f".format(prefs.ocrUnclipTenths / 10f),
                    range = OcrTuning.UNCLIP_TENTHS_MIN.toFloat()..OcrTuning.UNCLIP_TENTHS_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(ocrUnclipTenths = v) } },
                )
            }
    item {
                SettingNote(
                    "How far each detected box is grown before the crop is taken — the one to leave alone " +
                        "without a test image in front of you. Detection predicts a SHRUNK region, and this is " +
                        "what puts the ascenders and diacritics back inside it: too low silently decapitates " +
                        "every line and 'ä' starts reading as 'a'. Too high swallows the line above. 1.5 is " +
                        "PaddleOCR's own value and measured correct here."
                )
            }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UiCustomizationScreen(
    onBack: () -> Unit,
    /** When [FOCUS_OCR], the 文字認識 section is unfolded and scrolled to — see [ocrSectionRows]. */
    focusSection: String? = null,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val prefs by ThemeStore.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    var chartColorTarget by remember { mutableStateOf<ChartColorTarget?>(null) }
    var roleTarget by remember { mutableStateOf<UiRole?>(null) }
    var confirmResetColours by remember { mutableStateOf(false) }

    // The page is a folding accordion: its sections in 白い熊's order, each folded unless it is in the
    // open set. Both live in the page's own preferences, so they travel in the export.
    val listState = rememberLazyListState()
    val order = remember(prefs.uiSectionOrder) { sectionOrder(prefs.uiSectionOrder) }
    val open = remember(prefs.uiSectionsOpen) { openSections(prefs.uiSectionsOpen) }
    fun toggleSection(id: String) = ThemeStore.update { p ->
        val now = openSections(p.uiSectionsOpen)
        p.copy(uiSectionsOpen = encodeSections(if (id in now) now - id else now + id))
    }
    var showFontPicker by remember { mutableStateOf(false) }
    var showBubbleFontPicker by remember { mutableStateOf(false) }
    var showPickerFontPicker by remember { mutableStateOf(false) }
    var fontsRefresh by remember { mutableIntStateOf(0) }

    // Automation intent surface (StateExportReceiver): master switch + shared secret.
    var automationEnabled by remember { mutableStateOf(AutomationAuth.enabled(context)) }
    var automationRequireToken by remember { mutableStateOf(AutomationAuth.requireToken(context)) }
    var automationToken by remember { mutableStateOf(AutomationAuth.token(context)) }

    // Export/Import (Kōjiki-style): the settable backup directory + its latest export, re-queried
    // on page open and after every pick/export via the refresh tick.
    var showEximPanel by remember { mutableStateOf(false) }
    var eximRefresh by remember { mutableIntStateOf(0) }
    val eximStatus by produceState<Pair<String?, SettingsBackup.LatestExport?>>(
        initialValue = null to null,
        eximRefresh,
    ) {
        value = withContext(Dispatchers.IO) {
            SettingsBackup.dirLabel(context) to SettingsBackup.latestExport(context)
        }
    }
    val eximDirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            SettingsBackup.setDirUri(context, uri)
            eximRefresh++
        }
    }

    val fontImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = ThemeStore.importFont(uri)
            if (name != null) {
                ThemeStore.update { it.copy(fontFileName = name) }
                fontsRefresh++
            } else {
                scope.launch { snackbarHostState.showSnackbar("That file is not a usable .ttf or .otf font") }
            }
        }
    }

    // The whole page, as rows handed to [SectionRows] — once for the list, and once more without a
    // list whenever a chip is tapped, to learn at which row the section's heading stands.
    val page: (SectionRows, Set<String>, (String) -> Unit) -> Unit = { rows, openNow, onJump -> with(rows) {
        item(key = "chip-index") {
            ChipIndex(
                order = order,
                onJump = onJump,
                onReorder = { ids -> ThemeStore.update { it.copy(uiSectionOrder = ids.joinToString(",")) } },
            )
        }
        order.forEach { section ->
            val id = section.id
            header(id) { SectionHeader(section, open = id in openNow, onToggle = { toggleSection(id) }) }
            // A folded section emits no rows at all: its heading and the line above it are all of it.
            if (id in openNow) when (id) {
            "exim" -> {
            // --- Export / Import (Kōjiki UI-page flow) ---
            item {
                // Choosing the directory opens the system picker, so it is a pill with a › and the
                // directory it stands at beneath — it used to be a plain row that read as a note.
                val dirName = eximStatus.first
                PillRow(
                    level = 1,
                    label = "Export directory",
                    description = dirName ?: "Not set — choose a directory",
                    opens = true,
                    warn = dirName == null,
                    icon = Icons.Filled.Storage,
                    iconRole = IconRole.SHARE,
                    onClick = { eximDirPicker.launch(SettingsBackup.dirUri(context)) },
                )
            }
            item {
                val (dirName, latest) = eximStatus
                val (message, warn) = when {
                    dirName == null -> "No directory set yet — pick one to enable one-tap export." to true
                    latest == null -> "No export in this directory yet." to false
                    else -> "Last export: ${latest.timestampText}" to false
                }
                SettingNote(message, warn = warn)
            }
            item {
                PillRow(
                    level = 1,
                    label = "Export / Import…",
                    description = "Back up or restore everything settable — the whole workspace and all app settings — as one ZIP.",
                    main = true,
                    opens = true,
                    icon = Icons.Filled.ImportExport,
                    iconRole = IconRole.SHARE,
                    onClick = { showEximPanel = true },
                )
            }
            item {
                SwitchRow(
                    level = 1,
                    label = "Automation export",
                    description = "Let sister apps trigger this export, and back this app's data up " +
                        "and restore it. On by default — 白い熊 応用管理 needs it to reach a freshly " +
                        "installed copy on a clean phone.",
                    checked = automationEnabled,
                    icon = Icons.Filled.ImportExport,
                    iconRole = IconRole.SHARE,
                    onCheckedChange = {
                        automationEnabled = it
                        AutomationAuth.setEnabled(context, it)
                    },
                )
            }
            item {
                SwitchRow(
                    level = 1,
                    label = "Use authorization token?",
                    description = "Off: any sister app may drive the automation. On: a caller must " +
                        "also present the token below. The data door checks the caller's identity " +
                        "and signature either way, so this is extra, not the gate.",
                    checked = automationRequireToken,
                    icon = Icons.Filled.Lock,
                    iconRole = IconRole.BLOCK,
                    onCheckedChange = {
                        automationRequireToken = it
                        AutomationAuth.setRequireToken(context, it)
                    },
                )
            }
            // The token row appears only when it is being asked for. A 48-character secret sitting
            // under a switch that is off invites 白い熊 to paste it somewhere it will do nothing.
            if (automationRequireToken) item {
                val clipboard = LocalClipboardManager.current
                PillRow(
                    level = 1,
                    label = "Copy automation token",
                    description = "${automationToken.take(8)}…${automationToken.takeLast(8)}",
                    icon = Icons.Filled.ContentCopy,
                    iconRole = IconRole.SHARE,
                    onClick = {
                        clipboard.setText(AnnotatedString(automationToken))
                        scope.launch { snackbarHostState.showSnackbar("Automation token copied") }
                    },
                )
            }
            // Regenerating throws the old token away, so its pill is the destructive one.
            if (automationRequireToken) item {
                PillRow(
                    level = 1,
                    label = "Regenerate token",
                    description = "Makes a new token. Every copy pasted elsewhere has to be updated.",
                    destructive = true,
                    icon = Icons.Filled.Refresh,
                    iconRole = IconRole.DELETE,
                    onClick = {
                        automationToken = AutomationAuth.regenerateToken(context)
                        scope.launch { snackbarHostState.showSnackbar("Automation token regenerated — update pasted copies") }
                    },
                )
            }

            }
            "colors" -> {
            item {
                SettingNote(
                    "The foundation colours. Every other colour follows these until it is set apart in its own section."
                )
            }
            item { ColorRow(1, "Background", prefs.background, ColorTarget.Background) { colorTarget = it } }
            item { ColorRow(1, "Text", prefs.text, ColorTarget.Text) { colorTarget = it } }
            item { ColorRow(1, "Secondary text", prefs.textSecondary, ColorTarget.TextSecondary) { colorTarget = it } }
            item { ColorRow(1, "Accent", prefs.accent, ColorTarget.Accent) { colorTarget = it } }
            item { ColorRow(1, "Surface (cards & bars)", prefs.surface, ColorTarget.Surface) { colorTarget = it } }

            }
            "borders" -> {
            item { ColorRow(1, "Border color", prefs.border, ColorTarget.Border) { colorTarget = it } }
            item {
                SliderRow(
                    level = 1,
                    label = "Border width",
                    value = prefs.borderWidthDp,
                    valueText = "${prefs.borderWidthDp} dp",
                    range = 0f..ThemePrefs.BORDER_WIDTH_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(borderWidthDp = v) } },
                )
            }

            }
            "typography" -> {
            item {
                FontRow(
                    level = 1,
                    fileName = prefs.fontFileName,
                    onClick = { showFontPicker = true },
                )
            }
            item {
                WeightRow(
                    level = 1,
                    weight = prefs.fontWeight,
                    onPick = { w -> ThemeStore.update { it.copy(fontWeight = w) } },
                )
            }
            item {
                SliderRow(
                    level = 1,
                    label = "Text size",
                    value = prefs.fontScalePct,
                    valueText = "${prefs.fontScalePct}%",
                    range = ThemePrefs.SCALE_MIN.toFloat()..ThemePrefs.SCALE_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(fontScalePct = v) } },
                )
            }
            item { SampleRow(level = 1) }

            }
            "flash" -> {
            item { FlashPreview(level = 1, prefs = prefs) }
            item { ColorRow(1, "Background", prefs.flashBackground, ColorTarget.FlashBackground) { colorTarget = it } }
            item { ColorRow(1, "Text", prefs.flashText, ColorTarget.FlashText) { colorTarget = it } }
            item { ColorRow(1, "Border color", prefs.flashBorder, ColorTarget.FlashBorder) { colorTarget = it } }
            item {
                SliderRow(
                    level = 1,
                    label = "Border width",
                    value = prefs.flashBorderWidthDp,
                    valueText = "${prefs.flashBorderWidthDp} dp",
                    range = 0f..ThemePrefs.FLASH_BORDER_WIDTH_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(flashBorderWidthDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1,
                    label = "Corner radius",
                    value = prefs.flashCornerRadiusDp,
                    valueText = "${prefs.flashCornerRadiusDp} dp",
                    range = 0f..ThemePrefs.FLASH_CORNER_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(flashCornerRadiusDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1,
                    label = "Text size",
                    value = prefs.flashTextSizeSp,
                    valueText = "${prefs.flashTextSizeSp} sp",
                    range = ThemePrefs.FLASH_TEXT_MIN.toFloat()..ThemePrefs.FLASH_TEXT_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(flashTextSizeSp = v) } },
                )
            }
            item {
                WeightRow(
                    level = 1,
                    weight = prefs.flashFontWeight,
                    // Flash weight must stay 100..900 (FontWeight(0) is invalid); map "Default" to Bold.
                    onPick = { w -> ThemeStore.update { it.copy(flashFontWeight = if (w == 0) 700 else w) } },
                )
            }

            }
            "editor" -> {
            item {
                SwitchRow(
                    level = 1,
                    label = "Advanced action picker",
                    icon = Icons.Filled.Checklist,
                    description = "When adding an action, browse a full-screen list folded by category, each action expandable to its description and fields.",
                    checked = prefs.advancedActionPicker,
                    onCheckedChange = { ThemeStore.update { p -> p.copy(advancedActionPicker = it) } },
                )
            }

            }
            "tasklist" -> {
            item { TaskIconSizeRow(level = 1, prefs = prefs) }

            }
            "tasks" -> {
            item {
                SliderRow(
                    level = 1, label = "Padding between cards",
                    value = prefs.taskCardGapDp, valueText = "${prefs.taskCardGapDp} dp",
                    range = 0f..ThemePrefs.TASK_CARD_GAP_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(taskCardGapDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Padding inside card",
                    value = prefs.taskCardVPadDp, valueText = "${prefs.taskCardVPadDp} dp",
                    range = 0f..ThemePrefs.TASK_CARD_VPAD_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(taskCardVPadDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Padding inside action rows",
                    value = prefs.actionRowPadDp, valueText = "${prefs.actionRowPadDp} dp",
                    range = 0f..ThemePrefs.ACTION_ROW_PAD_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(actionRowPadDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Action label text size",
                    value = prefs.actionLabelSizeSp, valueText = "${prefs.actionLabelSizeSp} sp",
                    range = ThemePrefs.ACTION_LABEL_MIN.toFloat()..ThemePrefs.ACTION_LABEL_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(actionLabelSizeSp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Action name/value text size",
                    value = prefs.actionValueSizeSp, valueText = "${prefs.actionValueSizeSp} sp",
                    range = ThemePrefs.ACTION_VALUE_MIN.toFloat()..ThemePrefs.ACTION_VALUE_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(actionValueSizeSp = v) } },
                )
            }
            item { ColorRow(1, "Action name colour", prefs.actionNameColor, ColorTarget.ActionName) { colorTarget = it } }
            item { ColorRow(1, "Action value colour", prefs.actionValueColor, ColorTarget.ActionValue) { colorTarget = it } }
            item { ColorRow(1, "Action label frame colour", prefs.actionLabelFrameColor, ColorTarget.ActionLabelFrame) { colorTarget = it } }
            item {
                SliderRow(
                    level = 1, label = "Action label frame width",
                    value = prefs.actionLabelFrameWidthDp, valueText = "${prefs.actionLabelFrameWidthDp} dp",
                    range = 0f..ThemePrefs.BORDER_WIDTH_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(actionLabelFrameWidthDp = v) } },
                )
            }
            item { ColorRow(1, "Action border colour", prefs.actionBorderColor, ColorTarget.ActionBorder) { colorTarget = it } }
            item {
                SliderRow(
                    level = 1, label = "Action border width",
                    value = prefs.actionBorderWidthDp, valueText = "${prefs.actionBorderWidthDp} dp",
                    range = 0f..ThemePrefs.BORDER_WIDTH_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(actionBorderWidthDp = v) } },
                )
            }
            item { ColorRow(1, "Selection highlight colour", prefs.selectionColor, ColorTarget.Selection) { colorTarget = it } }
            item {
                SliderRow(
                    level = 1, label = "Padding inside group headers",
                    value = prefs.groupHeaderVPadDp, valueText = "${prefs.groupHeaderVPadDp} dp",
                    range = 0f..ThemePrefs.GROUP_HEADER_VPAD_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(groupHeaderVPadDp = v) } },
                )
            }
            item { ColorRow(1, "Group header colour", prefs.groupHeaderColor, ColorTarget.GroupHeader) { colorTarget = it } }
            item { ColorRow(1, "Group header border colour", prefs.groupHeaderBorderColor, ColorTarget.GroupHeaderBorder) { colorTarget = it } }
            item {
                SliderRow(
                    level = 1, label = "Group header border width",
                    value = prefs.groupHeaderBorderWidthDp, valueText = "${prefs.groupHeaderBorderWidthDp} dp",
                    range = 0f..ThemePrefs.GROUP_HEADER_BORDER_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(groupHeaderBorderWidthDp = v) } },
                )
            }

            // Variables tab — the folded row's name/value styling. Defaults equal the action-view data
            // styling (blue name / white value, 16 sp); name and value colour + size are independent.
            }
            "variables" -> {
            item {
                SliderRow(
                    level = 1, label = "Padding inside variable rows",
                    value = prefs.varRowPadDp, valueText = "${prefs.varRowPadDp} dp",
                    range = 0f..ThemePrefs.ACTION_ROW_PAD_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(varRowPadDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Variable name text size",
                    value = prefs.varNameSizeSp, valueText = "${prefs.varNameSizeSp} sp",
                    range = ThemePrefs.ACTION_VALUE_MIN.toFloat()..ThemePrefs.ACTION_VALUE_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(varNameSizeSp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Variable value text size",
                    value = prefs.varValueSizeSp, valueText = "${prefs.varValueSizeSp} sp",
                    range = ThemePrefs.ACTION_VALUE_MIN.toFloat()..ThemePrefs.ACTION_VALUE_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(varValueSizeSp = v) } },
                )
            }
            item { ColorRow(1, "Variable name colour", prefs.varNameColor, ColorTarget.VarName) { colorTarget = it } }
            item { ColorRow(1, "Variable value colour", prefs.varValueColor, ColorTarget.VarValue) { colorTarget = it } }

            }
            "monitor" -> {
            item {
                SliderRow(
                    level = 1, label = "Task row spacing",
                    value = prefs.monitorRowPadDp, valueText = "${prefs.monitorRowPadDp} dp",
                    range = 0f..ThemePrefs.MONITOR_PAD_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(monitorRowPadDp = v) } },
                )
            }

            }
            "panelbars" -> {
            item {
                SliderRow(
                    level = 1, label = "Oval bar border width",
                    value = prefs.ovalBarBorderWidthDp, valueText = "${prefs.ovalBarBorderWidthDp} dp",
                    range = 0f..ThemePrefs.OVAL_BAR_BORDER_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(ovalBarBorderWidthDp = v) } },
                )
            }
            item { ColorRow(1, "Oval bar border colour", prefs.ovalBarBorderColor, ColorTarget.OvalBarBorder) { colorTarget = it } }

            }
            "bubbles" -> {
            item { FreezeBubblePreview(level = 1, prefs = prefs) }
            item {
                SliderRow(
                    level = 1,
                    label = "Icon size",
                    value = prefs.bubbleIconSizeDp,
                    valueText = "${prefs.bubbleIconSizeDp} dp",
                    range = ThemePrefs.BUBBLE_ICON_MIN.toFloat()..ThemePrefs.BUBBLE_ICON_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(bubbleIconSizeDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1,
                    label = "Icon roundness",
                    value = prefs.bubbleIconCornerDp,
                    valueText = "${prefs.bubbleIconCornerDp} dp",
                    range = 0f..ThemePrefs.BUBBLE_ICON_CORNER_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(bubbleIconCornerDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1,
                    label = "Label size",
                    value = prefs.bubbleLabelSizeSp,
                    valueText = "${prefs.bubbleLabelSizeSp} sp",
                    range = ThemePrefs.BUBBLE_LABEL_MIN.toFloat()..ThemePrefs.BUBBLE_LABEL_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(bubbleLabelSizeSp = v) } },
                )
            }
            item {
                WeightRow(
                    level = 1,
                    weight = prefs.bubbleLabelWeight,
                    onPick = { w -> ThemeStore.update { it.copy(bubbleLabelWeight = if (w == 0) 700 else w) } },
                )
            }
            item { FontRow(level = 1, fileName = prefs.bubbleFontFileName, onClick = { showBubbleFontPicker = true }) }

            // Flash bubbles (通知明滅): the left-edge Desktop icons share the freeze bubbles' icon /
            // label styling above; only the gesture behaviors and kill tasks are their own.
            }
            "flashbubbles" -> {
            item {
                FlashBehaviorRow(
                    level = 1, label = "Tap",
                    value = prefs.flashTapBehavior,
                    onPick = { v -> ThemeStore.update { it.copy(flashTapBehavior = v) } },
                )
            }
            item {
                FlashBehaviorRow(
                    level = 1, label = "Long-tap",
                    value = prefs.flashLongTapBehavior,
                    onPick = { v -> ThemeStore.update { it.copy(flashLongTapBehavior = v) } },
                )
            }
            item {
                FlashTaskRow(
                    level = 1, label = "Kill-flash task (gets %APP_PACKAGE)",
                    value = prefs.flashKillTaskName,
                    onChange = { v -> ThemeStore.update { it.copy(flashKillTaskName = v) } },
                )
            }
            item {
                FlashTaskRow(
                    level = 1, label = "Kill-all task (kill icon / notification tap)",
                    value = prefs.flashKillAllTaskName,
                    onChange = { v -> ThemeStore.update { it.copy(flashKillAllTaskName = v) } },
                )
            }

            }
            "picker" -> {
            item {
                SliderRow(
                    level = 1, label = "Font size",
                    value = prefs.pickerFontSizeSp, valueText = "${prefs.pickerFontSizeSp} sp",
                    range = ThemePrefs.PICKER_FONT_MIN.toFloat()..ThemePrefs.PICKER_FONT_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(pickerFontSizeSp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Row spacing",
                    value = prefs.pickerRowPadDp, valueText = "${prefs.pickerRowPadDp} dp",
                    range = 0f..ThemePrefs.PICKER_PAD_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(pickerRowPadDp = v) } },
                )
            }
            item {
                // Its own size since 2026-09-22. It used to be the font size plus six, which meant
                // asking for a bigger icon meant asking for bigger type — and 白い熊 wanted both,
                // separately. Zero hands it back to the type, the way it was.
                SliderRow(
                    level = 1, label = "Task icon size",
                    value = prefs.pickerIconDp,
                    valueText = if (prefs.pickerIconDp == 0) "follow the font" else "${prefs.pickerIconDp} dp",
                    range = 0f..ThemePrefs.PICKER_ICON_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(pickerIconDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Indent per level",
                    value = prefs.pickerIndentDp, valueText = "${prefs.pickerIndentDp} dp",
                    range = 0f..ThemePrefs.PICKER_INDENT_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(pickerIndentDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Group box roundness",
                    value = prefs.pickerGroupCornerDp, valueText = "${prefs.pickerGroupCornerDp} dp",
                    range = 0f..ThemePrefs.PICKER_CORNER_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(pickerGroupCornerDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Group box border",
                    value = prefs.pickerGroupBorderDp, valueText = "${prefs.pickerGroupBorderDp} dp",
                    range = 0f..ThemePrefs.PICKER_BORDER_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(pickerGroupBorderDp = v) } },
                )
            }
            item { FontRow(level = 1, fileName = prefs.pickerFontFileName, onClick = { showPickerFontPicker = true }) }

            }
            "importreview" -> {
            item { ImportReviewPreview(level = 1, prefs = prefs) }
            item {
                SliderRow(
                    level = 1, label = "Header & stats size",
                    value = prefs.importHeaderSp, valueText = "${prefs.importHeaderSp} sp",
                    range = 12f..ThemePrefs.IMPORT_TEXT_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(importHeaderSp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Section title size",
                    value = prefs.importSectionSp, valueText = "${prefs.importSectionSp} sp",
                    range = 12f..ThemePrefs.IMPORT_TEXT_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(importSectionSp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Item row size",
                    value = prefs.importItemSp, valueText = "${prefs.importItemSp} sp",
                    range = 12f..ThemePrefs.IMPORT_TEXT_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(importItemSp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Warnings size",
                    value = prefs.importWarnSp, valueText = "${prefs.importWarnSp} sp",
                    range = 12f..ThemePrefs.IMPORT_TEXT_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(importWarnSp = v) } },
                )
            }
            item { ColorRow(1, "Conflict colour", prefs.importConflictColor, ColorTarget.ImportConflict) { colorTarget = it } }
            item {
                SliderRow(
                    level = 1, label = "Row spacing",
                    value = prefs.importRowPadDp, valueText = "${prefs.importRowPadDp} dp",
                    range = 0f..ThemePrefs.IMPORT_ROW_PAD_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(importRowPadDp = v) } },
                )
            }


            }
            "ocr" -> ocrSectionRows(prefs)
            // --- 「健康」 charts — the numbers the band screens draw with ---------------------
            "charts" -> {
            item { ChartLivePreview(level = 1, prefs = prefs) }

            item { SubHeader(1, "Sizes") }
            item {
                SliderRow(
                    level = 1, label = "Preview height",
                    value = prefs.chartPreviewHeightDp, valueText = "${prefs.chartPreviewHeightDp} dp",
                    range = ThemePrefs.CHART_PREVIEW_H_MIN.toFloat()..ThemePrefs.CHART_PREVIEW_H_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(chartPreviewHeightDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Full-screen height",
                    value = prefs.chartDetailHeightDp, valueText = "${prefs.chartDetailHeightDp} dp",
                    range = ThemePrefs.CHART_DETAIL_H_MIN.toFloat()..ThemePrefs.CHART_DETAIL_H_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(chartDetailHeightDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Space between cards",
                    value = prefs.chartCardGapDp, valueText = "${prefs.chartCardGapDp} dp",
                    range = 0f..ThemePrefs.CHART_GAP_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(chartCardGapDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Axis label size",
                    value = prefs.chartAxisTextSp, valueText = "${prefs.chartAxisTextSp} sp",
                    range = ThemePrefs.CHART_AXIS_SP_MIN.toFloat()..ThemePrefs.CHART_AXIS_SP_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(chartAxisTextSp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Headline number size",
                    value = prefs.chartHeadlineSp, valueText = "${prefs.chartHeadlineSp} sp",
                    range = ThemePrefs.CHART_HEADLINE_MIN.toFloat()..ThemePrefs.CHART_HEADLINE_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(chartHeadlineSp = v) } },
                )
            }

            item { SubHeader(1, "Marks") }
            item {
                SliderRow(
                    level = 1, label = "Line width",
                    value = prefs.chartLineWidthDp, valueText = "${prefs.chartLineWidthDp} dp",
                    range = 1f..ThemePrefs.CHART_LINE_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(chartLineWidthDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Sample dot size",
                    value = prefs.chartDotSizeDp, valueText = "${prefs.chartDotSizeDp} dp",
                    range = 0f..ThemePrefs.CHART_DOT_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(chartDotSizeDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Hourly capsule width",
                    value = prefs.chartCapsuleWidthDp, valueText = "${prefs.chartCapsuleWidthDp} dp",
                    range = 2f..ThemePrefs.CHART_MARK_W_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(chartCapsuleWidthDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Step bar width",
                    value = prefs.chartBarWidthDp, valueText = "${prefs.chartBarWidthDp} dp",
                    range = 1f..ThemePrefs.CHART_MARK_W_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(chartBarWidthDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Blood-pressure cap width",
                    value = prefs.chartDumbbellWidthDp, valueText = "${prefs.chartDumbbellWidthDp} dp",
                    range = 2f..ThemePrefs.CHART_MARK_W_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(chartDumbbellWidthDp = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Sleep block height",
                    value = prefs.chartHypnogramBandPct, valueText = "${prefs.chartHypnogramBandPct} % of its row",
                    range = 10f..100f,
                    onChange = { v -> ThemeStore.update { it.copy(chartHypnogramBandPct = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Rounded data-end radius",
                    value = prefs.chartCornerRadiusDp, valueText = "${prefs.chartCornerRadiusDp} dp",
                    range = 0f..ThemePrefs.CHART_CORNER_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(chartCornerRadiusDp = v) } },
                )
            }

            item { SubHeader(1, "Ink") }
            item { ChartColorRow(1, ChartColorTarget.Grid, prefs) { chartColorTarget = it } }
            item {
                SliderRow(
                    level = 1, label = "Grid opacity",
                    value = prefs.chartGridOpacityPct, valueText = "${prefs.chartGridOpacityPct} %",
                    range = 0f..100f,
                    onChange = { v -> ThemeStore.update { it.copy(chartGridOpacityPct = v) } },
                )
            }
            item { ChartColorRow(1, ChartColorTarget.AxisText, prefs) { chartColorTarget = it } }
            item {
                SliderRow(
                    level = 1, label = "Area fill under a line",
                    value = prefs.chartFillOpacityPct, valueText = "${prefs.chartFillOpacityPct} %",
                    range = 0f..100f,
                    onChange = { v -> ThemeStore.update { it.copy(chartFillOpacityPct = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Glow behind a line",
                    value = prefs.chartGlowOpacityPct, valueText = "${prefs.chartGlowOpacityPct} %",
                    range = 0f..100f,
                    onChange = { v -> ThemeStore.update { it.copy(chartGlowOpacityPct = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "No-measurement tint",
                    value = prefs.chartGapTintPct, valueText = "${prefs.chartGapTintPct} %",
                    range = 0f..100f,
                    onChange = { v -> ThemeStore.update { it.copy(chartGapTintPct = v) } },
                )
            }

            item { SubHeader(1, "What gets drawn") }
            item {
                SwitchRow(
                    level = 1, label = "Grid",
                    icon = Icons.Filled.Visibility, iconRole = IconRole.VIEW,
                    description = "The recessive lines behind the data.",
                    checked = prefs.chartShowGrid,
                    onCheckedChange = { v -> ThemeStore.update { it.copy(chartShowGrid = v) } },
                )
            }
            item {
                SwitchRow(
                    level = 1, label = "Sample dots",
                    icon = Icons.Filled.Visibility, iconRole = IconRole.VIEW,
                    description = "The real readings, drawn over the smoothed line. Turning these off hides where the curve is interpolating.",
                    checked = prefs.chartShowDots,
                    onCheckedChange = { v -> ThemeStore.update { it.copy(chartShowDots = v) } },
                )
            }
            item {
                SwitchRow(
                    level = 1, label = "Flagged-sample ✕ marks",
                    icon = Icons.Filled.Visibility, iconRole = IconRole.VIEW,
                    description = "Readings the outlier filter dropped, shown at their real value.",
                    checked = prefs.chartShowRejected,
                    onCheckedChange = { v -> ThemeStore.update { it.copy(chartShowRejected = v) } },
                )
            }
            item {
                SwitchRow(
                    level = 1, label = "Tint stretches with no measurement",
                    icon = Icons.Filled.Visibility, iconRole = IconRole.VIEW,
                    description = "Off, a gap looks the same as a flat reading — the chart stops showing that the band was not measuring.",
                    checked = prefs.chartShowGaps,
                    onCheckedChange = { v -> ThemeStore.update { it.copy(chartShowGaps = v) } },
                )
            }
            item {
                SliderRow(
                    level = 1, label = "Opening time span",
                    value = prefs.chartDefaultSpanHours, valueText = "${prefs.chartDefaultSpanHours} h",
                    range = 1f..ThemePrefs.CHART_SPAN_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(chartDefaultSpanHours = v) } },
                )
            }
            item { ChartCurveRow(1, prefs.chartCurveMode) { v -> ThemeStore.update { it.copy(chartCurveMode = v) } } }

            item { SubHeader(1, "Series colours") }
            item { ChartColorRow(1, ChartColorTarget.HeartRate, prefs) { chartColorTarget = it } }
            item { ChartColorRow(1, ChartColorTarget.BandState, prefs) { chartColorTarget = it } }
            item { ChartColorRow(1, ChartColorTarget.Spo2, prefs) { chartColorTarget = it } }
            item { ChartColorRow(1, ChartColorTarget.Temperature, prefs) { chartColorTarget = it } }
            item { ChartColorRow(1, ChartColorTarget.Steps, prefs) { chartColorTarget = it } }
            item { ChartColorRow(1, ChartColorTarget.Systolic, prefs) { chartColorTarget = it } }
            item { ChartColorRow(1, ChartColorTarget.Diastolic, prefs) { chartColorTarget = it } }
            item { ChartColorRow(1, ChartColorTarget.SleepDeep, prefs) { chartColorTarget = it } }
            item { ChartColorRow(1, ChartColorTarget.SleepLight, prefs) { chartColorTarget = it } }
            item { ChartColorRow(1, ChartColorTarget.SleepRem, prefs) { chartColorTarget = it } }
            item { ChartColorRow(1, ChartColorTarget.SleepAwake, prefs) { chartColorTarget = it } }
            item {
                PillRow(
                    level = 1,
                    label = "Restore the validated chart palette",
                    description = "Puts every chart knob above back to what shipped — the colours that pass the checks in the preview.",
                    icon = Icons.Filled.Restore,
                    iconRole = IconRole.DELETE,
                    onClick = { ThemeStore.resetChartsToDefault() },
                )
            }

            }
            // --- Maintenance: what puts the page, or parts of it, back the way it shipped ---
            "maintenance" -> {
            item {
                PillRow(
                    level = 1,
                    label = "Reset section order",
                    description = "Puts the sections of this page, and the chips at its top, back in their built-in order. What is unfolded stays unfolded.",
                    icon = Icons.Filled.Restore,
                    iconRole = IconRole.DELETE,
                    onClick = { ThemeStore.update { it.copy(uiSectionOrder = "") } },
                )
            }
            item {
                PillRow(
                    level = 1,
                    label = "Reset all colours",
                    description = "Every colour back to black and yellow: the foundation colours, and every item that was set apart in its own section. Fonts, sizes, widths and the chart palette stay.",
                    destructive = true,
                    icon = Icons.Filled.Palette,
                    iconRole = IconRole.DELETE,
                    onClick = { confirmResetColours = true },
                )
            }
            item {
                PillRow(
                    level = 1,
                    label = "Reset to black & yellow",
                    description = "Restore the default appearance — black background, yellow text and borders.",
                    destructive = true,
                    icon = Icons.Filled.Restore,
                    iconRole = IconRole.DELETE,
                    // The arrangement of this page is not appearance: the order and the unfolded
                    // sections stay, or the section under the finger would fold away with the tap.
                    onClick = {
                        ThemeStore.update {
                            ThemePrefs.DEFAULT.copy(uiSectionOrder = it.uiSectionOrder, uiSectionsOpen = it.uiSectionsOpen)
                        }
                    },
                )
            }
            }
            // --- One section per group of the role table: every item's own colour ---
            else -> section.roles?.let { group ->
                items(UiRole.entries.filter { it.section == group }, key = { "role-${it.key}" }) { uiRole ->
                    RoleRow(level = 1, uiRole = uiRole, icon = section.icon, prefs = prefs) { roleTarget = it }
                }
            }
            }
        }
    } }

    /** Unfolds [id] and scrolls its heading to the top; the stored order is not touched. */
    fun jumpTo(id: String) {
        val openNow = open + id
        if (id !in open) ThemeStore.update { it.copy(uiSectionsOpen = encodeSections(openNow)) }
        val index = SectionRows(null).also { page(it, openNow) {} }.headers[id] ?: return
        scope.launch {
            // The list still holds the rows of before the unfolding until it is composed again.
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > index }
            listState.animateScrollToItem(index)
        }
    }

    // Sent here to adjust one section (the 文字認識 review window): open it and go to it.
    LaunchedEffect(focusSection) {
        if (focusSection == FOCUS_OCR) jumpTo("ocr")
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { ThemedSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("白い熊 自由作業盤 UI") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            page(SectionRows(this), open) { jumpTo(it) }
        }
    }

    if (showEximPanel) {
        ExportImportPanel(
            onDismiss = { showEximPanel = false },
            onCloseChain = {
                // Success acknowledged: close the whole chain — the panel and the UI page beneath it.
                showEximPanel = false
                onBack()
            },
            onDirChanged = { eximRefresh++ },
        )
    }

    if (confirmResetColours) {
        AlertDialog(
            onDismissRequest = { confirmResetColours = false },
            title = { Text("Reset all colours?") },
            text = {
                Text(
                    "The foundation colours and every colour set apart in a section of its own go back to " +
                        "black and yellow. Fonts, sizes, widths and the chart palette are not touched."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        // One update: the page's own colour table back to its defaults, and no role set apart.
                        ThemeStore.update { p ->
                            ColorTarget.entries.fold(p) { acc, target -> target.set(acc, target.default) }
                                .copy(roleOverrides = emptyMap())
                        }
                        confirmResetColours = false
                    },
                    colors = destructiveButtonColors(),
                ) { Text("Reset") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmResetColours = false }) { Text("Cancel") } },
        )
    }

    roleTarget?.let { target ->
        ColorPickerDialog(
            title = target.label,
            initial = UiRoles.argb(target, prefs),
            onDismiss = { roleTarget = null },
            onDefault = {
                UiRoles.reset(target)
                roleTarget = null
            },
            onConfirm = { argb ->
                UiRoles.set(target, argb)
                roleTarget = null
            },
        )
    }

    chartColorTarget?.let { target ->
        ColorPickerDialog(
            title = target.label,
            initial = target.get(prefs),
            onDismiss = { chartColorTarget = null },
            onDefault = {
                ThemeStore.update { target.set(it, target.get(ThemePrefs.DEFAULT)) }
                chartColorTarget = null
            },
            onConfirm = { argb ->
                ThemeStore.update { target.set(it, argb) }
                chartColorTarget = null
            },
        )
    }

    colorTarget?.let { target ->
        ColorPickerDialog(
            title = target.label,
            initial = target.get(prefs),
            onDismiss = { colorTarget = null },
            onDefault = {
                ThemeStore.update { target.set(it, target.default) }
                colorTarget = null
            },
            onConfirm = { argb ->
                ThemeStore.update { target.set(it, argb) }
                colorTarget = null
            },
        )
    }

    if (showFontPicker) {
        val fonts = remember(fontsRefresh) { ThemeStore.availableFonts() }
        FontPickerDialog(
            current = prefs.fontFileName,
            fonts = fonts,
            onDismiss = { showFontPicker = false },
            onPick = { fileName ->
                ThemeStore.update { it.copy(fontFileName = fileName) }
                showFontPicker = false
            },
            onAddFont = { fontImportLauncher.launch(arrayOf("*/*")) },
            onDelete = { fileName ->
                ThemeStore.deleteFont(fileName)
                fontsRefresh++
            },
        )
    }

    if (showBubbleFontPicker) {
        val fonts = remember(fontsRefresh) { ThemeStore.availableFonts() }
        FontPickerDialog(
            current = prefs.bubbleFontFileName,
            fonts = fonts,
            onDismiss = { showBubbleFontPicker = false },
            onPick = { fileName ->
                ThemeStore.update { it.copy(bubbleFontFileName = fileName) }
                showBubbleFontPicker = false
            },
            onAddFont = { fontImportLauncher.launch(arrayOf("*/*")) },
            onDelete = { fileName ->
                ThemeStore.deleteFont(fileName)
                fontsRefresh++
            },
        )
    }

    if (showPickerFontPicker) {
        val fonts = remember(fontsRefresh) { ThemeStore.availableFonts() }
        FontPickerDialog(
            current = prefs.pickerFontFileName,
            fonts = fonts,
            onDismiss = { showPickerFontPicker = false },
            onPick = { fileName ->
                ThemeStore.update { it.copy(pickerFontFileName = fileName) }
                showPickerFontPicker = false
            },
            onAddFont = { fontImportLauncher.launch(arrayOf("*/*")) },
            onDelete = { fileName ->
                ThemeStore.deleteFont(fileName)
                fontsRefresh++
            },
        )
    }
}

// ---- section / rows -----------------------------------------------------------------------------

// ---- sections: registry, order, folding ----------------------------------------------------------

/**
 * One top-level section of the page. [id] is what the stored order and the stored open set name, so
 * it never changes; [chip] is the short name on the section's index chip. [roles] is set for the
 * sections that are generated from the role table.
 */
private class PageSection(
    val id: String,
    val title: String,
    val chip: String,
    val icon: ImageVector,
    val roles: RoleSection? = null,
)

/** The section that lists one group of the role table, a swatch row per role. */
private fun roleSection(group: RoleSection): PageSection {
    val (chip, icon) = when (group) {
        RoleSection.TEXT -> "Text" to Icons.Filled.Title
        RoleSection.BUTTONS -> "Buttons" to Icons.Filled.TouchApp
        RoleSection.DIALOGS -> "Dialogs" to Icons.Filled.ChatBubble
        RoleSection.SWITCHES -> "Switches" to Icons.Filled.ToggleOn
        RoleSection.MARKS -> "Marks" to Icons.Filled.CheckBox
        RoleSection.MENUS -> "Menus" to Icons.Filled.Menu
        RoleSection.LISTS -> "Lists" to Icons.Filled.Reorder
        RoleSection.PAGE -> "This page" to Icons.Filled.Settings
        RoleSection.BARS -> "Bars" to Icons.Filled.Tab
        RoleSection.SCENES -> "Scenes" to Icons.Filled.Layers
        RoleSection.WIDGETS -> "Widgets" to Icons.Filled.Widgets
        RoleSection.PROGRESS -> "Progress" to Icons.Filled.HourglassEmpty
        RoleSection.CHARTS -> "健康 chrome" to Icons.Filled.BarChart
        RoleSection.ISLANDS -> "言語島" to Icons.Filled.Translate
        RoleSection.SCREENS -> "Screens" to Icons.Filled.Smartphone
    }
    return PageSection("role_" + group.name.lowercase(), group.title, chip, icon, group)
}

/** Every section, in the built-in order: the foundation colours first, then each item's own. */
private val PAGE_SECTIONS: List<PageSection> = listOf(
    PageSection("exim", "Export / Import", "Export", Icons.Filled.ImportExport),
    PageSection("colors", "Colors", "Colors", Icons.Filled.Palette),
    PageSection("borders", "Borders", "Borders", Icons.Filled.BorderOuter),
) + RoleSection.entries.map(::roleSection) + listOf(
    PageSection("typography", "Typography", "Fonts", Icons.Filled.TextFields),
    PageSection("flash", "Flash / toast", "Flash", Icons.Filled.Notifications),
    PageSection("editor", "Editor", "Editor", Icons.Filled.Edit),
    PageSection("tasklist", "Task list", "Task list", Icons.Filled.Checklist),
    PageSection("tasks", "Tasks", "Tasks", Icons.Filled.Task),
    PageSection("variables", "Variables", "Variables", Icons.Filled.Code),
    PageSection("monitor", "Monitor", "Monitor", Icons.Filled.Visibility),
    PageSection("panelbars", "Panel bars", "Panels", Icons.Filled.Tune),
    PageSection("bubbles", "Freeze bubbles", "Freeze", Icons.Filled.AcUnit),
    PageSection("flashbubbles", "Flash bubbles (通知明滅)", "通知明滅", Icons.Filled.FlashOn),
    PageSection("picker", "Shortcut picker", "Picker", Icons.Filled.Apps),
    PageSection("importreview", "Import review", "Import", Icons.Filled.Download),
    PageSection("ocr", "「文字認識」 OCR", "OCR", Icons.Filled.CenterFocusStrong),
    PageSection("charts", "「健康」 charts", "健康", Icons.Filled.BarChart),
    PageSection("maintenance", "Maintenance", "Reset", Icons.Filled.Build),
)

/** The sections as [stored] orders them: unknown ids are ignored, missing ones follow in the built-in order. */
private fun sectionOrder(stored: String): List<PageSection> {
    val byId = PAGE_SECTIONS.associateBy { it.id }
    val named = stored.split(',').mapNotNull { byId[it.trim()] }.distinct()
    return named + PAGE_SECTIONS.filter { it !in named }
}

/** The ids of the unfolded sections. Nothing stored means everything folded. */
private fun openSections(stored: String): Set<String> =
    stored.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

private fun encodeSections(ids: Set<String>): String =
    PAGE_SECTIONS.filter { it.id in ids }.joinToString(",") { it.id }

/**
 * The page's rows, counted as they are handed to the list.
 *
 * A chip has to scroll to its section's heading, and a lazy list scrolls to a row NUMBER — which
 * depends on which sections above are unfolded and how many rows each has. So the page is written
 * against this instead of the list itself: it passes every row on to [list] and remembers where each
 * heading landed. With no [list] it only counts, which is how a tap on a chip learns the number for
 * the open set it is about to store.
 */
private class SectionRows(private val list: LazyListScope?) {
    private var count = 0

    /** Section id → the row number of its heading. */
    val headers = HashMap<String, Int>()

    fun header(id: String, content: @Composable LazyItemScope.() -> Unit) {
        headers[id] = count
        item(key = "section-$id", content = content)
    }

    fun item(key: Any? = null, content: @Composable LazyItemScope.() -> Unit) {
        count++
        list?.item(key = key, content = content)
    }

    fun <T> items(items: List<T>, key: ((T) -> Any)? = null, itemContent: @Composable LazyItemScope.(T) -> Unit) {
        count += items.size
        list?.items(items, key = key, itemContent = itemContent)
    }
}

private const val CHIPS_PER_ROW = 4
private val CHIP_SHAPE = RoundedCornerShape(percent = 50)

/**
 * The index under the page title: one small outlined chip per section, about four to a row.
 *
 * A tap unfolds the section and goes to it. A long-press lifts the chip, and from then on the chips
 * reorder LIVE under the finger — the dragged one moves to the slot of whichever chip the finger is
 * over — and the sections follow on drop ([onReorder]). Reordering only on drop was tried in the 地図
 * chat and turned down.
 *
 * The drag is detected on the grid, not on a chip: a chip changes place while it is dragged, and a
 * gesture that lived on it would see its own coordinates move under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipIndex(order: List<PageSection>, onJump: (String) -> Unit, onReorder: (List<String>) -> Unit) {
    val latestOrder by rememberUpdatedState(order)
    val latestReorder by rememberUpdatedState(onReorder)
    // The order while a chip is in the air; kept after the drop until the stored order has caught up,
    // so that the chips do not flick back for a frame.
    var live by remember { mutableStateOf<List<PageSection>?>(null) }
    var dragged by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(order) { if (dragged == null) live = null }
    // Where each chip and the grid stand in the window. Plain holders: only the gesture reads them.
    val bounds = remember { HashMap<String, Rect>() }
    val origin = remember { arrayOf(Offset.Zero) }
    val shown = live ?: order
    val frame = role(UiRole.PAGE_CHIP_FRAME)
    val ink = role(UiRole.PAGE_CHIP_LABEL)

    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp)) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { origin[0] = it.positionInRoot() }
                .pointerInput(Unit) {
                    // The chip the finger was over at the last move. A move is made once per chip
                    // entered, not once per event: a wide chip dragged over a narrow one would
                    // otherwise land under the finger again and swap back, to and fro.
                    var lastOver: String? = null
                    fun chipAt(position: Offset): String? {
                        val inRoot = position + origin[0]
                        return bounds.entries.firstOrNull { it.value.contains(inRoot) }?.key
                    }
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            val id = chipAt(at)
                            lastOver = id
                            live = if (id != null) latestOrder else null
                            dragged = id
                        },
                        onDragEnd = {
                            val dropped = live
                            if (dragged != null && dropped != null) latestReorder(dropped.map { it.id })
                            dragged = null
                        },
                        onDragCancel = {
                            live = null
                            dragged = null
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val id = dragged
                            val now = live
                            if (id != null && now != null) {
                                val over = chipAt(change.position)
                                if (over != lastOver) {
                                    lastOver = over
                                    val from = now.indexOfFirst { it.id == id }
                                    val to = now.indexOfFirst { it.id == over }
                                    if (over != id && from >= 0 && to >= 0) {
                                        live = now.toMutableList().apply { add(to, removeAt(from)) }
                                    }
                                }
                            }
                        },
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = CHIPS_PER_ROW,
        ) {
            shown.forEach { section ->
                key(section.id) {
                    val lifted = dragged == section.id
                    Box(
                        Modifier
                            .weight(1f)
                            .onGloballyPositioned { bounds[section.id] = it.boundsInRoot() }
                            .clip(CHIP_SHAPE)
                            // Never filled: the chip in the air is told apart by its frame alone.
                            .border(if (lifted) 3.dp else 1.dp, frame, CHIP_SHAPE)
                            // A long-press that ends without a move still ends in a release on the
                            // chip; that release belongs to the drag, not to a tap.
                            .clickable { if (dragged == null) onJump(section.id) }
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            section.chip,
                            color = ink,
                            fontSize = 12.sp,
                            fontWeight = if (lifted) FontWeight.Bold else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // The last line is filled up with empty slots, or its chips would stretch across it.
            repeat((CHIPS_PER_ROW - shown.size % CHIPS_PER_ROW) % CHIPS_PER_ROW) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * The heading of a folding section: the thin divider above it (which stays, folded or open), the
 * section's icon, the bold title underlined only as wide as its text, and › when folded or ⌄ when
 * open at the row's right. The whole line folds and unfolds.
 */
@Composable
private fun SectionHeader(section: PageSection, open: Boolean, onToggle: () -> Unit) {
    val ink = role(UiRole.HEADING)
    val titleStyle = TextStyle(fontSize = 20.sp)
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.padding(top = 6.dp),
            thickness = 1.dp,
            color = role(UiRole.PAGE_DIVIDER),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Sized by the heading, as a row's icon is sized by its label.
            RoleIcon(section.icon, IconRole.VIEW, Modifier.padding(end = ROW_ICON_GAP).size(rowIconSize(titleStyle)))
            Box(Modifier.weight(1f)) {
                Column(
                    // Max, not Min. The rule under a heading should be exactly as wide as the heading, and
                    // for a Latin title Min happens to give that. For a CJK title it does not: every
                    // character is a legal break point, so the MINIMUM intrinsic width is about one glyph —
                    // and with softWrap off the title is then clipped to 「文 rather than wrapped. It hit
                    // 「健康」 charts too, quietly, until a second Japanese section made it obvious.
                    Modifier.width(IntrinsicSize.Max),
                ) {
                    Text(
                        section.title,
                        fontSize = titleStyle.fontSize,
                        fontWeight = FontWeight.Bold,
                        color = ink,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(thickness = 2.dp, color = ink)
                }
            }
            Text(
                if (open) "⌄" else "›",
                modifier = Modifier.padding(start = 8.dp),
                fontSize = titleStyle.fontSize,
                fontWeight = FontWeight.Bold,
                color = ink,
            )
        }
    }
}

// ---- row furniture: rule, icon, label, note ------------------------------------------------------

private val RULE_WIDTH = 2.dp

/** From a row's left edge to its icon: the 2 dp rule of an explained row, and the 10 dp after it. */
private val RULE_GUTTER = 12.dp
private val ROW_ICON_GAP = 8.dp
private val ROW_ICON_FLOOR = 18.dp

/**
 * A row icon's side: 1.25 × its label's text size, never under 18 dp (settled 2026-11-22). The text
 * size is settable on this very page, so a fixed size would be right for one setting only.
 */
@Composable
private fun rowIconSize(labelStyle: TextStyle): Dp {
    val fontSize = if (labelStyle.fontSize.isSp) labelStyle.fontSize else 16.sp
    return maxOf(ROW_ICON_FLOOR, with(LocalDensity.current) { (fontSize * 1.25f).toDp() })
}

/** How tall the first line of a label in [labelStyle] is — what its icon is centred on. */
@Composable
private fun firstLineHeight(labelStyle: TextStyle): Dp {
    val fontSize = if (labelStyle.fontSize.isSp) labelStyle.fontSize else 16.sp
    val line = if (labelStyle.lineHeight.isSp) labelStyle.lineHeight else fontSize * 1.2f
    return with(LocalDensity.current) { line.toDp() }
}

/**
 * The icon at a row's left, coloured by what the row does. It is centred on the label's FIRST line,
 * not on the row — a row with a description is tall, and an icon centred on all of it drifts away
 * from the label it belongs to. [slotExtra] is what the first line carries besides its text (the
 * padding of a pill).
 */
@Composable
private fun RowIcon(icon: ImageVector, iconRole: IconRole, labelStyle: TextStyle, slotExtra: Dp = 0.dp) {
    val side = rowIconSize(labelStyle)
    Box(
        Modifier
            .padding(end = ROW_ICON_GAP)
            .size(width = side, height = maxOf(side, firstLineHeight(labelStyle) + slotExtra)),
        contentAlignment = Alignment.Center,
    ) {
        RoleIcon(icon, iconRole, Modifier.size(side))
    }
}

/**
 * What every row of the page is built on: the gutter (the dim-gold rule of an explained row, or the
 * same width left empty, so that all icons stand in one column), the row's icon, then [content].
 * No minimum height, and 2 dp above and below — a row is as tall as what is in it.
 */
@Composable
private fun IconRow(
    level: Int,
    icon: ImageVector,
    iconRole: IconRole,
    ruled: Boolean = false,
    onClick: (() -> Unit)? = null,
    verticalPadding: Dp = 2.dp,
    labelStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    iconSlotExtra: Dp = 0.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val rule = role(UiRole.DIM_GOLD)
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
    Row(
        modifier = clickable
            .padding(start = rowStartPadding(level), end = 16.dp, top = verticalPadding, bottom = verticalPadding)
            // Drawn behind rather than laid out, so that it is as tall as the row without the row
            // having to be measured twice.
            .drawBehind { if (ruled) drawRect(rule, size = Size(RULE_WIDTH.toPx(), size.height)) }
            .padding(start = RULE_GUTTER),
        verticalAlignment = Alignment.Top,
    ) {
        RowIcon(icon, iconRole, labelStyle, iconSlotExtra)
        content()
    }
}

/** A setting's label: bold, in its own role. */
@Composable
private fun RowLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        color = role(UiRole.PAGE_LABEL),
    )
}

/** The explanation under a label or a pill: dim gold, 13 sp. [warn] is a note that asks for something. */
@Composable
private fun RowNote(text: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        fontSize = 13.sp,
        color = role(if (warn) UiRole.WARNING else UiRole.DIM_GOLD),
    )
}

/** The value a setting stands at, shown beside or under its label. */
@Composable
private fun RowValue(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    fontFamily: FontFamily? = null,
) {
    Text(
        text,
        modifier = modifier,
        style = style,
        fontFamily = fontFamily,
        color = role(UiRole.PAGE_VALUE),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The round colour sample at a swatch row's right. */
@Composable
private fun Swatch(argb: Int) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

/** A small round outlined button that carries one glyph — the ↺ beside a swatch. */
@Composable
private fun GlyphButton(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .border(1.25.dp, role(UiRole.BUTTON_FRAME), CircleShape)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = role(UiRole.BUTTON_LABEL), fontSize = 16.sp)
    }
}

// ---- rows ---------------------------------------------------------------------------------------

/** A plain control row: label and value side by side, the control (or the value that opens a picker) at the right. */
@Composable
private fun RowScaffold(
    level: Int,
    onClick: (() -> Unit)? = null,
    icon: ImageVector = Icons.Filled.Tune,
    iconRole: IconRole = IconRole.VIEW,
    ruled: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    IconRow(level, icon, iconRole, ruled = ruled, onClick = onClick) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun ColorRow(
    level: Int,
    label: String,
    value: Int,
    target: ColorTarget,
    icon: ImageVector = Icons.Filled.Palette,
    iconRole: IconRole = IconRole.VIEW,
    onPick: (ColorTarget) -> Unit,
) {
    RowScaffold(level, onClick = { onPick(target) }, icon = icon, iconRole = iconRole) {
        Column(Modifier.weight(1f)) {
            RowLabel(label)
            RowValue(
                if (value == target.default) "Default - ${hex6(value)}" else hex6(value),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Swatch(value)
    }
}

/** Which icon colour a role's own row wears: the menu icon roles show themselves, the rest are colour rows. */
private fun roleRowIconRole(uiRole: UiRole): IconRole = when (uiRole) {
    UiRole.ICON_CONNECT -> IconRole.CONNECT
    UiRole.ICON_MESSAGE -> IconRole.WRITE
    UiRole.ICON_CONTACT -> IconRole.PERSON
    UiRole.ICON_SHARE -> IconRole.SHARE
    UiRole.ICON_BLOCK -> IconRole.BLOCK
    UiRole.DELETE -> IconRole.DELETE
    else -> IconRole.VIEW
}

/**
 * The swatch row of one role of the role table — the same row for all of them, generated from the
 * table, so that a role added there shows up here without a line being written for it. A tap opens
 * the colour picker; the ↺ appears once the role is set apart and puts it back to following.
 */
@Composable
private fun RoleRow(level: Int, uiRole: UiRole, icon: ImageVector, prefs: ThemePrefs, onPick: (UiRole) -> Unit) {
    RowScaffold(level, onClick = { onPick(uiRole) }, icon = icon, iconRole = roleRowIconRole(uiRole), ruled = true) {
        Column(Modifier.weight(1f)) {
            RowLabel(uiRole.label)
            RowNote(uiRole.note)
        }
        if (uiRole.key in prefs.roleOverrides) {
            GlyphButton("↺", "Reset ${uiRole.label}") { UiRoles.reset(uiRole) }
        }
        Swatch(UiRoles.argb(uiRole, prefs))
    }
}

@Composable
private fun SliderRow(
    level: Int,
    label: String,
    value: Int,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    icon: ImageVector = Icons.Filled.Tune,
    iconRole: IconRole = IconRole.VIEW,
    onChange: (Int) -> Unit,
) {
    IconRow(level, icon, iconRole) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RowLabel(label, Modifier.weight(1f))
                RowValue(valueText)
            }
            // No 48 dp touch-target floor around the slider: the row is as tall as the slider itself.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Slider(
                    value = value.toFloat(),
                    onValueChange = { onChange(it.roundToInt()) },
                    valueRange = range,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FontRow(level: Int, fileName: String, onClick: () -> Unit) {
    RowScaffold(level, onClick = onClick, icon = Icons.Filled.TextFields, iconRole = IconRole.WRITE) {
        RowLabel("Font", Modifier.weight(1f))
        RowValue(
            ThemeStore.displayNameFor(fileName),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = ThemeStore.fontFamily(fileName) ?: FontFamily.Default,
        )
    }
}

@Composable
private fun WeightRow(level: Int, weight: Int, onPick: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        RowScaffold(level, onClick = { expanded = true }, icon = Icons.Filled.FormatBold, iconRole = IconRole.WRITE) {
            RowLabel("Weight", Modifier.weight(1f))
            RowValue(weightLabel(weight), style = MaterialTheme.typography.bodyLarge)
        }
        ThemedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            WEIGHT_OPTIONS.forEach { (label, w) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onPick(w)
                        expanded = false
                    },
                    trailingIcon = if (w == weight) ({ Text("✓") }) else null,
                    choice = true,
                )
            }
        }
    }
}

/** Flash-bubble gesture behavior options: stored value → display label. */
private val FLASH_BEHAVIOR_OPTIONS = listOf(
    "open_kill" to "Open app + kill flash",
    "kill" to "Kill flash only",
    "open" to "Open app only",
    "dismiss" to "Dismiss icon only",
)

@Composable
private fun FlashBehaviorRow(level: Int, label: String, value: String, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        RowScaffold(level, onClick = { expanded = true }, icon = Icons.Filled.TouchApp, iconRole = IconRole.CONNECT) {
            RowLabel(label, Modifier.weight(1f))
            RowValue(
                FLASH_BEHAVIOR_OPTIONS.firstOrNull { it.first == value }?.second ?: value,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        ThemedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FLASH_BEHAVIOR_OPTIONS.forEach { (v, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onPick(v)
                        expanded = false
                    },
                    trailingIcon = if (v == value) ({ Text("✓") }) else null,
                    choice = true,
                )
            }
        }
    }
}

/** A workspace task name the flash-bubble layer runs (kill / kill-all), edited inline. */
@Composable
private fun FlashTaskRow(level: Int, label: String, value: String, onChange: (String) -> Unit) {
    // The field is 56 dp tall and its text is its one line, so the icon is centred on the field.
    IconRow(level, Icons.Filled.Block, IconRole.BLOCK, iconSlotExtra = 32.dp) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SampleRow(level: Int) {
    PreviewBlock(level, caption = "Live sample", spacing = 0.dp) {
        Text(
            "AaIiMmQq 0123  白い熊 自由作業盤  áÁčČ",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Renders a sample flash exactly as the real one, so changes are visible without running a task. */
@Composable
private fun FlashPreview(level: Int, prefs: ThemePrefs) {
    PreviewBlock(level, spacing = 0.dp) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(prefs.flashCornerRadiusDp.dp),
                color = Color(prefs.flashBackground),
                contentColor = Color(prefs.flashText),
                border = if (prefs.flashBorderWidthDp > 0) {
                    BorderStroke(prefs.flashBorderWidthDp.dp, Color(prefs.flashBorder))
                } else null,
            ) {
                Text(
                    text = "Main succeeded (8 ms)",
                    color = Color(prefs.flashText),
                    fontSize = prefs.flashTextSizeSp.sp,
                    fontWeight = FontWeight(prefs.flashFontWeight),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** Global task-icon size: a slider plus a live preview mirroring how an icon sits on a task card. */
@Composable
private fun TaskIconSizeRow(level: Int, prefs: ThemePrefs) {
    val context = LocalContext.current
    val sample by produceState<ImageBitmap?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap()
            }.getOrNull()
        }
    }
    SliderRow(
        level = level, label = "Task icon size",
        value = prefs.taskIconSizeDp, valueText = "${prefs.taskIconSizeDp} dp",
        range = ThemePrefs.TASK_ICON_MIN.toFloat()..ThemePrefs.TASK_ICON_MAX.toFloat(),
        onChange = { v -> ThemeStore.update { it.copy(taskIconSizeDp = v) } },
    )
    PreviewBlock(level) {
        // Fixed-height box so the row doesn't jump as the icon grows/shrinks.
        Box(Modifier.fillMaxWidth().height((ThemePrefs.TASK_ICON_MAX + 8).dp), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val bmp = sample
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        modifier = Modifier.size(prefs.taskIconSizeDp.dp).clip(RoundedCornerShape(6.dp)),
                    )
                }
                Text("フラッシュ 林檎", style = MaterialTheme.typography.titleLarge, maxLines = 1)
            }
        }
    }
}

/** Live preview of a freeze bubble: the app's own icon + ❄ badge + label, reflecting every bubble setting. */
@Composable
private fun FreezeBubblePreview(level: Int, prefs: ThemePrefs) {
    val context = LocalContext.current
    val sample by produceState<ImageBitmap?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap()
            }.getOrNull()
        }
    }
    val font = prefs.bubbleFontFileName.ifBlank { prefs.fontFileName }
    val family = ThemeStore.fontFamily(font)
    val label = runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }.getOrDefault("App")
    val badgeDp = (prefs.bubbleIconSizeDp * 0.4f).coerceIn(14f, 26f)

    PreviewBlock(level) {
        Box(Modifier.fillMaxWidth().height((ThemePrefs.BUBBLE_ICON_MAX + 44).dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box {
                    val bmp = sample
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            modifier = Modifier.size(prefs.bubbleIconSizeDp.dp).clip(RoundedCornerShape(prefs.bubbleIconCornerDp.dp)),
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(badgeDp.dp)
                            .clip(CircleShape)
                            .background(Color(prefs.background))
                            .border(1.dp, Color(prefs.accent), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("❄", color = Color(prefs.accent), fontSize = (badgeDp * 0.6f).sp)
                    }
                }
                Text(
                    label,
                    color = Color(prefs.accent),
                    fontSize = prefs.bubbleLabelSizeSp.sp,
                    fontWeight = FontWeight(prefs.bubbleLabelWeight.coerceIn(100, 900)),
                    fontFamily = family ?: FontFamily.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Live preview of the Review-import screen: header + stats, a warning, a section title, a normal item
 *  row and a CONFLICT row (name in the conflict colour + a yellow-bordered "⚠ Overwrite ▾" pill). */
@Composable
private fun ImportReviewPreview(level: Int, prefs: ThemePrefs) {
    val conflictColor = Color(prefs.importConflictColor)
    PreviewBlock(level) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Review import",
                    fontSize = prefs.importHeaderSp.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "2 tasks · 1 profile — 1 already exist",
                    fontSize = prefs.importHeaderSp.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "⚠ Warnings appear here.",
                    fontSize = prefs.importWarnSp.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Tasks (2)",
                    fontSize = prefs.importSectionSp.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = prefs.importRowPadDp.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "New task",
                        fontSize = prefs.importItemSp.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text("New", fontSize = prefs.importItemSp.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = prefs.importRowPadDp.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Existing task",
                        fontSize = prefs.importItemSp.sp,
                        color = conflictColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text("⚠", fontSize = prefs.importItemSp.sp, color = conflictColor)
                        Text("Overwrite", fontSize = prefs.importItemSp.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("▾", fontSize = prefs.importItemSp.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/**
 * A row that DOES something — an outlined pill that hugs its label, with its explanation beneath
 * (the black-yellow theme's settings-page rule, 白い熊 2026-11-07; hugging since 2026-11-09: a short
 * action blown up to the full width reads as a banner, not a button). A plain row that runs an
 * action reads as a heading or a note; the pill says “press me”. [opens] adds the › of a row that
 * leads somewhere (a panel, a picker, the browser); an in-place action (Reset) has none. [main] is
 * the section's one main action: thick frame, bold label. [destructive] lends it the Delete role.
 * Only the pill is the touch target, never the explanation.
 */
@Composable
private fun PillRow(
    level: Int,
    label: String,
    description: String,
    main: Boolean = false,
    opens: Boolean = false,
    destructive: Boolean = false,
    /** The explanation is a warning (nothing chosen yet), not a description. */
    warn: Boolean = false,
    icon: ImageVector = Icons.Filled.PlayArrow,
    iconRole: IconRole = IconRole.CONNECT,
    onClick: () -> Unit,
) {
    // About 3 dp around the pill; the icon is centred on the pill, whose label line carries 9 dp
    // of padding above and below.
    IconRow(
        level, icon, iconRole,
        ruled = description.isNotEmpty(),
        verticalPadding = 3.dp,
        iconSlotExtra = PILL_TEXT_VPAD * 2,
    ) {
        Column(Modifier.weight(1f)) {
            HuggingPill(label = label, main = main, opens = opens, destructive = destructive, onClick = onClick)
            if (description.isNotEmpty()) {
                // Lined up with the label inside the pill, not with the pill's edge.
                RowNote(description, Modifier.padding(start = PILL_TEXT_INSET, top = 2.dp), warn = warn)
            }
        }
    }
}

/** The pill of a [PillRow]: as wide as its label, never stretched across the line. */
@Composable
private fun HuggingPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    main: Boolean = false,
    opens: Boolean = false,
    destructive: Boolean = false,
) {
    val inner = PaddingValues(horizontal = PILL_TEXT_INSET, vertical = PILL_TEXT_VPAD)
    val colors = if (destructive) destructiveButtonColors() else null
    val content: @Composable RowScope.() -> Unit = {
        Text(
            label,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyLarge,
            // Said again here: the style names a weight of its own, which would undo the pill's bold.
            fontWeight = if (main) FontWeight.Bold else null,
        )
        if (opens) {
            Text(
                "›",
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    // No 48 dp touch-target floor: it would add space above and below the pill, and the pill's own
    // padding already makes it easy to hit.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        if (main) {
            Button(onClick = onClick, modifier = modifier, colors = colors, contentPadding = inner, content = content)
        } else {
            OutlinedButton(onClick = onClick, modifier = modifier, colors = colors, contentPadding = inner, content = content)
        }
    }
}

/** Inside a [PillRow]: where the label starts, so the explanation beneath lines up with it. */
private val PILL_TEXT_INSET = 16.dp
private val PILL_TEXT_VPAD = 9.dp

@Composable
private fun SwitchRow(
    level: Int,
    label: String,
    description: String,
    checked: Boolean,
    icon: ImageVector = Icons.Filled.ToggleOn,
    iconRole: IconRole = IconRole.CONNECT,
    onCheckedChange: (Boolean) -> Unit,
) {
    RowScaffold(level, onClick = { onCheckedChange(!checked) }, icon = icon, iconRole = iconRole, ruled = true) {
        Column(Modifier.weight(1f)) {
            RowLabel(label)
            RowNote(description)
        }
        // No 48 dp touch-target floor: the whole row toggles, so the switch needs no padding of its own.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            ThemedSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/**
 * A preview or a sample, as a row of its own: the caption with the eye beside it, and what is shown
 * beneath. The icon is sized by the caption, as every row's icon is sized by its label.
 */
@Composable
private fun PreviewBlock(
    level: Int,
    caption: String = "Live preview",
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val captionStyle = MaterialTheme.typography.labelMedium
    IconRow(level, Icons.Filled.Visibility, IconRole.VIEW, labelStyle = captionStyle) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing)) {
            Text(caption, style = captionStyle, color = role(UiRole.DIM_GOLD))
            content()
        }
    }
}

// ---- dialogs ------------------------------------------------------------------------------------

@Composable
private fun ColorPickerDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    /** Puts the edited colour back to its default (a role: back to following) and closes; null where there is none. */
    onDefault: (() -> Unit)? = null,
    onConfirm: (Int) -> Unit,
) {
    var r by remember { mutableIntStateOf((initial ushr 16) and 0xFF) }
    var g by remember { mutableIntStateOf((initial ushr 8) and 0xFF) }
    var b by remember { mutableIntStateOf(initial and 0xFF) }
    var hexText by remember { mutableStateOf(hex6(initial)) }
    val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    fun syncHex() { hexText = hex6((r shl 16) or (g shl 8) or b) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(argb))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
                )
                ChannelSlider("R", r) { r = it; syncHex() }
                ChannelSlider("G", g) { g = it; syncHex() }
                ChannelSlider("B", b) { b = it; syncHex() }
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { input ->
                        val cleaned = input.removePrefix("#").uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                        hexText = "#$cleaned"
                        if (cleaned.length == 6) {
                            val c = cleaned.toLong(16).toInt()
                            r = (c ushr 16) and 0xFF
                            g = (c ushr 8) and 0xFF
                            b = c and 0xFF
                        }
                    },
                    label = { Text("Hex") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDefault != null) {
                    OutlinedButton(onClick = onDefault) { Text("Default") }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(argb) }) { Text("Apply") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(16.dp), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f),
        )
        Text(value.toString(), Modifier.width(32.dp), style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun FontPickerDialog(
    current: String,
    fonts: List<FontOption>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onAddFont: () -> Unit,
    onDelete: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Font") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                fonts.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option.fileName) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RoleIcon(Icons.Filled.TextFields, IconRole.WRITE, Modifier.size(20.dp))
                        Text(
                            option.displayName,
                            modifier = Modifier.weight(1f),
                            // Render every option in its OWN glyphs.
                            fontFamily = ThemeStore.fontFamily(option.fileName) ?: FontFamily.Default,
                            style = MaterialTheme.typography.bodyLarge,
                            color = role(UiRole.LIST_LABEL),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (option.fileName == current) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = role(UiRole.LIST_SELECTED))
                        }
                        if (option.fileName.isNotEmpty() && option.fileName != ThemeStore.MONOSPACE) {
                            IconButton(onClick = { onDelete(option.fileName) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete font", tint = role(UiRole.DELETE))
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp), color = role(UiRole.LIST_DIVIDER))
                // An action, not an entry of the list: a pill, with the … and the › of what opens a picker.
                OutlinedButton(onClick = onAddFont) {
                    RoleIcon(Icons.Filled.Add, IconRole.WRITE, Modifier.size(18.dp))
                    Text("Add font…", Modifier.padding(start = 8.dp))
                    Text("›", Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
    )
}

// ---- model helpers ------------------------------------------------------------------------------

/**
 * A paragraph under a slider, for a knob whose effect is not guessable from its name.
 *
 * The 文字認識 detection knobs each have a failure mode that looks like a bad model rather than a bad
 * setting — a decapitated crop reads as "the OCR can't do diacritics" — so each one says what it does
 * and which way it goes wrong.
 */
@Composable
private fun SettingNote(text: String, warn: Boolean = false) {
    // A loose note belongs to no single row, so it has no rule; it stands at the rows' text edge.
    val textEdge = RULE_GUTTER + rowIconSize(MaterialTheme.typography.bodyLarge) + ROW_ICON_GAP
    RowNote(
        text,
        Modifier.padding(start = rowStartPadding(1) + textEdge, end = 16.dp, top = 2.dp, bottom = 2.dp),
        warn = warn,
    )
}

/**
 * A sub-heading inside a section.
 *
 * The chart section has thirty-odd rows, which is more than one flat list can be read as. It groups
 * into sizes / marks / ink / what-gets-drawn / colours, and those groups deserve labels without each
 * becoming a full [SectionHeader] with its own rule above it.
 */
@Composable
private fun SubHeader(level: Int, title: String) {
    // Bold and underlined, in the heading's role. The space above stays: it is what marks where
    // the group before it ends.
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        textDecoration = TextDecoration.Underline,
        color = role(UiRole.HEADING),
        modifier = Modifier.padding(start = rowStartPadding(level), end = 16.dp, top = 14.dp, bottom = 2.dp),
    )
}

/**
 * The live 「健康」 chart preview, plus the colour verdict beneath it.
 *
 * The preview runs the app's real chart renderers over made-up data, so every slider above shows its
 * effect immediately and none of it depends on the band having ever been synced. The verdict runs the
 * colour-blindness and contrast arithmetic on whatever is currently picked — advisory, never a block,
 * with the one-tap restore at the bottom of the section.
 */
@Composable
private fun ChartLivePreview(level: Int, prefs: ThemePrefs) {
    PreviewBlock(level, spacing = 10.dp) {
        Surface(color = Color(ThemePrefs.NEAR_BLACK), shape = RoundedCornerShape(12.dp)) {
            ChartStylePreview(prefs, Modifier.padding(10.dp))
        }
    }
    PreviewBlock(level, caption = "Colour check", spacing = 10.dp) {
        ChartPaletteVerdict(prefs)
    }
}

/** How a line joins its samples. STEP is the honest one for a value read at a fixed cadence. */
private val CHART_CURVE_OPTIONS = listOf(
    "PCHIP" to "Smooth curve (no overshoot)",
    "LINEAR" to "Straight lines",
    "STEP" to "Held until the next reading",
)

@Composable
private fun ChartCurveRow(level: Int, value: String, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        RowScaffold(level, onClick = { expanded = true }, icon = Icons.Filled.Timeline) {
            RowLabel("Line shape", Modifier.weight(1f))
            RowValue(
                CHART_CURVE_OPTIONS.firstOrNull { it.first == value }?.second ?: value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ThemedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CHART_CURVE_OPTIONS.forEach { (v, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onPick(v); expanded = false },
                    trailingIcon = if (v == value) ({ Text("✓") }) else null,
                    choice = true,
                )
            }
        }
    }
}

@Composable
private fun ChartColorRow(
    level: Int,
    target: ChartColorTarget,
    prefs: ThemePrefs,
    onPick: (ChartColorTarget) -> Unit,
) {
    val value = target.get(prefs)
    RowScaffold(level, onClick = { onPick(target) }, icon = Icons.Filled.Palette) {
        Column(Modifier.weight(1f)) {
            RowLabel(target.label)
            RowValue(
                if (value == target.get(ThemePrefs.DEFAULT)) "Default - ${hex6(value)}" else hex6(value),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Swatch(value)
    }
}

/**
 * The chart colours, as lambdas rather than another arm on [ColorTarget]'s two `when` blocks.
 *
 * Eleven more entries there would have meant thirty-three more lines across three places that must
 * stay in step; here each slot states its getter and setter once, next to its own name.
 */
private enum class ChartColorTarget(
    val label: String,
    val get: (ThemePrefs) -> Int,
    val set: (ThemePrefs, Int) -> ThemePrefs,
) {
    Grid("Grid lines", { it.chartGridColor }, { p, v -> p.copy(chartGridColor = v) }),
    AxisText("Axis + caption ink", { it.chartAxisTextColor }, { p, v -> p.copy(chartAxisTextColor = v) }),
    HeartRate("心拍 — heart rate", { it.chartColorHeartRate }, { p, v -> p.copy(chartColorHeartRate = v) }),
    BandState("バンド状態指数 — band state index", { it.chartColorBandState }, { p, v -> p.copy(chartColorBandState = v) }),
    Spo2("血中酸素 — blood oxygen", { it.chartColorSpo2 }, { p, v -> p.copy(chartColorSpo2 = v) }),
    Temperature("体温 — temperature", { it.chartColorTemperature }, { p, v -> p.copy(chartColorTemperature = v) }),
    Steps("歩数 — steps", { it.chartColorSteps }, { p, v -> p.copy(chartColorSteps = v) }),
    RestingHr("安静時心拍 — resting heart rate (Huawei)", { it.chartColorRestingHr }, { p, v -> p.copy(chartColorRestingHr = v) }),
    Systolic("収縮期 — systolic", { it.chartColorSystolic }, { p, v -> p.copy(chartColorSystolic = v) }),
    Diastolic("拡張期 — diastolic", { it.chartColorDiastolic }, { p, v -> p.copy(chartColorDiastolic = v) }),
    SleepDeep("睡眠 深い — deep", { it.chartColorSleepDeep }, { p, v -> p.copy(chartColorSleepDeep = v) }),
    SleepLight("睡眠 浅い — light", { it.chartColorSleepLight }, { p, v -> p.copy(chartColorSleepLight = v) }),
    SleepRem("睡眠 REM", { it.chartColorSleepRem }, { p, v -> p.copy(chartColorSleepRem = v) }),
    SleepAwake("睡眠 覚醒 — awake", { it.chartColorSleepAwake }, { p, v -> p.copy(chartColorSleepAwake = v) }),
}

private enum class ColorTarget(val label: String, val default: Int) {
    Background("Background", ThemePrefs.DEFAULT.background),
    Text("Text", ThemePrefs.DEFAULT.text),
    TextSecondary("Secondary text", ThemePrefs.DEFAULT.textSecondary),
    Accent("Accent", ThemePrefs.DEFAULT.accent),
    Surface("Surface", ThemePrefs.DEFAULT.surface),
    Border("Border", ThemePrefs.DEFAULT.border),
    FlashBackground("Flash background", ThemePrefs.DEFAULT.flashBackground),
    FlashText("Flash text", ThemePrefs.DEFAULT.flashText),
    FlashBorder("Flash border", ThemePrefs.DEFAULT.flashBorder),
    ImportConflict("Conflict colour", ThemePrefs.DEFAULT.importConflictColor),
    OvalBarBorder("Oval bar border", ThemePrefs.DEFAULT.ovalBarBorderColor),
    GroupHeader("Group header colour", ThemePrefs.DEFAULT.groupHeaderColor),
    GroupHeaderBorder("Group header border", ThemePrefs.DEFAULT.groupHeaderBorderColor),
    ActionName("Action name colour", ThemePrefs.DEFAULT.actionNameColor),
    ActionValue("Action value colour", ThemePrefs.DEFAULT.actionValueColor),
    ActionLabelFrame("Action label frame", ThemePrefs.DEFAULT.actionLabelFrameColor),
    ActionBorder("Action border", ThemePrefs.DEFAULT.actionBorderColor),
    Selection("Selection highlight", ThemePrefs.DEFAULT.selectionColor),
    VarName("Variable name colour", ThemePrefs.DEFAULT.varNameColor),
    VarValue("Variable value colour", ThemePrefs.DEFAULT.varValueColor);

    fun get(p: ThemePrefs): Int = when (this) {
        Background -> p.background
        Text -> p.text
        TextSecondary -> p.textSecondary
        Accent -> p.accent
        Surface -> p.surface
        Border -> p.border
        FlashBackground -> p.flashBackground
        FlashText -> p.flashText
        FlashBorder -> p.flashBorder
        ImportConflict -> p.importConflictColor
        OvalBarBorder -> p.ovalBarBorderColor
        GroupHeader -> p.groupHeaderColor
        GroupHeaderBorder -> p.groupHeaderBorderColor
        ActionName -> p.actionNameColor
        ActionValue -> p.actionValueColor
        ActionLabelFrame -> p.actionLabelFrameColor
        ActionBorder -> p.actionBorderColor
        Selection -> p.selectionColor
        VarName -> p.varNameColor
        VarValue -> p.varValueColor
    }

    fun set(p: ThemePrefs, value: Int): ThemePrefs = when (this) {
        Background -> p.copy(background = value)
        Text -> p.copy(text = value)
        TextSecondary -> p.copy(textSecondary = value)
        Accent -> p.copy(accent = value)
        Surface -> p.copy(surface = value)
        Border -> p.copy(border = value)
        FlashBackground -> p.copy(flashBackground = value)
        FlashText -> p.copy(flashText = value)
        FlashBorder -> p.copy(flashBorder = value)
        ImportConflict -> p.copy(importConflictColor = value)
        OvalBarBorder -> p.copy(ovalBarBorderColor = value)
        GroupHeader -> p.copy(groupHeaderColor = value)
        GroupHeaderBorder -> p.copy(groupHeaderBorderColor = value)
        ActionName -> p.copy(actionNameColor = value)
        ActionValue -> p.copy(actionValueColor = value)
        ActionLabelFrame -> p.copy(actionLabelFrameColor = value)
        ActionBorder -> p.copy(actionBorderColor = value)
        Selection -> p.copy(selectionColor = value)
        VarName -> p.copy(varNameColor = value)
        VarValue -> p.copy(varValueColor = value)
    }
}

private val WEIGHT_OPTIONS = listOf(
    "Default" to 0,
    "Thin" to 100,
    "Light" to 300,
    "Regular" to 400,
    "Medium" to 500,
    "Semibold" to 600,
    "Bold" to 700,
    "Black" to 900,
)

private fun weightLabel(weight: Int): String = WEIGHT_OPTIONS.firstOrNull { it.second == weight }?.first ?: "$weight"

private fun hex6(color: Int): String = "#%06X".format(color and 0xFFFFFF)

// ---- Export / Import panel ----------------------------------------------------------------------

/** Outcome dialogs stacked over the panel; failure leaves the panel open, success closes the chain. */
private sealed interface EximInfo {
    data class ExportDone(val message: String) : EximInfo
    data class ImportDone(val lines: List<String>) : EximInfo
    data class Failure(val message: String) : EximInfo
}

/**
 * The Export/Import panel (Kōjiki's category sheet as a Compose dialog): directory box +
 * last-export line, category checkboxes (export-everything first), and the ArcaneChat-style pill
 * button row — Cancel alone on the left, Import / Export grouped on the right.
 */
@Composable
private fun ExportImportPanel(
    onDismiss: () -> Unit,
    onCloseChain: () -> Unit,
    onDirChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<EximInfo?>(null) }
    val selected = remember {
        mutableStateMapOf<SettingsBackup.Cat, Boolean>().apply {
            SettingsBackup.Cat.entries.forEach { put(it, true) }
        }
    }
    val status by produceState<Pair<String?, SettingsBackup.LatestExport?>>(
        initialValue = null to null,
        refresh,
    ) {
        value = withContext(Dispatchers.IO) {
            SettingsBackup.dirLabel(context) to SettingsBackup.latestExport(context)
        }
    }

    fun selectedCats(): Set<SettingsBackup.Cat> = selected.filterValues { it }.keys

    fun runExport(displayName: String, open: () -> java.io.OutputStream?) {
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val out = open() ?: error("Unable to open the export destination")
                    out.use {
                        SettingsBackup.export(
                            context = context,
                            db = OpenTaskerApp_NoHilt.db,
                            appVersion = BuildConfig.VERSION_NAME,
                            cats = selectedCats(),
                            output = it,
                        )
                    }
                }
            }
                .onSuccess { summary ->
                    refresh++
                    onDirChanged()
                    info = EximInfo.ExportDone("Exported $summary.\n\n$displayName")
                }
                .onFailure { info = EximInfo.Failure("Export failed: ${it.message ?: "unknown error"}") }
            busy = false
        }
    }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            SettingsBackup.setDirUri(context, uri)
            refresh++
            onDirChanged()
        }
    }
    // No directory set: fall back to a save-as picker so export still works one-off.
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            runExport(uri.lastPathSegment ?: "export") { context.contentResolver.openOutputStream(uri) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = readBoundedDocumentBytes(context, uri, EXIM_IMPORT_MAX_BYTES, "import")
                        SettingsBackup.import(context, OpenTaskerApp_NoHilt.db, bytes, selectedCats())
                    }
                }
                    .onSuccess { info = EximInfo.ImportDone(it.summaryLines) }
                    .onFailure { info = EximInfo.Failure("Import failed: ${it.message ?: "unknown error"}") }
                busy = false
            }
        }
    }

    fun onExport() {
        if (selectedCats().isEmpty()) {
            info = EximInfo.Failure("No categories selected.")
            return
        }
        val dir = SettingsBackup.exportDir(context)
        val name = SettingsBackup.exportFileName()
        if (dir == null) {
            saveAsLauncher.launch(name)
        } else {
            runExport(name) {
                val file = dir.createFile("application/zip", name)
                    ?: error("Unable to create a file in the export directory")
                context.contentResolver.openOutputStream(file.uri)
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            modifier = Modifier.dialogFrame(RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = role(UiRole.DIALOG_GROUND),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 20.dp),
            ) {
                Text(
                    "Export / Import — 白い熊 自由作業盤",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = role(UiRole.DIALOG_TITLE),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Everything settable in the app, category by category, as one ZIP of plain JSON files.",
                    fontSize = 13.sp,
                    color = role(UiRole.DIM_GOLD),
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                val (dirName, latest) = status
                // Choosing the directory opens the system picker: a pill with a ›, the directory it
                // stands at as its label — it used to be a framed box, which read as a field.
                Text("Export directory", fontSize = 12.sp, color = role(UiRole.DIM_GOLD))
                OutlinedButton(
                    onClick = { dirPicker.launch(SettingsBackup.dirUri(context)) },
                    enabled = !busy,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    RoleIcon(Icons.Filled.Storage, IconRole.SHARE, Modifier.size(18.dp))
                    Text(
                        dirName ?: "Not set — choose a directory",
                        modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
                        color = if (dirName == null) role(UiRole.WARNING) else Color.Unspecified,
                    )
                    Text("›", Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                }
                val (statusMessage, statusWarn) = when {
                    dirName == null -> "No directory set yet — pick one to enable one-tap export." to true
                    latest == null -> "No export in this directory yet." to false
                    else -> "Last export: ${latest.timestampText}" to false
                }
                Text(
                    statusMessage,
                    fontSize = 13.sp,
                    color = role(if (statusWarn) UiRole.WARNING else UiRole.DIM_GOLD),
                    modifier = Modifier.padding(top = 8.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    thickness = 1.dp,
                    color = role(UiRole.LIST_DIVIDER),
                )
                val allSelected = SettingsBackup.Cat.entries.all { selected[it] == true }
                EximCheckRow("Select all", allSelected, bold = true) { checked ->
                    SettingsBackup.Cat.entries.forEach { selected[it] = checked }
                }
                SettingsBackup.Cat.entries.forEach { cat ->
                    EximCheckRow(cat.label, selected[cat] == true) { selected[cat] = it }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    thickness = 1.dp,
                    color = role(UiRole.LIST_DIVIDER),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EximPill("Cancel", enabled = !busy, onClick = onDismiss)
                    Spacer(Modifier.weight(1f))
                    EximPill("Import", enabled = !busy) {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                    Spacer(Modifier.width(8.dp))
                    EximPill("Export", enabled = !busy, main = true, onClick = ::onExport)
                }
            }
        }
    }

    when (val current = info) {
        is EximInfo.ExportDone -> EximInfoDialog(
            title = "✓ Export finished",
            body = current.message,
            buttons = {
                EximPill("OK", main = true) {
                    info = null
                    onCloseChain()
                }
            },
        )
        is EximInfo.ImportDone -> EximInfoDialog(
            title = "✓ Import finished",
            body = "Restored:\n\n${current.lines.joinToString("\n")}\n\nRestart to apply everything.",
            buttons = {
                EximPill("Later") {
                    info = null
                    onCloseChain()
                }
                Spacer(Modifier.width(8.dp))
                EximPill("Restart now", main = true) { restartApp(context) }
            },
        )
        is EximInfo.Failure -> EximInfoDialog(
            title = "Export / Import",
            body = current.message,
            warn = true,
            // Failure leaves the panel open underneath — only the info dialog closes.
            buttons = { EximPill("OK", main = true) { info = null } },
        )
        null -> Unit
    }
}

/** Black surface, yellow border, right-aligned pill buttons — the finished/failed info dialog. */
@Composable
private fun EximInfoDialog(
    title: String,
    body: String,
    warn: Boolean = false,
    buttons: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Dialog(onDismissRequest = {}) {
        Surface(
            modifier = Modifier.dialogFrame(RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = role(UiRole.DIALOG_GROUND),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = role(if (warn) UiRole.WARNING else UiRole.DIALOG_TITLE),
                )
                Text(
                    body,
                    fontSize = 14.sp,
                    color = role(UiRole.DIALOG_BODY),
                    modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
    }
}

/**
 * A button of the Export / Import panel and its outcome dialogs: the app's pill, with the panel's
 * tighter padding. [main] is the one a dialog exists for (Export, OK, Restart now).
 */
@Composable
private fun EximPill(label: String, enabled: Boolean = true, main: Boolean = false, onClick: () -> Unit) {
    val inner = PaddingValues(horizontal = 20.dp, vertical = 6.dp)
    if (main) {
        Button(onClick = onClick, enabled = enabled, contentPadding = inner) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = inner) { Text(label) }
    }
}

@Composable
private fun EximCheckRow(label: String, checked: Boolean, bold: Boolean = false, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChecked,
            colors = CheckboxDefaults.colors(
                checkedColor = role(UiRole.CHECK_ON),
                uncheckedColor = role(UiRole.CHECK_OFF),
                checkmarkColor = role(UiRole.CHECK_MARK),
            ),
        )
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = role(UiRole.DIALOG_BODY),
        )
    }
}

/** Import cap for the settings ZIP (fonts + icons ride along, so far above the JSON-only cap). */
private const val EXIM_IMPORT_MAX_BYTES = 256 * 1024 * 1024

/** Kōjiki's restart: relaunch the launcher activity as a fresh task, then exit this process. */
private fun restartApp(context: Context) {
    val pm = context.packageManager
    val launch = pm.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(Intent.makeRestartActivityTask(launch.component))
    Runtime.getRuntime().exit(0)
}
