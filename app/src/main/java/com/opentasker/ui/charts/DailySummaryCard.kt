package com.opentasker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.ui.theme.isNarrowScreen
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The day-by-day table.
 *
 * Deliberately a **table, not a chart**. Its job is comparison between days, and a reader comparing
 * Tuesday with Monday wants two numbers side by side, not two points on a line they have to measure
 * against an axis. The charts above already do the within-day shape.
 *
 * The index column is the reason this earns its place: one day's 健康指数 says very little, and a
 * column of them says whether anything is moving.
 *
 * Months are ruled off as they are reached — see [MonthDivider]. The table runs sixty days, which is
 * two month boundaries, and without them a reader scrolling back has only the year-month on every
 * line to go by, which is to say nothing at all.
 */
@Composable
fun DailySummaryCard(days: List<DaySummary>) {
    val lang = LocalBandLanguage.current
    if (days.isEmpty()) return
    val cols = dayColumns()

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.padding(horizontal = cols.cardPad.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                BandText.byDay[lang],
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            HeaderRow(lang, cols)
            days.forEachIndexed { i, d ->
                val ym = YearMonth.from(d.date)
                if (i == 0 || ym != YearMonth.from(days[i - 1].date)) {
                    MonthDivider(ym, topPadding = if (i == 0) 0.dp else 8.dp)
                }
                DayRow(d, lang, cols)
            }
            Text(
                BandText.byDayNote[lang],
                style = MaterialTheme.typography.bodySmall,
                color = LocalChartStyle.current.axisText,
            )
        }
    }
}

@Composable
private fun HeaderRow(lang: BandLanguage, cols: DayColumns) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Cell(BandText.colDay[lang], cols.date, cols, header = true)
        Cell(BandText.colIndex[lang], cols.num, cols, header = true)
        Cell(BandText.colResting[lang], cols.num, cols, header = true)
        Cell(BandText.colSleep[lang], cols.sleep, cols, header = true)
        Cell(BandText.colSteps[lang], cols.step, cols, header = true)
        Cell(BandText.colSpo2[lang], cols.num, cols, header = true)
    }
}

@Composable
private fun DayRow(d: DaySummary, lang: BandLanguage, cols: DayColumns) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(cols.date.dp)) {
            Text(
                // Narrow: no year on the line — the month rule above carries it. See
                // [nightDateParts] for the same trade on the register's table.
                if (cols.narrow) {
                    "%02d-%02d".format(d.date.monthValue, d.date.dayOfMonth)
                } else {
                    d.date.format(BandDates.DATE)
                },
                style = MaterialTheme.typography.bodyLarge,
                fontSize = cols.row,
                lineHeight = cols.row,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                d.date.dayOfWeek.getDisplayName(
                    TextStyle.SHORT,
                    if (lang == BandLanguage.JA) Locale.JAPAN else Locale.ENGLISH,
                ),
                style = MaterialTheme.typography.labelMedium,
                fontSize = cols.sub,
                lineHeight = cols.sub,
                color = LocalChartStyle.current.axisText,
                maxLines = 1,
                softWrap = false,
            )
        }
        // The index gets a colour swatch, because a column of bare numbers is exactly the thing a
        // person scans without seeing. Everything else stays plain ink.
        Row(Modifier.width(cols.num.dp), verticalAlignment = Alignment.CenterVertically) {
            val v = d.index?.value
            if (v != null) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape).background(ChartPalette.sequential(v / 100f)),
                )
                Spacer(Modifier.width(cols.swatchGap.dp))
            }
            Text(
                v?.toString() ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                fontSize = cols.row,
                lineHeight = cols.row,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
        }
        Cell(d.restingHr?.let { "%.0f".format(it) } ?: "—", cols.num, cols)
        Cell(
            d.sleepMinutes?.let { "${it / 60}:${"%02d".format(it % 60)}" } ?: "—",
            cols.sleep,
            cols,
            sub = d.deepMinutes?.let { deep ->
                d.remMinutes?.let { rem -> BandText.deepRemShort[lang].format(deep, rem) }
            },
        )
        Cell(if (d.steps > 0) "%,d".format(d.steps) else "—", cols.step, cols)
        Cell(d.spo2Low?.let { "%.0f".format(it) } ?: "—", cols.num, cols)
    }
}

@Composable
private fun Cell(
    text: String,
    width: Int,
    cols: DayColumns,
    header: Boolean = false,
    sub: String? = null,
) {
    Column(Modifier.width(width.dp)) {
        Text(
            text,
            style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
            fontSize = if (header) cols.head else cols.row,
            lineHeight = if (header) cols.head else cols.row,
            color = if (header) LocalChartStyle.current.axisText else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (header) FontWeight.Normal else FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
        sub?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                fontSize = cols.sub,
                lineHeight = cols.sub,
                color = LocalChartStyle.current.axisText,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Six columns of fixed width, in the two sizes the phone actually has.
 *
 * Fixed rather than weighted because these are columns of digits: weights make a column as wide as
 * its share of the line, and what a number column wants is to be as wide as its widest number and no
 * wider, so the decimal points line up down the page.
 *
 * The wide set is 白い熊's from 2026-08-08 — the daily records had been set in the smallest type on
 * the page while more than half the row sat empty, and `num` was too narrow for the "Rest HR"
 * heading, which is why it ran into "Sleep".
 *
 * The narrow set exists because those widths add up to 748 dp and the **folded** Mate XT cover panel
 * is 413 dp: three of the six columns were off the right-hand edge of the screen entirely, with no
 * way to reach them. (白い熊, 2026-08-18.) It is the same six columns at 15 sp with the year off the
 * date, which fits in 366 dp — not fewer columns, because a column that is not on the screen and a
 * column that was never rendered are the same table to read and only one of them is honest about it.
 */
private class DayColumns(
    val narrow: Boolean,
    val date: Int,
    val num: Int,
    val sleep: Int,
    val step: Int,
    val cardPad: Int,
    val swatchGap: Int,
    val row: TextUnit,
    val head: TextUnit,
    val sub: TextUnit,
)

@Composable
private fun dayColumns(): DayColumns = if (isNarrowScreen()) {
    DayColumns(
        narrow = true,
        date = 54, num = 50, sleep = 98, step = 64,
        cardPad = 8, swatchGap = 3,
        row = 15.sp, head = 11.sp, sub = 11.sp,
    )
} else {
    DayColumns(
        narrow = false,
        date = 148, num = 92, sleep = 208, step = 116,
        cardPad = 14, swatchGap = 5,
        row = 19.sp, head = 14.sp, sub = 14.sp,
    )
}
