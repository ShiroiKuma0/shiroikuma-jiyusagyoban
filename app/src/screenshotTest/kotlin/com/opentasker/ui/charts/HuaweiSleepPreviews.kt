package com.opentasker.ui.charts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.opentasker.core.huawei.HuaweiSleep
import com.opentasker.ui.charts.huawei.HuaweiSleepCard
import com.opentasker.ui.charts.huawei.HuaweiSleepNight
import com.opentasker.ui.theme.OpenTaskerTheme

/**
 * The hypnogram, rendered from 白い熊's real night of 2026-08-21.
 *
 * The fixture is the actual decoded night — the same one the decoder's tests check against a
 * photograph of the band's Sleep screen — rather than invented segments. A made-up night would have
 * tidy blocks and would not show what this card has to survive: an eighteen-segment night whose
 * shortest block is four minutes, with awake time hanging off both ends of the band's own span.
 *
 * Rendered because the phone is normally locked, so `adb shell screencap` returns the keyguard and
 * there is no other way to look at this.
 *
 * **`@PreviewTest` is not optional** — the engine discovers that annotation, not `@Preview`.
 */

/** The night, exactly as decoded: (minutes, stage) with 1 light, 2 REM, 3 deep, 4 awake. */
private val NIGHT = listOf(
    12 to 4, 11 to 1, 10 to 3, 18 to 1, 32 to 3, 8 to 1, 41 to 2, 34 to 1, 15 to 3,
    25 to 1, 9 to 2, 8 to 3, 17 to 1, 18 to 3, 28 to 1, 18 to 4, 16 to 1, 4 to 4,
)

/** 2026-08-21 23:55:00 local — the band's own bed time for this night. */
private const val BED_TIME = 1_787_349_300L

private fun nightFixture(): HuaweiSleepNight {
    var cursor = BED_TIME - 12 * 60      // the leading awake block sits BEFORE bed time
    val segments = NIGHT.map { (minutes, stage) ->
        HuaweiSleep.Segment(cursor, minutes * 60, HuaweiSleep.Stage.of(stage))
            .also { cursor += minutes * 60L }
    }
    val lastSleep = segments.last { it.stage != HuaweiSleep.Stage.AWAKE }
    return HuaweiSleepNight(HuaweiSleep.Session(BED_TIME, lastSleep.endSeconds, segments))
}

/**
 * The dashboard's own clock — 24 hours ending at 17:30 on the day after the night.
 *
 * Fixed rather than `now()`, because the whole point of this render is that the night sits at its
 * TRUE position on a shared axis rather than being stretched to fill the card. A viewport that moved
 * with the wall clock would slide the night sideways on every render and make each one a false diff.
 */
private const val VIEW_END_MS = 1_787_412_600_000L   // 2026-08-22 17:30 local

@Composable
private fun sharedViewport() = remember {
    ChartViewport(initialEndMs = VIEW_END_MS, initialSpanMs = 24 * 60 * 60 * 1000L)
}

@Composable
private fun Frame(content: @Composable () -> Unit) {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(12.dp)) { content() }
        }
    }
}

@PreviewTest
@Preview(name = "Huawei sleep — English", widthDp = 413, heightDp = 430, showBackground = true)
@Composable
fun HuaweiSleepCardEnglishPreview() {
    Frame {
        CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
            HuaweiSleepCard(nightFixture(), sharedViewport(), rememberCrosshairState())
        }
    }
}

@PreviewTest
@Preview(name = "Huawei sleep — 日本語", widthDp = 413, heightDp = 430, showBackground = true)
@Composable
fun HuaweiSleepCardJapanesePreview() {
    Frame {
        CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) {
            HuaweiSleepCard(nightFixture(), sharedViewport(), rememberCrosshairState())
        }
    }
}

/** The empty state, which is what every reader sees until the first sync brings a night. */
@PreviewTest
@Preview(name = "Huawei sleep — nothing yet", widthDp = 413, heightDp = 180, showBackground = true)
@Composable
fun HuaweiSleepCardEmptyPreview() {
    Frame {
        CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
            HuaweiSleepCard(null, sharedViewport(), rememberCrosshairState())
        }
    }
}
