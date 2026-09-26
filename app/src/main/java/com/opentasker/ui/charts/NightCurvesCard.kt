package com.opentasker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * One chart per night, stacked — the past nights' curves as themselves.
 *
 * ## Why this, when the card above already overlays them
 *
 * [RecentCurvesCard] lays five nights over one another normalised to their own bed time, which
 * answers *"is last night's descent like the others?"* and answers it well. It cannot answer *"what
 * did Tuesday look like?"*, because five lines in one frame are five lines in one frame: the moment
 * one is faint enough not to be a thicket it is too faint to read on its own.
 *
 * 白い熊 asked for the other thing, on 2026-09-26: *"it should have a click-through history
 * featuring the graphs for past days, so we can quickly visually compare."* Small multiples are the
 * form that request names — each night drawn as its own picture, all of them on **one shared
 * scale**, so a comparison is made by moving the eye down the column rather than by unpicking
 * overlapping lines.
 *
 * ## What makes them comparable
 *
 * **One y axis for every night**, in absolute bpm rather than relative to bed time. The overlay
 * above is the relative view and already does that job; this one keeps the real numbers, so a night
 * that ran high all through and a night that ran low are visibly different rather than normalised
 * into the same picture. The axis is the widest span any night in the set needs, rounded out to
 * whole [GUIDE_BPM] steps, so every chart shares gridlines and the eye can carry a level from one
 * to the next.
 *
 * **Each night scored the same way the strip scores last night** — [MarkerHistory.descentStep]
 * against the same usual drop the strip compares against, shown as the same left bar. One arithmetic
 * and one colour vocabulary across both screens; a red night here is red on the strip too.
 *
 * **Newest first**, matching the register's own order, because the question being asked of this page
 * is almost always "and what about the nights before THIS one".
 */
@Composable
fun NightCurvesCard(
    /** `(nightEndMs, curve)`, oldest first — the same pairs [RecentCurvesCard] takes. */
    curves: List<Pair<Long, List<Double>>>,
    /**
     * 白い熊's usual nightly drop, in bpm, or null when there is not enough history for one.
     *
     * Passed in rather than derived here on purpose: the strip's bar, its sentence and these tiles
     * must all be comparing against the SAME "usually", and a second median computed from a
     * different set of nights would quietly disagree with the first.
     */
    usualDrop: Double?,
    zone: java.time.ZoneId,
) {
    val lang = LocalBandLanguage.current
    val usable = curves.filter { it.second.size >= 2 }
    if (usable.isEmpty()) return

    // ONE axis for all of them — see the class note. Rounded outward to whole gridline steps so the
    // bounds are round numbers and every tile's gridlines land in the same places.
    val lo = usable.minOf { it.second.min() }
    val hi = usable.maxOf { it.second.max() }
    val span = maxOf(
        MIN_SPAN_BPM,
        kotlin.math.ceil((hi - lo) * HEADROOM / GUIDE_BPM) * GUIDE_BPM,
    )
    val mid = (hi + lo) / 2
    var yLo = kotlin.math.floor((mid - span / 2) / GUIDE_BPM) * GUIDE_BPM
    if (lo < yLo) yLo = kotlin.math.floor(lo / GUIDE_BPM) * GUIDE_BPM
    if (hi > yLo + span) yLo = kotlin.math.ceil(hi / GUIDE_BPM) * GUIDE_BPM - span

    SectionCard(accent = ChartPalette.STEPS) {
        SubHeading(BandText.nightCurvesTitle[lang])
        NoteText(BandText.nightCurvesNote[lang].format(usable.size))

        for ((ms, curve) in usable.asReversed()) {
            val drop = curve.first() - curve.min()
            // No usual yet means no score — and then a neutral bar, never a reassuring green one.
            val step = usualDrop?.let { MarkerHistory.descentStep(it - drop) }
            val tint = step?.let { ChartPalette.scale(it) } ?: sectionNote
            val date = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                // The same 6 dp bar the deviation strip's rows carry, in the same colours.
                Box(
                    Modifier
                        .width(6.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint)
                        .padding(vertical = 52.dp),
                ) { Text(" ", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$date",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            fontWeight = FontWeight.Bold,
                            color = sectionInk,
                        )
                        // The number the colour is FROM, on every tile: hue is never the only
                        // channel, and a drop is the quantity the whole page is about.
                        Text(
                            BandText.nightCurvesDrop[lang].format(
                                curve.first().roundToInt(), curve.min().roundToInt(),
                                drop.roundToInt(),
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            fontWeight = FontWeight.Bold,
                            color = if (step != null && step >= 4) tintedInk(tint) else sectionNote,
                        )
                    }
                    BoundedLineChart(
                        values = curve,
                        yLo = yLo,
                        yHi = yLo + span,
                        yStep = GUIDE_BPM,
                        color = ChartPalette.STEPS,
                        // Bed to waking, like the strip's own chart. The clock times differ per
                        // night and printing them would invite reading width as length.
                        xLabels = Triple(
                            BandText.nightCurvesBed[lang], "", BandText.nightCurvesWoke[lang],
                        ),
                        marked = setOf(curve.indexOf(curve.min())),
                        height = TILE_HEIGHT,
                    )
                }
            }
        }
    }
}

/** Tall enough for a descent to have a shape, short enough that several fit on one screen. */
private val TILE_HEIGHT = 104.dp

/** The floor on each tile's span, in bpm — the same one the strip's single chart uses, and why. */
private const val MIN_SPAN_BPM = 20.0

/** Padding applied before the floor, so an extreme night is not drawn touching the frame. */
private const val HEADROOM = 1.2

/** Spacing of the labelled horizontal gridlines, in bpm. */
private const val GUIDE_BPM = 5.0
