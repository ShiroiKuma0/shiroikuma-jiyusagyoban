package com.sysadmindoc.ruledeck.core.actions

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sysadmindoc.ruledeck.i18n.tr
import com.sysadmindoc.ruledeck.ui.theme.ThemeStore

/**
 * The command window of `Run Shell` (白い熊, 2026-11-24): a panel over whatever is on screen that
 * shows the command, what it prints while it runs, and how it ended — so that a task which starts
 * something can be watched instead of trusted. It stays until it is closed by hand.
 *
 * One window at a time: a new run takes the window over and says so in it. Drawn as an overlay, like
 * the flash, because a task runs with no screen of its own; without the “display over other apps”
 * permission nothing is shown and the command runs all the same.
 */
internal object ShellWindow {
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var body: TextView? = null
    private var scroll: ScrollView? = null
    private var title: TextView? = null

    /** Text kept in the window; older lines go first when a command prints more. */
    private const val MAX_CHARS = 60_000

    fun open(context: Context, heading: String) = main.post {
        val app = context.applicationContext
        if (root == null && !build(app)) return@post
        title?.text = heading
        append("\n\$ $heading\n")
    }

    fun print(text: String) {
        if (text.isEmpty()) return
        main.post { append(text) }
    }

    fun finish(exitCode: Int?, note: String? = null) = main.post {
        append("\n" + (note ?: tr("— ended, exit code %1\$s —", exitCode?.toString() ?: "?")) + "\n")
    }

    private fun append(text: String) {
        val view = body ?: return
        val joined = view.text.toString() + text
        view.text = if (joined.length > MAX_CHARS) joined.substring(joined.length - MAX_CHARS) else joined
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun close(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        root?.let { runCatching { wm.removeView(it) } }
        root = null; body = null; scroll = null; title = null
    }

    private fun build(context: Context): Boolean {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val prefs = ThemeStore.state.value
        val accent = prefs.flashBorder
        val background = Color.BLACK

        val heading = TextView(context).apply {
            setTextColor(accent)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // The pill rule: outlined, never filled; this is the panel's one action, so the frame is thick.
        val closeButton = TextView(context).apply {
            text = tr("Close")
            setTextColor(accent)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(8), dp(18), dp(8))
            this.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(100).toFloat()
                setStroke(dp(3), accent)
            }
            setOnClickListener { close(context) }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(heading)
            addView(closeButton)
        }
        val output = TextView(context).apply {
            setTextColor(accent)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(false)
        }
        val scroller = ScrollView(context).apply {
            addView(output)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            this.background = GradientDrawable().apply {
                setColor(background)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), accent)
            }
            addView(header)
            addView(scroller)
        }
        val metrics = context.resources.displayMetrics
        // An explicit width: a WRAP_CONTENT overlay is capped at 320 dp on these phones.
        val params = WindowManager.LayoutParams(
            metrics.widthPixels - dp(24),
            (metrics.heightPixels * 0.45f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // At the top: the flash of the output appears at the bottom, and the two must not cover
            // each other.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(64)
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return try {
            wm.addView(panel, params)
            root = panel; body = output; scroll = scroller; title = heading
            true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Flashes what a command printed — its output, then its errors — unless the action asked for
 * silence. Nothing is flashed for a command that printed nothing.
 */
internal fun flashShellOutput(ctx: com.sysadmindoc.ruledeck.core.engine.ActionContext, stdout: String, stderr: String, quiet: Boolean) {
    if (quiet) return
    val out = stdout.trim()
    val err = stderr.trim()
    if (out.isEmpty() && err.isEmpty()) return
    val text = buildString {
        if (out.isNotEmpty()) append(out.takeLast(FLASH_CHARS))
        if (err.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append("✗ ").append(err.takeLast(FLASH_CHARS))
        }
    }
    val prefs = ThemeStore.state.value
    Handler(Looper.getMainLooper()).post {
        FlashOverlay.show(
            context = ctx.app,
            text = text,
            backgroundColor = prefs.flashBackground,
            textColor = prefs.flashText,
            borderColor = prefs.flashBorder,
            borderWidthDp = prefs.flashBorderWidthDp,
            cornerRadiusDp = prefs.flashCornerRadiusDp,
            textSizeSp = prefs.flashTextSizeSp,
            fontWeight = prefs.flashFontWeight,
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            xDp = 0,
            yDp = 0,
            longDuration = true,
        )
    }
}

/** The end of each stream that a flash carries: the last lines are the ones that say how it went. */
private const val FLASH_CHARS = 400
