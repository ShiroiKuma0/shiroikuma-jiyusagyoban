package com.opentasker.scenes

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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

        setContent {
            val prefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs) {
                var scene by remember { mutableStateOf<Scene?>(null) }
                LaunchedEffect(sceneId) {
                    scene = withContext(Dispatchers.IO) {
                        OpenTaskerApp_NoHilt.db.sceneDao().getById(sceneId)?.toDomain()
                    }
                }
                SceneOverlay(scene, onDismiss = { finish() }, onRunTask = ::runTask, onSetVar = ::setVar)
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

/** The scene's modal content (scrim + scaled card). Shared by [SceneActivity] and the system-wide
 *  [SceneOverlayManager] window. */
@Composable
internal fun SceneOverlay(
    scene: Scene?,
    onDismiss: () -> Unit,
    onRunTask: (Long) -> Unit,
    onSetVar: (sceneProjectId: Long?, name: String, value: String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        if (scene == null) return@Box
        BoxWithConstraints {
            val availW = maxWidth.value
            val availH = maxHeight.value
            val sw = scene.widthDp.coerceAtLeast(1)
            val sh = scene.heightDp.coerceAtLeast(1)
            val scale = minOf(1f, (availW * 0.94f) / sw, (availH * 0.94f) / sh)
            Box(
                Modifier
                    .size((sw * scale).dp, (sh * scale).dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    // Absorb taps so tapping the scene (not the scrim) doesn't dismiss.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                scene.elements.forEach { element ->
                    Box(
                        Modifier
                            .offset((element.xDp * scale).dp, (element.yDp * scale).dp)
                            .size((element.widthDp * scale).dp, (element.heightDp * scale).dp),
                    ) {
                        SceneElementView(element, onRunTask) { name, value -> onSetVar(scene.projectId, name, value) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneElementView(
    element: SceneElement,
    onRunTask: (Long) -> Unit,
    onSetVar: (name: String, value: String) -> Unit,
) {
    val cfg = element.config
    fun v(key: String, fallback: String = "") = expandAgainstGlobals(cfg[key] ?: fallback)
    // Shared styling (see the element editor's Style section).
    val styleSize = cfg["textSize"]?.toIntOrNull()?.sp ?: TextUnit.Unspecified
    val styleWeight = if (sceneBool(cfg["bold"] ?: "")) FontWeight.Bold else FontWeight.Normal
    // For elements with a styled label (slider/checkbox/toggle) keep the label's own weight unless bold.
    val styleWeightOrNull = if (sceneBool(cfg["bold"] ?: "")) FontWeight.Bold else null
    val styleLabelColor = sceneColor(cfg["textColor"])
    val styleAlign = sceneAlign(cfg["align"])
    val styleBorderW = cfg["borderWidth"]?.toIntOrNull() ?: 0
    val styleBorderColor = sceneColor(cfg["borderColor"])
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
            // Initial value is expanded against globals, so `value: "%VOL"` starts the slider at the
            // live variable (e.g. the current volume, seeded by a Get Volume action before scene.show).
            var value by remember(element.id) { mutableStateOf((v("value").toFloatOrNull() ?: min).coerceIn(min, max)) }
            // On release: publish the settled value to `var` (if set), then run the tap task — which can
            // read that variable (e.g. a Set Volume action with level = %VOL).
            val onSettled: () -> Unit = {
                if (!varName.isNullOrBlank()) onSetVar(varName, value.roundToInt().toString())
                element.tapTaskId?.let(onRunTask)
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
                            onValueChange = { value = it },
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
                        onValueChange = { value = it },
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

        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(element.type.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Truthy parse for checkbox/toggle scene values. */
private fun sceneBool(s: String): Boolean = s.trim().lowercase() in setOf("true", "1", "on", "yes")

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
