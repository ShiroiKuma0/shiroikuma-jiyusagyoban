package com.opentasker.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.opentasker.ui.components.ThemedDropdownMenu
import com.opentasker.ui.components.ThemedSnackbarHost
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Indent unit; section headers sit flush at 16dp, items step to 32dp — the kxkb cascade. */
private fun rowStartPadding(level: Int) = (16 + level * 16).dp

/** Kōjiki warn red — the directory-unset / failure colour. */
private val EximWarnColor = Color(0xFFFF5252)

/**
 * "白い熊 自由作業盤 UI" — the appearance-customization page, mirroring the sister apps' theme page:
 * logically sectioned, with individual items indented under their section headers, exposing the
 * colours, borders, and fonts that make up the default black-yellow look (all settable).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UiCustomizationScreen(
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val prefs by ThemeStore.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    var chartColorTarget by remember { mutableStateOf<ChartColorTarget?>(null) }
    var showFontPicker by remember { mutableStateOf(false) }
    var showBubbleFontPicker by remember { mutableStateOf(false) }
    var showPickerFontPicker by remember { mutableStateOf(false) }
    var fontsRefresh by remember { mutableIntStateOf(0) }

    // Automation intent surface (StateExportReceiver): master switch + shared secret.
    var automationEnabled by remember { mutableStateOf(AutomationAuth.enabled(context)) }
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            // --- Export / Import — first separated section (Kōjiki UI-page flow) ---
            item { SectionHeader("Export / Import", first = true) }
            item {
                RowScaffold(1, onClick = { eximDirPicker.launch(SettingsBackup.dirUri(context)) }) {
                    Column(Modifier.weight(1f)) {
                        Text("Export directory (tap to choose)", style = MaterialTheme.typography.bodyLarge)
                        val dirName = eximStatus.first
                        Text(
                            dirName ?: "Not set — tap to choose a directory",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (dirName == null) EximWarnColor else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item {
                val (dirName, latest) = eximStatus
                val (message, warn) = when {
                    dirName == null -> "No directory set yet — pick one to enable one-tap export." to true
                    latest == null -> "No export in this directory yet." to false
                    else -> "Last export: ${latest.timestampText}" to false
                }
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warn) EximWarnColor else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = rowStartPadding(1), end = 16.dp, bottom = 8.dp),
                )
            }
            item {
                RowScaffold(1, onClick = { showEximPanel = true }) {
                    Column(Modifier.weight(1f)) {
                        Text("Export / Import…", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Back up or restore everything settable — the whole workspace and all app settings — as one ZIP.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SwitchRow(
                    level = 1,
                    label = "Automation export",
                    description = "Let sister-app tasks trigger this export via the token-gated EXPORT_STATE intent.",
                    checked = automationEnabled,
                    onCheckedChange = {
                        automationEnabled = it
                        AutomationAuth.setEnabled(context, it)
                    },
                )
            }
            item {
                val clipboard = LocalClipboardManager.current
                RowScaffold(1, onClick = {
                    clipboard.setText(AnnotatedString(automationToken))
                    scope.launch { snackbarHostState.showSnackbar("Automation token copied") }
                }) {
                    Column(Modifier.weight(1f)) {
                        Text("Automation token (tap to copy)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${automationToken.take(8)}…${automationToken.takeLast(8)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        "Regenerate",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = EximWarnColor,
                        modifier = Modifier.clickable {
                            automationToken = AutomationAuth.regenerateToken(context)
                            scope.launch { snackbarHostState.showSnackbar("Automation token regenerated — update pasted copies") }
                        },
                    )
                }
            }

            item { SectionHeader("Colors") }
            item { ColorRow(1, "Background", prefs.background, ColorTarget.Background) { colorTarget = it } }
            item { ColorRow(1, "Text", prefs.text, ColorTarget.Text) { colorTarget = it } }
            item { ColorRow(1, "Secondary text", prefs.textSecondary, ColorTarget.TextSecondary) { colorTarget = it } }
            item { ColorRow(1, "Accent", prefs.accent, ColorTarget.Accent) { colorTarget = it } }
            item { ColorRow(1, "Surface (cards & bars)", prefs.surface, ColorTarget.Surface) { colorTarget = it } }

            item { SectionHeader("Borders") }
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

            item { SectionHeader("Typography") }
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

            item { SectionHeader("Flash / toast") }
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

            item { SectionHeader("Editor") }
            item {
                SwitchRow(
                    level = 1,
                    label = "Advanced action picker",
                    description = "When adding an action, browse a full-screen list folded by category, each action expandable to its description and fields.",
                    checked = prefs.advancedActionPicker,
                    onCheckedChange = { ThemeStore.update { p -> p.copy(advancedActionPicker = it) } },
                )
            }

            item { SectionHeader("Task list") }
            item { TaskIconSizeRow(level = 1, prefs = prefs) }

            item { SectionHeader("Tasks") }
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
            item { SectionHeader("Variables") }
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

            item { SectionHeader("Monitor") }
            item {
                SliderRow(
                    level = 1, label = "Task row spacing",
                    value = prefs.monitorRowPadDp, valueText = "${prefs.monitorRowPadDp} dp",
                    range = 0f..ThemePrefs.MONITOR_PAD_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(monitorRowPadDp = v) } },
                )
            }

            item { SectionHeader("Panel bars") }
            item {
                SliderRow(
                    level = 1, label = "Oval bar border width",
                    value = prefs.ovalBarBorderWidthDp, valueText = "${prefs.ovalBarBorderWidthDp} dp",
                    range = 0f..ThemePrefs.OVAL_BAR_BORDER_MAX.toFloat(),
                    onChange = { v -> ThemeStore.update { it.copy(ovalBarBorderWidthDp = v) } },
                )
            }
            item { ColorRow(1, "Oval bar border colour", prefs.ovalBarBorderColor, ColorTarget.OvalBarBorder) { colorTarget = it } }

            item { SectionHeader("Freeze bubbles") }
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
            item { SectionHeader("Flash bubbles (通知明滅)") }
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

            item { SectionHeader("Shortcut picker") }
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

            item { SectionHeader("Import review") }
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


            // --- 「健康」 charts — the numbers the band screens draw with ---------------------
            item { SectionHeader("「文字認識」 OCR") }
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

            item { SectionHeader("「健康」 charts") }
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
                    description = "The recessive lines behind the data.",
                    checked = prefs.chartShowGrid,
                    onCheckedChange = { v -> ThemeStore.update { it.copy(chartShowGrid = v) } },
                )
            }
            item {
                SwitchRow(
                    level = 1, label = "Sample dots",
                    description = "The real readings, drawn over the smoothed line. Turning these off hides where the curve is interpolating.",
                    checked = prefs.chartShowDots,
                    onCheckedChange = { v -> ThemeStore.update { it.copy(chartShowDots = v) } },
                )
            }
            item {
                SwitchRow(
                    level = 1, label = "Flagged-sample ✕ marks",
                    description = "Readings the outlier filter dropped, shown at their real value.",
                    checked = prefs.chartShowRejected,
                    onCheckedChange = { v -> ThemeStore.update { it.copy(chartShowRejected = v) } },
                )
            }
            item {
                SwitchRow(
                    level = 1, label = "Tint stretches with no measurement",
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
                ActionRow(
                    level = 1,
                    label = "Restore the validated chart palette",
                    description = "Puts every chart knob above back to what shipped — the colours that pass the checks in the preview.",
                    actionLabel = "Restore",
                    onAction = { ThemeStore.resetChartsToDefault() },
                )
            }

            // --- Presets — at the very bottom (白い熊 2026-07-25), like Kōjiki's trailing Reset row ---
            item { SectionHeader("Presets") }
            item {
                ActionRow(
                    level = 1,
                    label = "Reset to black & yellow",
                    description = "Restore the default appearance — black background, yellow text and borders.",
                    actionLabel = "Reset",
                    onAction = { ThemeStore.resetToDefault() },
                )
            }
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

    chartColorTarget?.let { target ->
        ColorPickerDialog(
            title = target.label,
            initial = target.get(prefs),
            onDismiss = { chartColorTarget = null },
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

/**
 * kxkb-style section heading: 20sp medium accent title underlined only as wide as the text
 * (IntrinsicSize.Min sizes the column to its single line), each section preceded by a thin
 * full-width hairline spacer (Kōjiki's addSectionSpacer).
 */
@Composable
private fun SectionHeader(title: String, first: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        if (!first) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 20.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            )
        }
        Column(
            Modifier
                .padding(start = 16.dp, top = if (first) 8.dp else 20.dp, end = 16.dp, bottom = 4.dp)
                .width(IntrinsicSize.Min),
        ) {
            Text(
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RowScaffold(
    level: Int,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
    Row(
        modifier = clickable.padding(
            start = rowStartPadding(level),
            end = 16.dp,
            top = 12.dp,
            bottom = 12.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun ColorRow(level: Int, label: String, value: Int, target: ColorTarget, onPick: (ColorTarget) -> Unit) {
    RowScaffold(level, onClick = { onPick(target) }) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (value == target.default) "Default - ${hex6(value)}" else hex6(value),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(value))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

@Composable
private fun SliderRow(
    level: Int,
    label: String,
    value: Int,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = rowStartPadding(level), end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FontRow(level: Int, fileName: String, onClick: () -> Unit) {
    RowScaffold(level, onClick = onClick) {
        Text("Font", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            ThemeStore.displayNameFor(fileName),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = ThemeStore.fontFamily(fileName) ?: FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WeightRow(level: Int, weight: Int, onPick: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        RowScaffold(level, onClick = { expanded = true }) {
            Text("Weight", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                weightLabel(weight),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ThemedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            WEIGHT_OPTIONS.forEach { (label, w) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onPick(w)
                        expanded = false
                    },
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
        RowScaffold(level, onClick = { expanded = true }) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                FLASH_BEHAVIOR_OPTIONS.firstOrNull { it.first == value }?.second ?: value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                )
            }
        }
    }
}

/** A workspace task name the flash-bubble layer runs (kill / kill-all), edited inline. */
@Composable
private fun FlashTaskRow(level: Int, label: String, value: String, onChange: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = rowStartPadding(level), end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SampleRow(level: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = rowStartPadding(level), end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text("Live sample", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "AaIiMmQq 0123  白い熊 自由作業盤  áÁčČ",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Renders a sample flash exactly as the real one, so changes are visible without running a task. */
@Composable
private fun FlashPreview(level: Int, prefs: ThemePrefs) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = rowStartPadding(level), end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text("Live preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = rowStartPadding(level), end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Task icon size", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                "${prefs.taskIconSizeDp} dp",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = prefs.taskIconSizeDp.toFloat(),
            onValueChange = { v -> ThemeStore.update { it.copy(taskIconSizeDp = v.roundToInt()) } },
            valueRange = ThemePrefs.TASK_ICON_MIN.toFloat()..ThemePrefs.TASK_ICON_MAX.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Live preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    Column(
        Modifier.fillMaxWidth().padding(start = rowStartPadding(level), end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Live preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(
        Modifier.fillMaxWidth().padding(start = rowStartPadding(level), end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Live preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun ActionRow(level: Int, label: String, description: String, actionLabel: String, onAction: () -> Unit) {
    RowScaffold(level) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun SwitchRow(level: Int, label: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    RowScaffold(level, onClick = { onCheckedChange(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---- dialogs ------------------------------------------------------------------------------------

@Composable
private fun ColorPickerDialog(title: String, initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var r by remember { mutableIntStateOf((initial ushr 16) and 0xFF) }
    var g by remember { mutableIntStateOf((initial ushr 8) and 0xFF) }
    var b by remember { mutableIntStateOf(initial and 0xFF) }
    var hexText by remember { mutableStateOf(hex6(initial)) }
    val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    fun syncHex() { hexText = hex6((r shl 16) or (g shl 8) or b) }

    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
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
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(argb) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
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
                        Text(
                            option.displayName,
                            modifier = Modifier.weight(1f),
                            // Render every option in its OWN glyphs.
                            fontFamily = ThemeStore.fontFamily(option.fileName) ?: FontFamily.Default,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (option.fileName == current) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        }
                        if (option.fileName.isNotEmpty() && option.fileName != ThemeStore.MONOSPACE) {
                            IconButton(onClick = { onDelete(option.fileName) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete font", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAddFont)
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Add font…", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---- model helpers ------------------------------------------------------------------------------

/**
 * A sub-heading inside a section.
 *
 * The chart section has thirty-odd rows, which is more than one flat list can be read as. It groups
 * into sizes / marks / ink / what-gets-drawn / colours, and those groups deserve labels without each
 * becoming a full [SectionHeader] with its own rule above it.
 */
@Composable
private fun SubHeader(level: Int, title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = rowStartPadding(level), end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Live preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = Color(ThemePrefs.NEAR_BLACK), shape = RoundedCornerShape(12.dp)) {
            ChartStylePreview(prefs, Modifier.padding(10.dp))
        }
        Text("Colour check", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        RowScaffold(level, onClick = { expanded = true }) {
            Text("Line shape", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                CHART_CURVE_OPTIONS.firstOrNull { it.first == value }?.second ?: value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ThemedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CHART_CURVE_OPTIONS.forEach { (v, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onPick(v); expanded = false })
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
    RowScaffold(level, onClick = { onPick(target) }) {
        Column(Modifier.weight(1f)) {
            Text(target.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (value == target.get(ThemePrefs.DEFAULT)) "Default - ${hex6(value)}" else hex6(value),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(value))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
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
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
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
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Everything settable in the app, category by category, as one ZIP of plain JSON files.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                val (dirName, latest) = status
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) { dirPicker.launch(SettingsBackup.dirUri(context)) }
                        .padding(12.dp),
                ) {
                    Text("Export directory (tap to choose)", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        dirName ?: "Not set — tap to choose a directory",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (dirName == null) EximWarnColor else MaterialTheme.colorScheme.onSurface,
                    )
                }
                val (statusMessage, statusWarn) = when {
                    dirName == null -> "No directory set yet — pick one to enable one-tap export." to true
                    latest == null -> "No export in this directory yet." to false
                    else -> "Last export: ${latest.timestampText}" to false
                }
                Text(
                    statusMessage,
                    fontSize = 13.sp,
                    color = if (statusWarn) EximWarnColor else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
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
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
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
                    EximPill("Export", enabled = !busy, onClick = ::onExport)
                }
            }
        }
    }

    when (val current = info) {
        is EximInfo.ExportDone -> EximInfoDialog(
            title = "✓ Export finished",
            body = current.message,
            buttons = {
                EximPill("OK") {
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
                EximPill("Restart now") { restartApp(context) }
            },
        )
        is EximInfo.Failure -> EximInfoDialog(
            title = "Export / Import",
            body = current.message,
            warn = true,
            // Failure leaves the panel open underneath — only the info dialog closes.
            buttons = { EximPill("OK") { info = null } },
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
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (warn) EximWarnColor else MaterialTheme.colorScheme.primary,
                )
                Text(
                    body,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
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

/** ArcaneChat-style pill: black fill, 1.5dp accent stroke, accent text, fully rounded. */
@Composable
private fun EximPill(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Text(label)
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
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = MaterialTheme.colorScheme.primary,
                checkmarkColor = MaterialTheme.colorScheme.background,
            ),
        )
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
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
