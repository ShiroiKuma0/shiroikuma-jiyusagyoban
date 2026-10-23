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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.HealthIndex
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import java.time.Instant
import java.time.ZoneId

/** One reading of a single-number measurement: its time and its value. */
data class HuaweiDayValue(val tMs: Long, val value: Double)

/**
 * How a single-number measurement is drawn as bars: its scale, its guides, its grade and its words.
 *
 * The grade is the HEALTH INDEX's own scoring of that quantity, put through the index's own 1–5
 * steps — so a bar here, the component's bar on the index card and its word there can never
 * disagree. [legend] spells out where each step begins, in the quantity's own unit.
 */
data class ValueBarsScale(
    val lo: Double,
    val hi: Double,
    /** Values marked by a dashed line and a bold number in the gutter. */
    val guides: List<Double>,
    /** The faint grid in the detailed chart, every this many units. */
    val gridStep: Double,
    val decimals: Int,
    val grade: (Double) -> Int,
    val legend: List<Loc>,
) {
    fun format(v: Double): String = if (decimals == 0) "${Math.round(v)}" else String.format("%.${decimals}f", v)

    companion object {
        /** The watch's resting heart rate, on the index's 50–85 bpm ramp. */
        val RESTING_HR = ValueBarsScale(
            lo = 40.0, hi = 90.0, guides = emptyList(), gridStep = 5.0, decimals = 0,
            grade = { HealthIndex.step(HealthIndex.ramp(it, HealthIndex.HR_BEST, HealthIndex.HR_WORST)) },
            legend = listOf(
                Loc("55 bpm or below", "55 bpm 以下"), Loc("56–60", "56–60"), Loc("61–65", "61–65"),
                Loc("66–71", "66–71"), Loc("72 or more", "72 以上"),
            ),
        )

        /** Pulse-wave velocity, on the index's ramp between Huawei's own 8 and 10 m/s. */
        val PWV = ValueBarsScale(
            lo = 4.0, hi = 12.0, guides = listOf(HealthIndex.PWV_BEST, HealthIndex.PWV_WORST), gridStep = 1.0,
            decimals = 1,
            grade = { HealthIndex.step(HealthIndex.ramp(it, HealthIndex.PWV_BEST, HealthIndex.PWV_WORST)) },
            legend = listOf(
                Loc("below 8.3 m/s", "8.3 m/s 未満"), Loc("8.3–8.6", "8.3–8.6"), Loc("8.6–8.9", "8.6–8.9"),
                Loc("8.9–9.2", "8.9–9.2"), Loc("9.2 or more", "9.2 以上"),
            ),
        )
    }
}

/** The five steps as pills in their own colours, each with where it begins. */
@Composable
fun ValueScaleLegend(scale: ValueBarsScale, modifier: Modifier = Modifier) {
    val lang = LocalBandLanguage.current
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        scale.legend.forEachIndexed { i, label ->
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

/**
 * Every reading as one bar standing on the chart's floor, its number written inside the top of it
 * and the bar filled in its grade — the blood-pressure chart's layout for a single number: the x
 * axis is readings, not time, the day under each and the month under the first of it, the gutter
 * fixed while the strip scrolls and opens on the newest.
 *
 * @param values oldest first.
 */
@Composable
fun ValueBars(
    values: List<HuaweiDayValue>,
    scale: ValueBarsScale,
    height: Dp,
    modifier: Modifier = Modifier,
    detailed: Boolean = false,
) {
    if (values.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val style = LocalChartStyle.current
    val lang = LocalBandLanguage.current
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(values) { values.map { Instant.ofEpochMilli(it.tMs).atZone(zone).toLocalDate() } }
    val dayGap = 0.16f
    val units = remember(days) { values.size + dayGap * days.zipWithNext().count { (a, b) -> a != b } }
    val lo = minOf(scale.lo, values.minOf { it.value } - scale.gridStep)
    val hi = maxOf(scale.hi, values.maxOf { it.value } + scale.gridStep)
    val guide = style.axisText.copy(alpha = 0.55f)
    val grid = style.axisText.copy(alpha = 0.14f)
    val gridValues = if (!detailed) emptyList() else
        generateSequence(Math.ceil(lo / scale.gridStep) * scale.gridStep) { it + scale.gridStep }
            .takeWhile { it <= hi + 1e-9 }.filter { v -> scale.guides.none { kotlin.math.abs(it - v) < 1e-9 } }.toList()

    BoxWithConstraints(modifier) {
        val plotWidth = maxWidth - GUTTER
        val chartHeight = maxOf(height, maxWidth * (if (detailed) 0.6f else 0.5f))
        val slotDp = (plotWidth / units).coerceIn(MIN_SLOT, MAX_SLOT)
        val stripWidth = maxOf(slotDp * units, plotWidth)
        Row {
            Canvas(Modifier.width(GUTTER).height(chartHeight)) {
                val frame = BpFrame(size.height, DAY_ROW.toPx(), 8.dp.toPx(), lo, hi)
                for (v in gridValues + scale.guides) {
                    val strong = v in scale.guides
                    val t = measurer.measure(scale.format(v), TextStyle(
                        fontSize = if (strong) 11.sp else 10.sp,
                        fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
                        color = if (strong) guide else style.axisText.copy(alpha = 0.4f),
                    ))
                    drawText(t, topLeft = Offset(size.width - t.size.width - 4.dp.toPx(), frame.y(v) - t.size.height / 2f))
                }
            }
            val scroll: ScrollState = rememberScrollState()
            LaunchedEffect(values.size, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
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
                val floor = frame.y(lo)
                for (v in gridValues) drawLine(grid, Offset(0f, frame.y(v)), Offset(size.width, frame.y(v)), 1.dp.toPx())
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                for (v in scale.guides) {
                    drawLine(guide, Offset(0f, frame.y(v)), Offset(size.width, frame.y(v)), 1.dp.toPx(), pathEffect = dash)
                }

                val slot = slotDp.toPx()
                val barW = minOf(slot - BAR_GAP.toPx(), MAX_BAR.toPx())
                val numSize = (barW / 2.6f).toSp().value.coerceIn(10f, 18f).sp
                var x = 0f
                var dayStart = 0f
                var dayStartIndex = 0
                values.forEachIndexed { i, r ->
                    if (i > 0 && days[i] != days[i - 1]) x += dayGap * slot
                    val cx = x + slot / 2
                    if (i == 0 || days[i] != days[i - 1]) dayStartIndex = i
                    val step = scale.grade(r.value)
                    val top = frame.y(r.value)
                    val left = cx - barW / 2
                    val radius = CornerRadius(minOf(barW / 3, 10.dp.toPx()))
                    // Rounded at the top only: the bar stands on the floor of the chart.
                    drawPath(Path().apply {
                        addRoundRect(RoundRect(Rect(left, top, left + barW, floor), topLeft = radius, topRight = radius))
                    }, ChartPalette.scale(step))
                    val num = measurer.measure(scale.format(r.value), TextStyle(fontSize = numSize, fontWeight = FontWeight.Bold))
                    val pad = 3.dp.toPx()
                    if (floor - top >= num.size.height + 2 * pad) {
                        drawText(num, ChartPalette.scaleInk(step), Offset(cx - num.size.width / 2f, top + pad))
                    } else {
                        drawText(num, ChartPalette.scale(step), Offset(cx - num.size.width / 2f, top - num.size.height - 1))
                    }

                    if (i == 0 || days[i] != days[i - 1]) dayStart = cx
                    if (i == values.lastIndex || days[i + 1] != days[i]) {
                        val d = days[i]
                        val firstOfMonth = dayStartIndex == 0 || days[dayStartIndex - 1].month != d.month
                        val text = if (firstOfMonth) monthDay(d, lang.tag.startsWith("ja")) else d.dayOfMonth.toString()
                        val t = measurer.measure(text, TextStyle(fontSize = 12.sp,
                            fontWeight = if (firstOfMonth) FontWeight.Bold else FontWeight.Normal))
                        val mid = (dayStart + cx) / 2
                        drawText(t, style.axisText, Offset(mid - t.size.width / 2f, size.height - dayRow + 4.dp.toPx()))
                    }
                    x += slot
                }
            }
        }
    }
}
