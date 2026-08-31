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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentasker.core.band.BandMetric
import com.opentasker.core.band.TrainingSessions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Mark a training session after the fact, by drawing it on the heart-rate chart.
 *
 * ## Why this screen exists
 *
 * Strength work is invisible to this band — 白い熊's real lifting session gave three spot readings at
 * the 71st, 95th and 91st percentile of an ordinary waking day, with the periodic series *below*
 * resting throughout (see [TrainingSessions]). So sessions are marked rather than detected, and the
 * live toggle handles that when 白い熊 remembers to tap. This handles when they do not.
 *
 * ## Why nothing is recorded until the button is pressed
 *
 * 白い熊's own instruction (2026-08-09): the drag must not commit. A finger on a six-hour chart
 * resolves to roughly a minute per pixel at best, and the ends of a workout are exactly where that
 * imprecision matters most — a session recorded ten minutes long by accident scores a third of what
 * it should. So the drag proposes, the arrows correct, and **nothing reaches the store until
 * `Record session`**. Until then the span is a mark on a chart and nothing else.
 *
 * ## What is drawn
 *
 * The heart-rate chart exactly as the detail screen draws it — the curve over the SpO₂-coincident
 * spot readings, which is the population that tracks exertion, and the periodic series as hollow
 * dots, which is the one that does not. That is deliberate for this task: the lift is visible in the
 * curve and absent from the dots, so the shape you are looking for is the gap between them.
 */
@Composable
fun MarkSessionScreen(
    state: DashboardState,
    contentPadding: PaddingValues,
    onSubmit: (Long, Long) -> Boolean,
    onBack: () -> Unit,
    onSwitchLanguage: suspend () -> Loc? = { null },
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val zone = remember { ZoneId.systemDefault() }
    val chart = state.metrics.firstOrNull { it.spec.key == BandMetric.HEART_RATE }

    val bounds = state.bounds
    // Opens on the last six hours: a session that needs marking after the fact happened today, and a
    // six-hour window puts a 45-minute block at a usable width without any panning.
    val viewport = remember(bounds) {
        ChartViewport(
            initialEndMs = bounds.last.takeIf { it > 0 } ?: System.currentTimeMillis(),
            initialSpanMs = 6 * 3_600_000L,
        )
    }
    val span = rememberSpanSelectionState()
    var message by remember { mutableStateOf<Loc?>(null) }

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
        DetailHeader(
            BandText.markSessionTitle[lang],
            hasInfo = false,
            onBack = onBack,
            onInfo = {},
            onSwitchLanguage = onSwitchLanguage,
        )

        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SpanChips(viewport, bounds)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (chart == null || chart.isEmpty) {
                        Text(BandText.noData[lang], style = MaterialTheme.typography.bodyMedium)
                    } else {
                        MetricPlot(
                            chart, viewport,
                            Modifier.height(style.detailHeight)
                                .spanSelectInput(span, viewport)
                                .then(gestures),
                            selection = span,
                        )
                        Text(
                            BandText.markSessionHint[lang],
                            style = MaterialTheme.typography.bodySmall,
                            color = sectionNote,
                        )
                    }
                }
            }

            val start = span.startMs
            val end = span.endMs
            val minutes = if (start != null && end != null) (end - start) / 60_000L else null

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val fmt = remember(zone) { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
                    Text(
                        if (start != null && end != null && minutes != null) {
                            BandText.markSessionSpan[lang].format(
                                fmt.format(Instant.ofEpochMilli(start)),
                                fmt.format(Instant.ofEpochMilli(end)),
                                minutes,
                            )
                        } else {
                            BandText.markSessionNothing[lang]
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )

                    if (start != null && end != null) {
                        // Five-minute arrows on each end. A workout's edges are the thing worth
                        // getting right — the score is (METs − 1) × minutes, so ten minutes of slop
                        // is a fifth of a fifty-minute session.
                        NudgeRow(BandText.markSessionStart[lang]) { delta ->
                            span.set((start + delta).coerceAtMost(end - 60_000L), end)
                            message = null
                        }
                        NudgeRow(BandText.markSessionEnd[lang]) { delta ->
                            span.set(start, (end + delta).coerceAtLeast(start + 60_000L))
                            message = null
                        }
                    }

                    message?.let {
                        Text(
                            it[lang],
                            style = MaterialTheme.typography.bodySmall,
                            color = ChartPalette.BAND_WARN,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val valid = minutes != null &&
                            minutes >= TrainingSessions.MIN_SESSION_MINUTES &&
                            minutes <= TrainingSessions.MAX_OPEN_MINUTES
                        Button(
                            text = BandText.markSessionSubmit[lang],
                            enabled = valid,
                            primary = true,
                        ) {
                            // Guarded twice on purpose: the button is disabled when the span is out
                            // of range, and the model refuses it anyway. The store is the thing that
                            // must never take a one-minute "workout", so it owns the rule.
                            if (start != null && end != null && onSubmit(start, end)) {
                                span.clear()
                                message = BandText.markSessionDone
                            } else {
                                message = BandText.markSessionRejected
                            }
                        }
                        Button(
                            text = BandText.markSessionClear[lang],
                            enabled = span.active,
                            primary = false,
                        ) {
                            span.clear()
                            message = null
                        }
                    }
                }
            }
        }
    }
}

/** −5 / +5 minutes on one end of the span. */
@Composable
private fun NudgeRow(label: String, onNudge: (Long) -> Unit) {
    val style = LocalChartStyle.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = style.axisText)
        Spacer(Modifier.width(4.dp))
        Button(text = "−5", enabled = true, primary = false) { onNudge(-5 * 60_000L) }
        Button(text = "+5", enabled = true, primary = false) { onNudge(5 * 60_000L) }
    }
}

/** A plain tappable pill — the window has no button style of its own and needs two here. */
@Composable
private fun Button(text: String, enabled: Boolean, primary: Boolean, onClick: () -> Unit) {
    val style = LocalChartStyle.current
    val tint = if (primary) ChartPalette.HEART_RATE else style.axisText
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) tint.copy(alpha = if (enabled) 0.22f else 0.06f) else Color.Transparent)
            .then(
                if (primary) Modifier else Modifier.clip(RoundedCornerShape(12.dp)),
            )
            // padding before clickable, so the whole pill is the target rather than the text.
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
            color = if (enabled) tint else tint.copy(alpha = 0.4f),
        )
    }
}
