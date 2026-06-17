package com.opentasker.scenes

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.engine.variables.PersistentGlobalScope
import com.opentasker.core.engine.variables.expandAgainstGlobals
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Runtime display of a [Scene]: a modal overlay (scrim + the scene laid out by its elements'
 * dp positions, scaled to fit). Element `%vars` are expanded against the persisted globals, and
 * tap / long-press run the element's tasks. Shown by the `scene.show` action; dismissed by tapping
 * the scrim, back, or the `scene.hide` action.
 */
class SceneActivity : ComponentActivity() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        open.add(WeakReference(this))
        val sceneId = intent.getLongExtra(EXTRA_SCENE_ID, -1L)
        val position = sceneAlignment(intent.getStringExtra(EXTRA_POSITION))
        val timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, 0L)
        val dismissOnOutside = intent.getBooleanExtra(EXTRA_DISMISS_OUTSIDE, true)

        setContent {
            val prefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs) {
                var scene by remember { mutableStateOf<Scene?>(null) }
                LaunchedEffect(sceneId) {
                    scene = withContext(Dispatchers.IO) {
                        OpenTaskerApp_NoHilt.db.sceneDao().getById(sceneId)?.toDomain()
                    }
                }
                // The Activity fallback is always modal; honour position + auto-dismiss timeout.
                if (timeoutMs > 0) {
                    LaunchedEffect(sceneId) { kotlinx.coroutines.delay(timeoutMs); finish() }
                }
                SceneOverlay(scene, modal = true, position = position, dismissOnOutside = dismissOnOutside, onDismiss = { finish() }, onRunTask = ::runTask, onSetVar = ::setVar)
            }
        }
    }

    private fun runTask(taskId: Long) {
        io.launch {
            val db = OpenTaskerApp_NoHilt.db
            val task = db.taskDao().getById(taskId)?.toDomain() ?: return@launch
            executeAndLogTask(applicationContext, db, task, source = "Scene")
        }
    }

    /**
     * Write a scene input element's value to a persisted global so a task (the element's tap task) can
     * read it. Scope follows the variable name's case: `%ALLCAPS` is super-global, anything else is
     * scoped to the scene's project (Unfiled → super-global).
     */
    private fun setVar(sceneProjectId: Long?, name: String, value: String) {
        val clean = name.trim().removePrefix("%").ifBlank { return }
        val superGlobal = clean.any { it.isLetter() } && clean == clean.uppercase()
        PersistentGlobalScope.set(if (superGlobal) 0L else (sceneProjectId ?: 0L), clean, value)
    }

    override fun onDestroy() {
        super.onDestroy()
        open.removeAll { it.get() == null || it.get() === this }
    }

    companion object {
        const val EXTRA_SCENE_ID = "com.opentasker.scenes.SCENE_ID"
        const val EXTRA_POSITION = "com.opentasker.scenes.POSITION"
        const val EXTRA_TIMEOUT_MS = "com.opentasker.scenes.TIMEOUT_MS"
        const val EXTRA_DISMISS_OUTSIDE = "com.opentasker.scenes.DISMISS_OUTSIDE"
        private val open = mutableListOf<WeakReference<SceneActivity>>()

        /** Dismiss every open scene (the `scene.hide` action). Returns how many were closed. */
        fun dismissAll(): Int {
            val activities = open.mapNotNull { it.get() }
            activities.forEach { it.runOnUiThread { it.finish() } }
            open.clear()
            return activities.size
        }
    }
}

/**
 * The scene's content. Shared by [SceneActivity] and the system-wide [SceneOverlayManager] window.
 * [modal] = full-screen dimmed scrim with the card scaled to fit and placed at [position] (tap the
 * scrim to dismiss); non-modal = just the card at its exact size (the window handles placement),
 * leaving the app underneath visible and touchable around it.
 */
@Composable
internal fun SceneOverlay(
    scene: Scene?,
    modal: Boolean = true,
    position: Alignment = Alignment.Center,
    dismissOnOutside: Boolean = true,
    fullWidth: Boolean = false,
    fullscreen: Boolean = false,
    onDismiss: () -> Unit,
    onRunTask: (Long) -> Unit,
    onSetVar: (sceneProjectId: Long?, name: String, value: String) -> Unit,
) {
    if (scene == null) return
    if (modal) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scene.scrimAlpha.coerceIn(0, 100) / 100f))
                // Scrim always consumes the tap (blocks the app); it dismisses only when allowed.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (dismissOnOutside) onDismiss()
                },
            contentAlignment = position,
        ) {
            BoxWithConstraints {
                val sw = scene.widthDp.coerceAtLeast(1)
                val sh = scene.heightDp.coerceAtLeast(1)
                val scale = minOf(1f, (maxWidth.value * 0.94f) / sw, (maxHeight.value * 0.94f) / sh)
                SceneCard(scene, scale, absorbTaps = true, onRunTask = onRunTask, onSetVar = onSetVar)
            }
        }
    } else {
        SceneCard(scene, scale = 1f, absorbTaps = false, fullWidth = fullWidth, fullscreen = fullscreen, onRunTask = onRunTask, onSetVar = onSetVar)
    }
}

@Composable
private fun SceneCard(
    scene: Scene,
    scale: Float,
    absorbTaps: Boolean,
    fullWidth: Boolean = false,
    fullscreen: Boolean = false,
    onRunTask: (Long) -> Unit,
    onSetVar: (sceneProjectId: Long?, name: String, value: String) -> Unit,
) {
    val sw = scene.widthDp.coerceAtLeast(1)
    val sh = scene.heightDp.coerceAtLeast(1)
    val shape = RoundedCornerShape((scene.cornerRadiusDp.coerceAtLeast(0) * scale).dp)
    val borderW = scene.borderWidth.coerceAtLeast(0)
    Box(
        Modifier
            // fullscreen (e.g. the music edge-light): cover the whole screen. fullWidth: span the
            // screen width, keep the configured height (e.g. a top status bar). Else a fixed card.
            .then(
                if (fullscreen) Modifier.fillMaxSize()
                else if (fullWidth) Modifier.fillMaxWidth().height((sh * scale).dp)
                else Modifier.size((sw * scale).dp, (sh * scale).dp),
            )
            .clip(shape)
            // Blank background defaults to the theme background (black); blank border to outline (yellow).
            .background(sceneColor(scene.bgColor) ?: MaterialTheme.colorScheme.background)
            .then(if (borderW > 0) Modifier.border((borderW * scale).dp, sceneColor(scene.borderColor) ?: MaterialTheme.colorScheme.outline, shape) else Modifier)
            // Modal: absorb taps so tapping the card (not the scrim) doesn't dismiss.
            .then(if (absorbTaps) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {} else Modifier),
    ) {
        scene.elements.forEach { element ->
            // widthDp/heightDp <= 0 means "fill the card" — lets an element span a full-width bar scene.
            Box(
                Modifier
                    .offset((element.xDp * scale).dp, (element.yDp * scale).dp)
                    .then(if (element.widthDp > 0) Modifier.width((element.widthDp * scale).dp) else Modifier.fillMaxWidth())
                    .then(if (element.heightDp > 0) Modifier.height((element.heightDp * scale).dp) else Modifier.fillMaxHeight()),
            ) {
                SceneElementView(element, onRunTask) { name, value -> onSetVar(scene.projectId, name, value) }
            }
        }
    }
}

@Composable
internal fun SceneElementView(
    element: SceneElement,
    onRunTask: (Long) -> Unit,
    onSetVar: (name: String, value: String) -> Unit,
) {
    val cfg = element.config
    // Re-expand %vars whenever any global changes, so a shown scene/HUD stays live (e.g. a clock).
    val revision by PersistentGlobalScope.revision.collectAsState()
    fun v(key: String, fallback: String = ""): String {
        revision // subscribe: reading the value here recomposes this element on any variable change.
        return expandAgainstGlobals(cfg[key] ?: fallback)
    }
    // Shared styling (see the element editor's Style section).
    val styleSize = cfg["textSize"]?.toIntOrNull()?.sp ?: TextUnit.Unspecified
    val styleWeight = if (sceneBool(cfg["bold"] ?: "")) FontWeight.Bold else FontWeight.Normal
    // For elements with a styled label (slider/checkbox/toggle) keep the label's own weight unless bold.
    val styleWeightOrNull = if (sceneBool(cfg["bold"] ?: "")) FontWeight.Bold else null
    val styleLabelColor = sceneColor(cfg["textColor"])
    val styleAlign = sceneAlign(cfg["align"])
    val styleBorderW = cfg["borderWidth"]?.toIntOrNull() ?: 0
    val styleBorderColor = sceneColor(cfg["borderColor"])
    // Optional custom font: an imported .ttf/.otf filename (same library the widgets/clock use).
    val styleFont = cfg["font"]?.trim()?.takeIf { it.isNotEmpty() }?.let { ThemeStore.fontFamily(it) }
    when (element.type) {
        SceneElementType.TEXT -> {
            val bg = sceneColor(cfg["bgColor"])
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier.fillMaxSize()
                    .then(if (bg != null) Modifier.background(bg, shape) else Modifier)
                    .then(if (styleBorderW > 0) Modifier.border(styleBorderW.dp, styleBorderColor ?: MaterialTheme.colorScheme.outline, shape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    v("text"),
                    color = sceneColor(cfg["textColor"]) ?: MaterialTheme.colorScheme.onSurface,
                    fontFamily = styleFont,
                    fontSize = styleSize,
                    fontWeight = styleWeight,
                    textAlign = styleAlign ?: TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SceneElementType.BUTTON -> Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(sceneColor(cfg["bgColor"]) ?: MaterialTheme.colorScheme.primary)
                .then(if (styleBorderW > 0) Modifier.border(styleBorderW.dp, styleBorderColor ?: MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)) else Modifier)
                .pointerInput(element.id) {
                    detectTapGestures(
                        onTap = { element.tapTaskId?.let(onRunTask) },
                        onLongPress = { element.longPressTaskId?.let(onRunTask) },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                v("label", "Button"),
                color = sceneColor(cfg["textColor"]) ?: MaterialTheme.colorScheme.onPrimary,
                fontFamily = styleFont,
                fontSize = styleSize,
                fontWeight = styleWeight,
                textAlign = styleAlign ?: TextAlign.Center,
            )
        }

        SceneElementType.SLIDER -> {
            val min = cfg["min"]?.toFloatOrNull() ?: 0f
            val max = (cfg["max"]?.toFloatOrNull() ?: 100f).coerceAtLeast(min + 1f)
            val varName = cfg["var"]?.trim()
            val vertical = cfg["orientation"].equals("vertical", ignoreCase = true)
            // live: also commit (set var + run task) on every integer step during the drag, not just on
            // release — so a volume/brightness slider applies as you slide it.
            val live = sceneBool(cfg["live"] ?: "")
            // Initial value is expanded against globals, so `value: "%VOL"` starts the slider at the
            // live variable (e.g. the current volume, seeded by a Get Volume action before scene.show).
            var value by remember(element.id) { mutableStateOf((v("value").toFloatOrNull() ?: min).coerceIn(min, max)) }
            // On release: publish the settled value to `var` (if set), then run the tap task — which can
            // read that variable (e.g. a Set Volume action with level = %VOL).
            val onSettled: () -> Unit = {
                if (!varName.isNullOrBlank()) onSetVar(varName, value.roundToInt().toString())
                element.tapTaskId?.let(onRunTask)
            }
            var lastSent by remember(element.id) { mutableStateOf(Int.MIN_VALUE) }
            val onChange: (Float) -> Unit = { f ->
                value = f
                if (live) {
                    val r = value.roundToInt()
                    if (r != lastSent) { lastSent = r; onSettled() }
                }
            }
            val label = v("label", "Slider")
            if (vertical) {
                // A horizontal Slider rotated 90° CCW: its track length follows the element height, so
                // the top is max and dragging up increases the value.
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = styleLabelColor ?: MaterialTheme.colorScheme.onSurface, fontSize = styleSize, fontWeight = styleWeightOrNull)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val trackLength = maxHeight
                        Slider(
                            value = value,
                            onValueChange = onChange,
                            onValueChangeFinished = onSettled,
                            valueRange = min..max,
                            // requiredWidth ignores the (narrow) element width so the rotated track can
                            // span the full element height instead of being clamped to its width.
                            modifier = Modifier.requiredWidth(trackLength).rotate(-90f),
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = styleLabelColor ?: MaterialTheme.colorScheme.onSurface, fontSize = styleSize, fontWeight = styleWeightOrNull)
                    Slider(
                        value = value,
                        onValueChange = onChange,
                        onValueChangeFinished = onSettled,
                        valueRange = min..max,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        SceneElementType.EDIT_TEXT -> {
            val varName = cfg["var"]?.trim()
            var text by remember(element.id) { mutableStateOf(v("value")) }
            var focused by remember(element.id) { mutableStateOf(false) }
            // Seed the bound variable with the initial value so other elements/tasks can read it before
            // any edit (e.g. a button that flashes %NAME without the field being touched).
            LaunchedEffect(element.id) { if (!varName.isNullOrBlank()) onSetVar(varName, text) }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    // Keep the variable live on every keystroke, so anything reading it (a button, a
                    // task) sees the current text without waiting for Done/focus-loss.
                    if (!varName.isNullOrBlank()) onSetVar(varName, it)
                },
                label = { Text(v("label", "Text")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                // Run the element's tap task when the user presses Done or the field loses focus.
                keyboardActions = KeyboardActions(onDone = { element.tapTaskId?.let(onRunTask) }),
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { st ->
                        if (focused && !st.isFocused) element.tapTaskId?.let(onRunTask)
                        focused = st.isFocused
                    },
            )
        }

        SceneElementType.CHECKBOX, SceneElementType.TOGGLE -> {
            val varName = cfg["var"]?.trim()
            var checked by remember(element.id) { mutableStateOf(sceneBool(v("value"))) }
            // On change: write the boolean to `var` (true/false) and run the tap task.
            val onChanged: (Boolean) -> Unit = { c ->
                checked = c
                if (!varName.isNullOrBlank()) onSetVar(varName, c.toString())
                element.tapTaskId?.let(onRunTask)
            }
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (element.type == SceneElementType.CHECKBOX) {
                    Checkbox(checked = checked, onCheckedChange = onChanged)
                    Text(v("label", "Checkbox"), color = styleLabelColor ?: MaterialTheme.colorScheme.onSurface, fontSize = styleSize, fontWeight = styleWeightOrNull, modifier = Modifier.weight(1f))
                } else {
                    Text(v("label", "Toggle"), color = styleLabelColor ?: MaterialTheme.colorScheme.onSurface, fontSize = styleSize, fontWeight = styleWeightOrNull, modifier = Modifier.weight(1f))
                    Switch(checked = checked, onCheckedChange = onChanged)
                }
            }
        }

        SceneElementType.SPINNER -> {
            val varName = cfg["var"]?.trim()
            val options = remember(cfg["options"]) {
                (cfg["options"] ?: "").split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
            }
            var selected by remember(element.id) { mutableStateOf(v("value")) }
            var expanded by remember(element.id) { mutableStateOf(false) }
            // Seed the variable with the current selection so tasks can read it before any change.
            LaunchedEffect(element.id) { if (!varName.isNullOrBlank()) onSetVar(varName, selected) }
            Box(Modifier.fillMaxSize()) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxSize()) {
                    Text(
                        selected.ifBlank { v("label", "Select") },
                        color = styleLabelColor ?: MaterialTheme.colorScheme.onSurface,
                        fontSize = styleSize,
                        fontWeight = styleWeightOrNull,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { opt ->
                        DropdownMenuItem(text = { Text(opt) }, onClick = {
                            selected = opt
                            expanded = false
                            // On select: write the value to `var` and run the tap task.
                            if (!varName.isNullOrBlank()) onSetVar(varName, opt)
                            element.tapTaskId?.let(onRunTask)
                        })
                    }
                }
            }
        }

        SceneElementType.NUMBER_PICKER -> {
            val min = cfg["min"]?.toIntOrNull() ?: 0
            val max = (cfg["max"]?.toIntOrNull() ?: 100).coerceAtLeast(min)
            val step = (cfg["step"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val varName = cfg["var"]?.trim()
            // Start value is expanded against globals, so `value: "%COUNT"` opens at the live variable.
            var value by remember(element.id) { mutableStateOf((v("value").toIntOrNull() ?: min).coerceIn(min, max)) }
            // Seed the bound variable so tasks/elements can read it before any tap.
            LaunchedEffect(element.id) { if (!varName.isNullOrBlank()) onSetVar(varName, value.toString()) }
            val onChanged: (Int) -> Unit = { next ->
                value = next.coerceIn(min, max)
                if (!varName.isNullOrBlank()) onSetVar(varName, value.toString())
                element.tapTaskId?.let(onRunTask)
            }
            val label = v("label", "")
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (label.isNotBlank()) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = styleLabelColor ?: MaterialTheme.colorScheme.onSurface, fontSize = styleSize, fontWeight = styleWeightOrNull)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onChanged(value - step) },
                        enabled = value > min,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(44.dp),
                    ) { Text("−", fontSize = 20.sp) }
                    Text(
                        value.toString(),
                        color = styleLabelColor ?: MaterialTheme.colorScheme.onSurface,
                        fontSize = if (styleSize != TextUnit.Unspecified) styleSize else 18.sp,
                        fontWeight = styleWeight,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { onChanged(value + step) },
                        enabled = value < max,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(44.dp),
                    ) { Text("+", fontSize = 20.sp) }
                }
            }
        }

        SceneElementType.RECTANGLE, SceneElementType.OVAL -> {
            val fill = sceneColor(cfg["bgColor"])
            val shape = if (element.type == SceneElementType.OVAL) ovalShape
            else RoundedCornerShape((cfg["cornerRadius"]?.toIntOrNull() ?: 0).dp)
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .then(if (fill != null) Modifier.background(fill) else Modifier)
                    .then(if (styleBorderW > 0) Modifier.border(styleBorderW.dp, styleBorderColor ?: MaterialTheme.colorScheme.outline, shape) else Modifier)
                    .then(
                        if (element.tapTaskId != null || element.longPressTaskId != null) {
                            Modifier.pointerInput(element.id) {
                                detectTapGestures(
                                    onTap = { element.tapTaskId?.let(onRunTask) },
                                    onLongPress = { element.longPressTaskId?.let(onRunTask) },
                                )
                            }
                        } else Modifier,
                    ),
            )
        }

        SceneElementType.IMAGE -> {
            val source = v("source")
            val bmp = remember(source) {
                runCatching { File(source).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }.getOrNull()
            }
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(source.ifBlank { "Image" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SceneElementType.PROGRESS -> {
            // Horizontal fill bar (e.g. the battery line). value=0..100 (a %var); fillColor over trackColor;
            // when `charging` is truthy a highlight sweeps along the filled part. Re-renders live via v().
            val pct = ((v("value").toFloatOrNull() ?: 0f).coerceIn(0f, 100f)) / 100f
            val fillColor = sceneColor(v("fillColor")) ?: MaterialTheme.colorScheme.primary
            val trackColor = sceneColor(v("trackColor")) ?: Color.Transparent
            val charging = sceneBool(v("charging"))
            Box(Modifier.fillMaxSize().background(trackColor)) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(pct)
                        .align(Alignment.Center)
                        .background(fillColor),
                ) {
                    if (charging && pct > 0f) {
                        // Charging indicator. The sweep is driven by a delay loop that advances a State —
                        // the same state→recompose path as the live colour update, so it animates inside
                        // the overlay window (where an infinite-transition frame clock may not tick). A
                        // static brightening on top keeps "charging" unmistakable even on a 3 dp bar.
                        var sweep by remember { mutableStateOf(0f) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                sweep = if (sweep >= 1f) 0f else sweep + 0.012f
                                delay(16L)
                            }
                        }
                        Box(
                            Modifier.matchParentSize().drawWithContent {
                                drawContent()
                                drawRect(color = Color.Red.copy(alpha = 0.22f))
                                val band = size.width * 0.30f
                                val cx = sweep * (size.width + band) - band
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(Color.Transparent, Color.Red.copy(alpha = 0.9f), Color.Transparent),
                                        startX = cx,
                                        endX = cx + band,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }

        SceneElementType.WEB -> {
            // A transparent, JS-enabled WebView showing raw HTML from config (e.g. the music
            // edge-light's canvas meteor animation). The page body is loaded RAW — not %var-expanded —
            // because its JS uses '%' (modulo) that expansion would mangle. Instead, an optional `vars`
            // config (newline-separated name=value, value %var-expanded) is injected as window.<name>
            // JS globals, so a settings task can tune the animation. Tap-through via pointer-events:none.
            val rawHtml = cfg["html"] ?: ""
            val inject = (cfg["vars"] ?: "").lineSequence().mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val name = line.substring(0, eq).trim()
                val value = expandAgainstGlobals(line.substring(eq + 1).trim())
                if (name.isEmpty() || value.isEmpty()) null else "window.$name=${jsString(value)};"
            }.joinToString("")
            val html = when {
                inject.isEmpty() -> rawHtml
                rawHtml.contains("</head>", ignoreCase = true) ->
                    rawHtml.replaceFirst("</head>", "<script>$inject</script></head>")
                else -> "<script>$inject</script>$rawHtml"
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        settings.javaScriptEnabled = true
                    }
                },
                update = { wv ->
                    if (wv.tag != html) {
                        wv.tag = html
                        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                },
            )
        }

        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(element.type.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Truthy parse for checkbox/toggle scene values. */
private fun sceneBool(s: String): Boolean = s.trim().lowercase() in setOf("true", "1", "on", "yes")

/** Encode a string as a safe double-quoted JS literal (for WebView variable injection). */
private fun jsString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

/** An ellipse filling the element's bounds (the OVAL shape). */
private val ovalShape = GenericShape { size, _ -> addOval(Rect(0f, 0f, size.width, size.height)) }

/** Map a scene `position` arg to where the card sits on screen. */
internal fun sceneAlignment(position: String?): Alignment = when (position?.trim()?.lowercase()) {
    "top" -> Alignment.TopCenter
    "bottom" -> Alignment.BottomCenter
    "left" -> Alignment.CenterStart
    "right" -> Alignment.CenterEnd
    else -> Alignment.Center
}

/** Parse a "#AARRGGBB"/"#RRGGBB" scene colour, or null (use the element's default). */
private fun sceneColor(s: String?): Color? =
    s?.trim()?.takeIf { it.isNotBlank() }?.let {
        runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
    }

/** Map a scene `align` value to a [TextAlign], or null (default per element). */
private fun sceneAlign(s: String?): TextAlign? = when (s?.trim()?.lowercase()) {
    "center" -> TextAlign.Center
    "end", "right" -> TextAlign.End
    "start", "left" -> TextAlign.Start
    else -> null
}
