package com.opentasker.scenes

import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.opentasker.core.shizuku.ShizukuShell
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.opentasker.ui.components.ThemedDropdownMenu
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
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

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
        // Huawei Mate XT: ONLY on the folded cover panel held in PORTRAIT, EMUI reserves a 105px system-bar
        // strip at the top and confines the wakedance mask/edge-light below it (real 1008x2232 but the app
        // area starts 105px down). Folded LANDSCAPE (2232x1008) is reported correctly; semi-folded (short
        // side 2048) and unfolded (2232) too — all excluded below. The window metrics already report the
        // real 2232 height, so we just pull the window UP 105px into the strip (no height change). Applied
        // only to the wakedance (showWhenLocked+fullscreen) so the screen-ON overlay blink is untouched. 白い熊
        if (showWhenLocked && fullscreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            val w = b.width()
            val h = b.height()
            val foldedCover = minOf(w, h) < 1500   // folded cover ≈ 1008; semi-folded 2048; unfolded 2232
            val portrait = h > w                    // folded LANDSCAPE is not misreported → skip
            if (foldedCover && portrait) {
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                window.attributes = window.attributes.apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = -105
                    width = w
                    height = h
                }
            }
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

        /** How many scene Activities are open right now — for the Monitor / shutdown inventory. */
        fun openCount(): Int = open.count { it.get() != null }

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
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cardWidthDp = maxWidth.value  // the card's actual width in dp (= the real screen width when fullWidth)
            scene.elements.forEach { element ->
                // `xr` = the element's LEFT x measured from the card's RIGHT edge (right-anchored). Lets a
                // fixed-width cluster hug the right of a full-width card, so it FILLS a folded screen and sits
                // at the right ~half of a wider one — without depending on the (foldable-broken) window gravity.
                val xr = element.config["xr"]?.trim()?.toFloatOrNull()
                // `xc` = position the element's align-anchor at (card CENTRE + xc), independent of card width.
                // With align=end the content's RIGHT edge lands at centre+xc; align=start its LEFT edge; else
                // its centre. Lets two halves straddle a fixed centre gap (e.g. a camera hole between 時/分).
                val xc = element.config["xc"]?.trim()?.toFloatOrNull()
                val xOffDp = when {
                    xc != null -> {
                        val half = cardWidthDp / 2f
                        when (element.config["align"]?.trim()?.lowercase()) {
                            "end", "right" -> -half + xc   // full-width box: content right edge = xOff + card = centre+xc
                            "center" -> xc                 // content centre = xOff + half = centre+xc
                            else -> half + xc              // start/default: content left edge = xOff = centre+xc
                        }
                    }
                    xr != null -> (cardWidthDp - xr)
                    else -> (element.xDp * scale)
                }
                // widthDp/heightDp <= 0 means "fill the card" — lets an element span a full-width bar scene.
                Box(
                    Modifier
                        .offset(xOffDp.dp, (element.yDp * scale).dp)
                        .then(if (element.widthDp > 0) Modifier.width((element.widthDp * scale).dp) else Modifier.fillMaxWidth())
                        .then(if (element.heightDp > 0) Modifier.height((element.heightDp * scale).dp) else Modifier.fillMaxHeight()),
                ) {
                    SceneElementView(element, onRunTask) { name, value -> onSetVar(scene.projectId, name, value) }
                }
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
    // Global appearance (used for oval-bar defaults when the element has no per-element override).
    val prefs by ThemeStore.state.collectAsState()
    val expandedCfg by remember(cfg) {
        derivedStateOf {
            revisionState.value // re-derive on any var change…
            cfg.mapValues { (k, raw) -> if (k == "html") raw else expandAgainstGlobals(raw) } // …but notify only if THIS element's expansion changed
        }
    }
    fun v(key: String, fallback: String = ""): String = expandedCfg[key] ?: expandAgainstGlobals(fallback)
    // Shared styling (see the element editor's Style section). Read via v() so it is %var-EXPANDED — font,
    // size, colour, alignment, border can all be driven by variables (e.g. a clock's %SC_* settings), not
    // just literals. A literal value expands to itself, so existing scenes are unaffected.
    val styleSize = v("textSize").toIntOrNull()?.sp ?: TextUnit.Unspecified
    val styleWeight = if (sceneBool(v("bold"))) FontWeight.Bold else FontWeight.Normal
    // For elements with a styled label (slider/checkbox/toggle) keep the label's own weight unless bold.
    val styleWeightOrNull = if (sceneBool(v("bold"))) FontWeight.Bold else null
    val styleLabelColor = sceneColor(v("textColor"))
    val styleAlign = sceneAlign(v("align"))
    val styleBorderW = v("borderWidth").toIntOrNull() ?: 0
    val styleBorderColor = sceneColor(v("borderColor"))
    // Optional per-character outline, shared by TEXT and BUTTON: a stroked copy of the glyphs drawn UNDER
    // the fill, so the characters get a border (e.g. black around yellow) and stay legible over any
    // background. strokeWidth is in PIXELS and the stroke is centred on the glyph path, so ~half of it
    // shows outside. Both knobs go through v(), so a %var can drive them (the 相撲字時計's %SC_StrokeColor
    // /%SC_StrokeW, the 音楽端灯 buttons' %Ongaku_Btnstrokecolor/%Ongaku_Btnstrokew).
    val styleStrokeColor = sceneColor(v("strokeColor"))
    val styleStrokeWpx = v("strokeWidth").toFloatOrNull()?.takeIf { it > 0f }
    // Optional custom font: an imported .ttf/.otf filename, OR a built-in keyword (serif/明朝, sans/ゴシック).
    val styleFont = v("font").trim().takeIf { it.isNotEmpty() }?.let { ThemeStore.fontFamily(it) }
    // Optional swipe target: a task id run when the element is dragged/slid (e.g. an edge-bar strip).
    val swipeTask = cfg["swipeTask"]?.trim()?.toLongOrNull()
    when (element.type) {
        SceneElementType.TEXT -> {
            val bg = sceneColor(v("bgColor"))
            val shape = RoundedCornerShape(8.dp)
            val annotated = sceneSpans(v("text"))
            val fillColor = sceneColor(v("textColor")) ?: MaterialTheme.colorScheme.onSurface
            val strokeColor = styleStrokeColor
            val strokeWpx = styleStrokeWpx
            Box(
                Modifier.fillMaxSize()
                    .then(if (bg != null) Modifier.background(bg, shape) else Modifier)
                    .then(if (styleBorderW > 0) Modifier.border(styleBorderW.dp, styleBorderColor ?: MaterialTheme.colorScheme.outline, shape) else Modifier)
                    // A TEXT carrying a tap task used to do NOTHING, silently: only BUTTON, the input
                    // widgets and RECTANGLE/OVAL ever read `tapTaskName`, so a scene author who bound a
                    // task to a text element got a dead element and no complaint from anywhere. 白い熊
                    // met it as an eighteen-card board where not one card responded (2026-08-28).
                    //
                    // Binding it here rather than telling scenes to use BUTTON instead: a button draws
                    // its label as a plain string, so it cannot carry the ⟦|30⟧…⟦/⟧ span markup that
                    // makes a card's glyph larger than its caption — and "the element you want is the
                    // one that silently ignores you" is not a rule worth keeping.
                    .then(
                        if (tapRef != null || longPressRef != null) {
                            Modifier.pointerInput(element.id) {
                                detectTapGestures(
                                    onTap = { tapRef?.let(onRunTask) },
                                    onLongPress = { longPressRef?.let(onRunTask) },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (strokeColor != null && strokeWpx != null) {
                    Text(
                        annotated,
                        color = strokeColor,
                        fontFamily = styleFont,
                        fontSize = styleSize,
                        fontWeight = styleWeight,
                        textAlign = styleAlign ?: TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                        style = TextStyle(drawStyle = Stroke(width = strokeWpx, join = StrokeJoin.Round)),
                    )
                }
                Text(
                    annotated,
                    color = fillColor,
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
            // Edge-bar long-swipe threshold (dp). Lives with the edge-bar project (画面操作), set in its 01 設定
            // task as %Longswipe_Dp (a 画面操作 project-global since the 2026-07-05 demotion; the old
            // super-global %LONGSWIPE_DP is kept as a fallback) — same pattern as %Pkey_Longms for 物理鍵.
            // A per-element `longSwipeDp` config still overrides it if present. Default 200dp (was a hardcoded
            // 140 — a normal swipe now stays "short" → Home instead of crossing into a long swipe → Recents).
            val longSwipePx = with(LocalDensity.current) {
                val cfgVal = v("longSwipeDp").trim().toIntOrNull()
                val globalVal = expandAgainstGlobals("%Longswipe_Dp").trim().toIntOrNull()
                    ?: expandAgainstGlobals("%LONGSWIPE_DP").trim().toIntOrNull()
                (cfgVal ?: globalVal ?: 200).coerceIn(60, 400).dp.toPx()
            }
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
                val label = v("label", "Button")
                // Same per-character outline as TEXT: the stroked copy first, the fill on top. Lets the
                // 音楽端灯 良/削 buttons carry the 相撲字時計 date's black glyph border (%Ongaku_Btnstrokecolor
                // /%Ongaku_Btnstrokew) so they read over album art of any colour.
                if (styleStrokeColor != null && styleStrokeWpx != null) {
                    Text(
                        label,
                        color = styleStrokeColor,
                        fontFamily = styleFont,
                        fontSize = styleSize,
                        fontWeight = styleWeight,
                        textAlign = styleAlign ?: TextAlign.Center,
                        style = TextStyle(drawStyle = Stroke(width = styleStrokeWpx, join = StrokeJoin.Round)),
                    )
                }
                Text(
                    label,
                    // Via styleLabelColor (v()-expanded) like every other element, so a %var can drive
                    // the colour/alpha live (e.g. the 音楽端灯 buttons' fade knob %Ongaku_Btncolor).
                    color = styleLabelColor ?: MaterialTheme.colorScheme.onPrimary,
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
            // Fill style (style:"fill"): a fat rounded-capsule bar that fills from the bottom (no thumb),
            // with a channel icon seated at the base. trackColor / fillColor / icon / iconColor / iconSize
            // are configurable; drag (or tap) anywhere on the bar sets the level. Inherently vertical.
            val styleFill = cfg["style"].equals("fill", ignoreCase = true)
            if (styleFill) {
                val trackColor = sceneColor(v("trackColor")) ?: Color(0xFF222222)
                val fillColor = sceneColor(v("fillColor")) ?: tint ?: Color(0xFFFFFF00)
                val iconSpec = cfg["icon"]?.trim()
                val iconColor = sceneColor(v("iconColor")) ?: trackColor
                val iconSz = cfg["iconSize"]?.toFloatOrNull() ?: 22f
                val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
                // Oval-bar outline (白い熊): each volume/brightness capsule gets a border whose default
                // width/colour come from the global UI settings (ovalBarBorderWidthDp/ovalBarBorderColor);
                // a per-element borderWidth/borderColor overrides them. Width 0 = no border.
                val barBorderW = if (styleBorderW > 0) styleBorderW else prefs.ovalBarBorderWidthDp
                val barBorderColor = styleBorderColor ?: Color(prefs.ovalBarBorderColor)
                fun valueAtY(y: Float, hPx: Int): Float =
                    min + (1f - (y / hPx.toFloat()).coerceIn(0f, 1f)) * (max - min)
                Box(
                    Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(trackColor)
                        .then(if (barBorderW > 0) Modifier.border(barBorderW.dp, barBorderColor, RoundedCornerShape(percent = 50)) else Modifier)
                        .pointerInput(element.id) {
                            detectTapGestures { off -> onChange(valueAtY(off.y, size.height)); onFinished() }
                        }
                        .pointerInput(element.id) {
                            detectDragGestures(
                                onDragStart = { off -> onChange(valueAtY(off.y, size.height)) },
                                onDrag = { ch, _ -> ch.consume(); onChange(valueAtY(ch.position.y, size.height)) },
                                onDragEnd = { onFinished() },
                                onDragCancel = { onFinished() },
                            )
                        },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // The fill is clipped to the capsule by the parent, so its bottom is rounded.
                    Box(Modifier.fillMaxWidth().fillMaxHeight(frac).background(fillColor))
                    val vec = sliderIcon(iconSpec)
                    Box(Modifier.fillMaxWidth().padding(bottom = 14.dp), contentAlignment = Alignment.BottomCenter) {
                        when {
                            vec != null -> Icon(vec, contentDescription = null, tint = iconColor, modifier = Modifier.size(iconSz.dp))
                            !iconSpec.isNullOrBlank() -> Text(iconSpec, color = iconColor, fontSize = iconSz.sp)
                        }
                    }
                }
            } else if (vertical) {
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
                ThemedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
            // v(), NOT the raw map: every other element expands its config through v() so a %variable
            // re-renders live on each change. Shapes read cfg[] directly, so a colour like
            // %Setsuzoku_CurColor resolved to the literal string, sceneColor() returned null and the
            // shape drew nothing — a per-SIM coloured icon was invisible while a literal-colour
            // sibling rendered fine.
            val fill = sceneColor(v("bgColor"))
            val shape = if (element.type == SceneElementType.OVAL) ovalShape
            else RoundedCornerShape((v("cornerRadius").toIntOrNull() ?: 0).dp)
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
            // Horizontal battery line. value=0..100 (a %var); the line keeps its state colour at all times
            // (yellow, or red-low / green-full) over an optional trackColor. The VISIBLE line is a thin strip
            // at the TOP (`barThickness` dp, default 3); the rest of the — deliberately taller — scene is
            // head-room for the charging effect's embers to fly into. While `charging` is truthy AND the
            // screen is on, two fire-comets glide in from both ends, meet in the middle and slide back, raining
            // red embers below the line (see [ChargingFlame]). It is gated on the screen so it recomputes ONLY
            // while visible — pulling the charger or blanking the screen drops it out of composition and the
            // animation clock stops dead (no off-screen CPU).
            val pct = ((v("value").toFloatOrNull() ?: 0f).coerceIn(0f, 100f)) / 100f
            val fillColor = sceneColor(v("fillColor")) ?: MaterialTheme.colorScheme.primary
            val trackColor = sceneColor(v("trackColor")) ?: Color.Transparent
            val charging = sceneBool(v("charging"))
            val screenOn = rememberScreenOn()
            val barThickness = (v("barThickness").toIntOrNull() ?: 3).dp
            // Charging-effect tunables, all %var-drivable from the scene config (see 電池線の設定 [01]):
            // flameCycle (s) = one converge-and-return breath; emberCount / glintCount per flame tip;
            // trailLinger (s) = how long the red heat-tint lingers on the bar (0 = no trail).
            val flameCycleMs = ((v("flameCycle").toFloatOrNull() ?: 3.8f) * 1000).toInt().coerceIn(800, 20000)
            val emberCount = (v("emberCount").toIntOrNull() ?: 14).coerceIn(0, 40)
            val glintCount = (v("glintCount").toIntOrNull() ?: 7).coerceIn(0, 20)
            val trailLingerMs = ((v("trailLinger").toFloatOrNull() ?: 1.3f) * 1000f).coerceIn(0f, 10000f)
            Box(Modifier.fillMaxSize().background(trackColor)) {
                // The battery-% column: centred horizontally, spanning the full (tall) height. It holds the
                // thin coloured bar at its top and the ember effect over the whole column.
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(pct)
                        .align(Alignment.Center),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(barThickness)
                            .background(fillColor),
                    )
                    if (charging && screenOn) {
                        ChargingFlame(Modifier.fillMaxSize(), barThickness, flameCycleMs, emberCount, glintCount, trailLingerMs)
                    }
                }
            }
        }

        SceneElementType.METEOR -> {
            // Legacy tombstone (2026-07-16): the 音楽端灯 edge meteors moved natively into 白い熊 音楽
            // (shiroikuma-ongaku) and the renderer/beat-source were removed from this app. The enum
            // value survives only so archived exports/backups containing METEOR elements still decode;
            // such an element renders nothing.
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
            // OPT-IN screen-off pause (config `pauseWhenScreenOff`=true). When set, the whole WebView is
            // paused while the display is off, so an rAF animation (the music edge-light) stops recomputing
            // behind a dark screen: WebView.onPause() halts the view's compositor/timers, and
            // window.__scenePlay(false) (if the page defines it) stops its own rAF loop from re-arming —
            // belt and suspenders. It is deliberately OPT-IN: the 通知明滅 edge-light and its over-lockscreen
            // wakedance draw *while the screen is off/waking*, so they must NOT be paused (they omit the flag).
            val pauseWhenScreenOff = sceneBool(v("pauseWhenScreenOff"))
            val screenOn = rememberScreenOn()
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        settings.javaScriptEnabled = true
                        // Links become actions. Without a client the WebView tries to NAVIGATE, which
                        // for a page loaded from a null base URL simply fails — so an HTML list could
                        // be shown but never acted on.
                        //
                        //   task://run?task=<name>&<var>=<value>…   set each extra query parameter as a
                        //                                           global, then run that task
                        //   anything else (geo:, tel:, https:, …)   handed to Android as a VIEW intent
                        //
                        // Variables are set BEFORE the task starts so it can read them as ordinary
                        // globals — the same contract a scene button has, plus arguments.
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: android.webkit.WebView,
                                request: android.webkit.WebResourceRequest,
                            ): Boolean {
                                val uri = request.url ?: return false
                                if (uri.scheme.equals("task", ignoreCase = true)) {
                                    val target = uri.getQueryParameter("task")?.trim().orEmpty()
                                    if (target.isEmpty()) return true
                                    uri.queryParameterNames
                                        .filter { it != "task" && it.isNotBlank() }
                                        .forEach { name -> onSetVar(name, uri.getQueryParameter(name).orEmpty()) }
                                    onRunTask(target)
                                    return true
                                }
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString()))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                                return true
                            }
                        }
                    }
                },
                update = { wv ->
                    if (wv.tag != html) {
                        wv.tag = html
                        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                    // Only scenes that opt in are gated on the screen; every other WEB scene is left exactly
                    // as before (no onPause/onResume, no JS injected — so 通知明滅 keeps drawing over the lock).
                    if (pauseWhenScreenOff) {
                        if (screenOn) {
                            wv.onResume()
                            wv.evaluateJavascript("window.__scenePlay&&window.__scenePlay(true)", null)
                        } else {
                            wv.evaluateJavascript("window.__scenePlay&&window.__scenePlay(false)", null)
                            wv.onPause()
                        }
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

/** Channel icon for a fill-style slider (config `icon`). Unknown names → null (the renderer then shows the
 *  raw `icon` string as a glyph). */
private fun sliderIcon(name: String?): ImageVector? = when (name?.trim()?.lowercase()) {
    "brightness", "light", "sun" -> Icons.Filled.LightMode
    "media", "music", "song", "note" -> Icons.Filled.MusicNote
    // The ringer and the notification stream are separate sliders on 音量パネル, so they must not share
    // the bell: ring = the incoming-call ringtone (handset under sound rays), notification = the bell.
    "ring", "ringer", "ringtone" -> Icons.Filled.RingVolume
    "notification", "notif", "bell" -> Icons.Filled.Notifications
    "alarm", "clock" -> Icons.Filled.Alarm
    // Likewise `call` is the in-call earpiece volume, drawn as a handset mid-conversation; `handset`
    // keeps the plain receiver reachable for scenes that mean "a phone call" generically.
    "call", "phone", "incall", "voicecall" -> Icons.Filled.PhoneInTalk
    "handset", "dialer" -> Icons.Filled.Call
    "mic", "microphone" -> Icons.Filled.Mic
    "volume", "vol" -> Icons.AutoMirrored.Filled.VolumeUp
    else -> null
}

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

/**
 * The battery-line "charging" indicator. Two glowing fire-comets glide in from the two ends of the line,
 * converge and meet in the middle (with a collision bloom), then slide back out — a slow, seamless
 * breathing cycle that never fully disappears. Each comet has a flickering white-hot head, a deep-red
 * blurred tail, and a fountain of red embers that spray up-and-out and rain down well below the thin line
 * (into the scene's deliberately tall head-room). Two out-of-phase flicker sines make it dance like fire.
 *
 * It is driven by [rememberInfiniteTransition], whose clock ticks ONLY while this composable is in
 * composition — and the caller composes it exclusively while `charging && screen-on`. So the moment the
 * charger is pulled or the screen sleeps, it leaves composition and the animation stops dead: nothing is
 * recomputed off-screen. Everything is derived from the animation phases (no state across frames).
 *
 * [barThickness] is the thin visible line's thickness; the comets ride along it (near the top of [modifier]).
 * [cycleMs] = one converge-and-return breath; [emberCount]/[glintCount] per flame tip; [trailMs] = the
 * heat-trail's linger time-constant in ms (0 disables the trail). All four map to %Denchi_* settings vars.
 */
@Composable
private fun ChargingFlame(
    modifier: Modifier,
    barThickness: Dp,
    cycleMs: Int = 3800,
    emberCount: Int = 14,
    glintCount: Int = 7,
    trailMs: Float = 1300f,
) {
    val tr = rememberInfiniteTransition(label = "denchiFlame")
    // Slow breathing convergence: a single linear phase mapped through a cosine so the ends (comets at the
    // edges) and the middle (comets meeting) are BOTH zero-velocity turnarounds → a seamless loop, no jump.
    val phase by tr.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(cycleMs, easing = LinearEasing), RepeatMode.Restart),
        label = "breath",
    )
    // A fast phase to flicker the flame brightness.
    val flick by tr.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(430, easing = LinearEasing), RepeatMode.Restart),
        label = "flicker",
    )
    // An independent looping driver for the ember fountain (staggered per spark → a continuous spray).
    val ember by tr.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "ember",
    )
    // A decaying "heat" field along the line: each comet deposits heat at its current position every frame,
    // and every bin cools exponentially — so where a comet just ran, the line glows red and lingers for
    // [trailMs] before fading back. Driven by real frame time in a LaunchedEffect that lives ONLY while
    // composed (charging && screen-on), so it stops dead off-screen. 64 bins across the line width.
    val heat = remember { FloatArray(64) }
    if (trailMs > 0f) LaunchedEffect(trailMs) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceIn(0f, 100f)
                last = now
                val decay = exp(-dt / trailMs)                 // heat time-constant (the "lingering")
                for (i in heat.indices) heat[i] *= decay
                val ss = (0.5 - 0.5 * cos(phase * 2.0 * Math.PI)).toFloat()
                depositHeat(heat, 0.5f * ss)                   // left comet head fraction
                depositHeat(heat, 1f - 0.5f * ss)              // right comet head fraction
            }
        }
    }
    Canvas(modifier.clipToBounds()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val lineHalf = (barThickness.toPx() / 2f).coerceAtMost(h / 2f)
        val lineCy = lineHalf                                  // the bar hugs the top edge; embers fall below
        val center = w / 2f
        // s: 0 at the two ends → 1 at the centre → 0 again (seamless because cos gives zero velocity at both).
        val s = (0.5 - 0.5 * cos(phase * 2.0 * Math.PI)).toFloat()
        val tail = (w * 0.45f).coerceAtLeast(h * 2f)
        val fallRange = (h - lineCy) * 0.96f                   // how far embers rain below the line
        val flicker = (0.72 + 0.28 * (0.5 + 0.5 * sin(flick.toDouble())) *
            (0.5 + 0.5 * sin(flick * 1.7 + 1.3))).toFloat()
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            // 0) lingering red heat-trail on the bar itself — drawn first so the comets/embers sit on top.
            val binW = w / heat.size
            val strip = lineHalf * 2f
            val tint = android.graphics.Paint()
            for (i in heat.indices) {
                val hv = heat[i]
                if (hv <= 0.02f) continue
                tint.color = 0xFFE01A18.toInt()                // deep red; blends over the line's own colour
                tint.alpha = (hv * 210f).toInt().coerceIn(0, 255)
                nc.drawRect(i * binW, 0f, (i + 1) * binW + 1f, strip, tint)
            }
            // left comet: head glides 0 → centre; right comet: head glides w → centre (mirror).
            drawDenchiComet(nc, s * center, 1f, tail, lineCy, lineHalf, fallRange, flicker, ember, 0.0, emberCount, glintCount)
            drawDenchiComet(nc, w - s * center, -1f, tail, lineCy, lineHalf, fallRange, flicker, ember, 3.3, emberCount, glintCount)
            // collision bloom where they meet in the middle (grows as s → 1) — deep-red/orange.
            val meet = s * s
            if (meet > 0.03f) {
                val r = (h * 0.85f).coerceAtLeast(lineHalf * 4f)
                val bloom = android.graphics.Paint().apply {
                    isAntiAlias = true
                    shader = android.graphics.RadialGradient(
                        center, lineCy, r,
                        intArrayOf(0xFFFFA84E.toInt(), 0xCCE52424.toInt(), 0x00A00010),
                        floatArrayOf(0f, 0.4f, 1f),
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                    alpha = (210 * meet * flicker).toInt().coerceIn(0, 255)
                }
                nc.drawCircle(center, lineCy, r, bloom)
            }
        }
    }
}

/** Deposit heat (up to 1.0, brightest at [frac]) into a few bins of the line's heat field around the
 *  fractional position [frac]∈[0,1]. Used by [ChargingFlame] to paint the comet's lingering trail. */
private fun depositHeat(heat: FloatArray, frac: Float) {
    val n = heat.size
    val c = frac * (n - 1)
    val radius = n * 0.035f + 1.2f
    val lo = maxOf(0, (c - radius).toInt())
    val hi = minOf(n - 1, (c + radius).toInt() + 1)
    for (i in lo..hi) {
        val v = (1f - abs(i - c) / radius).coerceIn(0f, 1f)
        if (v > heat[i]) heat[i] = v
    }
}

/**
 * Draw ONE converging fire-comet of the charging effect: a soft-blurred capsule body along the top line
 * (transparent tail → white-hot head), an incandescent head glow, and a fountain of red embers that spray
 * up-and-out from the head and arc down under "gravity". All positions are derived from the animation
 * phases (deterministic hashes for scatter) — no per-frame state. [dir] +1 = travelling right, -1 = left.
 */
private fun drawDenchiComet(
    nc: android.graphics.Canvas,
    headX: Float,
    dir: Float,
    tail: Float,
    lineCy: Float,
    lineHalf: Float,
    fallRange: Float,
    flicker: Float,
    ember: Float,
    seed: Double,
    emberCount: Int = 14,
    glintCount: Int = 7,
) {
    val tailX = headX - dir * tail
    val lft = minOf(headX, tailX)
    val rgt = maxOf(headX, tailX)
    val bodyH = (lineHalf * 1.6f).coerceAtLeast(1.5f)
    // 1) comet body — a deep, blood-red tail rising to a hot orange-red head (no white/amber), softly blurred.
    val body = android.graphics.Paint().apply {
        isAntiAlias = true
        shader = android.graphics.LinearGradient(
            tailX, 0f, headX, 0f,
            intArrayOf(0x00A00010, 0x55C2181B.toInt(), 0xCCE02020.toInt(), 0xFFFF3A1A.toInt(), 0xFFFF7A3A.toInt()),
            floatArrayOf(0f, 0.5f, 0.82f, 0.94f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        alpha = (255 * flicker).toInt().coerceIn(0, 255)
        maskFilter = android.graphics.BlurMaskFilter(bodyH * 0.7f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    nc.drawRoundRect(lft, lineCy - bodyH, rgt, lineCy + bodyH, bodyH, bodyH, body)
    // 2) head glow — hot orange-red core into deep red, fading to transparent dark red.
    val glowR = bodyH * 3.2f
    val glow = android.graphics.Paint().apply {
        isAntiAlias = true
        shader = android.graphics.RadialGradient(
            headX, lineCy, glowR,
            intArrayOf(0xFFFF8A40.toInt(), 0xE6E52020.toInt(), 0x00A00010),
            floatArrayOf(0f, 0.4f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        alpha = (255 * flicker).toInt().coerceIn(0, 255)
    }
    nc.drawCircle(headX, lineCy, glowR, glow)
    // 3) RED GLINTS at the tip — the "sparkle". Not a solid core (that read as a plain white dot): tiny
    //    star-crosses (two crossing strokes) around the head that flash in and out on a fast cycle, in
    //    bright red-orange. Twinkle comes from the rapid appear/disappear + the star shape, so it reads
    //    as sparkling while staying firmly RED.
    val glintPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeWidth = (lineHalf * 0.7f).coerceAtLeast(1.2f)
        style = android.graphics.Paint.Style.STROKE
    }
    for (i in 0 until glintCount) {
        // Each glint runs its own fast phase (~3 cycles per ember loop, offset per glint) and is only
        // visible near the peak of its cycle → a scatter of brief red flashes around the tip.
        val ph = ((ember * 3.0 + hashUnit(i * 7.31 + seed)) % 1.0).toFloat()
        val vis = sin(ph * Math.PI).toFloat().let { it * it }             // 0→1→0, sharpened
        if (vis < 0.3f) continue
        val dx = (hashUnit(i * 17.77 + seed * 3.1) - 0.5f) * bodyH * 6f
        val dy = (hashUnit(i * 29.53 + seed * 5.7) - 0.5f) * bodyH * 5f
        val gx = headX + dx
        val gy = (lineCy + dy).coerceAtLeast(0f)
        val len = bodyH * (0.7f + hashUnit(i * 43.19 + seed) * 1.1f) * vis
        // Bright red-orange, brighter at the flash peak — never white.
        glintPaint.color = if (vis > 0.75f) 0xFFFF5A28.toInt() else 0xFFE01A18.toInt()
        glintPaint.alpha = (255 * vis * flicker).toInt().coerceIn(0, 255)
        nc.drawLine(gx - len, gy, gx + len, gy, glintPaint)               // ─ ray
        nc.drawLine(gx, gy - len, gx, gy + len, glintPaint)               // │ ray → a + star
    }
    // 4) ember burst — 14 red sparks fired in ALL directions from the tip (bright red-orange at birth,
    //    cooling to deep crimson), then pulled down by "gravity" so they arc and rain below the line.
    val twoPi = 6.2831855f
    val spark = android.graphics.Paint().apply { isAntiAlias = true }
    for (i in 0 until emberCount) {
        val la = ((ember + i * 0.6180339887 + seed) % 1.0).toFloat()      // 0..1 life, golden-ratio staggered
        val h1 = hashUnit(i * 12.9898 + seed * 78.233)                    // angle hash
        val h2 = hashUnit(i * 39.425 + seed * 93.7)                       // reach hash
        val ang = h1 * twoPi                                              // full circle → sparks all directions
        val dist = fallRange * la * (0.45f + 0.55f * h2)
        val sx = headX + cos(ang.toDouble()).toFloat() * dist * 0.75f + dir * fallRange * 0.12f * la
        val sy = lineCy + sin(ang.toDouble()).toFloat() * dist * 0.6f + fallRange * 0.7f * la * la  // radial + gravity
        val sr = (lineHalf * 1.5f * (1f - la)).coerceAtLeast(0.8f)
        spark.color = emberColor(la)
        spark.alpha = (255 * flicker * (1f - la) * (if (la < 0.1f) la / 0.1f else 1f)).toInt().coerceIn(0, 255)
        nc.drawCircle(sx, sy, sr, spark)
    }
}

/** Deterministic hash → [0,1) from a seed (classic fract(sin·k)); used to scatter sparks reproducibly. */
private fun hashUnit(x: Double): Float {
    val v = sin(x) * 43758.5453
    return (v - kotlin.math.floor(v)).toFloat()
}

/** Ember colour ramp — RED throughout (no white; 白い熊: the white tip read as a plain dot): bright
 *  red-orange at birth → red → deep crimson as it ages. Returns an opaque ARGB int (the caller overrides
 *  the alpha to fade). */
private fun emberColor(la: Float): Int {
    val r: Int
    val g: Int
    val b: Int
    if (la < 0.3f) {
        val t = la / 0.3f
        r = 255; g = (130 - 60 * t).toInt(); b = (60 - 40 * t).toInt()      // bright red-orange → red
    } else {
        val t = ((la - 0.3f) / 0.7f).coerceIn(0f, 1f)
        r = (255 - 115 * t).toInt(); g = (70 - 55 * t).toInt(); b = (20 - 12 * t).toInt()  // red → deep crimson
    }
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
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
// Inline font/size/rise spans for a scene TEXT: `⟦font|size|rise⟧text⟦/⟧` sets font (imported name or a
// serif/明朝 · sans keyword), size, and an optional baseline RISE for the wrapped run; text outside spans
// keeps the element's own font/size. `rise` (fraction, e.g. 0.35) lifts a smaller span so its TOP lines up
// with a bigger neighbour instead of sitting on the shared baseline (top-align vs baseline-align). Any
// field may be blank to inherit; the third is optional. A plain string with no ⟦ is returned as-is.
private val SCENE_SPAN_RE = Regex("⟦([^|⟧]*)\\|([^|⟧]*)(?:\\|([^⟧]*))?⟧(.*?)⟦/⟧", RegexOption.DOT_MATCHES_ALL)

private fun sceneSpans(text: String): AnnotatedString {
    if ('⟦' !in text) return AnnotatedString(text)
    return buildAnnotatedString {
        var last = 0
        for (m in SCENE_SPAN_RE.findAll(text)) {
            if (m.range.first > last) append(text.substring(last, m.range.first))
            val fam = m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { ThemeStore.fontFamily(it) }
            val size = m.groupValues[2].trim().toFloatOrNull()
            val rise = m.groupValues[3].trim().toFloatOrNull()
            withStyle(
                SpanStyle(
                    fontFamily = fam,
                    fontSize = size?.sp ?: TextUnit.Unspecified,
                    baselineShift = rise?.let { BaselineShift(it) } ?: BaselineShift.None,
                )
            ) {
                append(m.groupValues[4])
            }
            last = m.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }
}

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
