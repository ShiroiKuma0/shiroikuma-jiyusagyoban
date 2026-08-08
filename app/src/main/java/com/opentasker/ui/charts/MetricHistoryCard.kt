package com.opentasker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The day-by-day history printed under a full-screen chart.
 *
 * The chart above shows the shape; this is where the numbers are legible. Before it existed the sleep
 * screen showed one undated breakdown — the most recent night — and every other metric showed none at
 * all, so "how did I sleep on Tuesday" could only be answered by zooming the hypnogram and reading an
 * axis (白い熊, 2026-08-07).
 *
 * Dates are `2026-08-07` throughout, per [BandDates]. Text follows [InfoType] sizing so a page of
 * numbers reads at the same weight as the prose in the `i` sheet next to it.
 */

/** One row per calendar day for a continuous metric. */
@Composable
fun MetricHistoryCard(days: List<MetricDay>, spec: MetricSpec) {
    val lang = LocalBandLanguage.current
    val ink = LocalChartStyle.current.axisText
    // Steps are counts, so a day of them is a TOTAL. Everything else is a rate or a level, where a
    // sum would be a number with no meaning — 24 hours of heart rate added together is nothing.
    val isCount = spec.render == RenderKind.BARS

    HistoryFrame(BandText.history[lang], BandText.historyNote[lang], days.isEmpty()) {
        Row {
            HeadCell(BandText.colDate[lang], DATE_W)
            HeadCell(if (isCount) BandText.colTotal[lang] else BandText.colMedian[lang], VALUE_W)
            HeadCell(BandText.colRange[lang], RANGE_W)
            HeadCell(BandText.colSamples[lang], N_W)
        }
        HorizontalDivider(color = ink.copy(alpha = 0.4f))
        days.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cell(d.date.format(BandDates.DATE), DATE_W, bold = true)
                Cell(
                    if (isCount) "%,.0f".format(d.total) else spec.format(d.median),
                    VALUE_W,
                    bold = true,
                )
                Cell("${spec.format(d.lo)}–${spec.format(d.hi)}", RANGE_W)
                Cell(d.samples.toString(), N_W, dim = true)
            }
        }
    }
}

/**
 * One row per night, with the extent as well as the duration.
 *
 * `22:41 → 08:33` answers a question the duration cannot: two seven-hour nights that started four
 * hours apart are not the same night, and only one of them explains a bad morning.
 */
@Composable
fun SleepHistoryCard(nights: List<SleepNight>) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current

    HistoryFrame(BandText.history[lang], BandText.nightsNote[lang], nights.isEmpty()) {
        nights.forEach { n ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        n.date.format(BandDates.DATE),
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = ROW_SP,
                        lineHeight = ROW_SP,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        BandDates.span(n.startMs, n.endMs),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = ROW_SP,
                        lineHeight = ROW_SP,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${n.totalMinutes / 60}h ${"%02d".format(n.totalMinutes % 60)}m",
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = ROW_SP,
                        lineHeight = ROW_SP,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // The four stages on one line, each beside its own colour — identity never rests on
                // the colour alone, so the minutes carry the label's place.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SleepShape.ROWS.forEach { code ->
                        val minutes = when (code) {
                            '1' -> n.deep
                            '2' -> n.light
                            '3' -> n.rem
                            else -> n.awake
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(7.dp).clip(CircleShape)
                                    .background(style.sleepStage(code)),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${SleepShape.labelOf(code)[lang]} ${minutes}m · ${n.pctOf(minutes)}%",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = SUB_SP,
                                lineHeight = SUB_SP,
                                color = style.axisText,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryFrame(
    title: String,
    note: String,
    empty: Boolean,
    rows: @Composable () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoHeading(title)
            if (empty) {
                Text(
                    BandText.noHistory[LocalBandLanguage.current],
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = ROW_SP,
                    color = LocalChartStyle.current.axisText,
                )
            } else {
                rows()
            }
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                fontSize = SUB_SP,
                lineHeight = SUB_SP,
                color = LocalChartStyle.current.axisText,
            )
        }
    }
}

@Composable
private fun HeadCell(text: String, width: Int) {
    Text(
        text,
        Modifier.width(width.dp),
        style = MaterialTheme.typography.labelMedium,
        fontSize = SUB_SP,
        lineHeight = SUB_SP,
        color = LocalChartStyle.current.axisText,
    )
}

@Composable
private fun Cell(text: String, width: Int, bold: Boolean = false, dim: Boolean = false) {
    Text(
        text,
        Modifier.width(width.dp),
        style = MaterialTheme.typography.bodyLarge,
        fontSize = ROW_SP,
        lineHeight = ROW_SP,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = if (dim) LocalChartStyle.current.axisText else MaterialTheme.colorScheme.primary,
    )
}

private val ROW_SP = 15.sp
private val SUB_SP = 12.sp
private const val DATE_W = 104
private const val VALUE_W = 74
private const val RANGE_W = 104
private const val N_W = 48
