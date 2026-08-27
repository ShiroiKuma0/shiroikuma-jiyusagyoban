package com.opentasker.ui.charts.huawei

import com.opentasker.ui.charts.Loc

/**
 * Every string the Huawei report shows, in both languages.
 *
 * Reuses `Loc`, `BandLanguage` and `LocalBandLanguage` from the Hume side unchanged — none of that
 * machinery was ever device-specific, and `Loc`'s lack of a single-argument constructor is what
 * makes a missing translation a compile error rather than a blank on screen.
 *
 * **It does NOT reuse `BandText`, and the duplication is deliberate.** That object is 213 entries
 * describing recovery, sleep stages, felt ratings and index components this report does not have.
 * Extracting a shared subset would mean editing the one file 白い熊 asked to leave untouched while
 * both bands run in parallel; copying forty pairs is far cheaper than churning the Hume report. When
 * the Huawei side becomes primary this object is the survivor and `BandText` shrinks.
 *
 * `Loc` is `(en, ja)` — **English first**. Both are `String`, so a swapped pair compiles and renders
 * every sheet in the wrong language; twenty of them were inverted when the spec table was written.
 * `HuaweiStringsTest` checks for CJK in the English slot.
 */
object HuaweiText {

    // --- window chrome -------------------------------------------------------------------------
    val title = Loc("Huawei Band", "健康（Huawei）")
    val back = Loc("Back", "戻る")

    // --- the about card ------------------------------------------------------------------------
    val facesTitle = Loc("Watch faces", "文字盤")
    val facesInstall = Loc("Install", "入れる")
    val facesNoPreview = Loc("no picture", "画像なし")
    val facesRead = Loc("Read the band", "バンドを読む")
    val facesBandTitle = Loc("On the band", "バンドの中")
    val facesOnBand = Loc("✓ on the band", "✓ バンドに有り")
    val facesDelete = Loc("Remove", "外す")
    val facesShowing = Loc("showing now", "表示中")
    val facesUnknownOnBand = Loc("not in this library", "この庫に無い")
    val facesCountUnit = Loc("faces", "文字盤")
    val facesCountMine = Loc("in this library", "この庫の分")
    val facesCountOther = Loc("from elsewhere", "他から")
    val facesFree = Loc("free space", "空き")
    val facesBandNever = Loc(
        "The band has not been read yet. Reading it takes a few seconds and needs the band nearby.",
        "まだバンドを読んでいない。読むには数秒かかり、バンドが近くに要る。",
    )
    val facesEmpty = Loc(
        "No faces in this directory yet. Faces are captured from Huawei Health — they cannot be " +
            "made here.",
        "この場所にはまだ文字盤が無い。文字盤は Huawei Health から捕獲するもので、ここでは作れない。",
    )
    val facesBusy = Loc(
        "The band takes one at a time.",
        "バンドは一度に一つしか受け取らない。",
    )

    val sleepTitle = Loc("Last night", "昨夜の睡眠")
    /**
     * The PAGE, which is not one night.
     *
     * The card on the front page shows last night and is titled so. This screen draws every
     * night on record, so borrowing that title labelled a month of sleep as a single night —
     * 白い熊 caught it, 2026-08-23.
     */
    val sleepPageTitle = Loc("Sleep", "睡眠")
    /** Used instead of [sleepTitle] once the newest night is no longer last night. */
    val sleepOlderNight = Loc("Most recent night", "直近の睡眠")
    val sleepStale = Loc("nights ago", "夜前")
    val sleepNone = Loc(
        "No night stored yet — sleep arrives with the next sync.",
        "まだ夜の記録がありません。次の同期で入ります。",
    )
    val sleepWhy = Loc(
        "Read from the band's own sleep file, not from per-minute samples. The band stores the " +
            "stage blocks and no totals at all, so these figures are added up here the same way " +
            "Huawei's own app adds them up.",
        "バンドの睡眠ファイルから読んでいる（分ごとの記録ではない）。バンドは区間だけを持っていて" +
            "合計は一切持たない。だからこの合計は Huawei 純正アプリと同じくこちらで足している。",
    )
    val sleepDeep = Loc("Deep", "深い")
    val sleepLight = Loc("Light", "浅い")
    val sleepRem = Loc("REM", "レム")
    val sleepAwake = Loc("Awake", "覚醒")
    val sleepNap = Loc("Nap", "昼寝")
    val sleepAsleep = Loc("Asleep", "睡眠")
    val sleepBed = Loc("Bed time", "就寝")
    val sleepWoke = Loc("Woke", "起床")
    val sleepOutside = Loc(
        "Awake outside the band's own span is drawn, not counted.",
        "区間の外側の覚醒は描くだけで数えない。",
    )
    val sleepMisaligned = Loc(
        "This night's blocks do not line up with the band's own bed and wake times — read the " +
            "shape with care.",
        "この夜は区間がバンドの就寝・起床時刻と合わない。形は慎重に読むこと。",
    )

    val sleepEveryNight = Loc("Every night on record", "記録された夜すべて")
    val sleepEveryNightNote = Loc(
        "Newest first. Each row is one night from one band — nights before this one existed are the " +
            "Hume band's and say so.",
        "新しい順。各行は一台のバンドの一夜 — このバンドが無かった頃の夜は Hume のもので、そう書いてある。",
    )
    val sleepStages = Loc(
        "deep %d min · light %d min · REM %d min · awake %d min",
        "深い %d 分 ／ 浅い %d 分 ／ REM %d 分 ／ 覚醒 %d 分",
    )
    val sleepFromHume = Loc("— the Hume band's night", "— Hume の夜")

    // 「運動」 — the walks grid.
    val walksTitle = Loc("Walks", "運動")
    val walksAbout = Loc(
        "Walks recorded by the band, with their GPS tracks. A walk is not part of the ordinary sync " +
            "— it has to be asked for, so ask when you have recorded one.",
        "バンドが記録した運動と、その GPS の軌跡。運動はふつうの同期には乗らない — " +
            "取りに行く必要があるので、記録した日に押す。",
    )
    val walksDownload = Loc("Ask the band for new walks", "新しい運動を取りに行く")
    val walksEmpty = Loc(
        "No walks yet. Record one with the band's own Workout app — outdoors, and give it a minute " +
            "to find satellites before you set off.",
        "まだ何も無い。バンドの運動アプリで記録を — 屋外で、歩き出す前に一分ほど衛星を待つこと。",
    )
    val walksNoMap = Loc("no map yet", "地図はまだ")
    val walksSend = Loc("Send to 地図", "地図へ送る")
    val walksFixes = Loc("fixes", "点")
    val walksNoneFound = Loc("The band had no new walks.", "バンドに新しい運動は無かった。")
    val walksOpenIn = Loc("Open in 地図", "地図で開く")
    val walksRedraw = Loc("Draw the map again", "地図を描き直す")
    val walksChizuFigures = Loc("地図's own reading of the route", "地図が読んだ経路")
    val walksMoving = Loc("moving", "移動")
    /** The band's own figure, which is time-with-the-recorder-running, not wall clock. */
    val walksActive = Loc("active", "実動")
    /** First fix to last, stops included. */
    val walksSpan = Loc("span", "通し")
    /** 地図's active time — the figure that stands directly against the band's own. */
    val walksAgainstBand = Loc("vs the band", "バンド比")
    val walksNoRegion = Loc(
        "This is the route on a blank ground: 地図 has no offline map covering this walk, so there " +
            "was nothing to draw underneath. Download the region in 地図, then press Draw the map " +
            "again — the walk itself does not need re-sending.",
        "経路だけで下地が無い。この散歩を覆う地図を 地図 が持っていないので、下に描くものが無かった。" +
            "地図 でその地域を落としてから「地図を描き直す」を押せばよい。散歩そのものを送り直す必要はない。",
    )
    val walksNoMapLong = Loc(
        "No map yet. 白い熊 地図 draws it: send the track over and it keeps it as one of its own " +
            "tracks, then hands back a picture which is stored here.",
        "地図はまだ無い。描くのは白い熊 地図 — 軌跡を送るとあちらの軌跡として保存され、" +
            "絵が返ってきて、それをここに置く。",
    )
    val walksFilesTitle = Loc("Files", "書類")
    val walksFilesNote = Loc(
        "The route as GPX, and the raw bytes off the band beside it. The raw file is kept because a " +
            "format understood slightly wrongly can only be corrected by re-reading it — which has " +
            "already happened once.",
        "GPX の軌跡と、バンドから来た生の書類。生のほうを残すのは、読み方が少し違っていたとき" +
            "読み直せる唯一の道だから — 実際に一度そうなった。",
    )
    val walksSharedNote = Loc(
        "The map comes from 白い熊 地図. It keeps the track; this keeps a copy of the picture, so the " +
            "grid draws without asking another app anything.",
        "地図は白い熊 地図が描いたもの。軌跡はあちらに、絵の写しはこちらに置く — " +
            "一覧を出すのに毎回よそへ問い合わせなくて済むように。",
    )

    val registerTitle = Loc("Every night and session", "あらゆる夜と運動")
    val registerSummary = Loc("%d nights on record · %d rated", "記録された夜 %d ／ 評価済み %d")
    /** Both numbers must come from ONE list of nights — see the note in HuaweiMorningCard. */
    val humeNights = Loc(
        "%d of those %d are the Hume band's — the nights before this one existed. No night is " +
            "built from both.",
        "その %d 夜（全 %d 夜）は Hume のもの — このバンドがまだ無かった頃。両方から作った夜は一つも無い。",
    )

    // 「今朝の体感」 — the one thing on this screen that does not exist unless 白い熊 answers it.
    val morningTitle = Loc("This morning", "今朝の体感")
    val morningAsk = Loc(
        "How do you feel this morning? Nothing on this page can be filled in later — by evening the " +
            "question is unanswerable and the day stays blank.",
        "今朝の調子は？ あとから埋めることはできない — 夕方には答えようがなく、その日は空のまま残る。",
    )
    val morningAnswered = Loc("%s — recorded as %d", "%s — %d で記録済み")
    val morningScale = Loc("1 is best, 5 is worst.", "1 が最良、5 が最悪。")

    val aboutTitle = Loc("About this report", "この画面について")
    val aboutBody = Loc(
        "This is the HUAWEI Band 11 Pro, running alongside the Hume band so the two can be "
            + "compared. Their data is kept in separate tables and is never averaged together.\n\n"
            + "There is no health index and no recovery here yet. Both need sleep, which this band "
            + "does not deliver to us yet, and both are scored against baselines built from the "
            + "Hume band's nights — a number computed that way would look comparable and would not "
            + "be.\n\n"
            + "The gate values on every chart below are placeholders, not measurements. The "
            + "coverage card is what replaces them.",
        "HUAWEI Band 11 Pro の画面。Hume バンドと並べて動かし、二台を比べるためにある。"
            + "記録はそれぞれ別の表に入り、平均して混ぜることはしない。\n\n"
            + "健康指数と回復はまだ無い。どちらも睡眠が要るがこのバンドからはまだ取れておらず、"
            + "しかも基準値は Hume の夜から作られている — その基準で出した数字は、"
            + "比べられるように見えて比べられない。\n\n"
            + "下のグラフの判定値はどれも仮のもので、実測ではない。カバー率の card がそれを置き換える。",
    )

    // --- sync header ---------------------------------------------------------------------------
    val syncNow = Loc("Sync now", "いま同期")
    val syncing = Loc("Syncing…", "同期中…")
    val pairFirst = Loc("Pair the band first", "先にバンドと接続")
    val neverSynced = Loc("never synced", "まだ一度も同期していません")
    val lastSync = Loc("Last sync", "最後の同期")
    val battery = Loc("Battery", "電池")
    val firmware = Loc("Firmware", "ファーム")
    val ago = Loc("%s h ago", "%s 時間前")
    val syncCount = Loc("%d syncs on record", "同期の記録 %d 件")

    val phaseStarting = Loc("starting", "開始")
    val phaseConnecting = Loc("connecting", "接続中")
    val phaseHandshake = Loc("handshake", "認証中")
    val phaseDevice = Loc("reading the band", "バンドを読み取り中")
    val phaseCounting = Loc("counting records", "件数を確認中")
    val phaseReading = Loc("reading records", "記録を取得中")
    val phaseWriting = Loc("writing", "書き込み中")
    val phaseServing = Loc("answering the band", "バンドに応答中")
    val phaseDone = Loc("done", "完了")

    /** Worded as a FLOOR. It is the deepest the band has answered from, never a promise. */
    val observedDepth = Loc(
        "At least %s h of history observed",
        "これまでに確認できた履歴は最低 %s 時間",
    )
    val observedDepthUnmeasured = Loc(
        "History depth not measured yet",
        "履歴の深さはまだ測っていません",
    )
    val missingRecords = Loc(
        "⚠ the band refused %d record(s) on the last sync",
        "⚠ 前回の同期でバンドが %d 件の記録を返しませんでした",
    )

    // --- coverage card -------------------------------------------------------------------------
    val coverageTitle = Loc("Coverage", "カバー率")
    val coverageWhy = Loc(
        "How much this band actually recorded, and how far apart the readings were. These "
            + "measurements are what will replace the placeholder gate values on the charts.",
        "このバンドが実際にどれだけ記録し、その間隔がどれだけ空いたか。"
            + "ここで測った値が、グラフの仮の判定値を置き換える。",
    )
    val covSamples = Loc("readings", "件数")
    val covSpan = Loc("span", "範囲")
    val covCadence = Loc("interval", "間隔")
    val covP90 = Loc("p90", "p90")
    val covLongest = Loc("longest gap", "最長の空き")
    val covDensity = Loc("density", "密度")
    val covNothing = Loc("nothing recorded", "記録なし")
    val covNeedMore = Loc(
        "needs at least two readings",
        "二件以上ないと測れません",
    )
    val covDensityNote = Loc(
        "Density is how close the count came to what the observed interval implies — the nearest "
            + "thing to an answer for \"is this band catching what the other one catches\".",
        "密度は、観測した間隔から期待される件数にどれだけ届いたか。"
            + "「このバンドはもう一台が拾っているものを拾えているか」に一番近い答え。",
    )

    // --- diagnostics card ----------------------------------------------------------------------
    val diagnosticsTitle = Loc("Diagnostics", "診断")
    val diagnosticsWhy = Loc(
        "Numbers the band returns whose meaning is not established. Kept rather than dropped, so a "
            + "firmware change shows up in the data instead of vanishing.",
        "バンドが返すが、意味の確かめられていない数値。捨てずに残してあるのは、"
            + "ファームが変わったときにデータに現れるようにするため。",
    )
    val rawUnitsWarning = Loc(
        "Not kilocalories and not metres — the conversion is unknown, so these are not presented "
            + "as calories or distance.",
        "キロカロリーでもメートルでもない。換算がまだ分からないので、"
            + "「カロリー」「距離」としては出さない。",
    )
    val unknownFields = Loc("Undecoded fields", "未解読のフィールド")
    val unknownFieldRow = Loc("bit 0x%s · %d readings", "ビット 0x%s ・ %d 件")
    val noUnknownFields = Loc("none seen", "検出なし")

    // --- charts --------------------------------------------------------------------------------
    val provisional = Loc(
        "provisional — no filter applied",
        "仮設定 — 異常値の除外なし",
    )
    val noData = Loc("no readings yet", "まだ記録がありません")
    val notImplemented = Loc(
        "HRV and sleep are not read from this band yet.",
        "心拍変動と睡眠は、このバンドからまだ読み出していません。",
    )

    // --- days ----------------------------------------------------------------------------------
    val daysTitle = Loc("By day", "日ごと")
    val dayColumnSteps = Loc("steps", "歩数")
    val dayColumnHr = Loc("heart rate", "心拍")
    val dayColumnSpo2 = Loc("blood oxygen", "血中酸素")

    // --- failures ------------------------------------------------------------------------------
    val notPaired = Loc(
        "The band is not paired with this phone. Run バンド接続（Huawei）.",
        "このスマホとバンドが接続されていません。「バンド接続（Huawei）」を実行してください。",
    )
    val syncFailed = Loc("Sync failed: %s", "同期できませんでした: %s")
}
