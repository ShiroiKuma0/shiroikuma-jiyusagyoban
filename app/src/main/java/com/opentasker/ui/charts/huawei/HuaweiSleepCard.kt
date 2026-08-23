package com.opentasker.ui.charts.huawei

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.opentasker.core.huawei.HuaweiSleep
import com.opentasker.ui.charts.ChartCard
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.ChartTicks
import com.opentasker.ui.charts.ChartViewport
import com.opentasker.ui.charts.CrosshairState
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import com.opentasker.ui.charts.SleepRun
import com.opentasker.ui.charts.crosshairInput
import com.opentasker.ui.charts.crosshairTimeLabel
import com.opentasker.ui.charts.render.PlotFrame
import com.opentasker.ui.charts.render.drawCrosshair
import com.opentasker.ui.charts.render.drawGrid
import com.opentasker.ui.charts.render.drawHypnogram
import com.opentasker.ui.charts.render.drawTimeLabels
import java.time.ZoneId

/**
 * Last night, as a hypnogram, on the dashboard's own clock.
 *
 * ## Why it shares the viewport instead of spanning the night
 *
 * The first version gave the hypnogram its own span — first segment to last — which stretched five
 * hours of sleep across the full card width. That is wrong twice over. It makes the night look like
 * a whole day next to charts that really do cover a day, and it puts this card on a different time
 * axis from every other one, so the crosshair cannot line up. The question the crosshair exists to
 * answer — *heart rate spiked at 03:12, was I in REM?* — is unanswerable across two axes.
 *
 * Hume's own sleep card had already settled this, and its comment says so. Sharing the clock costs
 * the hypnogram width and buys the only thing that makes a shared crosshair worth having.
 *
 * ## Stage is carried by POSITION
 *
 * Deep at the bottom rising to awake at the top, with the lane named down the left edge. 白い熊 is
 * red-green colour-blind and the tightest adjacent pair in this palette (deep against light)
 * measures ΔE 8.4 under simulated deficiency — over the 8.0 target, but not by enough to lean on.
 * With position and a written label carrying the meaning, colour is reinforcement and the chart
 * survives greyscale. Every colour comes from [ChartPalette]; `HuaweiSleepPaletteTest` gates them.
 *
 * ## What is drawn versus what is counted
 *
 * The band's bed and wake times bracket the SLEEP, and awake blocks can sit outside them at both
 * ends — this night has twelve minutes before bed time and four after waking. Those are drawn,
 * because hiding them would misrepresent the shape, and excluded from the asleep total, because the
 * band excludes them too.
 */
@Composable
fun HuaweiSleepCard(
    night: HuaweiSleepNight?,
    viewport: ChartViewport,
    crosshair: CrosshairState,
    onClick: (() -> Unit)? = null,
) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current

    if (night == null) {
        SectionCard(accent = ChartPalette.SLEEP_DEEP, onClick = onClick) {
            SectionTitle(HuaweiText.sleepTitle[lang], ChartPalette.SLEEP_DEEP)
            NoteText(HuaweiText.sleepNone[lang])
        }
        return
    }

    val s = night.session
    val runs = remember(s) {
        s.segments.map { SleepRun(it.startSeconds * 1000L, it.endSeconds * 1000L, codeOf(it.stage)) }
    }
    // At the crosshair the card reads out that instant rather than the night's summary — the same
    // bargain every other card on this page makes.
    val atCrosshair = crosshair.tMs?.let { t -> s.segments.firstOrNull { it.startSeconds * 1000L <= t && t < it.endSeconds * 1000L } }

    // Which night this actually is, said out loud. The card titled itself "Last night" while showing
    // a two-day-old one for two days, because the parser could only ever reach the oldest night in
    // the file. Nothing on screen contradicted it, and that is what made it hard to see.
    val nightsAgo = remember(s) {
        val today = java.util.Calendar.getInstance()
        val night = java.util.Calendar.getInstance().apply { timeInMillis = s.endSeconds * 1000L }
        ((today.timeInMillis / 86_400_000L) - (night.timeInMillis / 86_400_000L)).toInt()
    }
    val dated = remember(s) {
        java.text.SimpleDateFormat("M/d", java.util.Locale.getDefault())
            .format(java.util.Date(s.startSeconds * 1000L))
    }

    ChartCard(
        title = if (nightsAgo <= 0) HuaweiText.sleepTitle[lang]
        else "${HuaweiText.sleepOlderNight[lang]} · $dated ($nightsAgo ${HuaweiText.sleepStale[lang]})",
        headline = atCrosshair?.let { labelOf(it.stage)[lang] } ?: HuaweiSleepFormat.hm(s.asleepSeconds),
        unit = "",
        band = null,
        subtitle = atCrosshair?.let { crosshairTimeLabel(crosshair.tMs!!) }
            ?: "${HuaweiText.sleepBed[lang]} ${HuaweiSleepFormat.clock(s.startSeconds)}" +
            "   ${HuaweiText.sleepWoke[lang]} ${HuaweiSleepFormat.clock(s.endSeconds)}",
        accent = atCrosshair?.let { colorOf(it.stage) } ?: ChartPalette.SLEEP_DEEP,
        // Tappable like every other card. It was the one that was not, which made it look like a
        // summary rather than a way in — and it is the card with the most behind it.
        onClick = onClick,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Hypnogram(
                runs, viewport, crosshair,
                Modifier.height(style.previewHeight).crosshairInput(crosshair, viewport),
            )
            StageLegend(s)
            NoteText(HuaweiText.sleepOutside[lang])
            if (!s.alignsWithHeader) NoteText(HuaweiText.sleepMisaligned[lang], warn = true)
        }
    }
}

/**
 * Bottom to top: deep, light, REM, awake.
 *
 * These codes are the HUAWEI band's own numbering and are **not** interchangeable with the Hume
 * band's — there, 1 is deep and 2 is light, exactly inverted from here. Passing one band's runs to
 * the other's `rowOf` swaps deep with light and still draws a convincing night, so the two mappings
 * stay in separate files and neither is ever reused for the other device.
 */
private val LANES = listOf(
    HuaweiSleep.Stage.AWAKE,
    HuaweiSleep.Stage.REM,
    HuaweiSleep.Stage.LIGHT,
    HuaweiSleep.Stage.DEEP,
)

private fun codeOf(stage: HuaweiSleep.Stage): Char =
    if (stage == HuaweiSleep.Stage.UNKNOWN) '?' else ('0' + stage.code)

private fun stageOfCode(code: Char): HuaweiSleep.Stage =
    if (code == '?') HuaweiSleep.Stage.UNKNOWN else HuaweiSleep.Stage.of(code - '0')

internal fun colorOf(stage: HuaweiSleep.Stage): Color = when (stage) {
    HuaweiSleep.Stage.DEEP -> ChartPalette.SLEEP_DEEP
    HuaweiSleep.Stage.LIGHT -> ChartPalette.SLEEP_LIGHT
    HuaweiSleep.Stage.REM -> ChartPalette.SLEEP_REM
    HuaweiSleep.Stage.AWAKE -> ChartPalette.SLEEP_AWAKE
    HuaweiSleep.Stage.UNKNOWN -> ChartPalette.UNKNOWN
}

private fun labelOf(stage: HuaweiSleep.Stage) = when (stage) {
    HuaweiSleep.Stage.DEEP -> HuaweiText.sleepDeep
    HuaweiSleep.Stage.LIGHT -> HuaweiText.sleepLight
    HuaweiSleep.Stage.REM -> HuaweiText.sleepRem
    HuaweiSleep.Stage.AWAKE -> HuaweiText.sleepAwake
    HuaweiSleep.Stage.UNKNOWN -> HuaweiText.sleepAwake
}

/** Room for the time labels below and the lane names on the left. */
private const val AXIS_BOTTOM = 18f
private const val AXIS_LEFT = 46f

@Composable
internal fun Hypnogram(
    runs: List<SleepRun>,
    viewport: ChartViewport,
    crosshair: CrosshairState,
    modifier: Modifier,
) {
    val lang = LocalBandLanguage.current
    val measurer = rememberTextMeasurer()
    val zone = remember { ZoneId.systemDefault() }
    val style = LocalChartStyle.current
    val accent = MaterialTheme.colorScheme.primary

    Canvas(modifier.fillMaxWidth()) {
        val rect = Rect(AXIS_LEFT, 4f, size.width, size.height - AXIS_BOTTOM)
        if (rect.width <= 0f || rect.height <= 0f) return@Canvas
        viewport.plotWidthPx = rect.width
        val frame = PlotFrame(rect, viewport, 0.0, 1.0, style)

        // The same style the axis itself uses; ChartFrame keeps its helper private, and one small
        // duplicated TextStyle is a better trade than widening that file's surface for this.
        val axisText = TextStyle(fontSize = style.axisTextSize, color = style.axisText)
        val ticks = ChartTicks.labelled(ChartTicks.forSpan(viewport.startMs, viewport.endMs, zone))
        drawGrid(frame, ticks, horizontalLines = LANES.size)
        drawHypnogram(
            frame = frame,
            runs = runs,
            rowOf = { code -> LANES.indexOf(stageOfCode(code)).takeIf { it >= 0 } ?: 0 },
            rows = LANES.size,
            colorOf = { code -> colorOf(stageOfCode(code)) },
        )
        drawTimeLabels(frame, ticks, measurer)

        // The Y legend: the lane names, so the vertical axis reads without the colour key.
        val rowH = rect.height / LANES.size
        LANES.forEachIndexed { i, stage ->
            val laid = measurer.measure(labelOf(stage)[lang], axisText)
            drawText(
                laid,
                topLeft = Offset(
                    (AXIS_LEFT - 6f - laid.size.width).coerceAtLeast(0f),
                    rect.top + rowH * (i + 0.5f) - laid.size.height / 2f,
                ),
            )
        }
        crosshair.tMs?.let { drawCrosshair(frame, it, accent) }
    }
}

@Composable
private fun StageLegend(session: HuaweiSleep.Session) {
    val lang = LocalBandLanguage.current
    val totals = session.totals()
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LANES.reversed().forEach { stage ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(colorOf(stage)))
                NoteText("${labelOf(stage)[lang]} ${HuaweiSleepFormat.hm(totals[stage] ?: 0)}")
            }
        }
    }
}

internal object HuaweiSleepFormat {
    private val CLOCK = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    fun clock(epochSeconds: Long): String =
        CLOCK.format(java.time.Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))

    /** Hours and minutes, never a bare minute count — nobody reads a night in minutes. */
    fun hm(seconds: Int): String {
        val m = seconds / 60
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }
}
