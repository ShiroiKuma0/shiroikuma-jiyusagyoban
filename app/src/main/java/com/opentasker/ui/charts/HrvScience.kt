package com.opentasker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * What heart-rate variability is, how this app measures it, and what the literature does and does
 * not support — the explanation on the HRV page (白い熊, 2026-10-26: "a detailed scientific
 * explanation").
 *
 * Every figure here is either from a cited source or from this app's own pipeline
 * (`HuaweiBeatMetrics`, `RecoverySource.nightMedian`, [HealthIndex.hrvScore]), and the two are kept
 * apart in the prose: a population number never stands in for 白い熊's own, and the app's edges are
 * named as the app's.
 */
object HrvScience {

    data class Section(val title: Loc, val body: Loc)

    val title = Loc("The science of HRV", "心拍変動の科学")

    val sections = listOf(
        Section(
            Loc("What it is", "何を測っているか"),
            Loc(
                "A healthy heart does not beat like a metronome. The time between one beat and the next — " +
                    "the inter-beat interval — changes from beat to beat by tens of milliseconds, even at " +
                    "rest. Heart-rate variability (HRV) is the measure of that change. It is not a flaw in the " +
                    "rhythm: it is the visible trace of the nervous system adjusting the heart continuously.",
                "健康な心臓はメトロノームのようには打たない。一拍と次の拍の間隔（拍間隔）は、安静時でも一拍ごとに" +
                    "数十ミリ秒ずつ変わる。心拍変動（HRV）はその変化の大きさである。リズムの乱れではなく、神経系が" +
                    "心臓を絶えず調整している跡が見えているものである。",
            ),
        ),
        Section(
            Loc("Where the variation comes from", "変動はどこから来るか"),
            Loc(
                "The heart's pacemaker, the sinoatrial node, fires on its own at about 100 beats a minute; " +
                    "the autonomic nervous system slows it down or speeds it up. The two branches work on " +
                    "very different clocks. The parasympathetic branch, through the vagus nerve, releases " +
                    "acetylcholine, which acts within a single beat and wears off almost as fast. The " +
                    "sympathetic branch acts through noradrenaline, which takes seconds to build up and to " +
                    "clear. So fast, beat-to-beat changes can only be vagal. The largest of them is " +
                    "respiratory sinus arrhythmia: the heart speeds up as you breathe in and slows as you " +
                    "breathe out, because breathing in briefly damps vagal output. High beat-to-beat " +
                    "variability therefore means a strong, responsive vagal brake on the heart — the state " +
                    "of rest and recovery.",
                "心臓のペースメーカーである洞結節は、放っておけば毎分およそ 100 回で発火する。自律神経がそれを" +
                    "遅くしたり速くしたりする。二つの系は時間の尺度がまったく違う。副交感神経は迷走神経を通じて" +
                    "アセチルコリンを放出し、これは一拍のうちに効いて、ほぼ同じ速さで消える。交感神経はノルアドレナリンを" +
                    "通じて働き、効き始めにも消えるのにも数秒かかる。だから一拍ごとの速い変化は迷走神経によるものしか" +
                    "ありえない。その最大のものが呼吸性洞性不整脈で、息を吸うと心拍が速くなり、吐くと遅くなる。吸気が" +
                    "迷走神経の出力を一時的に弱めるためである。拍ごとの変動が大きいということは、心臓にかかる迷走神経の" +
                    "ブレーキが強く、よく反応しているということ — 休息と回復の状態である。",
            ),
        ),
        Section(
            Loc("RMSSD, the number on this page", "RMSSD — このページの数値"),
            Loc(
                "RMSSD is the root mean square of successive differences: take each interval minus the one " +
                    "before it, square those differences, average them, and take the square root. Because it " +
                    "looks only at neighbouring beats, slow drifts in heart rate — standing up, a change of " +
                    "sleep stage — cancel out and what remains is the fast, vagal part. It closely tracks " +
                    "high-frequency spectral power, the classical vagal index, while being less disturbed by " +
                    "the breathing rate, and it stays valid on short recordings: in 3 387 people, RMSSD from " +
                    "10–60 seconds agreed closely with the standard 5-minute value (Munoz 2015). That is why " +
                    "it is the measure used by most wearables and in HRV-guided training research. It is " +
                    "given in milliseconds.",
                "RMSSD は「隣り合う拍間隔の差の二乗平均平方根」である。各間隔からその前の間隔を引き、その差を" +
                    "二乗して平均し、平方根をとる。隣同士の拍だけを見るので、立ち上がる・睡眠段階が変わるといった心拍の" +
                    "ゆっくりした変化は打ち消され、残るのは速い迷走神経の成分である。古典的な迷走神経の指標である" +
                    "高周波（HF）パワーとよく一致し、しかも呼吸数の影響を受けにくい。短い記録でも成り立ち、3 387 人の" +
                    "調査では 10〜60 秒の RMSSD が標準の 5 分間の値とよく一致した（Munoz 2015）。そのため多くの" +
                    "ウェアラブルと、HRV に基づくトレーニングの研究でこの指標が使われる。単位はミリ秒。",
            ),
        ),
        Section(
            Loc("How this app measures it", "このアプリでの測り方"),
            Loc(
                "The Watch D3 finds each pulse optically (photoplethysmography, PPG) and records the time " +
                    "between pulses, in windows of about a minute, through the night. These pulse-to-pulse " +
                    "intervals match ECG R–R intervals well at rest and in sleep; they drift with movement. " +
                    "The app then cleans every window before computing anything. Beats the watch itself " +
                    "marks as poor quality are dropped. Then Malik's rule: an interval more than 20 % away " +
                    "from the one before it is a missed or extra beat, not physiology, and is removed. This " +
                    "is not cosmetic — on raw intervals your median RMSSD came out at 133 ms, four to six " +
                    "times any real resting figure, because one missed beat makes one interval twice as long. " +
                    "A window with fewer than 12 usable beats gives no value. The night's figure is the " +
                    "median of every window inside the sleep session. Because of the cleaning, it runs " +
                    "10–30 % below the RMSSD Huawei's own panel reports for the same nights.",
                "Watch D3 は脈を光学的に（光電容積脈波、PPG）捉え、脈と脈の間隔を夜通し約一分の区切りで記録する。" +
                    "この脈間隔は、安静時と睡眠中には心電図の R–R 間隔とよく一致し、体が動くとずれる。アプリは計算の前に" +
                    "区切りごとに掃除をする。時計自身が品質不良とした拍を除き、次に Malik の規則を当てる：前の間隔から" +
                    "20 % 以上離れた間隔は生理ではなく脈の取り逃がしか余分な拍なので取り除く。これは見た目の問題ではない — " +
                    "生の間隔ではあなたの RMSSD の中央値は 133 ms になった。実際の安静時の値の四〜六倍で、一拍取り逃がすと" +
                    "一つの間隔が二倍になるからである。使える拍が 12 未満の区切りは値を出さない。その夜の値は、睡眠の時間内" +
                    "の全区切りの中央値。この掃除のため、同じ夜について Huawei 自身のパネルが出す RMSSD より 10〜30 % 低く出る。",
            ),
        ),
        Section(
            Loc("Why at night, and why the median", "なぜ夜か、なぜ中央値か"),
            Loc(
                "HRV responds within minutes to posture, talking, food, coffee, exercise and stress, so a " +
                    "daytime reading mostly measures the last few minutes. Sleep is the one long stretch " +
                    "taken under nearly the same conditions every day, which makes nights comparable with " +
                    "each other. Within a night, RMSSD is highest in deep (N3) sleep, when vagal tone " +
                    "dominates, and lower and more erratic in REM sleep and around awakenings. The median of " +
                    "all the windows is used rather than the mean, so a handful of noisy or restless windows " +
                    "cannot pull the night's value.",
                "HRV は姿勢・会話・食事・コーヒー・運動・ストレスに数分で反応するので、日中の一回の値はほぼ直前の数分を" +
                    "測っているにすぎない。睡眠は毎日ほぼ同じ条件で長く続く唯一の時間であり、だから夜同士は比べられる。" +
                    "一晩の中では、迷走神経が優位になる深い睡眠（N3）で RMSSD が最も高く、レム睡眠や目覚めの前後では低く、" +
                    "ばらつく。平均ではなく全区切りの中央値を使うのは、少数の乱れた区切りに夜の値を引っ張らせないためである。",
            ),
        ),
        Section(
            Loc("What moves it", "何が値を動かすか"),
            Loc(
                "Lower: age — RMSSD roughly halves between the twenties and the sixties (Umetani 1998); " +
                    "alcohol, in a dose-dependent way, the following night (Pietilä 2018, 4 098 people); " +
                    "infection and fever, often before symptoms — in Fitbit data RMSSD fell and breathing " +
                    "rate rose around COVID-19 onset (Natarajan 2020); hard training without enough recovery; " +
                    "short or broken sleep; psychological stress; a large late meal; heat and dehydration. " +
                    "Higher: aerobic fitness, recovery after a hard block, slow deep breathing. A higher heart " +
                    "rate lowers RMSSD partly by arithmetic alone, since faster beats leave less room for the " +
                    "intervals to differ — so read it beside the nocturnal heart rate. People differ a great " +
                    "deal at baseline, largely for genetic reasons: comparing your number with someone " +
                    "else's says little; comparing it with your own nights says a lot.",
                "下げるもの：年齢 — RMSSD は 20 代から 60 代でおよそ半分になる（Umetani 1998）。飲酒は量に応じて" +
                    "その夜を下げる（Pietilä 2018、4 098 人）。感染と発熱は、しばしば症状より先に下げる — Fitbit の" +
                    "データでは COVID-19 の発症前後に RMSSD が下がり呼吸数が上がった（Natarajan 2020）。回復の足りない" +
                    "強い運動、短い・途切れた睡眠、心理的ストレス、遅い大量の食事、暑さと脱水。上げるもの：有酸素の体力、" +
                    "きつい時期の後の回復、ゆっくり深い呼吸。心拍数が高いと、拍が速いぶん間隔が変わる余地が減るので、" +
                    "計算上だけでも RMSSD は下がる — だから夜間心拍と並べて読む。基準値は人によって大きく違い、多くは" +
                    "遺伝による。他人の数値と比べてもほとんど何も分からないが、自分の夜と比べれば多くが分かる。",
            ),
        ),
        Section(
            Loc("What it means for health", "健康にとっての意味"),
            Loc(
                "Across populations, low HRV goes with worse outcomes. After a heart attack, a 24-hour SDNN " +
                    "under 50 ms carried 5.3 times the mortality of one over 100 ms (Kleiger 1987); in people " +
                    "without heart disease, low HRV went with a 32–45 % higher risk of a first cardiovascular " +
                    "event (Hillebrand 2013). Those are group statistics from clinical recordings, not a " +
                    "diagnosis from one night on a wrist. What a night's RMSSD does well is tell you how " +
                    "recovered your body is compared with its own usual — which is why it is graded here and " +
                    "counted in the health index.",
                "集団で見ると、HRV が低いほど経過が悪い。心筋梗塞の後、24 時間 SDNN が 50 ms 未満の人は 100 ms を" +
                    "超える人の 5.3 倍の死亡率だった（Kleiger 1987）。心疾患の無い人では、HRV が低いと初めての心血管事故の" +
                    "危険が 32〜45 % 高かった（Hillebrand 2013）。これらは臨床記録による集団の統計であり、手首の一晩から" +
                    "診断できるものではない。一晩の RMSSD が得意なのは、体が自分のいつもと比べてどれだけ回復しているかを" +
                    "示すことであり、だからここで採点し、健康指数にも数えている。",
            ),
        ),
        Section(
            Loc("Reading your own nights", "自分の夜の読み方"),
            Loc(
                "Single nights commonly move 10–20 % either way for no particular reason, so one low night " +
                    "is a question, not an answer. What carries meaning is a run: two or three nights in a " +
                    "row clearly below your usual, or a weekly average that has slid. Training studies use " +
                    "exactly that — a 7-day rolling average of RMSSD against the person's own baseline " +
                    "(Plews 2013; Kiviniemi 2007). A fall that comes together with a higher nocturnal heart " +
                    "rate, a faster breathing rate or a warmer skin temperature is the pattern most often seen " +
                    "at the start of an illness. A night at 30 ms after a run at 40–44 is a drop of more than a " +
                    "quarter — larger than ordinary night-to-night noise.",
                "一晩の値は特に理由が無くても上下に 10〜20 % 動くことがよくあるので、低い一晩は答えではなく問いである。" +
                    "意味があるのは連続：いつもより明らかに低い夜が二〜三晩続くこと、あるいは週の平均が下がってきていること。" +
                    "トレーニングの研究もまさにそれを使う — RMSSD の 7 日移動平均を本人の基準と比べる（Plews 2013、" +
                    "Kiviniemi 2007）。夜間心拍の上昇、呼吸数の増加、皮膚温の上昇と同時に起こる低下は、体調を崩し始める" +
                    "ときに最もよく見られる型である。40〜44 が続いた後の 30 ms の夜は四分の一を超える低下で、ふだんの夜ごとの" +
                    "ゆらぎより大きい。",
            ),
        ),
        Section(
            Loc("How it is graded here", "ここでの採点"),
            Loc(
                "On fixed edges, the same on every screen: 45 ms or more is the best end and 15 ms the worst, " +
                    "so about 41 ms and above is a 1, 36–40 a 2, 32–35 a 3, 27–31 a 4 and below 27 a 5. In " +
                    "adults, short resting recordings average about 42 ms with a normal range of roughly " +
                    "19–75 ms (Nunan 2010), and sleep values run higher than seated daytime ones. These edges " +
                    "are this app's choice inside that range. They are not adjusted for age, so the shaded " +
                    "band of your own nights on the chart above is the better guide to what is normal for you.",
                "どの画面でも同じ固定の境界で採点する：45 ms 以上が最良、15 ms が最悪で、およそ 41 ms 以上が 1、" +
                    "36〜40 が 2、32〜35 が 3、27〜31 が 4、27 未満が 5。成人の短い安静時記録の平均は約 42 ms、正常範囲は" +
                    "おおよそ 19〜75 ms で（Nunan 2010）、睡眠中の値は座った日中の値より高く出る。この境界はその範囲の中で" +
                    "このアプリが選んだものであり、年齢で補正していない。だから自分にとって何が普通かは、上のグラフの" +
                    "自分の夜の帯のほうがよい目安になる。",
            ),
        ),
        Section(
            Loc("Limits", "限界"),
            Loc(
                "An irregular rhythm inflates RMSSD: extra beats and atrial fibrillation make successive " +
                    "intervals differ wildly, which reads as high HRV while being the opposite of healthy. " +
                    "Malik's rule removes isolated extra beats but cannot rescue a rhythm that is irregular " +
                    "throughout — that is what the watch's ECG and arrhythmia screening are for. Optical " +
                    "pulses carry a little extra variation from the pulse's travel time along the arteries, " +
                    "and a loose strap or a restless night adds noise. Beta-blockers and some other " +
                    "medications change HRV directly.",
                "不規則なリズムは RMSSD を水増しする：期外収縮や心房細動では隣り合う間隔が大きく食い違い、健康とは逆なのに" +
                    "HRV が高く見える。Malik の規則は単発の期外収縮は除けるが、ずっと不規則なリズムは救えない — そのための" +
                    "ものが時計の心電図と不整脈の検査である。光学的な脈には、脈が動脈を伝わる時間のゆらぎが少し加わり、" +
                    "ゆるいバンドや落ち着かない夜は雑音を増やす。ベータ遮断薬などの薬は HRV を直接変える。",
            ),
        ),
    )

    val sourcesTitle = Loc("Sources", "出典")

    val sources = listOf(
        "Task Force of the ESC and NASPE (1996). Heart rate variability: standards of measurement, physiological interpretation and clinical use. Circulation 93:1043–1065.",
        "Shaffer F, Ginsberg JP (2017). An overview of heart rate variability metrics and norms. Front Public Health 5:258.",
        "Munoz ML et al. (2015). Validity of (ultra-)short recordings for heart rate variability measurements. PLoS One 10:e0138921.",
        "Kleiger RE et al. (1987). Decreased heart rate variability and its association with increased mortality after acute myocardial infarction. Am J Cardiol 59:256–262.",
        "Hillebrand S et al. (2013). Heart rate variability and first cardiovascular event in populations without known cardiovascular disease: meta-analysis and dose–response meta-regression. Europace 15:742–749.",
        "Nunan D et al. (2010). A quantitative systematic review of normal values for short-term heart rate variability in healthy adults. Pacing Clin Electrophysiol 33:1407–1417.",
        "Umetani K et al. (1998). Twenty-four hour time domain heart rate variability and heart rate: relations to age and gender over nine decades. J Am Coll Cardiol 31:593–601.",
        "Pietilä J et al. (2018). Acute effect of alcohol intake on cardiovascular autonomic regulation during the first hours of sleep in a large real-world sample of Finnish employees. JMIR Ment Health 5:e23.",
        "Natarajan A et al. (2020). Assessment of physiological signs associated with COVID-19 measured using wearable devices. npj Digit Med 3:156.",
        "Plews DJ et al. (2013). Training adaptation and heart rate variability in elite endurance athletes: opening the door to effective monitoring. Sports Med 43:773–781.",
        "Kiviniemi AM et al. (2007). Endurance training guided individually by daily heart rate variability measurements. Eur J Appl Physiol 101:743–751.",
        "Malik M et al. (1989). Heart rate variability in relation to prognosis after myocardial infarction: selection of optimal processing techniques. Eur Heart J 10:1060–1074.",
    )
}

/**
 * The explanation as a card, under the chart on the HRV page.
 *
 * Every section — the sources too — is folded to its heading by default (白い熊, 2026-10-26): ten
 * sections of prose under a chart turned the page into a long scroll past what it was opened to see.
 * A tap on a heading opens or closes it; the fold survives a rotation but not leaving the page.
 */
@Composable
fun HrvScienceCard(accent: Color) {
    val lang = LocalBandLanguage.current
    SectionCard(accent = accent) {
        SubHeading(HrvScience.title[lang], accent)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HrvScience.sections.forEachIndexed { i, s ->
                FoldedSection(s.title[lang], key = "hrv-science-$i") { TextBox(accent) { BodyText(s.body[lang]) } }
            }
            FoldedSection(HrvScience.sourcesTitle[lang], key = "hrv-science-sources") {
                TextBox(accent) {
                    // Cited as published, in the language they were published in.
                    for (ref in HrvScience.sources) NoteText(ref)
                }
            }
        }
    }
}

/** An open section's text, in a rounded box of the page's accent (白い熊, 2026-10-26). */
@Composable
private fun TextBox(accent: Color, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** A bold heading with a chevron; the content shows only while it is open. */
@Composable
private fun FoldedSection(title: String, key: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BodyText(title, Modifier.weight(1f), bold = true)
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = sectionInk,
            )
        }
        if (open) content()
    }
}
