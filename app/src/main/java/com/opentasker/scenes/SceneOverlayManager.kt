package com.opentasker.scenes

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
 * The overlay is focusable so buttons/sliders/toggles (and Back to dismiss) work; text-field input in
 * an overlay window is best-effort.
 */
object SceneOverlayManager {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val active = LinkedHashMap<Long, Overlay>()
    private var appContext: Context? = null

    private class Overlay(val view: ComposeView, val owner: OverlayLifecycleOwner)

    fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context.applicationContext)

    /** Show [scene] as an overlay (no-op if it's already showing). Safe to call from any thread. */
    fun show(context: Context, scene: Scene) {
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
                isFocusableInTouchMode = true
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        hide(scene.id); true
                    } else false
                }
                setContent {
                    val prefs by ThemeStore.state.collectAsState()
                    OpenTaskerTheme(prefs) {
                        SceneOverlay(
                            scene = scene,
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
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            )
            runCatching { wm.addView(composeView, params) }
                .onSuccess { owner.onResume(); active[scene.id] = Overlay(composeView, owner) }
                .onFailure { owner.onDestroy() }
        }
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
