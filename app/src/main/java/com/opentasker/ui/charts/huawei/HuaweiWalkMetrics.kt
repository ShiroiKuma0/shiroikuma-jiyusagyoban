package com.opentasker.ui.charts.huawei

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.opentasker.core.huawei.HuaweiWorkoutStore
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import java.util.Locale

/**
 * The rest of what the Watch D3 shows on its own screens after a walk, page by page: pace, the
 * heart-rate zones, altitude, cadence and steps, energy, training load and recovery time.
 *
 * Every figure is either the watch's own (its summary — zones, loads, recovery time, altitude
 * extremes, descent, active energy) or read straight off its five-second stream, and the two were
 * checked against each other on 2026-10-18's walk (HuaweiD3WalkTest). The charts draw each sample as
 * recorded — nothing averaged, nothing smoothed — with the watch's own time marks: start, middle,
 * end. A Band 11 Pro walk has no altitude stream and no zones, and simply shows fewer cards.
 */
object WalkMetricsText {
    val pace = Loc("Pace", "ペース")
    val avg = Loc("avg", "平均")
    val fastest = Loc("fastest", "最速")
    val zones = Loc("Heart-rate zones", "心拍ゾーン")
    val zoneNames = listOf(
        Loc("Warm-up", "ウォームアップ"),
        Loc("Fat-burning", "脂肪燃焼"),
        Loc("Aerobic", "有酸素"),
        Loc("Anaerobic", "無酸素"),
        Loc("Extreme", "最大"),
    )
    val zonesNote = Loc(
        "The watch's own minutes in each zone, as it counted them.",
        "各ゾーンにいた時間。時計自身が数えた値。",
    )
    val altitude = Loc("Altitude", "高度")
    val maxAlt = Loc("max", "最高")
    val minAlt = Loc("min", "最低")
    val gain = Loc("gain", "上り")
    val descent = Loc("descent", "下り")
    val cadence = Loc("Cadence", "ピッチ")
    val stepsMin = Loc("steps/min", "歩/分")
    val max = Loc("max", "最大")
    val steps = Loc("steps", "歩")
    val stride = Loc("avg stride", "平均歩幅")
    val energy = Loc("Energy", "消費エネルギー")
    val total = Loc("total", "合計")
    val active = Loc("active", "活動")
    val training = Loc("Training", "トレーニング")
    val load = Loc("Training load", "トレーニング負荷")
    val aerobic = Loc("aerobic", "有酸素")
    val anaerobic = Loc("anaerobic", "無酸素")
    val recoveryTime = Loc("Recovery time", "回復時間")
    val recoveryNote = Loc(
        "The watch's estimate of the time needed to recover from this exercise.",
        "この運動から回復するのに要する時間の、時計による見積もり。",
    )
}

/** The cards, each only when the walk carries what it shows. */
@Composable
fun WalkMetricsCards(walk: HuaweiWorkoutStore.Workout, effort: HuaweiWorkoutStore.Effort) {
    val lang = LocalBandLanguage.current
    val t = WalkMetricsText
    val step = effort.intervalSeconds.coerceAtLeast(1)
    val duration = walk.durationSeconds ?: (effort.heart.size * step).toLong()
    val summary = effort.summary

    // --- pace ---
    val speeds = effort.speedDmS
    val distanceKm = walk.distanceMetres?.takeIf { it > 0 }?.div(1000.0)
    if (speeds.any { it > 0 } && distanceKm != null) {
        SectionCard(accent = ChartPalette.STEPS) {
            SectionTitle(t.pace[lang], ChartPalette.STEPS)
            val avgPace = duration / distanceKm
            val fastest = speeds.max().let { 10_000.0 / it }
            BodyText("${pace(avgPace)} /km ${t.avg[lang]}  ·  ${t.fastest[lang]} ${pace(fastest)}")
            // Seconds per km, fast at the TOP as the watch draws it; a sample at a standstill has
            // no pace and breaks the line rather than shooting off the chart.
            val series = speeds.map { if (it > 0) 10_000.0 / it else null }
            SeriesChart(series, ChartPalette.STEPS, duration, invert = true, label = { pace(it) })
        }
    }

    // --- heart-rate zones ---
    summary?.zoneSeconds?.let { zones ->
        SectionCard(accent = ChartPalette.HEART_RATE) {
            SectionTitle(t.zones[lang], ChartPalette.HEART_RATE)
            val most = zones.max().coerceAtLeast(1)
            // Highest zone first, as the watch lists them.
            for (i in zones.indices.reversed()) {
                ZoneRow(t.zoneNames[i][lang], zones[i], most)
            }
            NoteText(t.zonesNote[lang])
        }
    }

    // --- altitude ---
    val alt = effort.altitudeDm
    if (alt.isNotEmpty()) {
        SectionCard(accent = ChartPalette.SPO2) {
            SectionTitle(t.altitude[lang], ChartPalette.SPO2)
            val hi = summary?.maxAltitudeDm ?: alt.max()
            val lo = summary?.minAltitudeDm ?: alt.min()
            BodyText(
                "${t.maxAlt[lang]} ${metres(hi)}  ·  ${t.minAlt[lang]} ${metres(lo)}  ·  " +
                    "${t.avg[lang]} ${metres(alt.average())}",
            )
            listOfNotNull(
                walk.elevationGainDm?.let { "${t.gain[lang]} ${metres(it)}" },
                summary?.descentDm?.let { "${t.descent[lang]} ${metres(it)}" },
            ).takeIf { it.isNotEmpty() }?.let { BodyText(it.joinToString("  ·  ")) }
            SeriesChart(alt.map { it / 10.0 }, ChartPalette.SPO2, duration, label = { "%.0f m".format(Locale.US, it) })
        }
    }

    // --- cadence and steps ---
    val perInterval = effort.stepsPerInterval
    if (perInterval.isNotEmpty()) {
        SectionCard(accent = ChartPalette.TEMPERATURE) {
            SectionTitle(t.cadence[lang], ChartPalette.TEMPERATURE)
            val perMin = perInterval.map { it * 60.0 / step }
            BodyText(
                "%.0f %s %s  ·  %s %.0f".format(
                    Locale.US, perMin.average(), t.stepsMin[lang], t.avg[lang], t.max[lang], perMin.max(),
                ),
            )
            walk.steps?.let { steps ->
                val stride = walk.distanceMetres?.takeIf { steps > 0 }?.let { it * 100.0 / steps }
                BodyText(
                    "$steps ${t.steps[lang]}" +
                        (stride?.let { "  ·  ${t.stride[lang]} %.0f cm".format(Locale.US, it) } ?: ""),
                )
            }
            SeriesChart(perMin.map { it.takeIf { v -> v > 0 } }, ChartPalette.TEMPERATURE, duration,
                label = { "%.0f".format(Locale.US, it) })
        }
    }

    // --- energy and training ---
    val active = summary?.activeKcal
    val load = summary?.trainingLoad
    val recovery = summary?.recoveryMinutes
    if (walk.calories != null || load != null || recovery != null) {
        SectionCard(accent = ChartPalette.BAND_INDEX) {
            SectionTitle(t.training[lang], ChartPalette.BAND_INDEX)
            walk.calories?.let { kcal ->
                BodyText(
                    "${t.energy[lang]}: $kcal kcal ${t.total[lang]}" +
                        (active?.let { "  ·  $it kcal ${t.active[lang]}" } ?: ""),
                )
            }
            load?.let {
                BodyText(
                    "${t.load[lang]}: $it" +
                        listOfNotNull(
                            summary.aerobicLoad?.let { a -> "${t.aerobic[lang]} $a" },
                            summary.anaerobicLoad?.let { a -> "${t.anaerobic[lang]} $a" },
                        ).takeIf { l -> l.isNotEmpty() }?.joinToString(", ", "  (", ")").orEmpty(),
                )
            }
            recovery?.let {
                BodyText("${t.recoveryTime[lang]}: ${hoursMinutes(it)}")
                NoteText(t.recoveryNote[lang])
            }
        }
    }
}

@Composable
private fun ZoneRow(name: String, seconds: Int, most: Int) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            // Whole minutes, rounded down, as the watch prints them (1770 s → "29 min").
            Text("${seconds / 60} min", style = MaterialTheme.typography.bodyMedium)
        }
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(Color(0x22FFFFFF)),
        ) {
            if (seconds > 0) {
                Box(
                    Modifier.fillMaxWidth(seconds.toFloat() / most).height(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ChartPalette.HEART_RATE),
                )
            }
        }
    }
}

/**
 * One five-second series over the whole walk, every sample drawn, gaps (null) breaking the line.
 * Its low and high are labelled on the right, the start / middle / end time under it.
 */
@Composable
private fun SeriesChart(
    values: List<Double?>,
    color: Color,
    durationSeconds: Long,
    invert: Boolean = false,
    label: (Double) -> String,
) {
    val real = values.filterNotNull()
    if (real.size < 2) return
    val lo = real.min()
    val hi = real.max()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Canvas(Modifier.weight(1f).height(96.dp)) {
                val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
                val dx = if (values.size > 1) size.width / (values.size - 1) else size.width
                fun y(v: Double): Float {
                    val f = ((v - lo) / span).toFloat()
                    return if (invert) f * size.height else size.height - f * size.height
                }
                // Three faint guides: top, middle, bottom.
                for (k in 0..2) {
                    val gy = size.height * k / 2f
                    drawLine(Color(0x1AFFFFFF), Offset(0f, gy), Offset(size.width, gy), 1f)
                }
                val path = Path()
                var drawing = false
                values.forEachIndexed { i, v ->
                    if (v == null) { drawing = false; return@forEachIndexed }
                    val x = i * dx
                    if (drawing) path.lineTo(x, y(v)) else path.moveTo(x, y(v))
                    drawing = true
                }
                drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
            }
            Column(Modifier.height(96.dp), verticalArrangement = Arrangement.SpaceBetween) {
                // The top label is the value drawn at the top — the fastest pace when inverted.
                Text(label(if (invert) lo else hi), style = MaterialTheme.typography.labelSmall)
                Text(label(if (invert) hi else lo), style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0L, durationSeconds / 2, durationSeconds).forEach {
                Text(hms(it), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Seconds per km as the watch writes pace: 1111 → 18′31″. */
private fun pace(secondsPerKm: Double): String {
    val s = secondsPerKm.toInt().coerceAtLeast(0)
    return "%d′%02d″".format(Locale.US, s / 60, s % 60)
}

private fun metres(dm: Int) = "%.1f m".format(Locale.US, dm / 10.0)
private fun metres(dm: Double) = "%.1f m".format(Locale.US, dm / 10.0)
private fun hms(s: Long) = "%02d:%02d:%02d".format(Locale.US, s / 3600, s % 3600 / 60, s % 60)
private fun hoursMinutes(min: Int) = if (min % 60 == 0) "${min / 60} h" else "${min / 60} h ${min % 60} min"
