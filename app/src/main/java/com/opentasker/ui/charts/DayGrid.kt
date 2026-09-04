package com.opentasker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One square of a day calendar, already reduced to what the grid draws.
 *
 * Deliberately dumb: colours rather than ratings, a string rather than a value. Every screen that
 * shows a calendar of days decides for itself what a day MEANS and hands over the appearance, so the
 * grid has no opinion about nights, ratings or rehab and cannot acquire one. That is what lets the
 * 運動と回復 register and 機能訓練 share a calendar instead of owning two that drift.
 */
data class DayGridCell(
    val epochDay: Long,
    /** Null leaves the tile the grid's own empty fill — "nothing was recorded for this day". */
    val fill: Color? = null,
    /** The one foreground [fill] is chosen to carry. Null takes the note ink. */
    val ink: Color? = null,
    /** What the tile prints, if anything. */
    val label: String = "",
    /** A frame around the tile, drawn INSIDE it. Null leaves the fill unbordered. */
    val border: Color? = null,
    /** Whether [label] is bold. The rating grids want it; a plain date does not. */
    val bold: Boolean = true,
    val hasNote: Boolean = false,
    /**
     * How many sessions the day holds, when that is more than one.
     *
     * Null on every ordinary day, so the mark appears only where it means something. A calendar
     * tile is a seventh of a phone's width and already carries a date; a badge on all of them
     * would be a badge that says nothing on most of them.
     */
    val count: Int? = null,
    /** The bar under the tile, as a fraction of the cell's width. Null draws none. */
    val bar: Float? = null,
)

/**
 * The two calendars this grid draws, and what actually differs between them.
 *
 * Not a flag per difference: the differences are not independent. A calendar whose tile carries a
 * RATING needs its date above the tile, and one whose tile IS the date can afford tighter rows and a
 * real gutter at the weekend because nothing else is competing for the space. Naming the two lets a
 * change to one be a change to one.
 */
enum class DayGridStyle {
    /** 運動と回復: a date label, then a tile carrying that night's 1–5. */
    RATINGS,

    /**
     * 機能訓練: one filled cell per day with its own date in it, rows tight, weekend pushed apart.
     *
     * The metrics are 白い熊's choice from ten rendered options (2026-09-03, option 9): every day is
     * a filled cell so the calendar reads as a block rather than a scatter, and the weekend gets
     * eight dp of air either side of its rule — a hairline alone was a line among lines, and it is
     * the GAP that separates while the rule only marks where the gap falls.
     */
    DAYS,
}

/** How many week rows the calendar shows at once; the rest of the window scrolls into view. */
private const val VISIBLE_WEEKS = 5

/**
 * The month a grid row opens, or null when it opens none.
 *
 * A month boundary almost never falls on a Monday, so the rule cannot be drawn between two rows and
 * be honest about where the month starts. It is drawn above the row that CONTAINS the 1st instead:
 * that row is the first one with any of the new month in it, which is what "the beginning of the
 * month" means to someone scrolling. [isFirst] labels the top row whatever its dates, because the
 * top of the window has no preceding rule to inherit a month from.
 */
private fun gridMonthOpenedBy(week: List<DayGridCell?>, isFirst: Boolean): java.time.YearMonth? {
    val days = week.filterNotNull()
    if (days.isEmpty()) return null
    days.firstOrNull { LocalDate.ofEpochDay(it.epochDay).dayOfMonth == 1 }
        ?.let { return BandMonths.ofEpochDay(it.epochDay) }
    return if (isFirst) BandMonths.ofEpochDay(days.first().epochDay) else null
}

/**
 * Five weeks, Monday first, each day a tile carrying that night's rating.
 *
 * The tile is filled with [feltColor] and prints the 1–5, so a run of good nights and a run of bad
 * ones are different shapes on the page — which is the only thing a five-week grid is actually good
 * at, and the reason it exists at all. It replaced three anonymous dots that said the same thing for
 * a night rated 3 and a night never rated: a count of markers is not a reading, and 白い熊 could not
 * tell one from the other (2026-08-11). The session load survives as the bar beneath. The marker count
 * does not survive here at all: it rang the tile in the tile's own ink, which meant a yellow border on
 * every 3 and a white one on every 5, and once today had a border of its own that was three borders
 * saying three things in one grid (白い熊, 2026-08-16). It is on every line of the table below instead,
 * beside the values it counts — which is where a count belongs when the tile is already a reading.
 *
 * **Every tile is a button.** A tile is the smallest thing on the screen that names one night, so it
 * is the natural place to reach for when a night is wrong — and unlike the table below it, the grid
 * has a square for a day the band recorded nothing at all, which is precisely the day a rating is
 * most likely to be missing from. Ratings on such days reach [SessionRegister.DayCell.felt] straight
 * from the store, so a tap fills a blank tile in rather than appearing to do nothing.
 */
@Composable
fun DayGrid(
    days: List<DayGridCell>,
    zone: ZoneId,
    /** The tapped tile's epoch day. */
    onTap: (Long) -> Unit,
    /**
     * How many week rows are on screen at once; the rest scrolls.
     *
     * Five for a full calendar. The 機能訓練 card passes two, because a cut-out inside a card that is
     * itself inside a scrolling report must not have a scroller of its own — two nested scrollers in
     * the same direction fight over every drag.
     */
    visibleWeeks: Int = VISIBLE_WEEKS,
    /**
     * Which of the two calendars this is. See [DayGridStyle].
     */
    gridStyle: DayGridStyle = DayGridStyle.RATINGS,
) {
    val dateInTile = gridStyle == DayGridStyle.DAYS
    // The weekend gutter, the column gap and the row padding are all tighter for a day calendar —
    // see [DayGridStyle.DAYS] for why each one moves.
    val weekendGutter = if (dateInTile) 8.dp else 0.dp
    val columnGap = if (dateInTile) 3.dp else 4.dp
    val rowPadding = if (dateInTile) 1.5.dp else 3.dp
    val style = LocalChartStyle.current
    // Today, marked wherever it lands. Five weeks of squares are five weeks of squares: without a
    // fixed point 白い熊 has to count columns to find the morning being asked about, and the tile most
    // likely to be tapped is exactly the one hardest to locate. (白い熊, 2026-08-16.)
    val todayEpochDay = remember(zone) { LocalDate.now(zone).toEpochDay() }
        val labels = remember(zone) { DateTimeFormatter.ofPattern("d").withZone(zone) }

    // Pad the head so the first column really is Monday.
    val first = days.firstOrNull() ?: return
    val firstDow = Instant.ofEpochMilli(first.epochDay * 86_400_000L).atZone(zone).dayOfWeek.value // 1=Mon
    val cells: List<DayGridCell?> = List(firstDow - 1) { null } + days

    val lang = LocalBandLanguage.current
    val heads = if (lang == BandLanguage.EN) {
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    } else {
        listOf("月", "火", "水", "木", "金", "土", "日")
    }

    // The rows are a fixed window that SCROLLS, not a list that grows: the register reaches a month
    // and a half back (see [RecoveryBuild.gridStart]) while the card stays the height it is today, so
    // the screen below it does not move as the weeks accumulate. It opens at the BOTTOM — today's
    // week — because that is the row every other row is read relative to, and scrolling back through
    // the months is the gesture this window exists for. (白い熊, 2026-08-18.)
    //
    // Derived from the type rather than hardcoded: everything in a week row is a fixed dp except the
    // date label, which grows with the font-scale preference, and a viewport pinned at a literal 370
    // would clip the last row the moment 白い熊 moved that slider.
    val density = LocalDensity.current
    val lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
    val dateLine = if (lineHeight.isSp) with(density) { lineHeight.toDp() } else 21.dp
    // vertical padding + date + gap + (tile 34 + its 2.5 ring inset either side) + gap + load bar
    val weekRow =
        if (dateInTile) 3.dp + 39.dp + 2.dp + 18.dp else 6.dp + dateLine + 2.dp + 39.dp + 2.dp + 18.dp
    val headLine = MaterialTheme.typography.titleMedium.lineHeight
    val monthRule = (if (headLine.isSp) with(density) { headLine.toDp() } else 22.dp) + 12.dp
    val viewport = weekRow * visibleWeeks + monthRule

    val weeks = cells.chunked(7)
    // A window that scrolls ONLY when there is more than fits. The two-week cut-out has exactly its
    // own two rows and must not be given a fixed height: the month rules between rows are drawn
    // inside the window and the viewport arithmetic cannot know how many there will be, so a cut-out
    // that spans a month boundary came out with its second row sliced off (白い熊, 2026-09-03).
    // Unconstrained, the Column simply wraps whatever it holds and nothing can be cut.
    val scrolls = weeks.size > visibleWeeks
    val scroll = rememberScrollState()
    var openedAtToday by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(scroll.maxValue) {
        if (!openedAtToday && scroll.maxValue > 0) {
            scroll.scrollTo(scroll.maxValue)
            openedAtToday = true
        }
    }

    // No gap between the rows: the weekend rule has to run unbroken down the grid, and a segment
    // cannot cover space that belongs to the parent's arrangement. The breathing room moved inside
    // each day cell instead, where the rule spans it too. (白い熊, 2026-08-11: "make it a full line".)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // Outside the scrolling box, so the column a square sits in is still nameable after scrolling
        // back six weeks — a weekday header that scrolls away is a header that is never there when
        // it is wanted.
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            for (i in 0 until 7) {
                Box(
                    Modifier.weight(1f).padding(bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(heads[i], style = MaterialTheme.typography.bodyMedium, color = style.axisText)
                }
                // The week splits after Friday: the weekend is the part of a row that is remembered
                // differently from the rest of it, so it gets a rule rather than more spacing.
                if (i == 4) {
                    Box(
                        Modifier.padding(horizontal = weekendGutter).width(1.5.dp).fillMaxHeight()
                            .background(sectionInk),
                    )
                }
            }
        }
        Column(
            if (scrolls) Modifier.height(viewport).verticalScroll(scroll) else Modifier,
        ) {
        weeks.forEachIndexed { wi, week ->
            // Which month this row opens, if any: the one whose 1st it contains — and for the first
            // row in the window, simply the month it starts in, so the top of the grid is labelled
            // too rather than only the boundaries below it.
            gridMonthOpenedBy(week, wi == 0)?.let { ym ->
                MonthDivider(ym, topPadding = if (wi == 0) 0.dp else 6.dp)
            }
            // Only where the rule actually separates two days. On the last row the grid runs out
            // mid-week, and a line hanging past the final date divides nothing.
            val ruleHere = week.getOrNull(4) != null && week.getOrNull(5) != null
            Row(
                horizontalArrangement = Arrangement.spacedBy(columnGap),
                modifier = Modifier.height(IntrinsicSize.Min),
            ) {
                for (i in 0 until 7) {
                    val cell = week.getOrNull(i)
                    Column(
                        Modifier.weight(1f).padding(vertical = rowPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (cell == null) {
                            Spacer(Modifier.height(38.dp))
                        } else {
                            val dayNumber = labels.format(
                                Instant.ofEpochMilli(cell.epochDay * 86_400_000L),
                            )
                            if (!dateInTile) {
                                Text(
                                    dayNumber,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = sectionNote,
                                )
                            }
                            // The tile: the rating, in its own colour. A ring means at least one
                            // marker was outside usual that night — a qualifier on the score, never
                            // a substitute for it.
                            val skin = ScaleSkin(
                                cell.fill ?: style.grid,
                                null,
                                cell.ink ?: sectionNote,
                            )
                            // A border on a tile means TODAY, and means nothing else.
                            //
                            // The marker count used to ring the tile in its own ink, which put a
                            // yellow border on every 3 and a white one on every 5 — so the moment
                            // today got a border of its own there were three different borders in one
                            // grid and a 3 two weeks ago looked exactly like this morning. The ring is
                            // gone rather than recoloured: the fills have been solid and unbordered by
                            // 白い熊's own decision since 2026-08-12, and any ring at all is a border
                            // on a solid fill. (白い熊, 2026-08-16: "remove the yellow border from
                            // ordinary 3s and the white border from 5s".) The count it carried is on
                            // every line of the table below, beside the values that produced it.
                            //
                            // Outside the tile with a gap, not on its edge, so the fill stays exactly
                            // the solid rectangle the scale specifies.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (cell.epochDay == todayEpochDay) {
                                            Modifier
                                                .border(1.5.dp, sectionInk, RoundedCornerShape(8.dp))
                                                .padding(2.5.dp)
                                        } else {
                                            Modifier.padding(2.5.dp)
                                        },
                                    ),
                            ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(skin.fill)
                                    .then(
                                        cell.border?.let {
                                            Modifier.border(1.5.dp, it, RoundedCornerShape(5.dp))
                                        } ?: Modifier,
                                    )
                                    // The whole tile, not the digit inside it: these squares are a
                                    // seventh of a phone's width, and a hit area any smaller than the
                                    // paint would be a target 白い熊 has to aim at.
                                    .clickable { onTap(cell.epochDay) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (dateInTile) dayNumber else cell.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                                    color = skin.ink,
                                )
                                // How many sessions the day holds, when that is more than one —
                                // in the tile, ringed, in the tile's own ink.
                                //
                                // Small enough to clear the date beside it: a 12 dp ring in the
                                // corner of a 34 dp square leaves the centred numeral alone, where
                                // a 15 dp one sat on the 9 of "29". The ring is what separates it
                                // from the date — a numeral beside a numeral reads as part of it.
                                cell.count?.takeIf { it > 1 }?.let { many ->
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            // Inset from both edges, so the ring sits INSIDE the
                                            // tile rather than running along its corner
                                            // (白い熊, 2026-09-04).
                                            .padding(top = 3.dp, end = 3.dp)
                                            .size(17.dp)
                                            .border(1.5.dp, skin.ink, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            many.toString(),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 11.sp,
                                                lineHeight = 11.sp,
                                            ),
                                            fontWeight = FontWeight.Bold,
                                            color = skin.ink,
                                        )
                                    }
                                }
                            }
                            }
                            // UNDER the tile, in both calendars — never inside it.
                            //
                            // "I wrote something about this day", and nothing else. It was a dot,
                            // which said nothing and vanished on the emerald; then the note glyph on
                            // a black chip inside the tile; then, at half again the size 白い熊 asked
                            // for (2026-09-03), a chip that covered the very rating the tile exists
                            // to show — a 21 dp mark cannot share a 34 dp tile with a number. So the
                            // mark moved out from under the value instead of shrinking back: it sits
                            // in the strip below, at a size that can actually be seen, and it can
                            // never obscure anything again.
                            //
                            // The session load shares that strip, pinned to the bottom, so a day can
                            // carry both. They coincide rarely and now legibly when they do.
                            Box(Modifier.fillMaxWidth().height(18.dp)) {
                                if (cell.hasNote) {
                                    Icon(
                                        Icons.Filled.EditNote,
                                        contentDescription = null,
                                        tint = ANNOTATION_INK,
                                        modifier = Modifier.align(Alignment.TopCenter).size(15.dp),
                                    )
                                }
                                cell.bar?.takeIf { it > 0f }?.let { load ->
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth(load.coerceIn(0.15f, 1.0f))
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(ChartPalette.HEART_RATE),
                                    )
                                }
                            }
                        }
                    }
                    // The width is reserved either way, so the columns stay aligned on the row that
                    // does not draw it.
                    if (i == 4) {
                        Box(
                            Modifier
                                .padding(horizontal = weekendGutter)
                                .width(1.5.dp)
                                .fillMaxHeight()
                                .background(if (ruleHere) sectionInk else Color.Transparent),
                        )
                    }
                }
            }
        }
        }
    }
}