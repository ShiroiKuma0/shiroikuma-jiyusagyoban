package com.opentasker.ui.charts.compare

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.opentasker.ui.charts.render.PlotFrame
import kotlin.math.abs

/**
 * The one mark this screen adds: the rail beneath the two tracks.
 *
 * ## Why a rail rather than a third curve
 *
 * A month of paired minutes is tens of thousands of marks, and a difference drawn as its own line
 * would be a third thing to read against a scale it does not share with either track. The rail is a
 * COLUMN per screen pixel, carrying the real spread of the differences that landed there and, above
 * all, whether each column had one band or two.
 *
 * The spread is two REAL differences — the smallest and the largest in that column — never a mean of
 * them. A mean difference is not a difference anything observed, and this screen's whole claim is
 * that nothing it draws was invented by averaging.
 *
 * ## The device channel, which is never colour
 *
 * 白い熊 is red-green colour-blind, and ten separable hues do not exist. Which band a column is
 * missing is therefore said by the DIRECTION of a tick — up for Band 11 only, down for Hume only,
 * each pointing at the track that has the data — which survives greyscale, a bad screen and a
 * photograph.
 */
object CompareMarks {

    /**
     * One screen column of the rail.
     *
     * [deltaLo] and [deltaHi] are two differences that actually occurred; [onlyHuawei] and
     * [onlyHume] count the cells in this column that only one band saw.
     */
    data class Column(
        val xPx: Float,
        val deltaLo: Double?,
        val deltaHi: Double?,
        val onlyHuawei: Int,
        val onlyHume: Int,
    )

    /**
     * Fold the join into one column per pixel.
     *
     * Done here rather than in the join because it depends on how wide the rail is drawn — the same
     * data at a different width is a different set of columns, and caching it against the data alone
     * would show yesterday's spacing.
     */
    fun columns(join: CompareData.Join, frame: PlotFrame): List<Column> {
        val byX = LinkedHashMap<Int, MutableList<CompareData.Cell>>()
        for (cell in join.cells) {
            if (!frame.visible(cell.epochMs)) continue
            byX.getOrPut(frame.x(cell.epochMs).toInt()) { ArrayList() } += cell
        }
        return byX.map { (x, cells) ->
            val deltas = cells.mapNotNull { it.delta }
            Column(
                xPx = x.toFloat(),
                deltaLo = deltas.minOrNull(),
                deltaHi = deltas.maxOrNull(),
                onlyHuawei = cells.count { it.huawei != null && it.hume == null },
                onlyHume = cells.count { it.hume != null && it.huawei == null },
            )
        }
    }

    /**
     * Draw the rail into [rect]'s own band of the canvas.
     *
     * [scale] is the largest difference the rail shows at full height; anything beyond is clamped,
     * which is honest because the rail answers "how far apart, and which way" rather than "exactly
     * how far".
     */
    fun DrawScope.drawPairRail(
        frame: PlotFrame,
        columns: List<Column>,
        scale: Double,
        color: Color,
        onlyColor: Color,
    ) {
        if (columns.isEmpty() || scale <= 0.0) return
        val mid = frame.rect.top + frame.rect.height / 2f
        val half = frame.rect.height / 2f
        // Short and faint. These fire on most columns — two bands on different cadences miss
        // each other constantly — so at full strength they read as a picket fence and bury the
        // differences, which are the thing the rail is actually for.
        val tick = (half * 0.30f).coerceAtLeast(2f)
        val onlyInk = onlyColor.copy(alpha = 0.45f)

        // The zero line, so a column above it and a column below it are distinguishable without
        // comparing them to each other.
        drawLine(
            color = color.copy(alpha = 0.35f),
            start = Offset(frame.rect.left, mid),
            end = Offset(frame.rect.right, mid),
            strokeWidth = 1f,
        )

        fun yOf(d: Double) = mid - (d / scale).coerceIn(-1.0, 1.0).toFloat() * half

        for (c in columns) {
            val lo = c.deltaLo
            val hi = c.deltaHi
            if (lo != null && hi != null) {
                val a = yOf(lo)
                val b = yOf(hi)
                drawLine(
                    color = color,
                    start = Offset(c.xPx, a),
                    // A column whose differences all agree still has to be visible: without a
                    // floor it collapses to a single pixel on the zero line and the rail looks empty
                    // exactly when the two bands agree best.
                    end = Offset(c.xPx, if (abs(a - b) < 3f) b + 3f else b),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
            }
            // Direction is the device channel: the tick points at the track that HAS the reading.
            if (c.onlyHuawei > 0) {
                drawLine(
                    color = onlyInk,
                    start = Offset(c.xPx, frame.rect.top),
                    end = Offset(c.xPx, frame.rect.top + tick),
                    strokeWidth = 1f,
                )
            }
            if (c.onlyHume > 0) {
                drawLine(
                    color = onlyInk,
                    start = Offset(c.xPx, frame.rect.bottom - tick),
                    end = Offset(c.xPx, frame.rect.bottom),
                    strokeWidth = 1f,
                )
            }
        }
    }

    /**
     * A track's own label mark: filled for the Band 11, hollow for the Hume.
     *
     * The same distinction the readings themselves carry, so the legend and the plot say the device
     * the same way rather than needing to be learned separately.
     */
    fun DrawScope.drawDeviceKey(at: Offset, radius: Float, color: Color, hollow: Boolean) {
        if (hollow) {
            drawCircle(color, radius = radius, center = at, style = Stroke(width = radius * 0.5f))
        } else {
            drawCircle(color, radius = radius, center = at)
        }
    }
}
