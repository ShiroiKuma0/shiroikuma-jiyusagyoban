package com.opentasker.core.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Draws a [WidgetNode] tree into a Bitmap with Canvas — the only way to put a custom .otf font and
 * arbitrary styling on a home-screen widget (RemoteViews can't). Layout is a pragmatic flex:
 * `column`/`row` flow (with cross-axis alignment, arrange, gap), `box` overlay (children layered,
 * pinned by align + offset) and `free` (absolute x/y). All node dimensions are dp.
 */
class WidgetRenderer(
    private val density: Float,
    private val fontResolver: (String) -> Typeface?,
) {
    private data class Size(val w: Int, val h: Int)
    private data class Pad(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val horizontal get() = left + right
        val vertical get() = top + bottom
    }

    fun render(root: WidgetNode, widthPx: Int, heightPx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas, root, 0, 0, widthPx, heightPx, TextStyle())
        return bitmap
    }

    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpf(v: Int): Float = v * density

    private fun color(hex: String?, fallback: Int = Color.TRANSPARENT): Int =
        if (hex.isNullOrBlank()) fallback
        else runCatching { Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") }.getOrDefault(fallback)

    private fun pad(node: WidgetNode): Pad {
        val p = node.padding ?: return Pad(0, 0, 0, 0)
        return Pad(dp(p.left), dp(p.top), dp(p.right), dp(p.bottom))
    }

    private fun merge(parent: TextStyle, child: TextStyle?): TextStyle {
        if (child == null) return parent
        return TextStyle(
            font = child.font ?: parent.font,
            color = child.color ?: parent.color,
            size = child.size ?: parent.size,
            align = child.align ?: parent.align,
            bold = child.bold ?: parent.bold,
            italic = child.italic ?: parent.italic,
        )
    }

    private fun resolve(spec: String?, available: Int, intrinsic: Int): Int = when {
        spec == null || spec == "wrap" -> intrinsic
        spec == "fill" -> available
        else -> spec.toIntOrNull()?.let { dp(it) } ?: intrinsic
    }

    // ---- measure ----

    private fun measure(node: WidgetNode, maxW: Int, maxH: Int, inh: TextStyle): Size {
        val p = pad(node)
        val innerW = (maxW - p.horizontal).coerceAtLeast(0)
        val innerH = (maxH - p.vertical).coerceAtLeast(0)
        val style = merge(inh, node.defaults)
        val content = when (node.type) {
            "text" -> {
                val layout = buildLayout(node, inh, innerW)
                Size(layout.width, layout.height)
            }
            "image" -> Size(innerW, innerH)
            "shape", "spacer" -> Size(0, 0)
            "row" -> {
                var w = 0; var h = 0; val gap = dp(node.gap ?: 0)
                node.children.forEachIndexed { i, c ->
                    val s = measure(c, innerW, innerH, style); w += s.w + if (i > 0) gap else 0; h = maxOf(h, s.h)
                }
                Size(w, h)
            }
            "column" -> {
                var w = 0; var h = 0; val gap = dp(node.gap ?: 0)
                node.children.forEachIndexed { i, c ->
                    val s = measure(c, innerW, innerH, style); h += s.h + if (i > 0) gap else 0; w = maxOf(w, s.w)
                }
                Size(w, h)
            }
            else -> { // box / free
                var w = 0; var h = 0
                node.children.forEach { c ->
                    val s = measure(c, innerW, innerH, style)
                    w = maxOf(w, s.w + dp(c.x ?: 0)); h = maxOf(h, s.h + dp(c.y ?: 0))
                }
                Size(w, h)
            }
        }
        return Size(
            resolve(node.width, maxW, content.w + p.horizontal),
            resolve(node.height, maxH, content.h + p.vertical),
        )
    }

    // ---- draw ----

    private fun draw(canvas: Canvas, node: WidgetNode, left: Int, top: Int, width: Int, height: Int, inh: TextStyle) {
        drawBackground(canvas, node, left, top, width, height)
        val p = pad(node)
        val il = left + p.left; val it = top + p.top
        val iw = (width - p.horizontal).coerceAtLeast(0); val ih = (height - p.vertical).coerceAtLeast(0)
        val style = merge(inh, node.defaults)
        when (node.type) {
            "text" -> drawText(canvas, node, il, it, iw, inh)
            "image" -> drawImage(canvas, node, il, it, iw, ih)
            "shape" -> drawShape(canvas, node, il, it, iw, ih)
            "spacer" -> {}
            "row" -> layoutFlow(canvas, node, il, it, iw, ih, style, horizontal = true)
            "column" -> layoutFlow(canvas, node, il, it, iw, ih, style, horizontal = false)
            "free" -> node.children.forEach { c ->
                val s = measure(c, iw, ih, style)
                val cw = c.w?.let { dp(it) } ?: s.w; val ch = c.h?.let { dp(it) } ?: s.h
                draw(canvas, c, il + dp(c.x ?: 0), it + dp(c.y ?: 0), cw, ch, style)
            }
            else -> node.children.forEach { c -> // box overlay
                val s = measure(c, iw, ih, style)
                val (ax, ay) = anchor(c.align ?: node.align ?: "center")
                val ox = c.offset?.let { dp(it.x) } ?: 0; val oy = c.offset?.let { dp(it.y) } ?: 0
                val cx = il + ((iw - s.w) * ax).toInt() + ox
                val cy = it + ((ih - s.h) * ay).toInt() + oy
                draw(canvas, c, cx, cy, s.w, s.h, style)
            }
        }
    }

    private fun layoutFlow(
        canvas: Canvas, node: WidgetNode, il: Int, it: Int, iw: Int, ih: Int, style: TextStyle, horizontal: Boolean,
    ) {
        val gap = dp(node.gap ?: 0)
        val sizes = node.children.map { measure(it, iw, ih, style) }
        val total = sizes.sumOf { if (horizontal) it.w else it.h } + gap * (sizes.size - 1).coerceAtLeast(0)
        val extent = if (horizontal) iw else ih
        var cursor = when (node.arrange) {
            "center" -> (extent - total) / 2
            "end" -> extent - total
            else -> 0
        }
        node.children.forEachIndexed { i, c ->
            val s = sizes[i]
            if (horizontal) {
                val cross = crossPos(c.align ?: node.align, ih, s.h)
                draw(canvas, c, il + cursor, it + cross, s.w, s.h, style)
                cursor += s.w + gap
            } else {
                val cross = crossPos(c.align ?: node.align, iw, s.w)
                draw(canvas, c, il + cross, it + cursor, s.w, s.h, style)
                cursor += s.h + gap
            }
        }
    }

    private fun crossPos(align: String?, extent: Int, childExtent: Int): Int = when (align) {
        "center", null -> (extent - childExtent) / 2
        "end" -> extent - childExtent
        else -> 0 // start
    }

    /** Fractional anchor (x,y) in [0,1] for box placement. */
    private fun anchor(a: String): Pair<Float, Float> = when (a.lowercase()) {
        "topstart", "topleft" -> 0f to 0f
        "top", "topcenter" -> 0.5f to 0f
        "topend", "topright" -> 1f to 0f
        "start", "left" -> 0f to 0.5f
        "center", "middle" -> 0.5f to 0.5f
        "end", "right" -> 1f to 0.5f
        "bottomstart", "bottomleft" -> 0f to 1f
        "bottom", "bottomcenter" -> 0.5f to 1f
        "bottomend", "bottomright" -> 1f to 1f
        else -> 0.5f to 0.5f
    }

    private fun drawBackground(canvas: Canvas, node: WidgetNode, l: Int, t: Int, w: Int, h: Int) {
        val bg = node.background ?: return
        val rect = RectF(l.toFloat(), t.toFloat(), (l + w).toFloat(), (t + h).toFloat())
        val r = dpf(bg.corner)
        bg.color?.let {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(it); style = Paint.Style.FILL }
            canvas.drawRoundRect(rect, r, r, paint)
        }
        if (!bg.border.isNullOrBlank() && bg.borderWidth > 0) {
            val sw = dpf(bg.borderWidth)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = color(bg.border); style = Paint.Style.STROKE; strokeWidth = sw
            }
            val inset = sw / 2
            canvas.drawRoundRect(RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset), r, r, paint)
        }
    }

    private fun buildTextPaint(node: WidgetNode, inh: TextStyle): TextPaint {
        val fontName = node.font ?: inh.font
        val bold = node.bold ?: inh.bold ?: false
        val italic = node.italic ?: inh.italic ?: false
        val base = fontName?.let { fontResolver(it) } ?: Typeface.DEFAULT
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(base, style)
            textSize = dpf(node.size ?: inh.size ?: 14)
            color = color(node.color ?: inh.color, Color.WHITE)
        }
    }

    private fun textAlignment(node: WidgetNode, inh: TextStyle): Layout.Alignment = when (node.align ?: inh.align) {
        "center" -> Layout.Alignment.ALIGN_CENTER
        "end", "right" -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }

    private fun buildLayout(node: WidgetNode, inh: TextStyle, maxWidth: Int): StaticLayout {
        val paint = buildTextPaint(node, inh)
        val text = node.text.orEmpty()
        val natural = Math.ceil(paint.measureText(text).toDouble()).toInt()
        val width = when (node.width) {
            "fill" -> maxWidth
            null, "wrap" -> natural.coerceAtMost(if (maxWidth > 0) maxWidth else natural).coerceAtLeast(1)
            else -> node.width.toIntOrNull()?.let { dp(it) } ?: natural.coerceAtLeast(1)
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(textAlignment(node, inh))
            .setMaxLines(node.maxLines ?: Int.MAX_VALUE)
            .setIncludePad(false)
            .build()
    }

    private fun drawText(canvas: Canvas, node: WidgetNode, il: Int, it: Int, iw: Int, inh: TextStyle) {
        val layout = buildLayout(node, inh, iw)
        canvas.save()
        canvas.translate(il.toFloat(), it.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawImage(canvas: Canvas, node: WidgetNode, il: Int, it: Int, iw: Int, ih: Int) {
        val path = node.src?.takeIf { it.isNotBlank() } ?: return
        val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return
        val dst = RectF(il.toFloat(), it.toFloat(), (il + iw).toFloat(), (it + ih).toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        node.tint?.takeIf { it.isNotBlank() }?.let {
            paint.colorFilter = android.graphics.PorterDuffColorFilter(color(it), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        when (node.scale) {
            "fill" -> canvas.drawBitmap(bitmap, null, dst, paint)
            else -> { // fit (default) / crop — preserve aspect
                val sw = bitmap.width.toFloat(); val sh = bitmap.height.toFloat()
                val scale = if (node.scale == "crop") maxOf(iw / sw, ih / sh) else minOf(iw / sw, ih / sh)
                val dw = sw * scale; val dh = sh * scale
                val dx = il + (iw - dw) / 2; val dy = it + (ih - dh) / 2
                canvas.drawBitmap(bitmap, null, RectF(dx, dy, dx + dw, dy + dh), paint)
            }
        }
    }

    private fun drawShape(canvas: Canvas, node: WidgetNode, il: Int, it: Int, iw: Int, ih: Int) {
        val rect = RectF(il.toFloat(), it.toFloat(), (il + iw).toFloat(), (it + ih).toFloat())
        val fill = node.color?.takeIf { it.isNotBlank() }
        val stroke = node.stroke?.takeIf { it.isNotBlank() }
        val r = dpf(node.corner ?: 0)
        if (fill != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(fill); style = Paint.Style.FILL }
            when (node.shape) {
                "oval" -> canvas.drawOval(rect, paint)
                "line" -> canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, paint.apply { strokeWidth = dpf(node.strokeWidth ?: 1) })
                else -> canvas.drawRoundRect(rect, r, r, paint)
            }
        }
        if (stroke != null && (node.strokeWidth ?: 0) > 0) {
            val sw = dpf(node.strokeWidth ?: 1)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(stroke); style = Paint.Style.STROKE; strokeWidth = sw }
            val inset = sw / 2
            val r2 = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            if (node.shape == "oval") canvas.drawOval(r2, paint) else canvas.drawRoundRect(r2, r, r, paint)
        }
    }
}
