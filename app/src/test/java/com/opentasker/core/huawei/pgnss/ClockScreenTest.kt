package com.opentasker.core.huawei.pgnss

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The clock screen, written from the failure it exists to stop.
 *
 * On 2026-09-14 白い熊's band took three minutes to fix on a freshly built set. Every instrument the
 * project owned said the set was perfect — the builder's own residual, and `pgnss-grade.py`
 * decoding the shipped bytes at 0.03 m median — because all of them grade ORBITS. A range is orbit
 * plus clock, and BeiDou C10 shipped with a clock line fitted at **−5.2e-10 s/s** against a true
 * drift of **+3.5e-12**: 1.1 km of range error in every block past the splice, and a 14.5 km step
 * where the extrapolation met the product's own clock.
 *
 * Nothing in the fit complains about that. `linearFit` returns the best line through whatever it is
 * given, and the 3σ pass cannot remove a TURN — half the points sit either side of it, so each half
 * looks like the outliers of the other. The only signal is that the line MISSES, and until this
 * existed nobody asked it whether it did.
 */
class ClockScreenTest {

    /** A satellite clock: an offset, a steady drift, and a little noise. */
    private fun steady(n: Int = 300, step: Double = 300.0, drift: Double = 3.5e-12): Sp3.Arc {
        val t = DoubleArray(n) { it * step }
        val clock = DoubleArray(n) { -8.88e-4 + drift * t[it] + 1e-11 * kotlin.math.sin(it * 0.7) }
        return Sp3.Arc(t, DoubleArray(3 * n), clock)
    }

    /** The same clock, with its drift turning a third of the way in — C10's shape. */
    private fun turning(n: Int = 300, step: Double = 300.0): Sp3.Arc {
        val t = DoubleArray(n) { it * step }
        val turn = t[n / 3]
        val clock = DoubleArray(n) {
            val x = t[it]
            -8.88e-4 + if (x <= turn) 3.5e-12 * x else 3.5e-12 * turn - 5.0e-10 * (x - turn)
        }
        return Sp3.Arc(t, DoubleArray(3 * n), clock)
    }

    @Test
    fun `a steady clock fits to well under the screen`() {
        val line = Orbit.clockLine(steady(), hours = 36.0)
        assertNotNull(line)
        line!!
        assertTrue(
            "a steady clock should fit to nanoseconds, got ${line.rmsSeconds} s",
            line.rmsSeconds < 1e-9,
        )
        assertTrue(
            "and recover its own drift, got ${line.slope}",
            abs(line.slope - 3.5e-12) < 1e-13,
        )
        assertTrue(line.rmsSeconds < PgnssBuildConfig().bdsMaxClockRms)
    }

    @Test
    fun `a clock whose drift turns inside the window is caught by its residual`() {
        val line = Orbit.clockLine(turning(), hours = 36.0)
        assertNotNull(line)
        line!!
        // The fitted slope is neither of the two real ones — that is the whole problem, and it is
        // invisible to anything that does not compare the line against the clock.
        assertTrue(
            "the residual must exceed the screen, got ${line.rmsSeconds} s",
            line.rmsSeconds > PgnssBuildConfig().bdsMaxClockRms,
        )
    }

    @Test
    fun `the drift bound rejects what shipped and keeps what the good set carried`() {
        // Measured out of the shipped files: the 2026-08-30 set that fixed in 13-20 s tops out at
        // 3.675e-11, and 2026-09-14 shipped these two.
        assertTrue("the good set's worst must survive", 3.675e-11 < Orbit.MAX_CLOCK_DRIFT)
        assertTrue("C10's tail must be rejected", 5.197e-10 > Orbit.MAX_CLOCK_DRIFT)
        assertTrue("C10's seam block must be rejected", 1.108e-8 > Orbit.MAX_CLOCK_DRIFT)
        // And the bound stated the way it matters: what one block of it does to a range.
        assertTrue(Orbit.driftMetres(Orbit.MAX_CLOCK_DRIFT) in 200.0..230.0)
        assertTrue(Orbit.driftMetres(5.197e-10) > 1000.0)
    }

    @Test
    fun `a clock with too few finite samples is screened rather than guessed`() {
        val t = DoubleArray(6) { it * 300.0 }
        val arc = Sp3.Arc(t, DoubleArray(18), DoubleArray(6) { Double.NaN })
        assertNull(Orbit.clockLine(arc, hours = 36.0))
    }
}
