package com.opentasker.ui.charts

import androidx.compose.ui.graphics.Color

/**
 * Chart colours — computed and validated, not chosen by eye.
 *
 * Every value here was run through the data-viz validator against **this app's own surface**
 * (`#0D0D0D`, the fork's near-black card) in dark mode, and only passing sets were kept. The checks
 * are lightness band, chroma floor, CVD separation between adjacent series, a normal-vision floor of
 * ΔE 15, and 3:1 contrast against the surface.
 *
 * ## What the validator rejected, so nobody re-proposes it
 *
 * The obvious hypnogram colours — Hume's own deep-green / light-blue / REM-violet / awake-red — fail
 * outright: **violet `#9085e9` against blue `#3987e5` measures ΔE 1.9 under protanopia and 9.8 with
 * normal colour vision.** Two sleep stages nobody can tell apart is not a stylistic quibble. Magenta
 * beside red (7.8) and yellow beside orange (10.6) fail the same way.
 *
 * The fix is not a nicer pair of hues, it is **using the documented slot order**, which is the
 * CVD-safety mechanism rather than a style guide. So the metric colours below run in slot order, and
 * the sleep stages use the one ordering of slots 1–4 that clears every gate while still putting a
 * cool hue at the deep end and a warm one at the awake end.
 *
 * ## Hume's rainbows are deliberately not reproduced
 *
 * Their band ladders and value-tinted lines sweep red→green→blue. A rainbow is forbidden for
 * magnitude: it has no perceptual order, so a reader cannot tell which end is "more" without the
 * legend. The idea worth keeping — colour carrying value — survives as [sequential], one hue running
 * light→dark, which does have an order.
 */
object ChartPalette {

    // --- series identity, in documented slot order -------------------------------------------
    // Validated as a set, dark, surface #0D0D0D: worst adjacent CVD ΔE 8.4, normal-vision 19.3.
    // ALL CHECKS PASS. Do not reorder without re-running the validator.
    // 白い熊, 2026-08-23: steps take the blue and the heart rate takes the red.
    //
    // The red was NOT free. Measured against everything already on screen, a red heart rate lands at
    // ΔE 7.1 from the orange HRV / band state, 6.7 from the amber resting heart rate, and 7.1 from a
    // warm blood oxygen — all below the threshold for telling two colours apart AT ALL, before
    // red-green deficiency is considered. Warm hues collapse toward each other under it, so the
    // palette can host exactly ONE warm series. The orange therefore moves to plum to make room, and
    // blood oxygen stays aqua rather than becoming the orange-red that was asked for: beside this
    // red it measured ΔE 7.1, and no better orange exists — nine combinations were measured.
    val HEART_RATE = Color(0xFFE66767)   // red — as asked
    val HRV = Color(0xFFA96BAF)          // plum — vacated the orange for the red heart rate
    val SPO2 = Color(0xFF199E70)         // slot 3 aqua
    val TEMPERATURE = Color(0xFFC98500)  // slot 4 yellow
    val STEPS = Color(0xFF3987E5)        // blue — as asked

    /**
     * 安静時心拍 — the Huawei band's resting heart rate, and the first series this app added that
     * the Hume band never had.
     *
     * **It deliberately reuses slot 4 rather than introducing an eighth hue, because there is no
     * eighth hue to introduce.** Hume already occupies all seven documented slots, and a search of
     * OKLCh space at this app's own surface found nothing that is both CVD-separable from all seven
     * and bright enough to sit in family: every candidate above 4.5:1 contrast lands on top of the
     * existing orange or yellow (ΔE 0.9–2.4), and the ones that do separate are dim (~3.5:1).
     *
     * So the placement is the decision. On the Huawei screen the cards run
     * 歩数 → 心拍 → 血中酸素 → 安静時心拍, and measured across **all four** — not merely neighbours —
     * this yellow floors at **CVD ΔE 8.4 / normal ΔE 19.3, contrast 6.33:1**. That 8.4 is not a new
     * tolerance; it is exactly the worst-adjacent figure already shipping in both the series set
     * above and the sleep set below.
     *
     * ## What was rejected, so nobody re-proposes it
     *
     * - **Violet, slot 7** — the obvious pick, and a disaster: **ΔE 1.9 under protanopia and 9.8
     *   with ordinary colour vision against slot 1 blue**, failing even the gate that cannot be
     *   waived. Worse, 心拍 and 安静時心拍 are the same quantity, so they are the two cards a reader
     *   most wants to compare. Adjacency alone passes it — violet's neighbour would be aqua — which
     *   is precisely why the check that matters here is all-pairs across the screen.
     * - **Orange, slot 2** — normal ΔE 11.6 against 歩数 magenta, below the 15.0 floor.
     * - **Red, slot 8** — ΔE 6.5 against aqua and 7.5 against magenta. Fails outright.
     *
     * The cost, stated rather than hidden: yellow already means 体温 on the Hume screen. It is a
     * different window, the cards are labelled, and the Huawei band has no temperature metric, so
     * the two never appear together.
     */
    // Amber against the red heart rate is ΔE 6.7 — the closest pair in the palette, and the
    // one that matters most because the two are the SAME quantity. Kept rather than moved
    // because every alternative collided with the blue steps instead, which is worse: a
    // resting heart rate is read against the heart rate, not against a step count.
    // Lime since 2026-08-23, and no longer shared with TEMPERATURE.
    //
    // It used to be the amber, on the reasoning that a resting heart rate and a skin temperature
    // never appear on one card. Against a RED heart rate the amber measured ΔE 6.7 under red-green
    // deficiency and 13.0 to ordinary vision — and these two are the same quantity, so they are read
    // against each other whatever the card order. Twelve candidates were measured; lime is the only
    // one that clears the red (11.6 / 27.1), the blue steps (30.6) and the aqua blood oxygen (16.3)
    // all at once.
    val RESTING_HEART_RATE = Color(0xFF9CCC65) // lime

    val BAND_INDEX = Color(0xFF9085E9)   // slot 7 violet
    val BLOOD_PRESSURE = Color(0xFFD55181) // magenta — the heart rate took the red

    /**
     * Systolic and diastolic, on ONE axis because they share one unit.
     *
     * Validated as a pair: CVD ΔE 26.8, normal-vision 31.8, both PASS. A second y-scale for the
     * second series would be the dual-axis mistake — it lets any two series be made to look
     * correlated by choosing the scales.
     */
    // The blue and the orange both moved on 2026-08-23 — steps took one, the heart rate
    // displaced the other — so the pair was re-measured rather than left pointing at colours
    // that now mean something else. Magenta against violet passes on its own card.
    val SYSTOLIC = Color(0xFFD55181)
    val DIASTOLIC = Color(0xFF8B6FD8)

    /**
     * Sleep stages, keyed to the band's RAW codes.
     *
     * 1 = deep, 2 = light, 3 = REM, 5 = awake. The vendor plugin re-codes these to 1=deep 2=light
     * 3=awake 4=REM before its own layer sees them, and mixing the two schemes silently swaps REM and
     * awake — which is why the raw codes are what this app stores and what these colours key off.
     *
     * Validated in this order: worst adjacent CVD ΔE 8.4, normal-vision 19.8, ALL PASS. Aqua at the
     * deep end and orange at the awake end is the best semantic fit among the orderings that pass;
     * the conventional violet-for-REM is exactly the one that fails.
     */
    val SLEEP_DEEP = Color(0xFF199E70)    // slot 3 aqua
    val SLEEP_LIGHT = Color(0xFFC98500)   // slot 4 yellow
    val SLEEP_REM = Color(0xFF3987E5)     // slot 1 blue
    val SLEEP_AWAKE = Color(0xFFD95926)   // slot 2 orange

    fun sleepStage(rawCode: Char): Color = when (rawCode) {
        '1' -> SLEEP_DEEP
        '2' -> SLEEP_LIGHT
        '3' -> SLEEP_REM
        '5' -> SLEEP_AWAKE
        else -> UNKNOWN
    }

    /** Code 4 has never been observed in 2 970 stage-minutes; if it ever appears it shows as this. */
    val UNKNOWN = Color(0xFF6B6B68)

    // --- non-series ink ----------------------------------------------------------------------
    /** Grid and axes are recessive: they orient, they do not compete with the data. */
    val GRID = Color(0x1AFFFFFF)
    val AXIS_TEXT = Color(0xFF8A8A85)
    /** A stretch with no measurement. Tinted, never a dashed connector — that would read as data. */
    val GAP_TINT = Color(0x14FFFFFF)
    /** Hollow ✕ at a flagged sample's real value. */
    val REJECTED = Color(0xFFE66767)

    /**
     * A single-hue ramp for magnitude, light→dark, from the validated blue scale.
     *
     * Used where colour should carry value rather than identity — the area fill under a line. One
     * hue with monotone lightness, so "more" is legible without a legend.
     */
    fun sequential(t: Float): Color {
        val f = t.coerceIn(0f, 1f)
        val stops = listOf(
            Color(0xFFCDE2FB), Color(0xFF9EC5F4), Color(0xFF6DA7EC),
            Color(0xFF3987E5), Color(0xFF256ABF), Color(0xFF184F95),
        )
        val scaled = f * (stops.size - 1)
        val i = scaled.toInt().coerceAtMost(stops.size - 2)
        return lerp(stops[i], stops[i + 1], scaled - i)
    }

    private fun lerp(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t,
    )

    /**
     * The qualitative band ladder's colours — good through bad.
     *
     * These are the reserved status roles, never reused for a series, and they always ship beside a
     * label so the state never rests on hue alone.
     */
    val BAND_GOOD = Color(0xFF0CA30C)
    val BAND_WARN = Color(0xFFFAB219)
    val BAND_SERIOUS = Color(0xFFEC835A)
    val BAND_CRITICAL = Color(0xFFD03B3B)

    /**
     * The shared 1–5 scale — one colour vocabulary for every graded value in 回復.
     *
     * **1 is the best step and 5 the worst** (白い熊, 2026-08-12; it ran the other way until then).
     * Used for 白い熊's own 体感 rating and for every measured marker beside it, because a reader who
     * has learned one column has then learned all of them.
     *
     * ## How these five were arrived at
     *
     * 白い熊 chose the anchors and rejected everything else by eye across five rounds of rendered
     * strips: pure yellow at the good end, pure red at 4, and the same red pressed down at 5. Each
     * candidate was measured first and drawn second, so what was rejected was rejected knowing its
     * numbers.
     *
     * The measurements that shaped it, all against 白い熊's own red-green deficiency:
     *
     * - **Pure green `#00FF00` cannot sit beside pure yellow** — ΔE 3.5 under deuteranopia. `#00D084`
     *   is the emerald that survives it (18.6) without drifting into teal.
     * - **The middle of the orange family was unusable while 4 was a pure red**: everything from about
     *   `#E5760A` down to `#A34B00` measured under ΔE 6 against it. Moving the dark red to 5 is what
     *   opened the orange-red at 4 back up.
     * - **Violet cannot sit beside blue** — the failure this file's header already records — so every
     *   violet and purple candidate for 4 was dropped.
     * - **A lighter 4 is limited by the EMERALD, never by the dark red.** A light red and a light
     *   green are the same colour to a red-green reader: `#FF8080` measures ΔE 1.5 against the 2.
     *   `#F4511E` is as light as the 4 can go while holding 11.7 against it — and it holds that
     *   because an orange-red carries yellow, which is the axis colour deficiency leaves alone.
     *
     * Adjacent pairs, ordinary vision / worst of protanopia and deuteranopia: 26.7/18.6, 40.3/35.9,
     * 44.4/32.9, 27.0/21.3. The worst of ALL ten pairs is 11.7 — the green at 2 against the 4, which
     * is the ceiling of any palette holding both a green and a red.
     *
     * 4 and 5 are deliberately one family getting worse rather than two hues, so the bad end is read
     * by lightness, which no colour deficiency touches.
     */
    val SCALE = listOf(
        Color(0xFFFFFF00), // 1 — yellow, best
        Color(0xFF00D084), // 2 — emerald
        Color(0xFF1E5AFF), // 3 — blue, the neutral middle
        Color(0xFFF4511E), // 4 — orange-red
        Color(0xFFA00000), // 5 — dark red, worst
    )

    /**
     * The ink each step carries, chosen by 白い熊 pill by pill rather than computed.
     *
     * Two of them are deliberately not the maximum-contrast choice: blue on the yellow and yellow on
     * the blue are the pairing the rest of 「健康」 already uses for data-against-explanation, and
     * white on the red reads as a warning label where black on it reads as a road sign. All five clear
     * 4:1 against their fill, which is above the 3:1 large-text floor these pills are drawn at.
     */
    val SCALE_INK = listOf(
        Color(0xFF1E5AFF), // on yellow
        Color(0xFF000000), // on emerald
        Color(0xFFFFFF00), // on blue
        Color(0xFF000000), // on orange-red
        Color(0xFFFFFFFF), // on dark red
    )

    /** [SCALE] by its 1–5 step, clamped. */
    fun scale(step: Int): Color = SCALE[(step - 1).coerceIn(0, SCALE.lastIndex)]

    /** [SCALE_INK] by its 1–5 step, clamped. */
    fun scaleInk(step: Int): Color = SCALE_INK[(step - 1).coerceIn(0, SCALE_INK.lastIndex)]
}
