package com.opentasker.ui.charts.huawei

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.opentasker.core.band.RehabLog
import com.opentasker.ui.charts.ANNOTATION_INK
import com.opentasker.ui.charts.BandText
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.DayGrid
import com.opentasker.ui.charts.DayGridCell
import com.opentasker.ui.charts.DayGridStyle
import com.opentasker.ui.charts.DetailHeader
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.NotePill
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import com.opentasker.ui.charts.nightDateFull
import com.opentasker.ui.charts.sectionInk
import com.opentasker.ui.charts.sectionNote
import java.time.LocalDate
import java.time.ZoneId

/**
 * 機能訓練 — the rehab exercises, ticked off a day at a time.
 *
 * ## Why a calendar and not a counter
 *
 * What matters about rehab is the RUN: whether it was done yesterday and the day before, not how
 * many times this month. A number cannot show a gap, and a gap is the only thing worth acting on —
 * so the record is a calendar from the start, and the card at the top of the report is a two-week
 * cut-out of exactly the same calendar the full page draws (白い熊, 2026-09-03). One record, two
 * windows onto it; a card built from its own smaller query would be a second source able to disagree.
 *
 * ## Yellow means done, and nothing else does
 *
 * A day is a tick, not a score, so the calendar has two states and needs two appearances. Done is
 * FULL yellow — the same [ANNOTATION_INK] the whole window uses for what 白い熊 authored, which is
 * exactly what a tick is. A day with no record is the same cell in [REST_FILL], so the month is one
 * block of days rather than a scatter of yellow on nothing. There is deliberately no red: a missed
 * day is not an error, and colouring it as one would make the calendar an accusation.
 */
/**
 * A day with no record: a filled cell, not an empty one.
 *
 * Dark enough to stay clearly behind the yellow and light enough to be a cell rather than a hole —
 * the calendar has to read as a block of days of which some are done, never as a scattering of
 * yellow on nothing.
 */
/** The unfilled ground of a calendar tile, shared by every kind's calendar. */
internal val REST_FILL = Color(0xFF2A2A2A)

/** The two full weeks ending on today's — always exactly two rows, whatever weekday it is. */
fun rehabCutoutStart(today: LocalDate): LocalDate =
    today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks(1)

/**
 * One day of the rehab calendar, ready to draw.
 *
 * Built in one place and used by both the card and the page, so the cut-out cannot show a day
 * differently from the calendar it is a cut-out of.
 */
fun rehabCells(
    fromEpochDay: Long,
    toEpochDay: Long,
    done: Set<Long>,
    notes: Map<Long, String>,
): List<DayGridCell> = (fromEpochDay..toEpochDay).map { day ->
    val key = RehabLog.dateKeyOf(LocalDate.ofEpochDay(day))
    val isDone = key in done
    // EVERY day is a filled cell; the done ones are bright — 白い熊's choice from ten rendered
    // options (2026-09-03, option 9). Outlining all 42 days made the fills compete with 42 borders
    // for attention; filling them all makes the calendar one block, and a run of done days one
    // unbroken band of yellow, which is the thing this record exists to show.
    DayGridCell(
        epochDay = day,
        fill = if (isDone) ANNOTATION_INK else REST_FILL,
        ink = if (isDone) Color.Black else ANNOTATION_INK.copy(alpha = 0.8f),
        bold = isDone,
        hasNote = notes[key]?.isNotBlank() == true,
    )
}

/**
 * The card under 今朝の体感: two weeks of the same calendar, and a way in.
 *
 * Tapping any square opens the day's editor, so the commonest act — ticking today — costs one tap
 * from the top of the report and never needs the full page at all.
 */
@Composable
fun HuaweiRehabCard(
    days: List<DayGridCell>,
    zone: ZoneId,
    doneCount: Int,
    totalDays: Int,
    onTapDay: (Long) -> Unit,
    onOpen: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    SectionCard(accent = ANNOTATION_INK) {
        SectionTitle(HuaweiText.rehabTitle[lang], ANNOTATION_INK)
        BodyText(HuaweiText.rehabSummary[lang].format(doneCount, totalDays))
        // Two rows, so the whole cut-out is on screen at once and nothing scrolls inside a card that
        // is itself inside a scrolling report.
        DayGrid(
            days = days,
            zone = zone,
            onTap = onTapDay,
            visibleWeeks = 2,
            gridStyle = DayGridStyle.DAYS,
        )
        NoteText(HuaweiText.rehabHint[lang])
        // The way to the full calendar, in the blue that means "this opens something" everywhere
        // else on this screen — the same nested affordance the morning card uses for the register.
        val link = ChartPalette.STEPS
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(link.copy(alpha = 0.10f))
                .border(2.dp, link, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .clickable(onClick = onOpen),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                HuaweiText.rehabOpen[lang],
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = link,
            )
            Text("›", style = MaterialTheme.typography.titleLarge, color = link)
        }
    }
}

/**
 * The full page: every day on record, in the calendar the register uses.
 *
 * Same grid, same month rules, same weekend rule, same today border — one component, two skins. What
 * differs is only what a square MEANS, which is the one thing a calendar cannot share.
 */
@Composable
fun HuaweiRehabScreen(
    days: List<DayGridCell>,
    zone: ZoneId,
    doneCount: Int,
    totalDays: Int,
    contentPadding: PaddingValues,
    onTapDay: (Long) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
    ) {
        DetailHeader(HuaweiText.rehabTitle[lang], hasInfo = false, onBack = onBack, onInfo = {})
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(accent = ANNOTATION_INK) {
                SectionTitle(HuaweiText.rehabTitle[lang], ANNOTATION_INK)
                BodyText(HuaweiText.rehabSummary[lang].format(doneCount, totalDays))
                DayGrid(days = days, zone = zone, onTap = onTapDay, gridStyle = DayGridStyle.DAYS)
                NoteText(HuaweiText.rehabHint[lang])
                NoteText(HuaweiText.rehabLegend[lang])
            }
        }
    }
}

/**
 * Tick or un-tick one named day, and reach its note.
 *
 * The register's rating dialog, one answer narrower: the date in full above the thing that writes it,
 * two full-width rows, and any tap files and closes. A tick filed against the wrong day is the same
 * kind of wrong as a rating on the wrong night — it looks authored — so the day is named rather than
 * left to whatever square was under the finger.
 */
@Composable
fun HuaweiRehabDayDialog(
    dateKey: Long,
    done: Boolean,
    note: String?,
    onPick: (Boolean) -> Unit,
    onEditNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.5.dp, sectionInk, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                nightDateFull(dateKey, lang),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = sectionInk,
            )
            for (isDone in listOf(true, false)) {
                val chosen = done == isDone
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isDone) ANNOTATION_INK else Color.Transparent)
                        .then(
                            if (isDone) Modifier
                            else Modifier.border(1.5.dp, sectionNote, RoundedCornerShape(10.dp)),
                        )
                        .clickable { onPick(isDone) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (isDone) HuaweiText.rehabDone else HuaweiText.rehabNotDone)[lang],
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isDone) Color.Black else sectionNote,
                    )
                    // The state on file is marked by a GLYPH, exactly as the rating dialog marks
                    // its own — the two colours here already mean "done" and "not done", and a
                    // third appearance for "this one is selected" would be a colour to learn.
                    if (chosen) {
                        Text(
                            "✓",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isDone) Color.Black else sectionNote,
                        )
                    }
                }
            }
            NotePill(note = note, onClick = onEditNote)
            Text(
                BandText.rateClose[lang],
                Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = sectionInk,
            )
        }
    }
}
