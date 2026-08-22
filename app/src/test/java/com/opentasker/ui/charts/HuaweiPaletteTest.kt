package com.opentasker.ui.charts

import androidx.compose.ui.graphics.toArgb
import com.opentasker.ui.charts.huawei.HuaweiKeys
import com.opentasker.core.band.BandMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second band's colours, and the collision that made them necessary.
 *
 * 白い熊 is red-green colour-blind, so none of this is cosmetic: two series that resolve to one
 * colour are two series that cannot be told apart, and the failure is silent.
 */
class HuaweiPaletteTest {

    private val style = ChartStyle.DEFAULT

    @Test
    fun `the two bands' colliding storage keys resolve to different colours`() {
        // HuaweiSyncEngine stores heart rate as "hr" and blood oxygen as "spo2" — the same strings
        // BandMetric uses. Unprefixed, both devices would silently take the same colour.
        assertEquals(BandMetric.HEART_RATE, HuaweiKeys.storageKey(HuaweiKeys.HEART_RATE))
        assertEquals(BandMetric.SPO2, HuaweiKeys.storageKey(HuaweiKeys.SPO2))
        assertNotEquals(BandMetric.HEART_RATE, HuaweiKeys.HEART_RATE)
        assertNotEquals(BandMetric.SPO2, HuaweiKeys.SPO2)
    }

    @Test
    fun `a Huawei metric shares its Hume counterpart's hue, deliberately`() {
        // Colour carries the METRIC; the device is carried by a channel colour cannot be asked to
        // do. Asserted as intent so it never reads like the collision it used to be.
        assertEquals(style.colorFor(BandMetric.HEART_RATE), style.colorFor(HuaweiKeys.HEART_RATE))
        assertEquals(style.colorFor(BandMetric.SPO2), style.colorFor(HuaweiKeys.SPO2))
        assertEquals(style.colorFor(BandMetric.STEPS_MINUTE), style.colorFor(HuaweiKeys.STEPS))
    }

    @Test
    fun `an undecoded Huawei field reads grey, not a series colour`() {
        // Its raw fields and unknown feature bits are numbers whose meaning we do not know.
        // Borrowing a series colour would dress them as measurements.
        assertEquals(ChartPalette.UNKNOWN, style.colorFor("hw:unknown_10"))
        assertEquals(ChartPalette.UNKNOWN, style.colorFor(HuaweiKeys.CALORIES))
        assertEquals(ChartPalette.UNKNOWN, style.colorFor(HuaweiKeys.DISTANCE))
        assertNotEquals(ChartPalette.BAND_INDEX, style.colorFor("hw:unknown_10"))
    }

    @Test
    fun `an unknown HUME key still falls through to the old default`() {
        assertEquals(ChartPalette.BAND_INDEX, style.colorFor("something_else"))
    }

    @Test
    fun `the Huawei screen clears the adjacent gate`() {
        assertEquals(
            PaletteCheck.Verdict.PASS,
            PaletteCheck.validate(style.huaweiSeriesColors.map { (n, c) -> n to c.toArgb() }).verdict,
        )
    }

    @Test
    fun `heart rate and RESTING heart rate separate, because they are the same quantity`() {
        // The gate PaletteCheck applies is adjacency, and adjacency alone is not enough here.
        //
        // Two cards showing the same quantity are the two a reader will hold against each other
        // whatever order they sit in. Violet was chosen for resting heart rate on exactly the
        // adjacent-pairs reasoning and measures ΔE 1.9 against blue under protanopia — it passes
        // the automated check and is unreadable on the screen.
        //
        // All-pairs is NOT the fix and must not be asserted: 歩数 magenta against 血中酸素 aqua is
        // ΔE 1.6, and that pair already ships in the Hume palette. It is legal because the slot
        // order never places them adjacent and nobody compares steps against blood oxygen. So the
        // rule is adjacency PLUS the pairs that mean the same thing.
        val cvd = PaletteCheck.cvdSeparation(
            style.colorFor(BandMetric.HEART_RATE).toArgb(),
            style.colorFor(HuaweiKeys.RESTING_HR).toArgb(),
        )
        val normal = PaletteCheck.separation(
            style.colorFor(BandMetric.HEART_RATE).toArgb(),
            style.colorFor(HuaweiKeys.RESTING_HR).toArgb(),
        )
        assertTrue("心拍 / 安静時心拍 CVD ΔE $cvd — below the 8.0 target", cvd >= 8.0)
        assertTrue("心拍 / 安静時心拍 normal ΔE $normal — below the 15.0 floor", normal >= 15.0)
    }

    @Test
    fun `violet is NOT used for resting heart rate`() {
        // The rejection, pinned. It is the natural-looking choice and it is unreadable beside blue.
        assertNotEquals(ChartPalette.BAND_INDEX, style.restingHr)
        assertTrue(
            "violet against blue is ΔE ${PaletteCheck.cvdSeparation(
                ChartPalette.BAND_INDEX.toArgb(), ChartPalette.HEART_RATE.toArgb(),
            )} under CVD — kept here so the reason survives",
            PaletteCheck.cvdSeparation(
                ChartPalette.BAND_INDEX.toArgb(), ChartPalette.HEART_RATE.toArgb(),
            ) < 8.0,
        )
    }
}
