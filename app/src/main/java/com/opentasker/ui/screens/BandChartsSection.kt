package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentasker.ui.charts.ChartPipeline
import com.opentasker.ui.charts.ChartPoint
import com.opentasker.ui.charts.ChartViewport
import com.opentasker.ui.charts.MetricLineChart
import com.opentasker.ui.charts.MetricSpec
import com.opentasker.ui.charts.QualifiedChunk
import com.opentasker.ui.charts.rememberChartColors
import java.time.ZoneId

/**
 * The charts, inside the existing 「健康」 tab — below the sync controls and the census, per the
 * charts hand-off. Not a new screen and not a new [OpenTaskerScreen] entry.
 */

/** What the ViewModel hands the charts: the viewport-independent half of the pipeline, per metric. */
data class BandChartData(
    val chunks: Map<String, QualifiedChunk> = emptyMap(),
    /** SpO₂-coincident heart-rate readings — real, differently biased, drawn as spots. */
    val heartRateSpots: List<ChartPoint> = emptyList(),
    /** Oldest .. newest sample across every metric, for clamping the viewport. */
    val bounds: LongRange = 0L..0L,
    val totalSamples: Int = 0,
) {
    val hasData: Boolean get() = totalSamples > 0 && bounds.last > bounds.first
}

@Composable
fun BandChartsCard(
    data: BandChartData,
    specs: List<MetricSpec>,
    viewport: ChartViewport,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "健康 — グラフ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (!data.hasData) {
                Text(
                    "No samples yet. Run 同期 -- [727] and the charts fill in.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            SpanChips(viewport = viewport, bounds = data.bounds)

            for (spec in specs) {
                val chunk = data.chunks[spec.key] ?: continue
                if (chunk.retainedCount == 0 && chunk.rejectedPoints.isEmpty()) continue
                MetricChart(
                    spec = spec,
                    chunk = chunk,
                    viewport = viewport,
                    zone = zone,
                    spots = if (spec.key == com.opentasker.core.band.BandMetric.HEART_RATE) {
                        data.heartRateSpots
                    } else {
                        emptyList()
                    },
                )
            }
        }
    }
}

@Composable
private fun MetricChart(
    spec: MetricSpec,
    chunk: QualifiedChunk,
    viewport: ChartViewport,
    zone: ZoneId,
    spots: List<ChartPoint>,
) {
    var showRejected by rememberSaveable(spec.key) { mutableStateOf(false) }
    val colors = rememberChartColors(series = seriesColorFor(spec.key))

    // S4 + S5. Keyed on the chunk, the span and the plot width — NOT on the pan position, so
    // scrolling sideways does not recompute the level of detail. At 24 h across a phone this is a
    // no-op anyway: 720 samples over 1080 px needs no decimation, so the chart IS the measurements.
    val model = remember(chunk, viewport.spanMs, viewport.plotWidthPx) {
        ChartPipeline.render(
            chunk = chunk,
            spanMs = viewport.spanMs,
            plotWidthPx = viewport.plotWidthPx.takeIf { it > 0f } ?: 1080f,
        )
    }

    MetricLineChart(
        spec = spec,
        model = model,
        viewport = viewport,
        zone = zone,
        colors = colors,
        showRejected = showRejected,
        onToggleRejected = { showRejected = !showRejected },
        spotReadings = spots,
    )
}

@Composable
private fun SpanChips(viewport: ChartViewport, bounds: LongRange) {
    val options = remember {
        listOf(
            "24h" to 24 * 3_600_000L,
            "6h" to 6 * 3_600_000L,
            "1h" to 3_600_000L,
        )
    }
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, span) in options) {
            FilterChip(
                selected = viewport.spanMs == span,
                onClick = { viewport.setSpan(span, bounds) },
                label = { Text(label) },
            )
        }
        FilterChip(
            selected = false,
            onClick = { viewport.jumpTo(bounds.last, bounds) },
            label = { Text("Now") },
        )
    }
}

/**
 * The series palette. Chrome follows the fork's black-yellow identity; the series deliberately do
 * not, because a multi-series stack in one hue is unreadable.
 */
@Composable
private fun seriesColorFor(key: String): androidx.compose.ui.graphics.Color = when (key) {
    com.opentasker.core.band.BandMetric.HEART_RATE -> androidx.compose.ui.graphics.Color(0xFFE0453B)
    com.opentasker.core.band.BandMetric.HRV -> androidx.compose.ui.graphics.Color(0xFF3BA5E0)
    com.opentasker.core.band.BandMetric.SPO2 -> androidx.compose.ui.graphics.Color(0xFF37C26B)
    com.opentasker.core.band.BandMetric.TEMPERATURE -> androidx.compose.ui.graphics.Color(0xFFE59A2B)
    com.opentasker.core.band.BandMetric.STRESS -> androidx.compose.ui.graphics.Color(0xFFB268D6)
    else -> MaterialTheme.colorScheme.primary
}
