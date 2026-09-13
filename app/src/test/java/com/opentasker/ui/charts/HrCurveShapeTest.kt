package com.opentasker.ui.charts

import com.opentasker.ui.charts.HrCurveShape.nadirIsMeaningful
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The commentary under the night's heart-rate chart.
 *
 * Both fixtures are real: 白い熊's nights of 2026-09-11 and 2026-09-12, twelve bin medians each,
 * straight out of the exported archive. A describer tested only on invented curves would be tested
 * on curves chosen to suit it.
 */
class HrCurveShapeTest {

    /** 2026-09-11 — descends through the night and is still at its floor by morning. Swing 13.5. */
    private val descending =
        listOf(74.0, 78.5, 76.0, 76.5, 76.0, 72.0, 71.0, 71.0, 75.0, 67.0, 68.0, 65.0)

    /** 2026-09-12 — the flattest night on record. Swing 6.0. */
    private val flat =
        listOf(64.0, 70.0, 69.0, 66.0, 65.0, 64.0, 68.0, 64.0, 68.0, 64.0, 65.5, 68.5)

    @Test
    fun `a descending night is described by where its low fell`() {
        val s = HrCurveShape.describe(descending)!!
        assertEquals(HrCurveShape.Nadir.LATE, s.nadir)
        assertEquals(13.5, s.swingBpm, 1e-9)
        assertTrue(s.nadirIsMeaningful())
    }

    /** It ends ON its floor, so it is still falling rather than climbing toward waking. */
    @Test
    fun `a night that ends at its lowest is not described as rising`() {
        assertEquals(HrCurveShape.Ending.STILL_FALLING, HrCurveShape.describe(descending)!!.ending)
    }

    /**
     * The flat night is reported AS flat, and its nadir position is withheld.
     *
     * The case that moved [HrCurveShape.FLAT_BPM] from 4 to 8: at 4 this curve produced "lowest
     * early in the night", which is a claim about where a 6 bpm wobble happened to bottom out.
     */
    @Test
    fun `the flattest night reports its flatness, not a nadir`() {
        val s = HrCurveShape.describe(flat)!!
        assertEquals(6.0, s.swingBpm, 1e-9)
        assertFalse("a 6 bpm curve has no meaningful low point", s.nadirIsMeaningful())
    }

    @Test
    fun `a night with an early low is called early`() {
        val v = listOf(80.0, 62.0, 64.0, 66.0, 68.0, 70.0, 71.0, 72.0, 73.0, 74.0, 75.0, 76.0)
        val s = HrCurveShape.describe(v)!!
        assertEquals(HrCurveShape.Nadir.EARLY, s.nadir)
        assertEquals(HrCurveShape.Ending.RISING, s.ending)
    }

    @Test
    fun `a night with a middle low is called middle`() {
        val v = listOf(80.0, 78.0, 74.0, 70.0, 66.0, 62.0, 66.0, 70.0, 72.0, 74.0, 76.0, 78.0)
        assertEquals(HrCurveShape.Nadir.MIDDLE, HrCurveShape.describe(v)!!.nadir)
    }

    /**
     * The fraction uses bin CENTRES.
     *
     * A low in the first bin is a low in the first twelfth of the night, not at the instant the
     * night began — reporting 0.0 would put it at bed time, which is a place the curve does not
     * have a sample for.
     */
    @Test
    fun `the nadir fraction is never zero`() {
        val v = listOf(60.0, 70.0, 71.0, 72.0, 73.0, 74.0, 75.0, 76.0, 77.0, 78.0, 79.0, 80.0)
        val s = HrCurveShape.describe(v)!!
        assertTrue("fraction ${s.nadirFraction}", s.nadirFraction > 0.0)
        assertEquals(0.5 / 12, s.nadirFraction, 1e-9)
    }

    /** Too few points to describe is null, not a shrug — see the KDoc. */
    @Test
    fun `a stub of a curve is not described`() {
        assertNull(HrCurveShape.describe(listOf(70.0, 65.0)))
        assertNull(HrCurveShape.describe(listOf(70.0, 68.0, 66.0, 64.0, 62.0)))
        assertNull(HrCurveShape.describe(emptyList()))
    }

    /** A perfectly constant night has no swing and no shape, and must not divide by its own zero. */
    @Test
    fun `a constant night is handled`() {
        val s = HrCurveShape.describe(List(12) { 70.0 })!!
        assertEquals(0.0, s.swingBpm, 1e-9)
        assertFalse(s.nadirIsMeaningful())
        assertEquals(HrCurveShape.Ending.FLAT_AT_THE_BOTTOM, s.ending)
    }
}
