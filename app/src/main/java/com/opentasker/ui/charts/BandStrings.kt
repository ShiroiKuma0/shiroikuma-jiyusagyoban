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
    // --- the band's own charge, in the sync header ------------------------------------------------
    val bandBattery = Loc("Band", "バンド")
    val bandBatteryUnknown = Loc("Band charge unknown — sync to read it", "バンドの残量は未取得 — 同期すると読めます")
    /** The charge always travels with its age; a bare percentage could be hours old. */
    val bandBatteryFresh = Loc("just now", "たった今")
    val bandBatteryAgeHours = Loc("%.0f h ago", "%.0f 時間前")
    val bandBatteryAgeDays = Loc("%.0f d ago", "%.0f 日前")
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
    // ---- 回復 --------------------------------------------------------------------------------
    val recoveryTitle = Loc("Recovery", "回復")
    val recoveryAllUsual = Loc("Nothing unusual", "すべて平常")
    val recoveryOneOff = Loc("One marker is outside your usual range", "1つが平常の範囲外")
    val recoveryTwoOff = Loc("%d markers are outside your usual range", "%d つが平常の範囲外")
    val recoveryCollecting = Loc(
        "Collecting — %d of 5 nights needed before there is anything to compare against",
        "収集中 — 比較できるようになるまで、あと %d／5 夜",
    )
    val recoveryProvisional = Loc(
        "Provisional: %d nights. Compared on absolute differences until 14.",
        "暫定値：%d 夜。14 夜になるまでは差の絶対値で比べている。",
    )
    val markerNocturnalHr = Loc("Nocturnal heart rate", "夜間心拍")
    val markerSleep = Loc("Time asleep", "実睡眠")
    val markerFelt = Loc("How you felt", "体感")
    val markerTemperature = Loc("Skin temperature", "皮膚温")
    val bandUsual = Loc("usual", "平常")
    val bandHigh = Loc("high", "高い")
    val bandLow = Loc("low", "低い")
    val bandUnknown = Loc("—", "—")
    val usualRange = Loc("usual %s–%s", "平常 %s〜%s")
    val recoveryLateEffort = Loc(
        "You were still moving %d min before you fell asleep — that raises nocturnal heart rate on its own, and it costs nothing once there are 4 h in between.",
        "寝つく %d 分前まで動いていた。それだけで夜間心拍は上がる。間が4時間あれば影響は出ない。",
    )
    val recoveryIllness = Loc(
        "Heart rate up and sleep short together — the pattern that tracks illness. Worth watching, not a diagnosis.",
        "心拍が上がり睡眠が短い — 体調不良と相関するパターン。診断ではないが、注意する価値はある。",
    )
    val recoveryAsk = Loc("How do you feel today?", "今日の体感は？")
    val recoveryAskDone = Loc("Today: %s", "今日：%s")
    val feltScale1 = Loc("Wrecked", "最悪")
    val feltScale2 = Loc("Below par", "いまひとつ")
    val feltScale3 = Loc("Normal", "普通")
    val feltScale4 = Loc("Good", "良い")
    val feltScale5 = Loc("Great", "絶好調")
    val loadTitle = Loc("Load, 7 d vs 28 d", "負荷 7日/28日")
    val loadWeekly = Loc("%d MET-min this week", "今週 %d MET分")
    val loadDetraining = Loc("below your usual", "いつもより少ない")
    val loadMaintaining = Loc("maintaining", "維持")
    val loadProductive = Loc("building", "積み上げ中")
    val loadOverreaching = Loc("a sharp step up", "急な増加")
    val loadSessions = Loc("%d marked session(s)", "記録した運動 %d 件")
    val loadSessionOpen = Loc("a session is open — mark its end", "運動が開始のまま — 終了を記録して")
    val loadFromSessions = Loc("of which %d from sessions", "うち %d は運動から")
    // ---- 運動と回復（記録簿）------------------------------------------------------------------
    val registerTitle = Loc("Training and recovery", "運動と回復")
    val registerOpen = Loc("See every session ▸", "記録した運動をすべて見る ▸")
    val registerEmpty = Loc(
        "No sessions marked yet. Mark one with 運動記録, and the night that follows it will appear here beside it.",
        "まだ運動が記録されていない。運動記録で記録すれば、その次の夜がここに並んで出る。",
    )
    val registerLegend = Loc(
        "Bar = session load that day. Dots = markers outside your usual range on the night that started that day.",
        "棒はその日の運動量。点はその日の夜に平常の範囲外だった指標の数。",
    )
    val registerSession = Loc("%s · %d min · %d MET-min", "%s ・ %d 分 ・ %d MET分")
    val registerPeak = Loc("peak %d bpm", "最高 %d bpm")
    val registerNoNight = Loc("→ no night recorded after it yet", "→ その後の夜はまだ記録されていない")
    val registerContrast = Loc(
        "Nocturnal heart rate: %d after a session (%d nights) against %d after none (%d nights)",
        "夜間心拍：運動した日の夜は %d（%d 晩）、しなかった日の夜は %d（%d 晩）",
    )
    val registerContrastNote = Loc(
        "A within-person comparison of medians, nothing more. No correlation is computed and none should be read into it.",
        "同一人物内での中央値の比較にすぎない。相関は計算していないし、読み取るべきでもない。",
    )
    val registerContrastWaiting = Loc(
        "The comparison of nights after a session against nights after none appears once there are %d of each.",
        "運動した夜としなかった夜の比較は、両方が %d 晩たまってから出る。",
    )

    // ---- あとから運動を記録 ----------------------------------------------------------------------
    val markSessionTitle = Loc("Mark a past session", "あとから運動を記録")
    val markSessionHint = Loc(
        "Long-press and drag across the chart to mark when you trained. Nothing is recorded until you press the button — adjust the ends first.",
        "グラフを長押ししてなぞり、運動していた時間帯を囲む。ボタンを押すまで何も記録されない。先に端を調整すること。",
    )
    val markSessionSpan = Loc("%s → %s · %d min", "%s → %s ・ %d 分")
    val markSessionNothing = Loc("Nothing marked yet", "まだ選んでいない")
    val markSessionStart = Loc("Start", "開始")
    val markSessionEnd = Loc("End", "終了")
    val markSessionSubmit = Loc("Record session", "この区間を記録")
    val markSessionClear = Loc("Clear", "取消")
    val markSessionDone = Loc("Recorded. It is in 回復 after the next sync.", "記録した。次の同期のあと 回復 に出る。")
    val markSessionRejected = Loc(
        "Not recorded — a session must be 5 to 240 minutes.",
        "記録しなかった — 運動は 5〜240 分でなければならない。",
    )

    val sleepScoreTitle = Loc("Sleep score", "睡眠スコア")
    val sleepScoreParts = Loc(
        "duration %d/50 · consistency %d/30 · interruptions %d/20",
        "長さ %d/50 ・ 規則性 %d/30 ・ 中断 %d/20",
    )
    val sleepScoreNote = Loc(
        "Apple's published weights, stages deliberately excluded. The point split is theirs; the curve inside each part is ours.",
        "Apple が公表している配点で、睡眠段階は意図的に除外。配点は向こうのもの、各項目の曲線はこちらのもの。",
    )
    val scoreVeryLow = Loc("very low", "とても低い")
    val scoreLow = Loc("low", "低い")
    val scoreOk = Loc("OK", "まずまず")
    val scoreHigh = Loc("high", "高い")
    val scoreVeryHigh = Loc("very high", "とても高い")
    val onsetDrift = Loc("%d min from your usual bedtime", "いつもの就寝時刻から %d 分ずれ")
    val peakCadence = Loc("Peak 30-min cadence", "最も速い30分の歩調")
    val peakCadenceNote = Loc(
        "%s · %d steps/min, against a population norm of 71. The one intensity measure that predicts outcomes independently of how much you walked. A day needs 30 minutes of walking in it before this means anything, so early in the morning it shows the last day that had them.",
        "%s ・ 毎分 %d 歩（一般的な目安は 71）。総歩数とは独立に結果を予測できる、数少ない強度の指標。歩いた時間が 30 分たまるまで意味を持たないので、朝のうちは直近のそういう日を出す。",
    )
    val sleepAwakeNote = Loc(
        "Time actually asleep. The 睡眠 card below shows the whole session — the difference is the %d min you were awake in it.",
        "実際に眠っていた時間。下の 睡眠 カードは区間全体を出しているので、その差が途中で起きていた %d 分。",
    )
    val regimeTravel = Loc(
        "You changed time zone %d day(s) ago. Sleep duration re-converges in about two days but timing takes over two weeks, so the comparisons above are still catching up.",
        "%d 日前に時間帯が変わっている。睡眠時間は二日ほどで戻るが、時刻のずれは二週間以上残る。上の比較はまだ追いついていない。",
    )
    val regimeAltitude = Loc(
        "Blood oxygen has sat %.1f points low for %d nights — the altitude signature. A raised heart rate at altitude is adaptation, not poor recovery.",
        "血中酸素が %.1f ポイント低い状態が %d 晩続いている — 高地の兆候。高地で心拍が上がるのは適応であって、回復不良ではない。",
    )
    val sriTitle = Loc("Sleep regularity", "睡眠の規則性")
    val sriIrregular = Loc("irregular", "不規則")
    val sriMiddling = Loc("middling", "ふつう")
    val sriRegular = Loc("regular", "規則的")
    val sriVeryRegular = Loc("very regular", "とても規則的")
    val sriNote = Loc(
        "How closely you sleep and wake at the same clock times, 0–100. In 60 977 people this predicted mortality more strongly than how long they slept.",
        "毎日ほぼ同じ時刻に寝て起きているか、0〜100 で。60 977 人の調査では、睡眠時間より強く死亡率を予測した指標。",
    )
    val loadFloorNote = Loc(
        "Walking cadence, plus the heart rate inside sessions you marked. Anything unmarked and stepless — cycling, carrying, lifting — is invisible, and marked strength work still reads about 18 % low because heart rate falls between sets. A floor, not a total.",
        "歩行ケイデンスと、記録した運動の中の心拍から。記録していない歩行なしの活動 — 自転車・荷物運び・筋トレ — は見えない。記録した筋トレでもセット間で心拍が下がるぶん約 18 % 低めに出る。合計ではなく下限。",
    )

    // scatter legend — which dot is the periodic series and which came with an SpO₂ reading
    val markPeriodic = Loc("periodic", "定期測定")
    val markCoincident = Loc("with blood oxygen", "血中酸素と同時")

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
    val sleepStageHeader = Loc("Stage", "段階")
    val sleepMinutesHeader = Loc("Minutes", "分")
    val sleepShareHeader = Loc("Share", "割合")
    val sleepTotalRow = Loc("Total", "合計")
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
    // --- a long-press-dragged stretch of time, and what it adds up to -----------------------------
    val spanHint = Loc(
        "Long-press and drag across the chart to total a stretch.",
        "長押しして横になぞると、その区間を合計します。",
    )
    val spanTotal = Loc("total", "合計")
    val spanMean = Loc("mean", "平均")
    val spanEmpty = Loc("nothing measured in that stretch", "その区間の測定なし")
    val spanSamples = Loc("%d samples", "%d 件")
    // --- the day-by-day history under each full-screen chart ------------------------------------
    val history = Loc("Day by day", "日ごとの記録")
    val historyNote = Loc(
        "Every calendar day the archive holds, newest first. The sample count is part of the reading: a median built from four readings is not the same claim as one built from four hundred.",
        "書庫にある日をすべて、新しい順に。測定数も読みの一部です — 四回の中央値と四百回の中央値は同じ主張ではありません。",
    )
    val nightsNote = Loc(
        "Every night the band recorded, newest first, with when it began and ended as well as how long it lasted.",
        "バンドが記録した夜をすべて、新しい順に。長さだけでなく、いつ始まりいつ終わったかも。",
    )
    val colDate = Loc("Date", "日付")
    val colRange = Loc("Low–high", "最小〜最大")
    val colMedian = Loc("Median", "中央値")
    val colTotal = Loc("Total", "合計")
    val colSamples = Loc("n", "測定数")
    val colWhen = Loc("From → to", "就寝〜起床")
    val colDuration = Loc("Slept", "睡眠")
    val noHistory = Loc("Nothing recorded yet.", "まだ記録がありません。")

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
