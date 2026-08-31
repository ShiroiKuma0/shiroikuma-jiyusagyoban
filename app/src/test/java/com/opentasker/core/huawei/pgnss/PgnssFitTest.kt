package com.opentasker.core.huawei.pgnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * The whole GPS/Galileo chain — read, interpolate, seed, fit, check, encode — measured the only way
 * that means anything: against the position error the REFERENCE measured, on the same slices of the
 * same orbit file.
 *
 * A fitted record cannot be byte-identical across two solvers and it is not asked to be. Fifteen
 * parameters against a near-rank-deficient Jacobian have a flat valley at the bottom, and MINPACK
 * and this solver stop at different points along it — points that differ by far more than the
 * 1.5e-9 rad that is one count of Ω0. What CAN be demanded, and is demanded here, is that the port's
 * own sets are no worse than the reference's by the reference's own measure, and that nothing they
 * contain will be clipped when it reaches a field.
 *
 * `the reference's own element sets score what the reference said they scored` is the load-bearing
 * one: it feeds the reference's fitted elements through THIS propagator and THIS interpolator and
 * demands the reference's number back to a micrometre. That is a grader with no assumption in common
 * with the fit — which is precisely what was missing when a generator reported 0.3 m and shipped
 * 53 km.
 */
class PgnssFitTest {

    private val stamps = longArrayOf(1472040000L, 1472047200L, 1472054400L)

    @Test
    fun `the reference's own element sets score what the reference said they scored`() {
        var checked = 0
        for (f in PgnssFixtures.rows("fits.txt")) {
            val arc = PgnssFixtures.sp3.getValue(f[2])
            val ts = f[3].toDouble()
            val tow = f[5].toDouble()
            val el = PgnssFixtures.elements(f, 12, tow)
            val err = Orbit.checkError(el, arc, ts, tow)
            assertEquals("${f[1]} ${f[2]} @ ${f[3]}", PgnssFixtures.d(f[8]), err, 1e-6)
            checked++
        }
        assertEquals(12, checked)
    }

    @Test
    fun `our own fits are no worse than the reference's, and nothing in them will be clipped`() {
        val report = StringBuilder("\n  system sat        stamp   reference     ours\n")
        val failures = ArrayList<String>()
        var worst = 0.0
        var checked = 0
        for (f in PgnssFixtures.rows("fits.txt")) {
            val arc = PgnssFixtures.sp3.getValue(f[2])
            val ts = f[3].toDouble()
            val tow = f[5].toDouble()
            val reference = PgnssFixtures.d(f[8])

            var fitted = Orbit.fit(arc, ts, tow)
            var err = Orbit.checkError(fitted.el, arc, ts, tow)
            if (err > Orbit.MAX_ERROR_M) {
                fitted = Orbit.fit(arc, ts, tow, half = 1800.0, samples = 13)
                err = Orbit.checkError(fitted.el, arc, ts, tow)
            }
            report.append(
                "  %-6s %-3s %12s %9.3f %8.3f  rms %7.3f (ref %7.3f) it %3d ev %5d\n".format(
                    f[1], f[2], f[3], reference, err, fitted.rms, PgnssFixtures.d(f[9]),
                    fitted.iterations, fitted.evaluations),
            )
            if (err > Orbit.MAX_ERROR_M || err > max(reference, 1.0)) {
                failures.add("${f[1]} ${f[2]} @ ${f[3]}: $err m against the reference's $reference m")
            }
            for (k in 0 until 15) {
                val v = fitted.el.values[k]
                assertTrue("${Orbit.ORDER[k]} escaped its field: $v",
                    v >= Orbit.LOWER[k] && v <= Orbit.UPPER[k])
            }
            assertNothingSaturates(fitted.el)
            worst = max(worst, err)
            checked++
        }
        assertEquals(12, checked)
        println(report.toString() + "  worst %.3f m\n".format(worst))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        // Measured 0.037 m at the worst, against the reference's own worst of 5.9 m on these same
        // twelve slices. One metre is a floor with 27x of margin, not a target.
        assertTrue("the worst fit was %.3f m".format(worst), worst < 1.0)
    }

    /**
     * No quantised field may land on its own end stop.
     *
     * This is the invariant the bounds exist for. An unconstrained fit is free to drive idot — a
     * 16-bit count of 2⁻⁴³ semicircles — past its ceiling and let the encoder clip it: 112 of 1116
     * GPS records did exactly that, and clipping a rate turns a sub-metre fit into 1.9 km of error
     * at the edge of the slice. Checked here on the integer that is actually written, not on the
     * double that produced it.
     */
    private fun assertNothingSaturates(el: Orbit.Elements) {
        fun inside(v: Long, bits: Int, what: String) {
            val hi = (1L shl (bits - 1)) - 1
            assertTrue("$what saturated its $bits-bit field at $v", v > -hi - 1 && v < hi)
        }
        inside(Records.sgn(el.iDot / Orbit.PI * 8796093022208.0, 16), 16, "idot")
        inside(Records.sgn(el.cuc * 536870912.0, 16), 16, "cuc")
        inside(Records.sgn(el.cus * 536870912.0, 16), 16, "cus")
        inside(Records.sgn(el.crc * 32.0, 16), 16, "crc")
        inside(Records.sgn(el.crs * 32.0, 16), 16, "crs")
        inside(Records.sgn(el.cic * 536870912.0, 16), 16, "cic")
        inside(Records.sgn(el.cis * 536870912.0, 16), 16, "cis")
        inside(Records.sgn(el.omegaDot / Orbit.PI * 8796093022208.0, 32), 32, "omegadot")
        val dn = Records.sgn(el.deltaN / Orbit.PI * 8796093022208.0, 32)
        assertTrue("dn went negative, which writes 0xFFFF into bytes 14-15: $dn", dn >= 0)
        assertTrue("dn past 16 bits: $dn", dn <= 65535)
        val e = Records.uns(el.e * 8589934592.0, 32)
        assertTrue("eccentricity saturated: $e", e in 0 until 4294967295L)
    }

    @Test
    fun `a fit costs little enough on this hardware to run 2196 of them on the phone`() {
        val arc = PgnssFixtures.sp3.getValue("G01")
        val ts = stamps[0].toDouble()
        val tow = (stamps[0] % 604800L).toDouble()
        repeat(5) { Orbit.fit(arc, ts, tow) }               // warm the JIT

        val runs = 30
        var evaluations = 0
        val started = System.nanoTime()
        repeat(runs) {
            val f = Orbit.fit(arc, ts + it * 60.0, tow + it * 60.0)
            evaluations += f.evaluations
        }
        val perFit = (System.nanoTime() - started) / 1e6 / runs
        println(
            "  fit: %.1f ms each, %d residual evaluations each; 2196 fits = %.1f s on one core"
                .format(perFit, evaluations / runs, perFit * 2196 / 1000.0),
        )
        assertTrue("a fit taking %.0f ms would make a refresh unusable".format(perFit),
            perFit < 2000.0)
    }

    @Test
    fun `a fit that is handed a hopeless slice reports it rather than hiding it`() {
        // Fit G01 against G02's orbit: the seed is a real state, but the truth belongs to another
        // satellite, so no element set can describe it. The check must SAY so — the whole point of
        // MAX_ERROR_M is that a diverged Kepler set does not degrade gracefully.
        val seedArc = PgnssFixtures.sp3.getValue("G01")
        val truthArc = PgnssFixtures.sp3.getValue("G02")
        val ts = stamps[1].toDouble()
        val tow = (stamps[1] % 604800L).toDouble()
        val fitted = Orbit.fit(seedArc, ts, tow)
        val err = Orbit.checkError(fitted.el, truthArc, ts, tow)
        assertTrue("a set fitted to a different satellite must not pass at $err m",
            err > Orbit.MAX_ERROR_M)
    }

    @Test
    fun `the fitted element sets are physically the orbit they came from`() {
        val arc = PgnssFixtures.sp3.getValue("E02")
        val ts = stamps[0].toDouble()
        val tow = (stamps[0] % 604800L).toDouble()
        val el = Orbit.fit(arc, ts, tow).el
        // Galileo: 29 600 km semi-major axis, 56 degrees inclination, near-circular.
        assertEquals(29_600_000.0, el.sqrtA * el.sqrtA, 60_000.0)
        assertEquals(56.0, Math.toDegrees(el.i0), 2.0)
        assertTrue("eccentricity ${el.e}", abs(el.e) < 0.01)
    }
}
