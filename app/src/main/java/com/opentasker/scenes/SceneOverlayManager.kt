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
    private var appContext: Context? = null

    private class Overlay(
        val view: ComposeView,
        val owner: OverlayLifecycleOwner,
        val params: WindowManager.LayoutParams,
        val fullscreen: Boolean,
    )

    // Re-size fullscreen overlays when the display geometry changes (fold/unfold, rotation) — they are
    // pinned to an explicit pixel size, so they don't auto-track like MATCH_PARENT would.
    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null

    fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context.applicationContext)

    private fun realMetrics(wm: WindowManager): android.util.DisplayMetrics =
        android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }

    private fun resizeFullscreenOverlays() {
        val ctx = appContext ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val real = realMetrics(wm)
        active.values.filter { it.fullscreen }.forEach { ov ->
            if (ov.params.width != real.widthPixels || ov.params.height != real.heightPixels) {
                ov.params.width = real.widthPixels
                ov.params.height = real.heightPixels
                runCatching { wm.updateViewLayout(ov.view, ov.params) }
            }
        }
    }

    private fun ensureDisplayListener(app: Context) {
        if (displayListener != null) return
        val dm = app.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return
        val l = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                // The metrics can lag the callback slightly on foldables; re-apply now and once more shortly.
                resizeFullscreenOverlays()
                main.postDelayed({ resizeFullscreenOverlays() }, 150)
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
    fun show(context: Context, scene: Scene, position: String? = null, modal: Boolean = true, timeoutMs: Long = 0L, dismissOnOutside: Boolean = true, fullWidth: Boolean = false, fullscreen: Boolean = false) {
        val app = context.applicationContext
        main.post {
            appContext = app
            if (active.containsKey(scene.id)) return@post
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val owner = OverlayLifecycleOwner().apply { onCreate() }
            val composeView = ComposeView(app).apply {
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
                            onDismiss = { hide(scene.id) },
                            onRunTask = ::runTask,
                            onSetVar = ::setVar,
                        )
                    }
                }
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
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
                        else -> WindowManager.LayoutParams.WRAP_CONTENT
                    },
                    if (fullscreen) real.heightPixels else WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or edgeFlags,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = if (fullscreen) Gravity.TOP or Gravity.START else sceneGravity(position)
                    val pos = position?.trim()?.lowercase()
                    when {
                        fullscreen -> { x = 0; y = 0 }
                        // Edge HUDs (the music 良/削) sit in the lower-middle, dropping further on the
                        // wider fold states (where the app's controls move down). y is from the v-centre.
                        pos == "left" || pos == "right" -> {
                            val frac = when {
                                real.widthPixels < 1500 -> 0.06f
                                real.widthPixels < 2150 -> 0.19f
                                else -> 0.27f
                            }
                            y = (frac * real.heightPixels).toInt()
                        }
                        // A full-width bar sits flush over the status bar; a regular HUD gets a small inset.
                        !fullWidth && gravity != Gravity.CENTER -> y = (48 * app.resources.displayMetrics.density).toInt()
                    }
                }
            }
            runCatching { wm.addView(composeView, params) }
                .onSuccess {
                    owner.onResume()
                    active[scene.id] = Overlay(composeView, owner, params, fullscreen)
                    if (fullscreen) ensureDisplayListener(app)
                    if (timeoutMs > 0) main.postDelayed({ hide(scene.id) }, timeoutMs)
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
        val overlay = active.remove(sceneId) ?: return
        val ctx = appContext ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(overlay.view) }
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
