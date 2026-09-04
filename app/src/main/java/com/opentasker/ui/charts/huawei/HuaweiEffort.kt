package com.opentasker.ui.charts.huawei

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.opentasker.core.huawei.HuaweiWorkoutStore
import com.opentasker.ui.charts.BandLanguage
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import java.util.Locale

/**
 * What a workout cost — the band's own heart rate, and how fast it let go afterwards.
 *
 * ## Why this is the band's stream and not the synced samples
 *
 * The walk detail already showed a mean heart rate, taken from the per-minute history the ordinary
 * sync stores and averaged over the walk's window. That figure is ours, not the band's: it is a
 * twelfth of the resolution, it is computed by us from a different instrument, and it knows nothing
 * about a workout having happened. This is the band's own five-second stream, recorded BECAUSE a
 * workout was running, and it is kept beside the old figure rather than replacing it — two
 * measurements of one walk are how a decoder that reads a format slightly wrongly gets caught, and
 * that has already happened once here.
 *
 * ## The recovery drop
 *
 * The band takes twenty-five more readings after the work stops and had been throwing them away in
 * this app since the first walk (tag `0x66`, never decoded). It is the one number on this card that
 * is about fitness rather than effort: everything else says how hard the session was, and this says
 * how quickly the heart let go of it. The elapsed time is deliberately not stated — the band never
 * says how far apart those readings are, and a "two-minute recovery" that turns out to be ninety
 * seconds is worse than no label at all.
 */
@Composable
fun EffortCard(walk: HuaweiWorkoutStore.Workout, effort: HuaweiWorkoutStore.Effort?) {
    if (effort == null) return
    val lang = LocalBandLanguage.current
    SectionCard(accent = ChartPalette.HEART_RATE) {
        SectionTitle(HuaweiText.effortTitle[lang], ChartPalette.HEART_RATE)

        effort.meanHeart?.let { mean ->
            BodyText(
                "${HuaweiText.effortMean[lang]} %.0f bpm".format(Locale.US, mean) +
                    (effort.minHeart?.let { lo -> effort.maxHeart?.let { hi -> "  ($lo–$hi)" } } ?: ""),
            )
            NoteText(
                "${HuaweiText.effortFromBand[lang]} · ${effort.samples} × ${effort.intervalSeconds}s",
            )
        }

        if (effort.heart.size >= 4) {
            HeartTrace(effort, Modifier.fillMaxWidth().height(64.dp))
        }

        effort.recoveryDrop?.let { drop ->
            val curve = effort.recovery.filter { it > 0 }
            BodyText("${HuaweiText.effortRecovery[lang]} −$drop bpm  (${curve.first()} → ${curve.last()})")
            NoteText(HuaweiText.effortRecoveryNote[lang])
        }

        if (effort.splits.isNotEmpty()) {
            NoteText(HuaweiText.effortSplits[lang])
            // Kilometres only. The band keeps the same table in miles and 白い熊 does not think in
            // them; both are on disk, so showing the other is a line of code and not a re-fetch.
            for (s in effort.splits.filter { !it.mile }.sortedBy { it.index }) {
                // The last row of a walk is the unfinished kilometre, and the band says how far it
                // got. Labelling it by its index — "3 km" for 270 metres — reads as a third full
                // kilometre that took fourteen minutes, so the partial row is labelled by its
                // distance instead.
                val label = s.partialDecimetres
                    ?.let { "%.2f km".format(Locale.US, it / 10_000.0) }
                    ?: "${s.index} km"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BodyText(label)
                    BodyText(mmss(s.seconds))
                }
            }
        }
    }
}

/**
 * The heart rate as a line, over the whole workout.
 *
 * Drawn rather than charted: this is a shape, not a reading — the numbers are stated above it — and
 * the full chart machinery would bring axes, a crosshair and a viewport to a card that wants to say
 * "steady" or "spiky" at a glance. Gaps (a recorded zero, which is "no reading" and not a pulse)
 * break the line instead of dropping it to the floor, which is the whole reason this is not a
 * single `Path` over every sample.
 */
@Composable
fun HeartTrace(effort: HuaweiWorkoutStore.Effort, modifier: Modifier = Modifier) {
    val beats = effort.heart
    val lo = effort.minHeart ?: return
    val hi = effort.maxHeart ?: return
    val span = (hi - lo).coerceAtLeast(1).toFloat()
    Canvas(modifier) {
        val dx = if (beats.size > 1) size.width / (beats.size - 1) else size.width
        fun y(v: Int) = size.height - (v - lo) / span * size.height
        val path = Path()
        var drawing = false
        beats.forEachIndexed { i, v ->
            if (v <= 0) {
                drawing = false
                return@forEachIndexed
            }
            val at = Offset(i * dx, y(v))
            if (drawing) path.lineTo(at.x, at.y) else path.moveTo(at.x, at.y)
            drawing = true
        }
        drawPath(path, ChartPalette.HEART_RATE, style = Stroke(width = 2.dp.toPx()))
    }
}

/** `mm:ss`, for a split. Never `hh:mm` — a kilometre that takes an hour is still minutes here. */
private fun mmss(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * The one-line summary a grid cell can carry: energy, and the heart rate range.
 *
 * A lifting session has no distance and no steps, so [walkStats] — which is two thirds distance —
 * says almost nothing about one. This is what replaces it there.
 */
internal fun effortStats(
    walk: HuaweiWorkoutStore.Workout,
    effort: HuaweiWorkoutStore.Effort?,
    lang: BandLanguage,
): String {
    val mins = walk.durationSeconds?.let { "${HuaweiText.walksActive[lang]} ${hhmm(it)}" }
    val kcal = walk.calories?.let { "$it ${HuaweiText.walksCalories[lang]}" }
    val hr = effort?.meanHeart?.let { "%.0f bpm".format(Locale.US, it) }
    return listOfNotNull(mins, kcal, hr).joinToString(" · ")
}
