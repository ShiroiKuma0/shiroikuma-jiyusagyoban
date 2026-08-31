package com.opentasker.ui.charts.huawei

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.ChartViewport
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import com.opentasker.ui.charts.SleepSession
import com.opentasker.ui.charts.crosshairInput
import com.opentasker.ui.charts.rememberChartGestureModifier
import com.opentasker.ui.charts.rememberCrosshairState
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 睡眠 in full: last night's hypnogram, then every night on record beneath it.
 *
 * The card on the front page shows ONE night, because that is the question a front page answers.
 * This screen answers the other one — what the nights have been doing — and it is the reason the
 * card had to become tappable: it was the only card on the report with no way in, which made the
 * richest thing on the page look like a dead end.
 *
 * Nights before this band existed are the Hume band's and are labelled as such, one row at a time.
 * They are not merged into anything: each row is one night from one wrist.
 */
@Composable
fun HuaweiSleepDetailScreen(
    night: HuaweiSleepNight?,
    nights: List<SleepSession>,
    cutoverMs: Long?,
    contentPadding: PaddingValues,
    bounds: LongRange,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val crosshair = rememberCrosshairState()
    val viewport = remember(bounds, style.defaultSpanMs) {
        ChartViewport(
            initialEndMs = bounds.last.takeIf { it > 0 } ?: System.currentTimeMillis(),
            initialSpanMs = style.defaultSpanMs,
        )
    }
    val date = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val boundary = cutoverMs ?: Long.MAX_VALUE

    // The Huawei band's own stage numbers are not the shared ones — 1 light, 2 REM, 3 deep, 4 awake
    // here against '1' deep, '2' light, '3' REM, '5' awake there. HuaweiNights already translates
    // when it builds sessions, so these runs are ALREADY in the shared vocabulary and must not be
    // translated a second time. Mapping twice would swap deep with light and still draw a
    // convincing night, which is the worst kind of wrong.
    val allRuns = remember(nights) { nights.flatMap { it.runs } }

    val gestures = rememberChartGestureModifier(
        onZoom = { viewport.zoomAround(viewport.plotWidthPx / 2f, it, bounds) },
        onPan = { viewport.panBy(it.x, bounds) },
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(style.cardGap),
    ) {
        item("back") { TextButton(onClick = onBack) { Text(HuaweiText.back[lang]) } }

        // Every night on ONE hypnogram, over the shared viewport — so it pans and zooms like every
        // other chart on the report rather than being a fixed picture of one night.
        //
        // Drawing all the nights rather than only the latest is what makes scrolling worth doing: a
        // hypnogram of a single night with a movable window is mostly empty space. Every bar is a
        // real segment from one night; nothing is stitched across the gap between two.
        item("plot") {
            SectionCard(accent = ChartPalette.SLEEP_DEEP) {
                SectionTitle(HuaweiText.sleepPageTitle[lang], ChartPalette.SLEEP_DEEP)
                Hypnogram(
                    allRuns,
                    viewport,
                    crosshair,
                    Modifier
                        .fillMaxWidth()
                        .height(style.detailHeight)
                        .crosshairInput(crosshair, viewport)
                        .then(gestures),
                )
                NoteText(HuaweiText.sleepEveryNightNote[lang])
            }
        }

        item("head") {
            SectionCard(accent = ChartPalette.SLEEP_DEEP) {
                SectionTitle(HuaweiText.sleepEveryNight[lang], ChartPalette.SLEEP_DEEP)
                NoteText(HuaweiText.sleepEveryNightNote[lang])
            }
        }

        // Newest first: the question a reader arrives with is almost always about a recent night,
        // and making them scroll a month to reach it would be the wrong default.
        items(nights.reversed(), key = { it.startMs }) { session ->
            SectionCard(accent = ChartPalette.SLEEP_DEEP) {
                SectionTitle(date.format(java.util.Date(session.startMs)), ChartPalette.SLEEP_DEEP)
                NoteText(
                    "${HuaweiText.sleepBed[lang]} ${clock.format(java.util.Date(session.startMs))}" +
                        "   ${HuaweiText.sleepWoke[lang]} ${clock.format(java.util.Date(session.endMs))}" +
                        "   ${session.totalMinutes / 60}h ${session.totalMinutes % 60}m",
                )
                NoteText(
                    HuaweiText.sleepStages[lang].format(
                        session.deep, session.light, session.rem, session.awake,
                    ),
                )
                // Whose night this was, said on the row rather than inferred from its date. A reader
                // scrolling back should never have to work out where the handover fell.
                if (session.endMs < boundary) NoteText(HuaweiText.sleepFromHume[lang], warn = false)
            }
        }
    }
}
