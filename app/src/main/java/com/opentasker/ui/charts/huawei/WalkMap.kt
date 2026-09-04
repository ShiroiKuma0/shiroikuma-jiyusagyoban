package com.opentasker.ui.charts.huawei

import android.graphics.BitmapFactory
import androidx.collection.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.opentasker.core.huawei.HuaweiWorkoutStore
import com.opentasker.core.huawei.maps.WalkPlot
import com.opentasker.core.huawei.maps.WalkTrack
import com.opentasker.core.huawei.maps.MapCutouts
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A walk drawn over a shared map cutout.
 *
 * ## Why this exists rather than a picture per walk
 *
 * Every walk used to be sent to 白い熊 地図, which drew the route onto its own framing and handed
 * back a 2.5 MB PNG — for a track of about 120 kB. Two walks down the same street produced two
 * pictures of that street, and each one also became a permanent entry in 地図's library. Here the
 * base image is shared by every walk that fits inside it and the route is drawn when it is looked
 * at, so a hundred walks around one neighbourhood cost one map.
 *
 * ## Framing
 *
 * The cutout is a NEIGHBOURHOOD; the view frames the WALK inside it. The route's own pixel bounds
 * are computed in the cutout's coordinates and then fitted to the view, so one wide cutout still
 * draws a short walk tightly. The scale is uniform in both axes — a map stretched to fill a cell is
 * not a map any more.
 */
object WalkMap {

    /** Padding around the route, as a fraction of the fitted box. */
    private const val PAD = 0.08f

    /**
     * Cutouts are shared, so decoding one per cell would decode the same megabyte a dozen times
     * while scrolling. Bounded by bytes rather than by count: the cutouts differ in size.
     */
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** Decode a cutout, cached. Null when the file is missing or not an image. */
    fun bitmapOf(file: File): ImageBitmap? {
        val key = "${file.absolutePath}:${file.lastModified()}"
        cache[key]?.let { return it }
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }

    /** The transform that puts a route's own bounds into a view of [size], uniformly scaled. */
    private data class Fit(val scale: Float, val dx: Float, val dy: Float)

    private fun fit(
        pts: List<Offset>,
        baseW: Int,
        baseH: Int,
        size: Size,
    ): Fit {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (p in pts) {
            x0 = min(x0, p.x); x1 = max(x1, p.x)
            y0 = min(y0, p.y); y1 = max(y1, p.y)
        }
        if (x0 > x1) return Fit(1f, 0f, 0f)
        // Pad, then square up to the view's aspect so the scale can stay uniform.
        val padX = (x1 - x0) * PAD + 8f
        val padY = (y1 - y0) * PAD + 8f
        x0 -= padX; x1 += padX; y0 -= padY; y1 += padY
        val w = max(1f, x1 - x0)
        val h = max(1f, y1 - y0)
        // CONTAIN, not cover. Covering filled the cell nicely and cut the ends off every route
        // whose shape did not match it (白い熊, 2026-08-31) — and a walk with its corners missing
        // is not a picture of that walk. Containing costs nothing here, because the base map is a
        // whole neighbourhood: the route is fitted, and the map keeps drawing past it into
        // whatever slack the cell has left, so the cell still fills with cartography.
        val scale = min(size.width / w, size.height / h)
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        return Fit(scale, size.width / 2f - cx * scale, size.height / 2f - cy * scale)
    }

    /**
     * Draw the base and the route.
     *
     * [points] are latitude/longitude pairs in track order. Anything the cutout cannot place is
     * simply not drawn — a stray point does not get to drag the framing.
     */
    @Composable
    fun Route(
        cutout: MapCutouts.Cutout,
        points: List<Pair<Double, Double>>,
        modifier: Modifier = Modifier,
        line: Color = Color(0xFFFF3B30),
        base: ImageBitmap? = null,
    ) {
        // The cutout's bytes come from the database now, decoded by the caller — there is no file
        // to read here and no path to be wrong about. Without one the route still draws, projected
        // onto the cutout's own transform, which is the "somewhere new" state rather than a fault.
        val image = base
        Canvas(modifier) {
            val bw = image?.width ?: (cutout.tilesW * 256)
            val bh = image?.height ?: (cutout.tilesH * 256)
            val projected = points.map { (lat, lon) ->
                val (x, y) = cutout.pixelOf(lat, lon, bw, bh)
                Offset(x, y)
            }
            if (projected.isEmpty()) return@Canvas
            val f = fit(projected, bw, bh, size)
            clipRect {
                if (image != null) {
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(f.dx.roundToInt(), f.dy.roundToInt()),
                        dstSize = IntSize(
                            (bw * f.scale).roundToInt().coerceAtLeast(1),
                            (bh * f.scale).roundToInt().coerceAtLeast(1),
                        ),
                    )
                }
                drawRoute(projected, f, line)
            }
        }
    }

    /**
     * One walk's picture, wherever it is shown.
     *
     * Three states, and only one of them is a fault:
     *  * a route over its cutout — the ordinary case;
     *  * a route and no cutout — somewhere new, which is an invitation to fetch one, not an error;
     *  * no usable points — the band recorded no fix, and nothing here can help that.
     *
     * [plotOf] is the seam. Supplied, it is called SYNCHRONOUSLY: the screenshot engine renders one
     * frame and never runs a `produceState`, so an asynchronous seam draws every cell empty and the
     * preview quietly stops being evidence of anything.
     */
    @Composable
    fun Picture(
        walk: HuaweiWorkoutStore.Workout,
        /** The route and the cutout under it. Null while it is still being resolved, or if there is none. */
        plot: WalkPlot?,
        /** The cutout's pixels, decoded once per cutout and shared by every walk that crosses it. */
        base: ImageBitmap?,
        modifier: Modifier = Modifier,
        thinTo: Int = 600,
        empty: @Composable () -> Unit = {},
        needsMap: @Composable () -> Unit = {},
    ) {
        when {
            plot == null || !plot.hasTrack -> empty()
            plot.cutout == null -> needsMap()
            else -> Route(
                cutout = plot.cutout,
                points = remember(walk.id, thinTo) { WalkTrack.thin(plot.points, thinTo) },
                base = base,
                modifier = modifier,
            )
        }
    }

    private fun DrawScope.drawRoute(pts: List<Offset>, f: Fit, line: Color) {
        val path = Path()
        var started = false
        for (p in pts) {
            val x = p.x * f.scale + f.dx
            val y = p.y * f.scale + f.dy
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        // Drawn twice: a dark casing under the line so the route stays legible over pale streets
        // AND over dark parkland. 白い熊 is red-green colour-blind, so the route must never rely on
        // hue to separate itself from what is under it — the casing is what does the separating.
        drawPath(path, Color(0xCC000000), style = Stroke(width = 7f))
        drawPath(path, line, style = Stroke(width = 3.5f))
        val first = pts.first()
        val last = pts.last()
        drawCircle(Color(0xCC000000), 6f, Offset(first.x * f.scale + f.dx, first.y * f.scale + f.dy))
        drawCircle(Color(0xFF22C55E), 4f, Offset(first.x * f.scale + f.dx, first.y * f.scale + f.dy))
        drawCircle(Color(0xCC000000), 6f, Offset(last.x * f.scale + f.dx, last.y * f.scale + f.dy))
        drawCircle(Color(0xFFFFFF00), 4f, Offset(last.x * f.scale + f.dx, last.y * f.scale + f.dy))
    }
}
