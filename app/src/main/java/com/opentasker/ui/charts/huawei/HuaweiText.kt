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

    // --- 機能訓練 ------------------------------------------------------------------------------
    val rehabTitle = Loc("Rehab", "機能訓練")
    val rehabSummary = Loc("Done on %d of the last %d days", "直近 %2\$d 日のうち %1\$d 日")
    val rehabHint = Loc(
        "Tap any day to tick it off, or to take the tick back.",
        "どの日でも押せば付けられる。押し直せば取り消せる。",
    )
    val rehabLegend = Loc(
        "A yellow square is a day the exercises were done. Everything else is simply a day with no "
            + "record — a gap is not a failure, it is a gap.",
        "黄色の升はした日。それ以外は記録の無い日というだけで、空きは失敗ではなく空きである。",
    )
    val rehabOpen = Loc("Every day on record", "すべての記録")
    val rehabDone = Loc("Done", "した")
    val rehabNotDone = Loc("Not done", "していない")

    // --- the about card ------------------------------------------------------------------------
    val facesTitle = Loc("Watch faces", "文字盤")
    val facesInstall = Loc("Install", "入れる")
    val facesNoPreview = Loc("no picture", "画像なし")
    val facesRead = Loc("Read the band", "バンドを読む")
    val facesBandTitle = Loc("On the band", "バンドの中")
    val facesOnBand = Loc("✓ on the band", "✓ バンドに有り")
    val facesDelete = Loc("Remove", "外す")

    val facesActivate = Loc("Show on band", "盤面にする")
    val facesActivating = Loc("bringing it to the front…", "前に出しています…")
    val walksOpen = Loc("Open", "開く")
    /**
     * What a cell says while the area's base map is being fetched.
     *
     * It used to read "No map cached for this area yet", which states a fact and asks 白い熊 to do
     * something about it — and was read, correctly, as the feature being broken (白い熊,
     * 2026-09-04). The screen knows what is missing and who to ask, so it asks; this says so.
     */
    val walksAskingMap = Loc(
        "Asking 白い熊 地図 for this area…",
        "白い熊 地図 にこの辺りの地図を頼んでいます…",
    )
    val walksGetMap = Loc("Get the map for this area", "この辺りの地図を取る")
    val walksMapShared = Loc(
        "Drawn over a shared map — one map serves every walk in the area.",
        "共有の地図に重ねて描いています。同じ辺りの運動は一枚で足ります。",
    )

    val facesFullTitle = Loc("The band is full", "バンドが一杯")
    val facesFullBody = Loc(
        "Every slot is taken, so one face has to go before this one can be installed. Removing a face from the band does not lose it — the library keeps its copy and it can be installed again.",
        "空きがないので、入れる前に一つ外す必要がある。バンドから外しても失われない — 控えはこの一覧に残るので、いつでも入れ直せる。",
    )
    val facesFullPick = Loc("Which face should go?", "どれを外す？")
    val facesFullConfirm = Loc("Remove and install", "外して入れる")
    val facesCancel = Loc("Cancel", "やめる")
    val facesUnknownFace = Loc("not in this library", "控えなし")
    val facesShowing = Loc("showing now", "表示中")
    val facesUnknownOnBand = Loc("not in this library", "この庫に無い")
    val facesCountUnit = Loc("faces", "文字盤")
    val facesCountMine = Loc("in this library", "この庫の分")
    val facesCountOther = Loc("from elsewhere", "他から")
    val facesFree = Loc("free space", "空き")

    // ── naming the faces this library has no copy of ──────────────────────────────────────────
    val facesIdentify = Loc("Name the band's own faces", "バンド側の面に名前を")
    val facesIdentifyTitle = Loc("Which face is on the band?", "今どの面か")
    val facesIdentifyHow = Loc(
        "Swipe through the faces on the band. Whichever one it lands on is read back here — give " +
            "it a name and it stops being a bare number when the band is full.",
        "バンドで面を切り替えてください。今出ている面がここに出ます。名前を付ければ、" +
            "一杯になったときに数字だけで選ばずに済みます。",
    )
    val facesIdentifyWaiting = Loc("Reading the band…", "バンドを読んでいます…")
    val facesIdentifyNone = Loc("The band does not say which face is showing.", "今の面が分かりません。")
    val facesIdentifyName = Loc("Name for this face", "この面の名前")
    val facesIdentifySave = Loc("Save the name", "名前を付ける")
    val facesIdentifySaved = Loc("Saved", "保存しました")
    val facesIdentifySavedAs = Loc("Saved as", "名前")
    val facesIdentifyForget = Loc("Remove the name", "名前を消す")
    val facesIdentifyDone = Loc("Done", "終わり")
    val facesIdentifyInLibrary = Loc("already in this library", "この庫にある")
    val facesKeep = Loc("named — keep", "名前あり — 残す")
    val facesStrangersNote = Loc(
        "Installed from somewhere else, so this library has no copy. Removing one is final — it " +
            "cannot be put back from here.",
        "他所から入った面で、この庫に控えがありません。消したら戻せません。",
    )
    val facesRemoveConfirmTitle = Loc("Remove this face?", "この面を消しますか")
    val facesRemoveConfirmBody = Loc(
        "This library has no copy of it, so it cannot be reinstalled from here. Only the band's " +
            "own source could bring it back.",
        "この庫に控えがないので、ここからは入れ直せません。",
    )
    val facesRemoveConfirmYes = Loc("Remove it", "消す")
    val facesRemoveCancel = Loc("Keep it", "やめる")
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
    val walksSteps = Loc("steps", "歩")
    val walksCalories = Loc("kcal", "kcal")
    val walksClimb = Loc("climb", "上り")
    val walksBandFigures = Loc("The band's own figures", "バンド自身の集計")

    // 「機能訓練」 — the band's Free exercise, which is what 白い熊 records rehab under.
    val rehabAbout = Loc(
        "The rehab sessions the band recorded, under its own Free exercise. No route and no map — " +
            "what is here is the clock, the energy, and the heart rate the band took every five " +
            "seconds. The calendar of which days were done is filled from these.",
        "バンドが「フリー運動」として記録した機能訓練。経路も地図も無い。あるのは時刻と消費、" +
            "そしてバンドが五秒ごとに測った心拍。やった日の暦はこれで埋まる。",
    )
    val rehabEmpty = Loc(
        "No rehab sessions yet. Record one on the band with Free exercise — it needs no satellites, " +
            "so it works indoors.",
        "機能訓練の記録はまだ無い。バンドの「フリー運動」で記録する。衛星は要らないので室内でも動く。",
    )
    val rehabCalendar = Loc("Calendar", "暦")
    val windowsTitle = Loc("What the band recorded", "バンドが記録したもの")
    val noHeart = Loc("no heart rate recorded", "心拍の記録が無い")
    val calendarTapNote = Loc(
        "Tap a filled day to open that session.",
        "埋まっている日を押すと、その日の記録が開く。",
    )
    private val calendarAboutRehab = Loc(
        "Filled from the sessions the band recorded. A day done without the band can still be " +
            "marked by hand — tap an empty one.",
        "バンドが記録した分で埋まる。バンドを付けずにやった日は、空いている日を押せば手で印を付けられる。",
    )
    private val calendarAboutRecorded = Loc(
        "Filled from what the band recorded.",
        "バンドが記録した分で埋まる。",
    )

    fun calendarAbout(kind: com.opentasker.core.huawei.HuaweiWorkoutStore.Kind) =
        if (kind == com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.REHAB) calendarAboutRehab
        else calendarAboutRecorded

    /**
     * One place where a window's words are decided, rather than a conditional at each of them.
     *
     * With two kinds an `if` at every call site was tolerable. With three it is the shape that
     * quietly leaves one of them reading "Walks" because a branch was missed.
     */
    fun titleFor(kind: com.opentasker.core.huawei.HuaweiWorkoutStore.Kind) = when (kind) {
        com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.WALK -> walksTitle
        com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.STRENGTH -> liftTitle
        com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.REHAB -> rehabTitle
    }

    fun aboutFor(kind: com.opentasker.core.huawei.HuaweiWorkoutStore.Kind) = when (kind) {
        com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.WALK -> walksAbout
        com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.STRENGTH -> liftAbout
        com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.REHAB -> rehabAbout
    }

    fun emptyFor(kind: com.opentasker.core.huawei.HuaweiWorkoutStore.Kind) = when (kind) {
        com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.WALK -> walksEmpty
        com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.STRENGTH -> liftEmpty
        com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.REHAB -> rehabEmpty
    }

    fun downloadFor(kind: com.opentasker.core.huawei.HuaweiWorkoutStore.Kind) =
        if (kind == com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.WALK) walksDownload
        else liftDownload

    fun noneFoundFor(kind: com.opentasker.core.huawei.HuaweiWorkoutStore.Kind) =
        if (kind == com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.WALK) walksNoneFound
        else liftNoneFound

    /**
     * Blue for a walk, red for lifting, **yellow for 機能訓練**.
     *
     * The yellow is not a new choice. 機能訓練 has been the annotation ink everywhere it appears
     * since the calendar was built — the card, the page, the tiles — so a window titled 機能訓練 in
     * heart-rate red was the odd one out rather than the consistent one (白い熊, 2026-09-04: *"make
     * it yellow — the name and the pill"*).
     *
     * Never the only thing separating the three — the title says which window this is — because
     * 白い熊 is red-green colour blind and a screen that could only be told apart by its accent
     * would be a screen they cannot tell apart.
     */
    fun accentFor(kind: com.opentasker.core.huawei.HuaweiWorkoutStore.Kind) = when (kind) {
        com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.WALK ->
            com.opentasker.ui.charts.ChartPalette.STEPS
        com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.STRENGTH ->
            com.opentasker.ui.charts.ChartPalette.HEART_RATE
        com.opentasker.core.huawei.HuaweiWorkoutStore.Kind.REHAB ->
            com.opentasker.ui.charts.ANNOTATION_INK
    }

    // 「重量挙げ」 — lifting, and the effort figures both it and the walks now carry.
    val liftTitle = Loc("Lifting", "重量挙げ")
    val liftAbout = Loc(
        "The strength sessions the band recorded. No route and no map — a lift has neither — so " +
            "what is here is the clock, the energy, and the heart rate the band took every five " +
            "seconds while you worked.",
        "バンドが記録した筋トレ。経路も地図も無い — 重量挙げには無いもの — ので、ここにあるのは" +
            "時刻と消費、そしてバンドが五秒ごとに測った心拍。",
    )
    val liftEmpty = Loc(
        "No lifting sessions yet. Start one on the band's own Workout app; it needs no satellites, " +
            "so it records anywhere.",
        "筋トレの記録はまだ無い。バンドの運動アプリで始める。衛星は要らないので、どこでも記録される。",
    )
    val liftDownload = Loc("Ask the band for new sessions", "新しい記録を取りに行く")
    val liftNoneFound = Loc("The band had no new sessions.", "バンドに新しい記録は無かった。")

    val effortTitle = Loc("Heart rate", "心拍")
    val effortMean = Loc("mean", "平均")
    val effortFromBand = Loc(
        "the band's own reading, taken while the workout ran",
        "運動中にバンド自身が測った値",
    )
    val effortRecovery = Loc("recovery", "回復")
    val effortRecoveryNote = Loc(
        "How far the heart fell once the work stopped — twenty-five readings the band takes after " +
            "the workout ends. It does not say how far apart they are, so neither do we.",
        "動きを止めた後に心拍がどこまで下がったか — 運動終了後にバンドが取る二十五回の計測。" +
            "その間隔はバンドが言わないので、こちらも言わない。",
    )
    val effortSplits = Loc("Per kilometre", "1キロごと")
    val walksExportTitle = Loc("Export", "書き出し")
    val chizuNoDetail = Loc(
        "白い熊 地図 has no map data for this area — only its world basemap, which at this zoom is " +
            "an empty picture. Nothing was cached.",
        "白い熊 地図 にこの辺りの地図が入っていない — 世界地図だけで、この倍率では白紙。" +
            "何も保存していない。",
    )
    val chizuNothingDrawn = Loc(
        "白い熊 地図 answered but drew nothing — the picture would have been solid black. " +
            "Nothing was cached; try again.",
        "白い熊 地図 は答えたが何も描かれなかった — 真っ黒な絵になるところだった。" +
            "何も保存していない。もう一度。",
    )
    val chizuNeedsRebuild = Loc(
        "白い熊 地図 has not been rebuilt for the new hand-over yet. Nothing was sent and nothing " +
            "was lost — opening a walk already in 地図 still works.",
        "白い熊 地図 がまだ新しい受け渡しに対応していない。何も送られず、何も失われていない — " +
            "すでに 地図 にある散歩を開くのは今でも動く。",
    )
    val walksExportFailed = Loc("could not write the file", "書き出せなかった")
    /** Said plainly: this one is OURS, averaged from the synced samples, not the band's own number. */
    val walksHeartFromSamples = Loc(
        "mean heart rate over this window, averaged from the synced samples — not the band's own figure",
        "同期した標本から求めたこの区間の平均心拍 — バンドが出す数値そのものではない",
    )
    val walksHeart = Loc("mean HR", "平均心拍")
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
