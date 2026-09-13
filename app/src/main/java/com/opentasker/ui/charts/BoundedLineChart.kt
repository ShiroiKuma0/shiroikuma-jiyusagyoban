package com.opentasker.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A small bounded chart with both axes — the shape every graph on this screen's cards uses.
 *
 * ## Why it exists
 *
 * 白い熊, 2026-09-12: a line must not *"float-inside-nowhere"*; it should *"have some x-y axes, be
 * bounded, look more like a professional graph."* That applied to the night's heart-rate curve and
 * then immediately to the per-night history behind every row, so the drawing is here rather than
 * twice over in two files that would drift apart on the first change.
 *
 * It is deliberately NOT the big `MetricPlots` pipeline. That one exists to clean raw sample series —
 * Hampel filters, slew gates, cadence-derived gap detection — and everything drawn here is already a
 * derived value, one per night or one per twelfth of a night. Running a slew gate over nightly sleep
 * minutes would be applying a sensor correction to an arithmetic result.
 *
 * ## What it guarantees
 *
 * - **A bounded plot.** Left and bottom axis rules, labelled, with a recessive grid behind the data.
 * - **A caller-chosen y span.** [yLo]/[yHi] are given, never fitted to the data, because fitting is
 *   how a flat series is drawn as a dramatic one — the trap the night curve exists to avoid.
 * - **One series, so no legend.** The caption names it. A second series would need one.
 * - **The app's own tokens.** Grid, axis ink and line width come from [LocalChartStyle], so a chart
 *   here matches every other chart in 健康 and follows 白い熊's own chart customisation.
 */
@Composable
fun BoundedLineChart(
    values: List<Double>,
    /** Inclusive y bounds. Chosen by the caller — see the class note on why this is not fitted. */
    yLo: Double,
    yHi: Double,
    /** Spacing of the labelled horizontal gridlines, in the y unit. */
    yStep: Double,
    color: Color,
    /** Labels for the left edge, middle and right edge of the x axis. Empty entries are skipped. */
    xLabels: Triple<String, String, String>,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
    /** How a y gridline value is written. Defaults to a whole number. */
    formatY: (Double) -> String = { it.roundToInt().toString() },
    /**
     * An optional shaded band — the "usual" range, drawn behind everything.
     *
     * Behind rather than over, and faint: it is the backdrop a point is judged against, and a band
     * drawn over the data would hide the very points it exists to give context to.
     */
    usualBand: ClosedFloatingPointRange<Double>? = null,
    /** Indices to mark with a dot. The last point is usually one of them. */
    marked: Set<Int> = emptySet(),
    /** Drawn as a dotted horizontal rule — the baseline the series is compared against. */
    baseline: Double? = null,
) {
    val style = LocalChartStyle.current
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = style.axisTextSize, color = style.axisText)
    if (values.size < 2 || yHi <= yLo) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            // The gutter is MEASURED from the widest label rather than guessed, so a three-digit
            // value does not clip and a two-digit one does not leave a hole.
            val widest = listOf(yLo, yHi).maxOf { measurer.measure(formatY(it), axisStyle).size.width }
            val xLabelHeight = measurer.measure("00:00", axisStyle).size.height.toFloat()
            val left = widest + 8f
            val bottom = size.height - xLabelHeight - 6f
            if (bottom <= 0f || left >= size.width) return@Canvas
            val plotW = size.width - left
            val span = yHi - yLo

            fun y(v: Double): Float =
                (bottom * (1f - ((v - yLo) / span).toFloat())).coerceIn(0f, bottom)

            usualBand?.let { band ->
                val top = y(band.endInclusive)
                val bot = y(band.start)
                drawRect(
                    color.copy(alpha = 0.10f),
                    topLeft = Offset(left, top),
                    size = Size(plotW, (bot - top).coerceAtLeast(1f)),
                )
            }

            var g = ceil(yLo / yStep) * yStep
            while (g <= yHi + 1e-9) {
                val gy = y(g)
                drawLine(style.grid, Offset(left, gy), Offset(size.width, gy), strokeWidth = 1f)
                val laid = measurer.measure(formatY(g), axisStyle)
                drawText(laid, topLeft = Offset(left - 8f - laid.size.width, gy - laid.size.height / 2f))
                g += yStep
            }

            baseline?.takeIf { it in yLo..yHi }?.let { b ->
                val by = y(b)
                // Dashes drawn by hand: one short segment every eight pixels reads as "a reference"
                // rather than as another measurement, at any width.
                var x = left
                while (x < size.width) {
                    drawLine(
                        style.axisText.copy(alpha = 0.45f),
                        Offset(x, by), Offset((x + 4f).coerceAtMost(size.width), by),
                        strokeWidth = 1.5f,
                    )
                    x += 8f
                }
            }

            val axis = style.axisText.copy(alpha = 0.55f)
            drawLine(axis, Offset(left, 0f), Offset(left, bottom), strokeWidth = 1.5f)
            drawLine(axis, Offset(left, bottom), Offset(size.width, bottom), strokeWidth = 1.5f)

            // Three x labels — the two ends and the middle. Not one per point: at this size a dozen
            // collide, and the ends plus the midpoint are what a reader actually wants.
            listOf(0f to xLabels.first, 0.5f to xLabels.second, 1f to xLabels.third)
                .forEach { (frac, label) ->
                    if (label.isEmpty()) return@forEach
                    val laid = measurer.measure(label, axisStyle)
                    val cx = left + plotW * frac
                    val x = when (frac) {
                        0f -> cx
                        1f -> cx - laid.size.width
                        else -> cx - laid.size.width / 2f
                    }
                    drawLine(axis, Offset(cx, bottom), Offset(cx, bottom + 3f), strokeWidth = 1.5f)
                    drawText(
                        laid,
                        topLeft = Offset(x.coerceIn(0f, size.width - laid.size.width), bottom + 5f),
                    )
                }

            val step = plotW / (values.size - 1)
            val path = Path().apply {
                moveTo(left, y(values.first()))
                values.forEachIndexed { i, v -> if (i > 0) lineTo(left + step * i, y(v)) }
            }
            drawPath(path, color, style = Stroke(width = style.lineWidth.toPx(), cap = StrokeCap.Round))
            for (i in marked) {
                values.getOrNull(i)?.let {
                    drawCircle(color, style.dotSize.toPx(), Offset(left + step * i, y(it)))
                }
            }
        }
    }
}
