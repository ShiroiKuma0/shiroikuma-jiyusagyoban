package com.opentasker.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The page behind one row of 「平常との差」 — every night on record for that one quantity.
 *
 * ## What it answers that the row cannot
 *
 * A row says "27 ms, usual 26". This says whether 26 has been drifting for a fortnight, whether last
 * night was ordinary or the best in a month, and what the range even looks like. 白い熊 asked for
 * exactly that (2026-09-13) after the strip went to the top of the report: the strip is for
 * orienting, and a number you cannot open is a number you have to take on faith.
 *
 * ## Its shape, and what it refuses to do
 *
 * A chart of every night, the usual band shaded behind it and the baseline dashed through it; then
 * the best nights on record; then the nights themselves, newest first, each with its difference.
 *
 * **No trend line, no projection, and no verdict.** Twenty-one nights of a derived quantity with no
 * published normal range does not support a slope, and drawing one would make an eye-catching claim
 * out of an arithmetic accident. The banding the strip already applies is the only judgement on the
 * page, and it is shown rather than restated.
 */
@Composable
fun MarkerHistoryScreen(
    series: MarkerHistory.Series,
    zone: java.time.ZoneId,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    /** Drawn under the chart, when the caller has something extra to say — see the night curve. */
    extra: (@Composable () -> Unit)? = null,
) {
    val lang = LocalBandLanguage.current
    val marker = series.marker
    val nights = series.nights
    val accent = markerColour(marker)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("head") {
            SectionCard(accent = accent, onClick = onBack) {
                SectionTitle(markerLabel(marker)[lang], accent)
                val latest = series.latest
                Text(
                    latest?.value?.let { markerValue(marker, it, lang) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp),
                    fontWeight = FontWeight.Bold,
                    color = sectionInk,
                )
                latest?.baseline?.let {
                    NoteText(BandText.deviationBaseline[lang].format(markerValue(marker, it, lang)))
                }
                NoteText(BandText.markerNights[lang].format(nights.size))
            }
        }

        if (nights.size >= 2) {
            item("chart") {
                SectionCard(accent = accent) {
                    SubHeading(BandText.markerEveryNight[lang])
                    val values = nights.map { it.value }
                    val lo = values.min()
                    val hi = values.max()
                    // A tenth of the range as breathing room at each end, then rounded outward to a
                    // whole step, so the top and bottom gridlines are round numbers and the extreme
                    // nights are not drawn touching the frame.
                    val pad = ((hi - lo) * 0.1).coerceAtLeast(1.0)
                    val step = niceStep(hi - lo + 2 * pad)
                    BoundedLineChart(
                        values = values,
                        yLo = floor((lo - pad) / step) * step,
                        yHi = ceil((hi + pad) / step) * step,
                        yStep = step,
                        color = accent,
                        xLabels = Triple(
                            nightLabel(nights.first().endMs, zone),
                            "",
                            nightLabel(nights.last().endMs, zone),
                        ),
                        formatY = { markerValue(marker, it, lang, unit = false) },
                        usualBand = series.latest?.let { r ->
                            val a = r.usualLo
                            val b = r.usualHi
                            if (a != null && b != null && b > a) a..b else null
                        },
                        marked = setOf(values.lastIndex),
                        baseline = series.latest?.baseline,
                        height = 180.dp,
                    )
                    NoteText(BandText.markerChartNote[lang])
                }
            }
        }

        extra?.let { item("extra") { it() } }

        if (nights.size >= 3) {
            item("best") {
                SectionCard(accent = accent) {
                    SubHeading(BandText.markerBest[lang])
                    for (n in series.best(3)) {
                        NightRow(marker, n, series.latest?.baseline, zone, bold = false)
                    }
                }
            }
        }

        // ONE card holding every night, not one card per night. A bordered box around each row
        // turns a list into a stack of boxes: the border stops meaning "a group" and the rows stop
        // reading as a column you can run an eye down, which is the only thing a list of dates is
        // for.
        item("all") {
            SectionCard(accent = accent) {
                SubHeading(BandText.markerAllNights[lang])
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in nights.reversed()) {
                        NightRow(
                            marker, n, series.latest?.baseline, zone,
                            bold = n.endMs == nights.last().endMs,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NightRow(
    marker: RecoveryMarker,
    night: MarkerHistory.Night,
    baseline: Double?,
    zone: java.time.ZoneId,
    bold: Boolean,
) {
    val lang = LocalBandLanguage.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            nightLabel(night.endMs, zone),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = sectionInk,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                markerValue(marker, night.value, lang),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                fontWeight = FontWeight.Bold,
                color = sectionInk,
            )
            baseline?.let {
                val d = night.value - it
                // Suppressed when it rounds to nothing: "+0" on half the rows is noise that makes
                // the rows that did move harder to find.
                if (abs(d) >= 0.5) {
                    Text(
                        "  " + markerDelta(marker, d, lang),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = sectionNote,
                    )
                }
            }
        }
    }
}

/** `yyyy-MM-dd` of the morning a night is filed under. */
private fun nightLabel(endMs: Long, zone: java.time.ZoneId): String =
    java.time.Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate().toString()

/**
 * A round gridline step for a given range — 1, 2, 5 or a power of ten of those.
 *
 * Chosen so every marker's axis carries whole numbers a reader recognises, whatever its unit: a
 * heart rate spanning 20 bpm gets fives, a night's minutes spanning 400 gets hundreds.
 */
private fun niceStep(range: Double): Double {
    if (range <= 0) return 1.0
    val target = range / 4
    val mag = Math.pow(10.0, floor(Math.log10(target)))
    return listOf(1.0, 2.0, 5.0, 10.0).first { it * mag >= target * 0.999 } * mag
}

/**
 * Each marker's own colour — the one its quantity already wears elsewhere on this screen.
 *
 * Never picked per screen: a heart rate is the heart rate's colour on the report, in the night table
 * and here, so the eye carries the association across. Everything without an established colour
 * takes the card's own blue rather than borrowing one that means something else.
 */
private fun markerColour(marker: RecoveryMarker): androidx.compose.ui.graphics.Color = when (marker) {
    RecoveryMarker.NOCTURNAL_HR, RecoveryMarker.BEDTIME_HR, RecoveryMarker.HR_SWING ->
        ChartPalette.HEART_RATE
    RecoveryMarker.FELT -> ChartPalette.BAND_WARN
    else -> ChartPalette.STEPS
}
