package com.opentasker.scenes

import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.opentasker.core.shizuku.ShizukuShell
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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.draggable2D
import androidx.compose.foundation.gestures.rememberDraggable2DState
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.PowerManager
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
import com.opentasker.core.engine.resolveTaskByName
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Runtime display of a [Scene]: a modal overlay (scrim + the scene laid out by its elements'
 * dp positions, scaled to fit). Element `%vars` are expanded against the persisted globals, and
 * tap / long-press run the element's tasks. Shown by the `scene.show` action; dismissed by tapping
 * the scrim, back, or the `scene.hide` action.
 */
open class SceneActivity : ComponentActivity() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val showWhenLocked = intent.getBooleanExtra(EXTRA_SHOW_WHEN_LOCKED, false)
        val fullscreen = intent.getBooleanExtra(EXTRA_FULLSCREEN, false)
        if (showWhenLocked) {
            // Show over the keyguard. A SCREEN_BRIGHT wakelock — acquired AFTER the scene draws (so no
            // lockscreen flash) — wakes the screen AND keeps it on for the whole rotation; without it EMUI
            // tears the occluding Activity down after ~2s (which capped the wakedance). Released on finish.
            // (FLAG_KEEP_SCREEN_ON + a keyevent wake didn't hold the screen on EMUI; a real wakelock does.)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
            }
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
            // The Activity theme is translucent (for modal scenes) — paint the decor opaque black so the
            // live wallpaper never flashes through during the launch/finish transitions.
            window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
            // Edge-to-edge + hide the system bars so the black mask covers the status/nav bar too.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            val holdMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, 0L).let { if (it > 0) it + 1000 else 5000L }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "shiroikuma:wakedance",
            )
            window.decorView.postDelayed({
                runCatching { wakeLock?.acquire(holdMs) }
            }, 450) // let the black scene draw over the keyguard before the wakelock lights it → no flash
        }
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
                // Auto-dismiss timeout (the wakedance relies on this to close — scene.hide with a name
                // only hides overlays, not this Activity).
                if (timeoutMs > 0) {
                    LaunchedEffect(sceneId) {
                        kotlinx.coroutines.delay(timeoutMs)
                        if (showWhenLocked) {
                            // Sleep the screen OURSELVES (wakelock released first) so the lockscreen never
                            // flashes between this Activity finishing and a separate screen.off.
                            runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
                            withContext(Dispatchers.IO) { runCatching { ShizukuShell.exec("input keyevent 223") } }
                            // Wait for the display to actually power down before finishing — otherwise the
                            // Activity tears down while the screen is still on, flashing the wallpaper.
                            kotlinx.coroutines.delay(450)
                        }
                        finish()
                    }
                }
                // showWhenLocked scenes render fullscreen (the wakedance black mask fills the screen);
                // the normal Activity fallback is modal (a scrim + scaled card).
                if (fullscreen) {
                    SceneOverlay(scene, modal = false, fullscreen = true, onDismiss = { finish() }, onRunTask = { ref -> runTask(ref, scene?.projectId) }, onSetVar = ::setVar)
                } else {
                    SceneOverlay(scene, modal = true, position = position, dismissOnOutside = dismissOnOutside, onDismiss = { finish() }, onRunTask = { ref -> runTask(ref, scene?.projectId) }, onSetVar = ::setVar)
                }
            }
        }
    }

    private fun runTask(ref: String, projectId: Long?) {
        io.launch {
            val db = OpenTaskerApp_NoHilt.db
            // Name-first (the element carries the task NAME; the id is only a legacy fallback).
            val task = resolveTaskByName(db, ref, projectId) ?: return@launch
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
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        open.removeAll { it.get() == null || it.get() === this }
    }

    companion object {
        const val EXTRA_SCENE_ID = "com.opentasker.scenes.SCENE_ID"
        const val EXTRA_POSITION = "com.opentasker.scenes.POSITION"
        const val EXTRA_TIMEOUT_MS = "com.opentasker.scenes.TIMEOUT_MS"
        const val EXTRA_DISMISS_OUTSIDE = "com.opentasker.scenes.DISMISS_OUTSIDE"
        const val EXTRA_SHOW_WHEN_LOCKED = "com.opentasker.scenes.SHOW_WHEN_LOCKED"
        const val EXTRA_FULLSCREEN = "com.opentasker.scenes.FULLSCREEN"
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
    fillHeight: Boolean = false,
    fillWidth: Boolean = false,
    onDismiss: () -> Unit,
    onRunTask: (String) -> Unit,
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
        SceneCard(scene, scale = 1f, absorbTaps = false, fullWidth = fullWidth, fullscreen = fullscreen, fillHeight = fillHeight, fillWidth = fillWidth, onRunTask = onRunTask, onSetVar = onSetVar)
    }
}

@Composable
private fun SceneCard(
    scene: Scene,
    scale: Float,
    absorbTaps: Boolean,
    fullWidth: Boolean = false,
    fullscreen: Boolean = false,
    fillHeight: Boolean = false,
    fillWidth: Boolean = false,
    onRunTask: (String) -> Unit,
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
                // fillHeight (a side edge strip): keep the configured width, fill the fraction-height window.
                else if (fillHeight) Modifier.width((sw * scale).dp).fillMaxHeight()
                // fillWidth (a bottom edge strip): keep the configured height, fill the fraction-width window.
                else if (fillWidth) Modifier.fillMaxWidth().height((sh * scale).dp)
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
    onRunTask: (String) -> Unit,
    onSetVar: (name: String, value: String) -> Unit,
) {
    val cfg = element.config
    // Task links resolve NAME-first: prefer the element's stored task name, fall back to the legacy id.
    // Gesture-config values (swipeUp, doubleTap, …) are passed through as-is — a name (new) or an id
    // string (legacy); runTask's resolver tries name then id, so both keep working.
    fun taskRef(name: String, id: Long?): String? = name.ifBlank { id?.toString() ?: "" }.ifBlank { null }
    val tapRef = taskRef(element.tapTaskName, element.tapTaskId)
    val longPressRef = taskRef(element.longPressTaskName, element.longPressTaskId)
    // Expand each config value against the globals via derivedStateOf, so this element recomposes ONLY
    // when one of ITS OWN variables changes — not on every global write. (Before, every element read a
    // shared revision, so any var change re-ran every on-screen overlay at once — a real idle CPU cost
    // with many overlays up.) "html" stays raw (it's large and the WebView reads/expands it itself).
    val revisionState = PersistentGlobalScope.revision.collectAsState()
    val expandedCfg by remember(cfg) {
        derivedStateOf {
            revisionState.value // re-derive on any var change…
            cfg.mapValues { (k, raw) -> if (k == "html") raw else expandAgainstGlobals(raw) } // …but notify only if THIS element's expansion changed
        }
    }
    fun v(key: String, fallback: String = ""): String = expandedCfg[key] ?: expandAgainstGlobals(fallback)
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
    // Optional swipe target: a task id run when the element is dragged/slid (e.g. an edge-bar strip).
    val swipeTask = cfg["swipeTask"]?.trim()?.toLongOrNull()
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

        SceneElementType.BUTTON -> {
            // Edge bars: a strip binds any of swipe up/down/left/right (short) and longSwipe* (a longer
            // drag in that direction), plus tap, double-tap and long-press — each to its own task.
            // Gesture targets: a task NAME (new) or a legacy id string — runTask resolves either.
            fun cfgRef(key: String): String? = cfg[key]?.trim()?.takeIf { it.isNotBlank() }
            val swipeUp = cfgRef("swipeUp")
            val swipeDown = cfgRef("swipeDown")
            val swipeLeft = cfgRef("swipeLeft")
            val swipeRight = cfgRef("swipeRight")
            val longSwipeUp = cfgRef("longSwipeUp")
            val longSwipeDown = cfgRef("longSwipeDown")
            val longSwipeLeft = cfgRef("longSwipeLeft")
            val longSwipeRight = cfgRef("longSwipeRight")
            val doubleTapId = cfgRef("doubleTap")
            val moveDebug = cfgRef("moveDebug")   // DEBUG: fires once on the first pointer move
            val tapId = tapRef
            val longPressId = longPressRef
            val hasSwipe = swipeUp != null || swipeDown != null || swipeLeft != null || swipeRight != null ||
                longSwipeUp != null || longSwipeDown != null || longSwipeLeft != null || longSwipeRight != null ||
                moveDebug != null
            val slopPx = with(LocalDensity.current) { 36.dp.toPx() }
            val longSwipePx = with(LocalDensity.current) { 140.dp.toPx() }
            // Pick the task for the swipe's dominant axis from a (up,down,left,right) set.
            fun pick(dx: Float, dy: Float, up: String?, down: String?, left: String?, right: String?): String? =
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) { if (dx > 0) right else left }
                else { if (dy > 0) down else up }
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    // bgColor read via v() so a (debug) task can flip an edge strip visible/invisible live.
                    .background(sceneColor(v("bgColor")) ?: MaterialTheme.colorScheme.primary)
                    .then(if (styleBorderW > 0) Modifier.border(styleBorderW.dp, styleBorderColor ?: MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)) else Modifier)
                    // Swipe or double-tap bound → the full edge-gesture detector (it consumes the down so
                    // the tap-through overlay claims the move stream). Otherwise the lightweight tap
                    // detector, so a plain button's tap stays instant (no double-tap wait).
                    .then(if (hasSwipe || doubleTapId != null) Modifier.pointerInput(element.id) {
                        detectEdgeGestures(
                            slopPx = slopPx,
                            longSwipePx = longSwipePx,
                            onSwipe = { dx, dy -> pick(dx, dy, swipeUp, swipeDown, swipeLeft, swipeRight)?.let(onRunTask) },
                            onLongSwipe = { dx, dy ->
                                val t = pick(dx, dy, longSwipeUp, longSwipeDown, longSwipeLeft, longSwipeRight)
                                t?.let(onRunTask); t != null
                            },
                            onTap = tapId?.let { id -> { onRunTask(id) } },
                            onDoubleTap = doubleTapId?.let { id -> { onRunTask(id) } },
                            onLongPress = longPressId?.let { id -> { onRunTask(id) } },
                            onFirstMove = moveDebug?.let { id -> { onRunTask(id) } },
                        )
                    }
                    else if (tapId != null || longPressId != null) Modifier.pointerInput(element.id) {
                        detectTapGestures(
                            onTap = { tapId?.let(onRunTask) },
                            onLongPress = { longPressId?.let(onRunTask) },
                        )
                    } else Modifier),
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
        }

        SceneElementType.SLIDER -> {
            val min = cfg["min"]?.toFloatOrNull() ?: 0f
            val max = (cfg["max"]?.toFloatOrNull() ?: 100f).coerceAtLeast(min + 1f)
            val varName = cfg["var"]?.trim()
            val vertical = cfg["orientation"].equals("vertical", ignoreCase = true)
            // live: also commit (set var + run task) on every integer step during the drag, not just on
            // release — so a volume/brightness slider applies as you slide it.
            val live = sceneBool(cfg["live"] ?: "")
            // swipeOnly: ignore the initial press (a tap) and only fire once the drag moves — so a tap
            // doesn't trigger the task (used by the edge-swipe strips, which want slide-only).
            val swipeOnly = sceneBool(cfg["swipeOnly"] ?: "")
            // tint: a (possibly %var) colour for the whole slider; transparent = invisible. Read via v()
            // so a debug task can flip it visible at runtime.
            val tint = if (cfg["tint"] != null) sceneColor(v("tint")) else null
            // Initial value is expanded against globals, so `value: "%VOL"` starts the slider at the
            // live variable (e.g. the current volume, seeded by a Get Volume action before scene.show).
            var value by remember(element.id) { mutableStateOf((v("value").toFloatOrNull() ?: min).coerceIn(min, max)) }
            // On release: publish the settled value to `var` (if set), then run the tap task — which can
            // read that variable (e.g. a Set Volume action with level = %VOL).
            val onSettled: () -> Unit = {
                if (!varName.isNullOrBlank()) onSetVar(varName, value.roundToInt().toString())
                tapRef?.let(onRunTask)
            }
            var lastSent by remember(element.id) { mutableStateOf(Int.MIN_VALUE) }
            var moved by remember(element.id) { mutableStateOf(false) }
            val onChange: (Float) -> Unit = { f ->
                val firstOfGesture = !moved   // the first change of a gesture is the press position (a tap)
                moved = true
                value = f
                if (live && !(swipeOnly && firstOfGesture)) {
                    val r = value.roundToInt()
                    if (r != lastSent) { lastSent = r; onSettled() }
                }
            }
            // Reset the gesture tracker on release; a non-swipeOnly slider also commits its final value.
            val onFinished: () -> Unit = { moved = false; if (!swipeOnly) onSettled() }
            val sliderColors = tint?.let { SliderDefaults.colors(thumbColor = it, activeTrackColor = it, inactiveTrackColor = it) }
            val label = v("label", "Slider")
            if (vertical) {
                // A horizontal Slider rotated 90° CCW: its track length follows the element height, so
                // the top is max and dragging up increases the value.
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelMedium, color = styleLabelColor ?: MaterialTheme.colorScheme.onSurface, fontSize = styleSize, fontFamily = styleFont, fontWeight = styleWeightOrNull)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val trackLength = maxHeight
                        Slider(
                            value = value,
                            onValueChange = onChange,
                            onValueChangeFinished = onFinished,
                            valueRange = min..max,
                            colors = sliderColors ?: SliderDefaults.colors(),
                            // requiredWidth ignores the (narrow) element width so the rotated track can
                            // span the full element height instead of being clamped to its width.
                            modifier = Modifier.requiredWidth(trackLength).rotate(-90f),
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelMedium, color = styleLabelColor ?: MaterialTheme.colorScheme.onSurface, fontSize = styleSize, fontFamily = styleFont, fontWeight = styleWeightOrNull)
                    Slider(
                        value = value,
                        onValueChange = onChange,
                        onValueChangeFinished = onFinished,
                        valueRange = min..max,
                        colors = sliderColors ?: SliderDefaults.colors(),
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
                keyboardActions = KeyboardActions(onDone = { tapRef?.let(onRunTask) }),
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { st ->
                        if (focused && !st.isFocused) tapRef?.let(onRunTask)
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
                tapRef?.let(onRunTask)
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
                            tapRef?.let(onRunTask)
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
                tapRef?.let(onRunTask)
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
                        if (tapRef != null || longPressRef != null) {
                            Modifier.pointerInput(element.id) {
                                detectTapGestures(
                                    onTap = { tapRef?.let(onRunTask) },
                                    onLongPress = { longPressRef?.let(onRunTask) },
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
            // Horizontal fill bar (e.g. the battery line). value=0..100 (a %var); fillColor over trackColor.
            // While `charging` is truthy the whole filled bar turns SOLID RED — a static, unmistakable
            // charging indicator (no animation, so it's free to keep up the whole time you're plugged in;
            // a faint tint was too subtle and a 5 fps sweep cost ~5% CPU in the overlay).
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
                        .background(if (charging) Color.Red else fillColor),
                )
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

/**
 * One detector for an edge strip's whole gesture set: 4-direction swipe, tap, double-tap, long-press.
 * It consumes the DOWN so a FLAG_NOT_FOCUSABLE tap-through overlay actually claims the move stream
 * (without that the window slips moves away and only taps survive). A drag past [slopPx] fires [onSwipe]
 * with the accumulated delta; holding past the long-press timeout fires [onLongPress]; a quick release
 * fires [onTap] — unless [onDoubleTap] is bound and a second tap lands within the double-tap window.
 * Single taps stay instant when no double-tap is bound (no wait).
 */
private suspend fun PointerInputScope.detectEdgeGestures(
    slopPx: Float,
    longSwipePx: Float,
    onSwipe: (Float, Float) -> Unit,
    onLongSwipe: (Float, Float) -> Boolean,
    onTap: (() -> Unit)?,
    onDoubleTap: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
    onFirstMove: (() -> Unit)? = null,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        var dx = 0f
        var dy = 0f
        var firstMoveSeen = false
        // Only arm the long-press clock when a long-press is bound, so otherwise a held press just waits
        // for release (a tap) rather than being swallowed as a no-op long-press.
        val timeout = if (onLongPress != null) viewConfiguration.longPressTimeoutMillis else Long.MAX_VALUE
        // "swipe" = moved past slop; "tap" = released first; null = timed out (held) → long-press.
        val phase = withTimeoutOrNull(timeout) {
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull "tap"
                if (!ch.pressed) return@withTimeoutOrNull "tap"
                val pc = ch.positionChange(); dx += pc.x; dy += pc.y; ch.consume()
                if (!firstMoveSeen && (pc.x != 0f || pc.y != 0f)) { firstMoveSeen = true; onFirstMove?.invoke() }
                if (kotlin.math.abs(dx) > slopPx || kotlin.math.abs(dy) > slopPx) return@withTimeoutOrNull "swipe"
            }
            @Suppress("UNREACHABLE_CODE") "tap"
        }
        when (phase) {
            "swipe" -> {
                // Keep tracking: cross the long threshold → fire the long-swipe (if one is bound for this
                // direction); otherwise fire the short swipe on release. So a quick flick = short, a long
                // drag = long, and a long drag with no long binding falls back to the short task.
                var firedLong = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val pc = ch.positionChange(); dx += pc.x; dy += pc.y; ch.consume()
                    if (!firedLong && (kotlin.math.abs(dx) > longSwipePx || kotlin.math.abs(dy) > longSwipePx)) {
                        firedLong = onLongSwipe(dx, dy)
                    }
                }
                if (!firedLong) onSwipe(dx, dy)
            }
            null -> { onLongPress?.invoke(); drainPressed(down.id) }
            else -> {
                if (onDoubleTap != null) {
                    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                        awaitFirstDown(requireUnconsumed = false)
                    }
                    if (second != null) { second.consume(); onDoubleTap(); drainPressed(second.id) }
                    else onTap?.invoke()
                } else {
                    onTap?.invoke()
                }
            }
        }
    }
}

/** Consume the rest of a claimed gesture until the pointer lifts (so it doesn't leak to anything else). */
private suspend fun AwaitPointerEventScope.drainPressed(id: PointerId) {
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == id } ?: return
        if (!ch.pressed) return
        ch.consume()
    }
}

/** Tracks the screen on/off state via the system broadcasts, so an overlay animation can pause itself
 *  while the display is off (where it would otherwise burn CPU drawing something nobody can see). */
@Composable
private fun rememberScreenOn(): Boolean {
    val context = LocalContext.current
    var on by remember {
        mutableStateOf((context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive)
    }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                on = i?.action != Intent.ACTION_SCREEN_OFF
            }
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return on
}

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
