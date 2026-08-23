package com.opentasker.ui.charts.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.ChartViewport
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import com.opentasker.ui.charts.compare.CompareData.Result

/** 「バンド比較」 — the two bands, one metric per card, on one shared time axis. */
@Composable
fun CompareScreen(
    cards: List<CompareModel.Card>,
    viewport: ChartViewport,
    contentPadding: PaddingValues,
    loading: Boolean = false,
) {
    val lang = LocalBandLanguage.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard(accent = ChartPalette.AXIS_TEXT) {
                SectionTitle(CompareText.title[lang], ChartPalette.AXIS_TEXT)
                BodyText(CompareText.about[lang])
                NoteText(CompareText.neverPooled[lang])
            }
        }
        if (loading) {
            item { SectionCard(accent = ChartPalette.AXIS_TEXT) { BodyText(CompareText.loading[lang]) } }
        }
        items(cards, key = { it.row.huaweiKey }) { card ->
            when (val r = card.result) {
                is Result.Refused -> CompareRefusedCard(card.row.title[lang], r.refusal, card.row.color)
                is Result.Joined -> CompareCard(
                    title = card.row.title[lang],
                    unit = card.row.unit,
                    color = card.row.color,
                    join = r.join,
                    viewport = viewport,
                    footer = card.footer,
                    tier = card.tier,
                    format = { "%.${card.row.decimals}f".format(it) },
                )
            }
        }
    }
}
