package com.opentasker.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 健康指数 — the one composite number, and the only one that shows its working.
 *
 * The card is deliberately not a gauge with a needle. A gauge implies precision the inputs do not
 * have; a number with its five components listed underneath, each with its measurement and its
 * contribution, says exactly as much as is actually known. Tapping opens the full arithmetic.
 */
@Composable
fun HealthIndexCard(index: HealthIndexResult, onClick: () -> Unit) {
    val lang = LocalBandLanguage.current
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    BandText.indexTitle[lang],
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (index.partial) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(ChartPalette.BAND_WARN.copy(alpha = 0.16f))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    ) {
                        Text(
                            BandText.indexPartial[lang],
                            style = MaterialTheme.typography.labelSmall,
                            color = ChartPalette.BAND_WARN,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                // The index is the one card whose whole point is an explanation, and a card that
                // merely happens to be tappable does not advertise that. The ring does.
                InfoCircle(diameter = 28.dp, onClick = onClick)
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    index.value?.toString() ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    BandText.indexOutOf[lang].format(index.band[lang]),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChartStyle.current.axisText,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            // One label column, wide enough for the LONGEST label there actually is — measured, not
            // guessed. See [labelColumnWidth].
            val labelWidth = labelColumnWidth(index.components.map { it.label[lang] })
            index.components.forEach { ComponentRow(it, labelWidth) }

            if (index.partial) {
                Text(
                    BandText.indexPartialNote[lang].format(
                        index.missing.joinToString(if (lang == BandLanguage.EN) ", " else "・") { it[lang] },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChartStyle.current.axisText,
                )
            }
        }
    }
}

/**
 * The width the widest component label actually needs, in dp.
 *
 * It used to be a per-language constant — 116 dp for English, 84 for Japanese — chosen by eye. Two
 * of the five English labels ("Resting heart rate", "Heart-rate stability") did not fit, so they
 * wrapped to two lines; those rows then stood taller than the other three and the bars stopped being
 * evenly spaced down the card (白い熊, 2026-08-09).
 *
 * A wider constant would only move the problem: the labels are localised, the type scale is a
 * setting, and the system font size is the user's. So the column asks the text measurer how wide
 * these particular strings are at this particular style and takes the largest — always exactly
 * enough, in any language, at any font scale. Five short strings, measured once per composition.
 *
 * The rows also stop wrapping outright (`maxLines = 1`, `softWrap = false`), so a label can never
 * break even if a font substitution lands a pixel over the measurement — it would ellipsise
 * instead, which is visible and harmless where a broken row is neither.
 */
@Composable
private fun labelColumnWidth(labels: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    return remember(labels, style, density, measurer) {
        val widest = labels.maxOfOrNull { measurer.measure(it, style, softWrap = false).size.width } ?: 0
        with(density) { widest.toDp() }
    }
}

@Composable
private fun ComponentRow(c: IndexComponent, labelWidth: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val lang = LocalBandLanguage.current
        Text(
            c.label[lang],
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(labelWidth),
            color = if (c.score == null) LocalChartStyle.current.axisText else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            ScoreBar(c.score)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (c.score == null) "—" else "${c.score}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp),
            color = if (c.score == null) LocalChartStyle.current.axisText else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * One component's 0–100, as a bar.
 *
 * A missing component draws an empty track rather than a zero-length bar, because those are
 * different claims: "we measured nothing" and "we measured the worst possible value" must not look
 * the same.
 */
@Composable
private fun ScoreBar(score: Int?) {
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val r = size.height / 2f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.08f),
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
        if (score == null) return@Canvas
        val w = size.width * (score / 100f)
        if (w <= 0f) return@Canvas
        drawRoundRect(
            color = ChartPalette.sequential(score / 100f),
            size = Size(w.coerceAtLeast(size.height), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
    }
}

/** The full arithmetic, for the detail screen. Every breakpoint and weight, printed. */
@Composable
fun HealthIndexDetail(index: HealthIndexResult) {
    val lang = LocalBandLanguage.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        InfoHeading(
            Loc(
                "This index is not a reproduction of Hume's Health Score.",
                "この指数は Hume の Health Score の再現ではありません。",
            )[lang],
        )
        InfoBody(
            Loc(
                "Hume's 0–900 is built from resting heart rate, HRV, heart-rate stability, blood " +
                    "oxygen and sleep — every ingredient is something this app measures. But the " +
                    "weight given to each is not published, and body-composition readings from " +
                    "their Body Pod are mixed in as well. So their number cannot be reproduced.\n\n" +
                    "\"Metabolic Momentum\", \"Life Added 1.9 days\" and \"Pace of Aging 0.5×\" on the " +
                    "same screen go further still. The manufacturer's own material describes the " +
                    "life-added figure as a model-based estimate rather than the result of a " +
                    "controlled clinical study, and there is no established clinical standard for " +
                    "biological age from a wearable. A number that cannot be checked and cannot be " +
                    "wrong is not a measurement, so none of it was built.\n\n" +
                    "This is an index whose every breakpoint and weight is written on this screen " +
                    "instead. If you disagree with one, you can change it. A hidden formula does " +
                    "not allow that.",
                "Hume の 0〜900 は、安静時心拍・心拍変動・心拍の安定性・血中酸素・睡眠から作られていて、" +
                    "その材料はどれもこのアプリが測っているものです。しかし各項目の重みは公開されておらず、" +
                    "体組成計（Body Pod）の値も混ざります。だから同じ数字は出せません。\n\n" +
                    "同じ画面にある「Metabolic Momentum」「Life Added 1.9 日」「Pace of Aging 0.5×」は" +
                    "さらに踏み込めません。メーカー自身の資料が、寿命が延びたという数字を" +
                    "「モデルによる推定であって、対照臨床試験の結果ではない」と書いており、" +
                    "ウェアラブルの生物学的年齢に確立された臨床基準は存在しません。" +
                    "検証できず、外れようもない数字は測定ではないので、作りませんでした。\n\n" +
                    "代わりにこれは、区切りも重みも全部この画面に書いてある指数です。" +
                    "納得できなければ数字を変えられます。隠された式にはそれができません。",
            )[lang])

        InfoHeading(Loc("How to read it", "この数字の読み方")[lang])
        InfoBody(
            Loc(
                "Each component is scored 0–100 against the breakpoints printed below it, and the " +
                    "index is their weighted mean. The breakpoints are the SAME edges as each " +
                    "metric's own band ladder, so a card reading \"Standard\" and its component here " +
                    "can never contradict one another.\n\n" +
                    "A component summarises a WINDOW — a median or a percentile over the last day or " +
                    "the last night — while a card's big number is that same window's median. Neither " +
                    "is the single most recent reading, because one reading of a noisy series says " +
                    "very little.\n\n" +
                    "85 and above is Excellent, 70–84 Good, 55–69 Standard, 40–54 Low.",
                "各項目は下に書いてある区切りで 0〜100 点にし、重み付き平均をとったものがこの指数です。" +
                    "区切りは各グラフの帯（目安）とまったく同じ値なので、カードが「標準」と出ている" +
                    "のにここだけ極端に低い、ということは起こりません。\n\n" +
                    "各項目は「期間」をまとめた数字です — 直近一日、または直近の一晩の中央値や" +
                    "パーセンタイル。カードの大きな数字も同じ期間の中央値で、どちらも" +
                    "「いちばん新しい一回の測定」ではありません。ばらつく数値の一回分は" +
                    "ほとんど何も語らないからです。\n\n" +
                    "85 以上でとても良い、70〜84 で良い、55〜69 で標準、40〜54 で低い。",
            )[lang])

        InfoHeading(BandText.indexTargets[lang])
        InfoBody(
            Loc(
                "Three of the five are things you can actually move, and together they are nearly " +
                    "three quarters of the index.\n\n" +
                    "Steps carry 20 %: 3 000 in a day scores nothing, 7 500 scores full marks, and " +
                    "walking further than that does not keep buying score. Those are the same edges " +
                    "as the steps card's own guide. Note that steps are the one BEHAVIOUR here — " +
                    "the other four are what your body did, mostly not under your control on any " +
                    "given day — so a long walk can lift this number while nothing physiological " +
                    "has changed. That is deliberate, and worth remembering when reading a rise.\n\n" +
                    "Resting heart rate carries 26 % and falls with aerobic fitness over weeks — " +
                    "50 bpm scores full marks, 85 scores nothing. Sleep carries 26 % and wants 7–9 " +
                    "hours with a healthy share of deep and REM; more than nine scores LOWER, not " +
                    "higher.\n\n" +
                    "Heart-rate stability (11 %) and blood oxygen (17 %) are mostly not under your " +
                    "control day to day — they are here because a sustained fall in either is worth " +
                    "noticing, not because they are targets.\n\n" +
                    "There is no HRV component. The field this band labels HRV is not a variability " +
                    "measurement — see the Band State Index card — and nothing was substituted for " +
                    "it, because no other field in this protocol measures autonomic tone.",
                "五つのうち三つは自分で動かせるもので、合わせて指数のおよそ四分の三を占めます。\n\n" +
                    "歩数は 20 %。一日 3,000 歩で 0 点、7,500 歩で満点、それ以上歩いても加点はありません。" +
                    "この区切りは歩数カードの「目安」とまったく同じ値です。" +
                    "なお歩数だけが**行動**で、ほかの四つは身体の状態 — その日どうこうできるものではありません。" +
                    "だから長く歩いた日は、身体側の数字が何も変わらなくてもこの数字が上がります。" +
                    "それは意図したとおりで、上がった理由を読むときに覚えておく価値があります。\n\n" +
                    "安静時心拍は 26 %。数週間の有酸素運動で下がります — 50 bpm で満点、85 bpm で 0 点。" +
                    "睡眠も 26 % で、7〜9 時間かつ深い睡眠と REM の割合が高いほど良い。" +
                    "九時間を超えると点は**下がります**、上がりません。\n\n" +
                    "心拍安定性（11 %）と血中酸素（17 %）は、日々どうこうできるものではありません。" +
                    "目標というより「続けて下がったら気づくため」に入れてあります。\n\n" +
                    "心拍変動の項目はありません。このバンドが心拍変動と称している値は変動の測定ではなく" +
                    "（「バンド状態指数」のカードを参照）、代わりに何かを入れることもしませんでした。" +
                    "この通信規約には自律神経の状態を測る項目が他にないからです。",
            )[lang])

        // Each component's own arithmetic, at the same weight as the prose above it — this is the
        // part 白い熊 would actually check a number against, so it is not a footnote.
        index.components.forEach { c ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(
                        if (c.score == null) ChartPalette.UNKNOWN else ChartPalette.sequential(c.score / 100f),
                    ).align(Alignment.CenterVertically))
                    Spacer(Modifier.width(8.dp))
                    InfoBody(c.label[lang], Modifier.weight(1f), bold = true)
                    Text(
                        BandText.indexWeight[lang].format((c.weight * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalChartStyle.current.axisText,
                    )
                }
                InfoBody(c.scale[lang])
                Text(
                    if (c.score == null) {
                        BandText.indexMissing[lang].format(c.missingReason?.get(lang).orEmpty())
                    } else {
                        BandText.indexContribution[lang]
                            .format(c.measured ?: 0.0, c.unit, c.score, c.contribution)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = InfoType.BODY_SP,
                    lineHeight = InfoType.BODY_LEADING,
                    color = if (c.score == null) ChartPalette.BAND_WARN else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
