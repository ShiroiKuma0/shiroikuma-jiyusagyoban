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
    val HEART_RATE = Color(0xFF3987E5)   // slot 1 blue
    val HRV = Color(0xFFD95926)          // slot 2 orange
    val SPO2 = Color(0xFF199E70)         // slot 3 aqua
    val TEMPERATURE = Color(0xFFC98500)  // slot 4 yellow
    val STEPS = Color(0xFFD55181)        // slot 5 magenta
    val BAND_INDEX = Color(0xFF9085E9)   // slot 7 violet
    val BLOOD_PRESSURE = Color(0xFFE66767) // slot 8 red — the card accent

    /**
     * Systolic and diastolic, on ONE axis because they share one unit.
     *
     * Validated as a pair: CVD ΔE 26.8, normal-vision 31.8, both PASS. A second y-scale for the
     * second series would be the dual-axis mistake — it lets any two series be made to look
     * correlated by choosing the scales.
     */
    val SYSTOLIC = Color(0xFF3987E5)
    val DIASTOLIC = Color(0xFFD95926)

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
}
