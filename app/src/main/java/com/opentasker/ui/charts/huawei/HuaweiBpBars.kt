package com.opentasker.ui.charts.huawei

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A cuff reading graded on the shared 1–5 scale by the European (ESH 2018) office categories.
 *
 * Each pressure is graded on its own, and the chart shows both grades. [of] is the guideline's own
 * rule for the reading as a whole — the highest category either of its numbers reaches. ESH's grade 2 and
 * grade 3 hypertension (160–179/100–109 and 180/110 or more) share the 5: five steps is what the
 * scale has, and both are "see a doctor", which is what the 5 says.
 */
object BpGrade {
    fun of(systolic: Double, diastolic: Double): Int = maxOf(ofSystolic(systolic), ofDiastolic(diastolic))

    fun ofSystolic(v: Double): Int = when {
        v < 120 -> 1
        v < 130 -> 2
        v < 140 -> 3
        v < 160 -> 4
        else -> 5
    }

    fun ofDiastolic(v: Double): Int = when {
        v < 80 -> 1
        v < 85 -> 2
        v < 90 -> 3
        v < 100 -> 4
        else -> 5
    }

    val LABELS = listOf(
        Loc("Optimal < 120/80", "至適 120/80 未満"),
        Loc("Normal 120–129/80–84", "正常 120–129/80–84"),
        Loc("High-normal 130–139/85–89", "正常高値 130–139/85–89"),
        Loc("High 140–159/90–99", "高血圧 140–159/90–99"),
        Loc("High 160/100 or more", "高血圧 160/100 以上"),
    )
}

/** The five grades as pills in their own colours, with the thresholds written on them. */
@Composable
fun BpScaleLegend(modifier: Modifier = Modifier) {
    val lang = LocalBandLanguage.current
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BpGrade.LABELS.forEachIndexed { i, label ->
            Text(
                "${i + 1} ${label[lang]}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = ChartPalette.scaleInk(i + 1),
                modifier = Modifier
                    .background(ChartPalette.scale(i + 1), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** Space a bar needs for its numbers to stay legible; fewer fit and the strip scrolls instead. */
internal val MIN_SLOT = 32.dp

/**
 * The widest a reading's slot gets. Past this a bar stops reading as a reading and starts reading
 * as a block: on the unfolded screen a week of readings would otherwise spread to 120 dp apiece.
 */
internal val MAX_SLOT = 50.dp
internal val MAX_BAR = 42.dp

/** The space between two bars — narrow, so the readings read as one row of a record. */
internal val BAR_GAP = 8.dp
internal val DAY_ROW = 22.dp
internal val GUTTER = 30.dp
private val GUIDES = listOf(140.0, 90.0)

/** The vertical mapping both canvases share, so a guide's number sits exactly on its line. */
internal class BpFrame(height: Float, dayRow: Float, private val top: Float, private val lo: Double, private val hi: Double) {
    private val plotH = height - dayRow - top
    fun y(v: Double) = top + ((hi - v) / (hi - lo) * plotH).toFloat()
}

/**
 * Every cuff reading as one bar, systolic at its top and diastolic at its foot, each number written
 * inside its own half and each half filled in that number's own grade — a reading's two pressures
 * often fall in different categories, and one colour for both would hide which of them it is.
 *
 * The x axis is readings, not time: a reading a day leaves a day's width of empty chart between
 * bars on a time axis, which is what made the hourly dumbbells unreadable. Bars of one day stand
 * together and a small gap opens where the day changes, and the axis below names only the day of
 * the month — the month itself is written once, under the first reading in it. When there are more
 * readings than fit at a legible width, the strip scrolls sideways and opens on the newest.
 *
 * The y axis is fixed at 60–170 mmHg (widened only for a reading outside it), so a bar's height and
 * position mean the same thing from one week to the next. Dashed guides mark 140 and 90, where
 * "high" begins; [detailed] adds a faint line and a number every 10 mmHg.
 *
 * [height] is a floor: on a wide screen the chart grows with the width, so the unfolded phone keeps
 * the folded one's proportions — a tall chart of narrow bars — instead of a flat strip.
 *
 * @param readings oldest first.
 */
@Composable
fun BpReadingBars(
    readings: List<HuaweiBpReading>,
    height: Dp,
    modifier: Modifier = Modifier,
    detailed: Boolean = false,
) {
    if (readings.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val style = LocalChartStyle.current
    val lang = LocalBandLanguage.current
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(readings) { readings.map { Instant.ofEpochMilli(it.tMs).atZone(zone).toLocalDate() } }
    // A day boundary costs this much extra, in slots: enough to read as a gap, not as a missing day.
    val dayGap = 0.16f
    val units = remember(days) { readings.size + dayGap * days.zipWithNext().count { (a, b) -> a != b } }

    val lo = minOf(60.0, readings.minOf { it.diastolic } - 5)
    val hi = maxOf(170.0, readings.maxOf { it.systolic } + 5)
    val guide = style.axisText.copy(alpha = 0.55f)
    val grid = style.axisText.copy(alpha = 0.14f)
    val gridValues = if (!detailed) emptyList() else
        generateSequence(Math.ceil(lo / 10) * 10) { it + 10 }.takeWhile { it <= hi }.filter { it !in GUIDES }.toList()

    BoxWithConstraints(modifier) {
        val plotWidth = maxWidth - GUTTER
        val chartHeight = maxOf(height, maxWidth * (if (detailed) 0.6f else 0.5f))
        val slotDp = (plotWidth / units).coerceIn(MIN_SLOT, MAX_SLOT)
        val stripWidth = maxOf(slotDp * units, plotWidth)
        Row {
            // The scale's numbers stand in a column of their own that does not scroll, so they are
            // there whichever readings the strip is showing and no bar ever covers them.
            Canvas(Modifier.width(GUTTER).height(chartHeight)) {
                val frame = BpFrame(size.height, DAY_ROW.toPx(), 8.dp.toPx(), lo, hi)
                for (v in gridValues + GUIDES) {
                    val strong = v in GUIDES
                    val t = measurer.measure(v.toInt().toString(), TextStyle(
                        fontSize = if (strong) 11.sp else 10.sp,
                        fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
                        color = if (strong) guide else style.axisText.copy(alpha = 0.4f),
                    ))
                    drawText(t, topLeft = Offset(size.width - t.size.width - 4.dp.toPx(), frame.y(v) - t.size.height / 2f))
                }
            }
            val scroll: ScrollState = rememberScrollState()
            LaunchedEffect(readings.size, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
            Canvas(
                Modifier
                    .width(plotWidth)
                    .height(chartHeight)
                    .horizontalScroll(scroll)
                    .width(stripWidth)
                    .height(chartHeight),
            ) {
                val dayRow = DAY_ROW.toPx()
                val frame = BpFrame(size.height, dayRow, 8.dp.toPx(), lo, hi)
                fun y(v: Double) = frame.y(v)

                // The scale first, so the bars sit over it.
                for (v in gridValues) drawLine(grid, Offset(0f, y(v)), Offset(size.width, y(v)), 1.dp.toPx())
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                for (v in GUIDES) {
                    drawLine(guide, Offset(0f, y(v)), Offset(size.width, y(v)), 1.dp.toPx(), pathEffect = dash)
                }

                val slot = slotDp.toPx()
                // The slot less a narrow gap, capped so a bar never turns into a block.
                val barW = minOf(slot - BAR_GAP.toPx(), MAX_BAR.toPx())
                val numSize = (barW / 2.6f).toSp().value.coerceIn(10f, 18f).sp
                var x = 0f
                var dayStart = 0f
                var dayStartIndex = 0
                readings.forEachIndexed { i, r ->
                    if (i > 0 && days[i] != days[i - 1]) x += dayGap * slot
                    val cx = x + slot / 2
                    if (i == 0 || days[i] != days[i - 1]) dayStartIndex = i
                    val sysGrade = BpGrade.ofSystolic(r.systolic)
                    val diaGrade = BpGrade.ofDiastolic(r.diastolic)
                    val yTop = y(r.systolic)
                    val yBot = y(r.diastolic)
                    val yMid = (yTop + yBot) / 2
                    val left = cx - barW / 2
                    val radius = CornerRadius(minOf(barW / 3, 10.dp.toPx()))
                    if (sysGrade == diaGrade) {
                        // One grade, one shape: two halves of one colour leave an antialiased seam.
                        drawRoundRect(ChartPalette.scale(sysGrade), Offset(left, yTop), Size(barW, yBot - yTop), radius)
                        // The halves still meet in the middle: a splitter in the bar's own ink marks it.
                        drawLine(ChartPalette.scaleInk(sysGrade).copy(alpha = 0.6f), Offset(left, yMid),
                            Offset(left + barW, yMid), 1.5.dp.toPx())
                    } else {
                        // Each half rounded at its outer end only, so the two meet square in the middle.
                        drawPath(Path().apply {
                            addRoundRect(RoundRect(Rect(left, yTop, left + barW, yMid), topLeft = radius, topRight = radius))
                        }, ChartPalette.scale(sysGrade))
                        drawPath(Path().apply {
                            addRoundRect(RoundRect(Rect(left, yMid, left + barW, yBot), bottomLeft = radius, bottomRight = radius))
                        }, ChartPalette.scale(diaGrade))
                    }

                    val numStyle = TextStyle(fontSize = numSize, fontWeight = FontWeight.Bold)
                    val sys = measurer.measure(r.systolic.toInt().toString(), numStyle)
                    val dia = measurer.measure(r.diastolic.toInt().toString(), numStyle)
                    val pad = 3.dp.toPx()
                    val inside = yMid - yTop >= sys.size.height + 2 * pad
                    if (inside) {
                        drawText(sys, ChartPalette.scaleInk(sysGrade), Offset(cx - sys.size.width / 2f, yTop + pad))
                        drawText(dia, ChartPalette.scaleInk(diaGrade), Offset(cx - dia.size.width / 2f, yBot - pad - dia.size.height))
                    } else {
                        // Too short to hold both: the numbers stand just outside its ends, each in
                        // its own half's colour, rather than overlapping.
                        drawText(sys, ChartPalette.scale(sysGrade), Offset(cx - sys.size.width / 2f, yTop - sys.size.height - 1))
                        drawText(dia, ChartPalette.scale(diaGrade), Offset(cx - dia.size.width / 2f, yBot + 1))
                    }

                    // The day, centred under its readings; the month with it on the first day of it.
                    if (i == 0 || days[i] != days[i - 1]) dayStart = cx
                    if (i == readings.lastIndex || days[i + 1] != days[i]) {
                        val d = days[i]
                        val firstOfMonth = dayStartIndex == 0 || days[dayStartIndex - 1].month != d.month
                        val text = if (firstOfMonth) monthDay(d, lang.tag.startsWith("ja")) else d.dayOfMonth.toString()
                        val t = measurer.measure(text, TextStyle(fontSize = 12.sp,
                            fontWeight = if (firstOfMonth) FontWeight.Bold else FontWeight.Normal))
                        val mid = (dayStart + cx) / 2
                        // Kept inside the strip: centred under the first day, its month ran off the left edge
                        // and read ")ct 18" (2026-10-26).
                        val lx = (mid - t.size.width / 2f).coerceIn(0f, maxOf(0f, size.width - t.size.width))
                        drawText(t, style.axisText, Offset(lx, size.height - dayRow + 4.dp.toPx()))
                    }
                    x += slot
                }
            }
        }
    }
}

internal fun monthDay(d: LocalDate, ja: Boolean): String =
    if (ja) "${d.monthValue}/${d.dayOfMonth}" else
        d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + d.dayOfMonth
