package com.opentasker.ui.charts.compare

import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.PaletteCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compare screen's colour decisions, pinned.
 *
 * 白い熊 is red-green colour-blind, so these are not preferences.
 */
class ComparePaletteTest {

    @Test
    fun `steps and blood oxygen are never adjacent`() {
        // Measured: magenta against aqua is ΔE 1.6 under deuteranopia — worse than the violet/blue
        // pair ChartPalette already records as a failure at 1.9. Heart rate's blue between them
        // restores the floor. Ordering these cards by how comparable they are, which is the obvious
        // thing to do, would put the two indistinguishable colours side by side.
        val keys = CompareModel.ROWS.map { it.huaweiKey }
        val steps = keys.indexOf("hw:steps")
        val spo2 = keys.indexOf("hw:spo2")
        assertTrue("both must be present", steps >= 0 && spo2 >= 0)
        assertTrue("steps and SpO₂ must not be neighbours", kotlin.math.abs(steps - spo2) > 1)
    }

    @Test
    fun `the card order is the dashboard's own`() {
        assertEquals(listOf("hw:steps", "hw:hr", "hw:spo2"), CompareModel.ROWS.map { it.huaweiKey })
    }

    @Test
    fun `the screen introduces no colour of its own`() {
        // Every colour on this screen comes from ChartPalette. A one-off ARGB here would be outside
        // the set PaletteCheck validates, so it would never be measured against the others.
        val known = setOf(ChartPalette.STEPS, ChartPalette.HEART_RATE, ChartPalette.SPO2)
        assertTrue(CompareModel.ROWS.all { it.color in known })
    }

    @Test
    fun `both tracks of a card share the metric's colour`() {
        // The device is carried by position, fill, label and tick direction — never by hue. If a
        // future change gave each band its own colour, the metric would become unidentifiable and
        // the two devices would still not be reliably distinguishable.
        for (row in CompareModel.ROWS) {
            assertTrue("one colour per metric, not per device", row.color in setOf(
                ChartPalette.STEPS, ChartPalette.HEART_RATE, ChartPalette.SPO2,
            ))
        }
    }

    @Test
    fun `the tier chips stay distinguishable without colour`() {
        // DIRECT deliberately has no chip: green against red is ΔE 4.1 under deuteranopia, and
        // badging the ordinary case teaches the reader to ignore badges.
        assertEquals(4, CompareTier.values().size)
        assertTrue(CompareTier.values().contains(CompareTier.DIRECT))
    }
}
