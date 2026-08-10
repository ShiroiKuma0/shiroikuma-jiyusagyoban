package com.opentasker.ui.charts

import androidx.compose.ui.graphics.Color
import com.opentasker.core.band.BandMetric

/**
 * Everything that distinguishes one metric from another, as a table row.
 *
 * Adding a metric should be a row here, not new code. The gate values come from the band's real
 * output; where a number is a judgement call rather than a measurement, the comment says so.
 */

/** How a metric is drawn. The renderer is chosen by the data's shape, never by preference. */
enum class RenderKind {
    /** A continuous quantity sampled often enough to interpolate between. */
    LINE,

    /** One capsule per hour spanning that hour's real min–max. For sparse or bursty series. */
    CAPSULE,

    /**
     * A [LINE] through the primary population, with the second population as hollow spot dots.
     *
     * For a stream that carries two kinds of record which must not be merged into one curve. Heart
     * rate is the only one: a periodic series that moves slowly, and a spot reading taken at each
     * SpO₂ measurement that tracks exertion (see [MetricSpecs.HEART_RATE]). A line through both
     * draws a sawtooth that is an artefact of the interleaving; a line through the periodic series
     * alone, with the spot readings drawn on top, shows each for what it is.
     *
     * Every reading still appears. The curve is the periodic series, the dots are the rest, and
     * nothing is aggregated or averaged away.
     */
    LINE_WITH_SPOTS,

    /** Two series sharing one axis, drawn as a bar between them. Blood pressure only. */
    DUMBBELL,

    /** Categorical stages over time. Never interpolated — there is no value between two stages. */
    HYPNOGRAM,

    /** Counts in a bucket. Zero is a real measurement, not a gap. */
    BARS,
}

/**
 * One rung of the qualitative ladder under a chart.
 *
 * [upTo] is the exclusive top of the rung. The last rung's [upTo] is `Double.MAX_VALUE`.
 */
data class BandRung(val label: Loc, val upTo: Double, val color: Color)

/** Text for the `i` sheet. Longer and more honest than Hume's, which is the point. */
data class MetricInfo(
    val whatItIs: Loc,
    val howMeasured: Loc,
    val howToRead: Loc,
    /** What we do NOT know. Both sides blank when there is nothing to disclaim. */
    val caveat: Loc = Loc("", ""),
)

data class MetricSpec(
    val key: String,
    val label: Loc,
    val unit: String,
    val color: Color,
    val render: RenderKind,
    /** Nominal sampling interval. Drives the gap threshold and the slew gate's per-step limit. */
    val cadenceSec: Int,
    val validMin: Double,
    val validMax: Double,
    /** When true a stored 0 means "no reading", not a measurement of zero. */
    val zeroIsNoReading: Boolean,
    /** Largest believable change across one cadence step. null disables the gate. */
    val slewPerStep: Double?,
    /** Hampel half-window in samples. 0 turns the filter off entirely. */
    val hampelHalfWindow: Int,
    val hampelSigmas: Double,
    /**
     * The floor under the MAD scale. MANDATORY where the filter is on.
     *
     * In a quiet window — seven SpO₂ readings of 97, resting HR pinned at 58 overnight — the MAD is
     * exactly 0, so the threshold is 0 and every deviation including a real one-unit change gets
     * flagged. The filter goes berserk in precisely the calmest, most trustworthy stretches of the
     * night. This floor is what stops that.
     */
    val hampelMinScale: Double,
    /** Fixed clinical band. Auto-expands to fit data, but never auto-ranges per window. */
    val yMin: Double,
    val yMax: Double,
    val decimals: Int,
    val bands: List<BandRung>,
    val info: MetricInfo,
    /**
     * Draw the axis from [yMin] with a visible break marker, because the true zero is far below and
     * a full-range axis would make real excursions invisible. Only SpO₂ needs this.
     */
    val axisBreak: Boolean = false,
    /**
     * This series interleaves two cadences, so its gap threshold needs a high percentile rather than
     * a median. Only the POOLED heart-rate series does.
     */
    val mixedCadence: Boolean = false,
    /**
     * Headline the window's low–high rather than its typical value.
     *
     * Tied to the metric, not to the mark. It used to be inferred from [RenderKind] — CAPSULE meant
     * a range, everything else a median — so changing heart rate's mark from a capsule to
     * [RenderKind.POINTS] would silently have turned its `53–105 bpm` headline into `68 bpm`. The
     * question "is a range or a typical value the more useful summary of this metric" has nothing to
     * do with how the samples happen to be drawn, so it is asked here instead.
     */
    val headlineIsRange: Boolean = false,
    /**
     * This metric's stream carries two measurement populations: one takes the curve, the other is
     * drawn as hollow dots and kept out of it.
     *
     * Only heart rate does. See [MetricSpecs.HEART_RATE].
     */
    val splitPopulations: Boolean = false,
) {
    fun format(value: Double): String = when (decimals) {
        0 -> value.toInt().toString()
        else -> String.format("%.${decimals}f", value)
    }

    /** The rung [value] falls in, for the headline chip. */
    fun bandFor(value: Double): BandRung? = bands.firstOrNull { value < it.upTo } ?: bands.lastOrNull()

    /** Both sides of the info sheet's caveat are blank when there is nothing to disclaim. */
    val hasCaveat: Boolean get() = info.caveat.en.isNotBlank() || info.caveat.ja.isNotBlank()
}

object MetricSpecs {

    /**
     * Heart rate — **a curve through the periodic series, with the spot readings on top**.
     *
     * ## Why it is not a capsule any more
     *
     * It was an hourly capsule: one mark per clock hour spanning that hour's real min and max. The
     * mark was honest — both ends were readings that occurred — but it answered a question 白い熊 was
     * not asking. An hour of heart rate is 12 to 30 readings, and a capsule showed two of them and
     * hid the rest, so the chart read as though the band measures once an hour. It does not
     * (白い熊, 2026-08-09).
     *
     * ## What the two populations actually are — measured, and not what we first said
     *
     * The `hr` stream interleaves a periodic series with an extra reading taken at each SpO₂
     * measurement. That second population was written up as "the same thing measured with a +7.46
     * bpm bias". **It is not a bias, and they are not measuring the same thing.** Over ten days of
     * 白い熊's data, with each spot reading compared against the periodic readings either side of it:
     *
     * | when | median gap | readings > +15 bpm |
     * |---|---|---|
     * | asleep and still | **+1.0 bpm** | **0 %** |
     * | awake, no steps | +3.5 bpm | 7.8 % |
     * | 21–100 steps nearby | +10.5 bpm | 27.5 % |
     * | over 100 steps nearby | **+22.0 bpm** | 72.5 % |
     *
     * A calibration offset would survive sleep. This one vanishes. What is left is that **the spot
     * reading tracks exertion and the periodic series does not**: walking at 130 steps/min the
     * periodic series reads a median of 58 bpm — below its own resting median of 66 — while the spot
     * reading reads 89. Nor is the spot reading the classic cadence-lock artefact: at 130 steps/min
     * it sits 42 bpm BELOW the cadence, where an artefact locks on to it. It owns the day's maximum
     * on 9 of 10 days.
     *
     * So the periodic series behaves like a slowly-updated resting baseline, and the spot reading
     * like a real measurement of the moment. Both are kept, and neither is allowed to stand for the
     * other.
     *
     * ## Which is why the mark is [RenderKind.LINE_WITH_SPOTS]
     *
     * **The spot readings get the curve**, and the periodic series is relegated to hollow dots. That
     * is the way round it is because of which one can be believed: the curve should carry the series
     * that tracks the heart, and during any activity that is the ten-minutely spot reading. It was
     * built the other way round first, for a day, on the reasoning that a curve suits a slow-moving
     * quantity — but that gave the prominent mark to the readings that are wrong exactly when they
     * matter, so 白い熊 had it swapped.
     *
     * The cost is a sparser curve: six samples an hour rather than twenty-four, and a line that
     * breaks wherever the spot series pauses for more than half an hour. The benefit is that the
     * shape on screen is the shape of the heart rate.
     *
     * Every reading is still drawn. Pooling them into one line would draw a sawtooth that is an
     * artefact of the interleaving; pooling them into one hourly capsule hid both. The headline range
     * and the day table stay pooled, which is why they still match Hume's.
     */
    val HEART_RATE = MetricSpec(
        key = BandMetric.HEART_RATE,
        label = Loc("Heart Rate", "心拍"),
        unit = "bpm",
        color = ChartPalette.HEART_RATE,
        render = RenderKind.LINE_WITH_SPOTS,
        cadenceSec = 120,
        validMin = 25.0,
        validMax = 250.0,
        zeroIsNoReading = true,
        slewPerStep = 40.0,
        hampelHalfWindow = 3,
        hampelSigmas = 3.5,
        hampelMinScale = 2.0,
        yMin = 40.0,
        yMax = 180.0,
        decimals = 0,
        mixedCadence = true,
        // The low–high the card has always headlined. Stated rather than inherited from the mark,
        // so changing the mark could not quietly change it.
        headlineIsRange = true,
        splitPopulations = true,
        bands = listOf(
            BandRung(Loc("Very low", "とても低い"), 45.0, ChartPalette.BAND_CRITICAL),
            BandRung(Loc("Low", "低い"), 55.0, ChartPalette.BAND_WARN),
            BandRung(Loc("Standard", "標準"), 75.0, ChartPalette.BAND_GOOD),
            BandRung(Loc("High", "高い"), Double.MAX_VALUE, ChartPalette.BAND_SERIOUS),
        ),
        info = MetricInfo(
            whatItIs = Loc(
                "Beats per minute — the most direct read on cardiovascular state. A lower resting " +
                    "rate generally means a more efficient heart.",
                "一分あたりの拍動数。心血管の状態をもっとも直接に映す数字で、安静時が低いほど" +
                    "一般に心臓の効率が良い。",
            ),
            howMeasured = Loc(
                "Optical sensor at the wrist (PPG), in two interleaved series. Both are real " +
                    "measurements: asleep and still they agree to within 1 bpm. They part company " +
                    "as soon as you move. The periodic series — every two minutes, of which the " +
                    "band fills about 40 % — stops following your heart under wrist motion: walking " +
                    "it reads a median of 64, which is 4 bpm BELOW your own resting level over the " +
                    "previous half hour, and a heart rate cannot fall while you walk. The ten-minute " +
                    "reading taken alongside blood oxygen does follow it: walking it reads 86, some " +
                    "18 bpm above the same baseline, and it owns the day's maximum on 9 days in 10.",
                "手首の光学式センサー（PPG）。二つの系列が交互に入る。どちらも実測であって、" +
                    "眠っていて動かないときは 1 bpm 以内で一致する。離れるのは動いた瞬間から。" +
                    "定期測定（二分ごと、実際に埋まるのは四割ほど）は手首が動くと心拍を追えなくなる。" +
                    "歩行中の中央値は 64 で、直前三十分の自分の安静値より 4 bpm 低い。" +
                    "歩いていて心拍が下がることはない。血中酸素と同時に取られる十分ごとの測定は" +
                    "きちんと追う。歩行中は 86、同じ基準より 18 bpm 高く、" +
                    "十日のうち九日はその日の最高値を出している。",
            ),
            howToRead = Loc(
                "The line is the ten-minute reading taken with blood oxygen — the one that follows " +
                    "your heart whatever you are doing, so it is the one drawn boldly. The hollow " +
                    "dots are the two-minute periodic series. At rest the dots sit on the line, " +
                    "because there both are right. **When the dots fall well below the line you " +
                    "were moving**, and it is the dots that are under-reading, not the line that is " +
                    "wrong. Nothing is averaged or summarised; shaded stretches are where the band " +
                    "recorded nothing at all.",
                "折れ線は血中酸素と同時に取られる十分ごとの測定 — 何をしていても心拍を追える" +
                    "ほうなので、こちらを太く描く。白抜きの点は二分ごとの定期測定。" +
                    "安静時は点が線の上に乗る。そこではどちらも正しいから。" +
                    "**点が線よりはっきり下に落ちていたら動いていた合図**で、" +
                    "低く出ているのは点のほうであって、線が誤っているのではない。" +
                    "平均も要約もしない。影の部分はバンドが何も記録していない区間。",
            ),
            caveat = Loc(
                "The band edges (45 / 55 / 75 bpm) are ordinary resting-rate guidance, not a " +
                    "diagnostic threshold for any individual. And the dots must not be read as your " +
                    "heart rate during activity: the periodic series is a genuine measurement that " +
                    "loses the pulse under wrist motion, and its failure mode is to read slightly " +
                    "low rather than to report nothing. Only six readings an hour carry the line, " +
                    "so it says nothing about what happened between two of them.",
                "帯の区切り（45 / 55 / 75 bpm）は一般的な安静時心拍の目安であって、" +
                    "個人の診断基準ではない。また、運動中の心拍として点を読んではいけない。" +
                    "定期測定も実測ではあるが、手首が動くと脈を見失う。しかも「測れない」とは言わず、" +
                    "少し低い値を出してくるのが厄介なところ。線を支えるのは一時間に六点だけなので、" +
                    "点と点のあいだに何があったかは分からない。",
            ),
        ),
    )

    val HRV = MetricSpec(
        key = BandMetric.HRV,
        label = Loc("Band State Index", "バンド状態指数"),
        // No unit. The band labels it milliseconds; it is not a duration, and carrying "ms" was the
        // last thing on the card still asserting it was HRV.
        unit = "",
        color = ChartPalette.HRV,
        render = RenderKind.LINE,
        cadenceSec = 120,
        validMin = 3.0,
        validMax = 400.0,
        zeroIsNoReading = true,
        slewPerStep = null,
        hampelHalfWindow = 3,
        hampelSigmas = 3.0,
        hampelMinScale = 3.0,
        yMin = 0.0,
        yMax = 150.0,
        decimals = 0,
        // No band ladder. We do not know what "good" means for a device state index, and inventing
        // a Low/Standard/High scale for it would be exactly the pretence this rename removes.
        bands = emptyList(),
        info = MetricInfo(
            whatItIs = Loc(
                "A 0–100 number the band reports under the label \"HRV\". It is NOT heart-rate " +
                    "variability. Analysis of 2 131 records identified it as a device state index: " +
                    "74 % of its variance is fixed by two firmware flags — whether the band thinks " +
                    "you are asleep, and whether the optical read succeeded.",
                "バンドが「HRV」として返してくる 0〜100 の数値。**心拍変動ではありません。** " +
                    "2 131 件を解析した結果、これは端末の状態を表す指数でした — " +
                    "分散の 74 % が二つのファームウェア上のフラグ（眠っていると判定しているか、" +
                    "光学測定が成功したか）だけで決まります。",
            ),
            howMeasured = Loc(
                "Offset [9] of the HRV record, every two minutes. Records come in two kinds: those " +
                    "carrying a heart rate and blood pressure (1 644 of them, values 15–94) and " +
                    "those where that whole triple failed (487, values 50–99). Nothing is ever " +
                    "partially missing — it is a whole-record success or failure. The apparent " +
                    "15–99 range is those two populations pooled.",
                "HRV レコードのオフセット [9]、二分ごと。レコードには二種類あります — " +
                    "心拍と血圧を伴うもの（1 644 件、値 15〜94）と、その三つがまるごと欠けるもの" +
                    "（487 件、値 50〜99）。一部だけ欠けることは一度もなく、成功か失敗かのどちらか。" +
                    "見かけの 15〜99 という幅は、この二群を混ぜたことで生まれています。",
            ),
            howToRead = Loc(
                "Split by record type, because pooling them is what manufactured the range. Within " +
                    "each type it is close to random from one record to the next. Read it as \"which " +
                    "state was the band in\", not as a physiological trace.",
                "レコードの種類ごとに分けて描いてあります。混ぜると幅が生まれてしまうからです。" +
                    "同じ種類の中では、一件ごとにほぼランダムに動きます。" +
                    "「バンドがどの状態にいたか」として読むものであって、生体の推移ではありません。",
            ),
            caveat = Loc(
                "The evidence it is not HRV: it correlates POSITIVELY with heart rate (+0.383 " +
                    "pooled, +0.179 asleep) where every real variability metric is negative; it " +
                    "carries no sleep-stage information at all (deep 21.0, light 21.0, REM 20.5); " +
                    "it switches at the sleep boundary within one or two records where heart rate " +
                    "ramps over 40 minutes; and it is near-random within a state where the same " +
                    "record's heart rate is strongly autocorrelated.\n\n" +
                    "It is not motion artefact either. Awake-but-stationary (no steps for 15 " +
                    "minutes, median 48) looks like awake-and-moving (54), not like asleep (21).\n\n" +
                    "The band never sends beat intervals, so no real HRV could be computed from what " +
                    "it transmits. It has been removed from 健康指数 and is drawn here only because " +
                    "the record exists.",
                "心拍変動でない根拠：心拍と**正**の相関を持つ（全体 +0.383、睡眠中 +0.179）— " +
                    "本物の変動指標はどれも負の相関になります。睡眠段階の情報をまったく持たない" +
                    "（深い 21.0、浅い 21.0、REM 20.5）。心拍が四十分かけて滑らかに変わる境目で、" +
                    "この数字は一〜二件で切り替わる。同じ種類の中ではほぼランダムに動く一方、" +
                    "同じレコードの心拍はしっかり自己相関を持つ。\n\n" +
                    "体動によるノイズでもありません。起きていて歩いていない時間（十五分歩数ゼロ、" +
                    "中央値 48）は、眠っている時間（21）ではなく歩いている時間（54）に似ています。\n\n" +
                    "バンドは拍間隔そのものを送ってこないので、そもそも本物の心拍変動は計算できません。" +
                    "健康指数からは外しました。ここに描いてあるのは、記録が存在するからです。",
            ),
        ),
    )

    val SPO2 = MetricSpec(
        key = BandMetric.SPO2,
        label = Loc("Blood Oxygen", "血中酸素"),
        unit = "%",
        color = ChartPalette.SPO2,
        render = RenderKind.CAPSULE,
        cadenceSec = 600,
        validMin = 70.0,
        validMax = 100.0,
        zeroIsNoReading = true,
        // NO slew gate. A ">3 %" limit was proposed as a bound on "a single 120 s step" — but SpO₂ is
        // sampled every TEN minutes, and over ten minutes a swing of several points is ordinary
        // physiology. Measured on real data a 3-point limit flagged 53 of 430 adjacent pairs: 12.3 %
        // of real readings, in a series running 91–100 with nothing out of range at all.
        slewPerStep = null,
        hampelHalfWindow = 2,
        hampelSigmas = 3.0,
        hampelMinScale = 1.0,
        // 88–100 rather than 70–100: on a full axis a real desaturation is a couple of pixels. The
        // break marker at the baseline is what keeps the truncation honest instead of hidden.
        yMin = 88.0,
        yMax = 100.0,
        decimals = 0,
        axisBreak = true,
        bands = listOf(
            BandRung(Loc("Very low", "とても低い"), 90.0, ChartPalette.BAND_CRITICAL),
            BandRung(Loc("Low", "低い"), 94.0, ChartPalette.BAND_WARN),
            BandRung(Loc("Standard", "標準"), 97.0, ChartPalette.BAND_GOOD),
            BandRung(Loc("High", "高い"), Double.MAX_VALUE, ChartPalette.BAND_GOOD),
        ),
        info = MetricInfo(
            whatItIs = Loc(
                "How well the blood is carrying oxygen. A healthy range is roughly 95–100 %.",
                "血液が酸素をどれだけ運べているか。健常な範囲はおおむね 95〜100 %。",
            ),
            howMeasured = Loc(
                "From the difference in red and infrared absorption, roughly every ten minutes. A " +
                    "wrist optical reading is far less accurate than a fingertip pulse oximeter and " +
                    "is easily thrown off by a loose strap or a moving arm.",
                "赤色光と赤外光の吸収差から、およそ十分ごと。手首の光学式は指先の" +
                    "パルスオキシメーターより誤差が大きく、装着の緩さや腕の動きで簡単に狂う。",
            ),
            howToRead = Loc(
                "The axis starts at 88 %, because drawing from zero squashes a genuine desaturation " +
                    "into a couple of pixels. The break mark at the baseline is there so the " +
                    "truncation is visible rather than hidden.",
                "縦軸は 88 % から始めてある。0 から描くと本当の低下が数ピクセルに潰れるため。" +
                    "軸の切れ目はそのことを隠さないための印。",
            ),
            caveat = Loc(
                "Not a medical device. A run of low readings means more than any single one.",
                "医療機器ではない。ひとつの低い値より、続けて低いことのほうが意味がある。",
            ),
        ),
    )

    val TEMPERATURE = MetricSpec(
        key = BandMetric.TEMPERATURE,
        label = Loc("Body Temperature", "体温"),
        unit = "°C",
        color = ChartPalette.TEMPERATURE,
        render = RenderKind.LINE,
        cadenceSec = 1800,
        validMin = 30.0,
        validMax = 45.0,
        zeroIsNoReading = true,
        // Same reasoning as SpO₂, more so: half a degree across THIRTY minutes is normal, and the
        // gate dropped four real readings on the reference data.
        slewPerStep = null,
        hampelHalfWindow = 2,
        hampelSigmas = 3.0,
        hampelMinScale = 0.15,
        yMin = 34.0,
        yMax = 38.0,
        decimals = 1,
        bands = listOf(
            BandRung(Loc("Very low", "とても低い"), 35.5, ChartPalette.BAND_CRITICAL),
            BandRung(Loc("Low", "低い"), 36.5, ChartPalette.BAND_WARN),
            BandRung(Loc("Standard", "標準"), 37.5, ChartPalette.BAND_GOOD),
            BandRung(Loc("High", "高い"), Double.MAX_VALUE, ChartPalette.BAND_SERIOUS),
        ),
        info = MetricInfo(
            whatItIs = Loc(
                "Body temperature estimated from wrist skin temperature. Not core temperature.",
                "手首の皮膚温から推定した体温。深部体温そのものではない。",
            ),
            howMeasured = Loc(
                "A temperature sensor against the skin, roughly every thirty minutes.",
                "皮膚に接する温度センサーで、およそ三十分ごと。",
            ),
            howToRead = Loc(
                "It rises and falls naturally through the day, lowest before dawn. Look for a " +
                    "departure from your usual daily shape rather than at the absolute value.",
                "一日のうちで自然に上下する（夜明け前が最も低い）。" +
                    "絶対値より、いつもの日内変動から外れたかどうかを見る。",
            ),
            caveat = Loc(
                "Moves a lot with air temperature, bathing and strap tightness. It is not a " +
                    "substitute for a thermometer.",
                "外気温・入浴・装着の緩さで大きく動く。体温計の代わりにはならない。",
            ),
        ),
    )

    /**
     * The byte at HRV-record offset `[12]`.
     *
     * Deliberately NOT called ストレス. It is identical to offset `[10]` — which we store as
     * `vascular` — in **2038 of 2038 samples**, and in both golden frames. One of those two labels is
     * wrong, and the data cannot tell us which. It also does not behave like a stress index: asleep it
     * sits at ~45 in 850 of 872 samples, awake it scatters 10–99, where a real stress measure falls
     * during sleep.
     */
    val BAND_INDEX = MetricSpec(
        key = BandMetric.STRESS,
        label = Loc("Band Index", "バンド指標"),
        unit = "",
        color = ChartPalette.BAND_INDEX,
        render = RenderKind.LINE,
        cadenceSec = 120,
        validMin = 0.0,
        validMax = 100.0,
        zeroIsNoReading = true,
        slewPerStep = null,
        hampelHalfWindow = 3,
        hampelSigmas = 3.0,
        hampelMinScale = 3.0,
        yMin = 0.0,
        yMax = 100.0,
        decimals = 0,
        bands = emptyList(),   // no ladder: we do not know what "good" means for this number
        info = MetricInfo(
            whatItIs = Loc(
                "A 0–100 number the band includes in its HRV records. The Hume app displays it as " +
                    "\"Stress\".",
                "バンドが HRV レコードの中に入れてくる 0〜100 の数値。Hume のアプリは" +
                    "これを「ストレス」として表示している。",
            ),
            howMeasured = Loc(
                "Offset [12] of the HRV record — except that it is IDENTICAL to offset [10] (which " +
                    "we store as vascular age) in 2038 of 2038 samples, and in both golden frames " +
                    "captured off the band. One number, stored under two names.",
                "HRV レコードのオフセット [12]。ただし **オフセット [10]（血管年齢として" +
                    "保存しているもの）と 2038 件中 2038 件で完全に同じ値** で、取得した二つの" +
                    "ゴールデンフレームでも一致している。つまり同じ数字を二つの名前で保存している。",
            ),
            howToRead = Loc(
                "Drawn exactly as it arrives. No filtering, no transformation.",
                "そのまま描いてある。フィルタも変換もしていない。",
            ),
            caveat = Loc(
                "There is no evidence this is stress. Asleep it sits at 40–49 in 850 of 872 " +
                    "samples; awake it scatters across 10–99. A real stress measure falls during " +
                    "sleep — this does the opposite.\n\n" +
                    "Measuring stress from HRV is real, validated science (Baevsky's Stress Index: " +
                    "normal range 80–150, rising 1.5–2× under mild and 5–10× under severe stress). " +
                    "But every such index needs the beat-to-beat intervals themselves (RR " +
                    "intervals), and this band never sends them. So it is unlikely this number is " +
                    "one of them.\n\n" +
                    "The units are unknown too. The number is kept; no meaning is attached to it.",
                "**これがストレスである根拠はない。** 眠っている間は 872 件中 850 件が 40〜49 に" +
                    "貼りついていて、起きている間だけ 10〜99 に散らばる — 本物のストレス指標なら" +
                    "睡眠中に下がるはずで、逆の振る舞い。\n\n" +
                    "HRV からストレスを測る方法自体は実在し、検証もされている（Baevsky の Stress Index：" +
                    "正常域 80〜150、軽いストレスで 1.5〜2 倍、強いストレスで 5〜10 倍）。" +
                    "しかしそれには拍と拍の間隔そのもの（RR 間隔）が要る。**このバンドは RR 間隔を" +
                    "送ってこない。** だからこの数字がその種の指標である可能性は低い。\n\n" +
                    "単位も不明。数字は残すが、意味づけはしない。",
            ),
        ),
    )

    val STEPS = MetricSpec(
        key = BandMetric.STEPS_MINUTE,
        label = Loc("Steps", "歩数"),
        unit = "歩",
        color = ChartPalette.STEPS,
        render = RenderKind.BARS,
        cadenceSec = 60,
        validMin = 0.0,
        validMax = 400.0,
        // 0 is a REAL step count — you stood still — not a sentinel.
        zeroIsNoReading = false,
        slewPerStep = null,
        // Never filtered. Per-minute counts are zero-inflated and heavy-tailed: over a mostly-zero
        // window the MAD is exactly 0, so with the floor everything non-zero clears the bar and
        // without it everything non-zero is flagged. Neither is useful.
        hampelHalfWindow = 0,
        hampelSigmas = 0.0,
        hampelMinScale = 1.0,
        yMin = 0.0,
        yMax = 120.0,
        decimals = 0,
        bands = listOf(
            BandRung(Loc("Low", "低い"), 3000.0, ChartPalette.BAND_SERIOUS),
            BandRung(Loc("Standard", "標準"), 7500.0, ChartPalette.BAND_GOOD),
            BandRung(Loc("High", "高い"), 12000.0, ChartPalette.BAND_GOOD),
            BandRung(Loc("Very high", "とても高い"), Double.MAX_VALUE, ChartPalette.BAND_GOOD),
        ),
        info = MetricInfo(
            whatItIs = Loc(
                "Steps taken. The band guidance refers to a daily total.",
                "歩いた歩数。帯の目安は一日あたりの合計に対するもの。",
            ),
            howMeasured = Loc(
                "From the accelerometer. The band packs ten minutes into one record, holding ten " +
                    "per-minute counts, and those ten run FORWARD from the record's timestamp — " +
                    "confirmed against all 87 reference records.",
                "加速度センサーから。バンドは十分ぶんをひとつの記録にまとめ、" +
                    "その中に一分ごとの十個の数を入れてくる。この十個は記録の時刻から**前向きに**並ぶ" +
                    "（87 件すべてで確認済み）。",
            ),
            howToRead = Loc(
                "A minute with no bar means you did not walk, not that nothing was measured. Zero " +
                    "is a real reading here, which is why steps alone are never treated as missing.",
                "棒がない分は「歩いていない」であって「測れていない」ではない。" +
                    "0 は実測値なので、他の指標と違って欠測とは区別される。",
            ),
            caveat = Loc(
                "Wrist-worn, so movement that does not swing the arm — pushing, carrying — is " +
                    "undercounted.",
                "手首式なので、押す・運ぶといった腕を振らない移動は数えにくい。",
            ),
        ),
    )

    /** The line metrics and everything else, in the order they stack on the dashboard. */
    /**
     * The dashboard order.
     *
     * [BAND_INDEX] is deliberately absent: its byte is reconstructible from [HRV]'s plus the record
     * type — 487/487 exactly for one record type and 1 632/1 644 for the other, with the residual
     * being a five-value uniform dither. It is one number wearing three names (`hrv`, `vascular`,
     * `stress`), and charting the derived copy adds nothing. The spec is kept so the decoder and the
     * archive stay unchanged and the finding stays checkable.
     */
    val ALL: List<MetricSpec> = listOf(HEART_RATE, HRV, SPO2, TEMPERATURE, STEPS)

    /** Metrics whose data is a simple sample series keyed by one `band_samples.metric`. */
    val SAMPLE_SERIES: List<MetricSpec> = ALL

    fun byKey(key: String): MetricSpec? = ALL.firstOrNull { it.key == key }

    // --- the two composite screens, which are not single sample series ------------------------

    const val KEY_BLOOD_PRESSURE = "bp"
    const val KEY_SLEEP = "sleep"
    const val KEY_INDEX = "index"
    const val KEY_RECOVERY = "recovery"
    const val KEY_MARK_SESSION = "mark_session"
    const val KEY_REGISTER = "register"

    val BLOOD_PRESSURE_INFO = MetricInfo(
        whatItIs = Loc(
            "Systolic (upper) and diastolic (lower) pressure. Each hour is one bar spanning the " +
                "range actually measured in it.",
            "収縮期（上）と拡張期（下）の血圧。ひとつの時間につき、その時間に実際に" +
                "測れた範囲を一本の棒で描く。",
        ),
        howMeasured = Loc(
            "Estimated from the shape of the pulse wave, not measured with a cuff. Offsets [13] and " +
                "[14] of the HRV record.",
            "脈波の形から推定したもので、カフで測ったものではない。" +
                "HRV レコードのオフセット [13][14]。",
        ),
        howToRead = Loc(
            "Both sit on one vertical axis, because they share a unit — that is the correct way to " +
                "draw them. With two axes, any two series can be made to look correlated by " +
                "choosing the scales.",
            "上下とも同じ一本の縦軸に載せてある。単位が同じだから、そうするのが正しい。" +
                "軸を二本にすると、目盛りの取り方しだいでどんな二つの系列も相関して見えてしまう。",
        ),
        caveat = Loc(
            "These numbers are generated, not measured, and the evidence is now conclusive.\n\n" +
                "Across 1 644 records systolic occupies EVERY integer from 110 to 129 and never once " +
                "leaves it; diastolic every integer from 60 to 79 and never once leaves it. Those " +
                "are 120 ± 10 and 70 ± 10 — the ±10 mmHg window this class of SDK clamps its output " +
                "to around a calibration figure. Over six days of ordinary life, real systolic " +
                "pressure does not stay inside a twenty-point box.\n\n" +
                "They also carry no memory: lag-1 autocorrelation −0.015 and −0.020, with mean|Δ|/σ " +
                "of 1.14 and 1.16 against 1.13 for independent random draws. Real blood pressure two " +
                "minutes apart is strongly correlated. On the SAME records heart rate holds +0.59, " +
                "so the analysis is not simply insensitive.\n\n" +
                "The chart is kept deliberately, but there is no trend in it to read.\n\n" +
                "Separately, the FDA issued a safety communication in September 2025 telling " +
                "consumers not to use unauthorised smartwatch or smart-ring apps to measure blood " +
                "pressure. This band has never been calibrated against a cuff.",
            "**この数字は測定ではなく生成されたものです。証拠は決定的です。**\n\n" +
                "1 644 件を通して、収縮期は 110〜129 の整数を**すべて**取り、そこから一度も出ません。" +
                "拡張期も 60〜79 の整数をすべて取り、一度も出ません。これは 120±10 と 70±10 — " +
                "この種の SDK が較正値のまわりに出力を押し込める ±10 mmHg の窓そのものです。" +
                "六日間ふつうに生活して、本物の収縮期血圧が二十の幅の箱から一度も出ないことは" +
                "ありません。\n\n" +
                "記憶もありません：一次の自己相関は −0.015 と −0.020、平均|差|/標準偏差は 1.14 と " +
                "1.16 — 独立に無作為抽出した場合の 1.13 とほぼ同じ。本物の血圧は二分後の値と" +
                "強く相関します。**同じレコード**の心拍は +0.59 を保つので、解析の感度不足では" +
                "ありません。\n\n" +
                "グラフは意図して残していますが、読み取るべき傾向はありません。\n\n" +
                "なお FDA は 2025 年 9 月、認可されていないスマートウォッチ／スマートリングの" +
                "アプリで血圧を測らないよう注意喚起を出しています。このバンドはカフで" +
                "較正されたことが一度もありません。",
        ),
    )

    val SLEEP_INFO = MetricInfo(
        whatItIs = Loc(
            "How sleep stage moved through the night: deep, light, REM and awake.",
            "睡眠段階の推移。深い・浅い・REM・覚醒の四つ。",
        ),
        howMeasured = Loc(
            "One stage code per minute. The band returns a night as several segments of at most 120 " +
                "minutes each, so contiguous segments are stitched back into one session.",
            "一分ごとに一つの段階コード。バンドは一晩を複数の区間に分けて返す" +
                "（一区間は最大 120 分）ので、続いた区間をつなぎ直して一晩にしている。",
        ),
        howToRead = Loc(
            "Stages are categorical — there is no value between deep and light — so nothing is " +
                "interpolated between points. Stepping is the correct way to draw it.",
            "段階は「categorical」— 深いと浅いの中間という値は存在しないので、" +
                "点と点の間を補間しない。階段状に描くのが正しい。",
        ),
        caveat = Loc(
            "Code 4 has never appeared in 2 970 stage-minutes across six nights. We read 1/2/3/5 as " +
                "deep/light/REM/awake — these are the band's RAW codes, which differ from the " +
                "numbering the vendor's own plugin uses (it swaps REM and awake).",
            "コード 4 は 2 970 分（六夜）で一度も出ていない。1/2/3/5 = 深い/浅い/REM/覚醒 として" +
                "扱っているが、これはバンドの生コードで、メーカーのプラグインが使う番号とは違う" +
                "（あちらは REM と覚醒が入れ替わる）。",
        ),
    )
}
