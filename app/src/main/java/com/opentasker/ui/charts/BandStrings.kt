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
    val markerFelt = Loc("How you woke", "目覚め")
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
    // A score is a MORNING thing — made on waking, about the night just ended — so the morning is what
    // it is named by. "Night of" was ambiguous exactly where it mattered: the night of the 15th could
    // be the one that ended that morning or the one that began that evening, and with a bedtime either
    // side of midnight it was silently sometimes one and sometimes the other.
    val recoveryAskToday = Loc("This morning — how did you wake?", "今朝の目覚めは？")
    val recoveryAsk = Loc("Morning of %s — how did you wake?", "%s の朝：目覚めは？")
    val recoveryAskDone = Loc("Morning of %s: %s", "%s の朝：%s")
    /** The morning, then the night it appraises: 「8月16日（日）の朝 ・ 15→16 の夜」. */
    val morningOfNight = Loc("%s · the night %s", "%s ・ %s の夜")
    // Printed only when the two mornings differ. The card names no morning on its own, so an older
    // night's numbers read as last night's unless something says otherwise — and the rating row below
    // names a DIFFERENT morning, which without this would look like the card contradicting itself.
    val recoveryNightMissing = Loc(
        "The band recorded no night ending %s, so the markers above are the morning of %s.",
        "%s に明けた夜は記録なし。上の指標は %s の朝のもの。",
    )
    // Named by meaning, never by step number: the scale flipped on 2026-08-12 and a constant called
    // `feltScale1` holding "Wrecked" would have had to be renamed or, worse, left lying.
    val feltGreat = Loc("Great", "絶好調")
    val feltGood = Loc("Good", "良い")
    val feltNormal = Loc("Normal", "普通")
    val feltBelowPar = Loc("Below par", "いまひとつ")
    val feltWrecked = Loc("Wrecked", "最悪")
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
    val registerOpen = Loc("Every night and session", "夜と運動の記録をすべて")
    val registerOpenCounts = Loc("%d nights on record · %d rated", "記録 %d 晩 ・ 評価 %d 件")
    val registerEmpty = Loc(
        "No sessions marked yet. Mark one with 運動記録, and the night that follows it will appear here beside it.",
        "まだ運動が記録されていない。運動記録で記録すれば、その次の夜がここに並んで出る。",
    )
    val registerLegend = Loc(
        "Each tile is a morning: how you woke that day, after the day before it — filled in that score's own colour from the scale below, 1 the best and 5 the worst. A yellow border marks today, and marks nothing else. The bar underneath is that day's own training, and its effect shows in the NEXT morning's score.",
        "各タイルは朝 — その日どう目覚めたか、前の日を経て。下の尺度と同じ色で塗る（1 が最良、5 が最悪）。黄色の枠は今日、それ以外の意味はない。下の棒はその日自身の運動量で、効いてくるのは翌朝の点数。",
    )
    // ---- 過去の夜を評価する ----------------------------------------------------------------------
    //
    // The card can only ever rate LAST night, because that is the only night it is about. Every other
    // night 白い熊 has lived through is on this screen, so this is where a missed one is filled in and
    // a mistaken one corrected. The hint is printed on both cards, in the same words, because the
    // gesture is invisible until someone says it exists.
    val registerRateHint = Loc(
        "Tap any morning — a tile above, or a line of the table — to score it, or to change a score already given.",
        "どの朝でも押せる。上のタイルでも、表の行でも、押せばその朝の目覚めを付けられる。付け直しも同じ。",
    )
    val rateClearHint = Loc(
        "Tapping the number already chosen removes the score — a morning can always go back to unscored.",
        "すでに選ばれている数字をもう一度押すと取り消す。いつでも未記入に戻せる。",
    )
    val rateLateNote = Loc(
        "A morning filled in late counts exactly like one answered on the day: same store, same baseline, same counting rule.",
        "あとから付けた評価も、その朝に付けたものとまったく同じに扱う。保存先も、比較の基準も、数え方も同じ。",
    )
    val rateClose = Loc("Close", "閉じる")
    // ---- 夜ごとの記録 --------------------------------------------------------------------------
    // The grid can only show a count, and a count is not a reading. These print what is stored.
    val registerNightsTitle = Loc("Every night and every rating — %d lines, %d rated", "夜と評価のすべて — %d 行、うち %d 件は評価あり")
    val registerNightsNote = Loc(
        "Newest first. 体感 is the number you tapped. Night HR and 実睡眠 are graded against published reference ranges — tap the i above for the cut points and their sources. 皮膚温 keeps the within-person reading, because a wrist sensor tracks the room almost as closely as the wearer and has no absolute band worth having. A dash is a value the band never recorded; a row of dashes is a rating with no night beside it.",
        "新しい順。体感は押した数字そのもの。夜間心拍と実睡眠は公表された基準範囲で採点する — 区切りと出典は上の i を押せば出る。皮膚温だけは自分自身との比較のまま。手首のセンサーは装着者とほぼ同じくらい部屋の温度を測っており、絶対的な基準を置く意味がないため。ダッシュはバンドが記録しなかった値、全部ダッシュの行は夜のない評価。",
    )
    // ---- the reference bands, behind the i ------------------------------------------------------
    //
    // Structured rather than prose (白い熊, 2026-08-12: "make the info texts less a stream of text and
    // more visually structured info"). Each band is a coloured box carrying its step, then the exact
    // cut points that land a night in it, then why that cut point is where it is. The panel draws
    // these; nothing here formats itself.
    val bandsTitle = Loc("The bands, and where they come from", "この区切りと、その出典")

    /** A section of the panel: the metric, and the source line under its ladder. */
    val bandsSleepTitle = Loc("実睡眠 — Time asleep", "実睡眠")
    val bandsHrTitle = Loc("夜間心拍 — Night HR", "夜間心拍")
    val bandsTempTitle = Loc("皮膚温 — Skin temperature", "皮膚温")
    val bandsFeltTitle = Loc("体感 — How you felt", "体感")

    // --- 実睡眠, rung by rung. The times are the actual cut points in RecoveryReference.
    val bandsSleep1 = Loc("7h00 – 9h00", "7時間00分 〜 9時間00分")
    val bandsSleep1Why = Loc(
        "The National Sleep Foundation's recommended window for adults 26–64 (Hirshkowitz 2015), and the only band the AASM's \"7 or more hours on a regular basis\" (Watson 2015) fully endorses.",
        "全米睡眠財団が成人（26〜64歳）に推奨する範囲（Hirshkowitz 2015）。AASM の「日常的に7時間以上」（Watson 2015）を完全に満たすのはこの帯だけ。",
    )
    val bandsSleep2 = Loc("9h00 – 10h00", "9時間00分 〜 10時間00分")
    val bandsSleep2Why = Loc(
        "\"May be appropriate\" in the NSF categories. Long, but it is the side of the recommendation with no evidence against it — which is why it ranks above 6–7 h rather than level with it.",
        "NSF の区分では「適切な場合もある」。長いが、推奨から外れる側としては不利な証拠がない方。だから 6〜7 時間と同列ではなく、その上に置く。",
    )
    val bandsSleep3 = Loc("6h00 – 7h00", "6時間00分 〜 7時間00分")
    val bandsSleep3Why = Loc(
        "The same NSF category as 9–10 h, but below the AASM line. Short and long are NOT symmetric here: falling short of the recommendation is the side with the evidence behind it.",
        "9〜10 時間と同じ NSF 区分だが、AASM の線を下回る。短い側と長い側は対称ではない。推奨に届かない側こそ証拠のある側だから。",
    )
    val bandsSleep4 = Loc("5h00 – 6h00, or 10h00 – 11h00", "5時間00分〜6時間00分、または 10時間00分〜11時間00分")
    val bandsSleep4Why = Loc(
        "One hour further out on either side. Outside every category the consensus is willing to call appropriate.",
        "どちらの側にもさらに一時間外。合意が「適切」と呼ぶ区分からは完全に外れている。",
    )
    val bandsSleep5 = Loc("under 5h00, or over 11h00", "5時間00分未満、または 11時間00分超")
    val bandsSleep5Why = Loc(
        "\"Not recommended\" by the NSF at either end.",
        "NSF がどちらの端についても「非推奨」とする範囲。",
    )

    // --- 夜間心拍, one step per decade.
    val bandsHr1 = Loc("under 50 bpm", "50 bpm 未満")
    val bandsHr2 = Loc("50 – 59 bpm", "50 〜 59 bpm")
    val bandsHr3 = Loc("60 – 69 bpm", "60 〜 69 bpm")
    val bandsHr4 = Loc("70 – 79 bpm", "70 〜 79 bpm")
    val bandsHr5 = Loc("80 bpm and over", "80 bpm 以上")
    val bandsHrWhy = Loc(
        "The resting-heart-rate decades of Jensen 2013 (16-year follow-up), across which mortality rises monotonically — one step per decade, because that is how the risk itself steps. Aune 2017 puts the slope at about 9 % higher all-cause mortality per 10 bpm.",
        "Jensen 2013（16年追跡）の安静時心拍の十年区分。死亡率はこの区切りに沿って単調に上がるので、一区分＝一段階にしてある。Aune 2017 によれば 10 bpm ごとに全死因死亡が約 9 % 増える。",
    )
    val bandsHrCaveat = Loc(
        "Worth knowing: those are DAYTIME resting rates, and a sleeping heart rate runs below its owner's daytime resting. Applied to a nocturnal value these bands are generous, not harsh.",
        "注意：これらは「日中の」安静時心拍で、睡眠中の心拍は日中の安静時より低い。夜間の値に当てると、厳しいどころか甘い。",
    )

    // --- the two that are not graded against anybody else.
    val bandsTempNoBand = Loc("no reference band, deliberately", "基準帯は意図的に置かない")
    val bandsTempWhy = Loc(
        "The wrist sensor correlates with the room at r = 0.961 (Sato 2024) and the ambient term is several times the physiological one, so an absolute threshold would be grading your bedroom. This column stays a comparison against your own nights — 3 is inside your usual range, 4 and 5 above it, and it is never graded downward because a cool night is unremarkable rather than good.",
        "手首のセンサーは室温と r = 0.961 で相関し（Sato 2024）、環境の項が生理的な項の数倍ある。絶対値で切れば寝室を採点することになる。この列だけは自分自身の夜との比較のまま。3 は平常の範囲内、4 と 5 はその上。下側は採点しない — 涼しい夜は「良い」ではなく「特筆なし」だから。",
    )
    val bandsFeltWhy = Loc(
        "The number you tapped. Nothing to convert, and nothing to compare it against — it is already the scale.",
        "押した数字そのもの。変換するものも、比べる相手もない。これ自体が尺度だから。",
    )
    val bandsRingNote = Loc(
        "Separately: a thicker ring on a calendar tile still means \"outside YOUR usual range that night\". That is a different question from these bands, and neither is derived from the other.",
        "なお、暦のタイルの太い縁は今も「その夜が自分の平常の範囲外だったか」を意味する。ここの基準帯とは別の問いで、どちらも他方から導いてはいない。",
    )
    // Column headings, printed once. The values under them keep their own colour. Prefixed because
    // the day-table further down already owns the bare col* names for a different set of columns.
    // --- the ⓘ panel's entries for the five columns added 2026-09-03 --------------------------
    val bandsLowTitle = Loc("Lowest HR", "最低心拍")
    val bandsLowWhy = Loc(
        "The lowest per-minute rate recorded between falling asleep and waking — the floor of the "
            + "night, where Night HR is its level over four hours after onset. Two questions, two "
            + "numbers.",
        "寝入ってから起きるまでに記録された毎分の最低値 — 夜の底であり、夜間心拍は寝入って四時間の水準である。"
            + "問いが二つ、数字も二つ。",
    )
    val bandsLowCaveat = Loc(
        "It rides the SAME published decades as Night HR, deliberately unshifted — inventing a "
            + "\"sleeping floor\" ladder by subtracting a few beats would be a scale nobody measured. "
            + "So a green low is a WEAKER claim than a green Night HR: a floor necessarily sits below "
            + "a level, and the two columns are not to be read as agreeing or disagreeing.",
        "夜間心拍と同じ公表された十年区分に乗せてある。数拍引いた「睡眠時の底」の尺度を作れば、誰も測っていない"
            + "物差しになるからである。よって緑の最低心拍は緑の夜間心拍より弱い主張である。底は必ず水準の下にあり、"
            + "二つの欄は一致・不一致として読むものではない。",
    )
    val bandsSpo2Title = Loc("SpO₂", "血中酸素")
    val bandsSpo2Why = Loc(
        "The clinical room-air ranges: 96 % and above is normal, 95 % borderline, 91–93 % low, "
            + "below 91 % hypoxaemia. The median of what the band recorded inside the sleep window.",
        "臨床の室内気の範囲。96 % 以上が正常、95 % が境界、91–93 % が低い、91 % 未満が低酸素。"
            + "睡眠時間帯にバンドが記録した値の中央値である。",
    )
    val bandsSpo2Caveat = Loc(
        "A wrist oximeter's error is about TWICE the day–night swing it is measuring, which is why "
            + "blood oxygen is excluded from the recovery count and always will be. The colour says "
            + "where the READING sits on the clinical ladder — not how sure anyone should be that the "
            + "reading is the truth.",
        "手首の酸素計の誤差は、測っている昼夜差のおよそ二倍ある。血中酸素が回復の数え上げから外されている理由で"
            + "あり、今後も外れ続ける。色が言うのは読み値が臨床の尺度のどこに乗るかであって、その読み値が真実だと"
            + "どれだけ確信してよいかではない。",
    )
    val bandsWithinTitle = Loc("Deep · Deep+REM · HRV", "深い・深＋レム・心拍変動")
    val bandsWithinWhy = Loc(
        "These three are banded against YOUR OWN preceding nights, not against a population range, "
            + "because no published ladder fits them. This band's \"deep\" is not polysomnography's "
            + "N3 — these nights run 30–40 % of sleep where the literature is 13–23 % — so an absolute "
            + "ladder would score every night ever recorded as extreme. RMSSD norms are so "
            + "age-dependent that a population mean would paint the whole column one colour.",
        "この三つは公表された範囲ではなく、自分自身の直前の夜に対して段を付けてある。当てはまる公表尺度が無いから"
            + "である。このバンドの「深い」は睡眠ポリグラフの N3 ではない — ここでの夜は睡眠の 30〜40 % を占め、"
            + "文献は 13〜23 % である — ので、絶対尺度に掛ければ記録した全ての夜が極端と出る。RMSSD の基準値は"
            + "年齢依存が強く、母集団平均では欄全体が一色になる。",
    )
    val bandsNotCounted = Loc(
        "None of the five is COUNTED. The headline still counts three markers; a colour here says "
            + "where a value sits, never that the night was adverse.",
        "この五つはいずれも数えていない。見出しが数えるのは今も三つの指標である。ここの色は値の位置を言うのみで、"
            + "その夜が悪かったとは言わない。",
    )

    val regColDate = Loc("Date", "日付")
    val regColFelt = Loc("Woke", "目覚め")
    val regColHr = Loc("Night HR", "夜間心拍")
    val regColSleep = Loc("Asleep", "実睡眠")
    // Dropped from the night table on 2026-09-03 — this band measures no temperature, so the column
    // was a dash on every line. The string stays for the Hume report, which does measure it.
    val regColTemp = Loc("Temp °C", "体温 ℃")
    val regColDeep = Loc("Deep", "深い")
    val regColDeepRem = Loc("Deep+REM", "深＋レム")
    val regColLowHr = Loc("Low HR", "最低心拍")
    val regColHrv = Loc("HRV", "心拍変動")
    val regColSpo2 = Loc("SpO₂", "血中酸素")

    /**
     * The night table's headings, in the order the columns are drawn.
     *
     * A list rather than nine call sites, because the header row and the value row have to stay the
     * same length and the same order, and two hand-maintained sequences of nine drift the first time
     * one is edited.
     *
     * **Every label is short on purpose.** The columns share ONE width and each heading gets ONE
     * line (白い熊, 2026-09-03), so `How you woke` became `Woke`, `Lowest HR` became `Low HR`, and
     * `HRV (RMSSD)` became `HRV` — RMSSD is named in the ⓘ panel, which is where a unit belongs when
     * the column cannot spell it.
     */
    val registerColumns = listOf(
        regColDate, regColFelt, regColHr, regColSleep,
        regColDeep, regColDeepRem, regColLowHr, regColHrv, regColSpo2,
    )
    val registerNightsEmpty = Loc(
        "No nights recorded yet. A sleep session has to sync from the band before anything appears here.",
        "まだ夜の記録がない。バンドから睡眠が同期されるまでここには何も出ない。",
    )
    /** The same note before there is a baseline to quote a threshold from. */
    val registerNightsNotePlain = Loc(
        "Newest first. 体感 is the number you tapped. The measured columns are graded against your own recent median, not against anyone else: 3 is inside your usual range, 2 and 4 are outside it — 2 on the better side, 4 on the worse — and 1 and 5 twice as far out again. A dash is a value the band never recorded; a row of dashes is a rating with no night beside it. Grey means there is no baseline yet to judge against.",
        "新しい順。体感はあなたが押した数字そのもの。測定値は他人ではなく自分自身の直近の中央値と比べる：3 は平常の範囲内、2 と 4 はその外（2 が良い側、4 が悪い側）、1 と 5 はさらに倍だけ外。ダッシュはバンドが記録しなかった値、全部ダッシュの行は夜のない評価。灰色はまだ比べる基準がない値。",
    )
    val registerNightUnrated = Loc("not rated", "未評価")
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
