package com.opentasker.ui.charts.huawei

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentasker.ui.charts.ChartViewport
import com.opentasker.ui.charts.InfoBody
import com.opentasker.ui.charts.InfoHeading
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.charts.MetricChart
import com.opentasker.ui.charts.MetricPlot
import com.opentasker.ui.charts.rememberChartGestureModifier
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import com.opentasker.ui.charts.rememberCrosshairState

/**
 * One Huawei metric, full screen, with the text that says what it is and what we do not know.
 *
 * The chart is the smaller half of this screen's purpose. Every gate on this band is provisional and
 * the steps metric's absent-means-not-measured convention is the exact inverse of the Hume band's —
 * facts a reader can only meet here, and which matter more than the curve.
 *
 * Its own crosshair rather than the dashboard's: a line left behind on the page underneath has
 * nothing to do with where it is wanted here.
 */
@Composable
fun HuaweiMetricDetailScreen(
    chart: MetricChart,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    /**
     * How far back the chart may be scrolled — the whole history, including the era before this band
     * existed. Without it the viewport clamps to whatever the visible window happened to start at.
     */
    bounds: LongRange = 0L..0L,
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val spec = chart.spec

    val viewport = remember(chart.spec.key, style.defaultSpanMs) {
        val end = chart.readoutPoints.lastOrNull()?.tMs ?: System.currentTimeMillis()
        ChartViewport(initialEndMs = end, initialSpanMs = style.defaultSpanMs)
    }
    val crosshair = rememberCrosshairState()

    // Pinch to zoom, drag sideways to travel. This screen had neither, which is why the history was
    // invisible: the readings were on the chart the whole time, just off the left of a window that
    // could not be moved.
    val span = if (bounds.last > bounds.first) bounds else viewport.startMs..viewport.endMs
    val gestures = rememberChartGestureModifier(
        onZoom = { viewport.zoomAround(viewport.plotWidthPx / 2f, it, span) },
        onPan = { viewport.panBy(it.x, span) },
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(style.cardGap),
    ) {
        item("back") { TextButton(onClick = onBack) { Text(HuaweiText.back[lang]) } }

        item("plot") {
            SectionCard(accent = spec.color) {
                SectionTitle(spec.label[lang], spec.color)
                Text(
                    "${chart.headline}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = spec.color,
                )
                if (spec.provisional) NoteText(HuaweiText.provisional[lang], warn = true)
                MetricPlot(
                    chart = chart,
                    viewport = viewport,
                    modifier = Modifier.fillMaxWidth().height(style.detailHeight).then(gestures),
                    crosshair = crosshair,
                )
            }
        }

        item("info") {
            SectionCard(accent = spec.color) {
                SectionTitle(spec.label[lang], spec.color)
                InfoHeading(if (lang.tag.startsWith("ja")) "これは何か" else "What it is")
                InfoBody(spec.info.whatItIs[lang])
                InfoHeading(if (lang.tag.startsWith("ja")) "どう測っているか" else "How it is measured")
                InfoBody(spec.info.howMeasured[lang])
                InfoHeading(if (lang.tag.startsWith("ja")) "どう読むか" else "How to read it")
                InfoBody(spec.info.howToRead[lang])
                if (spec.info.caveat[lang].isNotBlank()) {
                    // Last and unmissable. On this band the caveat is usually the most load-bearing
                    // thing on the screen — the steps one carries a trap that silently inverts a
                    // comparison against the other band.
                    InfoHeading(if (lang.tag.startsWith("ja")) "分かっていないこと" else "What we do not know")
                    InfoBody(spec.info.caveat[lang])
                }
            }
        }
    }
}
