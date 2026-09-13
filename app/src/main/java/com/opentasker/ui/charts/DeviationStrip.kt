package com.opentasker.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 「平常との差」 — last night beside 白い熊's own normal, one line per quantity.
 *
 * ## Why this exists, given the 回復 card already grades a night
 *
 * The 回復 card answers "did anything cross a threshold". On 2026-09-11 and 2026-09-12 白い熊 felt
 * ill, and the answer for the night between was *no*: the nocturnal heart rate came in at +0.8 bpm,
 * every counted marker sat inside its usual band, and both published conjunctions were unable to
 * fire — Radin's because it requires sleep BELOW baseline and this night ran 43 % above it.
 *
 * The movement was there and it was large: 636 minutes asleep, z = +4.09 against the seven nights
 * before it, the longest in the record. Nothing crossed a line, so nothing was said.
 *
 * This strip says it anyway, in the form the file header of [Recovery] argues for at length:
 * **numbers, not verdicts.** Across five experiments (van der Bles 2020, *PNAS*, n = 5 780) an
 * explicit numeric range cost almost nothing in trust where a verbal hedge cost a lot. So every row
 * prints its value, its baseline and the difference, and the colour is a re-expression of banding
 * that is already computed rather than a new judgement.
 *
 * ## Colour is never the only channel
 *
 * 白い熊 is red-green colour-blind, which rules out the obvious design — green for fine, red for
 * off. Every row therefore carries the reading three times over: the **number**, an explicit
 * **signed delta**, and the tint. The tint is [ChartPalette.scale], the same 1–5 ladder the morning
 * rating uses, so a colour on this strip means what it means everywhere else on the screen. Reading
 * the strip in greyscale loses nothing.
 *
 * ## What is NOT here
 *
 * No composite, no score, no arrow pointing at a conclusion. A strip that ranked the rows or summed
 * them would be the readiness percentage this whole feature declined to build — no commercial one
 * has ever been validated, and the only composite shape with published support is a counting rule,
 * which [Recovery] already implements and this does not duplicate.
 */
@Composable
fun DeviationStrip(
    recovery: RecoveryResult,
    /** The rows to draw, in order. Absent markers are skipped rather than drawn as dashes. */
    markers: List<RecoveryMarker> = DEFAULT_ROWS,
    /**
     * Last night's heart rate in twelfths — [RecoverySource.hrCurve]. Empty draws no sparkline.
     *
     * A picture rather than another row because the shape is the one thing on this card that a
     * number genuinely cannot carry: [RecoveryMarker.HR_SWING] says how far the curve moved and
     * says nothing whatever about when, and "when" is most of what a night's heart rate looks like.
     */
    hrCurve: List<Double> = emptyList(),
    /**
     * Last night's descent and 白い熊's usual one, for the commentary under the chart.
     *
     * A swing alone cannot answer "from where": 14 bpm starting at 78 and 14 starting at 64 are
     * different nights. 白い熊 asked for the comparison explicitly (2026-09-13).
     */
    descent: MarkerHistory.DescentComparison? = null,
    /** The three deepest-dropping nights on record, to aim that comparison at. */
    bestDescent: MarkerHistory.Descent? = null,
    /** Opens a row's own history page. Null leaves the rows inert. */
    onOpenMarker: ((RecoveryMarker) -> Unit)? = null,
) {
    val lang = LocalBandLanguage.current
    val rows = markers.mapNotNull { m ->
        recovery.markers.firstOrNull { it.marker == m }?.takeIf { it.value != null }
    }
    if (rows.isEmpty()) return

    // Built inline rather than with SectionCard, for the same reason the morning card is: this is
    // one of the two things on the page 白い熊 came to look at, and SectionCard's hairline border is
    // designed to make a group recede. Thick border, tinted ground, heading at the morning card's
    // size — the blue that means "a measurement" throughout this screen rather than the yellow that
    // means "answer me", so the two read as a pair without competing.
    val accent = ChartPalette.STEPS
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(3.dp, accent, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            BandText.deviationTitle[lang],
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp),
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline,
            color = accent,
        )

        if (recovery.confidence == RecoveryConfidence.COLLECTING) {
            NoteText(BandText.deviationNoBaseline[lang])
        } else {
            NoteText(BandText.deviationSubtitle[lang].format(recovery.nightsOfHistory))
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in rows) DeviationRow(r, onOpenMarker)
        }
        if (onOpenMarker != null) NoteText(BandText.deviationTapHint[lang])

        if (hrCurve.size >= 2) {
            HrCurveChart(
                hrCurve, recovery.nightStartMs, recovery.nightEndMs, descent, bestDescent,
                // The chart opens the swing's page: that IS this curve's history — the same
                // quantity for every night, with the recent curves themselves stacked under it.
                onOpenMarker?.let { { it(RecoveryMarker.HR_SWING) } },
            )
        }

        // A statement of fact, in the quiet weight, with its own caveat attached. It is deliberately
        // NOT a bordered box like the conjunction below: that one is a conjunction of two limbs on a
        // night that preceded something, and this is a pattern that arrived alongside it.
        if (recovery.hrvRunNights >= Recovery.HRV_RUN_NIGHTS) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BodyText(BandText.hrvRun[lang].format(recovery.hrvRunNights), bold = true)
                NoteText(BandText.hrvRunCaveat[lang])
            }
        }

        // The flag, under the numbers it is drawn from rather than above them — it is a reading OF
        // the strip, and putting a conclusion first would invite taking it on trust.
        if (recovery.sicknessBehaviour) SicknessNote(recovery)
    }
}

/**
 * The rows, in the order they earn attention.
 *
 * Sleep and the going-to-bed heart rate lead because they are the pair that moved in the episode
 * this strip was built for, and the pair [RecoveryResult.sicknessBehaviour] reads. The counted
 * markers follow. Breathing is last because it is the newest and the least tested on this wrist.
 */
val DEFAULT_ROWS = listOf(
    RecoveryMarker.SLEEP,
    RecoveryMarker.BEDTIME_HR,
    RecoveryMarker.NOCTURNAL_HR,
    RecoveryMarker.DEEP,
    RecoveryMarker.HRV,
    RecoveryMarker.HR_SWING,
    RecoveryMarker.RESPIRATION,
    RecoveryMarker.FELT,
)

/**
 * How far this row sits from usual, on the shared 1–5 ladder — **never which way**.
 *
 * [MarkerReading.scaleStep] grades by valence: more sleep is better, a lower heart rate is better,
 * so it returns 1 or 2 for those. That is right for the night table and wrong here, and the first
 * render showed exactly how wrong. "Time asleep 10h 36m" came out in best-step yellow directly above
 * a box explaining that the long night was the thing to notice, and "Going-to-bed heart rate 70 bpm"
 * came out in good-step green above the same sentence. A card whose colours argue with its own text
 * teaches a reader to ignore the colours.
 *
 * So this card grades by DISTANCE: inside the usual band is the middle step, outside it is step 4,
 * and twice the band's half-width is step 5. The direction is not lost — the signed delta carries it
 * on every row, and carries it in greyscale, which the colour never could.
 */
private fun unusualness(r: MarkerReading): Int? {
    val v = r.value ?: return null
    val b = r.baseline ?: return null
    if (r.band == RecoveryBand.UNKNOWN) return null
    val halfWidth = r.usualHi?.minus(b)?.takeIf { it > 0 } ?: return null
    val deviations = kotlin.math.abs((v - b) / halfWidth)
    return when {
        deviations <= 1.0 -> 3
        deviations < 2.0 -> 4
        else -> 5
    }
}

@Composable
private fun DeviationRow(r: MarkerReading, onOpen: ((RecoveryMarker) -> Unit)?) {
    val lang = LocalBandLanguage.current
    // Distance from usual, not better-or-worse — see [unusualness]. Null when there is no baseline
    // yet, and then the row is drawn in plain ink rather than given a colour it has not earned.
    val step = unusualness(r)
    val tint = step?.let { ChartPalette.scale(it) } ?: sectionNote

    Row(
        Modifier
            .fillMaxWidth()
            // Padding BEFORE clickable, so the whole row is the target rather than the text inside
            // it — the same trap the morning card's number buttons carry a note about.
            .padding(vertical = 2.dp)
            .then(if (onOpen != null) Modifier.clickable { onOpen(r.marker) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tint as a bar rather than as the text colour: a 5 is a dark red by design, and dark
        // red body text on this surface is not readable. The same trap the morning card's number
        // buttons carry a note about, avoided the same way — measured, not special-cased.
        Box(
            Modifier
                .width(6.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint)
                .padding(vertical = 10.dp),
        ) { Text(" ", style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Text(
                markerLabel(r.marker)[lang],
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                color = sectionInk,
            )
            r.baseline?.let {
                NoteText(BandText.deviationBaseline[lang].format(markerValue(r.marker, it, lang)))
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                r.value?.let { markerValue(r.marker, it, lang) } ?: "—",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                fontWeight = FontWeight.Bold,
                color = sectionInk,
            )
            // The signed difference, always with its sign. This is the channel that survives
            // greyscale, a colour-blind reader and a photograph of the screen.
            r.delta?.let { d ->
                Text(
                    markerDelta(r.marker, d, lang),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.5.sp),
                    fontWeight = FontWeight.Bold,
                    color = if (step != null && step != 3) tintedInk(tint) else sectionNote,
                )
            }
        }
        // The chevron says "this opens something" without needing a word for it, and it survives
        // both languages — the same affordance the register pill uses.
        if (onOpen != null) {
            Text(
                "  ›",
                style = MaterialTheme.typography.titleMedium,
                color = sectionNote,
            )
        }
    }
}

/**
 * Last night's heart rate, as a bounded chart with both axes.
 *
 * ## Why it is a real chart and not a sparkline
 *
 * It was a sparkline for one build and 白い熊 was right about it: *"it should not float-inside-
 * nowhere … look more like a professional graph, than just some line in space."* A bare line carries
 * a shape and withholds the two things needed to read it — what the values are and when they
 * happened — so the reader is invited to interpret a picture they cannot check.
 *
 * So: a plot rect with a left axis in bpm and a bottom axis in clock time, ticked and labelled, on a
 * recessive grid. One series, so no legend — the caption names it. Every token comes from
 * [LocalChartStyle], the same one every other chart on this screen draws with, so this reads as part
 * of 健康 rather than as a second chart style that happens to live above the first.
 *
 * ## The y scale is FIXED, and that is the whole point
 *
 * [SPARK_SPAN_BPM] beats, centred on the night's own middle — never fitted to the night. Fitting
 * would stretch the flattest night on record into a dramatic curve and draw every night alike, which
 * is the one thing this picture must not do. A night that fills the frame genuinely moved that much.
 * The axis labels make the scale checkable rather than something to take on trust.
 */
@Composable
private fun HrCurveChart(
    curve: List<Double>,
    startMs: Long?,
    endMs: Long?,
    descent: MarkerHistory.DescentComparison?,
    bestDescent: MarkerHistory.Descent?,
    onOpen: (() -> Unit)?,
) {
    val lang = LocalBandLanguage.current
    val zone = remember { java.time.ZoneId.systemDefault() }
    // Exactly [SPARK_SPAN_BPM] beats top to bottom, snapped to the gridline step so both bounds are
    // round numbers — a 20-point axis that reads 55–80 is a 25-point axis, which is what the first
    // attempt at this drew (白い熊, 2026-09-13).
    val lo = curve.min()
    val hi = curve.max()
    val span = maxOf(
        SPARK_SPAN_BPM,
        kotlin.math.ceil((hi - lo) * SPAN_HEADROOM / GUIDE_BPM) * GUIDE_BPM,
    )
    val mid = (hi + lo) / 2
    // Centre on the night, then slide the window if that would clip either end. The span itself is
    // never changed by the slide: the axis is the promised height or the data does not fit in it.
    var yLo = kotlin.math.floor((mid - span / 2) / GUIDE_BPM) * GUIDE_BPM
    if (lo < yLo) yLo = kotlin.math.floor(lo / GUIDE_BPM) * GUIDE_BPM
    if (hi > yLo + span) yLo = kotlin.math.ceil(hi / GUIDE_BPM) * GUIDE_BPM - span

    fun clockAt(fraction: Double): String {
        if (startMs == null || endMs == null || endMs <= startMs) return ""
        val t = startMs + ((endMs - startMs) * fraction).toLong()
        return java.time.Instant.ofEpochMilli(t).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .then(if (onOpen != null) Modifier.clickable { onOpen() } else Modifier),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BoundedLineChart(
            values = curve,
            // A FLOOR on the span, centred on the night's middle — not a fit. Fitting would
            // stretch the flattest night on record into a dramatic curve and draw every night
            // alike; a floor keeps them comparable while letting a real fall fill the frame.
            yLo = yLo,
            yHi = yLo + span,
            yStep = GUIDE_BPM,
            color = ChartPalette.STEPS,
            xLabels = Triple(clockAt(0.0), clockAt(0.5), clockAt(1.0)),
            marked = setOf(curve.indexOf(curve.min())),
            height = CHART_HEIGHT,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NoteText(BandText.curveCaption[lang], Modifier.weight(1f))
            if (onOpen != null) {
                Text("›", style = MaterialTheme.typography.titleMedium, color = sectionNote)
            }
        }
        HrCurveCommentary(curve, descent, bestDescent)
    }
}

/**
 * The chart's height — half again as tall as it first was (白い熊, 2026-09-13).
 *
 * A 20 bpm axis in 132 dp gives each beat under 7 dp of travel, and a night's whole descent lands
 * inside the thickness of a few gridlines. The extra height is what turns the span into a shape.
 */
private val CHART_HEIGHT = 198.dp

/**
 * The sentence under the chart — where the low fell, how far it moved, what it was doing by morning.
 *
 * Facts the picture already shows, said once in words, because a reader orienting on a card does not
 * want to squint at twelve points to find out when the low was. It interprets nothing: see
 * [HrCurveShape] for why.
 */
@Composable
private fun HrCurveCommentary(
    curve: List<Double>,
    descent: MarkerHistory.DescentComparison?,
    bestDescent: MarkerHistory.Descent?,
) {
    val lang = LocalBandLanguage.current
    val shape = HrCurveShape.describe(curve) ?: return
    val swing = shape.swingBpm.roundToInt()
    // Only the LAST number in a sentence carries the unit. "from 76 bpm to 62 bpm — a drop of
    // 14 bpm" says it three times and reads like a form; the unit is established once and the rest
    // are plainly the same quantity.
    fun n(v: Double) = "${v.roundToInt()}"
    fun bpm(v: Double) = "${v.roundToInt()} bpm"

    val first = with(HrCurveShape) {
        if (!shape.nadirIsMeaningful()) {
            // Too flat for "where the low fell" to mean anything — a 3 bpm curve's minimum is noise,
            // and naming its position would be reporting the noise.
            BandText.curveFlat[lang].format(swing)
        } else {
            val where = when (shape.nadir) {
                HrCurveShape.Nadir.EARLY -> BandText.curveNadirEarly
                HrCurveShape.Nadir.MIDDLE -> BandText.curveNadirMiddle
                HrCurveShape.Nadir.LATE -> BandText.curveNadirLate
            }[lang]
            val end = when (shape.ending) {
                HrCurveShape.Ending.RISING -> BandText.curveEndRising
                HrCurveShape.Ending.FLAT_AT_THE_BOTTOM -> BandText.curveEndLow
                HrCurveShape.Ending.STILL_FALLING -> BandText.curveEndFalling
            }[lang]
            BandText.curveShape[lang].format(where, swing, end)
        }
    }
    NoteText(first)

    // "From where", which the swing alone cannot say: a 14 bpm fall starting at 78 and one starting
    // at 64 are different nights, and 白い熊 asked for the comparison in those terms.
    descent?.let { d ->
        NoteText(
            BandText.curveDescent[lang].format(
                n(d.usual.from), n(d.usual.to), n(d.usual.drop),
                n(d.lastNight.from), n(d.lastNight.to), bpm(d.lastNight.drop),
            ),
        )
        // Only when the difference clears the heart rate's own smallest-worthwhile-change. Below
        // that, saying "one less than usual" dresses a rounding up as an observation.
        if (!d.negligible) {
            val line = if (d.shallower) BandText.curveDescentShallow else BandText.curveDescentDeeper
            NoteText(line[lang].format(bpm(kotlin.math.abs(d.dropDelta))), warn = d.shallower)
        }
    }
    bestDescent?.let { NoteText(BandText.curveBestDescent[lang].format(bpm(it.from), bpm(it.to))) }
}

/**
 * The chart's vertical span, in bpm — a FLOOR, not a fitted range and no longer a fixed one.
 *
 * ## Why 24, and why this was 40 for one build
 *
 * Two failure modes pull in opposite directions and both are real.
 *
 * Fit the scale to each night and every night looks identical: the flattest on record, a 6 bpm span,
 * is drawn as a dramatic descent, and the picture stops carrying the one thing a picture is for.
 *
 * Fix it too wide and a real fall is drawn as nothing. That is what 40 did — the largest swing 白い熊
 * has recorded is 17 bpm, so more than half the frame was permanently empty and a 16 bpm drop, which
 * is a significant night, rendered as a line lying almost flat. 白い熊, 2026-09-13: *"the current
 * scale basically shows the line flat, even though the 16 point drop is significant."*
 *
 * 20 is chosen against the measured distribution — 21 nights running 6.0 to 17.0 bpm. Every one fits
 * inside it, so all of them stay directly comparable on one scale; a 16 bpm night now spans four
 * fifths of the plot height, while the 6 bpm night occupies under a third and still reads as flat.
 * Both properties, rather than one bought with the other.
 *
 * The axis is snapped to whole [GUIDE_BPM] steps, so 20 really is 20: the first attempt centred the
 * span on the night and let the gridlines fall where they liked, which drew a 20-point axis labelled
 * 55 to 80.
 *
 * **It is a floor, not a cap.** A night wider than this expands the axis rather than being clipped —
 * comparability is worth a lot and is not worth drawing a measurement that ran off the frame.
 */
private const val SPARK_SPAN_BPM = 20.0

/** Padding factor applied before the floor, so an extreme night is not drawn touching the frame. */
private const val SPAN_HEADROOM = 1.2

/** Spacing of the labelled horizontal gridlines, in bpm. Five rules across the 20-point span. */
private const val GUIDE_BPM = 5.0

@Composable
private fun SicknessNote(recovery: RecoveryResult) {
    val lang = LocalBandLanguage.current
    val accent = ChartPalette.BAND_WARN
    val sleep = recovery.markers.firstOrNull { it.marker == RecoveryMarker.SLEEP }
    val bed = recovery.markers.firstOrNull { it.marker == RecoveryMarker.BEDTIME_HR }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(2.dp, accent, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            BandText.sicknessTitle[lang],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        BodyText(
            BandText.sicknessBody[lang].format(
                sleep?.delta?.let { markerValue(RecoveryMarker.SLEEP, kotlin.math.abs(it), lang) } ?: "—",
                bed?.delta?.let { markerValue(RecoveryMarker.BEDTIME_HR, kotlin.math.abs(it), lang) } ?: "—",
            ),
        )
        // Said every time it is drawn, never once in a help screen. This conjunction has no
        // published operating point and has never been checked against an illness on this wrist,
        // and a reader who does not know that will read it as a diagnosis.
        NoteText(BandText.sicknessUnproven[lang])
    }
}

/**
 * Ink that stays readable on this surface whatever the tint is.
 *
 * Step 5 of the scale is a deliberately dark red and step 1 a bright yellow, so neither is usable as
 * text on every background. Measured against the surface rather than special-cased by step, so a
 * future change to the palette cannot quietly reintroduce an unreadable row.
 */
@Composable
private fun tintedInk(tint: Color): Color {
    val surface = MaterialTheme.colorScheme.surface
    return if (PaletteCheck.contrast(tint.toArgb(), surface.toArgb()) >= 3.0) tint else sectionInk
}
