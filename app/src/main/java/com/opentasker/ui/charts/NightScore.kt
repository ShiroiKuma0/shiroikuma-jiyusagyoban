package com.opentasker.ui.charts

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 夜の点数 — one 0–100 for each night of the table, from the grades its own cells already carry.
 *
 * ## What it is, and what it is not (白い熊, 2026-11-03)
 *
 * 白い熊 asked for a total in the first column, weighted across the categories, with the weights on a
 * page of their own where they can be slid, reset and argued with. It is built here on purpose as a
 * **summary of the row**, not as a new measurement: each component is the 1–5 step its cell is
 * already painted with, turned into points (1 → 100, each step down −25, 5 → 0), and the score is
 * their weighted mean. So a score never disagrees with the colours beside it — a row of 2s scores 75
 * and is painted a 2 — and every point of it can be traced back to a cell.
 *
 * **It does not replace the count.** No weighted readiness composite has been validated anywhere;
 * the only composite with published operating points is the count of markers outside usual (≥ 2 of
 * 3). The count stays what the 回復 headline says. This number is the tunable summary beside it,
 * and the weights page says so.
 *
 * **How you woke is not a component.** It is what the score is checked AGAINST — [calibration]
 * correlates the score with 白い熊's own 1–5 ratings, so the weights have something to aim at other
 * than taste. A score that contained the rating would correlate with it by construction.
 *
 * **It refuses rather than guesses**, like [HealthIndex]: a missing cell is left out and the rest
 * renormalised, the score is marked partial, and below [MIN_SHARE] of the weight it is not given at
 * all — a night scored from its blood oxygen alone is not comparable down a column with one scored
 * from twelve cells.
 *
 * Pure Kotlin, like everything here that decides something.
 */
object NightScore {

    /**
     * The table's graded cells, in the table's order, each with its default weight and the reason.
     *
     * The defaults sum to 100 so each reads as a percentage; the page renormalises whatever 白い熊
     * sets, so they never have to. [marker] is the history page the weights page links to.
     */
    enum class Part(
        val label: Loc,
        val default: Int,
        val why: Loc,
        val marker: RecoveryMarker?,
    ) {
        BP(
            BandText.regColBp, 3,
            Loc(
                "The morning cuff reading. Blood pressure is the largest modifiable cardiovascular " +
                    "risk, but it is a state of the day rather than of the night just slept, and one " +
                    "reading a morning is noisy — so a small share.",
                "朝の血圧。心血管の最大の修正可能リスクだが、眠った夜より日中の状態を表し、朝一回の測定はばらつく。だから小さめ。",
            ),
            RecoveryMarker.BP_SYSTOLIC,
        ),
        TEMP(
            BandText.regColTemp, 6,
            Loc(
                "Skin temperature against your own nights. A rise of a few tenths is one of the " +
                    "earliest signs of an oncoming illness in the wearable studies (TemPredict and " +
                    "others), which is why it weighs more than its sensor's precision would suggest.",
                "自分の夜と比べた皮膚温。数十分の一度の上昇は、ウェアラブル研究（TemPredict など）で体調不良の最も早い兆しの一つ。だからセンサーの精度以上に重く。",
            ),
            RecoveryMarker.TEMPERATURE,
        ),
        NIGHT_HR(
            BandText.regColHr, 12,
            Loc(
                "Nocturnal heart rate against your usual — one of the three counted markers, and " +
                    "the best-evidenced overnight sign of strain, late training, alcohol and " +
                    "illness (Leota et al. 2025, over 4 million person-nights). The heaviest weight.",
                "平常と比べた夜間心拍。数える三指標の一つで、負荷・遅い運動・飲酒・体調不良の最も根拠ある夜の兆し（Leota ほか 2025、400 万夜超）。最も重い。",
            ),
            RecoveryMarker.NOCTURNAL_HR,
        ),
        BED_HR(
            BandText.regColBedHr, 3,
            Loc(
                "The first hour in bed. It caught the 2026-09-12 episode that every counted marker " +
                    "missed, but it is one night's evening, within-person only, and moves with " +
                    "what the evening was — so a small share.",
                "床に就いた最初の一時間。2026-09-12 の不調を他の全指標が見逃す中で捉えたが、自分比のみで夜の過ごし方に左右される。だから小さめ。",
            ),
            RecoveryMarker.BEDTIME_HR,
        ),
        SWING(
            BandText.regColSwing, 2,
            Loc(
                "How far the heart rate moved across the night. Largely the same information as " +
                    "the curve beside it, and on your own data it lagged the illness rather than " +
                    "led it — the smallest heart-rate share.",
                "夜の心拍の振れ幅。隣の曲線とほぼ同じ情報で、自分のデータでは不調に先行せず遅れた。心拍系で最小。",
            ),
            RecoveryMarker.HR_SWING,
        ),
        CURVE(
            BandText.regColCurve, 5,
            Loc(
                "The night's descent against your usual one. A heart rate that does not fall at " +
                    "night (non-dipping) is a recognised adverse sign, and the curve is the cell " +
                    "that shows it — a real share.",
                "平常と比べた夜の下降。夜に下がらない心拍（ノンディッパー）は知られた悪い兆しで、曲線がそれを示す。相応に。",
            ),
            RecoveryMarker.HR_SWING,
        ),
        SLEEP(
            BandText.regColSleep, 12,
            Loc(
                "Time asleep, on the NSF / AASM ladder. Sleep duration has the deepest literature " +
                    "of anything in this row, and it is what a watch measures best — tied for the " +
                    "heaviest weight.",
                "睡眠時間、NSF・AASM の段階で。この行で最も文献が厚く、時計が最もよく測れる量。最も重い一つ。",
            ),
            RecoveryMarker.SLEEP,
        ),
        DEEP(
            BandText.regColDeep, 1,
            Loc(
                "Deep sleep minutes. A wrist band's staging agrees with polysomnography only " +
                    "weakly (κ ≈ 0.20–0.53), and this band's \"deep\" runs at twice the literature's " +
                    "N3 — so it is kept in, barely.",
                "深い睡眠の分数。手首の睡眠段階判定と終夜睡眠ポリグラフの一致は弱く（κ ≈ 0.20〜0.53）、このバンドの「深い」は文献の N3 の倍。だからごくわずか。",
            ),
            RecoveryMarker.DEEP,
        ),
        DEEP_REM(
            BandText.regColDeepRem, 2,
            Loc(
                "Deep + REM as a share of the night. The same staging weakness as deep, a little " +
                    "steadier as a share — a small weight.",
                "深い睡眠＋レム睡眠の割合。深い睡眠と同じ判定の弱さだが、割合の方が少し安定。小さめ。",
            ),
            RecoveryMarker.DEEP_REM,
        ),
        LOW_HR(
            BandText.regColLowHr, 5,
            Loc(
                "The night's lowest heart rate, on Jensen's decades. A good fitness reading, but " +
                    "it overlaps the nocturnal rate heavily — counting both fully would weigh one " +
                    "thing twice.",
                "夜の最低心拍、Jensen の 10 拍区分で。良い体力の指標だが夜間心拍と大きく重なる。両方を全量で数えると同じものを二度量ることになる。",
            ),
            RecoveryMarker.LOWEST_HR,
        ),
        HRV(
            BandText.regColHrv, 10,
            Loc(
                "RMSSD from the watch's own beat-to-beat intervals. Overnight HRV is the most-used " +
                    "recovery signal in the literature and here it is a real measurement, checked " +
                    "against the beats — a large share.",
                "時計の拍間隔から計算した RMSSD。夜間 HRV は文献で最も使われる回復指標で、ここでは拍と照合した実測。大きめ。",
            ),
            RecoveryMarker.HRV,
        ),
        BREATHS(
            BandText.regColResp, 4,
            Loc(
                "Breathing rate against your own nights. A rise is an early illness sign in the " +
                    "wearable studies; here it is estimated from the beat series, not measured — " +
                    "a moderate share.",
                "自分の夜と比べた呼吸数。上昇はウェアラブル研究で体調不良の早い兆し。ここでは拍の系列からの推定で実測ではない。中程度。",
            ),
            RecoveryMarker.RESPIRATION,
        ),
        SPO2(
            BandText.regColSpo2, 2,
            Loc(
                "Blood oxygen on the clinical ranges. A wrist oximeter's error is about twice the " +
                    "swing it measures, so a low reading is worth a look and little more — a small share.",
                "臨床区分での血中酸素。手首の酸素計の誤差は測る振れ幅の約二倍。低い値は気に留める程度。小さめ。",
            ),
            RecoveryMarker.SPO2,
        ),
        CONTINUITY(
            BandText.regColWaso, 8,
            Loc(
                "Time awake after first falling asleep (WASO) — the standard measure of a broken " +
                    "night: under 20 min, 20–40, 40–60, 60–90, over 90. Getting up in pain and catching " +
                    "up on sleep later is a bad night however long the total (白い熊, 2026-11-08).",
                "入眠後の覚醒時間（WASO）— 途切れた夜の標準的な指標。20 分未満・20〜40・40〜60・60〜90・90 分超。痛みで起き、後で寝足した夜は、合計が長くても悪い夜（白い熊、2026-11-08）。",
            ),
            RecoveryMarker.SLEEP,
        ),
        WOKE(
            BandText.regColFelt, 25,
            Loc(
                "How you woke, your own 1–5. The heaviest weight: the cells measure the body, the " +
                    "rating is how the morning actually is, and a green score on a morning rated 4 " +
                    "was the score being wrong (白い熊, 2026-11-08). The fit at the top of this page " +
                    "is computed WITHOUT it, so it still says how well the measurements alone track you.",
                "目覚め、自分でつける 1〜5。最も重い。セルは体を測り、評価は朝の実際。4 と付けた朝の緑の点数は点数の誤り（白い熊、2026-11-08）。上の相関はこれを除いて計算するので、測定だけがどれだけ合うかを示し続ける。",
            ),
            RecoveryMarker.FELT,
        ),
    }

    /** What a slider can be set to. Zero takes a cell out of the score entirely. */
    const val MAX_WEIGHT = 30

    /** Below this share of the weight measured, a night gets a dash rather than a score. */
    const val MIN_SHARE = 0.5

    val DEFAULTS: Map<Part, Int> = Part.entries.associateWith { it.default }

    /** A 1–5 step as points: 1 → 100, 5 → 0, 25 a step. */
    fun points(step: Int): Int = (5 - step.coerceIn(1, 5)) * 25

    /** A score back to the step it is painted with — the nearest one, so a row of 3s is a 3. */
    fun stepOf(score: Int): Int = (1 + ((100 - score.coerceIn(0, 100)) / 25.0).roundToInt()).coerceIn(1, 5)

    /** One component of one night. [step] null means the cell was empty. */
    data class Component(val part: Part, val step: Int?, val weight: Int) {
        val points: Int? get() = step?.let(::points)
    }

    data class Score(
        /** 0–100, or null when less than [MIN_SHARE] of the weight was measured. */
        val value: Int?,
        val components: List<Component>,
        /** Measured weight over all weight, 0..1. */
        val share: Double,
        /** The illness signs that held the score down to [CAP]; empty when it was not capped. */
        val cappedBy: List<Part> = emptyList(),
        /** How the night was broken, when it was — see [broken]. */
        val continuity: SleepShape.Continuity? = null,
    ) {
        val partial: Boolean get() = components.any { it.step == null && it.weight > 0 }
        val capped: Boolean get() = cappedBy.isNotEmpty()

        /** A night broken by a long spell up, or by an hour or more awake in all. */
        val broken: Boolean
            get() = continuity != null &&
                (continuity.catchUpMinutes > 0 || SleepShape.wasoStep(continuity.wasoMinutes) >= 4)

        /** `*` partial, `!` capped or broken — both explained in the breakdown. */
        val marks: String get() = (if (partial) "*" else "") + (if (capped || broken) "!" else "")
        val step: Int? get() = value?.let(::stepOf)
        val missing: List<Component> get() = components.filter { it.step == null && it.weight > 0 }

        /** What one component added, in points of the final score. */
        fun contribution(c: Component): Double {
            val measured = components.filter { it.step != null }.sumOf { it.weight }
            return if (measured == 0 || c.step == null) 0.0 else c.points!! * c.weight.toDouble() / measured
        }
    }

    /** Each cell's step, exactly as the table paints it. A collecting temperature is no grade. */
    fun steps(n: SessionRegister.NightReading): Map<Part, Int?> = mapOf(
        Part.BP to n.bpStep,
        Part.TEMP to n.temperature.takeIf { !it.temperatureCollecting }?.displayStep,
        Part.NIGHT_HR to n.nocturnalHr.displayStep,
        Part.BED_HR to n.bedtimeHrStep,
        Part.SWING to n.hrSwingStep,
        Part.CURVE to n.hrCurveStep,
        Part.SLEEP to n.sleep.displayStep,
        Part.DEEP to n.deepStep,
        Part.DEEP_REM to n.deepRemStep,
        Part.LOW_HR to n.lowestHrStep,
        Part.HRV to n.hrvStep,
        Part.BREATHS to n.respirationStep,
        Part.SPO2 to n.spo2Step,
        Part.CONTINUITY to n.wasoStep,
        Part.WOKE to n.felt.value?.roundToInt()?.coerceIn(1, 5),
    )

    fun score(n: SessionRegister.NightReading, weights: Map<Part, Int>): Score =
        score(steps(n), weights).copy(continuity = n.continuity)

    /**
     * The ceiling illness signs put on a night: no better than 4, “below par” (白い熊, 2026-11-08).
     *
     * An average lets a fever night come out ordinary — temperature was 8 of 100, and on 2026-11-06
     * and -07, sick with a temperature, the nights scored a blue 54 and 44. Illness is not something
     * the other cells can make up for, so it is a ceiling, not a weight: the night is capped when the
     * temperature is graded 4 or 5, or when two of the three illness markers (temperature, night
     * heart rate, HRV) are.
     */
    const val CAP = 37

    /** Which illness signs cap a night with these steps; empty when none does. */
    fun illness(steps: Map<Part, Int?>): List<Part> {
        val off = listOf(Part.TEMP, Part.NIGHT_HR, Part.HRV).filter { (steps[it] ?: 0) >= 4 }
        return if (Part.TEMP in off || off.size >= 2) off else emptyList()
    }

    fun score(steps: Map<Part, Int?>, weights: Map<Part, Int>): Score {
        val components = Part.entries.map { Component(it, steps[it], weights[it] ?: it.default) }
        val total = components.sumOf { it.weight }
        val measured = components.filter { it.step != null && it.weight > 0 }
        val measuredWeight = measured.sumOf { it.weight }
        val share = if (total == 0) 0.0 else measuredWeight.toDouble() / total
        val value = if (measuredWeight == 0 || share < MIN_SHARE) {
            null
        } else {
            (measured.sumOf { it.points!! * it.weight }.toDouble() / measuredWeight).roundToInt()
        }
        val cappedBy = if (value != null && value > CAP) illness(steps) else emptyList()
        return Score(if (cappedBy.isEmpty()) value else CAP, components, share, cappedBy)
    }

    // ---- calibration against how 白い熊 woke ---------------------------------------------------

    /**
     * A rank correlation with its 95 % interval and the nights behind it.
     *
     * Spearman rather than Pearson: the rating is five ordered steps, not a measurement with a unit,
     * and the score is a sum of steps. The interval is Fisher's, with the 1.06 variance correction
     * for a Spearman coefficient (Fieller, Hartley & Pearson 1957) — honest enough to show that 30
     * nights cannot tell 0.3 from 0.5.
     */
    data class Fit(val rho: Double, val n: Int, val lo: Double, val hi: Double)

    /** Below this many rated, scored nights there is no correlation worth printing. */
    const val MIN_FIT_NIGHTS = 10

    /** How well the score tracks the rating: positive = a higher score on mornings you woke better. */
    fun calibration(rows: List<SessionRegister.NightRow>, weights: Map<Part, Int>): Fit? {
        val pairs = rows.mapNotNull { row ->
            val felt = row.felt ?: return@mapNotNull null
            // Without the rating itself, or the fit would be a rating correlated with itself.
            val s = row.night?.let { score(it, weights + (Part.WOKE to 0)).value } ?: return@mapNotNull null
            s.toDouble() to points(felt).toDouble()
        }
        return fit(pairs)
    }

    /** Each cell on its own against the rating — what the page prints beside each slider. */
    fun perPart(rows: List<SessionRegister.NightRow>): Map<Part, Fit?> {
        val rated = rows.mapNotNull { row ->
            val felt = row.felt ?: return@mapNotNull null
            val n = row.night ?: return@mapNotNull null
            steps(n) to points(felt).toDouble()
        }
        return Part.entries.associateWith { part ->
            if (part == Part.WOKE) return@associateWith null   // the rating against itself
            fit(rated.mapNotNull { (s, felt) -> s[part]?.let { points(it).toDouble() to felt } })
        }
    }

    /**
     * Weights proposed from 白い熊's own ratings: half the default, half each cell's own correlation
     * with the rating (negative ones count as nothing), rounded.
     *
     * Deliberately SHRUNK toward the defaults rather than fitted outright. Eighty-odd nights against
     * thirteen weights is a classic overfit — a free fit would chase the noise of this autumn and
     * score next spring worse than the defaults do. Halfway is a plain, explainable amount of
     * shrinkage; it is a proposal, shown with its own correlation, and applied only on a tap.
     */
    fun suggest(rows: List<SessionRegister.NightRow>): Map<Part, Int>? {
        val fits = perPart(rows)
        val positive = fits.mapValues { (_, f) -> f?.rho?.coerceAtLeast(0.0) ?: 0.0 }
        val sum = positive.values.sum()
        if (sum <= 0.0) return null
        // The rating keeps its own weight; the measurements share out the rest.
        val rest = 100 - Part.WOKE.default
        return Part.entries.associateWith { part ->
            if (part == Part.WOKE) return@associateWith part.default
            (0.5 * part.default + 0.5 * rest * (positive[part] ?: 0.0) / sum)
                .roundToInt().coerceIn(0, MAX_WEIGHT)
        }
    }

    fun fit(pairs: List<Pair<Double, Double>>): Fit? {
        if (pairs.size < MIN_FIT_NIGHTS) return null
        val rho = spearman(pairs.map { it.first }, pairs.map { it.second }) ?: return null
        val n = pairs.size
        val z = 0.5 * ln((1 + rho.coerceIn(-0.999, 0.999)) / (1 - rho.coerceIn(-0.999, 0.999)))
        val se = sqrt(1.06 / (n - 3))
        return Fit(rho, n, kotlin.math.tanh(z - 1.96 * se), kotlin.math.tanh(z + 1.96 * se))
    }

    /** Spearman's ρ with average ranks for ties; null when either side does not vary. */
    fun spearman(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size != ys.size || xs.size < 3) return null
        val rx = ranks(xs)
        val ry = ranks(ys)
        val mx = rx.average()
        val my = ry.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in rx.indices) {
            num += (rx[i] - mx) * (ry[i] - my)
            dx += (rx[i] - mx) * (rx[i] - mx)
            dy += (ry[i] - my) * (ry[i] - my)
        }
        if (dx < 1e-12 || dy < 1e-12) return null
        return num / sqrt(dx * dy)
    }

    private fun ranks(v: List<Double>): List<Double> {
        val order = v.indices.sortedBy { v[it] }
        val out = DoubleArray(v.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && abs(v[order[j + 1]] - v[order[i]]) < 1e-12) j++
            val rank = (i + j) / 2.0 + 1
            for (k in i..j) out[order[k]] = rank
            i = j + 1
        }
        return out.toList()
    }

    /**
     * A rolling median of the last [window] scores at each point, for the trend line.
     *
     * A median, not a mean: one wrecked night should bend a week's line, not drag it.
     */
    fun rollingMedian(values: List<Int?>, window: Int = 7): List<Double?> = values.indices.map { i ->
        val slice = values.subList(maxOf(0, i - window + 1), i + 1).filterNotNull()
        if (slice.size < (window + 1) / 2) null else HealthIndexSource.median(slice.map { it.toDouble() })
    }
}
