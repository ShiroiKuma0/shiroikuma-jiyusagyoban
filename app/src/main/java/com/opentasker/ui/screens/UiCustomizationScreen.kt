package com.opentasker.ui.screens

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.opentasker.ui.components.ThemedDropdownMenu
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.sp
import com.opentasker.ui.theme.FontOption
import com.opentasker.ui.theme.ThemePrefs
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Indent unit; section headers sit flush, individual items step in for orientation. */
private val INDENT_STEP = 28.dp

private fun rowStartPadding(level: Int) = (16 + level * 28).dp

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
    val prefs by ThemeStore.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    var showFontPicker by remember { mutableStateOf(false) }
    var showBubbleFontPicker by remember { mutableStateOf(false) }
    var fontsRefresh by remember { mutableIntStateOf(0) }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
        }
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
}

// ---- section / rows -----------------------------------------------------------------------------

@Composable
private fun SectionHeader(title: String) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(
            modifier = Modifier.padding(top = 6.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
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

private enum class ColorTarget(val label: String, val default: Int) {
    Background("Background", ThemePrefs.DEFAULT.background),
    Text("Text", ThemePrefs.DEFAULT.text),
    TextSecondary("Secondary text", ThemePrefs.DEFAULT.textSecondary),
    Accent("Accent", ThemePrefs.DEFAULT.accent),
    Surface("Surface", ThemePrefs.DEFAULT.surface),
    Border("Border", ThemePrefs.DEFAULT.border),
    FlashBackground("Flash background", ThemePrefs.DEFAULT.flashBackground),
    FlashText("Flash text", ThemePrefs.DEFAULT.flashText),
    FlashBorder("Flash border", ThemePrefs.DEFAULT.flashBorder);

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
