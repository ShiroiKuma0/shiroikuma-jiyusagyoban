package com.opentasker.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.opentasker.ui.charts.huawei.HuaweiMorningCard
import com.opentasker.ui.theme.OpenTaskerTheme

/**
 * 「平常との差」 — rendered because an instruction about PROMINENCE cannot be checked by reading code.
 *
 * 白い熊 asked for these indicators at the top of the report and featured, having found them fifth on
 * the page. So the previews below draw the strip **with the morning card above it**, in the order the
 * board now uses: the question that must be answered, then the numbers that answer to it. Drawing the
 * strip alone would show whether it is handsome and not whether it is findable, which is the thing
 * that was actually wrong.
 *
 * The fixtures are 白い熊's real nights of 2026-09-10 and 2026-09-11 — see [RecoverySicknessTest] for
 * the same numbers as assertions.
 */
private fun reading(
    marker: RecoveryMarker,
    value: Double,
    history: List<Double>,
    meaningful: Double,
    counted: Boolean = false,
) = Recovery.band(
    marker, value, history, meaningful, RecoveryConfidence.ESTABLISHED, counted,
)

/** The night of 2026-09-10 → 11: 10.6 h asleep, to bed at 70 bpm. The pair that fires the flag. */
private fun theNight(): RecoveryResult = Recovery.assemble(
    nightStartMs = BED_0910,
    nightEndMs = WAKE_0911,
    nocturnalHr = Recovery.bandNocturnalHr(
        66.8, listOf(62.2, 77.8, 64.2, 69.8, 72.0, 63.3, 66.0), RecoveryConfidence.ESTABLISHED,
    ),
    sleep = reading(
        RecoveryMarker.SLEEP, 636.0,
        listOf(432.0, 474.0, 534.0, 192.0, 432.0, 582.0, 442.0),
        Recovery.SLEEP_MEANINGFUL_MIN, counted = true,
    ),
    felt = reading(
        RecoveryMarker.FELT, 3.0, listOf(1.0, 2.0, 3.0, 2.0, 2.0, 2.0, 3.0),
        Recovery.FELT_MEANINGFUL_STEPS, counted = true,
    ),
    temperature = reading(
        RecoveryMarker.TEMPERATURE, 0.0, emptyList(), Recovery.TEMP_MEANINGFUL_C,
    ),
    temperatureSustained = false,
    lateEffortMinutesBeforeSleep = null,
    nightsOfHistory = 20,
    // The REAL going-to-bed figures, medians of the first hour after onset. The first pass used
    // 70 against a 78 baseline — numbers lifted off the 22:00 clock hour of the following night.
    bedtimeHr = reading(
        RecoveryMarker.BEDTIME_HR, 74.0, listOf(77.0, 76.5, 70.0, 74.0, 76.0, 79.0, 73.0),
        Recovery.HR_MEANINGFUL_BPM,
    ),
    respiration = reading(
        RecoveryMarker.RESPIRATION, 16.4, listOf(15.9, 15.6, 16.0, 15.7, 15.8, 16.1, 15.9),
        Recovery.RESPIRATION_MEANINGFUL_BPM,
    ),
    deep = reading(
        RecoveryMarker.DEEP, 209.0, listOf(115.0, 171.0, 190.0, 62.0, 170.0, 228.0, 134.0),
        Recovery.DEEP_MEANINGFUL_MIN,
    ),
    hrv = reading(
        RecoveryMarker.HRV, 27.3, listOf(26.3, 33.7, 20.3, 25.6, 30.9, 21.7, 25.4),
        Recovery.HRV_MEANINGFUL_MS,
    ),
    hrSwing = reading(
        RecoveryMarker.HR_SWING, 13.5, listOf(8.5, 12.5, 16.0, 17.0, 11.0, 15.0, 14.0),
        Recovery.HR_MEANINGFUL_BPM,
    ),
    hrvRunNights = 3,
)

/**
 * The night AFTER it began — 2026-09-12, the flattest on record and the run's fourth night.
 *
 * The one that shows both new things doing their job: a 6.0 bpm swing well outside its band, and the
 * HF run reaching the length that earns a sentence.
 */
private fun theNightAfter(): RecoveryResult = Recovery.assemble(
    nightStartMs = BED_0911,
    nightEndMs = WAKE_0912,
    nocturnalHr = Recovery.bandNocturnalHr(
        63.0, listOf(77.8, 64.2, 69.8, 72.0, 63.3, 66.0, 66.8), RecoveryConfidence.ESTABLISHED,
    ),
    sleep = reading(
        RecoveryMarker.SLEEP, 383.0,
        listOf(474.0, 534.0, 192.0, 432.0, 582.0, 442.0, 636.0),
        Recovery.SLEEP_MEANINGFUL_MIN, counted = true,
    ),
    felt = reading(
        RecoveryMarker.FELT, 2.0, listOf(1.0, 2.0, 3.0, 2.0, 2.0, 2.0, 3.0),
        Recovery.FELT_MEANINGFUL_STEPS, counted = true,
    ),
    temperature = reading(
        RecoveryMarker.TEMPERATURE, 0.0, emptyList(), Recovery.TEMP_MEANINGFUL_C,
    ),
    temperatureSustained = false,
    lateEffortMinutesBeforeSleep = null,
    nightsOfHistory = 20,
    bedtimeHr = reading(
        RecoveryMarker.BEDTIME_HR, 64.0, listOf(76.5, 70.0, 74.0, 76.0, 79.0, 73.0, 74.0),
        Recovery.HR_MEANINGFUL_BPM,
    ),
    respiration = reading(
        RecoveryMarker.RESPIRATION, 15.9, listOf(15.9, 15.6, 16.0, 15.7, 15.8, 16.1, 15.9),
        Recovery.RESPIRATION_MEANINGFUL_BPM,
    ),
    deep = reading(
        RecoveryMarker.DEEP, 54.0, listOf(171.0, 190.0, 62.0, 170.0, 228.0, 134.0, 209.0),
        Recovery.DEEP_MEANINGFUL_MIN,
    ),
    hrv = reading(
        RecoveryMarker.HRV, 27.0, listOf(33.7, 20.3, 25.6, 30.9, 21.7, 25.4, 27.3),
        Recovery.HRV_MEANINGFUL_MS,
    ),
    hrSwing = reading(
        RecoveryMarker.HR_SWING, 6.0, listOf(12.5, 16.0, 17.0, 11.0, 15.0, 14.0, 13.5),
        Recovery.HR_MEANINGFUL_BPM,
    ),
    hrvRunNights = 4,
)

/** An unremarkable night, so the quiet state can be looked at beside the loud one. */
private fun ordinaryNight(): RecoveryResult = Recovery.assemble(
    nightStartMs = BED_0910,
    nightEndMs = WAKE_0911,
    nocturnalHr = Recovery.bandNocturnalHr(
        64.0, listOf(62.2, 77.8, 64.2, 69.8, 72.0, 63.3, 66.0), RecoveryConfidence.ESTABLISHED,
    ),
    sleep = reading(
        RecoveryMarker.SLEEP, 452.0,
        listOf(432.0, 474.0, 534.0, 192.0, 432.0, 582.0, 442.0),
        Recovery.SLEEP_MEANINGFUL_MIN, counted = true,
    ),
    felt = reading(
        RecoveryMarker.FELT, 2.0, listOf(1.0, 2.0, 3.0, 2.0, 2.0, 2.0, 3.0),
        Recovery.FELT_MEANINGFUL_STEPS, counted = true,
    ),
    temperature = reading(
        RecoveryMarker.TEMPERATURE, 0.0, emptyList(), Recovery.TEMP_MEANINGFUL_C,
    ),
    temperatureSustained = false,
    lateEffortMinutesBeforeSleep = null,
    nightsOfHistory = 20,
    bedtimeHr = reading(
        RecoveryMarker.BEDTIME_HR, 76.0, listOf(77.0, 76.5, 70.0, 74.0, 76.0, 79.0, 73.0),
        Recovery.HR_MEANINGFUL_BPM,
    ),
    respiration = reading(
        RecoveryMarker.RESPIRATION, 15.8, listOf(15.9, 15.6, 16.0, 15.7, 15.8, 16.1, 15.9),
        Recovery.RESPIRATION_MEANINGFUL_BPM,
    ),
    deep = reading(
        RecoveryMarker.DEEP, 168.0, listOf(115.0, 171.0, 190.0, 62.0, 170.0, 228.0, 134.0),
        Recovery.DEEP_MEANINGFUL_MIN,
    ),
    hrv = reading(
        RecoveryMarker.HRV, 25.9, listOf(26.3, 33.7, 20.3, 25.6, 30.9, 21.7, 25.4),
        Recovery.HRV_MEANINGFUL_MS,
    ),
    hrSwing = reading(
        RecoveryMarker.HR_SWING, 14.0, listOf(8.5, 12.5, 16.0, 17.0, 11.0, 15.0, 14.5),
        Recovery.HR_MEANINGFUL_BPM,
    ),
)

/**
 * The two nights' real heart-rate curves, twelve medians each, straight out of the archive.
 *
 * 2026-09-11 swings 13.5 bpm and descends; 2026-09-12 swings 6.0, the flattest night on record. They
 * are here so the sparkline can be looked at doing the one thing it exists for — making those two
 * distinguishable at a glance, which no scalar on the card does.
 */
/**
 * The real bed and wake instants, so the x axis carries actual clock times.
 *
 * 白い熊 went to bed at 20:38 on the 10th and woke at 07:14; the next night ran 23:58 to 06:21. A
 * chart drawn from an epoch of zero would label its axis 01:00 and look entirely convincing.
 */
private val BED_0910 = java.time.LocalDateTime.of(2026, 9, 10, 20, 38)
    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
private val WAKE_0911 = java.time.LocalDateTime.of(2026, 9, 11, 7, 14)
    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
private val BED_0911 = java.time.LocalDateTime.of(2026, 9, 11, 23, 58)
    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
private val WAKE_0912 = java.time.LocalDateTime.of(2026, 9, 12, 6, 21)
    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * The real descents — bed-time level to the night's floor, from the archive.
 *
 * Usually 76 → 62; the night before the illness 74 → 65; the flat night 64 → 62. The three read
 * quite differently and a swing alone reports none of the difference, which is the reason 白い熊
 * asked for "the usual drop from where".
 */
private val CURVE_0911 = listOf(74.0, 78.5, 76.0, 76.5, 76.0, 72.0, 71.0, 71.0, 75.0, 67.0, 68.0, 65.0)
private val CURVE_0912 = listOf(64.0, 70.0, 69.0, 66.0, 65.0, 64.0, 68.0, 64.0, 68.0, 64.0, 65.5, 68.5)

private val USUAL = MarkerHistory.Descent(75.0, 63.0)
// First bin to lowest bin of the curves below — the same basis the app now uses, so the sentence
// and the picture above it cannot disagree.
private val DESCENT_0911 = MarkerHistory.DescentComparison(
    MarkerHistory.Descent(CURVE_0911.first(), CURVE_0911.min()), USUAL,
)
private val DESCENT_0912 = MarkerHistory.DescentComparison(
    MarkerHistory.Descent(CURVE_0912.first(), CURVE_0912.min()), USUAL,
)
private val BEST_DESCENT = MarkerHistory.Descent(78.0, 60.0)

/** The top of the report, in the order the board draws it. */
@Composable
private fun TopOfReport(
    recovery: RecoveryResult,
    curve: List<Double> = CURVE_0911,
    descent: MarkerHistory.DescentComparison? = DESCENT_0911,
) {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HuaweiMorningCard(
                    felt = 3,
                    nightLabel = "20260911",
                    onFelt = {},
                    note = null,
                    onNote = {},
                    nights = 48,
                    rated = 15,
                    onOpenRegister = {},
                )
                DeviationStrip(
                    recovery,
                    hrCurve = curve,
                    descent = descent,
                    bestDescent = BEST_DESCENT,
                    onOpenMarker = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "Top of report — the night it fires", widthDp = 413, heightDp = 1960, showBackground = true)
@Composable
fun DeviationStripFiringPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) { TopOfReport(theNight()) }
}

@PreviewTest
@Preview(name = "Top of report — 日本語", widthDp = 413, heightDp = 1960, showBackground = true)
@Composable
fun DeviationStripFiringJaPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) { TopOfReport(theNight()) }
}

@PreviewTest
@Preview(name = "Top of report — an ordinary night", widthDp = 413, heightDp = 1660, showBackground = true)
@Composable
fun DeviationStripQuietPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        TopOfReport(ordinaryNight())
    }
}

/** The flat night, and the run at its fourth: both of the things added on 2026-09-12, together. */
@PreviewTest
@Preview(name = "Top of report — the flat night", widthDp = 413, heightDp = 1900, showBackground = true)
@Composable
fun DeviationStripFlatNightPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        TopOfReport(theNightAfter(), CURVE_0912, DESCENT_0912)
    }
}

/**
 * The page behind a row — 白い熊 asked for every item to open to its own history (2026-09-13).
 *
 * Drawn with the real nightly swings so the chart, the usual band and the "best nights" list are all
 * showing something that happened rather than a shape invented to flatter the layout.
 */
private val SWING_NIGHTS = listOf(
    16.0, 12.5, 14.0, 10.0, 12.0, 6.5, 9.5, 9.5, 9.5, 14.5, 16.5,
    14.0, 14.0, 8.5, 12.5, 16.0, 17.0, 11.0, 15.0, 13.5, 6.0,
)

@PreviewTest
@Preview(name = "A row's history page", widthDp = 413, heightDp = 1700, showBackground = true)
@Composable
fun MarkerHistoryPreview() {
    val day = 86_400_000L
    val end = java.time.LocalDateTime.of(2026, 9, 12, 6, 21)
        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    val nights = SWING_NIGHTS.mapIndexed { i, v ->
        MarkerHistory.Night(end - (SWING_NIGHTS.lastIndex - i) * day, v)
    }
    val series = MarkerHistory.Series(
        marker = RecoveryMarker.HR_SWING,
        nights = nights,
        latest = Recovery.band(
            RecoveryMarker.HR_SWING, 6.0, SWING_NIGHTS.dropLast(1).takeLast(7),
            Recovery.HR_MEANINGFUL_BPM, RecoveryConfidence.ESTABLISHED, counted = false,
        ),
    )
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        OpenTaskerTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                MarkerHistoryScreen(
                    series = series,
                    zone = java.time.ZoneId.systemDefault(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    onBack = {},
                    extra = {
                        RecentCurvesCard(
                            listOf(
                                (end - 4 * day) to listOf(78.0, 76.0, 74.0, 70.0, 68.0, 66.0, 64.0, 63.0, 64.0, 66.0, 69.0, 72.0),
                                (end - 3 * day) to listOf(75.0, 74.0, 71.0, 68.0, 66.0, 64.0, 65.0, 64.0, 66.0, 68.0, 70.0, 73.0),
                                (end - 2 * day) to listOf(80.0, 77.0, 75.0, 72.0, 70.0, 67.0, 66.0, 65.0, 67.0, 70.0, 73.0, 76.0),
                                (end - day) to CURVE_0911,
                                end to CURVE_0912,
                            ),
                            java.time.ZoneId.systemDefault(),
                        )
                    },
                )
            }
        }
    }
}
