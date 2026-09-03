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
import com.opentasker.core.band.RehabLog
import com.opentasker.ui.charts.huawei.HuaweiRehabCard
import com.opentasker.ui.charts.huawei.HuaweiRehabDayDialog
import com.opentasker.ui.charts.huawei.HuaweiRehabScreen
import com.opentasker.ui.charts.huawei.rehabCells
import com.opentasker.ui.theme.OpenTaskerTheme
import java.time.LocalDate
import java.time.ZoneId

/**
 * 機能訓練 — the two-week cut-out, the full calendar, and the day editor.
 *
 * Rendered because the whole design is a judgement about appearance: whether a full-yellow square
 * reads as "done" against the grid's ordinary empty tile, and whether two weeks of it are legible in
 * a card. Neither can be checked by reading the code, and 白い熊's phone is normally locked.
 *
 * **Dated in the past**, like the register's fixture: the grid rings TODAY, so a fixture anchored to
 * `now` would redraw its reference every midnight.
 *
 * **`@PreviewTest` is not optional** — the engine discovers that annotation, not `@Preview`.
 */
private val TODAY = LocalDate.of(2025, 12, 7)   // a Sunday, so the cut-out is two full rows

private fun keyOf(d: LocalDate): Long = RehabLog.dateKeyOf(d)

/** A month of days with rehab on most of them and two real gaps — the shape worth looking at. */
private val DONE: Set<Long> = buildSet {
    for (back in 0..40) {
        val d = TODAY.minusDays(back.toLong())
        // Two deliberate gaps: a single missed day, and a three-day run of them.
        if (back == 3) continue
        if (back in 9..11) continue
        if (back % 7 == 6) continue
        add(keyOf(d))
    }
}

private val NOTES = mapOf(
    keyOf(TODAY) to "full set, no pain",
    keyOf(TODAY.minusDays(4)) to "skipped the twists — back was tight",
    keyOf(TODAY.minusDays(12)) to "half a set only",
)

@Composable
private fun CardFrame() {
    val zone = ZoneId.systemDefault()
    val from = com.opentasker.ui.charts.huawei.rehabCutoutStart(TODAY).toEpochDay()
    val to = TODAY.toEpochDay()
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(12.dp)) {
                HuaweiRehabCard(
                    days = rehabCells(from, to, DONE, NOTES),
                    zone = zone,
                    doneCount = (from..to).count { keyOf(LocalDate.ofEpochDay(it)) in DONE },
                    totalDays = (to - from + 1).toInt(),
                    onTapDay = {},
                    onOpen = {},
                )
            }
        }
    }
}

@Composable
private fun PageFrame() {
    val zone = ZoneId.systemDefault()
    val from = TODAY.minusDays(41).toEpochDay()
    val to = TODAY.toEpochDay()
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            HuaweiRehabScreen(
                days = rehabCells(from, to, DONE, NOTES),
                zone = zone,
                doneCount = DONE.size,
                totalDays = (to - from + 1).toInt(),
                contentPadding = PaddingValues(10.dp),
                onTapDay = {},
                onBack = {},
            )
        }
    }
}

/** The card as it sits under 今朝の体感 — two rows, and the way to the full calendar. */
@PreviewTest
@Preview(name = "Rehab — card", widthDp = 413, heightDp = 460, fontScale = 1.3f, showBackground = true)
@Composable
fun HuaweiRehabCardPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) { CardFrame() }
}

@PreviewTest
@Preview(name = "Rehab — card 日本語", widthDp = 413, heightDp = 460, fontScale = 1.3f, showBackground = true)
@Composable
fun HuaweiRehabCardJaPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) { CardFrame() }
}

/** The full page: the register's own calendar, with a tick's colours instead of a rating's. */
@PreviewTest
@Preview(name = "Rehab — page", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiRehabPagePreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) { PageFrame() }
}

/** The editor behind every square: the day named, the tick, and its note. */
@PreviewTest
@Preview(name = "Rehab — day editor 日本語", widthDp = 413, heightDp = 420, showBackground = true)
@Composable
fun HuaweiRehabDialogPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) {
        OpenTaskerTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                HuaweiRehabDayDialog(
                    dateKey = keyOf(TODAY),
                    done = true,
                    note = NOTES[keyOf(TODAY)],
                    onPick = {},
                    onEditNote = {},
                    onDismiss = {},
                )
            }
        }
    }
}
