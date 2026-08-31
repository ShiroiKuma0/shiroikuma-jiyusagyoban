package com.opentasker.ui.charts

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared 1–5 scale: its colours, and what a measured value has to be to land on each step.
 *
 * **1 is the best step since 2026-08-12** — 白い熊 flipped it, and the expectations below flipped with
 * it. The scale itself was settled over five rounds of rendered strips: yellow, emerald, blue, red,
 * dark red, with the anchors chosen by 白い熊 and every candidate measured before it was drawn.
 *
 * These pin the result. A colour edited by hand later has to clear the same gates the search did, and
 * the step numbers have to keep running the way the words on them do.
 */
class RecoveryScaleTest {

    private fun reading(
        marker: RecoveryMarker,
        value: Double?,
        baseline: Double? = 60.0,
        usualHi: Double? = 65.0,
        band: RecoveryBand = RecoveryBand.USUAL,
    ) = MarkerReading(marker, value, baseline, baseline?.let { it - 5 }, usualHi, null, band, true)

    @Test
    fun `the five scale colours pass every measurable check`() {
        val report = PaletteCheck.validate(
            ChartPalette.SCALE.mapIndexed { i, c -> "${i + 1}" to c.toArgb() },
        )
        // 明度 warns by design: 2 and 4 are drawn as single digits in small type, where the extra
        // brightness is what makes them legible. The gates that FAIL are the ones that matter.
        assertTrue(
            report.failures.toString(),
            report.findings.none { it.verdict == PaletteCheck.Verdict.FAIL },
        )
    }

    @Test
    fun `no two scale colours are the same`() {
        assertEquals(5, ChartPalette.SCALE.toSet().size)
    }

    /** The collision 白い熊 reported: an amber value beside the theme's yellow read as one state. */
    @Test
    fun `the scale does not reuse the band-warning amber`() {
        assertTrue(ChartPalette.SCALE.none { it == ChartPalette.BAND_WARN })
        assertNotEquals(ChartPalette.BAND_WARN, ChartPalette.scale(2))
    }

    @Test
    fun `inside the usual range is the neutral middle`() {
        val r = reading(RecoveryMarker.NOCTURNAL_HR, 62.0)
        assertEquals(3, r.scaleStep)
    }

    @Test
    fun `a high nocturnal heart rate is worse and a low one is better`() {
        assertEquals(4, reading(RecoveryMarker.NOCTURNAL_HR, 67.0, band = RecoveryBand.HIGH)!!.scaleStep)
        assertEquals(5, reading(RecoveryMarker.NOCTURNAL_HR, 71.0, band = RecoveryBand.HIGH)!!.scaleStep)
        assertEquals(2, reading(RecoveryMarker.NOCTURNAL_HR, 53.0, band = RecoveryBand.LOW)!!.scaleStep)
        assertEquals(1, reading(RecoveryMarker.NOCTURNAL_HR, 49.0, band = RecoveryBand.LOW)!!.scaleStep)
    }

    /** Sleep runs the other way: short is the bad end, long is not a problem. */
    @Test
    fun `short sleep is the bad end`() {
        val short = MarkerReading(
            RecoveryMarker.SLEEP, 330.0, 480.0, 450.0, 510.0, null, RecoveryBand.LOW, true,
        )
        assertEquals(5, short.scaleStep)
        // 8h45 is one and a half half-widths past usual — outside it, but not at the far step.
        val longish = MarkerReading(
            RecoveryMarker.SLEEP, 525.0, 480.0, 450.0, 510.0, null, RecoveryBand.HIGH, true,
        )
        assertEquals(2, longish.scaleStep)
        // 9h is two, which is where the far step begins.
        val long = MarkerReading(
            RecoveryMarker.SLEEP, 540.0, 480.0, 450.0, 510.0, null, RecoveryBand.HIGH, true,
        )
        assertEquals(1, long.scaleStep)
    }

    /**
     * 体感 moved sides in the 2026-08-12 flip.
     *
     * It is the one marker whose VALUE is itself on the scale, so when the scale inverted, a high
     * rating stopped meaning a good night. Nothing else in the file changes direction, and a test that
     * did not exist before is the only thing that would have caught it staying put.
     */
    @Test
    fun `a high felt rating is now the bad end`() {
        val bad = MarkerReading(RecoveryMarker.FELT, 5.0, 3.0, 2.0, 4.0, null, RecoveryBand.HIGH, true)
        assertEquals(5, bad.scaleStep)
        assertTrue("a 5 is the worst night, so it must count", bad.adverse)
        val good = MarkerReading(RecoveryMarker.FELT, 1.0, 3.0, 2.0, 4.0, null, RecoveryBand.LOW, true)
        assertEquals(1, good.scaleStep)
        assertTrue("a 1 is the best night, so it must not", !good.adverse)
    }

    /**
     * A cool night is unremarkable, not good. Temperature is banded one-sided because only elevation
     * is meaningful at the wrist, so the scale must not assert a benefit the measurement cannot show.
     */
    @Test
    fun `a cool night is never graded as good`() {
        val cool = MarkerReading(
            RecoveryMarker.TEMPERATURE, 35.4, 36.4, 36.1, 36.7, null, RecoveryBand.LOW, false,
        )
        assertEquals(3, cool.scaleStep)
        val warm = MarkerReading(
            RecoveryMarker.TEMPERATURE, 37.4, 36.4, 36.1, 36.7, null, RecoveryBand.HIGH, false,
        )
        assertEquals(5, warm.scaleStep)
    }

    @Test
    fun `a value with no baseline behind it is not graded at all`() {
        assertNull(reading(RecoveryMarker.NOCTURNAL_HR, 62.0, baseline = null).scaleStep)
        assertNull(reading(RecoveryMarker.NOCTURNAL_HR, null).scaleStep)
        assertNull(
            reading(RecoveryMarker.NOCTURNAL_HR, 62.0, band = RecoveryBand.UNKNOWN).scaleStep,
        )
    }

    @Test
    fun `dates carry the year and the weekday`() {
        assertEquals("2026-08-10 (Mon)", nightDateFull(20260810L, BandLanguage.EN))
        assertEquals("2026-08-10（月）", nightDateFull(20260810L, BandLanguage.JA))
        assertEquals("2026-08-09 (Sun)", nightDateFull(20260809L, BandLanguage.EN))
    }
}
