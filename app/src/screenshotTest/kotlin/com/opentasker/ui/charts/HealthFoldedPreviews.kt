package com.opentasker.ui.charts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.opentasker.ui.theme.OpenTaskerTheme
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The 健康 screens at the width of the **folded** Mate XT cover panel.
 *
 * 白い熊's phone is normally locked, so `adb shell screencap` returns the keyguard and there is no
 * way to look at a running layout from this end. These previews are the substitute, and the folded
 * panel is the state that needed them: at 413 dp the day tables ran off the right edge, the scale
 * legend clipped to "1 Grea", and the 回復 card broke `usual 58–68` one character per line
 * (白い熊, 2026-08-18, three screenshots).
 *
 * `./gradlew updateDebugScreenshotTest` renders them under `app/src/screenshotTestDebug/reference/`.
 * Each screen is rendered at both widths so the narrow variant can be compared against the layout it
 * is a variant OF, rather than judged alone.
 *
 * **`@PreviewTest` is not optional** — the engine discovers that annotation and not `@Preview`; see
 * the note in `CLAUDE.md`.
 *
 * ## The widths
 *
 * Measured off 白い熊's own screenshots (1008 px panel, 34 dp tiles rendering 83 px → 2.4375 px/dp,
 * i.e. the 390 dpi override in `dumpsys`):
 *
 * | State | panel px | dp | `isNarrowScreen()` |
 * | --- | --- | --- | --- |
 * | folded cover | 1008 | **413** | true |
 * | semi-folded | 2048 | 840 | false |
 * | unfolded | 2232 | 916 | false |
 *
 * ## Why the fixture is anchored in the past
 *
 * The calendar rings today's tile, and it finds today from the clock. Anchored on the day these were
 * written the reference PNGs would carry that ring for one day and then differ from every later
 * render, so `validateDebugScreenshotTest` would report nine failures a day after and none of them
 * about the layout. A month back, no fixture day is today, the ring is simply absent, and a diff
 * means what it says. The layout is identical either way — the ring is a 1.5 dp border, not a
 * measurement — and the fixture dates are synthetic regardless.
 */

private val ANCHOR: LocalDate = LocalDate.of(2026, 7, 15)

private fun dayKey(d: LocalDate): Long =
    d.year * 10_000L + d.monthValue * 100L + d.dayOfMonth.toLong()

private fun msOf(d: LocalDate, hour: Int): Long =
    d.atStartOfDay(ZoneOffset.UTC).plusHours(hour.toLong()).toInstant().toEpochMilli()

private fun marker(
    m: RecoveryMarker,
    value: Double?,
    baseline: Double,
    lo: Double,
    hi: Double,
    band: RecoveryBand = RecoveryBand.USUAL,
    counted: Boolean = true,
) = MarkerReading(
    marker = m,
    value = value,
    baseline = baseline,
    usualLo = lo,
    usualHi = hi,
    z = value?.let { (it - baseline) / ((hi - lo) / 2.0) },
    band = band,
    counted = counted,
)

/** Last night, as the 回復 card receives it: four markers, none of them off. */
private fun recoveryFixture() = RecoveryResult(
    nightStartMs = msOf(ANCHOR.minusDays(1), 23),
    nightEndMs = msOf(ANCHOR, 8),
    markers = listOf(
        marker(RecoveryMarker.NOCTURNAL_HR, 67.0, 63.0, 58.0, 68.0),
        marker(RecoveryMarker.SLEEP, 523.0, 480.0, 326.0, 632.0),
        marker(RecoveryMarker.FELT, 3.0, 3.0, 2.9, 5.1),
        marker(RecoveryMarker.TEMPERATURE, 36.4, 36.4, 36.1, 36.7, counted = false),
    ),
    confidence = RecoveryConfidence.ESTABLISHED,
    nightsOfHistory = 17,
    adverseCount = 0,
    adverseMarkers = emptyList(),
    lateEffortMinutesBeforeSleep = null,
    illnessSigns = false,
)

/**
 * Seventeen nights ending at the anchor, scored the way 白い熊's own register is.
 *
 * Deliberately runs back over the 1 August boundary so the month rules have something to rule off,
 * and leaves 16 August unrated-but-recorded and 14 August recorded-with-no-night so both of the
 * table's "absent" shapes are on screen.
 */
private fun registerFixture(): SessionRegister.Register {
    val felt = mapOf(
        0 to 3, 1 to 2, 2 to 3, 3 to 2, 4 to 4, 5 to 3, 6 to 4,
        7 to 5, 8 to 4, 9 to 4, 11 to 2, 12 to 3, 13 to 2, 15 to 3, 16 to 2, 18 to 4, 20 to 3,
    )
    val nights = (0..20).mapNotNull { back ->
        if (back == 4) return@mapNotNull null // a morning with a rating and no recorded night
        val date = ANCHOR.minusDays(back.toLong())
        val hr = 62.0 + (back % 5)
        val asleep = 380.0 + (back * 17 % 220)
        SessionRegister.NightReading(
            startMs = msOf(date.minusDays(1), 23),
            endMs = msOf(date, 8),
            nocturnalHr = marker(RecoveryMarker.NOCTURNAL_HR, hr, 63.0, 58.0, 68.0),
            sleep = marker(RecoveryMarker.SLEEP, asleep, 480.0, 326.0, 632.0),
            felt = marker(RecoveryMarker.FELT, felt[back]?.toDouble(), 3.0, 2.9, 5.1),
            temperature = marker(
                RecoveryMarker.TEMPERATURE, 36.0 + (back % 6) / 10.0, 36.4, 36.1, 36.7,
                counted = false,
            ),
            adverseCount = if (back % 6 == 0) 1 else 0,
        )
    }
    val rows = (0..20).map { back ->
        val date = ANCHOR.minusDays(back.toLong())
        SessionRegister.NightRow(
            dateKey = dayKey(date),
            night = nights.firstOrNull { it.endMs == msOf(date, 8) },
            felt = felt[back],
        )
    }
    val from = RecoveryBuild.gridStart(ANCHOR.toEpochDay())
    val days = (from..ANCHOR.toEpochDay()).map { epochDay ->
        val back = (ANCHOR.toEpochDay() - epochDay).toInt()
        SessionRegister.DayCell(
            epochDay = epochDay,
            sessionLoad = if (back in listOf(2, 6, 9, 13)) 120.0 + back * 9 else null,
            adverseCount = if (back <= 20) 1 else null,
            felt = felt[back],
        )
    }
    return SessionRegister.Register(
        entries = emptyList(),
        days = days,
        nights = nights,
        rows = rows,
        contrast = null,
    )
}

/** Thirty days for the day-by-day table, crossing the 1 August boundary twice over. */
private fun daysFixture(): List<DaySummary> = (0..34).map { back ->
    val date = ANCHOR.minusDays(back.toLong())
    val sleep = 380 + (back * 23 % 200)
    DaySummary(
        date = date,
        restingHr = if (back == 5) null else 55.0 + (back % 7),
        sleepMinutes = if (back == 5) null else sleep,
        deepMinutes = if (back == 5) null else 60 + (back * 7 % 50),
        remMinutes = if (back == 5) null else 55 + (back * 11 % 40),
        steps = if (back == 5) 0 else 6_000 + (back * 811 % 9_000),
        spo2Low = if (back == 5) null else 93.0 + (back % 5),
        index = if (back == 5) {
            null
        } else {
            HealthIndexResult(
                value = 68 + (back * 13 % 25),
                components = emptyList(),
                partial = false,
                missing = emptyList(),
            )
        },
    )
}

@Composable
private fun Frame(lang: BandLanguage, content: @Composable () -> Unit) {
    OpenTaskerTheme {
        CompositionLocalProvider(LocalBandLanguage provides lang) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                content()
            }
        }
    }
}

@Composable
private fun RegisterFixture(lang: BandLanguage) = Frame(lang) {
    SessionRegisterScreen(
        register = registerFixture(),
        contentPadding = PaddingValues(0.dp),
        onRate = { _, _ -> },
        onBack = {},
    )
}

@Composable
private fun RecoveryFixture(lang: BandLanguage) = Frame(lang) {
    Column(Modifier.padding(12.dp)) {
        RecoveryCard(
            recovery = recoveryFixture(),
            load = RecoveryBuild.LoadReading(
                weekly = 812.0,
                weeklyFromSessions = 260.0,
                sessionsThisWeek = 2,
                sessionOpen = false,
                ratio = 1.08,
                band = LoadBand.PRODUCTIVE,
                daysOfHistory = 34,
            ),
            sri = 74.0,
            sleepScore = SleepScore.Breakdown(
                total = 80,
                duration = 50.0,
                consistency = 18.0,
                interruptions = 12.0,
                onsetDeviationMinutes = 41.0,
            ),
            peak30Cadence = 118.0,
            peakCadenceDay = ANCHOR.minusDays(1).toEpochDay(),
            awakeMinutes = 35,
            regime = null,
            feltToday = 3,
            feltNight = dayKey(ANCHOR),
            recordedNight = dayKey(ANCHOR),
            feltEnabled = true,
            onFelt = {},
            registerNights = 21,
            registerRated = 17,
            onOpenRegister = {},
            onClick = {},
        )
    }
}

@Composable
private fun DaysFixture(lang: BandLanguage) = Frame(lang) {
    Column(Modifier.padding(12.dp)) { DailySummaryCard(daysFixture()) }
}

/**
 * The card at the top of the dashboard — computed by the real scorer, not hand-built, so the
 * component rows carry the labels and breakpoints they will carry on the phone.
 */
@Composable
private fun IndexFixture(lang: BandLanguage) = Frame(lang) {
    Column(Modifier.padding(12.dp)) {
        HealthIndexCard(
            HealthIndex.compute(
                HealthIndexInputs(
                    restingHr = 57.0,
                    hrIqr = 6.0,
                    spo2Low = 94.0,
                    sleepMinutes = 523,
                    deepRemShare = 0.34,
                    steps = 11_400.0,
                ),
            ),
        ) {}
    }
}

// --- folded cover panel, 413 dp -----------------------------------------------------------------

@PreviewTest
@Preview(name = "運動と回復 folded", widthDp = 413, heightDp = 2100, showBackground = true)
@Composable
fun RegisterFolded() = RegisterFixture(BandLanguage.EN)

@PreviewTest
@Preview(name = "運動と回復 folded 日本語", widthDp = 413, heightDp = 2100, showBackground = true)
@Composable
fun RegisterFoldedJa() = RegisterFixture(BandLanguage.JA)

@PreviewTest
@Preview(name = "回復 folded", widthDp = 413, heightDp = 1200, showBackground = true)
@Composable
fun RecoveryFolded() = RecoveryFixture(BandLanguage.EN)

@PreviewTest
@Preview(name = "回復 folded 日本語", widthDp = 413, heightDp = 1200, showBackground = true)
@Composable
fun RecoveryFoldedJa() = RecoveryFixture(BandLanguage.JA)

@PreviewTest
@Preview(name = "健康指数 folded", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun IndexFolded() = IndexFixture(BandLanguage.EN)

@PreviewTest
@Preview(name = "健康指数 folded 日本語", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun IndexFoldedJa() = IndexFixture(BandLanguage.JA)

@PreviewTest
@Preview(name = "日ごと folded", widthDp = 413, heightDp = 1800, showBackground = true)
@Composable
fun DaysFolded() = DaysFixture(BandLanguage.EN)

@PreviewTest
@Preview(name = "日ごと folded 日本語", widthDp = 413, heightDp = 1800, showBackground = true)
@Composable
fun DaysFoldedJa() = DaysFixture(BandLanguage.JA)

// --- unfolded panel, 916 dp ---------------------------------------------------------------------

@PreviewTest
@Preview(name = "運動と回復 unfolded", widthDp = 916, heightDp = 1900, showBackground = true)
@Composable
fun RegisterUnfolded() = RegisterFixture(BandLanguage.EN)

@PreviewTest
@Preview(name = "回復 unfolded", widthDp = 916, heightDp = 1000, showBackground = true)
@Composable
fun RecoveryUnfolded() = RecoveryFixture(BandLanguage.EN)

@PreviewTest
@Preview(name = "日ごと unfolded", widthDp = 916, heightDp = 1700, showBackground = true)
@Composable
fun DaysUnfolded() = DaysFixture(BandLanguage.EN)
