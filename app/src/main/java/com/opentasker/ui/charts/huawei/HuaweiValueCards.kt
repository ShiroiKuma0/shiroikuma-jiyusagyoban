package com.opentasker.ui.charts.huawei

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentasker.ui.charts.ChartCard
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.InfoBody
import com.opentasker.ui.charts.InfoHeading
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.charts.MetricSpec
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Resting heart rate and arterial stiffness as one bar per reading (白い熊, 2026-10-23), in the
 * blood-pressure chart's layout. Both change slowly and are read a few times a week at most, so a
 * time axis drew a flat line across a day (resting heart rate) or nothing at all (a single stiffness
 * measurement outside the window).
 */
private object ValueText {
    val readings = Loc("Readings", "測定一覧")
    val count = Loc("%d readings since %s", "%2\$s から %1\$d 件")
}

private val WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Composable
fun HuaweiValueCard(
    spec: MetricSpec,
    values: List<HuaweiDayValue>,
    scale: ValueBarsScale,
    onClick: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val zone = remember { ZoneId.systemDefault() }
    val oldestFirst = remember(values) { values.sortedBy { it.tMs } }
    val latest = oldestFirst.lastOrNull() ?: return
    ChartCard(
        title = spec.label[lang],
        // The unit is ChartCard's own second text; written into the headline too it printed twice.
        headline = scale.format(latest.value),
        unit = spec.unit,
        band = null,
        subtitle = ValueText.count[lang].format(
            oldestFirst.size, DAY.format(Instant.ofEpochMilli(oldestFirst.first().tMs).atZone(zone)),
        ),
        accent = spec.color,
        onClick = onClick,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ValueBars(oldestFirst, scale, height = style.previewHeight + 40.dp, modifier = Modifier.fillMaxWidth())
            ValueScaleLegend(scale)
        }
    }
}

@Composable
fun HuaweiValueDetailScreen(
    spec: MetricSpec,
    values: List<HuaweiDayValue>,
    scale: ValueBarsScale,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val zone = remember { ZoneId.systemDefault() }
    val oldestFirst = remember(values) { values.sortedBy { it.tMs } }
    val accent = spec.color
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(style.cardGap),
    ) {
        item("back") { TextButton(onClick = onBack) { Text(HuaweiText.back[lang]) } }
        item("plot") {
            SectionCard(accent = accent) {
                SectionTitle(spec.label[lang], accent)
                oldestFirst.lastOrNull()?.let {
                    Text("${scale.format(it.value)} ${spec.unit}", style = MaterialTheme.typography.headlineSmall, color = accent)
                }
                ValueBars(oldestFirst, scale, height = style.detailHeight * 1.26f, modifier = Modifier.fillMaxWidth(), detailed = true)
                ValueScaleLegend(scale)
            }
        }
        item("readings") {
            SectionCard(accent = accent) {
                SectionTitle(ValueText.readings[lang], accent)
                oldestFirst.asReversed().forEach { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(WHEN.format(Instant.ofEpochMilli(r.tMs).atZone(zone)), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium)
                        val step = scale.grade(r.value)
                        Text(
                            " ${scale.format(r.value)} ${spec.unit} ",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = ChartPalette.scaleInk(step),
                            modifier = Modifier.background(ChartPalette.scale(step), RoundedCornerShape(4.dp)),
                        )
                    }
                }
            }
        }
        item("info") {
            SectionCard(accent = accent) {
                SectionTitle(spec.label[lang], accent)
                val ja = lang.tag.startsWith("ja")
                InfoHeading(if (ja) "これは何か" else "What it is")
                InfoBody(spec.info.whatItIs[lang])
                InfoHeading(if (ja) "どう測っているか" else "How it is measured")
                InfoBody(spec.info.howMeasured[lang])
                InfoHeading(if (ja) "どう描いているか" else "How it is drawn")
                InfoBody(HOW_DRAWN[lang])
                InfoHeading(if (ja) "どう読むか" else "How to read it")
                InfoBody(spec.info.howToRead[lang])
                InfoHeading(if (ja) "分かっていないこと" else "What we do not know")
                InfoBody(spec.info.caveat[lang])
            }
        }
    }
}

private val HOW_DRAWN = Loc(
    "One bar per reading, standing on the floor of the chart, its number written inside it and the "
        + "bar filled in its grade — 1 to 5 by the Health index's own scoring of this quantity, so the "
        + "bar and the index's component always agree. The axis names the day, and the month under its "
        + "first reading. Nothing is averaged — the list below gives every reading.",
    "一本が一回の測定。棒はグラフの底から立ち、値をその中に書き、等級で塗る — 健康指数がこの量を"
        + "採点するのと同じ 1〜5 なので、棒と指数の項目は必ず一致する。軸には日を、月はその月の最初の測定の"
        + "下に書く。平均はしない — 下の一覧に全件。",
)
