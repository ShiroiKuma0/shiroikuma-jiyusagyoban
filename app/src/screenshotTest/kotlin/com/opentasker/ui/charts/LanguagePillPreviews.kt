package com.opentasker.ui.charts

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
import com.opentasker.core.band.BandHeadroom
import com.opentasker.core.band.BandStatus
import com.opentasker.core.band.BandSyncProgress
import com.opentasker.ui.theme.OpenTaskerTheme

/**
 * The 日本語／英語 pill, in the header it actually lives in.
 *
 * 白い熊 asked for it on the datetime's own line, to the right, with a little padding and a yellow
 * border (2026-08-20). All four of those are things you can only check by looking, and the phone is
 * normally locked — so this renders the REAL [SyncHeader] rather than a copy of its Row. A preview of
 * a reconstruction would agree with itself and tell us nothing about the screen.
 *
 * Both widths, because the pill lands on lines that are already full: on the folded cover panel the
 * sync line has 413 dp to fit "2026-08-20 07:14" plus a chip plus the sync button, and the report
 * header has to fit ← plus "Training and recovery" — the longest title of the nine — plus the chip
 * plus the ⓘ. Those two are where this change could wrap, so those two are what is rendered.
 *
 * **`@PreviewTest` is not optional** — the engine discovers that annotation, not `@Preview`.
 */

/** Fixed, never `now()`: a fixture that moves would make every later render a false diff. */
private const val LAST_SYNC_MS = 1_787_213_640_000L   // 2026-08-20 07:14 UTC

private fun statusFixture() = BandStatus(
    lastSuccessAtMillis = LAST_SYNC_MS,
    headroom = BandHeadroom(stream = "hrv", depthSec = 21 * 3600, measured = true),
    lostSec = 0,
    lostStreams = emptyList(),
    batteryPct = 76,
    batteryAtMillis = LAST_SYNC_MS,
)

@Composable
private fun HeaderFixture(lang: BandLanguage) {
    OpenTaskerTheme {
        CompositionLocalProvider(LocalBandLanguage provides lang) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(12.dp)) {
                    SyncHeader(
                        state = DashboardState(loading = false, status = statusFixture()),
                        progress = BandSyncProgress(),
                        onSync = {},
                        onSwitchLanguage = { null },
                    )
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "言語ピル folded", widthDp = 413, heightDp = 220, showBackground = true)
@Composable
fun LanguagePillFolded() = HeaderFixture(BandLanguage.EN)

@PreviewTest
@Preview(name = "言語ピル folded 日本語", widthDp = 413, heightDp = 220, showBackground = true)
@Composable
fun LanguagePillFoldedJa() = HeaderFixture(BandLanguage.JA)

@PreviewTest
@Preview(name = "言語ピル unfolded", widthDp = 916, heightDp = 220, showBackground = true)
@Composable
fun LanguagePillUnfolded() = HeaderFixture(BandLanguage.EN)

/**
 * The report header, with the longest title there is.
 *
 * `hasInfo = true` because that is the crowded case: Sleep and the line metrics carry the ⓘ, so the
 * row has to seat back-arrow, title, chip and circle at 413 dp.
 */
@Composable
private fun ReportHeaderFixture(lang: BandLanguage, title: Loc, hasInfo: Boolean) {
    OpenTaskerTheme {
        CompositionLocalProvider(LocalBandLanguage provides lang) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    DetailHeader(title[lang], hasInfo = hasInfo, onBack = {}, onInfo = {})
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "運動と回復の見出し folded", widthDp = 413, heightDp = 90, showBackground = true)
@Composable
fun RegisterHeaderFolded() = ReportHeaderFixture(BandLanguage.EN, BandText.registerTitle, hasInfo = false)

@PreviewTest
@Preview(name = "運動と回復の見出し folded 日本語", widthDp = 413, heightDp = 90, showBackground = true)
@Composable
fun RegisterHeaderFoldedJa() = ReportHeaderFixture(BandLanguage.JA, BandText.registerTitle, hasInfo = false)

@PreviewTest
@Preview(name = "睡眠の見出し folded", widthDp = 413, heightDp = 90, showBackground = true)
@Composable
fun SleepHeaderFolded() = ReportHeaderFixture(BandLanguage.EN, BandText.sleep, hasInfo = true)

@PreviewTest
@Preview(name = "睡眠の見出し folded 日本語", widthDp = 413, heightDp = 90, showBackground = true)
@Composable
fun SleepHeaderFoldedJa() = ReportHeaderFixture(BandLanguage.JA, BandText.sleep, hasInfo = true)
