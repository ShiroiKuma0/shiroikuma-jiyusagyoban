package com.opentasker.ui.charts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One metric, full screen.
 *
 * Opens on the **last 24 hours** and pinch-zooms and pans across the whole stored history. The
 * gesture arbitration is `rememberChartGestureModifier`, which refuses vertical drags so the page
 * behind the chart keeps scrolling — proven on device by `ChartGestureInteropTest`, including a
 * control that reproduces what a bare `transformable` does instead (it eats the scroll).
 */
@Composable
fun MetricDetailScreen(
    state: DashboardState,
    metricKey: String,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current

    val bounds = state.bounds
    val viewport = remember(bounds, style.defaultSpanMs) {
        ChartViewport(
            initialEndMs = bounds.last.takeIf { it > 0 } ?: System.currentTimeMillis(),
            initialSpanMs = style.defaultSpanMs,
        )
    }
    var showInfo by rememberSaveable(metricKey) { mutableStateOf(false) }
    // Its own crosshair, not the dashboard's: this screen shows one metric, and a line left behind on
    // the page underneath has nothing to do with where you want it here.
    val crosshair = rememberCrosshairState()
    // A marked stretch of time, on the same screen and the one spare gesture: long-press and drag.
    // The crosshair answers "what was it at 03:12"; this answers "what did that walk come to".
    val span = rememberSpanSelectionState()

    val spec = MetricSpecs.byKey(metricKey)
    val title = when (metricKey) {
        MetricSpecs.KEY_BLOOD_PRESSURE -> BandText.bloodPressure[lang]
        MetricSpecs.KEY_SLEEP -> BandText.sleep[lang]
        MetricSpecs.KEY_INDEX -> BandText.indexTitle[lang]
        MetricSpecs.KEY_RECOVERY -> BandText.recoveryTitle[lang]
        else -> spec?.label?.get(lang) ?: metricKey
    }
    val info = when (metricKey) {
        MetricSpecs.KEY_BLOOD_PRESSURE -> MetricSpecs.BLOOD_PRESSURE_INFO
        MetricSpecs.KEY_SLEEP -> MetricSpecs.SLEEP_INFO
        else -> spec?.info
    }

    val gestures = rememberChartGestureModifier(
        onZoom = { viewport.zoomAround(viewport.plotWidthPx / 2f, it, bounds) },
        onPan = { viewport.panBy(it.x, bounds) },
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
    ) {
        DetailHeader(title, hasInfo = info != null, onBack = onBack, onInfo = { showInfo = !showInfo })

        if (metricKey == MetricSpecs.KEY_INDEX) {
            state.index?.let { HealthIndexDetail(it) }
            return@Column
        }
        if (metricKey == MetricSpecs.KEY_RECOVERY) {
            RecoveryDetail(state.recovery, state.load, state.sri)
            return@Column
        }

        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SpanChips(viewport, bounds)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (metricKey) {
                        MetricSpecs.KEY_BLOOD_PRESSURE -> state.bloodPressure?.let { bp ->
                            Headline(bp.headline, "mmHg", null)
                            BloodPressurePlot(
                                bp, viewport,
                                Modifier.height(style.detailHeight)
                                    .crosshairTapInput(crosshair, viewport)
                                    .then(gestures),
                                crosshair = crosshair,
                            )
                            CrosshairHint(crosshair)
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                LegendEntry(
                                    style.systolic,
                                    "${BandText.systolic[lang]} ${bp.systolicRange}",
                                )
                                LegendEntry(
                                    style.diastolic,
                                    "${BandText.diastolic[lang]} ${bp.diastolicRange}",
                                )
                            }
                        }
                        MetricSpecs.KEY_SLEEP -> state.sleep?.let { sleep ->
                            val runs = remember(sleep) { sleep.sessions.flatMap { it.runs } }
                            val stage = crosshair.tMs?.let { stageAt(runs, it) }
                            Headline(
                                stage?.let { SleepShape.labelOf(it.code)[lang] } ?: sleep.headline[lang],
                                "",
                                null,
                            )
                            SleepPlot(
                                sleep, viewport,
                                Modifier.height(style.detailHeight)
                                    .crosshairTapInput(crosshair, viewport)
                                    .then(gestures),
                                crosshair = crosshair,
                            )
                            CrosshairHint(
                                crosshair,
                                readout = crosshair.tMs?.let { t ->
                                    if (stage != null) crosshairTimeLabel(t) else BandText.awakeAtCrosshair[lang]
                                },
                            )
                            SleepLegend()
                            sleep.latest?.let { SleepBreakdown(it) }
                        }
                        else -> state.metrics.firstOrNull { it.spec.key == metricKey }?.let { chart ->
                            // With the crosshair planted the headline reads THAT instant. Leaving the
                            // 24-hour median on screen beside a line sitting at 03:12 would answer a
                            // question nobody asked.
                            val at = crosshair.tMs?.let { chart.readoutAt(it) }
                            Headline(
                                at?.let { chart.spec.format(it.value) } ?: chart.headline,
                                chart.spec.unit,
                                if (at != null) null else chart.headlineBand,
                            )
                            MetricPlot(
                                chart, viewport,
                                Modifier.height(style.detailHeight)
                                    .crosshairTapInput(crosshair, viewport)
                                    .spanSelectInput(span, viewport)
                                    .then(gestures),
                                crosshair = crosshair,
                                selection = span,
                            )
                            if (chart.spec.splitPopulations) {
                                SecondPopulationLegend(style.colorFor(chart.spec.key))
                            }
                            CrosshairHint(
                                crosshair,
                                readout = crosshair.tMs?.let { t ->
                                    at?.let { crosshairTimeLabel(it.tMs) } ?: BandText.nothingHere[lang]
                                },
                            )
                            SpanReadout(span, chart, lang)
                            Text(
                                chart.subtitle[lang],
                                style = MaterialTheme.typography.bodySmall,
                                color = style.axisText,
                            )
                        }
                    }
                }
            }

            // The numbers, under the shape. A chart shows how a day went; only a table lets you read
            // Tuesday's figure without zooming and squinting at an axis.
            when (metricKey) {
                MetricSpecs.KEY_SLEEP -> state.sleep?.let { SleepHistoryCard(it.nights) }
                MetricSpecs.KEY_BLOOD_PRESSURE -> Unit  // a dumbbell day is two series; the ladder says more
                else -> state.metrics.firstOrNull { it.spec.key == metricKey }?.let {
                    MetricHistoryCard(it.history, it.spec)
                }
            }

            spec?.bands?.takeIf { it.isNotEmpty() }?.let { BandLadder(it) }

            if (showInfo && info != null) InfoSheet(info)
        }
    }
}

@Composable
fun DetailHeader(title: String, hasInfo: Boolean, onBack: () -> Unit, onInfo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton("←", onBack)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (hasInfo) InfoCircle(onClick = onInfo)
    }
}

/**
 * The line under the plot that says the crosshair exists, then what it is reading.
 *
 * A gesture with no affordance is a gesture nobody finds. Before the first tap this says how to place
 * the line; afterwards it is the readout, so the hint costs a row only until it has been used.
 */
@Composable
private fun CrosshairHint(crosshair: CrosshairState, readout: String? = null) {
    val lang = LocalBandLanguage.current
    Text(
        when {
            !crosshair.active -> BandText.crosshairHint[lang]
            readout != null -> readout
            else -> crosshairTimeLabel(crosshair.tMs!!)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (crosshair.active) {
            MaterialTheme.colorScheme.primary
        } else {
            LocalChartStyle.current.axisText
        },
    )
}

/**
 * The back arrow. Plain, unlike the ringed `i` beside it: back is a gesture everyone already knows,
 * whereas the `i` is the only route to what a metric actually means and has to advertise itself.
 */
@Composable
private fun CircleButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Headline(value: String, unit: String, band: BandRung?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        if (unit.isNotBlank()) {
            Spacer(Modifier.width(5.dp))
            Text(
                unit,
                style = MaterialTheme.typography.bodyLarge,
                color = LocalChartStyle.current.axisText,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        band?.let { Box(Modifier.padding(bottom = 6.dp)) { BandChip(it) } }
    }
}

/** Quick spans. Pinch does the same thing continuously; these are for getting there in one tap. */
@Composable
fun SpanChips(viewport: ChartViewport, bounds: LongRange) {
    val lang = LocalBandLanguage.current
    val spans = listOf(
        BandText.span1h[lang] to 3_600_000L,
        BandText.span6h[lang] to 6 * 3_600_000L,
        BandText.span24h[lang] to 24 * 3_600_000L,
        BandText.span3d[lang] to 3 * 24 * 3_600_000L,
        BandText.spanAll[lang] to (bounds.last - bounds.first).coerceAtLeast(3_600_000L),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        spans.forEach { (label, span) ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { viewport.setSpan(span, bounds) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The qualitative ladder. Always labelled — a colour never carries the state on its own. */
@Composable
private fun BandLadder(bands: List<BandRung>) {
    val lang = LocalBandLanguage.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                BandText.guide[lang],
                style = MaterialTheme.typography.labelMedium,
                color = LocalChartStyle.current.axisText,
            )
            bands.forEachIndexed { i, rung ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(rung.color))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        rung.label[lang],
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (rung.upTo == Double.MAX_VALUE) {
                            BandText.andAbove[lang].format(bands.getOrNull(i - 1)?.upTo?.toInt() ?: 0)
                        } else {
                            BandText.upTo[lang].format(rung.upTo.toInt())
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChartStyle.current.axisText,
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepBreakdown(session: SleepSession) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    // A table rather than four sentences (白い熊, 2026-08-10): the four stages are one quantity read
    // four ways, and columns are how you compare four of anything. Minutes right-aligned so the
    // digits line up, percentages beside them, and a total rule underneath to close it.
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row {
            Spacer(Modifier.width(18.dp))
            Text(
                BandText.sleepStageHeader[lang],
                style = MaterialTheme.typography.labelMedium,
                color = style.axisText,
                modifier = Modifier.weight(1f),
            )
            Text(
                BandText.sleepMinutesHeader[lang],
                style = MaterialTheme.typography.labelMedium,
                color = style.axisText,
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.End,
            )
            Text(
                BandText.sleepShareHeader[lang],
                style = MaterialTheme.typography.labelMedium,
                color = style.axisText,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(style.grid))
        SleepShape.ROWS.forEach { code ->
            val minutes = session.minutesOf(code)
            val pct = if (session.totalMinutes > 0) minutes * 100 / session.totalMinutes else 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(style.sleepStage(code)))
                Spacer(Modifier.width(8.dp))
                Text(
                    SleepShape.labelOf(code)[lang],
                    style = MaterialTheme.typography.bodyMedium,
                    color = sectionInk,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (lang == BandLanguage.EN) "${minutes}m" else "${minutes}分",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = sectionInk,
                    modifier = Modifier.width(64.dp),
                    textAlign = TextAlign.End,
                )
                Text(
                    "$pct%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = style.axisText,
                    modifier = Modifier.width(56.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(style.grid))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(18.dp))
            Text(
                BandText.sleepTotalRow[lang],
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = sectionInk,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (lang == BandLanguage.EN) "${session.totalMinutes}m" else "${session.totalMinutes}分",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = sectionInk,
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.width(56.dp))
        }
    }
}

/**
 * The `i` sheet. Richer than Hume's, and explicit about what is not known.
 *
 * Typography comes from [InfoType] — the same sizing, colour and solid leading the 健康指数 page uses,
 * so the two long-form screens in this window read as one thing rather than two.
 */
@Composable
private fun InfoSheet(info: MetricInfo) {
    val lang = LocalBandLanguage.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            InfoBlock(BandText.infoWhat[lang], info.whatItIs[lang])
            InfoBlock(BandText.infoHow[lang], info.howMeasured[lang])
            InfoBlock(BandText.infoRead[lang], info.howToRead[lang])
            if (info.caveat[lang].isNotBlank()) {
                InfoBlock(BandText.infoCaveat[lang], info.caveat[lang])
            }
        }
    }
}

@Composable
private fun InfoBlock(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoHeading(heading)
        InfoBody(body)
    }
}
