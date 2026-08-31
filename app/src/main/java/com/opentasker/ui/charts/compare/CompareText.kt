package com.opentasker.ui.charts.compare

import com.opentasker.ui.charts.Loc

/** Every string 「バンド比較」 shows, in both languages. `Loc` is (en, ja) — English first. */
object CompareText {

    val title = Loc("Two bands, side by side", "二台を並べて")

    val about = Loc(
        "The HUAWEI Band 11 Pro on top, the Hume band below, on one shared time axis and one shared " +
            "scale. The band is shown by which track a reading is on and whether its mark is filled " +
            "— never by colour, which belongs to the metric.",
        "上が HUAWEI Band 11 Pro、下が Hume。時間軸も目盛りも共通。" +
            "どちらのバンドかは**段**と**印の塗り**で示す — 色は指標のものなので、バンドには使わない。",
    )

    val neverPooled = Loc(
        "The two are never averaged together. The only figure derived from both is a signed " +
            "difference, and a minute that only one band recorded stays a minute only one band " +
            "recorded — it is counted, not hidden.",
        "二台を平均することはしない。両方から作る数は符号つきの差だけ。" +
            "片方しか測っていない分はそのまま片方のものとして数える — 消さない。",
    )

    val loading = Loc("Reading both tables…", "両方の記録を読んでいる…")

    val noData = Loc(
        "Neither band has anything in this window.",
        "この範囲はどちらのバンドにも記録が無い。",
    )
}
