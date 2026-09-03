package com.opentasker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.ui.theme.isNarrowScreen
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 回復 — last night's markers against 白い熊's own normal.
 *
 * ## Why the headline counts rather than scores
 *
 * See [Recovery] for the evidence in full. In one line: no readiness composite has ever been
 * validated, the only composite shape with published support is a **count** of how many criteria are
 * elevated, and a confident wrong verdict measurably degrades how someone appraises their day. So the
 * headline says how many markers are outside 白い熊's usual range and names them, and every marker
 * carries its number and its usual range underneath.
 *
 * ## Why there is a range on every row
 *
 * Across five experiments with 5 780 participants (van der Bles et al. 2020, *PNAS*) an explicit
 * numeric range cost almost nothing in trust (d = −0.15) where a verbal hedge cost a great deal
 * (d = −0.55) — and a point value that is visibly wrong once is distrusted permanently, where a
 * banded one survives being wrong. `58 bpm · usual 55–61` is therefore the whole design brief.
 */
@Composable
fun RecoveryCard(
    recovery: RecoveryResult?,
    load: RecoveryBuild.LoadReading?,
    sri: Double?,
    sleepScore: SleepScore.Breakdown?,
    peak30Cadence: Double?,
    peakCadenceDay: Long?,
    /** Minutes awake inside last night's session, so the two sleep numbers can be reconciled. */
    awakeMinutes: Int?,
    regime: RecoveryRegime.Regime?,
    feltToday: Int?,
    /** `yyyyMMdd` start date of the night [feltToday] belongs to; the row names it rather than "today". */
    feltNight: Long?,
    /** `yyyyMMdd` of the night the markers describe, so the card can own up when it is not [feltNight]. */
    recordedNight: Long?,
    feltEnabled: Boolean,
    onFelt: (Int) -> Unit,
    /** How much is behind the register, so the way in can say so rather than merely exist. */
    registerNights: Int,
    registerRated: Int,
    onOpenRegister: () -> Unit,
    onClick: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    SectionCard(onClick = onClick) {
        SectionTitle(BandText.recoveryTitle[lang]) { InfoCircle(diameter = 28.dp, onClick = onClick) }

        if (recovery == null || !recovery.hasHeadline) {
                NoteText(
                    BandText.recoveryCollecting[lang].format(
                        (Recovery.MIN_NIGHTS_FOR_ANY - (recovery?.nightsOfHistory ?: 0)).coerceAtLeast(0),
                    ),
                )
            } else {
                Headline(recovery)
            }

            recovery?.takeIf { it.hasHeadline }?.let { r ->
                val labels = r.markers.map { markerLabel(it.marker)[lang] }
                val width = markerLabelWidth(labels)
                r.markers.forEach { MarkerRow(it, width) }

                // The 睡眠 card below headlines the whole session; this marker is time ACTUALLY
                // asleep. 白い熊 read 7h40 against 8h07 as two different nights (2026-08-10) — it is
                // one night and 27 minutes awake, so the card now says so rather than leaving the
                // reader to subtract.
                r.markers.firstOrNull { it.marker == RecoveryMarker.SLEEP }?.value?.let { asleep ->
                    awakeMinutes?.takeIf { it > 0 }?.let { Note(BandText.sleepAwakeNote[lang].format(it)) }
                }
                if (r.confidence == RecoveryConfidence.PROVISIONAL) {
                    Note(BandText.recoveryProvisional[lang].format(r.nightsOfHistory))
                }
                // The annotation goes BEFORE the illness note: a late session is the ordinary
                // explanation and should be read first, precisely so an elevated night is not
                // mistaken for something it is not.
                r.lateEffortMinutesBeforeSleep?.takeIf { r.markers.first().band == RecoveryBand.HIGH }
                    ?.let { Note(BandText.recoveryLateEffort[lang].format(it)) }
                if (r.illnessSigns) Note(BandText.recoveryIllness[lang], warn = true)
            }

            sleepScore?.let { SleepScoreRow(it) }
            sri?.let { SriRow(it) }
            load?.let { LoadRow(it) }
            peak30Cadence?.let { PeakCadenceRow(it, peakCadenceDay) }
            // The way into the register. It sits under the load block because that is the number it
            // explains: the weekly figure is a total, and this is what it is made of.
            //
            // A full-width pill carrying its own counts, not a line of link text. As a caption it was
            // the smallest thing on a long card and read as a footnote, so the whole night-by-night
            // record — every rating, every measured value — sat behind something easy to never
            // notice. The counts are the point: they say there IS something in there.
            // (白い熊, 2026-08-11: "it should be prominent".)
            RegisterButton(registerNights, registerRated, onOpenRegister)
            // Regime notes go LAST and in amber: they qualify everything above them, so they read
            // as a caveat on the card rather than as another marker on it.
        regime?.let { RegimeNotes(it) }
        // A morning after a night the band did not record is still a morning to score, so the row
        // below can be about a later morning than everything above it. Said out loud, in amber,
        // immediately above the row that names the other one — the two dates side by side are the
        // whole explanation, and without it the card looks as though it is contradicting itself.
        if (recordedNight != null && feltNight != null && recordedNight != feltNight) {
            Note(
                BandText.recoveryNightMissing[lang]
                    .format(nightDateLabel(feltNight), nightDateLabel(recordedNight)),
                warn = true,
            )
        }
        if (feltEnabled) FeltRow(feltToday, feltNight, onFelt)
    }
}

@Composable
private fun Headline(r: RecoveryResult) {
    val lang = LocalBandLanguage.current
    val text = when (r.adverseCount) {
        0 -> BandText.recoveryAllUsual[lang]
        1 -> BandText.recoveryOneOff[lang]
        else -> BandText.recoveryTwoOff[lang].format(r.adverseCount)
    }
    // Colour carries the same thing the words do, never the thing alone — the words are the message.
    val tint = when (r.adverseCount) {
        0 -> ChartPalette.BAND_GOOD
        1 -> ChartPalette.BAND_WARN
        else -> ChartPalette.BAND_SERIOUS
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp),
            fontWeight = FontWeight.Bold,
            color = sectionInk,
        )
    }
    if (r.adverseMarkers.isNotEmpty()) {
        Text(
            r.adverseMarkers.joinToString(if (lang == BandLanguage.EN) ", " else "・") {
                markerLabel(it)[lang]
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = sectionNote,
        )
    }
}

/**
 * One marker: its name, its value, its band, and the range that band was judged against.
 *
 * On a wide panel that is one line. On a **narrow** one it is two, and it has to be: the name alone
 * runs to 145 dp ("Nocturnal heart rate"), the value takes 86 and the chip another 55, which on the
 * folded Mate XT's 355 dp of card leaves the range about 60 dp — and because the range is the only
 * part with no width floor, Compose gave it the leftovers and broke `usual 58–68` down the right
 * edge one character per line. (白い熊, 2026-08-18, the folded screenshot: a column of `u/s/u/a/l`.)
 *
 * The range moves to its own line under the value rather than being dropped or shortened: `58 bpm ·
 * usual 55–61` is the whole design brief of this card, and a banded number with its band's range
 * hidden is exactly the reading it refuses to give.
 */
@Composable
private fun MarkerRow(m: MarkerReading, labelWidth: Dp) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val narrow = isNarrowScreen()
    val usual = if (m.usualLo != null && m.usualHi != null) {
        BandText.usualRange[lang].format(
            format(m.marker, m.usualLo, lang, unit = false),
            format(m.marker, m.usualHi, lang, unit = false),
        )
    } else {
        null
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                markerLabel(m.marker)[lang],
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                // Narrow: the name takes what is left rather than a measured width, so it ellipsises
                // instead of pushing the value and the chip off the line.
                modifier = if (narrow) Modifier.weight(1f, fill = false) else Modifier.width(labelWidth),
                color = if (m.value == null) sectionNote else sectionInk,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                m.value?.let { format(m.marker, it, lang) } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = sectionInk,
                maxLines = 1,
                softWrap = false,
                modifier = if (narrow) Modifier else Modifier.width(86.dp),
            )
            Spacer(Modifier.width(if (narrow) 8.dp else 0.dp))
            BandChip(m)
            if (!narrow) {
                Spacer(Modifier.weight(1f))
                // The usual range, on every row, always. This is the whole point of the card.
                usual?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                        color = sectionNote,
                    )
                }
            }
        }
        if (narrow) {
            usual?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = sectionNote,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun BandChip(m: MarkerReading) {
    val lang = LocalBandLanguage.current
    val (label, tint) = when (m.band) {
        RecoveryBand.USUAL -> BandText.bandUsual[lang] to ChartPalette.BAND_GOOD
        RecoveryBand.HIGH -> BandText.bandHigh[lang] to
            (if (m.adverse) ChartPalette.BAND_SERIOUS else ChartPalette.BAND_GOOD)
        RecoveryBand.LOW -> BandText.bandLow[lang] to
            (if (m.adverse) ChartPalette.BAND_SERIOUS else ChartPalette.BAND_GOOD)
        RecoveryBand.UNKNOWN -> BandText.bandUnknown[lang] to LocalChartStyle.current.axisText
    }
    ValueChip(label, tint)
}

/**
 * Sleep regularity — the single best-evidenced number on this card.
 *
 * It sits with the markers rather than in the counting rule because it is a property of the last
 * fortnight, not of last night: it cannot be "outside usual" for one night, and counting a slow
 * fortnightly quantity alongside three nightly ones would let one bad week hold the headline down
 * indefinitely.
 */
@Composable
private fun SleepScoreRow(b: SleepScore.Breakdown) {
    val lang = LocalBandLanguage.current
    val label = when (SleepScore.band(b.total)) {
        SleepScoreBand.VERY_LOW -> BandText.scoreVeryLow
        SleepScoreBand.LOW -> BandText.scoreLow
        SleepScoreBand.OK -> BandText.scoreOk
        SleepScoreBand.HIGH -> BandText.scoreHigh
        SleepScoreBand.VERY_HIGH -> BandText.scoreVeryHigh
    }
    val tint = when (SleepScore.band(b.total)) {
        SleepScoreBand.VERY_LOW, SleepScoreBand.LOW -> ChartPalette.BAND_SERIOUS
        SleepScoreBand.OK -> ChartPalette.BAND_WARN
        else -> ChartPalette.BAND_GOOD
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubHeading(BandText.sleepScoreTitle[lang])
            Spacer(Modifier.width(10.dp))
            Text("${b.total}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Chip(label[lang], tint)
        }
        Note(
            BandText.sleepScoreParts[lang].format(
                b.duration.roundToInt(), b.consistency.roundToInt(), b.interruptions.roundToInt(),
            ) + (b.onsetDeviationMinutes?.let { " · " + BandText.onsetDrift[lang].format(it.roundToInt()) } ?: ""),
        )
        Note(BandText.sleepScoreNote[lang])
    }
}

@Composable
private fun PeakCadenceRow(peak: Double, day: Long?) {
    val lang = LocalBandLanguage.current
    val label = day?.let {
        java.time.Instant.ofEpochMilli(it * 86_400_000L)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
    } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubHeading(BandText.peakCadence[lang])
            Spacer(Modifier.width(10.dp))
            Text(
                "${peak.roundToInt()}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Note(BandText.peakCadenceNote[lang].format(label, peak.roundToInt()))
    }
}

@Composable
private fun RegimeNotes(r: RecoveryRegime.Regime) {
    val lang = LocalBandLanguage.current
    r.daysSinceZoneChange?.let { Note(BandText.regimeTravel[lang].format(it), warn = true) }
    if (r.altitudeNights != null && r.spo2Drop != null) {
        Note(BandText.regimeAltitude[lang].format(r.spo2Drop, r.altitudeNights), warn = true)
    }
}

@Composable
private fun Chip(text: String, tint: Color) = ValueChip(text, tint)

@Composable
private fun SriRow(sri: Double) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val band = SleepRegularity.band(sri)
    val label = when (band) {
        RegularityBand.IRREGULAR -> BandText.sriIrregular
        RegularityBand.MIDDLING -> BandText.sriMiddling
        RegularityBand.REGULAR -> BandText.sriRegular
        RegularityBand.VERY_REGULAR -> BandText.sriVeryRegular
    }
    val tint = when (band) {
        RegularityBand.IRREGULAR -> ChartPalette.BAND_SERIOUS
        RegularityBand.MIDDLING -> ChartPalette.BAND_WARN
        else -> ChartPalette.BAND_GOOD
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubHeading(BandText.sriTitle[lang])
            Spacer(Modifier.width(10.dp))
            Text(
                "${sri.roundToInt()}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(tint.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(label[lang], style = MaterialTheme.typography.labelSmall, color = tint)
            }
        }
        Note(BandText.sriNote[lang])
    }
}

@Composable
private fun LoadRow(load: RecoveryBuild.LoadReading) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                BandText.loadTitle[lang],
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp),
                fontWeight = FontWeight.Bold,
                color = sectionInk,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                load.ratio?.let { String.format("%.2f", it) } ?: "—",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(10.dp))
            load.band?.let {
                Text(
                    loadBandLabel(it)[lang],
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = sectionNote,
                )
            }
            Spacer(Modifier.weight(1f))
            load.weekly?.let {
                Text(
                    BandText.loadWeekly[lang].format(it.roundToInt()),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = sectionNote,
                )
            }
        }
        // The marked half, called out separately: it is the part 白い熊 had to tap for, and the part
        // that would be silently missing if they stopped.
        load.weeklyFromSessions?.let {
            Note(
                BandText.loadSessions[lang].format(load.sessionsThisWeek) + " · " +
                    BandText.loadFromSessions[lang].format(it.roundToInt()),
            )
        }
        if (load.sessionOpen) Note(BandText.loadSessionOpen[lang], warn = true)
        Note(BandText.loadFloorNote[lang])
    }
}

/**
 * The one thing 白い熊 taps.
 *
 * Five steps, not ten: the value of this input is that it takes a second, and a finer scale would
 * invite precision the instrument does not have. It is here rather than behind a notification
 * because a prompt that interrupts is a prompt that gets dismissed.
 */
@Composable
private fun FeltRow(feltToday: Int?, feltNight: Long?, onFelt: (Int) -> Unit) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val morning = feltNight?.let { nightDateLabel(it) } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            // Unanswered, the card is only ever asking about THIS morning — [RecoveryBuild.ratableMorning]
            // offers no other — so it says so in the words 白い熊 would use rather than reciting a date
            // back at them. Answered, the date returns: that is the line that has to be checkable.
            feltToday?.let { BandText.recoveryAskDone[lang].format(morning, feltLabel(it)[lang]) }
                ?: BandText.recoveryAskToday[lang],
            style = MaterialTheme.typography.bodyMedium,
            color = if (feltToday == null) sectionInk else sectionNote,
        )
        // The buttons wear the scale's own colours, so the thing 白い熊 taps and the thing the table
        // prints afterwards are the same object. An unselected step is its colour at low strength
        // with the colour as ink; the selected one is the filled pill itself.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in RecoveryLogScale) {
                val selected = feltToday == n
                val tint = ChartPalette.scale(n)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (selected) {
                                Modifier.background(tint)
                            } else {
                                Modifier
                                    .background(tint.copy(alpha = 0.20f))
                                    .border(1.dp, tint, RoundedCornerShape(10.dp))
                            },
                        )
                        // padding BEFORE clickable: the other order makes the touch target the
                        // glyph rather than the pill — about 20 px — so a tap aimed at the number
                        // falls through to the card behind it and opens the detail screen instead.
                        // Found by missing it repeatedly.
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable { onFelt(n) },
                ) {
                    Text(
                        "$n",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) ChartPalette.scaleInk(n) else tint,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "1 = ${BandText.feltGreat[lang]} · 5 = ${BandText.feltWrecked[lang]}",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = sectionNote,
            )
        }
    }
}

@Composable
private fun Note(text: String, warn: Boolean = false) = NoteText(text, warn = warn)

private val RecoveryLogScale = 1..5

fun markerLabel(m: RecoveryMarker): Loc = when (m) {
    RecoveryMarker.NOCTURNAL_HR -> BandText.markerNocturnalHr
    RecoveryMarker.SLEEP -> BandText.markerSleep
    RecoveryMarker.FELT -> BandText.markerFelt
    RecoveryMarker.TEMPERATURE -> BandText.markerTemperature
    // The display-only three never appear on this card — they exist to colour a night-table cell.
    // Named here rather than dismissed with an `else` so that a marker added later cannot slip
    // through unlabelled, which is what an `else` branch in a label function is for.
    RecoveryMarker.DEEP -> BandText.regColDeep
    RecoveryMarker.DEEP_REM -> BandText.regColDeepRem
    RecoveryMarker.HRV -> BandText.regColHrv
}

fun loadBandLabel(b: LoadBand): Loc = when (b) {
    LoadBand.DETRAINING -> BandText.loadDetraining
    LoadBand.MAINTAINING -> BandText.loadMaintaining
    LoadBand.PRODUCTIVE -> BandText.loadProductive
    LoadBand.OVERREACHING -> BandText.loadOverreaching
}

/** The way into 運動と回復: full width, its own counts, unmissable. */
@Composable
private fun RegisterButton(nights: Int, rated: Int, onClick: () -> Unit) {
    val lang = LocalBandLanguage.current
    val accent = ChartPalette.HEART_RATE
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.16f))
            .border(1.5.dp, accent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                BandText.registerOpen[lang],
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(
                BandText.registerOpenCounts[lang].format(nights, rated),
                style = MaterialTheme.typography.bodyMedium,
                color = sectionNote,
            )
        }
        Text(
            "▸",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}

/** `yyyyMMdd` → `2026-08-10`, the shape every other dated row on this card prints. */
fun nightDateLabel(key: Long): String =
    "%04d-%02d-%02d".format(key / 10_000L, (key / 100L) % 100L, key % 100L)

/**
 * The 1–5 rating as one colour, so a run of good nights and a run of bad ones are different shapes
 * on the page rather than different digits to read. See [ChartPalette.SCALE] for the scale itself.
 */
fun feltColor(rating: Int): Color = ChartPalette.scale(rating)

/** How one graded value is painted: the fill behind it, its outline, and the ink on top. */
data class ScaleSkin(val fill: Color, val edge: Color?, val ink: Color)

/**
 * One appearance for every graded value, so a calendar tile and a table cell showing the same step
 * are the same object twice and not two things that happen to share a hue.
 *
 * **Full-strength fill, no outline** (白い熊, 2026-08-12). It used to be a 30 %-alpha wash with the
 * hue as a ring around it, which made every graded value a tinted outline of the black card rather
 * than a block of colour, and left the darkest step needing a pale ring to be visible at all. The
 * colours now carry themselves: each is drawn solid with the ink [ChartPalette.SCALE_INK] pairs it
 * with. An ungraded value keeps the caller's [neutral], which is the only case with no colour to use.
 */
fun scaleSkin(step: Int?, neutral: Color, neutralInk: Color): ScaleSkin = when (step) {
    null -> ScaleSkin(neutral, null, neutralInk)
    else -> ScaleSkin(ChartPalette.scale(step), null, ChartPalette.scaleInk(step))
}

/**
 * `20260810` → `2026-08-10 (Mon)` / `2026-08-10（月）`.
 *
 * The year is spelled out and the weekday named because these are dates 白い熊 reads against memory —
 * "which night was that" is answered by the day of the week far more often than by the number, and a
 * bare `08-10` answers neither on its own.
 */
/**
 * The night a morning key appraises, as the two day-numbers it ran between: `15→16`.
 *
 * Day numbers alone, not full dates — the morning beside it already carries the month and the year,
 * and the point of this half is the SPAN, which is what tells 白い熊 that "the morning of the 16th"
 * is the night they went to bed on the 15th. A month boundary still reads correctly (`31→1`).
 */
fun nightSpanLabel(morningKey: Long): String {
    val morning = runCatching {
        java.time.LocalDate.of(
            (morningKey / 10_000L).toInt(),
            ((morningKey / 100L) % 100L).toInt(),
            (morningKey % 100L).toInt(),
        )
    }.getOrNull() ?: return nightDateLabel(morningKey)
    return "%d→%d".format(morning.minusDays(1).dayOfMonth, morning.dayOfMonth)
}

fun nightDateFull(key: Long, lang: BandLanguage): String {
    val date = runCatching {
        java.time.LocalDate.of(
            (key / 10_000L).toInt(),
            ((key / 100L) % 100L).toInt(),
            (key % 100L).toInt(),
        )
    }.getOrNull() ?: return nightDateLabel(key)
    val dow = date.dayOfWeek.value // 1 = Monday
    return if (lang == BandLanguage.EN) {
        "%s (%s)".format(date, listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[dow - 1])
    } else {
        "%s（%s）".format(date, listOf("月", "火", "水", "木", "金", "土", "日")[dow - 1])
    }
}

/**
 * The same date split for a narrow column: `08-18` and `Tue` / `08-18` and `火`.
 *
 * For the narrow layouts only, and only under a [MonthDivider] — the year has not been dropped, it
 * has moved to the rule at the top of the month, where it is written once instead of on every line.
 * A folded panel is 413 dp wide and the five columns of the night table do not fit with `2026-` on
 * the front of each one; they wrapped to two lines instead, which cost far more than the year was
 * worth. (白い熊, 2026-08-18.)
 *
 * Returned as two strings rather than one, because the column stacks them: the weekday is the
 * shorter line either way, so putting it under the date costs no width and saves the brackets.
 */
fun nightDateParts(key: Long, lang: BandLanguage): Pair<String, String> {
    val date = runCatching {
        java.time.LocalDate.of(
            (key / 10_000L).toInt(),
            ((key / 100L) % 100L).toInt(),
            (key % 100L).toInt(),
        )
    }.getOrNull() ?: return nightDateLabel(key) to ""
    val dow = date.dayOfWeek.value // 1 = Monday
    val names = if (lang == BandLanguage.EN) {
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    } else {
        listOf("月", "火", "水", "木", "金", "土", "日")
    }
    return "%02d-%02d".format(date.monthValue, date.dayOfMonth) to names[dow - 1]
}

/**
 * The word for a step, on the scale that runs **1 = best … 5 = worst** (白い熊, 2026-08-12).
 *
 * The names are held by meaning rather than by number precisely so a flip like that one is a change
 * to this table and to nothing else — `feltScale1` meaning "Wrecked" was a trap waiting for exactly
 * this day. Ratings already on file are re-numbered once by `RecoveryLog`, so a stored 2 that meant
 * "Below par" is a 4 afterwards and still means it.
 */
fun feltLabel(n: Int): Loc = when (n) {
    1 -> BandText.feltGreat
    2 -> BandText.feltGood
    4 -> BandText.feltBelowPar
    5 -> BandText.feltWrecked
    else -> BandText.feltNormal
}

/** Each marker in its own unit, rounded to the precision it actually has. */
private fun format(marker: RecoveryMarker, v: Double, lang: BandLanguage, unit: Boolean = true): String =
    when (marker) {
        RecoveryMarker.NOCTURNAL_HR -> if (unit) "${v.roundToInt()} bpm" else "${v.roundToInt()}"
        RecoveryMarker.SLEEP -> {
            val h = (abs(v) / 60).toInt()
            val m = (abs(v) % 60).roundToInt()
            if (lang == BandLanguage.EN) "${h}h ${m.toString().padStart(2, '0')}m" else "${h}時間${m}分"
        }
        RecoveryMarker.TEMPERATURE -> if (unit) String.format("%.1f °C", v) else String.format("%.1f", v)
        RecoveryMarker.FELT -> if (unit) feltLabel(v.roundToInt())[lang] else String.format("%.1f", v)
        // Deep is a duration like sleep, the restorative share is a percentage, and RMSSD is
        // milliseconds. Each in the unit it is measured in, for the same reason the others are.
        RecoveryMarker.DEEP -> {
            val h = (abs(v) / 60).toInt()
            val m = (abs(v) % 60).roundToInt()
            if (lang == BandLanguage.EN) "${h}h ${m.toString().padStart(2, '0')}m" else "${h}時間${m}分"
        }
        RecoveryMarker.DEEP_REM -> "${(v * 100).roundToInt()}%"
        RecoveryMarker.HRV -> if (unit) "${v.roundToInt()} ms" else "${v.roundToInt()}"
    }

/** Same measured-not-guessed column trick as the health index — see `labelColumnWidth` there. */
@Composable
private fun markerLabelWidth(labels: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    return remember(labels, style, density, measurer) {
        val widest = labels.maxOfOrNull { measurer.measure(it, style, softWrap = false).size.width } ?: 0
        with(density) { widest.toDp() }
    }
}

/**
 * The full working, on the detail screen — the same principle as [HealthIndexDetail].
 *
 * A card that says "two markers are outside your usual range" earns the right to say it only if the
 * reader can find out what the markers are, where the thresholds came from and what the whole thing
 * cannot see. All three are printed here, including the parts that are unflattering.
 */
@Composable
fun RecoveryDetail(recovery: RecoveryResult?, load: RecoveryBuild.LoadReading?, sri: Double? = null) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    Column(
        Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InfoHeading(BandText.infoWhat[lang])
        InfoBody(
            if (lang == BandLanguage.EN) {
                "Three markers from last night, each against your own normal rather than anyone " +
                    "else's: nocturnal heart rate, how long you slept, and how you said you felt. " +
                    "The headline counts how many are outside your usual range \u2014 nothing more."
            } else {
                "昨夜の三つの指標を、他人ではなく自分自身の平常と比べている。夜間心拍、睡眠時間、" +
                    "そして自己申告の体感。見出しはそのうち何個が平常の範囲外かを数えているだけ。"
            },
        )

        InfoHeading(if (lang == BandLanguage.EN) "Why it counts instead of scoring" else "なぜ点数ではなく個数か")
        InfoBody(
            if (lang == BandLanguage.EN) {
                "No commercial readiness score has ever been validated against an outcome \u2014 not " +
                    "Garmin Body Battery, not Polar Nightly Recharge, not Fitbit Daily Readiness, " +
                    "and the one positive study of WHOOP Recovery was written by six WHOOP " +
                    "employees. The only composite shape with published support is a count: \u22652 " +
                    "of 3 criteria elevated reached 92 % positive predictive value for detecting " +
                    "overreaching, against \u226585 % for each criterion alone. A weighted score " +
                    "would need coefficients no study has ever produced, and would hide which " +
                    "marker fired."
            } else {
                "市販の「回復スコア」で、結果に対して検証されたものは一つもない。Garmin の Body " +
                    "Battery も、Polar の Nightly Recharge も、Fitbit の Daily Readiness も。" +
                    "WHOOP Recovery の唯一の肯定的な研究は、著者六人全員が WHOOP 社員だった。" +
                    "根拠のある合成の形は「数える」ことだけ — 三つのうち二つ以上が上振れ、で" +
                    "オーバーリーチング検出の陽性的中率 92 %、単独指標はそれぞれ 85 % 以上。" +
                    "重み付けの点数にするには、どの研究も出したことのない係数が要るうえ、" +
                    "どの指標が鳴ったのかが見えなくなる。"
            },
        )

        InfoHeading(if (lang == BandLanguage.EN) "The thresholds" else "しきい値")
        InfoBody(
            if (lang == BandLanguage.EN) {
                "A marker fires only when the change is BOTH unusual for you (beyond 1.5 robust " +
                    "standard deviations of your last 28 nights) AND big enough that the " +
                    "literature calls it meaningful \u2014 5 bpm for heart rate, 30 minutes for " +
                    "sleep, one step on the 1\u20135 scale. Either test alone misbehaves: a " +
                    "z-score goes off in a quiet fortnight when the spread collapses, and a fixed " +
                    "threshold ignores that one person's 3 bpm is another's 8."
            } else {
                "指標が鳴るのは、変化が「自分にとって異常」（直近28夜のロバスト標準偏差の1.5倍超）" +
                    "かつ「文献が意味ありとする大きさ」（心拍 5 bpm、睡眠 30 分、体感 1 段階）の" +
                    "両方を満たしたときだけ。片方だけでは壊れる。z 値だけでは静かな二週間で" +
                    "ばらつきが潰れて誤報が出るし、固定値だけでは人による幅の違いを無視する。"
            },
        )

        InfoHeading(BandText.infoCaveat[lang])
        InfoBody(
            if (lang == BandLanguage.EN) {
                "There is no HRV here \u2014 the band's field of that name is a firmware state " +
                    "index, not heart-rate variability \u2014 and HRV is what most of this " +
                    "literature leans on. Deep and REM percentages are deliberately not scored: " +
                    "consumer staging agrees with sleep-lab scoring at \u03ba 0.20\u20130.53, and " +
                    "no study links stage proportions to next-day readiness. Blood oxygen is not " +
                    "scored either: its measurement error is about twice the whole day\u2013night " +
                    "swing. Skin temperature is shown but never counted, and only ever upward, " +
                    "because a wrist sensor at night is measuring your bedroom nearly as much as " +
                    "you. And alcohol, a hard late session and an illness all raise nocturnal " +
                    "heart rate by the same 3\u20139 bpm \u2014 so this can tell you something " +
                    "cost you, and how much, but not what."
            } else {
                "ここに HRV はない。バンドの同名フィールドはファームウェアの状態指数であって" +
                    "心拍変動ではなく、そしてこの分野の文献の多くは HRV に依っている。" +
                    "深睡眠・REM の割合は意図的に採点していない。市販機器のステージ判定は" +
                    "睡眠検査と \u03ba 0.20〜0.53 でしか一致せず、割合が翌日の調子を予測すると" +
                    "いう研究もない。血中酸素も採点しない。測定誤差が昼夜の変動幅の約二倍ある。" +
                    "皮膚温は表示するが数には入れず、しかも上振れ側だけ。夜間の手首センサーは" +
                    "自分と同じくらい寝室を測っているから。さらに、飲酒・遅い時間の運動・体調不良は" +
                    "どれも夜間心拍を同じ 3〜9 bpm 上げる。だから「何かが響いた、どれくらい」までは" +
                    "言えても、「何が」までは言えない。"
            },
        )

        recovery?.takeIf { it.hasHeadline }?.let { r ->
            InfoHeading(if (lang == BandLanguage.EN) "Last night" else "昨夜")
            r.markers.forEach { m ->
                Text(
                    markerLabel(m.marker)[lang] + "  " +
                        (m.value?.let { format(m.marker, it, lang) } ?: "\u2014") +
                        (m.z?.let { String.format("   z = %+.2f", it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = style.axisText,
                )
            }
        }
        load?.let {
            InfoHeading(BandText.loadTitle[lang])
            InfoBody(BandText.loadFloorNote[lang])
        }
    }
}
