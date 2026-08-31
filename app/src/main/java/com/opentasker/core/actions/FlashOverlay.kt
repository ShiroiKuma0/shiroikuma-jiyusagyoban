package com.opentasker.core.actions

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * Renders the Flash action as a styled overlay window (so it can carry a border, custom colours and
 * an arbitrary position — a plain Android Toast can't on modern Android). Falls back to a plain toast
 * when the "display over other apps" permission isn't granted. Must be called on the main thread.
 */
internal object FlashOverlay {
    fun show(
        context: Context,
        text: CharSequence,
        backgroundColor: Int,
        textColor: Int,
        borderColor: Int,
        borderWidthDp: Int,
        cornerRadiusDp: Int,
        textSizeSp: Int,
        fontWeight: Int,
        gravity: Int,
        xDp: Int,
        yDp: Int,
        longDuration: Boolean,
    ) {
        val density = context.resources.displayMetrics.density
        val view = TextView(context).apply {
            setText(text)
            setTextColor(textColor)
            textSize = textSizeSp.toFloat()
            typeface = Typeface.create(Typeface.DEFAULT, if (fontWeight >= 600) Typeface.BOLD else Typeface.NORMAL)
            val padH = (18 * density).toInt()
            val padV = (12 * density).toInt()
            setPadding(padH, padV, padH, padV)
            this.background = GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = cornerRadiusDp * density
                if (borderWidthDp > 0) setStroke((borderWidthDp * density).toInt(), borderColor)
            }
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            x = (xDp * density).toInt()
            y = (yDp * density).toInt()
        }
        try {
            wm.addView(view, params)
            Handler(Looper.getMainLooper()).postDelayed(
                { runCatching { wm.removeView(view) } },
                if (longDuration) 3500L else 2000L,
            )
        } catch (e: Exception) {
            // No overlay permission (or window add failed): degrade to a plain toast.
            Toast.makeText(context, text, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }
}

/** Parse a "#RRGGBB"/"#AARRGGBB" colour, or fall back to [default] when blank/invalid. */
internal fun parseColorOr(hex: String?, default: Int): Int {
    val value = hex?.trim().orEmpty()
    if (value.isEmpty()) return default
    return runCatching { Color.parseColor(if (value.startsWith("#")) value else "#$value") }.getOrDefault(default)
}

/** Map a position name to a Gravity. Defaults to bottom-centre (toast-like). */
internal fun flashGravity(position: String?): Int = when (position?.trim()?.lowercase()?.replace(" ", "")?.replace("_", "")) {
    "topleft", "top-left" -> Gravity.TOP or Gravity.START
    "top" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
    "topright", "top-right" -> Gravity.TOP or Gravity.END
    "left" -> Gravity.CENTER_VERTICAL or Gravity.START
    "center", "centre", "middle" -> Gravity.CENTER
    "right" -> Gravity.CENTER_VERTICAL or Gravity.END
    "bottomleft", "bottom-left" -> Gravity.BOTTOM or Gravity.START
    "bottomright", "bottom-right" -> Gravity.BOTTOM or Gravity.END
    else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
}
