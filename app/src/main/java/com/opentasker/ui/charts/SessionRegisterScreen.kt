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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.sp
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
    /** `yyyyMMdd` of the morning, then its note. Empty text deletes it — see `DayNotes`. */
    onNote: (Long, String) -> Unit = { _, _ -> },
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
    // The morning whose NOTE is open, which is a second, stacked editor rather than a field inside
    // the first. The rating dialog's whole contract is that one tap files a score and closes it —
    // a text field in there would give it an Apply step and a second chance to file the wrong thing.
    // So the note pill inside it opens this instead, and dismissing this returns to the dialog
    // underneath with the night still chosen.
    var noting by rememberSaveable { mutableStateOf<Long?>(null) }

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
                        // The shared calendar — 機能訓練 draws the same one. This screen decides
                        // what a day LOOKS like and hands over squares; the grid knows nothing about
                        // nights or ratings, which is what stops the two calendars drifting apart.
                        val maxLoad = r.days.mapNotNull { it.sessionLoad }.maxOrNull()
                            ?.takeIf { it > 0 } ?: 1.0
                        DayGrid(
                            days = r.days.map { cell ->
                                val skin = scaleSkin(cell.felt, style.grid, sectionNote)
                                DayGridCell(
                                    epochDay = cell.epochDay,
                                    fill = skin.fill,
                                    ink = skin.ink,
                                    label = cell.felt?.toString()
                                        ?: if (cell.adverseCount != null) "·" else "",
                                    hasNote = cell.hasNote,
                                    bar = cell.sessionLoad?.let { (it / maxLoad).toFloat() },
                                )
                            },
                            zone = zone,
                            onTap = { rating = SessionRegister.dateKeyOf(it) },
                        )
                        RateHint()
                        Text(
                            BandText.registerLegend[lang],
                            style = MaterialTheme.typography.bodyMedium,
                            color = sectionNote,
                        )
                    }
                }

                NightsCard(r.rows, onRate = { rating = it })

                rating?.takeIf { noting == null }?.let { key ->
                    RateNightDialog(
                        dateKey = key,
                        note = r.rows.firstOrNull { it.dateKey == key }?.note,
                        onEditNote = { noting = key },
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

                noting?.let { key ->
                    NoteDialog(
                        title = BandText.morningOfNight[lang]
                            .format(nightDateFull(key, lang), nightSpanLabel(key)),
                        note = r.rows.firstOrNull { it.dateKey == key }?.note,
                        onSave = { text ->
                            onNote(key, text)
                            noting = null
                        },
                        onDismiss = { noting = null },
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
                //
                // ## Fixed widths inside ONE horizontal scroll
                //
                // The table used to divide the screen's width between five weighted columns, which
                // is the right answer for five and impossible for nine: the folded panel offers
                // 413 dp, and nine shares of it are 45 dp each — narrower than "9h40". So every
                // column is a fixed dp instead, the whole table is as wide as it needs to be, and a
                // narrow screen scrolls it sideways (白い熊, 2026-09-03).
                //
                // The heading row and every line share ONE scroll state, and they must: a header
                // that does not travel with its column is a header that labels the wrong number the
                // moment anyone scrolls, which is worse than no header at all. That is also why the
                // scroll wraps the whole block rather than each row — independent per-row scrolls
                // would let two lines disagree about which column you are looking at.
                val tableScroll = rememberScrollState()
                Column(Modifier.horizontalScroll(tableScroll)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (heading in BandText.registerColumns) HeadCell(heading[lang])
                }
                Box(Modifier.width(NightColumns.TOTAL).height(1.dp).background(style.grid))
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
                        // Given the table's width explicitly: it is inside the horizontal scroll, and
                        // a divider that filled the VIEWPORT would end in the middle of the table.
                        MonthDivider(
                            ym,
                            modifier = Modifier.width(NightColumns.TOTAL),
                            topPadding = if (i == 0) 2.dp else 10.dp,
                        )
                    }
                    NightTableRow(row, onRate = { onRate(row.dateKey) })
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
                            Box(Modifier.width(NightColumns.TOTAL).height(thickness).background(sectionInk))
                        }
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
private fun HeadCell(text: String) {
    Text(
        text,
        Modifier.width(NightColumns.CELL).padding(end = 4.dp),
        // A heading is a label, not a reading, so it gives up the size first when something has to.
        // At 白い熊's font scale of 1.3, `Deep+REM` at body size overran its column and ran into
        // `Low HR` beside it — the columns stayed 96 dp, which keeps the whole table inside the
        // unfolded panel, and the labels came down a step instead.
        style = MaterialTheme.typography.bodySmall,
        color = sectionNote,
        // ONE line, like every cell under it. A heading that wraps makes the header row twice the
        // height of a data row and stops the two reading as one grid; the labels were shortened
        // instead, which is the cheaper half of that trade (白い熊, 2026-09-03).
        maxLines = 1,
        softWrap = false,
        // Ellipsis rather than the default overflow: a heading too long for its column has to be
        // VISIBLY cut, so it gets fixed. Bleeding into the neighbour is the failure that hides.
        overflow = TextOverflow.Ellipsis,
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
 * How wide each column of the night table is — a fixed dp, never a share of the line.
 *
 * Shares were right while there were five columns and wrong the moment there were nine: the folded
 * panel offers 413 dp, and a ninth of it is 45 dp, which will not hold `9h40`. Fixed widths let the
 * table be as wide as its content needs and the SCREEN decide how much of it is visible — the card
 * scrolls it sideways when it does not fit and simply shows all of it when it does. Every width here
 * is the widest real value that column can carry, plus the 4 dp gutter each cell adds itself.
 *
 * There is deliberately no narrow variant any more. The stacked date (`09-03` over `Thu`) and the
 * stacked rating (the step over its word) were the narrow layout's two ideas, and both are simply
 * better: they were adopted for every width rather than kept as a special case, which is one layout
 * to reason about instead of two that drift.
 */
private object NightColumns {
    /**
     * ONE width, for every column (白い熊, 2026-09-03: "all cells are one-line, same width").
     *
     * Per-column widths were a false economy: they buy a few dp on the narrow columns and cost the
     * eye its grid, because nothing lines up vertically between one row and the next when a row's
     * cells are nine different sizes. A single width is also the only way to be sure every cell fits
     * on ONE LINE — with nine different ones, each is a separate thing to check and a separate thing
     * to get wrong, which is exactly how `13h34` came to render as `13h3` over `4`.
     *
     * 96 dp is set by the widest real content at 白い熊's own font scale of **1.3**, which is where
     * the previous sizing went wrong — it was chosen against the default 1.0 and every value then had
     * 30 % more type to fit than the width allowed. The binding cases are `84 +17` in the heart-rate
     * column and the `Night HR` / `Deep+REM` headings; the previews render at 1.3 so they are checked
     * rather than estimated.
     */
    val CELL = 96.dp

    /** What the rules and dividers inside the scrolling block span. */
    val TOTAL = CELL * 9
}

@Composable
private fun NightTableRow(row: SessionRegister.NightRow, onRate: () -> Unit) {
    val lang = LocalBandLanguage.current
    val n = row.night
    // A Column so a written note can sit UNDER the five columns rather than inside one of them.
    // There is no width for it up there — the table is five weighted cells on a phone — and a note is
    // a sentence, not a value: it belongs across the row it annotates, in the ink the rest of this
    // screen uses for explanation.
    Column(
        // clickable OUTSIDE the padding, so the gap between two lines belongs to one of them rather
        // than to neither — the rows are 3 dp apart and a dead strip there would be most of the misses.
        // It wraps the note line too, so tapping the note opens the same editor the row does.
        modifier = Modifier.clickable(onClick = onRate).padding(vertical = 3.dp),
    ) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ONE line: the day bold, the weekday after it at label size in the note ink. It was two
        // lines, which made the date cell taller than every cell beside it and the row taller than
        // it needed to be — and a row's height is paid on all 33 of them.
        val (day, weekday) = nightDateParts(row.dateKey, lang)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = sectionInk)) { append(day) }
                append(" ")
                withStyle(SpanStyle(color = sectionNote, fontSize = 12.sp)) { append(weekday) }
            },
            Modifier.width(NightColumns.CELL).padding(end = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false,
        )
        // The step ALONE — no word. "Below par" cannot share one line with its number at any width
        // this table can afford, and the legend directly above the table names all five in full, so
        // the cell can be the thing the legend is a key TO rather than a second copy of it.
        ValueCell(
            text = row.felt?.toString() ?: BandText.registerNightUnrated[lang],
            step = row.felt,
        )
        // ## Every column carries a colour, and not every colour means the same thing
        //
        // Heart rate, the low, sleep and blood oxygen are graded against PUBLISHED ranges — see
        // [RecoveryReference], which also carries the two caveats worth knowing (a sleeping floor
        // sits under a daytime resting rate; a wrist oximeter's error is about twice the swing it
        // measures). Deep, deep+REM and RMSSD are graded WITHIN-PERSON, against the nights before
        // them, because no published ladder fits a consumer band's staging or an age-dependent
        // RMSSD — [SessionRegister.NightReading] sets out why at length, and the ⓘ panel says which
        // is which on screen. None of the five is counted; a colour is not a verdict on the night.
        ValueCell(
            n?.nocturnalHr?.value?.let {
                "${it.roundToInt()}" +
                    (n.nocturnalHr.delta?.let { d -> " %+d".format(d.roundToInt()) } ?: "")
            },
            n?.nocturnalHr?.value?.let { RecoveryReference.nocturnalHrStep(it) },
        )
        ValueCell(
            n?.sleep?.value?.let { hoursAndMinutes(it) },
            n?.sleep?.value?.let { RecoveryReference.sleepStep(it) },
        )
        ValueCell(n?.deepMinutes?.let { hoursAndMinutes(it) }, n?.deepStep)
        ValueCell(n?.deepRemShare?.let { "${(it * 100).roundToInt()}%" }, n?.deepRemStep)
        ValueCell(
            n?.lowestHr?.let { "${it.roundToInt()}" },
            n?.lowestHr?.let { RecoveryReference.lowestHrStep(it) },
        )
        // RMSSD, in the milliseconds it is measured in — the unit is part of the reading, and a bare
        // "38" in a table of heart rates would be read as a heart rate.
        ValueCell(n?.hrvMs?.let { "${it.roundToInt()} ms" }, n?.hrvStep)
        ValueCell(
            n?.spo2?.let { "${it.roundToInt()}%" },
            n?.spo2?.let { RecoveryReference.spo2Step(it) },
        )
    }
    row.note?.takeIf { it.isNotBlank() }?.let { note ->
        Row(
            // The table's own width, not the viewport's: inside a horizontal scroll `fillMaxWidth`
            // has nothing to fill, and a note is worth the whole line it annotates.
            Modifier.width(NightColumns.TOTAL).padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                Icons.Filled.EditNote,
                contentDescription = null,
                // The annotation yellow, not the explanation cyan: this line is not the screen
                // telling 白い熊 something, it is 白い熊's own words being read back.
                tint = ANNOTATION_INK,
                modifier = Modifier.size(18.dp),
            )
            // ONE LINE, ellipsized. A note may be a paragraph, and a table whose row heights depend
            // on how much was typed that morning stops being a table. The whole of it is in the
            // editor a tap away — the same rule the sister apps' rows follow.
            Text(
                note.trim().lineSequence().first(),
                style = MaterialTheme.typography.bodyMedium,
                color = ANNOTATION_INK,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
private fun ValueCell(text: String?, step: Int?) {
    val skin = scaleSkin(step, Color.Transparent, sectionNote)
    Box(
        Modifier
            .width(NightColumns.CELL)
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(skin.fill)
            .padding(horizontal = 5.dp, vertical = 5.dp),
    ) {
        Text(
            text ?: "—",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (step != null) FontWeight.Bold else FontWeight.Normal,
            color = skin.ink,
            // One line, never wrapped and never shrunk to fit: a value that does not fit its column
            // is a COLUMN that is too narrow, and the honest response is to widen [NightColumns.CELL]
            // rather than to let `13h34` render as `13h3` over `4` (白い熊, 2026-09-03). `softWrap`
            // off means an over-long value is clipped, which is visible and gets fixed, where a wrap
            // is quietly wrong.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Minutes as `9h40` — the table's one duration format, used by asleep and by deep alike.
 *
 * Shared so the two columns cannot drift into saying the same quantity two ways, which is exactly
 * what happens when a second duration is added beside a first and formatted where it is printed.
 */
private fun hoursAndMinutes(minutes: Double): String =
    "${(minutes / 60).toInt()}h${"%02d".format((minutes % 60).roundToInt())}"

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
    /** That morning's note, shown on a pill that opens its own editor. */
    note: String? = null,
    onEditNote: () -> Unit = {},
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
            // The note for this same night, one tap away. It is the editor behind every tile and
            // every table line, so it is also the only place a note can be reached for a morning the
            // band recorded nothing on — which is exactly the morning most likely to need a sentence
            // rather than a number.
            NotePill(note = note, onClick = onEditNote)
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
