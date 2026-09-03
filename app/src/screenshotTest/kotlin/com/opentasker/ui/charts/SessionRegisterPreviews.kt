package com.opentasker.ui.charts

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.opentasker.ui.theme.OpenTaskerTheme

/**
 * 「あらゆる夜と運動」 — the calendar and the night table, with notes on them.
 *
 * The question 白い熊 asked this feature to answer is "which days did I write something about", and
 * it is answered by LOOKING: a dot in the corner of a tile, and a margin line under a table row.
 * Neither can be checked by reading the code — the dot has to be visible on all five step colours
 * and on an empty tile, and the margin line has to read as an annotation rather than as a sixth
 * column. So both are rendered here, which is also the only way to see them at all while 白い熊's
 * phone is locked and `screencap` returns the keyguard.
 *
 * **Deliberately dated in the past.** The grid rings TODAY, and a fixture anchored to `now` would
 * redraw its reference PNG every midnight and fail `validateDebugScreenshotTest` on a feature nobody
 * touched.
 *
 * **`@PreviewTest` is not optional** — the engine discovers that annotation, not `@Preview`.
 */

/**
 * A Monday, so the grid's first column really is the first cell and the week rules land where they
 * should — and one far enough in the past that the window cannot contain TODAY. The grid rings
 * today, so a fixture straddling it would redraw this reference every midnight and fail
 * `validateDebugScreenshotTest` on a feature nobody had touched.
 */
private const val FIRST_DAY = 20_402L // 2025-11-10, a Monday; the window ends 2025-12-07

private fun marker(
    marker: RecoveryMarker,
    value: Double,
    band: RecoveryBand,
    counted: Boolean,
) = MarkerReading(marker, value, value, value - 3.0, value + 3.0, 0.0, band, counted)

private fun night(dayIndex: Int, hr: Double, sleepMinutes: Double) = SessionRegister.NightReading(
    startMs = (FIRST_DAY + dayIndex) * 86_400_000L + 23 * 3_600_000L,
    endMs = (FIRST_DAY + dayIndex + 1) * 86_400_000L + 7 * 3_600_000L,
    nocturnalHr = marker(RecoveryMarker.NOCTURNAL_HR, hr, RecoveryBand.USUAL, true),
    sleep = marker(RecoveryMarker.SLEEP, sleepMinutes, RecoveryBand.USUAL, true),
    felt = marker(RecoveryMarker.FELT, 3.0, RecoveryBand.USUAL, true),
    temperature = marker(RecoveryMarker.TEMPERATURE, 36.4, RecoveryBand.USUAL, false),
    adverseCount = 0,
    // The five ungraded readings, varied per night so the columns are not one repeated value — and
    // one night short of every one of them, because a missing reading is the state the dash exists
    // for and the one worth looking at.
    deepMinutes = if (dayIndex % 7 == 3) null else 62.0 + (dayIndex % 5) * 11,
    deepRemShare = if (dayIndex % 7 == 3) null else 0.34 + (dayIndex % 4) * 0.04,
    lowestHr = if (dayIndex % 7 == 3) null else hr - 9 - (dayIndex % 3),
    spo2 = if (dayIndex % 7 == 3) null else 95.0 + (dayIndex % 3),
    hrvMs = if (dayIndex % 7 == 3) null else 31.0 + (dayIndex % 6) * 4,
    // The within-person steps, walked across all five so the render shows every fill this column
    // can take rather than whichever one the fixture's arithmetic happens to land on.
    deepStep = if (dayIndex % 7 == 3) null else 1 + (dayIndex % 5),
    deepRemStep = if (dayIndex % 7 == 3) null else 1 + ((dayIndex + 2) % 5),
    hrvStep = if (dayIndex % 7 == 3) null else 1 + ((dayIndex + 4) % 5),
)

/** `yyyyMMdd` for a day index into the fixture window. */
private fun keyOf(dayIndex: Int): Long =
    SessionRegister.dateKeyOf(FIRST_DAY + dayIndex)

/**
 * A month of days: a rating on most, a note on a scattered few, one of them unrated.
 *
 * The notes are spread across DIFFERENT step colours on purpose — the dot is drawn in the tile's own
 * ink, which is the one foreground each fill is chosen to carry, and this is the render that shows
 * whether that holds on the yellow, the blue and the dark red alike.
 */
private val NOTED = mapOf(
    2 to "woke at 03:00 and did not get back down",
    5 to "cat",
    9 to "second night after the flight — flat all day",
    13 to "no band, forgot to charge it",
    20 to "best in weeks",
)

private val FELT = mapOf(
    0 to 3, 1 to 2, 2 to 4, 3 to 3, 4 to 2, 5 to 5, 6 to 3,
    7 to 2, 8 to 3, 9 to 4, 10 to 3, 11 to 2, 12 to 1, 14 to 3,
    15 to 4, 16 to 3, 17 to 2, 18 to 3, 19 to 3, 20 to 1, 21 to 2,
)

private val REGISTER = SessionRegister.Register(
    entries = emptyList(),
    days = (0..27).map { i ->
        SessionRegister.DayCell(
            epochDay = FIRST_DAY + i,
            sessionLoad = if (i % 6 == 4) 40.0 + i else null,
            adverseCount = if (FELT[i] != null) 0 else null,
            felt = FELT[i],
            hasNote = NOTED.containsKey(i),
        )
    },
    nights = emptyList(),
    rows = (21 downTo 0).map { i ->
        SessionRegister.NightRow(
            dateKey = keyOf(i),
            // The NEWEST row is 白い熊's real 08-26: 13h34 asleep and a +17 delta on the heart rate —
            // the two widest values this table has ever had to hold, and the pair that wrapped. They
            // sit first so every render CHECKS the column width instead of estimating it; buried
            // fifteen rows down they fell outside the preview's own height and proved nothing.
            night = when (i) {
                13 -> null
                21 -> night(20, 84.0, 814.0).let {
                    it.copy(
                        nocturnalHr = marker(RecoveryMarker.NOCTURNAL_HR, 84.0, RecoveryBand.HIGH, true)
                            .copy(baseline = 67.0),
                    )
                }
                else -> night(i - 1, 58.0 + (i % 5), 430.0 + (i % 7) * 12)
            },
            felt = FELT[i],
            note = NOTED[i],
        )
    },
    contrast = null,
)

@Composable
private fun Frame() {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SessionRegisterScreen(
                register = REGISTER,
                contentPadding = PaddingValues(10.dp),
                onRate = { _, _ -> },
                onNote = { _, _ -> },
                onBack = {},
            )
        }
    }
}

/**
 * The folded panel — 413 dp, which is what 白い熊 actually reads this on most of the time.
 *
 * The nine-column table does NOT fit here and is not meant to: it scrolls sideways, and what this
 * render is for is the part that is visible without scrolling. The date and the rating have to be in
 * it, because those are what a line is found by.
 */
@PreviewTest
@Preview(name = "Register — notes", widthDp = 413, heightDp = 1200, showBackground = true)
@Composable
fun SessionRegisterNotesPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) { Frame() }
}

/**
 * The unfolded panel — 916 dp, where the whole table fits at once and every column can be checked.
 *
 * Worth its own render rather than trusting the narrow one: the columns are fixed widths now, so
 * "does the content fit the column" is a question with one answer per column, and this is the only
 * view that asks it of all nine.
 */
@PreviewTest
@Preview(name = "Register — table unfolded", widthDp = 916, heightDp = 1000, showBackground = true)
@Composable
fun SessionRegisterTableUnfoldedPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) { Frame() }
}

/**
 * The same table at **fontScale 1.3**, which is 白い熊's own setting.
 *
 * The preview NAME carries no decimal point on purpose: the screenshot engine names its reference
 * file by splitting on the first `.`, so "table at 1.3" wrote itself out as `3_<hash>_0.png`.
 *
 * This is the render that matters for "does every cell fit on one line". The columns were first
 * sized against the default 1.0 and every value then had 30 % more type to fit than the width
 * allowed, which is how `13h34` came to wrap to `13h3` over `4` and `84 +17` to two lines. A width
 * checked at 1.0 is not checked.
 */
@PreviewTest
@Preview(
    name = "Register — table big type",
    widthDp = 916,
    heightDp = 1000,
    fontScale = 1.3f,
    showBackground = true,
)
@Composable
fun SessionRegisterTableFontScalePreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) { Frame() }
}

@PreviewTest
@Preview(
    name = "Register — table big type 日本語",
    widthDp = 916,
    heightDp = 1000,
    fontScale = 1.3f,
    showBackground = true,
)
@Composable
fun SessionRegisterTableFontScaleJaPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) { Frame() }
}

@PreviewTest
@Preview(name = "Register — table unfolded 日本語", widthDp = 916, heightDp = 1000, showBackground = true)
@Composable
fun SessionRegisterTableUnfoldedJaPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) { Frame() }
}

@PreviewTest
@Preview(name = "Register — notes 日本語", widthDp = 413, heightDp = 1200, showBackground = true)
@Composable
fun SessionRegisterNotesJaPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) { Frame() }
}
