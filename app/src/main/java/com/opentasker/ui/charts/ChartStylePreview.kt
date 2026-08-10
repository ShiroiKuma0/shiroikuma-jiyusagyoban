package com.opentasker.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.opentasker.ui.charts.render.PlotFrame
import com.opentasker.ui.charts.render.drawBars
import com.opentasker.ui.charts.render.drawCapsules
import com.opentasker.ui.charts.render.drawPoints
import com.opentasker.ui.charts.render.drawCrosshair
import com.opentasker.ui.charts.render.drawDumbbells
import com.opentasker.ui.charts.render.drawGaps
import com.opentasker.ui.charts.render.drawGrid
import com.opentasker.ui.charts.render.drawHypnogram
import com.opentasker.ui.charts.render.drawLineSeries
import com.opentasker.ui.charts.render.drawRejected
import com.opentasker.ui.charts.render.drawTimeLabels
import com.opentasker.ui.charts.render.drawValueLabels
import com.opentasker.ui.theme.ThemePrefs
import java.time.ZoneId
import kotlin.math.sin

/**
 * The live chart preview on the UI-customization page.
 *
 * It goes through **the real renderers** — the same `drawLineSeries`, `drawCapsules`, `drawHypnogram`
 * and `PlotFrame` the 「健康」 window uses — over synthetic data. That is the whole point: a preview
 * drawn by a second, simplified painter would eventually disagree with the thing it claims to
 * preview, and the disagreement would be discovered on the real screen, which is the one place a
 * preview exists to avoid.
 *
 * The data is deterministic and made up. Nothing here reads the database: the customization page must
 * work on a device that has never seen the band, and a preview that changed shape as real readings
 * arrived would make it impossible to tell a slider's effect from the day's.
 */
@Composable
fun ChartStylePreview(prefs: ThemePrefs, modifier: Modifier = Modifier) {
    val style = remember(prefs) { ChartStyle.from(prefs) }
    val measurer = rememberTextMeasurer()
    val zone = remember { ZoneId.systemDefault() }
    val accent = MaterialTheme.colorScheme.primary
    val sample = remember { SampleSeries.build() }
    // A fixed clock, so the preview is the same picture every time the page opens.
    val viewport = remember(style.defaultSpanMs) {
        ChartViewport(initialEndMs = SampleSeries.END_MS, initialSpanMs = SampleSeries.SPAN_MS)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // --- the line: grid, gap tint, fill, glow, dots, the rejected ✕, and the crosshair ------
        Canvas(Modifier.fillMaxWidth().height(style.previewHeight)) {
            val rect = Rect(0f, 4f, size.width - 34f, size.height - 18f)
            if (rect.width <= 0f || rect.height <= 0f) return@Canvas
            viewport.plotWidthPx = rect.width
            val frame = PlotFrame(rect, viewport, 45.0, 110.0, style)
            val ticks = ChartTicks.labelled(ChartTicks.forSpan(viewport.startMs, viewport.endMs, zone))
            drawGrid(frame, ticks)
            drawGaps(frame, sample.gaps)
            drawLineSeries(
                frame,
                ChartPipeline.render(sample.chunk, viewport.spanMs, rect.width, style.curve).segments,
                style.heartRate,
            )
            // Heart rate's mark in full: the curve over the periodic series, the spot readings
            // hollow on top. Drawn here rather than in its own canvas because the two ARE one mark.
            drawPoints(frame, sample.spots, style.heartRate, hollow = true)
            drawRejected(frame, sample.chunk.rejectedPoints)
            drawTimeLabels(frame, ticks, measurer)
            drawValueLabels(frame, measurer, format = { v -> v.toInt().toString() })
            drawCrosshair(frame, SampleSeries.CROSSHAIR_MS, accent, sample.crosshairPoint)
        }

        // --- the block marks: capsules, dumbbells, bars ------------------------------------------
        Canvas(Modifier.fillMaxWidth().height(style.previewHeight * 0.7f)) {
            val rect = Rect(0f, 4f, size.width, size.height - 4f)
            if (rect.width <= 0f || rect.height <= 0f) return@Canvas
            val frame = PlotFrame(rect, viewport, 0.0, 200.0, style)
            drawGrid(frame, emptyList())
            drawCapsules(frame, sample.capsules, style.spo2)
            drawDumbbells(frame, sample.dumbbells, style.systolic, style.diastolic)
            drawBars(frame, sample.bars, SampleSeries.BAR_MS, style.steps)
        }

        // --- the hypnogram: four stages, the colours the CVD check cares most about ---------------
        Canvas(Modifier.fillMaxWidth().height(style.previewHeight * 0.55f)) {
            val rect = Rect(0f, 2f, size.width, size.height - 2f)
            if (rect.width <= 0f || rect.height <= 0f) return@Canvas
            val frame = PlotFrame(rect, viewport, 0.0, 1.0, style)
            drawGrid(frame, emptyList(), horizontalLines = SleepShape.ROWS.size)
            drawHypnogram(
                frame = frame,
                runs = sample.sleepRuns,
                rowOf = SleepShape::rowOf,
                rows = SleepShape.ROWS.size,
                colorOf = style::sleepStage,
            )
        }
    }
}

/**
 * The colour verdict, computed live on whatever is currently picked.
 *
 * Advisory by design — see [PaletteCheck]. It reports the same arithmetic the shipped palette was
 * validated with, so a change that breaks colour-blind separation says so on the spot rather than
 * being discovered by someone who cannot read the chart.
 */
@Composable
fun ChartPaletteVerdict(prefs: ThemePrefs, modifier: Modifier = Modifier) {
    val reports = remember(prefs) {
        listOf(
            "Metric series" to PaletteCheck.validate(seriesEntries(prefs)),
            "Sleep stages" to PaletteCheck.validate(sleepEntries(prefs)),
            "Blood pressure" to PaletteCheck.validate(bpEntries(prefs)),
        )
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        reports.forEach { (name, report) ->
            val (mark, tint) = when (report.verdict) {
                PaletteCheck.Verdict.PASS -> "✓" to ChartPalette.BAND_GOOD
                PaletteCheck.Verdict.WARN -> "!" to ChartPalette.BAND_WARN
                PaletteCheck.Verdict.FAIL -> "✕" to ChartPalette.BAND_CRITICAL
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(mark, style = MaterialTheme.typography.bodyMedium, color = tint, fontWeight = FontWeight.Bold)
                Column {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    val detail = report.failures.takeIf { it.isNotEmpty() }
                        ?: report.findings.filter { it.check == "色覚" || it.check == "識別" }
                    detail.forEach {
                        Text(
                            "${it.check}: ${it.detail}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (it.verdict == PaletteCheck.Verdict.PASS) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                tint
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun seriesEntries(p: ThemePrefs) = listOf(
    "心拍" to p.chartColorHeartRate,
    "バンド状態指数" to p.chartColorBandState,
    "血中酸素" to p.chartColorSpo2,
    "体温" to p.chartColorTemperature,
    "歩数" to p.chartColorSteps,
)

private fun sleepEntries(p: ThemePrefs) = listOf(
    "深い" to p.chartColorSleepDeep,
    "浅い" to p.chartColorSleepLight,
    "REM" to p.chartColorSleepRem,
    "覚醒" to p.chartColorSleepAwake,
)

private fun bpEntries(p: ThemePrefs) = listOf(
    "収縮期" to p.chartColorSystolic,
    "拡張期" to p.chartColorDiastolic,
)

/**
 * One made-up day, shaped like a real one.
 *
 * It has to contain everything a knob can act on, or a slider will look inert: a gap (so the tint has
 * something to cover), a flagged sample (so the ✕ appears), an hour with one reading and an hour with
 * a wide spread (so a capsule's minimum height matters), and a stage change (so the hypnogram riser
 * shows).
 */
private object SampleSeries {
    const val END_MS = 1_754_500_000_000L
    const val SPAN_MS = 12 * 3_600_000L
    const val BAR_MS = 600_000L
    val START_MS = END_MS - SPAN_MS
    val CROSSHAIR_MS = START_MS + SPAN_MS * 62 / 100

    class Sample(
        val chunk: QualifiedChunk,
        val gaps: List<LongRange>,
        /** Heart rate's second population: the spot readings, drawn hollow above the curve. */
        val spots: List<ChartPoint>,
        val capsules: List<HourBucket>,
        val dumbbells: List<DumbbellBucket>,
        val bars: List<ChartPoint>,
        val sleepRuns: List<SleepRun>,
        val crosshairPoint: ChartPoint?,
    )

    fun build(): Sample {
        val step = SPAN_MS / 90
        // A resting stretch, a climb, and a plateau — enough shape that PCHIP, LINEAR and STEP look
        // visibly different from one another.
        val all = (0..90).map { i ->
            val t = START_MS + i * step
            val base = 58 + 22 * sin(i / 14.0) + if (i > 55) 14.0 else 0.0
            ChartPoint(t, base)
        }
        val gapFrom = START_MS + step * 30
        val gapTo = START_MS + step * 40
        val kept = all.filter { it.tMs < gapFrom || it.tMs > gapTo }
        val (before, after) = kept.partition { it.tMs <= gapFrom }
        val rejected = listOf(ChartPoint(START_MS + step * 70, 104.0))

        // Heart rate's hollow dots: the periodic series, which sits ON the curve at rest and sags
        // below it once the wrist is moving. Drawn from every tenth sample so the preview shows the
        // real density relationship — a sparse bold curve, dots scattered around and under it.
        val spots = kept.filterIndexed { i, _ -> i % 4 == 1 }
            .mapIndexed { i, p -> ChartPoint(p.tMs, p.value - if (i % 3 == 0) 14.0 else 1.0) }

        val capsules = (0 until 6).map { h ->
            val start = START_MS + h * 2 * 3_600_000L
            val spread = if (h == 3) 0.0 else 9.0 + h * 3
            HourBucket(start, 92.0 - spread, 96.0 + spread / 3, if (h == 3) 1 else 12)
        }
        val dumbbells = (0 until 4).map { h ->
            val start = START_MS + (h * 3 + 1) * 3_600_000L
            DumbbellBucket(
                startMs = start,
                upper = HourBucket(start, 112.0 + h, 124.0 + h, 4),
                lower = HourBucket(start, 66.0 + h, 76.0 + h, 4),
            )
        }
        val bars = (0 until 24).map { i ->
            val t = START_MS + i * (SPAN_MS / 24)
            ChartPoint(t, if (i in 8..15) (30 + (i % 5) * 26).toDouble() else 0.0)
        }
        val stages = listOf('1', '2', '3', '2', '5', '2', '1')
        val runMs = SPAN_MS / stages.size
        val sleepRuns = stages.mapIndexed { i, code ->
            SleepRun(START_MS + i * runMs, START_MS + (i + 1) * runMs, code)
        }

        return Sample(
            chunk = QualifiedChunk(
                segments = listOf(ChartSegment(before), ChartSegment(after)),
                gaps = listOf(gapFrom..gapTo),
                rejectedPoints = rejected,
                noReading = 0,
                retainedCount = kept.size,
            ),
            gaps = listOf(gapFrom..gapTo),
            spots = spots,
            capsules = capsules,
            dumbbells = dumbbells,
            bars = bars,
            sleepRuns = sleepRuns,
            crosshairPoint = kept.minByOrNull { kotlin.math.abs(it.tMs - CROSSHAIR_MS) },
        )
    }
}
