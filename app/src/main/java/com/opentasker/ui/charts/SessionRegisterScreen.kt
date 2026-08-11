package com.opentasker.ui.charts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.time.Instant
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
 */
@Composable
fun SessionRegisterScreen(
    register: SessionRegister.Register?,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val zone = remember { ZoneId.systemDefault() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
    ) {
        DetailHeader(BandText.registerTitle[lang], hasInfo = false, onBack = onBack, onInfo = {})

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
                        Grid(r.days, zone)
                        Text(
                            BandText.registerLegend[lang],
                            style = MaterialTheme.typography.bodyMedium,
                            color = sectionNote,
                        )
                    }
                }

                NightsCard(r.rows)

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
 * tell one from the other (2026-08-11). The count survives as the tile's ring, where it qualifies the
 * score instead of standing in for it, and the session load as the bar beneath.
 */
@Composable
private fun Grid(days: List<SessionRegister.DayCell>, zone: ZoneId) {
    val style = LocalChartStyle.current
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

    // No gap between the rows: the weekend rule has to run unbroken down the grid, and a segment
    // cannot cover space that belongs to the parent's arrangement. The breathing room moved inside
    // each day cell instead, where the rule spans it too. (白い熊, 2026-08-11: "make it a full line".)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
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
        cells.chunked(7).forEach { week ->
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
                            // Thickness, not colour, carries how many MEASURED markers were off — the
                            // tile's colour is already spoken for by the rating, and a second hue on
                            // the same square would read as a second rating.
                            val ringWidth = when (cell.adverseCount ?: 0) {
                                0 -> 1.dp
                                1 -> 2.dp
                                else -> 3.dp
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(skin.fill)
                                    .then(
                                        skin.edge?.let {
                                            Modifier.border(ringWidth, it, RoundedCornerShape(5.dp))
                                        } ?: Modifier,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    felt?.toString() ?: if (cell.adverseCount != null) "·" else "",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = skin.ink,
                                )
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
private fun NightsCard(rows: List<SessionRegister.NightRow>) {
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
                // The field names appear ONCE, as column headings. Repeating them on every line was
                // four labels a night of pure noise, and it buried the numbers they were labelling.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeadCell(BandText.regColDate[lang], 0.28f)
                    HeadCell(BandText.regColFelt[lang], 0.24f)
                    HeadCell(BandText.regColHr[lang], 0.16f)
                    HeadCell(BandText.regColSleep[lang], 0.18f)
                    HeadCell(BandText.regColTemp[lang], 0.14f)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(style.grid))
                // Week rules, drawn BELOW the row that ends a week reading downwards: the list runs
                // newest first, so a Monday is followed by the Sunday before it, and a Saturday by
                // the Friday before it. Weeks are how a run of nights is actually remembered — "that
                // was the weekend" — and without them ten dates are just ten dates.
                rows.forEachIndexed { i, row ->
                    NightTableRow(row)
                    if (i < rows.lastIndex) {
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
 */
@Composable
private fun ScaleLegend(onInfo: () -> Unit) {
    val lang = LocalBandLanguage.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        for (step in 1..5) {
            val skin = scaleSkin(step, ChartPalette.scale(step), sectionNote)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(skin.fill)
                        .then(skin.edge?.let { Modifier.border(1.5.dp, it, RoundedCornerShape(4.dp)) } ?: Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$step",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = skin.ink,
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    feltLabel(step)[lang],
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (step == 1) ChartPalette.scale(1) else skin.ink,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        InfoCircle(diameter = 26.dp, onClick = onInfo)
    }
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
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            BandText.bandsTitle[lang],
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = sectionInk,
        )
        Text(
            BandText.bandsBody[lang],
            style = MaterialTheme.typography.bodyMedium,
            color = sectionNote,
        )
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
@Composable
private fun NightTableRow(row: SessionRegister.NightRow) {
    val lang = LocalBandLanguage.current
    val n = row.night
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            nightDateFull(row.dateKey, lang),
            Modifier.weight(0.28f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = sectionInk,
        )
        ValueCell(
            row.felt?.let { "$it  ${feltLabel(it)[lang]}" } ?: BandText.registerNightUnrated[lang],
            row.felt,
            0.24f,
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
            0.16f,
        )
        ValueCell(
            n?.sleep?.value?.let { "${(it / 60).toInt()}h${"%02d".format((it % 60).roundToInt())}" },
            n?.sleep?.value?.let { RecoveryReference.sleepStep(it) },
            0.18f,
        )
        ValueCell(n?.temperature?.value?.let { "%.1f".format(it) }, n?.temperature?.scaleStep, 0.14f)
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
private fun RowScope.ValueCell(text: String?, step: Int?, weight: Float) {
    val skin = scaleSkin(step, Color.Transparent, sectionNote)
    Box(
        Modifier
            .weight(weight)
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(skin.fill)
            .then(skin.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(5.dp)) } ?: Modifier)
            .padding(horizontal = 5.dp, vertical = 5.dp),
    ) {
        Text(
            text ?: "—",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (step != null) FontWeight.Bold else FontWeight.Normal,
            color = skin.ink,
        )
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
