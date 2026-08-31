package com.opentasker.core.bubbles

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.accessibility.ShiroiKumaAccessibilityService
import com.opentasker.core.contexts.AppForegroundChangedContextEvents
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.engine.resolveTaskByName
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Renders the 通知明滅 flash bubbles ([FlashBubbleStore]) as draggable system-overlay windows, shown
 * **only while the device's default home launcher (the Desktop) is foreground** — the mirror of
 * [FreezeBubbleOverlayManager], but anchored to the **top + left** edges so the two stacks never mix.
 *
 * One window per flashing app (its icon + ⚡ badge + label) plus, while flashing is ongoing, a single
 * kill-all window (this app's own icon) pinned below the stack. The app bubbles' tap / long-tap
 * behaviors are UI-settable (UI customization → Flash bubbles): each picks from open-app + kill-flash /
 * kill-flash only / open-app only / dismiss-icon only. "Kill flash" runs the configured kill task with
 * the bubble's package injected as the per-invocation %APP_PACKAGE, so the same workspace task serves
 * both the foreground profile and a bubble gesture. The kill-all icon's tap runs the configured
 * kill-all task (same function as tapping the flash-ongoing notification) and hides itself — the app
 * bubbles stay; its long-tap just hides it.
 */
object FlashBubbleOverlayManager {

    private const val TAG = "FlashBubbles"

    /** Window-map key for the kill-all icon — "!" can never appear in a package name. */
    private const val KILL_KEY = "!kill"

    /** Gesture behavior values (ThemePrefs.flashTapBehavior / flashLongTapBehavior). */
    const val BEHAVIOR_OPEN_KILL = "open_kill"
    const val BEHAVIOR_KILL = "kill"
    const val BEHAVIOR_OPEN = "open"
    const val BEHAVIOR_DISMISS = "dismiss"

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private val wm get() = appContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val main = Handler(Looper.getMainLooper())

    private class BubbleWindow(val view: View, val params: WindowManager.LayoutParams, var dxDp: Int, var dyDp: Int)

    private val windows = mutableMapOf<String, BubbleWindow>()
    private var visible = false
    private var started = false
    private var displayListener: DisplayManager.DisplayListener? = null

    /** Start the foreground watcher + store sync. Idempotent; call from the always-on service. */
    fun start(context: Context, scope: CoroutineScope) {
        appContext = context.applicationContext
        this.scope = scope
        if (started) return
        started = true
        scope.launch {
            AppForegroundChangedContextEvents.events.collect { ev ->
                evaluateForeground(ev.metadata["package"])
            }
        }
        scope.launch {
            FlashBubbleStore.state.collect { s -> if (visible) main.post { sync(s) } }
        }
        main.post { evaluateForeground(ShiroiKumaAccessibilityService.recentApps.firstOrNull()) }
    }

    private fun homeLauncher(ctx: Context): String? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull()

    private fun evaluateForeground(foregroundPkg: String?) {
        val ctx = appContext ?: return
        val onDesktop = foregroundPkg != null && foregroundPkg == homeLauncher(ctx)
        val shouldShow = onDesktop && Settings.canDrawOverlays(ctx)
        main.post { setVisible(shouldShow) }
    }

    private fun setVisible(show: Boolean) {
        if (show == visible) return
        visible = show
        if (show) {
            ensureDisplayListener()
            sync(FlashBubbleStore.state.value)
        } else {
            removeAll()
        }
    }

    private fun sync(s: FlashBubbleState) {
        val keep = s.bubbles.map { it.pkg }.toMutableSet()
        if (s.killVisible) keep += KILL_KEY
        windows.keys.filterNot { it in keep }.toList().forEach { removeWindow(it) }
        s.bubbles.forEach { entry ->
            val existing = windows[entry.pkg]
            if (existing == null) {
                addWindow(entry.pkg, buildAppBubbleView(entry), entry.dxFromLeftDp, entry.dyFromTopDp)
            } else {
                existing.dxDp = entry.dxFromLeftDp; existing.dyDp = entry.dyFromTopDp
                applyParams(existing)
            }
        }
        if (s.killVisible) {
            val existing = windows[KILL_KEY]
            if (existing == null) {
                addWindow(KILL_KEY, buildKillView(), s.killDxFromLeftDp, s.killDyFromTopDp)
            } else {
                existing.dxDp = s.killDxFromLeftDp; existing.dyDp = s.killDyFromTopDp
                applyParams(existing)
            }
        }
    }

    private fun addWindow(key: String, view: View?, dxDp: Int, dyDp: Int) {
        val wm = wm ?: return
        if (view == null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val bw = BubbleWindow(view, params, dxDp, dyDp)
        applyParams(bw)
        attachTouch(key, bw)
        runCatching { wm.addView(view, params) }.onSuccess { windows[key] = bw }
    }

    /** Position the window from its dp-from-left / dp-from-top, clamped on-screen. */
    private fun applyParams(bw: BubbleWindow) {
        val ctx = appContext ?: return
        val d = ctx.resources.displayMetrics.density
        val metrics = ctx.resources.displayMetrics
        val maxX = (metrics.widthPixels - dp(40)).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - dp(40)).coerceAtLeast(0)
        bw.params.x = (bw.dxDp * d).toInt().coerceIn(0, maxX)
        bw.params.y = (bw.dyDp * d).toInt().coerceIn(0, maxY)
        if (bw.view.isAttachedToWindow) runCatching { wm?.updateViewLayout(bw.view, bw.params) }
    }

    private fun removeWindow(key: String) {
        val bw = windows.remove(key) ?: return
        runCatching { wm?.removeView(bw.view) }
    }

    private fun removeAll() {
        windows.values.toList().forEach { runCatching { wm?.removeView(it.view) } }
        windows.clear()
    }

    /** Keys of the flash bubbles on screen right now — for the Monitor / shutdown inventory. */
    fun shownKeys(): List<String> = synchronized(windows) { windows.keys.toList() }

    /** Tear the flash-bubble layer down and allow a later [start] to re-register. See the freeze twin. */
    fun stop() {
        main.post { removeAll() }
        visible = false
        started = false
        scope = null
    }

    private fun dp(v: Int): Int = ((appContext?.resources?.displayMetrics?.density ?: 1f) * v).toInt()

    // ---- views -----------------------------------------------------------------------------------

    /** A flashing app's bubble: its launcher icon + ⚡ badge + label (freeze-bubble styling). */
    private fun buildAppBubbleView(entry: FlashEntry): View? {
        val ctx = appContext ?: return null
        val icon = runCatching { ctx.packageManager.getApplicationIcon(entry.pkg) }.getOrNull()
        return buildBubbleView(ctx, entry.label, "⚡") { iv ->
            if (icon != null) iv.setImageDrawable(icon) else iv.setImageResource(android.R.drawable.sym_def_app_icon)
        }
    }

    /** The kill-all bubble: this app's own icon + ✕ badge, labeled 全消灯. */
    private fun buildKillView(): View? {
        val ctx = appContext ?: return null
        return buildBubbleView(ctx, "全消灯", "✕") { iv ->
            runCatching { iv.setImageDrawable(ctx.packageManager.getApplicationIcon(ctx.packageName)) }
        }
    }

    private fun buildBubbleView(ctx: Context, label: String, badgeText: String, setIcon: (ImageView) -> Unit): View {
        val prefs = ThemeStore.state.value
        val accent = prefs.accent
        val onAccent = prefs.background
        val iconSizePx = dp(prefs.bubbleIconSizeDp)
        val cornerPx = dp(prefs.bubbleIconCornerDp).coerceAtMost(iconSizePx / 2)

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val iconFrame = FrameLayout(ctx)
        val icon = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(iconSizePx, iconSizePx)
            setIcon(this)
            if (cornerPx > 0) {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, cornerPx.toFloat())
                    }
                }
            }
        }
        iconFrame.addView(icon)

        // Badge in the top-LEFT corner (the freeze bubbles badge top-right; mirrored like the stack side).
        val badgePx = (iconSizePx * 0.4f).toInt().coerceIn(dp(14), dp(26))
        val badge = TextView(ctx).apply {
            text = badgeText
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, badgePx * 0.6f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(onAccent)
                setStroke(dp(1), accent)
            }
            layoutParams = FrameLayout.LayoutParams(badgePx, badgePx).apply { gravity = Gravity.TOP or Gravity.START }
        }
        iconFrame.addView(badge)
        column.addView(iconFrame)

        val typeface = resolveBubbleTypeface(prefs)
        column.addView(TextView(ctx).apply {
            text = label
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.bubbleLabelSizeSp.toFloat())
            applyTypeface(this, typeface, prefs.bubbleLabelWeight)
            maxLines = 1
            gravity = Gravity.CENTER
            width = dp((prefs.bubbleIconSizeDp + 24).coerceAtLeast(64))
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) }
        })
        return column
    }

    private fun resolveBubbleTypeface(prefs: com.opentasker.ui.theme.ThemePrefs): Typeface? {
        val font = prefs.bubbleFontFileName.ifBlank { prefs.fontFileName }
        return ThemeStore.typeface(font)
    }

    private fun applyTypeface(tv: TextView, base: Typeface?, weight: Int) {
        if (Build.VERSION.SDK_INT >= 28) {
            tv.typeface = Typeface.create(base ?: Typeface.DEFAULT, weight.coerceIn(100, 900), false)
        } else {
            tv.setTypeface(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // ---- touch: drag + tap + long-tap ------------------------------------------------------------

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(key: String, bw: BubbleWindow) {
        val slop = ViewConfiguration.get(bw.view.context).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var startX = 0; var startY = 0
        var downRawX = 0f; var downRawY = 0f
        var dragging = false
        var consumed = false
        val longPress = Runnable {
            if (!dragging) { consumed = true; onGesture(key, longTap = true) }
        }
        bw.view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bw.params.x; startY = bw.params.y
                    downRawX = e.rawX; downRawY = e.rawY
                    dragging = false; consumed = false
                    main.postDelayed(longPress, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dxS = e.rawX - downRawX
                    val dyS = e.rawY - downRawY
                    if (!dragging && hypot(dxS.toDouble(), dyS.toDouble()) > slop) {
                        dragging = true; main.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        // gravity START: larger x = further from the left edge, so moving right grows x.
                        bw.params.x = (startX + dxS).toInt().coerceAtLeast(0)
                        bw.params.y = (startY + dyS).toInt().coerceAtLeast(0)
                        runCatching { wm?.updateViewLayout(bw.view, bw.params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (dragging) {
                        val d = appContext?.resources?.displayMetrics?.density ?: 1f
                        val dxDp = (bw.params.x / d).toInt()
                        val dyDp = (bw.params.y / d).toInt()
                        bw.dxDp = dxDp; bw.dyDp = dyDp
                        if (key == KILL_KEY) FlashBubbleStore.updateKillPosition(dxDp, dyDp)
                        else FlashBubbleStore.updatePosition(key, dxDp, dyDp)
                    } else if (!consumed && e.actionMasked == MotionEvent.ACTION_UP) {
                        onGesture(key, longTap = false)
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ---- gestures --------------------------------------------------------------------------------

    private fun onGesture(key: String, longTap: Boolean) {
        if (key == KILL_KEY) {
            // Tap = kill all flashing (the flash-ongoing notification's function); the icon hides
            // itself, the app bubbles stay. Long-tap = hide the icon without killing anything.
            if (!longTap) runTaskByName(ThemeStore.state.value.flashKillAllTaskName, emptyMap())
            FlashBubbleStore.hideKill()
            return
        }
        val prefs = ThemeStore.state.value
        val entry = FlashBubbleStore.state.value.bubbles.firstOrNull { it.pkg == key } ?: return
        val behavior = if (longTap) prefs.flashLongTapBehavior else prefs.flashTapBehavior
        // Every gesture retires the bubble; the behavior decides the side effects.
        FlashBubbleStore.remove(entry.pkg)
        val kill = behavior == BEHAVIOR_OPEN_KILL || behavior == BEHAVIOR_KILL
        val open = behavior == BEHAVIOR_OPEN_KILL || behavior == BEHAVIOR_OPEN
        // Kill first: the per-invocation %APP_PACKAGE targets this bubble's app, dismissing its
        // notification and dropping the flash-ongoing notification when it was the last flasher.
        if (kill) runTaskByName(prefs.flashKillTaskName, mapOf("APP_PACKAGE" to entry.pkg))
        if (open) launchApp(entry)
    }

    /** Resolve a workspace task by name and run it with [eventLocals] threaded per-invocation. */
    private fun runTaskByName(name: String, eventLocals: Map<String, String>) {
        val ctx = appContext ?: return
        val s = scope ?: return
        val ref = name.trim()
        if (ref.isEmpty()) return
        s.launch(Dispatchers.IO) {
            val db = OpenTaskerApp_NoHilt.db
            val task = resolveTaskByName(db, ref, null) ?: return@launch
            runCatching {
                executeAndLogTask(ctx, db, task, source = "FlashBubble", eventLocals = eventLocals, logTag = TAG)
            }
        }
    }

    /** Open the bubble's app (reuses the `app.launch` action). */
    private fun launchApp(entry: FlashEntry) {
        val ctx = appContext ?: return
        val s = scope ?: return
        s.launch(Dispatchers.IO) {
            val task = Task(
                name = "Launch ${entry.label}",
                actions = listOf(ActionSpec(type = "app.launch", args = mapOf("package" to entry.pkg))),
            )
            runCatching { executeAndLogTask(ctx, OpenTaskerApp_NoHilt.db, task, source = "FlashBubble", logTag = TAG) }
        }
    }

    // ---- geometry --------------------------------------------------------------------------------

    private fun ensureDisplayListener() {
        if (displayListener != null) return
        val ctx = appContext ?: return
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                reclamp(); main.postDelayed({ reclamp() }, 150)  // double-apply for foldable metric lag
            }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        dm.registerDisplayListener(l, main)
        displayListener = l
    }

    private fun reclamp() {
        if (!visible) return
        windows.values.toList().forEach { applyParams(it) }
    }
}
