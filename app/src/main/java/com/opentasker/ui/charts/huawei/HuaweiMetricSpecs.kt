package com.opentasker.ui.charts.huawei

import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.MetricInfo
import com.opentasker.ui.charts.MetricSpec
import com.opentasker.ui.charts.RenderKind

/**
 * The Huawei band's chart rows.
 *
 * The type is reused from the Hume band unchanged — it was never device-specific — but not one gate
 * value is. Every number in `MetricSpecs` was measured from Hume's output on 白い熊's wrist over days
 * of wear, and copying those figures across would be dressing a guess as a measurement.
 *
 * ## The standing rule for this table
 *
 * **Every filter and every tuned gate starts OFF**: `hampelHalfWindow = 0`, `slewPerStep = null`,
 * `bands = emptyList()`, `provisional = true`.
 *
 * A Hampel filter is an instrument tuned to a known signal. Pointed at an uncharacterised one it
 * does not decline to act — it manufactures rejections, and it did exactly that to Hume's pooled
 * heart rate, flagging 102 perfectly real readings. Drawing ✕ marks on this band's data today would
 * be inventing evidence about a device we have measured for hours.
 *
 * `mixedCadence = true` throughout for the same reason: its documented behaviour is to take the gap
 * threshold from a high percentile rather than a median, which is the right instrument for a series
 * whose real cadence is unknown.
 *
 * What DOES transfer is anything physiological rather than device-derived — a heart rate cannot be
 * 400 bpm whichever band reports it — so `validMin`/`validMax`, `unit`, `decimals` and SpO₂'s
 * `axisBreak` are carried over as facts about the quantity, not about the instrument.
 *
 * The coverage card is what retires this rule: it measures each metric's real inter-sample gaps, and
 * when there are enough of them the provisional rows get replaced with figures that were observed.
 */
object HuaweiMetricSpecs {

    /** Loc carries no plus operator; caveats are built from a fact and a standing disclaimer. */
    private fun Loc.then(other: Loc) = Loc(en + other.en, ja + other.ja)

    /** Rendered on the dashboard, in the order the cards are drawn — the order is a colour decision. */
    val ALL: List<MetricSpec> by lazy { listOf(STEPS, HEART_RATE, SPO2, RESTING_HEART_RATE) }

    /** Carried, charted only inside the 診断 card, and never presented as a measurement. */
    val DIAGNOSTIC: List<MetricSpec> by lazy { listOf(CALORIES, DISTANCE) }

    const val KEY_COVERAGE = "hw:coverage"
    const val KEY_DIAGNOSTICS = "hw:diagnostics"

    fun byKey(key: String): MetricSpec? =
        (ALL + DIAGNOSTIC).firstOrNull { it.key == key || it.key == HuaweiKeys.qualify(key) }

    private val NOT_TUNED = Loc(
        "No gate on this row has been tuned yet: outlier rejection and the slew limit are both off, "
            + "and what the band returned is what is drawn.",

        "測定値による調整はまだ行っていません。異常値の除外も傾きの制限も無効で、"
            + "バンドが返した値をそのまま描いています。",
)

    // --- steps ---------------------------------------------------------------------------------

    val STEPS = MetricSpec(
        key = HuaweiKeys.STEPS,
        label = Loc("Steps", "歩数"),
        unit = "歩",
        color = ChartPalette.STEPS,
        render = RenderKind.BARS,
        cadenceSec = 60,
        // The band sends nothing for a minute in which nothing was walked, so silence here is a
        // measured zero. Without this the ten hours 白い熊 spent asleep were tinted as missing data.
        absentIsZero = true,
        validMin = 0.0,
        // A physical bound, not a tuning: nobody takes four hundred steps inside one minute. This is
        // the one gate on the table that is ON, because it needed no measuring.
        validMax = 400.0,
        zeroIsNoReading = false,
        slewPerStep = null,
        hampelHalfWindow = 0,
        hampelSigmas = 0.0,
        hampelMinScale = 0.0,
        yMin = 0.0,
        yMax = 120.0,
        decimals = 0,
        bands = emptyList(),
        mixedCadence = true,
        provisional = true,
        info = MetricInfo(
            whatItIs = Loc(
                "Steps counted within that one minute.",

                "その一分間に数えた歩数。",
),
            howMeasured = Loc(
                "The band writes into a per-minute grid. A minute it did not measure has no row at all.",

                "バンドが一分ごとの升目に書き込む。測っていない分は行そのものが無い。",
),
            howToRead = Loc(
                "A minute with no bar means NOT MEASURED, not that you did not walk. "
                    + "This is the opposite of the Hume band.",

                "棒の無い分は「測っていない」であって「歩いていない」ではない。ここが Hume と逆。",
),
            caveat = Loc(
                "**Absent rows mean the OPPOSITE of the Hume band's.** Hume drops zeros at parse "
                    + "time, so a minute with no bar means you did not walk. This band keeps a "
                    + "recorded zero, so an absent minute means it did not measure. A daily total "
                    + "comes out the same either way; a count of active minutes does not. The two "
                    + "bands are compared at ten-minute buckets only, never per minute.",

                "**Hume の歩数とは、行が無いことの意味が正反対。** Hume は解析時に 0 を捨てるので、"
                    + "棒の無い分は「歩いていない」。こちらは記録された 0 を残すので、行が無い分は"
                    + "「測っていない」。一日の合計はどちらでも同じになるが、"
                    + "「動いていた分数」を数えると食い違う。二台を並べるときは 10 分単位でのみ比較すること。",
),
        ),
    )

    // --- heart rate ----------------------------------------------------------------------------

    val HEART_RATE = MetricSpec(
        key = HuaweiKeys.HEART_RATE,
        label = Loc("Heart Rate", "心拍"),
        unit = "bpm",
        color = ChartPalette.HEART_RATE,
        render = RenderKind.LINE,
        // A loose placeholder, NOT a measurement. The real cadence is unknown; the coverage card is
        // what will replace this figure with an observed one.
        cadenceSec = 300,
        validMin = 25.0,
        validMax = 250.0,
        zeroIsNoReading = true,
        slewPerStep = null,
        hampelHalfWindow = 0,
        hampelSigmas = 0.0,
        hampelMinScale = 0.0,
        yMin = 40.0,
        yMax = 140.0,
        decimals = 0,
        bands = emptyList(),
        mixedCadence = true,
        headlineIsRange = true,
        provisional = true,
        info = MetricInfo(
            whatItIs = Loc( "Heart rate as this band measured it.",
"バンドが測った心拍数。",
),
            howMeasured = Loc(
                "Written into the per-minute grid, in whatever minutes it managed a reading.",

                "一分ごとの升目に、測れた分だけ書き込まれる。",
),
            howToRead = Loc(
                "No band ladder is drawn: this device's own distribution has not been measured yet.",

                "帯の目安は付けていない。この機種の分布をまだ測っていないため。",
),
            caveat = Loc(
                "**Same unit as the Hume band's heart rate; not the same thing.** Hume's series "
                    + "carries two populations, one of which loses the pulse under wrist motion. "
                    + "Whether this band's is one population or two has not been established. "
                    + "The two devices' readings are never averaged together.\n\n",

                "**Hume の心拍と同じ単位だが、同じものではない。** Hume の系列は二種類が混ざっており、"
                    + "そのうち定期測定は手首が動くと脈を見失う。こちらが一種類なのか二種類なのかも"
                    + "まだ分かっていない。二台の値を平均してはいけない。\n\n",
).then(NOT_TUNED),
        ),
    )

    // --- blood oxygen --------------------------------------------------------------------------

    val SPO2 = MetricSpec(
        key = HuaweiKeys.SPO2,
        label = Loc("Blood Oxygen", "血中酸素"),
        unit = "%",
        color = ChartPalette.SPO2,
        render = RenderKind.LINE,
        cadenceSec = 600,
        validMin = 70.0,
        validMax = 100.0,
        zeroIsNoReading = true,
        slewPerStep = null,
        hampelHalfWindow = 0,
        hampelSigmas = 0.0,
        hampelMinScale = 0.0,
        yMin = 88.0,
        yMax = 100.0,
        decimals = 0,
        bands = emptyList(),
        // A property of the quantity, not of the band: the true zero is far below anything a living
        // reading takes, so a full-range axis would flatten every real excursion.
        axisBreak = true,
        mixedCadence = true,
        provisional = true,
        info = MetricInfo(
            whatItIs = Loc( "Peripheral blood oxygen saturation.",
"血中酸素飽和度。",
),
            howMeasured = Loc(
                "Whether this band measures it only at night or opportunistically is not yet known.",

                "夜間だけなのか、随時なのかは未確認。",
),
            howToRead = Loc(
                "No band ladder: drawing one on blood oxygen is a clinical claim, and it has to be "
                    + "earned on this device's own numbers first.",

                "帯の目安は付けていない。血中酸素に目安を引くのは臨床上の主張であり、"
                    + "この機種の値で裏を取ってからにする。",
),
            caveat = NOT_TUNED,
        ),
    )

    // --- resting heart rate --------------------------------------------------------------------

    val RESTING_HEART_RATE = MetricSpec(
        key = HuaweiKeys.RESTING_HR,
        label = Loc("Resting Heart Rate", "安静時心拍"),
        unit = "bpm",
        color = ChartPalette.RESTING_HEART_RATE,
        render = RenderKind.LINE,
        cadenceSec = 3600,
        validMin = 25.0,
        validMax = 250.0,
        zeroIsNoReading = true,
        slewPerStep = null,
        hampelHalfWindow = 0,
        hampelSigmas = 0.0,
        hampelMinScale = 0.0,
        yMin = 40.0,
        yMax = 100.0,
        decimals = 0,
        bands = emptyList(),
        mixedCadence = true,
        provisional = true,
        info = MetricInfo(
            whatItIs = Loc(
                "Resting heart rate as the band reports it — a metric the Hume band never had.",

                "バンドが出す安静時心拍。Hume には無かった指標。",
),
            howMeasured = Loc(
                "Computed inside the band by a method it does not disclose.",

                "バンド内部での算出。方法は公開されていない。",
),
            howToRead = Loc(
                "Expected to move very little within a day. A sudden step is more likely the band "
                    + "recomputing than the body changing.",

                "一日の中でほとんど動かないはずの値。急に変わったら、体調よりも先に"
                    + "「バンドが計算し直しただけ」を疑うこと。",
),
            caveat = Loc(
                "**It is not yet established whether this is a step function or an instantaneous "
                    + "reading.** If it steps, the curve drawn between two points is drawn but not "
                    + "measured.\n\n",

                "**これが階段状の値なのか、その都度の測定値なのかを、まだ確認していない。**"
                    + "階段状だった場合、点と点の間の曲線は描かれてはいるが測られてはいない。\n\n",
).then(NOT_TUNED),
        ),
    )

    // --- raw device units ------------------------------------------------------------------------

    private fun rawUnits(key: String, en: String, ja: String) = MetricSpec(
        key = key,
        label = Loc("$en (raw device units)", "$ja（生の端末単位）"),
        unit = "",
        color = ChartPalette.UNKNOWN,
        render = RenderKind.LINE,
        cadenceSec = 60,
        // Calories and distance are counters like steps, and the band omits them the same way.
        absentIsZero = true,
        validMin = -Double.MAX_VALUE,
        validMax = Double.MAX_VALUE,
        zeroIsNoReading = false,
        slewPerStep = null,
        hampelHalfWindow = 0,
        hampelSigmas = 0.0,
        hampelMinScale = 0.0,
        yMin = 0.0,
        yMax = 100.0,
        decimals = 0,
        bands = emptyList(),
        mixedCadence = true,
        provisional = true,
        info = MetricInfo(
            whatItIs = Loc(
                "A raw number the band returns. Its unit is unknown.",

                "バンドが返す生の数値。単位は不明。",
),
            howMeasured = Loc( "Uncalibrated.",
"未較正。",
),
            howToRead = Loc(
                "Nothing has been converted, so this is not a quantity that can be read as-is.",

                "換算していないので、そのまま読める量ではない。",
),
            caveat = Loc(
                "**Not kilocalories and not metres.** The conversion factor is unknown, so these are "
                    + "deliberately not presented as calories or distance. Walking a known distance "
                    + "and regressing against the Hume band's figures should yield a factor; if one "
                    + "falls out it gets written here and the row is promoted. If it does not, these "
                    + "stay raw permanently, which is an acceptable outcome.",

                "**キロカロリーでもメートルでもない。** 換算の係数がまだ分かっていないので、"
                    + "「カロリー」「距離」として出すことはしない。既知の距離を歩いて Hume の値と"
                    + "突き合わせれば係数が出るはずで、出たらここに書いて表側に昇格させる。"
                    + "出なければ、生のまま置いておく。",
),
        ),
    )

    val CALORIES = rawUnits(HuaweiKeys.CALORIES, "Calories", "カロリー")
    val DISTANCE = rawUnits(HuaweiKeys.DISTANCE, "Distance", "距離")

    /**
     * A row for a feature bit we have not decoded, synthesised rather than hand-written.
     *
     * `HuaweiSyncEngine` carries unknown bits through as `unknown_XX` instead of dropping them, so a
     * firmware change shows up in the data rather than vanishing. Hand-writing a spec for each one
     * would mean inventing a name, a unit and a valid range for a number whose meaning is unknown —
     * so nothing is invented here: no unit, grey, no gates, and nothing is invalid, because we do
     * not know what valid would mean.
     */
    fun forUnknown(storageKey: String): MetricSpec {
        val bit = storageKey.removePrefix("unknown_").removePrefix(HuaweiKeys.PREFIX)
        return rawUnits(
            HuaweiKeys.qualify(storageKey),
            "Unknown field 0x$bit",
            "未知のフィールド 0x$bit",
        ).copy(
            label = Loc("Unknown field 0x$bit", "未知のフィールド 0x$bit"),
            render = RenderKind.LINE,
        )
    }
}
