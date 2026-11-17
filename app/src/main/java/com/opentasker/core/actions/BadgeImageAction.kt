package com.opentasker.core.actions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot

/**
 * Draw a badge — the rounded label of a `dialog.form` badge field: short text on a fill, with a
 * border — as a square PNG made to be used as a ROUND avatar (白い熊, 2026-11-17).
 *
 * An app that shows avatars in a circle cuts the corners off whatever it is given, so the badge is
 * drawn small enough that its four corners stay inside the circle inscribed in the square, and the
 * rest of the square is [background] (black by default). 携帯 uses it for the Jami group pictures of
 * the SIM conversations: the picture then matches the pseudo-SIM's badge in 白い熊 メッセージ.
 *
 * The file is `<dir>/<name>.png`; an existing one is replaced (it is a picture, not a build). Its
 * path lands in `%<store>`.
 */
class BadgeImageAction : Action {
    override val id = "image.badge"
    override val category = ActionCategory.APP
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val text = args["text"].orEmpty().trim()
        if (text.isEmpty()) return ActionResult.Failure("badge text is required")
        val dirArg = args["dir"].orEmpty().trim()
        if (dirArg.isEmpty()) return ActionResult.Failure("directory is required")
        val fill = colorOf(args["fill"]) ?: return ActionResult.Failure("fill colour is not #RRGGBB: ${args["fill"]}")
        val ink = colorOf(args["ink"]) ?: Color.BLACK
        val border = colorOf(args["border"]) ?: fill
        val background = colorOf(args["background"]) ?: Color.BLACK
        val size = (args["size"]?.trim()?.toIntOrNull() ?: DEFAULT_SIZE).coerceIn(64, 2048)
        val store = args["store"]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() } ?: "badge_path"
        val name = safeFileName(args["name"].orEmpty().ifBlank { text })

        return withContext(Dispatchers.IO) {
            val dir = File(dirArg)
            if (!dir.isDirectory && !dir.mkdirs()) {
                return@withContext ActionResult.Failure("cannot create directory $dirArg")
            }
            val file = File(dir, "$name.png")
            val bitmap = draw(text, fill, ink, border, background, size)
            val written = runCatching {
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
            written.onFailure { return@withContext ActionResult.Failure("cannot write ${file.path}: ${it.message}") }
            ctx.variables.set(store, file.absolutePath)
            ctx.logger("Badge $text → ${file.path} (${size}px)")
            ActionResult.Success
        }
    }

    companion object {
        const val DEFAULT_SIZE = 512

        /**
         * How much of the inscribed circle's radius the badge's rounded corners may reach. Measured
         * to the corner ARC, not to the sharp corner of the bounding box: the first version measured
         * the box and left the badge at 61 % of the picture, visibly small in Jami's circle.
         */
        private const val REACH = 0.93f

        private fun colorOf(raw: String?): Int? =
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { Color.parseColor(it) }.getOrNull() }

        internal fun safeFileName(raw: String): String =
            raw.trim().replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim('.', ' ').ifEmpty { "badge" }

        /**
         * The badge in the proportions of the form's own preview (height h: corner h/4, side padding
         * h/5, border h/16, bold text h/2, never narrower than tall), scaled so that its rounded corners
         * end at [REACH] of the circle a round avatar keeps.
         */
        internal fun draw(text: String, fill: Int, ink: Int, border: Int, background: Int, size: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(background)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

            // Measure at a unit height of 100, then scale the whole badge to fit the circle.
            paint.textSize = 50f
            val unitWidth = maxOf(100f, paint.measureText(text) + 2 * 20f)
            // The farthest point of a rounded rectangle from its centre lies on a corner arc: the
            // distance to that arc's centre plus the corner radius (25 at unit height).
            val unitReach = hypot(unitWidth / 2f - 25f, 100f / 2f - 25f) + 25f
            val scale = (size / 2f * REACH) / unitReach
            val h = 100f * scale
            val w = unitWidth * scale
            val stroke = h / 16f
            val rect = RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f)
            val radius = h / 4f

            paint.style = Paint.Style.FILL
            paint.color = fill
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = border
            rect.inset(stroke / 2f, stroke / 2f)
            canvas.drawRoundRect(rect, radius - stroke / 2f, radius - stroke / 2f, paint)

            paint.style = Paint.Style.FILL
            paint.color = ink
            paint.textSize = 50f * scale
            paint.textAlign = Paint.Align.CENTER
            val metrics = paint.fontMetrics
            canvas.drawText(text, size / 2f, size / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
            return bitmap
        }
    }
}
