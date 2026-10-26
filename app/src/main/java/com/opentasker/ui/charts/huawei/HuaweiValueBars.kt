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
import com.opentasker.ui.charts.Grade
import com.opentasker.ui.charts.HealthIndex
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.charts.RecoveryMarker
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
        /**
         * The watch's resting heart rate, on Jensen's decades — [Grade], the ladder every other screen
         * grades it on. It was the index's old 50–85 bpm ramp until 2026-10-26, which called 72 bpm a 5
         * here while the strip and the index called it a 4.
         */
        val RESTING_HR = ValueBarsScale(
            lo = 40.0, hi = 90.0, guides = emptyList(), gridStep = 5.0, decimals = 0,
            grade = { Grade.ladder(RecoveryMarker.RESTING_HR, it) ?: 3 },
            legend = listOf(
                Loc("below 50 bpm", "50 bpm 未満"), Loc("50–59", "50–59"), Loc("60–69", "60–69"),
                Loc("70–79", "70–79"), Loc("80 or more", "80 以上"),
            ),
        )

        /**
         * Nightly skin temperature. There is no absolute ladder for a wrist reading, so the bars are
         * coloured by each night's own within-person grade, passed in as `steps`; [grade] is never
         * consulted for it. The floor sits near the readings so a few tenths are visible.
         */
        val SKIN_TEMPERATURE = ValueBarsScale(
            lo = 32.0, hi = 34.0, guides = emptyList(), gridStep = 0.5, decimals = 1,
            grade = { 3 },
            legend = listOf(
                Loc("well below usual", "平常よりかなり低い"), Loc("below usual", "平常より低い"),
                Loc("usual", "平常"), Loc("above usual", "平常より高い"), Loc("well above usual", "平常よりかなり高い"),
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
 * @param steps each bar's grade, in [values]' order, when it is not a function of the value alone (a
 *   within-person grade); a null there draws the bar ungraded. Omitted, [ValueBarsScale.grade] decides.
 */
@Composable
fun ValueBars(
    values: List<HuaweiDayValue>,
    scale: ValueBarsScale,
    height: Dp,
    modifier: Modifier = Modifier,
    detailed: Boolean = false,
    steps: List<Int?>? = null,
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
    // A night with no grade yet (a baseline still collecting): drawn, never in a grade's colour.
    val ungraded = style.axisText.copy(alpha = 0.3f)
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
                    val step = if (steps != null) steps.getOrNull(i) else scale.grade(r.value)
                    val top = frame.y(r.value)
                    val left = cx - barW / 2
                    val radius = CornerRadius(minOf(barW / 3, 10.dp.toPx()))
                    // Rounded at the top only: the bar stands on the floor of the chart.
                    drawPath(Path().apply {
                        addRoundRect(RoundRect(Rect(left, top, left + barW, floor), topLeft = radius, topRight = radius))
                    }, step?.let(ChartPalette::scale) ?: ungraded)
                    val num = measurer.measure(scale.format(r.value), TextStyle(fontSize = numSize, fontWeight = FontWeight.Bold))
                    val pad = 3.dp.toPx()
                    if (floor - top >= num.size.height + 2 * pad) {
                        drawText(num, step?.let(ChartPalette::scaleInk) ?: style.axisText, Offset(cx - num.size.width / 2f, top + pad))
                    } else {
                        drawText(num, step?.let(ChartPalette::scale) ?: style.axisText, Offset(cx - num.size.width / 2f, top - num.size.height - 1))
                    }

                    if (i == 0 || days[i] != days[i - 1]) dayStart = cx
                    if (i == values.lastIndex || days[i + 1] != days[i]) {
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
