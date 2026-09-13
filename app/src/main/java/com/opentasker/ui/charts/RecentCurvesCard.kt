package com.opentasker.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The last few nights' heart-rate curves, drawn over one another.
 *
 * ## Why this, on the swing's history page
 *
 * Every other row's history is a number per night, and a line of those is the whole story. The
 * swing's is not: it compresses a whole curve to one figure, and two nights with the same swing can
 * have fallen at completely different times. So the page that opens from the night chart carries the
 * curves themselves — which is what "the history of this shape" means, and the only form in which
 * last night can be compared to the nights around it as a shape rather than as a scalar.
 *
 * ## What makes it readable
 *
 * **Every curve on ONE scale, normalised to its own bed time.** A night that began at 60 bpm and one
 * that began at 80 would otherwise sit in different parts of the frame and could not be compared at
 * all; drawn as change-from-bedtime they share an origin and the comparison is about descent, which
 * is the quantity in question. The y axis is therefore in bpm BELOW where the night started.
 *
 * **Last night is opaque and the rest recede.** Five equally weighted lines are a thicket. The older
 * nights are there as context, not as five things to read, so they are drawn faint and last night is
 * drawn at full strength over them.
 *
 * **The x axis is the night's own length, not the clock.** Nights start at different times and run
 * for different spans; laying them on a clock would make a short night a short line and invite
 * reading length as depth. So it runs bed to waking, 0–100 %.
 */
@Composable
fun RecentCurvesCard(
    /** `(nightEndMs, curve)`, oldest first. The last entry is drawn as last night. */
    curves: List<Pair<Long, List<Double>>>,
    zone: java.time.ZoneId,
) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val measurer = rememberTextMeasurer()
    val accent = ChartPalette.HEART_RATE
    val usable = curves.filter { it.second.size >= 2 }
    if (usable.size < 2) return

    // Change from the night's own start, so five nights share an origin — see the class note.
    val relative = usable.map { (ms, c) -> ms to c.map { it - c.first() } }
    val lowest = relative.minOf { it.second.min() }
    // Rounded outward to a whole ten, so the bottom gridline is a round number.
    val yLo = kotlin.math.floor((lowest - 2) / 10.0) * 10.0
    val axisStyle = TextStyle(fontSize = style.axisTextSize, color = style.axisText)

    SectionCard(accent = accent) {
        SubHeading(BandText.curvesTitle[lang])
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val widest = measurer.measure(yLo.roundToInt().toString(), axisStyle).size.width
            val labelH = measurer.measure("100%", axisStyle).size.height.toFloat()
            val left = widest + 8f
            val bottom = size.height - labelH - 6f
            if (bottom <= 0f || left >= size.width) return@Canvas
            val plotW = size.width - left
            val yHi = 6.0   // a little headroom: a night that rises above its bed-time level

            fun y(v: Double): Float =
                (bottom * (1f - ((v - yLo) / (yHi - yLo)).toFloat())).coerceIn(0f, bottom)

            var g = kotlin.math.ceil(yLo / 10.0) * 10.0
            while (g <= yHi) {
                val gy = y(g)
                drawLine(style.grid, Offset(left, gy), Offset(size.width, gy), strokeWidth = 1f)
                val laid = measurer.measure(g.roundToInt().toString(), axisStyle)
                drawText(laid, topLeft = Offset(left - 8f - laid.size.width, gy - laid.size.height / 2f))
                g += 10.0
            }
            val axis = style.axisText.copy(alpha = 0.55f)
            drawLine(axis, Offset(left, 0f), Offset(left, bottom), strokeWidth = 1.5f)
            drawLine(axis, Offset(left, bottom), Offset(size.width, bottom), strokeWidth = 1.5f)
            listOf(0f to "0%", 0.5f to "50%", 1f to "100%").forEach { (frac, label) ->
                val laid = measurer.measure(label, axisStyle)
                val cx = left + plotW * frac
                val x = when (frac) {
                    0f -> cx
                    1f -> cx - laid.size.width
                    else -> cx - laid.size.width / 2f
                }
                drawLine(axis, Offset(cx, bottom), Offset(cx, bottom + 3f), strokeWidth = 1.5f)
                drawText(laid, topLeft = Offset(x.coerceIn(0f, size.width - laid.size.width), bottom + 5f))
            }

            relative.forEachIndexed { i, (_, c) ->
                val last = i == relative.lastIndex
                val step = plotW / (c.size - 1)
                val path = Path().apply {
                    moveTo(left, y(c.first()))
                    c.forEachIndexed { n, v -> if (n > 0) lineTo(left + step * n, y(v)) }
                }
                drawPath(
                    path,
                    if (last) accent else accent.copy(alpha = 0.28f),
                    style = Stroke(
                        width = if (last) style.lineWidth.toPx() else style.lineWidth.toPx() * 0.7f,
                        cap = StrokeCap.Round,
                    ),
                )
            }
        }
        NoteText(BandText.curvesNote[lang].format(relative.size))
        // Named, because "the faint ones" is not an identification and a legend of five dates would
        // out-weigh the picture. Oldest to newest, which is the order they were drawn.
        NoteText(
            usable.joinToString(" · ") {
                java.time.Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate().toString()
            },
        )
    }
}
