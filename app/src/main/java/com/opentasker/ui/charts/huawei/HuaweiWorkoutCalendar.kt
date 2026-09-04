package com.opentasker.ui.charts.huawei

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import com.opentasker.ui.charts.ANNOTATION_INK
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentasker.core.band.RehabLog
import com.opentasker.core.huawei.HuaweiWorkoutStore
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.DayGrid
import com.opentasker.ui.charts.DayGridStyle
import com.opentasker.ui.charts.DetailHeader
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which days a kind of workout was recorded on — one calendar, three windows.
 *
 * ## Why every kind gets one
 *
 * 機能訓練 had a calendar first, because what matters about rehab is the RUN: whether it was done
 * yesterday and the day before, which a counter cannot show. That is not special to rehab. A gap in
 * walking is the same finding, and 白い熊 asked for the same page on all three (2026-09-04).
 *
 * ## The tile is the way in, not just a mark
 *
 * Tapping a day that has a session opens THAT session. The calendar is therefore an index as well
 * as a record — the fastest route to "what did I do on the 29th" — which is what a grid of dates is
 * actually good for and what the old rehab calendar, whose taps only ever opened a tick box, was
 * not being used for.
 *
 * ## Rehab keeps its tick
 *
 * A day the band recorded is filled automatically. A day 白い熊 did rehab WITHOUT the band still has
 * to be markable by hand, and that mark is the one thing here nothing can re-supply — so on
 * 機能訓練 a day with no session opens the tick and its note, exactly as it always did. The other
 * two kinds have nothing to author: a walk either happened or it did not, and the band knows.
 */
@Composable
fun HuaweiWorkoutCalendarScreen(
    kind: HuaweiWorkoutStore.Kind,
    /** Every workout of this kind, newest first — the same list the grid behind this shows. */
    workouts: List<HuaweiWorkoutStore.Workout>,
    /** Days ticked by hand. 機能訓練 only; empty for the kinds with nothing to author. */
    ticked: Set<Long>,
    notes: Map<Long, String>,
    zone: ZoneId,
    contentPadding: PaddingValues,
    onOpenSession: (HuaweiWorkoutStore.Workout) -> Unit,
    /** A day with no session, on a kind that allows one to be marked anyway. */
    onTapEmptyDay: (Long) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    val accent = HuaweiText.accentFor(kind)
    val today = remember(zone) { LocalDate.now(zone) }

    // EVERY session on a day, not one of them.
    //
    // This was `associateBy`, which keeps the LAST of a duplicate key — so a morning walk and an
    // evening walk on the same date left only the evening reachable, and the comment above it
    // claimed it kept the first. Two walks in a day is 白い熊's ordinary Saturday (2026-09-04), so
    // the tile has to offer both rather than quietly pick.
    val byDay = remember(workouts, zone) {
        workouts.groupBy {
            RehabLog.dateKeyOf(java.time.Instant.ofEpochSecond(it.startSeconds).atZone(zone).toLocalDate())
        }
    }
    // The day whose sessions are being chosen between. Null unless a tile held more than one.
    var choosing by remember { mutableStateOf<List<HuaweiWorkoutStore.Workout>>(emptyList()) }
    val first = remember(workouts, today) {
        workouts.minOfOrNull { it.startSeconds }
            ?.let { java.time.Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
            ?: today.minusWeeks(4)
    }
    // Whole weeks from the Monday on or before the first record, so the grid never starts ragged.
    val from = remember(first) { first.with(java.time.DayOfWeek.MONDAY).toEpochDay() }
    val to = remember(today) { today.toEpochDay() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DetailHeader(HuaweiText.titleFor(kind)[lang], hasInfo = false, onBack = onBack, onInfo = {})
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(accent = accent) {
                SectionTitle(HuaweiText.rehabCalendar[lang], accent)
                BodyText(HuaweiText.calendarAbout(kind)[lang])
                DayGrid(
                    // The SAME cells 機能訓練 has drawn since 白い熊 chose them out of ten rendered
                    // options — yellow for a day that happened, `REST_FILL` for one that did not,
                    // and the date always in the app's own ink. There is no per-kind colouring
                    // here: a calendar is a calendar (白い熊, 2026-09-04), and a walks calendar in
                    // blue and a lifting one in red read as three designs rather than one.
                    //
                    // A hand-ticked day is unioned in rather than distinguished. The question is
                    // "did I do it", and the answer does not change because the band was charging.
                    days = rehabCells(from, to, byDay.keys + ticked, notes).map { cell ->
                        // How many sessions that day holds, so a doubled day is visible before it
                        // is tapped rather than only in the chooser that follows.
                        cell.copy(
                            count = byDay[RehabLog.dateKeyOf(LocalDate.ofEpochDay(cell.epochDay))]
                                ?.size,
                        )
                    },
                    zone = zone,
                    onTap = { day ->
                        val key = RehabLog.dateKeyOf(LocalDate.ofEpochDay(day))
                        val onThatDay = byDay[key].orEmpty()
                        when (onThatDay.size) {
                            0 -> onTapEmptyDay(day)
                            // One session is the common case and must stay one tap. A picker that
                            // appeared for a single item would be a dialog asking a question with
                            // one answer.
                            1 -> onOpenSession(onThatDay.first())
                            else -> choosing = onThatDay.sortedBy { it.startSeconds }
                        }
                    },
                    gridStyle = DayGridStyle.DAYS,
                )
                NoteText(HuaweiText.calendarTapNote[lang])
            }
        }
    }

    if (choosing.isNotEmpty()) {
        WorkoutPickerDialog(
            sessions = choosing,
            onPick = {
                choosing = emptyList()
                onOpenSession(it)
            },
            onDismiss = { choosing = emptyList() },
        )
    }
}

/**
 * Which of the day's sessions did you mean?
 *
 * Shown only when a tile holds more than one — two walks, a morning and an evening. Each row is the
 * clock time and what the band measured, because that is what tells them apart: the same distance
 * at 08:07 and at 19:08 are two different walks and 白い熊 knows which is which by when it was.
 */
@Composable
private fun WorkoutPickerDialog(
    sessions: List<HuaweiWorkoutStore.Workout>,
    onPick: (HuaweiWorkoutStore.Workout) -> Unit,
    onDismiss: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    val clock = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US) }
    // The weekday stays Japanese whatever the window's language pill says — 白い熊 asked for that
    // shape everywhere a walk is dated, and a date that changes its own alphabet is a different
    // date to scan for.
    val date = remember { java.text.SimpleDateFormat("yyyy-MM-dd (E)", java.util.Locale.JAPANESE) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
                // The same yellow frame every other card and pill in this window wears. Without it
                // the dialog was a grey block floating over a yellow calendar, belonging to neither
                // (白い熊, 2026-09-04).
                .border(
                    1.5.dp,
                    ANNOTATION_INK,
                    androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(date.format(java.util.Date(sessions.first().startSeconds * 1000)), ANNOTATION_INK)
            for (session in sessions) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .border(
                            1.5.dp,
                            ANNOTATION_INK,
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        )
                        .clickable { onPick(session) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    androidx.compose.material3.Text(
                        clock.format(java.util.Date(session.startSeconds * 1000)),
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = ANNOTATION_INK,
                    )
                    NoteText(
                        if (session.hasTrack) walkStats(session, lang)
                        else listOfNotNull(
                            session.durationSeconds?.let { hhmm(it) },
                            session.calories?.let { "$it kcal" },
                        ).joinToString(" · "),
                    )
                }
            }
        }
    }
}
