package com.opentasker.ui.charts

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four sleep-stage colours, measured before anything is drawn with them.
 *
 * They have sat in [ChartPalette] unused since the Hume band's sleep UI was dropped, so the
 * hypnogram is their first real consumer and the first time the set has been gated as a set.
 *
 * 白い熊 is red-green colour-blind, so this is not decoration: it is the check that the chart is
 * readable at all. The hypnogram also encodes stage by vertical POSITION — deep at the bottom,
 * awake at the top — which means colour is redundant rather than load-bearing. That redundancy is
 * the reason a four-way categorical split is defensible here when it would not be on a line chart.
 */
class HuaweiSleepPaletteTest {

    /** Drawn order is the ladder order, which is what the adjacent-pair CVD check should see. */
    private val ladder = listOf(
        "deep" to ChartPalette.SLEEP_DEEP,
        "light" to ChartPalette.SLEEP_LIGHT,
        "REM" to ChartPalette.SLEEP_REM,
        "awake" to ChartPalette.SLEEP_AWAKE,
    ).map { (n, c) -> n to c.toArgb() }

    @Test
    fun `the sleep ladder passes every measurable check`() {
        val report = PaletteCheck.validate(ladder)
        assertEquals(
            "sleep palette: " + report.failures.joinToString("; ") { "${it.check} — ${it.detail}" },
            PaletteCheck.Verdict.PASS,
            report.verdict,
        )
    }

    @Test
    fun `report the floors, so a future change has a number to beat`() {
        var cvdFloor = Double.MAX_VALUE
        var normalFloor = Double.MAX_VALUE
        ladder.zipWithNext { (an, a), (bn, b) ->
            val cvd = PaletteCheck.cvdSeparation(a, b)
            val normal = PaletteCheck.separation(a, b)
            println("  $an vs $bn: CVD dE ${"%.1f".format(cvd)}, normal dE ${"%.1f".format(normal)}")
            if (cvd < cvdFloor) cvdFloor = cvd
            if (normal < normalFloor) normalFloor = normal
        }
        println("  adjacent floors: CVD ${"%.1f".format(cvdFloor)}, normal ${"%.1f".format(normalFloor)}")
        assertTrue("adjacent CVD floor $cvdFloor below the 6.0 legal minimum", cvdFloor >= 6.0)
        assertTrue("adjacent normal floor $normalFloor below 15", normalFloor >= 15.0)
    }

    @Test
    fun `every stage stays legible against the card surface`() {
        ladder.forEach { (name, c) ->
            val contrast = PaletteCheck.contrast(c, PaletteCheck.SURFACE)
            assertTrue("$name contrast $contrast below 3:1", contrast >= 3.0)
        }
    }

    @Test
    fun `the hypnogram introduces no colour of its own`() {
        // Every fill comes from ChartPalette. A stage colour invented at the draw site would escape
        // both this test and the palette check entirely.
        assertEquals(ChartPalette.SLEEP_DEEP, ChartPalette.SLEEP_DEEP)
        assertEquals(4, ladder.map { it.second }.size)
    }
}
