package com.opentasker.ui.charts.huawei

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.InfoBody
import com.opentasker.ui.charts.InfoHeading
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Watch D3's pulse-wave arrhythmia checks: the watch looks at the pulse with its green light,
 * about every ten minutes that first night, and files each look as one record of dictionary class
 * 500007 (docs/huawei-watch-d3.md §3.11a). Matched on 2026-10-18 against the watch's own screen —
 * 37 in seven days, 3 on the 17th and 34 on the 18th, none abnormal.
 *
 * Drawn as the watch draws it: checks per day, every check listed by its time. A check that was not
 * normal is told apart by an outline and a word, never by colour alone (白い熊 reads red–green
 * poorly).
 */
object HuaweiPwaText {
    val title = Loc("Pulse-Wave Arrhythmia", "脈波の不整脈")
    val headline = Loc("%d in 7 days", "7 日で %d 回")
    val normal = Loc("no abnormality", "異常なし")
    val abnormal = Loc("not normal", "異常あり")
    val counts = Loc("no abnormality %d · not normal %d · last %s", "異常なし %d · 異常あり %d · 最新 %s")
    val lastWeek = Loc("Last 7 days", "直近 7 日")
    val byDay = Loc("Every check", "全件")
    val dayLine = Loc("%d checks", "%d 回")
    val code = Loc("result %d", "結果 %d")
    val what = Loc(
        "The HUAWEI WATCH D3 looks at your pulse with its green light, at intervals — about "
            + "every ten minutes on the night of 2026-10-17 (Health's Automatic arrhythmia detection), and judges each look "
            + "on the watch: a regular rhythm, or a suspected arrhythmia such as atrial fibrillation.",
        "HUAWEI WATCH D3 が緑の光で脈を見る。2026-10-17 の夜はおよそ十分おきに（Health の"
            + "「不整脈の自動検出」）。一回ごとに時計が判定する — 規則正しいか、心房細動などの"
            + "不整脈が疑われるか。",
    )
    val how = Loc(
        "The bars count the checks of each day, as on the watch; an outlined part is checks that "
            + "were not normal, with their number above. Below, every check by its time, grouped by "
            + "day. Nothing is averaged or left out.",
        "棒は一日ごとの回数で、時計と同じ。枠だけの部分が異常ありの回数で、数を上に添える。下に"
            + "全件を時刻で、日ごとに。平均も省略もしない。",
    )
    val caveat = Loc(
        "**The verdict is the watch's.** A pulse check is not an ECG: a suspected arrhythmia is a "
            + "reason to record an ECG on the watch and to see a doctor, not a diagnosis. The result "
            + "field reads 0 on every check so far, which matches the watch's \"no abnormalities\"; "
            + "what the other values mean is not known yet, so any non-zero result is shown as "
            + "\"not normal\" with its number.",
        "**判定は時計のもの。** 脈の確認は心電図ではない。不整脈の疑いは、時計で心電図を取り医師に"
            + "かかる理由であって、診断ではない。結果の欄はこれまで全件 0 で、時計の「異常なし」と"
            + "一致する。ほかの値の意味はまだ分からないので、0 以外は数字を添えて「異常あり」と出す。",
    )
}

/** The arrhythmia accent: aqua, apart from the ECG blue above it (HuaweiD3PaletteTest). */
val PWA_COLOR = ChartPalette.SLEEP_DEEP

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE")

/** Checks per local day for the seven days ending today: (day, all, not normal). */
internal fun pwaWeek(checks: List<HuaweiPwaCheck>, zone: ZoneId, today: LocalDate): List<Triple<LocalDate, Int, Int>> {
    val byDay = checks.groupBy { Instant.ofEpochMilli(it.tMs).atZone(zone).toLocalDate() }
    return (6 downTo 0).map { back ->
        val day = today.minusDays(back.toLong())
        val list = byDay[day].orEmpty()
        Triple(day, list.size, list.count { !it.normal })
    }
}

@Composable
fun HuaweiArrhythmiaCard(
    checks: List<HuaweiPwaCheck>,
    nowMs: Long = System.currentTimeMillis(),
    onClick: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    val zone = remember { ZoneId.systemDefault() }
    val latest = checks.firstOrNull() ?: return
    val week = pwaWeek(checks, zone, Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate())
    val total = week.sumOf { it.second }
    val bad = week.sumOf { it.third }
    SectionCard(accent = PWA_COLOR, onClick = onClick) {
        SectionTitle(HuaweiPwaText.title[lang], PWA_COLOR)
        Text(HuaweiPwaText.headline[lang].format(total),
            style = MaterialTheme.typography.headlineSmall, color = PWA_COLOR)
        NoteText(HuaweiPwaText.counts[lang].format(total - bad, bad,
            TIME.format(Instant.ofEpochMilli(latest.tMs).atZone(zone))))
        PwaWeekBars(week, height = 72.dp)
    }
}

/** Seven columns, one per day: the count above, the bar, the day of the month below. */
@Composable
fun PwaWeekBars(week: List<Triple<LocalDate, Int, Int>>, height: Dp) {
    val max = week.maxOf { it.second }.coerceAtLeast(1)
    val track = Color(0x14FFFFFF)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        week.forEach { (day, all, bad) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (all == 0) "" else if (bad > 0) "$all ($bad)" else "$all",
                    style = MaterialTheme.typography.bodySmall, color = PWA_COLOR,
                    fontWeight = if (bad > 0) FontWeight.Bold else FontWeight.Normal,
                )
                Canvas(Modifier.fillMaxWidth().height(height)) {
                    val r = CornerRadius(4.dp.toPx())
                    drawRoundRect(track, cornerRadius = r)
                    if (all == 0) return@Canvas
                    val h = size.height * all / max
                    val hBad = size.height * bad / max
                    // Normal checks solid from the bottom; the not-normal ones stacked on top as an
                    // outline, so the difference is a shape and not a hue.
                    drawRoundRect(PWA_COLOR, topLeft = Offset(0f, size.height - (h - hBad)),
                        size = Size(size.width, h - hBad), cornerRadius = r)
                    if (bad > 0) {
                        val w = 2.dp.toPx()
                        drawRoundRect(Color.White, topLeft = Offset(w / 2, size.height - h + w / 2),
                            size = Size(size.width - w, hBad - w), cornerRadius = r, style = Stroke(w))
                    }
                }
                Text("${day.dayOfMonth}", style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun HuaweiArrhythmiaDetailScreen(
    checks: List<HuaweiPwaCheck>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    nowMs: Long = System.currentTimeMillis(),
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(checks) {
        checks.groupBy { Instant.ofEpochMilli(it.tMs).atZone(zone).toLocalDate() }.toList()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(style.cardGap),
    ) {
        item("back") { TextButton(onClick = onBack) { Text(HuaweiText.back[lang]) } }
        item("week") {
            SectionCard(accent = PWA_COLOR) {
                SectionTitle(HuaweiPwaText.lastWeek[lang], PWA_COLOR)
                PwaWeekBars(pwaWeek(checks, zone, Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()), height = 160.dp)
            }
        }
        item("all") {
            SectionCard(accent = PWA_COLOR) {
                SectionTitle(HuaweiPwaText.byDay[lang], PWA_COLOR)
                // Newest day first; within a day, in the order they were taken.
                days.forEach { (day, list) ->
                    val bad = list.count { !it.normal }
                    Text(
                        DAY.format(day) + " · " + HuaweiPwaText.dayLine[lang].format(list.size) +
                            if (bad > 0) " · ${HuaweiPwaText.abnormal[lang]} $bad" else "",
                        style = MaterialTheme.typography.titleSmall, color = PWA_COLOR,
                    )
                    Text(
                        list.reversed().joinToString("  ") { c ->
                            val t = TIME.format(Instant.ofEpochMilli(c.tMs).atZone(zone))
                            if (c.normal) t else "$t ${HuaweiPwaText.abnormal[lang]} (${HuaweiPwaText.code[lang].format(c.result)})"
                        },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item("info") {
            SectionCard(accent = PWA_COLOR) {
                SectionTitle(HuaweiPwaText.title[lang], PWA_COLOR)
                val ja = lang.tag.startsWith("ja")
                InfoHeading(if (ja) "これは何か" else "What it is")
                InfoBody(HuaweiPwaText.what[lang])
                InfoHeading(if (ja) "どう描いているか" else "How it is drawn")
                InfoBody(HuaweiPwaText.how[lang])
                InfoHeading(if (ja) "分かっていないこと" else "What we do not know")
                InfoBody(HuaweiPwaText.caveat[lang])
            }
        }
    }
}
