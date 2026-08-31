package com.opentasker.ui.charts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.opentasker.ui.theme.isNarrowScreen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 運動と回復 — the grid and the list.
 *
 * The grid answers "where does the training sit, and do the bad nights follow it" at a glance; the
 * list underneath carries the numbers, each session already paired with the night that followed it.
 * See [SessionRegister] for why the pairing runs forwards and why there is no correlation anywhere.
 *
 * Expect it to look sparse for a fortnight. That is the honest state of a register with one session
 * in it, and padding it out with derived numbers is precisely what this whole feature refuses to do.
 *
 * ## Why the past is editable here and nowhere else
 *
 * The 回復 card rates exactly one night, because one night is what the card is about. Everything else
 * 白い熊 has lived through is on this screen — so a night missed because the phone was elsewhere that
 * morning, or one tapped a step wrong, could be seen here and changed nowhere. Both the tiles and the
 * table lines are therefore the way in to [RateNightDialog], and the rating they write is the ordinary
 * one: same store, same night-start key, same baseline. See [BandDashboardModel.setFelt].
 */
@Composable
fun SessionRegisterScreen(
    register: SessionRegister.Register?,
    contentPadding: PaddingValues,
    /** `yyyyMMdd` of the night, then the 1–5 step. Re-tapping the step on file withdraws it. */
    onRate: (Long, Int) -> Unit,
    onBack: () -> Unit,
    onSwitchLanguage: suspend () -> Loc? = { null },
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val zone = remember { ZoneId.systemDefault() }
    // The night being rated, by the key it is filed under — one piece of state for both entry points,
    // so a tile and a table line cannot open two different editors. Saveable because a rotation with
    // the dialog open must not silently drop the night 白い熊 had chosen.
    var rating by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
    ) {
        DetailHeader(
            BandText.registerTitle[lang],
            hasInfo = false,
            onBack = onBack,
            onInfo = {},
            onSwitchLanguage = onSwitchLanguage,
        )

        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (register == null || register.entries.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(
                        BandText.registerEmpty[lang],
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = sectionNote,
                    )
                }
            }

            register?.let { r ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Grid(r.days, zone, onRate = { rating = SessionRegister.dateKeyOf(it) })
                        RateHint()
                        Text(
                            BandText.registerLegend[lang],
                            style = MaterialTheme.typography.bodyMedium,
                            color = sectionNote,
                        )
                    }
                }

                NightsCard(r.rows, onRate = { rating = it })

                rating?.let { key ->
                    RateNightDialog(
                        dateKey = key,
                        // Straight from the store's own row, never from the night's banded marker: the
                        // dialog offers back the number 白い熊 typed, so tapping it again withdraws it
                        // rather than re-writing something adjacent to it. A day with neither a night
                        // nor a rating has no row at all, which is exactly "not rated yet".
                        current = r.rows.firstOrNull { it.dateKey == key }?.felt,
                        onPick = { step ->
                            onRate(key, step)
                            rating = null
                        },
                        onDismiss = { rating = null },
                    )
                }

                r.contrast?.let { c ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                BandText.registerContrast[lang].format(
                                    c.afterSession.roundToInt(), c.nAfterSession,
                                    c.afterRest.roundToInt(), c.nAfterRest,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                BandText.registerContrastNote[lang],
                                style = MaterialTheme.typography.bodyMedium,
                                color = sectionNote,
                            )
                        }
                    }
                } ?: run {
                    if (r.entries.isNotEmpty()) {
                        Text(
                            BandText.registerContrastWaiting[lang].format(SessionRegister.MIN_CONTRAST_NIGHTS),
                            style = MaterialTheme.typography.bodyMedium,
                            color = sectionNote,
                        )
                    }
                }

                r.entries.forEach { EntryCard(it, zone) }
            }
        }
    }
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
private fun Grid(
    days: List<SessionRegister.DayCell>,
    zone: ZoneId,
    /** The tapped tile's epoch day. */
    onRate: (Long) -> Unit,
) {
    val style = LocalChartStyle.current
    // Today, marked wherever it lands. Five weeks of squares are five weeks of squares: without a
    // fixed point 白い熊 has to count columns to find the morning being asked about, and the tile most
    // likely to be tapped is exactly the one hardest to locate. (白い熊, 2026-08-16.)
    val todayEpochDay = remember(zone) { LocalDate.now(zone).toEpochDay() }
    val maxLoad = days.mapNotNull { it.sessionLoad }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val labels = remember(zone) { DateTimeFormatter.ofPattern("d").withZone(zone) }

    // Pad the head so the first column really is Monday.
    val first = days.firstOrNull() ?: return
    val firstDow = Instant.ofEpochMilli(first.epochDay * 86_400_000L).atZone(zone).dayOfWeek.value // 1=Mon
    val cells: List<SessionRegister.DayCell?> = List(firstDow - 1) { null } + days

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
    val weekRow = 6.dp + dateLine + 2.dp + 39.dp + 2.dp + 4.dp
    val headLine = MaterialTheme.typography.titleMedium.lineHeight
    val monthRule = (if (headLine.isSp) with(density) { headLine.toDp() } else 22.dp) + 12.dp
    val viewport = weekRow * VISIBLE_WEEKS + monthRule

    val weeks = cells.chunked(7)
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
                if (i == 4) Box(Modifier.width(1.5.dp).fillMaxHeight().background(sectionInk))
            }
        }
        Column(Modifier.height(viewport).verticalScroll(scroll)) {
        weeks.forEachIndexed { wi, week ->
            // Which month this row opens, if any: the one whose 1st it contains — and for the first
            // row in the window, simply the month it starts in, so the top of the grid is labelled
            // too rather than only the boundaries below it.
            monthOpenedBy(week, wi == 0)?.let { ym ->
                MonthDivider(ym, topPadding = if (wi == 0) 0.dp else 6.dp)
            }
            // Only where the rule actually separates two days. On the last row the grid runs out
            // mid-week, and a line hanging past the final date divides nothing.
            val ruleHere = week.getOrNull(4) != null && week.getOrNull(5) != null
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.height(IntrinsicSize.Min),
            ) {
                for (i in 0 until 7) {
                    val cell = week.getOrNull(i)
                    Column(
                        Modifier.weight(1f).padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (cell == null) {
                            Spacer(Modifier.height(38.dp))
                        } else {
                            Text(
                                labels.format(Instant.ofEpochMilli(cell.epochDay * 86_400_000L)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = sectionNote,
                            )
                            // The tile: the rating, in its own colour. A ring means at least one
                            // marker was outside usual that night — a qualifier on the score, never
                            // a substitute for it.
                            val felt = cell.felt
                            val skin = scaleSkin(felt, style.grid, sectionNote)
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
                                    // The whole tile, not the digit inside it: these squares are a
                                    // seventh of a phone's width, and a hit area any smaller than the
                                    // paint would be a target 白い熊 has to aim at.
                                    .clickable { onRate(cell.epochDay) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    felt?.toString() ?: if (cell.adverseCount != null) "·" else "",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = skin.ink,
                                )
                            }
                            }
                            // Session load, only when there was one — an empty track on every rest
                            // day is noise on a grid whose subject is the nights. Width carries the
                            // magnitude, so the bar still says how much and not merely whether.
                            Box(Modifier.fillMaxWidth().height(4.dp), contentAlignment = Alignment.Center) {
                                cell.sessionLoad?.takeIf { it > 0 }?.let { load ->
                                    Box(
                                        Modifier
                                            .fillMaxWidth((load / maxLoad).coerceIn(0.15, 1.0).toFloat())
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
private fun monthOpenedBy(week: List<SessionRegister.DayCell?>, isFirst: Boolean): java.time.YearMonth? {
    val days = week.filterNotNull()
    if (days.isEmpty()) return null
    days.firstOrNull { LocalDate.ofEpochDay(it.epochDay).dayOfMonth == 1 }
        ?.let { return BandMonths.ofEpochDay(it.epochDay) }
    return if (isFirst) BandMonths.ofEpochDay(days.first().epochDay) else null
}

/**
 * Every night, printed with the values actually stored for it.
 *
 * The grid above answers "do the bad nights follow the training". It cannot answer "what WAS that
 * night", because a count of markers outside usual is not a reading: three grey dots say exactly the
 * same thing for a night rated 3 and a night never rated at all. Everything the register bands was
 * previously reachable only through [EntryCard], i.e. only for nights that happened to follow a
 * marked session — so with no session marked, this screen showed no number anywhere. (白い熊,
 * 2026-08-11: "it is indistinguishable what the individual day scores are - just some dot there".)
 *
 * A missing value prints as a dash rather than vanishing, so the row keeps its shape and an absent
 * reading is visible as an absence instead of being silently closed over.
 */
@Composable
private fun NightsCard(rows: List<SessionRegister.NightRow>, onRate: (Long) -> Unit) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        // Horizontal inset cut to the vertical one: five columns of numbers want the width far more
        // than the card wants a margin. (白い熊, 2026-08-11.)
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                BandText.registerNightsTitle[lang].format(rows.size, rows.count { it.felt != null }),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (rows.isEmpty()) {
                Text(
                    BandText.registerNightsEmpty[lang],
                    style = MaterialTheme.typography.bodyMedium,
                    color = sectionNote,
                )
            } else {
                var showBands by remember { mutableStateOf(false) }
                ScaleLegend(onInfo = { showBands = !showBands })
                if (showBands) ReferenceBandsPanel()
                RateHint()
                // The field names appear ONCE, as column headings. Repeating them on every line was
                // four labels a night of pure noise, and it buried the numbers they were labelling.
                val cols = nightColumns()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeadCell(BandText.regColDate[lang], cols.date)
                    HeadCell(BandText.regColFelt[lang], cols.felt)
                    HeadCell(BandText.regColHr[lang], cols.hr)
                    HeadCell(BandText.regColSleep[lang], cols.sleep)
                    HeadCell(BandText.regColTemp[lang], cols.temp)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(style.grid))
                // Week rules, drawn BELOW the row that ends a week reading downwards: the list runs
                // newest first, so a Monday is followed by the Sunday before it, and a Saturday by
                // the Friday before it. Weeks are how a run of nights is actually remembered — "that
                // was the weekend" — and without them ten dates are just ten dates.
                //
                // Month rules are the same idea one level up, and are drawn ABOVE the first line of
                // each month for the same reason the calendar draws them above the row that opens
                // one: reading downwards the list goes backwards in time, so the heading has to
                // arrive before the days it names. They carry the year, which is why the narrow
                // layout can take it off every line.
                rows.forEachIndexed { i, row ->
                    val ym = BandMonths.ofDateKey(row.dateKey)
                    val previous = if (i == 0) null else BandMonths.ofDateKey(rows[i - 1].dateKey)
                    if (ym != null && ym != previous) {
                        MonthDivider(ym, topPadding = if (i == 0) 2.dp else 10.dp)
                    }
                    NightTableRow(row, cols, onRate = { onRate(row.dateKey) })
                    // Never under the last line of a month: the month rule below it says the same
                    // thing louder, and two rules three pixels apart read as a rendering fault.
                    val opensAMonth = i < rows.lastIndex &&
                        BandMonths.ofDateKey(rows[i + 1].dateKey) != ym
                    if (i < rows.lastIndex && !opensAMonth) {
                        when (weekdayOf(row.dateKey)) {
                            java.time.DayOfWeek.MONDAY -> 2.5.dp
                            java.time.DayOfWeek.SATURDAY -> 1.dp
                            else -> null
                        }?.let { thickness ->
                            Box(Modifier.fillMaxWidth().height(thickness).background(sectionInk))
                        }
                    }
                }
                Text(
                    BandText.registerNightsNote[lang],
                    style = MaterialTheme.typography.bodyMedium,
                    color = sectionNote,
                )
            }
        }
    }
}

/**
 * The five colours, named once, above everything that uses them.
 *
 * The scale is shared by 白い熊's own rating and by every measured value beside it, so it is worth one
 * row of the screen: learn it here and the whole table and the grid above are readable without a
 * second key.
 *
 * **Each step is the same filled pill the table draws, stretched to share the width with the i**
 * (白い熊, 2026-08-12). It was a row of 16 dp swatches with the names loose beside them, which made
 * the key a different object from the thing it was a key to — and left the row ragged, ending
 * wherever the fifth word happened to end.
 */
@Composable
private fun ScaleLegend(onInfo: () -> Unit) {
    // Five pills and an ⓘ need about 350 dp of words to stay unclipped, and a folded panel offers
    // 377 dp of line for the whole card — so on the narrow layout the key breaks over two rows,
    // 1–2–3 then 4–5, rather than shrinking. It was clipping to "1 Grea" / "2 Goo" / "4 Below p",
    // which turns a key into five colours with no names at all. (白い熊, 2026-08-18.)
    if (isNarrowScreen()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (step in 1..3) LegendPill(step)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (step in 4..5) LegendPill(step)
                InfoCircle(diameter = 30.dp, onClick = onInfo)
            }
        }
        return
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        for (step in 1..5) LegendPill(step)
        InfoCircle(diameter = 30.dp, onClick = onInfo)
    }
}

/**
 * One step of the key, as the same filled pill the table draws.
 *
 * Weighted by what it has to hold, not equally: five equal fifths would clip "Below par" while
 * leaving air around "Good". The row still fills the line exactly — the widths just land where the
 * words are.
 */
@Composable
private fun RowScope.LegendPill(step: Int) {
    val lang = LocalBandLanguage.current
    val skin = scaleSkin(step, ChartPalette.scale(step), sectionNote)
    val label = feltLabel(step)[lang]
    Row(
        Modifier
            .weight(legendWeight(label))
            .clip(RoundedCornerShape(6.dp))
            .background(skin.fill)
            .padding(horizontal = 3.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$step",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = skin.ink,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = skin.ink,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * How much of the legend row one pill is owed: the digit, a gap, and the word.
 *
 * A CJK glyph is a full em where a Latin one is roughly half, so 「いまひとつ」 needs the width of ten
 * Latin characters and not five. Counting that way keeps the row honest in both languages without
 * measuring text — the numbers only have to be proportional to each other, not exact.
 */
private fun legendWeight(label: String): Float {
    val units = label.sumOf { if (it.code > 0x2E80) 2 else 1 }
    return (units + 3).toFloat()
}

/**
 * What the bands actually are, on tap, with their sources.
 *
 * On screen rather than in a commit message because 白い熊 asked what qualifies as excellent and the
 * scale could not answer: a colour that grades a night owes the reader the cut points it used.
 */
@Composable
private fun ReferenceBandsPanel() {
    val lang = LocalBandLanguage.current
    val accent = sectionInk
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.5.dp, accent, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InfoHeading(BandText.bandsTitle[lang])

        // 実睡眠 — five rungs, each with the exact times that land a night on it and why they are
        // there. Prose could say all of this and did; a reader looking up "what counts as 7h40"
        // then had to find it inside a paragraph. (白い熊, 2026-08-12.)
        BandSection(BandText.bandsSleepTitle[lang]) {
            BandRung(1, BandText.bandsSleep1[lang], BandText.bandsSleep1Why[lang])
            BandRung(2, BandText.bandsSleep2[lang], BandText.bandsSleep2Why[lang])
            BandRung(3, BandText.bandsSleep3[lang], BandText.bandsSleep3Why[lang])
            BandRung(4, BandText.bandsSleep4[lang], BandText.bandsSleep4Why[lang])
            BandRung(5, BandText.bandsSleep5[lang], BandText.bandsSleep5Why[lang])
        }

        // 夜間心拍 — one shared reason for the whole ladder, because there IS one: it is a single
        // published series of decades, not five separately-argued cut points.
        BandSection(BandText.bandsHrTitle[lang]) {
            BandRung(1, BandText.bandsHr1[lang])
            BandRung(2, BandText.bandsHr2[lang])
            BandRung(3, BandText.bandsHr3[lang])
            BandRung(4, BandText.bandsHr4[lang])
            BandRung(5, BandText.bandsHr5[lang])
            InfoBody(BandText.bandsHrWhy[lang])
            InfoBody(BandText.bandsHrCaveat[lang])
        }

        BandSection(BandText.bandsTempTitle[lang]) {
            InfoBody(BandText.bandsTempNoBand[lang], bold = true)
            InfoBody(BandText.bandsTempWhy[lang])
        }

        BandSection(BandText.bandsFeltTitle[lang]) {
            InfoBody(BandText.bandsFeltWhy[lang])
        }

        InfoBody(BandText.bandsRingNote[lang])
    }
}

/** One metric inside the panel: its name as a heading, then whatever explains it. */
@Composable
private fun BandSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        InfoHeading(title)
        content()
    }
}

/**
 * One rung: the step as the same coloured box the table uses, then the values, then the reason.
 *
 * The box is the point — a reader who has seen a 4 in the 実睡眠 column can find the same 4 here and
 * read what it took to earn it, without translating between a colour and a number on the way.
 */
@Composable
private fun BandRung(step: Int, range: String, why: String? = null) {
    val skin = scaleSkin(step, Color.Transparent, sectionNote)
    val lang = LocalBandLanguage.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Row(
            Modifier
                .width(126.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(skin.fill)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$step",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = skin.ink,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                feltLabel(step)[lang],
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = skin.ink,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            InfoBody(range, bold = true)
            why?.let { InfoBody(it) }
        }
    }
}

@Composable
private fun RowScope.HeadCell(text: String, weight: Float) {
    Text(
        text,
        Modifier.weight(weight),
        style = MaterialTheme.typography.bodyMedium,
        color = sectionNote,
    )
}

/**
 * One line of the table: the date, the rating in its own colour, then the measured values.
 *
 * Colour carries the state so the eye can run down a column and see the chain, and every value keeps
 * its number so nothing is expressed by hue alone. A value the band never recorded is a dash rather
 * than a gap, because an absence is itself a fact about the night.
 */
/**
 * How wide each column of the night table is, as a share of the line.
 *
 * Two sets, because the same five columns cannot be laid out the same way on a 916 dp unfolded panel
 * and a 413 dp folded one. On the narrow set the date gives up more than half its share — it prints
 * `08-18` over `Tue` instead of `2026-08-18 (Tue)` on one line — and hands it to the three value
 * columns, which had been squeezing `36.4` onto two lines. (白い熊, 2026-08-18: the folded screenshots.)
 */
private class NightColumns(
    val date: Float,
    val felt: Float,
    val hr: Float,
    val sleep: Float,
    val temp: Float,
)

@Composable
private fun nightColumns(): NightColumns = if (isNarrowScreen()) {
    NightColumns(date = 0.16f, felt = 0.26f, hr = 0.19f, sleep = 0.20f, temp = 0.19f)
} else {
    NightColumns(date = 0.28f, felt = 0.24f, hr = 0.16f, sleep = 0.18f, temp = 0.14f)
}

@Composable
private fun NightTableRow(row: SessionRegister.NightRow, cols: NightColumns, onRate: () -> Unit) {
    val lang = LocalBandLanguage.current
    val narrow = isNarrowScreen()
    val n = row.night
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // clickable OUTSIDE the padding, so the gap between two lines belongs to one of them rather
        // than to neither — the rows are 3 dp apart and a dead strip there would be most of the misses.
        modifier = Modifier.clickable(onClick = onRate).padding(vertical = 3.dp),
    ) {
        if (narrow) {
            // The weekday under the date rather than in brackets after it: it is the shorter of the
            // two lines either way, so stacking costs no width at all and buys the whole bracket.
            val (day, weekday) = nightDateParts(row.dateKey, lang)
            Column(Modifier.weight(cols.date)) {
                Text(
                    day,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = sectionInk,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    weekday,
                    style = MaterialTheme.typography.labelMedium,
                    color = sectionNote,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        } else {
            Text(
                nightDateFull(row.dateKey, lang),
                Modifier.weight(cols.date),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = sectionInk,
            )
        }
        // Narrow: the step on its own line and the word under it, so "Below par" stops breaking
        // across three lines and dragging the whole row's height with it.
        ValueCell(
            text = if (narrow) {
                row.felt?.toString() ?: BandText.registerNightUnrated[lang]
            } else {
                row.felt?.let { "$it  ${feltLabel(it)[lang]}" } ?: BandText.registerNightUnrated[lang]
            },
            step = row.felt,
            weight = cols.felt,
            sub = if (narrow) row.felt?.let { feltLabel(it)[lang] } else null,
        )
        // Heart rate and sleep are graded against the published reference ranges, NOT against 白い熊's
        // own median: a within-person scale can only call a six-hour habit "usual", never short.
        // Temperature keeps the within-person step, because a wrist sensor that tracks the room at
        // r = 0.961 has no absolute band worth having. See [RecoveryReference].
        ValueCell(
            n?.nocturnalHr?.value?.let {
                "${it.roundToInt()}" +
                    (n.nocturnalHr.delta?.let { d -> " %+d".format(d.roundToInt()) } ?: "")
            },
            n?.nocturnalHr?.value?.let { RecoveryReference.nocturnalHrStep(it) },
            cols.hr,
        )
        ValueCell(
            n?.sleep?.value?.let { "${(it / 60).toInt()}h${"%02d".format((it % 60).roundToInt())}" },
            n?.sleep?.value?.let { RecoveryReference.sleepStep(it) },
            cols.sleep,
        )
        ValueCell(n?.temperature?.value?.let { "%.1f".format(it) }, n?.temperature?.scaleStep, cols.temp)
    }
}

/**
 * A graded value drawn as the same chip the calendar uses — filled, ringed, with the number on top.
 *
 * The table and the grid now say the same thing the same way, so a colour learned in one is the same
 * colour in the other rather than a second convention to hold in mind. (白い熊, 2026-08-11: "we need
 * same box cell display for the bottom table".)
 */
@Composable
private fun RowScope.ValueCell(text: String?, step: Int?, weight: Float, sub: String? = null) {
    val skin = scaleSkin(step, Color.Transparent, sectionNote)
    Box(
        Modifier
            .weight(weight)
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(skin.fill)
            .padding(horizontal = 5.dp, vertical = 5.dp),
    ) {
        Column {
            Text(
                text ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (step != null) FontWeight.Bold else FontWeight.Normal,
                color = skin.ink,
            )
            // Same ink as the number above it, not the note ink: it is inside a filled step chip, and
            // the chip's fill is chosen to carry exactly one foreground.
            sub?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.ink,
                )
            }
        }
    }
}

/**
 * That the nights can be tapped, said out loud — on both cards, in the same words.
 *
 * A tile and a table line look exactly as they did before they became buttons, and nothing else on
 * this screen is tappable, so there is no established gesture to infer this one from. It is printed in
 * the data ink rather than the note ink for that reason: it is an instruction, not a caveat.
 */
@Composable
private fun RateHint() {
    val lang = LocalBandLanguage.current
    Text(
        BandText.registerRateHint[lang],
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = sectionInk,
    )
}

/**
 * Rate one named night — the editor behind every tile and every table line.
 *
 * ## Why a dialog, and why the date is the first thing in it
 *
 * The card's row of five pills works because there is only one night it could mean. Here there are
 * five weeks of them, and a rating filed against the wrong night is worse than no rating: it is wrong
 * data that looks authored, and it goes on feeding the baseline and the counting rule silently. So the
 * night is named in full, weekday and all, above the thing that writes it — and the editor is modal so
 * the only night on screen while choosing is the one being chosen for.
 *
 * The steps are full-width rows rather than the card's compact pills, in the same scale colours and
 * the same order. A row is unmissable at arm's length where a 12 dp pill is not, and this is the
 * screen where a slip is least likely to be noticed afterwards.
 *
 * Any tap closes the dialog. Nothing is "applied" separately: with one value to set there is no state
 * to accumulate, and a picker that then wants confirming is a second chance to file the wrong thing.
 */
@Composable
private fun RateNightDialog(
    dateKey: Long,
    current: Int?,
    onPick: (Int) -> Unit,
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
            // The morning in full, then the night it appraises — "2026-08-16 (Sun) · the night 15→16".
            // A score put on the wrong day is worse than none: it looks authored and it goes on
            // feeding the baseline silently, so the dialog names its subject twice over, by the day
            // 白い熊 woke and by the span they slept.
            Text(
                BandText.recoveryAsk[lang].format(
                    BandText.morningOfNight[lang]
                        .format(nightDateFull(dateKey, lang), nightSpanLabel(dateKey)),
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = sectionInk,
            )
            for (step in 1..5) {
                val skin = scaleSkin(step, Color.Transparent, sectionNote)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(skin.fill)
                        .clickable { onPick(step) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$step",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = skin.ink,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        feltLabel(step)[lang],
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = skin.ink,
                    )
                    // The rating on file is marked by a GLYPH, not by a lighter fill or a ring: the
                    // five colours are fixed and solid by 白い熊's own decision (2026-08-12), and a
                    // sixth appearance for "this one is selected" would be a colour to learn on a
                    // screen whose whole point is that the colours already mean something.
                    if (current == step) {
                        Text(
                            "✓",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = skin.ink,
                        )
                    }
                }
            }
            NoteText(BandText.rateClearHint[lang])
            NoteText(BandText.rateLateNote[lang])
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

/** The weekday a `yyyyMMdd` key falls on, for the week rules. */
private fun weekdayOf(dateKey: Long): java.time.DayOfWeek? = runCatching {
    java.time.LocalDate.of(
        (dateKey / 10_000L).toInt(),
        ((dateKey / 100L) % 100L).toInt(),
        (dateKey % 100L).toInt(),
    ).dayOfWeek
}.getOrNull()

@Composable
private fun EntryCard(e: SessionRegister.Entry, zone: ZoneId) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val day = remember(zone) { DateTimeFormatter.ofPattern("MM-dd").withZone(zone) }
    val time = remember(zone) { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    day.format(Instant.ofEpochMilli(e.session.startMs)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    BandText.registerSession[lang].format(
                        time.format(Instant.ofEpochMilli(e.session.startMs)),
                        e.session.minutes,
                        e.metMinutes.roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                e.peakHr?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        BandText.registerPeak[lang].format(it.roundToInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = sectionNote,
                    )
                }
            }
            val night = e.night
            if (night == null) {
                Text(
                    BandText.registerNoNight[lang],
                    style = MaterialTheme.typography.bodyMedium,
                    color = sectionNote,
                )
            } else {
                Text(
                    "→ " + listOfNotNull(
                        night.nocturnalHr.value?.let {
                            "${BandText.markerNocturnalHr[lang]} ${it.roundToInt()}" +
                                (night.nocturnalHr.delta?.let { d -> " (%+d)".format(d.roundToInt()) } ?: "")
                        },
                        night.sleep.value?.let {
                            "${BandText.markerSleep[lang]} ${(it / 60).toInt()}h${(it % 60).roundToInt()}m"
                        },
                        night.felt.value?.let { "${BandText.markerFelt[lang]} ${it.roundToInt()}" },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = sectionNote,
                )
                Text(
                    when (night.adverseCount) {
                        0 -> BandText.recoveryAllUsual[lang]
                        1 -> BandText.recoveryOneOff[lang]
                        else -> BandText.recoveryTwoOff[lang].format(night.adverseCount)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (night.adverseCount == 0) ChartPalette.BAND_GOOD else ChartPalette.BAND_WARN,
                )
            }
        }
    }
}
