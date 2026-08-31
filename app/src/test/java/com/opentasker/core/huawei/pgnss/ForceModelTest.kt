package com.opentasker.core.huawei.pgnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Every check here is one that has actually caught something, in the Python this is a port of or in
 * the port itself.
 *
 * The force model is the part of the pipeline with no natural feedback: a wrong force produces a
 * perfectly smooth orbit that fits its own arc beautifully and is kilometres out three days later.
 * There is no exception, no `NaN`, no residual that grows — which is why each piece is graded here
 * against something that is **not this file**: a closed form, or the reference implementation that
 * was itself measured against JPL Horizons and against observed orbits.
 *
 * The named traps:
 *
 * * **Polar motion.** Omitting it leaves a spurious Coriolis term of `2 |dw x v| = 8.8e-7 m/s^2`,
 *   seven times solar radiation pressure. [polar_motion_is_worth_more_than_radiation_pressure]
 *   measures that number rather than asserting it exists.
 * * **The Sun and Moon are referred to J2000, not to the equinox of date** — 1341 arcseconds if
 *   treated as of-date. Pinned against the reference implementation's own values.
 * * **`a = -GM r / r^3` and the J2 closed form**, which the Cunningham recursion must reproduce
 *   exactly and does not if any index in it is off by one.
 */
class ForceModelTest {

    private val ref = PythonReference.load()

    private fun field(nmax: Int = 12): GravityField =
        BufferedReader(InputStreamReader(checkNotNull(javaClass.getResourceAsStream("/pgnss/egm96-deg12.gfc")))).use {
            Egm96.parse(it, nmax)
        }

    private fun erp(): EarthOrientation =
        EarthOrientation.parse(
            listOf(BufferedReader(InputStreamReader(checkNotNull(javaClass.getResourceAsStream("/pgnss/code-predicted.erp"))))),
        )

    private fun pointMass(nmax: Int): GravityField {
        val n = (nmax + 1) * (nmax + 2) / 2
        val c = DoubleArray(n)
        c[0] = 1.0
        return GravityField(nmax, c, DoubleArray(n))
    }

    private val samples = listOf(
        doubleArrayOf(2.0e7, 1.0e7, 1.5e7),
        doubleArrayOf(-3.0e7, 5.0e6, -2.0e7),
    )

    @Test
    fun `point-mass gravity reproduces minus GM r over r cubed`() {
        val g = GeoPotential(12)
        val f = pointMass(12)
        val out = DoubleArray(3)
        for (r in samples) {
            g.accel(r[0], r[1], r[2], f.c, f.s, out, 0)
            val rn = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
            val scale = PgnssConstants.GM_EARTH / (rn * rn)
            for (i in 0..2) {
                val want = -PgnssConstants.GM_EARTH * r[i] / (rn * rn * rn)
                assertEquals(0.0, (out[i] - want) / scale, 1e-12)
            }
        }
    }

    @Test
    fun `J2 reproduces its closed form`() {
        val j2 = 1.0826266e-3
        val f = pointMass(12)
        f.c[f.index(2, 0)] = -j2
        val g = GeoPotential(12)
        val out = DoubleArray(3)
        for (r in samples) {
            g.accel(r[0], r[1], r[2], f.c, f.s, out, 0)
            val rr = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
            val k = 1.5 * j2 * PgnssConstants.GM_EARTH * PgnssConstants.RE * PgnssConstants.RE / (rr * rr * rr * rr * rr)
            val zz = r[2] * r[2] / (rr * rr)
            val want = doubleArrayOf(
                -PgnssConstants.GM_EARTH * r[0] / (rr * rr * rr) + k * r[0] * (5 * zz - 1),
                -PgnssConstants.GM_EARTH * r[1] / (rr * rr * rr) + k * r[1] * (5 * zz - 1),
                -PgnssConstants.GM_EARTH * r[2] / (rr * rr * rr) + k * r[2] * (5 * zz - 3),
            )
            val scale = want.maxOf { abs(it) }
            for (i in 0..2) assertEquals(0.0, (out[i] - want[i]) / scale, 1e-12)
        }
    }

    @Test
    fun `the Sun is over Greenwich at noon UTC`() {
        // Not a tautology: it is the one statement about the solar ephemeris that can be checked
        // without a second ephemeris, and it fails outright if GMST or the obliquity is wrong.
        val t = PythonReference.gpsSeconds(2026, 8, 30, 12, 0) + PgnssConstants.LEAP
        val s = DoubleArray(3)
        Ephemeris.sunEcef(t, s)
        val lon = Math.toDegrees(atan2(s[1], s[0]))
        assertTrue("sub-solar longitude $lon deg", abs(lon) < 1.0)
    }

    @Test
    fun `Sun and Moon agree with the reference implementation`() {
        // The reference was measured against JPL Horizons: better than 120 arcsec for the Sun and
        // 300 arcsec for the Moon over the days this is used on. Agreeing with it to a metre is the
        // statement that the J2000-versus-of-date correction landed on the right one of the two.
        val s = DoubleArray(3)
        val m = DoubleArray(3)
        for ((t, want) in ref.sun) {
            Ephemeris.sunEcef(t, s)
            assertEquals("sun at $t", 0.0, dist(s, want), 1e-3)
        }
        for ((t, want) in ref.moon) {
            Ephemeris.moonEcef(t, m)
            assertEquals("moon at $t", 0.0, dist(m, want), 1e-3)
        }
    }

    @Test
    fun `the whole acceleration agrees with the reference implementation`() {
        val fm = ForceModel(field(), 12, frame = erp())
        val out = DoubleArray(3)
        for ((key, want) in ref.accel) {
            val (t, i) = key
            fm.accel(t, ref.states[i], 1, ref.srp, 3, out)
            val scale = want.maxOf { abs(it) }
            for (c in 0..2) {
                assertEquals("accel[$c] at t=$t state=$i", 0.0, (out[c] - want[c]) / scale, 1e-12)
            }
        }
    }

    @Test
    fun `the Earth-orientation file gives the pole and the rate the reference reads`() {
        val fr = erp()
        assertTrue(fr.available())
        val p = DoubleArray(2)
        for ((t, want) in ref.pole) {
            fr.pole(t, p)
            assertEquals("xp at $t", want[0], p[0], 1e-15)
            assertEquals("yp at $t", want[1], p[1], 1e-15)
        }
        for ((t, want) in ref.omega) {
            assertEquals("omega at $t", want, fr.omega(t), 1e-20)
        }
    }

    @Test
    fun `covers refuses a window the file does not span`() {
        // The interpolation CLAMPS outside the series rather than failing, so a window that runs
        // past the file silently gets yesterday's pole held constant. Asking first is the only
        // defence, and this is the check that the asking works.
        val fr = erp()
        val span = fr.span()
        assertNotNull(span)
        val (lo, hi) = span!!
        val toGps = { mjd: Double -> (mjd - (PgnssConstants.GPS_EPOCH_JD - 2400000.5)) * 86400.0 + PgnssConstants.LEAP }
        assertTrue(fr.covers(toGps(lo + 0.5), toGps(hi - 0.5)))
        assertFalse(fr.covers(toGps(lo - 0.5), toGps(hi - 0.5)))
        assertFalse(fr.covers(toGps(lo + 0.5), toGps(hi + 0.5)))
        assertFalse(EarthOrientation.none().covers(0.0, 1.0))
    }

    @Test
    fun `toTirs and toItrs invert each other`() {
        val fr = erp()
        val t = ref.sun.first().first
        val r = doubleArrayOf(1.5e7, -2.0e7, 1.2e7)
        val a = DoubleArray(3)
        val b = DoubleArray(3)
        fr.toTirs(r, t, a)
        fr.toItrs(a, t, b)
        // First-order rotations, so the round trip leaves O(xp^2 r) — a tenth of a millimetre at
        // MEO, against the 30 m the pole itself moves the point.
        assertEquals(0.0, dist(r, b), 1e-3)
        assertTrue("the pole actually moves the point", dist(r, a) > 10.0)
    }

    @Test
    fun `polar motion is worth more than radiation pressure`() {
        // The measurement, not the assertion. Rotating the frame by the pole changes the direction
        // of the rotation axis by |w| * theta; against a satellite at 3.07 km/s the Coriolis term
        // that difference leaves is 2 |dw x v|. Radiation pressure on these craft is about
        // 1.3e-7 m/s^2, and this must come out several times larger — that is the whole reason
        // EarthOrientation exists.
        val fr = erp()
        val p = DoubleArray(2)
        fr.pole(ref.sun.first().first, p)
        val theta = sqrt(p[0] * p[0] + p[1] * p[1])
        val spurious = 2.0 * PgnssConstants.OMEGA_ERA * theta * 3070.0
        assertTrue("pole is $theta rad", theta > 1e-6)
        assertTrue("spurious Coriolis $spurious m/s^2", spurious > 5e-7)
        assertTrue("and that is more than SRP", spurious > 3 * 1.3e-7)
    }

    @Test
    fun `the argument of latitude survives a geostationary satellite`() {
        // The obvious formula divides by sin(inclination). A BeiDou geostationary satellite flies at
        // a fraction of a degree, so that divisor goes to zero, u becomes noise, and the
        // once-per-revolution coefficients fit the noise — one of the four came out of the arc fit
        // 68.8 km wrong that way. The node-projection form must stay finite and must still advance
        // by roughly the true anomaly.
        val fm = ForceModel(field(), 12, frame = erp())
        // A synthetic Sun well out of the orbit plane, so the D axis is never parallel to the radial
        // one and the Y axis never degenerates for a reason that has nothing to do with inclination.
        val sun = doubleArrayOf(PgnssConstants.AU, 0.0, 0.5 * PgnssConstants.AU)
        val eD = DoubleArray(3)
        val eY = DoubleArray(3)
        val eB = DoubleArray(3)
        val a = 4.2164e7
        val v = sqrt(PgnssConstants.GM_EARTH / a)
        // Exactly equatorial, then a thousandth of a degree: the first has no node at all, the
        // second has one made entirely of round-off.
        for (incDeg in listOf(0.0, 1e-3)) {
            val inc = Math.toRadians(incDeg)
            var previous = Double.NaN
            var advanced = 0
            for (deg in 0 until 360 step 15) {
                val th = Math.toRadians(deg.toDouble())
                val r = doubleArrayOf(a * Math.cos(th), a * Math.sin(th) * Math.cos(inc), a * Math.sin(th) * Math.sin(inc))
                val vel = doubleArrayOf(-v * Math.sin(th), v * Math.cos(th) * Math.cos(inc), v * Math.cos(th) * Math.sin(inc))
                val u = fm.dybFrame(r[0], r[1], r[2], vel, sun, eD, eY, eB)
                assertTrue("u must be finite at $deg deg, i=$incDeg", u.isFinite())
                assertEquals("eD is a unit vector", 1.0, norm(eD), 1e-12)
                assertEquals("eY is a unit vector", 1.0, norm(eY), 1e-12)
                assertEquals("eB is a unit vector", 1.0, norm(eB), 1e-12)
                if (!previous.isNaN()) {
                    var d = u - previous
                    while (d < -Math.PI) d += 2 * Math.PI
                    while (d > Math.PI) d -= 2 * Math.PI
                    assertEquals("u advances with the satellite, i=$incDeg", Math.toRadians(15.0), d, 1e-6)
                    advanced++
                }
                previous = u
            }
            assertEquals(23, advanced)
        }
    }

    @Test
    fun `the shadow is one in sunlight, zero in umbra and continuous through the penumbra`() {
        val fm = ForceModel(field(), 12)
        val sun = doubleArrayOf(PgnssConstants.AU, 0.0, 0.0)
        assertEquals(1.0, fm.shadow(2.6e7, 0.0, 0.0, sun), 0.0)          // sub-solar: full sunlight
        assertEquals(0.0, fm.shadow(-2.6e7, 0.0, 0.0, sun), 0.0)         // dead behind the Earth
        // Walk out of the umbra: monotone, and it crosses the middle rather than stepping.
        var previous = 0.0
        var sawPartial = false
        var y = 0.0
        while (y < 1.2e7) {
            val nu = fm.shadow(-2.6e7, y, 0.0, sun)
            assertTrue("shadow must not decrease as we leave the umbra", nu >= previous - 1e-12)
            if (nu > 0.02 && nu < 0.98) sawPartial = true
            previous = nu
            y += 2.0e4
        }
        assertTrue("there must be a penumbra", sawPartial)
        assertEquals(1.0, previous, 0.0)
    }

    private fun dist(a: DoubleArray, b: DoubleArray): Double =
        sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

    private fun norm(a: DoubleArray): Double = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
}
