package com.opentasker.ui.charts

import android.content.res.Configuration
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
import com.opentasker.ui.charts.compare.CompareData
import com.opentasker.ui.charts.compare.CompareModel
import com.opentasker.ui.charts.compare.CompareScreen
import com.opentasker.ui.charts.compare.CompareStats
import com.opentasker.ui.charts.compare.CompareTier
import com.opentasker.ui.theme.OpenTaskerTheme
import kotlin.math.sin

/**
 * 「バンド比較」, rendered without either band.
 *
 * The phone is normally locked, so a render is the only way this layout gets looked at before it
 * ships — and this one has to be looked at, because everything it claims is visual: that the two
 * tracks read as two devices, that the fill distinguishes them, and that the rail's direction says
 * which band a lone reading came from.
 */
private const val T0 = 1_787_400_000_000L

private fun cells(n: Int, bothEvery: Int, base: Double, spread: Double): List<CompareData.Cell> =
    (0 until n).map { i ->
        val t = T0 + i * 60_000L
        val hw = base + sin(i / 7.0) * spread
        // Every nth cell is paired; the rest belong to one band only, which is the ordinary case and
        // the one a preview must show rather than hide.
        when {
            i % bothEvery == 0 -> CompareData.Cell(t, hw, hw - 2.0 + sin(i / 3.0))
            i % 3 == 0 -> CompareData.Cell(t, hw, null)
            else -> CompareData.Cell(t, null, hw - 1.5)
        }
    }

private fun join(cells: List<CompareData.Cell>) = CompareData.Join(
    grain = CompareData.Grain.MINUTE,
    cells = cells,
    huaweiSamples = cells.count { it.huawei != null },
    humeSamples = cells.count { it.hume != null },
    both = cells.count { it.hasBoth },
    huaweiOnly = cells.count { it.huawei != null && it.hume == null },
    humeOnly = cells.count { it.hume != null && it.huawei == null },
)

private fun card(row: CompareModel.Row, cells: List<CompareData.Cell>): CompareModel.Card {
    val j = join(cells)
    val d = CompareStats.delta(j, row.threshold)
    return CompareModel.Card(
        row = row,
        result = CompareData.Result.Joined(j),
        footer = CompareStats.footer(j, d, row.unit, "40–180 ${row.unit}", null),
        tier = CompareTier.of(j, row.provisional),
    )
}

private fun sample(): List<CompareModel.Card> = listOf(
    card(CompareModel.ROWS[0], cells(180, 4, 30.0, 25.0)),
    card(CompareModel.ROWS[1], cells(180, 3, 72.0, 12.0)),
)

@Composable
private fun Frame(cards: List<CompareModel.Card>) {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CompareScreen(
                cards = cards,
                viewport = ChartViewport(T0 + 180 * 60_000L, 180 * 60_000L),
                contentPadding = PaddingValues(10.dp),
            )
        }
    }
}

@PreviewTest
@Preview(name = "Compare — twin track", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun CompareTwinTrackPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) { Frame(sample()) }
}

@PreviewTest
@Preview(name = "Compare — 日本語", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun CompareJapanesePreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) { Frame(sample()) }
}

/** A refusal must be as legible as a comparison — a reader who misses it assumes it was answered. */
@PreviewTest
@Preview(name = "Compare — refused", widthDp = 413, heightDp = 520, showBackground = true)
@Composable
fun CompareRefusedPreview() {
    val row = CompareModel.ROWS[0]
    val refusal = CompareData.Refusal(
        row.huaweiKey,
        CompareData.Refusal.Reason.ZERO_CONVENTION,
        "one band records a zero and the other omits it, so a missing minute means different " +
            "things on each — compare at ten minutes instead, where each device's own total is used",
    )
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(listOf(CompareModel.Card(row, CompareData.Result.Refused(refusal), emptyList(), CompareTier.REFUSED)))
    }
}
