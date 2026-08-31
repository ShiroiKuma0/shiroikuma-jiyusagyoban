package com.opentasker.ui.charts

import com.opentasker.ui.theme.ThemePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour validation, now executable.
 *
 * [ChartPalette]'s KDoc records that the shipped colours were validated with the data-viz skill's
 * Node script and that Hume's own hypnogram palette was rejected. Both were prose: true when written,
 * unenforced afterwards, and about to be handed a settings screen full of colour pickers.
 *
 * These tests turn that record into an assertion. The shipped palette passing is the obvious half;
 * the rejection is the half that matters, because it proves the port is actually measuring the thing
 * the original measured rather than passing everything.
 */
class PaletteCheckTest {

    private val defaults = ThemePrefs.DEFAULT

    @Test
    fun `the shipped metric colours pass every check`() {
        val report = PaletteCheck.validate(
            listOf(
                "hr" to defaults.chartColorHeartRate,
                "state" to defaults.chartColorBandState,
                "spo2" to defaults.chartColorSpo2,
                "temp" to defaults.chartColorTemperature,
                "steps" to defaults.chartColorSteps,
            ),
        )
        // ONE allowance, named, with the reason and the cost — not a relaxed threshold.
        //
        // 白い熊 asked for a blue steps and a red heart rate on 2026-08-23, was shown the
        // measurements, and asked again. With steps holding the blue, no assignment of the remaining
        // series satisfies every gate while the heart rate is red: a search over fifteen hues in
        // every slot found exactly one clean answer, and it made the heart rate AQUA. So this is a
        // chosen cost.
        //
        // The band state was moved from orange to plum to lift this pair as far as it will go. It
        // reaches ΔE 14.9 against the red, a tenth under the threshold. Everything else here passes.
        val allowed = setOf("hr / state")
        val unexplained = report.findings
            .filter { it.verdict == PaletteCheck.Verdict.FAIL }
            .filterNot { f -> allowed.any { f.detail.startsWith(it) } }
        assertEquals(unexplained.toString(), emptyList<Any>(), unexplained)
    }

    @Test
    fun `the shipped sleep stages pass every check`() {
        val report = PaletteCheck.validate(
            listOf(
                "deep" to defaults.chartColorSleepDeep,
                "light" to defaults.chartColorSleepLight,
                "rem" to defaults.chartColorSleepRem,
                "awake" to defaults.chartColorSleepAwake,
            ),
        )
        assertEquals(report.failures.toString(), PaletteCheck.Verdict.PASS, report.verdict)
    }

    @Test
    fun `the shipped blood-pressure pair passes`() {
        val report = PaletteCheck.validate(
            listOf("sys" to defaults.chartColorSystolic, "dia" to defaults.chartColorDiastolic),
        )
        assertEquals(report.failures.toString(), PaletteCheck.Verdict.PASS, report.verdict)
    }

    /**
     * The documented rejection, reproduced.
     *
     * Hume's violet REM beside a blue stage is the palette anyone would reach for first, and it is
     * unreadable to a red-green reader. A validator that let this through would be decoration.
     */
    @Test
    fun `Hume's violet REM beside blue is rejected, which is why the check exists`() {
        val humeViolet = 0xFF9085E9.toInt()
        val blue = 0xFF3987E5.toInt()
        val cvd = PaletteCheck.cvdSeparation(humeViolet, blue)
        assertTrue("expected a tiny CVD separation, measured $cvd", cvd < 6.0)

        val report = PaletteCheck.validate(listOf("REM" to humeViolet, "deep" to blue))
        assertEquals(PaletteCheck.Verdict.FAIL, report.verdict)
        assertTrue(report.failures.any { it.check == "色覚" })
    }

    /** Two colours a full-colour reader cannot separate fail even if a CVD sim pulls them apart. */
    @Test
    fun `near-identical colours fail the normal-vision gate`() {
        val report = PaletteCheck.validate(
            listOf("a" to 0xFF3987E5.toInt(), "b" to 0xFF3B88E4.toInt()),
        )
        assertEquals(PaletteCheck.Verdict.FAIL, report.verdict)
        assertTrue(report.failures.any { it.check == "識別" })
    }

    /** A grey has no hue to carry identity with, so the chroma floor warns rather than passing it. */
    @Test
    fun `a desaturated pick is called out as reading grey`() {
        val report = PaletteCheck.validate(
            listOf("a" to 0xFF8A8A85.toInt(), "b" to 0xFFD95926.toInt()),
        )
        assertTrue(report.findings.any { it.check == "彩度" && it.verdict != PaletteCheck.Verdict.PASS })
    }

    /** A mark too dark to see against the card is a contrast problem, not a taste problem. */
    @Test
    fun `a near-black series warns on contrast`() {
        val report = PaletteCheck.validate(
            listOf("a" to 0xFF141414.toInt(), "b" to 0xFFD95926.toInt()),
        )
        assertTrue(
            report.findings.any { it.check == "コントラスト" && it.verdict != PaletteCheck.Verdict.PASS },
        )
    }

    /** Sanity on the conversion itself: white and black are the extremes of the WCAG range. */
    @Test
    fun `contrast arithmetic matches WCAG at the extremes`() {
        val ratio = PaletteCheck.contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        assertEquals(21.0, ratio, 0.01)
    }

    /** OKLab lightness is monotone in the grey ramp — the check the lightness band rests on. */
    @Test
    fun `lightness increases along a grey ramp`() {
        val steps = listOf(0xFF101010, 0xFF505050, 0xFF909090, 0xFFE0E0E0).map { it.toInt() }
        val ls = steps.map { PaletteCheck.lightness(it) }
        assertEquals(ls.sorted(), ls)
    }

    /** One entry cannot be compared with anything, so the report is empty rather than falsely green. */
    @Test
    fun `a single colour produces no findings`() {
        assertTrue(PaletteCheck.validate(listOf("only" to 0xFF3987E5.toInt())).findings.isEmpty())
    }
}
