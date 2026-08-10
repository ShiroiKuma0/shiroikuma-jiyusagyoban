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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 */
@Composable
fun DailySummaryCard(days: List<DaySummary>) {
    val lang = LocalBandLanguage.current
    if (days.isEmpty()) return

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                BandText.byDay[lang],
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            HeaderRow(lang)
            days.forEach { DayRow(it, lang) }
            Text(
                BandText.byDayNote[lang],
                style = MaterialTheme.typography.bodySmall,
                color = LocalChartStyle.current.axisText,
            )
        }
    }
}

@Composable
private fun HeaderRow(lang: BandLanguage) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Cell(BandText.colDay[lang], DATE_W, header = true)
        Cell(BandText.colIndex[lang], NUM_W, header = true)
        Cell(BandText.colResting[lang], NUM_W, header = true)
        Cell(BandText.colSleep[lang], SLEEP_W, header = true)
        Cell(BandText.colSteps[lang], STEP_W, header = true)
        Cell(BandText.colSpo2[lang], NUM_W, header = true)
    }
}

@Composable
private fun DayRow(d: DaySummary, lang: BandLanguage) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(DATE_W.dp)) {
            Text(
                d.date.format(BandDates.DATE),
                style = MaterialTheme.typography.bodyLarge,
                fontSize = ROW_SP,
                lineHeight = ROW_SP,
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
                fontSize = SUB_SP,
                lineHeight = SUB_SP,
                color = LocalChartStyle.current.axisText,
                maxLines = 1,
                softWrap = false,
            )
        }
        // The index gets a colour swatch, because a column of bare numbers is exactly the thing a
        // person scans without seeing. Everything else stays plain ink.
        Row(Modifier.width(NUM_W.dp), verticalAlignment = Alignment.CenterVertically) {
            val v = d.index?.value
            if (v != null) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape).background(ChartPalette.sequential(v / 100f)),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                v?.toString() ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                fontSize = ROW_SP,
                lineHeight = ROW_SP,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
        }
        Cell(d.restingHr?.let { "%.0f".format(it) } ?: "—", NUM_W)
        Cell(
            d.sleepMinutes?.let { "${it / 60}:${"%02d".format(it % 60)}" } ?: "—",
            SLEEP_W,
            sub = d.deepMinutes?.let { deep ->
                d.remMinutes?.let { rem -> BandText.deepRemShort[lang].format(deep, rem) }
            },
        )
        Cell(if (d.steps > 0) "%,d".format(d.steps) else "—", STEP_W)
        Cell(d.spo2Low?.let { "%.0f".format(it) } ?: "—", NUM_W)
    }
}

@Composable
private fun Cell(text: String, width: Int, header: Boolean = false, sub: String? = null) {
    Column(Modifier.width(width.dp)) {
        Text(
            text,
            style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
            fontSize = if (header) HEAD_SP else ROW_SP,
            lineHeight = if (header) HEAD_SP else ROW_SP,
            color = if (header) LocalChartStyle.current.axisText else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (header) FontWeight.Normal else FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
        sub?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                fontSize = SUB_SP,
                lineHeight = SUB_SP,
                color = LocalChartStyle.current.axisText,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// 白い熊, 2026-08-08: the daily records were set in the smallest type on the page while more than half
// the row sat empty, and NUM_W was too narrow for the "Rest HR" header — which is why it ran into
// "Sleep". Bigger type, and every column wide enough for its own heading.
private val ROW_SP = 19.sp
private val HEAD_SP = 14.sp
private val SUB_SP = 14.sp
private const val DATE_W = 148
private const val NUM_W = 92
private const val SLEEP_W = 208
private const val STEP_W = 116
