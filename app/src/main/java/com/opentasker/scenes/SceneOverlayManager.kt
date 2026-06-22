package com.opentasker.scenes

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.accessibility.ShiroiKumaAccessibilityService
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.engine.variables.PersistentGlobalScope
import com.opentasker.core.model.Scene
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Shows a [Scene] as a system-wide overlay window (drawn over other apps) using `WindowManager` and a
 * `ComposeView`, rather than the foreground-only [SceneActivity]. Requires the "Display over other
 * apps" permission ([canOverlay]); the `scene.show` action falls back to [SceneActivity] without it.
 *
 * A modal overlay is focusable so buttons/sliders/toggles (and Back to dismiss) work and an EDIT_TEXT
 * can raise the soft keyboard (ADJUST_RESIZE). A non-modal HUD is tap-through (not focusable), so it
 * can't take text input — use a modal scene for fields.
 */
object SceneOverlayManager {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val active = LinkedHashMap<Long, Overlay>()
    private val shownNames = LinkedHashMap<Long, String>() // sceneId -> name, for the monitor view
    private var appContext: Context? = null

    /** Names of the scenes currently displayed as overlays — what's actually on screen right now. */
    fun shownSceneNames(): List<String> = ArrayList(shownNames.values)

    private class Overlay(
        val view: ComposeView,
        val owner: OverlayLifecycleOwner,
        val params: WindowManager.LayoutParams,
        val wm: WindowManager,
        val fullscreen: Boolean,
        val heightFraction: Float = 0f,
        val widthFraction: Float = 0f,
        val timeoutMs: Long = 0L,
        var dismissRunnable: Runnable? = null,
    )

    /** Restart the auto-dismiss countdown of every timed overlay (called when a panel is interacted with). */
    private fun resetTimeouts() {
        active.values.forEach { ov ->
            val r = ov.dismissRunnable ?: return@forEach
            if (ov.timeoutMs > 0) { main.removeCallbacks(r); main.postDelayed(r, ov.timeoutMs) }
        }
    }

    // Re-size fullscreen overlays when the display geometry changes (fold/unfold, rotation) — they are
    // pinned to an explicit pixel size, so they don't auto-track like MATCH_PARENT would.
    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null

    fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context.applicationContext)

    private fun realMetrics(wm: WindowManager): android.util.DisplayMetrics =
        android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }

    private fun resizeDynamicOverlays() {
        val ctx = appContext ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val real = realMetrics(wm)
        active.values.forEach { ov ->
            when {
                ov.fullscreen ->
                    if (ov.params.width != real.widthPixels || ov.params.height != real.heightPixels) {
                        ov.params.width = real.widthPixels
                        ov.params.height = real.heightPixels
                        runCatching { ov.wm.updateViewLayout(ov.view, ov.params) }
                    }
                // A fraction-height edge strip re-sizes to its fraction of the new screen height.
                ov.heightFraction > 0f -> {
                    val h = (ov.heightFraction * real.heightPixels).toInt()
                    if (ov.params.height != h) { ov.params.height = h; runCatching { ov.wm.updateViewLayout(ov.view, ov.params) } }
                }
                // A fraction-width (bottom) strip re-sizes to its fraction of the new screen width.
                ov.widthFraction > 0f -> {
                    val w = (ov.widthFraction * real.widthPixels).toInt()
                    if (ov.params.width != w) { ov.params.width = w; runCatching { ov.wm.updateViewLayout(ov.view, ov.params) } }
                }
            }
        }
    }

    private fun ensureDisplayListener(app: Context) {
        if (displayListener != null) return
        val dm = app.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return
        val l = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                // The metrics can lag the callback slightly on foldables; re-apply now and once more shortly.
                resizeDynamicOverlays()
                main.postDelayed({ resizeDynamicOverlays() }, 150)
            }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        dm.registerDisplayListener(l, main)
        displayListener = l
    }

    /**
     * Show [scene] as an overlay (no-op if it's already showing). [modal] dims + blocks the app
     * underneath (focusable, tap scrim/Back to dismiss); non-modal is a tap-through HUD sized to the
     * card and placed by [position] ("top"/"center"/"bottom"). [timeoutMs] > 0 auto-dismisses.
     * Safe to call from any thread.
     */
    fun show(context: Context, scene: Scene, position: String? = null, modal: Boolean = true, timeoutMs: Long = 0L, dismissOnOutside: Boolean = true, fullWidth: Boolean = false, fullscreen: Boolean = false, edgeCenter: Boolean = false, insetDp: Int = 0, heightFraction: Float = 0f, vAlign: String? = null, widthFraction: Float = 0f, hAlign: String? = null, showWhenLocked: Boolean = false) {
        val app = context.applicationContext
        main.post {
            appContext = app
            if (active.containsKey(scene.id)) return@post
            // The bottom edge bar (widthFraction) is routed through the accessibility service as a
            // TYPE_ACCESSIBILITY_OVERLAY when it's enabled: that captures the bottom system gesture the
            // OS otherwise pilfers WITHOUT taking key focus (so the app's keyboard keeps working). When
            // the service is off, it falls back to a focusable app overlay (captures, but holds focus).
            val a11y = if (widthFraction > 0f) ShiroiKumaAccessibilityService.service else null
            val wm = (a11y ?: app).getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val owner = OverlayLifecycleOwner().apply { onCreate() }
            val composeView = ComposeView(a11y ?: app).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                if (modal) {
                    isFocusableInTouchMode = true
                    setOnKeyListener { _, keyCode, event ->
                        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                            hide(scene.id); true
                        } else false
                    }
                } else if (dismissOnOutside) {
                    // A tap-through panel: a tap outside it (delivered via FLAG_WATCH_OUTSIDE_TOUCH as
                    // ACTION_OUTSIDE) closes it. Inside touches are consumed by Compose, so they don't fire here.
                    setOnTouchListener { _, event ->
                        if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) { hide(scene.id); true } else false
                    }
                }
                setContent {
                    val prefs by ThemeStore.state.collectAsState()
                    OpenTaskerTheme(prefs) {
                        SceneOverlay(
                            scene = scene,
                            modal = modal,
                            position = sceneAlignment(position),
                            dismissOnOutside = dismissOnOutside,
                            fullWidth = fullWidth,
                            fullscreen = fullscreen,
                            fillHeight = heightFraction > 0f,
                            fillWidth = widthFraction > 0f,
                            onDismiss = { hide(scene.id) },
                            onRunTask = ::runTask,
                            onSetVar = ::setVar,
                        )
                    }
                }
            }
            val type = when {
                a11y != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = if (modal) {
                // Full-screen, focusable: the scrim blocks the app underneath; the card is placed by
                // the composable's [position] alignment. Focusable (no FLAG_NOT_FOCUSABLE) + ADJUST_RESIZE
                // lets an EDIT_TEXT inside the scene raise the soft keyboard and stay visible above it.
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                }
            } else {
                // Tap-through HUD: wrap the card, not-focusable so touches outside it reach the app,
                // placed by window gravity.
                // fullscreen: cover the whole screen and pass ALL touches through (a purely visual
                // edge-light); fullWidth: span the screen over the status bar; else wrap the card.
                val edgeFlags = when {
                    fullscreen -> WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    fullWidth -> WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    // Edge strips (fraction-height side / fraction-width bottom) extend to the TRUE
                    // screen edge — over the status/nav-bar insets — so a swipe right on the edge lands
                    // on the strip, not the app behind it (no mini-gap).
                    heightFraction > 0f || widthFraction > 0f ->
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    else -> 0
                }
                // For fullscreen, size to the REAL physical display at (0,0): MATCH_PARENT gives the
                // (mis)reported content area on some foldables (Huawei folded), leaving a gap under the
                // status bar. Pinning TOP|START at the real size covers the whole screen, edge to edge.
                val real = realMetrics(wm)
                WindowManager.LayoutParams(
                    when {
                        fullscreen -> real.widthPixels
                        fullWidth -> WindowManager.LayoutParams.MATCH_PARENT
                        widthFraction > 0f -> (widthFraction * real.widthPixels).toInt()
                        else -> WindowManager.LayoutParams.WRAP_CONTENT
                    },
                    when {
                        fullscreen -> real.heightPixels
                        heightFraction > 0f -> (heightFraction * real.heightPixels).toInt()
                        else -> WindowManager.LayoutParams.WRAP_CONTENT
                    },
                    type,
                    // On the accessibility overlay the bottom bar stays NOT_FOCUSABLE (it captures the
                    // gesture without holding key focus, so the keyboard works). Without the service it
                    // falls back to a focusable window (NOT_TOUCH_MODAL) — captures, but holds focus.
                    (if (widthFraction > 0f && a11y == null) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or edgeFlags or
                        (if (dismissOnOutside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0),
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = if (fullscreen) Gravity.TOP or Gravity.START else sceneGravity(position)
                    val pos = position?.trim()?.lowercase()
                    when {
                        fullscreen -> { x = 0; y = 0 }
                        // Edge HUDs (the music 良/削) sit in the lower-middle, dropping further on the
                        // wider fold states (where the app's controls move down). y is from the v-centre.
                        pos == "left" || pos == "right" -> {
                            // Inset from the very edge (out of the OEM's edge-gesture region, so a slide reaches us).
                            if (insetDp > 0) x = (insetDp * app.resources.displayMetrics.density).toInt()
                            // vAlign places an edge strip in the top / middle / bottom third (its vertical
                            // gravity); keeping the horizontal (left/right) part. Falls back to the legacy
                            // edgeCenter / media-HUD fraction when unset.
                            val horiz = gravity and Gravity.HORIZONTAL_GRAVITY_MASK
                            when (vAlign?.trim()?.lowercase()) {
                                "top" -> { gravity = horiz or Gravity.TOP; y = 0 }
                                "bottom" -> { gravity = horiz or Gravity.BOTTOM; y = 0 }
                                "center", "middle" -> { gravity = horiz or Gravity.CENTER_VERTICAL; y = 0 }
                                else -> {
                                    y = if (edgeCenter) 0 else {
                                        val frac = when {
                                            real.widthPixels < 1500 -> 0.06f
                                            real.widthPixels < 2150 -> 0.19f
                                            else -> 0.27f
                                        }
                                        (frac * real.heightPixels).toInt()
                                    }
                                }
                            }
                        }
                        // Bottom edge bar: hAlign picks the left / centre / right third (horizontal
                        // gravity); flush at the very bottom edge.
                        pos == "bottom" && (widthFraction > 0f || hAlign != null) -> {
                            val vert = gravity and Gravity.VERTICAL_GRAVITY_MASK
                            gravity = vert or when (hAlign?.trim()?.lowercase()) {
                                "left" -> Gravity.START
                                "right" -> Gravity.END
                                else -> Gravity.CENTER_HORIZONTAL
                            }
                            y = 0
                        }
                        // A full-width bar sits flush over the status bar; a regular HUD gets a small inset.
                        !fullWidth && gravity != Gravity.CENTER -> y = (48 * app.resources.displayMetrics.density).toInt()
                    }
                }
            }
            if (showWhenLocked) {
                // Show over the lockscreen without unlocking (Tasker's wakedance trick). On EMUI the
                // accessibility overlay otherwise sits BELOW the keyguard, so the scene is hidden.
                @Suppress("DEPRECATION")
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            }
            runCatching { wm.addView(composeView, params) }
                .onSuccess {
                    owner.onResume()
                    val overlay = Overlay(composeView, owner, params, wm, fullscreen, heightFraction, widthFraction, timeoutMs)
                    active[scene.id] = overlay
                    shownNames[scene.id] = scene.name
                    if (fullscreen || heightFraction > 0f || widthFraction > 0f) ensureDisplayListener(app)
                    // Exclude the strip's whole area from the system edge gestures (back swipe, and on
                    // devices that honour it the bottom home/recents swipe) so the slide reaches the
                    // overlay instead of triggering the system gesture. Applied on every layout — a
                    // post{} can run before the view is measured (width/height 0 → an empty, useless rect).
                    if (!modal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        composeView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            if (v.width > 0 && v.height > 0) {
                                v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, v.width, v.height))
                            }
                        }
                    }
                    if (timeoutMs > 0) {
                        val r = Runnable { hide(scene.id) }
                        overlay.dismissRunnable = r
                        main.postDelayed(r, timeoutMs)
                    }
                }
                .onFailure { owner.onDestroy() }
        }
    }

    private fun sceneGravity(position: String?): Int = when (position?.trim()?.lowercase()) {
        "top" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        "bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        "left" -> Gravity.START or Gravity.CENTER_VERTICAL
        "right" -> Gravity.END or Gravity.CENTER_VERTICAL
        else -> Gravity.CENTER
    }

    /** Dismiss the overlay for [sceneId], if shown. */
    fun hide(sceneId: Long) { main.post { remove(sceneId) } }

    /** Dismiss every overlay; returns how many were showing. */
    fun hideAll(): Int {
        val n = active.size
        main.post { active.keys.toList().forEach { remove(it) } }
        return n
    }

    private fun remove(sceneId: Long) {
        shownNames.remove(sceneId)
        val overlay = active.remove(sceneId) ?: return
        runCatching { overlay.wm.removeView(overlay.view) }
        overlay.owner.onDestroy()
    }

    private fun runTask(taskId: Long) {
        io.launch {
            val db = OpenTaskerApp_NoHilt.db
            val task = db.taskDao().getById(taskId)?.toDomain() ?: return@launch
            executeAndLogTask(appContext ?: return@launch, db, task, source = "Scene")
        }
    }

    /** Mirrors SceneActivity.setVar — case-based scope (%ALLCAPS super-global, else the scene's project). */
    private fun setVar(sceneProjectId: Long?, name: String, value: String) {
        // Dragging a panel slider keeps the panel alive (restarts its auto-dismiss countdown).
        main.post { resetTimeouts() }
        val clean = name.trim().removePrefix("%").ifBlank { return }
        val superGlobal = clean.any { it.isLetter() } && clean == clean.uppercase()
        PersistentGlobalScope.set(if (superGlobal) 0L else (sceneProjectId ?: 0L), clean, value)
    }
}

/** Minimal owner so a [ComposeView] can host Compose outside an Activity (for the overlay window). */
private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
