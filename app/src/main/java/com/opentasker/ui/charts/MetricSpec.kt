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
     * Heart rate.
     *
     * The `hr` stream carries TWO populations: a periodic series and an extra reading taken at each
     * SpO₂ measurement, running +7.46 bpm higher. The line splits them (`ChartQualify.splitHeartRate`)
     * because merged they draw a sawtooth; the hourly capsules POOL them, because Hume's own day range
     * matches the pooled population and dropping the coincident readings would clip every capsule.
     */
    val HEART_RATE = MetricSpec(
        key = BandMetric.HEART_RATE,
        label = Loc("Heart Rate", "心拍"),
        unit = "bpm",
        color = ChartPalette.HEART_RATE,
        render = RenderKind.CAPSULE,
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
                "Optical sensor at the wrist (PPG), roughly every two minutes — plus one extra " +
                    "reading taken under a different measurement mode each time blood oxygen is " +
                    "sampled. That second population measures +7.46 bpm higher, so the line splits " +
                    "them and the hourly ranges pool them.",
                "手首の光学式センサー（PPG）で、およそ二分ごと。加えて血中酸素を測るたびに" +
                    "もう一回、別の測定モードで取られる。この二つ目は実測で +7.46 bpm 高いので、" +
                    "折れ線では分けて描き、時間ごとの範囲では合わせて使う。",
            ),
            howToRead = Loc(
                "Each capsule is the lowest and highest reading actually taken in that hour — not " +
                    "an average. Short capsules are settled hours; long ones mean you were moving, " +
                    "or tense.",
                "縦棒は「その一時間に実際に測れた最小値と最大値」。平均ではない。" +
                    "棒が短い時間は落ち着いていて、長い時間は動いていたか、緊張していた。",
            ),
            caveat = Loc(
                "The band edges (45 / 55 / 75 bpm) are ordinary resting-rate guidance, not a " +
                    "diagnostic threshold for any individual.",
                "帯の区切り（45 / 55 / 75 bpm）は一般的な安静時心拍の目安であって、" +
                    "個人の診断基準ではない。",
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
