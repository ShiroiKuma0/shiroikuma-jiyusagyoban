package com.opentasker.ui.charts

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 「健康」 in two languages.
 *
 * The window is opened from a task, so the language is a setting in `健康の設定 -- [727][01]` like
 * every other 健康 setting — `%Band_Language`, `en-US` or `ja-JP`.
 *
 * ## Why a pair type rather than Android resources
 *
 * `strings.xml` would tie the display language to the **device** locale, and this has to follow a
 * workspace variable instead: 白い熊 reads these tables in English on a Japanese phone. Resource
 * qualifiers cannot express "this one screen, in the language a task chose".
 *
 * The pair type also makes a missing translation a **compile error** rather than a silent fallback to
 * the other language. There is no single-argument constructor and no default, so a string cannot be
 * added in one language only.
 */
enum class BandLanguage(val tag: String) {
    EN("en-US"),
    JA("ja-JP"),
    ;

    companion object {
        val DEFAULT = EN

        /**
         * Parse a tag, tolerantly.
         *
         * `en`, `en-US`, `en_us` and `English` all mean English. An unrecognised value falls back to
         * [DEFAULT] rather than failing: a typo in the settings task must not stop the window opening.
         */
        fun parse(raw: String?): BandLanguage {
            val v = raw?.trim()?.lowercase()?.replace('_', '-').orEmpty()
            return when {
                v.isEmpty() -> DEFAULT
                v.startsWith("ja") || v.startsWith("jp") || v.startsWith("日本") -> JA
                v.startsWith("en") -> EN
                else -> DEFAULT
            }
        }
    }
}

/** One string in both languages. Both are required — that is the point of the type. */
data class Loc(val en: String, val ja: String) {
    operator fun get(lang: BandLanguage): String = when (lang) {
        BandLanguage.EN -> en
        BandLanguage.JA -> ja
    }
}

/** The language the 「健康」 window is currently displaying in. */
val LocalBandLanguage = staticCompositionLocalOf { BandLanguage.DEFAULT }

/**
 * Every piece of chrome outside the metric table.
 *
 * Metric names, band rungs and info sheets live on their own rows in [MetricSpecs]; this is the
 * furniture around them.
 */
object BandText {
    val lastSync = Loc("Last sync", "最終同期")
    val neverSynced = Loc("Not synced yet", "まだ同期していません")
    val syncNow = Loc("Sync", "同期")
    val headroom = Loc("%.0f h of headroom (%s is the shallowest)", "余裕 %.0f 時間（%s がいちばん浅い）")
    val lostWarning = Loc(
        "⚠ %.1f h of %s was overwritten before it could be read",
        "⚠ %s の %.1f 時間が読む前に上書きされました",
    )
    val noData = Loc("No data yet — run a sync.", "まだデータがありません。同期してください。")
    val noReadings = Loc("No readings", "測定なし")
    val guide = Loc("Guide", "目安")
    val andAbove = Loc("%d and above", "%d 以上")
    val upTo = Loc("up to %d", "〜 %d")

    // sync phases
    val phaseStarting = Loc("Starting…", "開始しています…")
    val phaseConnecting = Loc("Connecting to the band…", "バンドに接続しています…")
    val phaseDevice = Loc("Reading device info…", "端末情報を読んでいます…")
    val phaseReading = Loc("Reading", "読み取り中")
    val phaseDone = Loc("Done", "完了")
    val seconds = Loc("%ds", "%d秒")
    val recordsOf = Loc("%d of %d records", "%d / %d 件")

    // spans
    val span1h = Loc("1 h", "1時間")
    val span6h = Loc("6 h", "6時間")
    val span24h = Loc("24 h", "24時間")
    val span3d = Loc("3 d", "3日")
    val spanAll = Loc("All", "全部")

    // info sheet headings
    val infoWhat = Loc("What this is", "これは何か")
    val infoHow = Loc("How it is measured", "どう測っているか")
    val infoRead = Loc("Reading the chart", "グラフの読み方")
    val infoCaveat = Loc("What is not known", "わかっていないこと")

    // footer
    val samples = Loc("%d samples", "%d 件")
    val rejected = Loc("%d rejected", "%d 件除外")
    val gaps = Loc("%d gaps", "%d 箇所の欠測")
    val noReadingCount = Loc("%d no-reading", "%d 件未測定")

    // health index
    val indexTitle = Loc("Health Index", "健康指数")
    val indexPartial = Loc("Partial", "一部のみ")
    val indexOutOf = Loc("/ 100 · %s", "/ 100 · %s")
    val indexWeight = Loc("weight %d%%", "重み %d%%")
    val indexMissing = Loc("Not measured — %s", "測定なし — %s")
    val indexContribution = Loc(
        "measured %.1f %s → %d points → contributes %.1f",
        "実測 %.1f %s → %d 点 → 寄与 %.1f",
    )
    val indexTargets = Loc("What to aim for", "何を目指すか")
    val indexHowRead = Loc("How to read it", "この数字の読み方")
    val indexPartialNote = Loc(
        "The components with no data (%s) are not counted as zero; the index is computed from the " +
            "ones that were measured.",
        "測れていない項目（%s）は 0 点として数えず、測れた項目だけで計算しています。",
    )

    // blood pressure
    val bloodPressure = Loc("Blood Pressure", "血圧")
    val systolic = Loc("Systolic", "収縮期")
    val diastolic = Loc("Diastolic", "拡張期")
    val bpRanges = Loc("Systolic %s · Diastolic %s", "収縮期 %s ／ 拡張期 %s")

    // sleep
    val sleep = Loc("Sleep", "睡眠")
    val sleepDuration = Loc("%dh %02dm", "%d時間%d分")
    val sleepBreakdown = Loc(
        "Deep %dm · Light %dm · REM %dm · Awake %dm",
        "深い %d分 ／ 浅い %d分 ／ REM %d分 ／ 覚醒 %d分",
    )
    val noSleepRecord = Loc("No record", "記録なし")
    val awakeAtCrosshair = Loc("not asleep then", "その時は寝ていない")
    val crosshairHint = Loc(
        "Tap the chart to read a moment; tap it again to clear.",
        "グラフをタップするとその時点の値。もう一度タップで解除。",
    )
    val nothingHere = Loc("no reading there", "その時点の測定なし")

    // daily summary table
    val byDay = Loc("Day by day", "日ごと")
    val byDayNote = Loc(
        "One row per calendar day. A night counts toward the day it started on. Resting heart rate " +
            "and blood oxygen are 5th percentiles; a dash means that day had nothing to measure.",
        "一日ごとに一行。夜は、寝はじめた日のぶんとして数えます。安静時心拍と血中酸素は" +
            "第5パーセンタイル。ダッシュはその日に測れたものがなかったという意味です。",
    )
    val colDay = Loc("Day", "日")
    val colIndex = Loc("Index", "指数")
    val colResting = Loc("Rest HR", "安静心拍")
    val colSleep = Loc("Sleep", "睡眠")
    val colSteps = Loc("Steps", "歩数")
    val colSpo2 = Loc("SpO₂", "血中酸素")
    val deepRemShort = Loc("deep %d · REM %d", "深 %d ／ REM %d")

    /** Shown on a card whose numbers do not behave like measurements. Tap for the evidence. */
    val notAMeasurement = Loc("not a measurement", "測定値ではない")

    /**
     * BLE stream keys, in words.
     *
     * The headroom line and the loss warning name whichever stream is closest to overflowing, and
     * until now they printed the raw protocol key. `hrv` sitting next to a card that is no longer
     * called HRV read as a contradiction, so the streams are named for what a reader would lose.
     *
     * The `hrv` stream is the 0x56 record — six fields, of which only the band state index is still
     * drawn — so it is named for that rather than for the vendor's label.
     */
    private val streamNames: Map<String, Loc> = mapOf(
        "hr" to Loc("heart rate", "心拍"),
        "hrv" to Loc("band state", "バンド状態"),
        "spo2" to Loc("blood oxygen", "血中酸素"),
        "temp" to Loc("body temperature", "体温"),
        "detail" to Loc("steps", "歩数"),
        "sleep" to Loc("sleep", "睡眠"),
        "daily" to Loc("daily totals", "日次合計"),
    )

    /** Unknown keys pass through unchanged — a firmware update lighting up a new stream should be
     *  visible as itself rather than silently renamed to something wrong. */
    fun stream(key: String, lang: BandLanguage): String = streamNames[key]?.get(lang) ?: key

    fun streams(keys: List<String>, lang: BandLanguage): String =
        keys.joinToString(if (lang == BandLanguage.EN) ", " else "・") { stream(it, lang) }

    // band state index — the two record types
    val readOk = Loc("read succeeded", "測定成功")
    val readFailed = Loc("read failed", "測定失敗")
}
