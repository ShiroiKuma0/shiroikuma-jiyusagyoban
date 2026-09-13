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
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Renders the pending freeze bubbles ([FreezeBubbleStore]) as draggable system-overlay windows, shown
 * **only while the device's default home launcher (the Desktop) is foreground** — nowhere else. One small
 * window per bubble, anchored to the screen's **top + right** edges (so it keeps its relative spot across
 * rotation / fold). **Tap** freezes the app and removes the bubble; **long-tap** removes it only; the
 * ↗ badge opens the app and leaves the bubble where it is.
 *
 * ## What this class decides, and what it no longer does
 *
 * It owns only what a scene provably cannot: the launcher-foreground gate, one overlay window per
 * pending app, an icon readable while the app is frozen, and drag-with-persistence.
 *
 * **What each gesture MEANS is not here.** Every one of the three resolves a named workspace task —
 * [TASK_FREEZE], [TASK_DISMISS], [TASK_LAUNCH], or an app's own `<label> ⇦ 凍結` — and runs it with
 * the bubble's package, label and freeze set threaded in as event-locals. Each falls back to the
 * behaviour that used to be hard-coded here, so a renamed or missing task degrades rather than
 * turning the bubble into a button that does nothing visible.
 *
 * Native replacement for the Tasker 凍結 融解 AutoTools-WebScreen bubble layer.
 */
object FreezeBubbleOverlayManager {

    private const val TAG = "FreezeBubbles"

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private val wm get() = appContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val main = Handler(Looper.getMainLooper())

    private val windows = mutableMapOf<String, BubbleWindow>()
    private var visible = false
    private var started = false
    private var displayListener: DisplayManager.DisplayListener? = null

    private class BubbleWindow(val view: View, val params: WindowManager.LayoutParams, var entry: BubbleEntry)

    /** Start the foreground watcher + bubble-store sync. Idempotent; call from the always-on service. */
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
            FreezeBubbleStore.bubbles.collect { list -> if (visible) main.post { sync(list) } }
        }
        // Initial check: we may already be on the launcher when the engine starts.
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
            sync(FreezeBubbleStore.bubbles.value)
        } else {
            removeAll()
        }
    }

    private fun sync(list: List<BubbleEntry>) {
        val keep = list.map { it.pkg }.toSet()
        windows.keys.filterNot { it in keep }.toList().forEach { removeWindow(it) }
        list.forEach { entry ->
            val existing = windows[entry.pkg]
            if (existing == null) addWindow(entry) else { existing.entry = entry; applyParams(existing) }
        }
    }

    private fun addWindow(entry: BubbleEntry) {
        val ctx = appContext ?: return
        val wm = wm ?: return
        val view = buildBubbleView(ctx, entry)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END }
        val bw = BubbleWindow(view, params, entry)
        applyParams(bw)
        attachTouch(bw)
        runCatching { wm.addView(view, params) }.onSuccess { windows[entry.pkg] = bw }
    }

    /** Position the window from its entry's dp-from-right / dp-from-top, clamped on-screen. */
    private fun applyParams(bw: BubbleWindow) {
        val ctx = appContext ?: return
        val d = ctx.resources.displayMetrics.density
        val metrics = ctx.resources.displayMetrics
        val maxX = (metrics.widthPixels - dp(40)).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - dp(40)).coerceAtLeast(0)
        bw.params.x = (bw.entry.dxFromRightDp * d).toInt().coerceIn(0, maxX)
        bw.params.y = (bw.entry.dyFromTopDp * d).toInt().coerceIn(0, maxY)
        if (bw.view.isAttachedToWindow) runCatching { wm?.updateViewLayout(bw.view, bw.params) }
    }

    private fun removeWindow(pkg: String) {
        val bw = windows.remove(pkg) ?: return
        runCatching { wm?.removeView(bw.view) }
    }

    private fun removeAll() {
        windows.values.toList().forEach { runCatching { wm?.removeView(it.view) } }
        windows.clear()
    }

    /** Packages whose bubbles are on screen right now — for the Monitor / shutdown inventory. */
    fun shownPackages(): List<String> = synchronized(windows) { windows.keys.toList() }

    /**
     * Tear the bubble layer down and allow a later [start] to re-register its collectors.
     *
     * The engine's scope owns those collectors, so stopping the service kills them — but the windows
     * themselves are held by the WindowManager and would stay on screen, with `started` still true so a
     * restart never re-subscribed. Called from the engine's onDestroy and the shutdown teardown.
     */
    fun stop() {
        main.post { removeAll() }
        visible = false
        started = false
        scope = null
    }

    private fun dp(v: Int): Int = ((appContext?.resources?.displayMetrics?.density ?: 1f) * v).toInt()

    // ---- view ------------------------------------------------------------------------------------

    private fun buildBubbleView(ctx: Context, entry: BubbleEntry): View {
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
            val bmp = TaskIconStore.loadBitmap(entry.iconPath)
            if (bmp != null) setImageBitmap(bmp)
            else runCatching {
                val pm = ctx.packageManager
                setImageDrawable(
                    pm.getApplicationIcon(
                        pm.getApplicationInfo(entry.pkg, com.opentasker.core.policy.AppFreeze.MATCH_FROZEN),
                    ),
                )
            }
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

        // ❄ freeze badge in the top-right corner of the icon, scaled to the icon size.
        val badgePx = (iconSizePx * 0.4f).toInt().coerceIn(dp(14), dp(26))
        val badge = TextView(ctx).apply {
            text = "❄"
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, badgePx * 0.6f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(onAccent)
                setStroke(dp(1), accent)
            }
            layoutParams = FrameLayout.LayoutParams(badgePx, badgePx).apply { gravity = Gravity.TOP or Gravity.END }
        }
        iconFrame.addView(badge)

        // ↗ launch badge in the bottom-right corner (under the ❄ freeze badge), same size + styling.
        // It is the only clickable part of the bubble, so a tap here is consumed by the badge and never
        // reaches the parent's freeze handler → tapping it opens the app; tapping anywhere else freezes.
        val launchBadge = TextView(ctx).apply {
            text = "↗"
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, badgePx * 0.6f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(onAccent)
                setStroke(dp(1), accent)
            }
            isClickable = true
            setOnClickListener { launchApp(entry) }
            layoutParams = FrameLayout.LayoutParams(badgePx, badgePx).apply { gravity = Gravity.BOTTOM or Gravity.END }
        }
        iconFrame.addView(launchBadge)
        column.addView(iconFrame)

        val typeface = resolveBubbleTypeface(prefs)
        column.addView(TextView(ctx).apply {
            text = entry.label
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.bubbleLabelSizeSp.toFloat())
            applyTypeface(this, typeface, prefs.bubbleLabelWeight)
            maxLines = 1
            gravity = Gravity.CENTER
            width = dp((prefs.bubbleIconSizeDp + 24).coerceAtLeast(64))
            ellipsize = android.text.TextUtils.TruncateAt.END
            // Shadow so the label reads over any wallpaper.
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) }
        })
        return column
    }

    /** The bubble label's typeface: the bubble-specific font if set, else the app's global font (null = system). */
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

    // ---- touch: drag + tap (freeze) + long-tap (dismiss) -----------------------------------------

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(bw: BubbleWindow) {
        val slop = ViewConfiguration.get(bw.view.context).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var startX = 0; var startY = 0
        var downRawX = 0f; var downRawY = 0f
        var dragging = false
        var consumed = false
        val longPress = Runnable {
            if (!dragging) { consumed = true; dismissOnly(bw.entry.pkg) }
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
                        // gravity END: larger x = further from the right edge, so moving right shrinks x.
                        bw.params.x = (startX - dxS).toInt().coerceAtLeast(0)
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
                        bw.entry = bw.entry.copy(dxFromRightDp = dxDp, dyFromTopDp = dyDp)
                        FreezeBubbleStore.updatePosition(bw.entry.pkg, dxDp, dyDp)
                    } else if (!consumed && e.actionMasked == MotionEvent.ACTION_UP) {
                        freezeAndRemove(bw.entry)
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * The workspace tasks a gesture runs, by NAME.
     *
     * ## Why names and not settings
     *
     * 白い熊, 2026-09-13: *"the flow on both sides … in activities run on bubble tap, long-tap etc.
     * should all be task-based, so this behavior can be managed and audited."* A name is the whole
     * binding: the task exists and the gesture does what it says, or it does not and the fallback
     * below runs. Nothing to configure, and what happens is readable in the workspace.
     *
     * The flash bubbles hold their equivalents in `ThemeStore` as editable settings. Deliberately not
     * copied: 凍結融解 is about to own its configuration in a 01 settings task, and a second surface
     * in the app's own preferences would compete with it for the same question.
     */
    const val TASK_FREEZE = "凍結泡 ⇨ 凍結"
    const val TASK_DISMISS = "凍結泡 ⇨ 捨てる"
    const val TASK_LAUNCH = "凍結泡 ⇨ 起動"

    /**
     * The per-app override, by name: `<label> ⇦ 凍結`.
     *
     * Tried before [TASK_FREEZE], so a single app can do something the general rule cannot — and the
     * EXISTENCE of the task is the whole override. Nothing is enabled, ticked or listed anywhere;
     * `resolveTaskByName` returns null when there is no such task and the general path runs.
     *
     * `⇦` rather than `⇨`: the launcher tasks point forward into the app, this points back out of it.
     */
    private fun overrideNameFor(label: String) = "$label ⇦ 凍結"

    /**
     * Run a workspace task by name with the bubble's context threaded in, or null if there is none.
     *
     * **Event-locals, not globals.** `%APP_PACKAGE` as a super-global is written by every foreground
     * change on this phone, including the ones our own overlays provoke — a bubble reading it would
     * be reading whatever window moved last. Per-invocation locals belong to this tap alone.
     *
     * @return true when a task was found and started.
     */
    private suspend fun runNamed(name: String, entry: BubbleEntry): Boolean {
        val ctx = appContext ?: return false
        val db = OpenTaskerApp_NoHilt.db
        val task = com.opentasker.core.engine.resolveTaskByName(db, name, null) ?: return false
        val locals = mapOf(
            "APP_PACKAGE" to entry.pkg,
            "APP_LABEL" to entry.label,
            // Space-separated, the convention every package roster in this workspace uses
            // (%BR_Apps, %SC_Blacklist) and the one `var.split` is pointed at.
            "FREEZE_PACKAGES" to entry.freezeTargets.joinToString(" "),
        )
        runCatching {
            executeAndLogTask(ctx, db, task, source = "FreezeBubble", eventLocals = locals, logTag = TAG)
        }
        return true
    }

    /**
     * Freeze everything the launch thawed, then retire the bubble.
     *
     * Three ways in, in order: the app's own `<label> ⇦ 凍結` task, the general 凍結泡 task, and —
     * when the workspace holds neither — a synthesised run of `app.freeze` over the same packages.
     *
     * **The fallback is not decoration.** A renamed or not-yet-imported task would otherwise turn the
     * bubble into a button that removes itself and freezes nothing, which looks exactly like success.
     * [BubbleEntry.freezeTargets] is never empty: a bubble stored before the companion list existed
     * falls back to its own package.
     */
    private fun freezeAndRemove(entry: BubbleEntry) {
        val ctx = appContext ?: return
        val s = scope ?: return
        val targets = entry.freezeTargets
        s.launch(Dispatchers.IO) {
            if (runNamed(overrideNameFor(entry.label), entry)) return@launch
            if (runNamed(TASK_FREEZE, entry)) return@launch
            // One task carrying an `app.freeze` per target rather than one task each: they run in
            // order under a single run-log entry, so a half-completed re-freeze reads as one failed
            // task instead of two unrelated ones.
            val task = Task(
                name = if (targets.size == 1) "Freeze ${entry.label}" else "Freeze ${entry.label} (${targets.size})",
                actions = targets.map { ActionSpec(type = "app.freeze", args = mapOf("package" to it)) },
            )
            runCatching { executeAndLogTask(ctx, OpenTaskerApp_NoHilt.db, task, source = "FreezeBubble", logTag = TAG) }
        }
        FreezeBubbleStore.remove(entry.pkg)  // flow → sync() removes the window
    }

    /**
     * Long-tap: drop the reminder and leave the app thawed.
     *
     * The bubble is retired here rather than by the task, so a workspace that has no 捨てる task still
     * behaves correctly — the task, if present, is for whatever 白い熊 wants to happen BESIDES the
     * dismissal.
     */
    private fun dismissOnly(pkg: String) {
        val entry = FreezeBubbleStore.bubbles.value.firstOrNull { it.pkg == pkg }
        FreezeBubbleStore.remove(pkg)  // flow → sync() removes the window; app stays thawed
        val s = scope ?: return
        if (entry != null) s.launch(Dispatchers.IO) { runNamed(TASK_DISMISS, entry) }
    }

    /** Open the bubble's target app. The bubble stays put — the app is
     *  still thawed, so the reminder reappears when you come back to the Desktop to re-freeze it. */
    private fun launchApp(entry: BubbleEntry) {
        val ctx = appContext ?: return
        val s = scope ?: return
        s.launch(Dispatchers.IO) {
            if (runNamed(TASK_LAUNCH, entry)) return@launch
            val task = Task(
                name = "Launch ${entry.label}",
                actions = listOf(ActionSpec(type = "app.launch", args = mapOf("package" to entry.pkg))),
            )
            runCatching { executeAndLogTask(ctx, OpenTaskerApp_NoHilt.db, task, source = "FreezeBubble", logTag = TAG) }
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
