package com.opentasker.core.huawei.pgnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The propagator, the seed and the luni-solar residual, against the reference's own numbers.
 *
 * Two failures live here and neither of them throws.
 *
 * **The modulo.** `(tk + 302400) % 604800` is a FLOOR modulo in Python and a truncated one in
 * Kotlin. They agree on every positive value and disagree on every negative one, so a port that uses
 * `%` passes every test written around a satellite an hour after its toe and puts the satellite on
 * the far side of its orbit for one an hour before. The same trap sits inside `wrap`, where a
 * saturated Ω0 is 27 000 km from a set whose own fit residual was under a metre.
 *
 * **π.** The ICD's π is 3.1415926535898, not [kotlin.math.PI]. Every angle in every record is a
 * count of a power of two SEMICIRCLES, so it is a scale factor on an integer, not a maths nicety.
 */
class OrbitTest {

    @Test
    fun `the broadcast propagation matches the reference over the whole week`() {
        var kepler = 0
        var bds = 0
        var geo = 0
        val out = DoubleArray(3)
        for (f in PgnssFixtures.rows("propagate.txt")) {
            val toe = f[2].toDouble()
            val t = PgnssFixtures.d(f[3])
            val el = PgnssFixtures.elements(f, 4, toe)
            when (f[0]) {
                "K" -> { Orbit.propagate(el, t, out); kepler++ }
                "B" -> { Orbit.propagateBds(el, t, false, out); bds++ }
                else -> { Orbit.propagateBds(el, t, true, out); geo++ }
            }
            for (k in 0 until 3) {
                assertEquals("case ${f[1]} ${f[0]} toe=$toe t=$t axis $k",
                    PgnssFixtures.d(f[19 + k]), out[k], 1e-6)
            }
        }
        assertEquals(144, kepler)
        assertEquals(144, bds)
        assertEquals(144, geo)
    }

    @Test
    fun `a negative time since toe folds the way the reference folds it`() {
        // The truncated modulo Kotlin gives for free lands 604800 s away from this answer.
        assertEquals(-1.0, Orbit.pyMod(-1.0 + 302400.0, 604800.0) - 302400.0, 0.0)
        assertEquals(-302399.0, Orbit.pyMod(-302399.0 + 302400.0, 604800.0) - 302400.0, 0.0)
        assertEquals(302399.0, Orbit.pyMod(-302401.0 + 302400.0, 604800.0) - 302400.0, 0.0)
        assertEquals(0.5, Orbit.pyMod(-3.5, 4.0), 0.0)
        assertEquals(-0.5, Orbit.pyMod(3.5, -4.0), 0.0)
    }

    @Test
    fun `wrap folds onto the reference's fold, on the ICD's pi`() {
        for (f in PgnssFixtures.rows("scalars.txt")) {
            if (f[0] != "W") continue
            assertEquals(PgnssFixtures.d(f[2]), Orbit.wrap(PgnssFixtures.d(f[1])), 0.0)
        }
        assertTrue("the ICD's pi is not the maths one", Orbit.PI != Math.PI)
        assertEquals(3.1415926535898, Orbit.PI, 0.0)
        // Ten times pi is what a fit actually hands back for omega0, and it must fold, not saturate.
        assertTrue(abs(Orbit.wrap(31.4159)) <= Orbit.PI)
    }

    @Test
    fun `the osculating seed matches the reference for both rotation rates`() {
        var checked = 0
        for (f in PgnssFixtures.rows("seed.txt")) {
            if (f[0] != "E") continue
            val p = DoubleArray(3) { PgnssFixtures.d(f[3 + it]) }
            val v = DoubleArray(3) { PgnssFixtures.d(f[6 + it]) }
            val tow = f[9].toDouble()
            val omegaE = if (f[2] == "GPS") Orbit.OMEGA_E else Orbit.BDS_OMEGA
            val el = Orbit.seedElements(p, v, tow, tow, omegaE)
            for (k in 0 until 15) {
                val want = PgnssFixtures.d(f[10 + k])
                assertEquals("${f[1]} ${f[2]} ${Orbit.ORDER[k]}", want, el.values[k],
                    1e-11 * kotlin.math.max(1.0, abs(want)))
            }
            checked++
        }
        assertEquals(42, checked)
    }

    @Test
    fun `the GEO frame inverse matches the reference`() {
        val g = DoubleArray(3)
        val gv = DoubleArray(3)
        var checked = 0
        for (f in PgnssFixtures.rows("seed.txt")) {
            if (f[0] != "G") continue
            val p = DoubleArray(3) { PgnssFixtures.d(f[2 + it]) }
            val v = DoubleArray(3) { PgnssFixtures.d(f[5 + it]) }
            Orbit.geoFrame(p, v, g, gv)
            for (k in 0 until 3) {
                assertEquals(PgnssFixtures.d(f[8 + k]), g[k], 1e-6)
                assertEquals(PgnssFixtures.d(f[11 + k]), gv[k], 1e-9)
            }
            checked++
        }
        assertEquals(21, checked)
    }

    @Test
    fun `the luni-solar residual is the residual, not the total acceleration`() {
        val a = DoubleArray(3)
        var checked = 0
        for (f in PgnssFixtures.rows("lunisolar.txt")) {
            val p = DoubleArray(3) { PgnssFixtures.d(f[3 + it]) }
            val v = DoubleArray(3) { PgnssFixtures.d(f[6 + it]) }
            val arc = PgnssFixtures.sp3.getValue(f[1])
            val t = PgnssFixtures.d(f[2])
            val pa = DoubleArray(3)
            val va = DoubleArray(3)
            val pb = DoubleArray(3)
            val vb = DoubleArray(3)
            Sp3.stateAt(arc, t + 1.0, pa, va)
            Sp3.stateAt(arc, t - 1.0, pb, vb)
            val total = DoubleArray(3) { (va[it] - vb[it]) / 2.0 }
            Orbit.luniSolar(p, v, total, a)
            for (k in 0 until 3) {
                assertEquals("${f[1]} axis $k", PgnssFixtures.d(f[9 + k]), a[k], 1e-15)
            }
            // Huawei's own files sit around 3 µm/s². The TOTAL is ~0.5 m/s² and overruns the
            // 8-bit 2⁻³⁰ km/s² field by five orders of magnitude.
            val residual = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            val whole = sqrt(total[0] * total[0] + total[1] * total[1] + total[2] * total[2])
            assertTrue("residual $residual", residual < 1e-4)
            assertTrue("the total is orders larger: $whole", whole > 100.0 * residual)
            checked++
        }
        assertEquals(27, checked)
    }

    @Test
    fun `the clock fit matches the reference`() {
        for (f in PgnssFixtures.rows("clock.txt")) {
            val arc = PgnssFixtures.sp3.getValue(f[1])
            val got = Orbit.clockFit(arc, f[2].toDouble())
            assertEquals("${f[1]} af0", PgnssFixtures.d(f[3]), got[0], 1e-18)
            assertEquals("${f[1]} af1", PgnssFixtures.d(f[4]), got[1], 1e-22)
        }
    }

    @Test
    fun `a satellite with no published clock gets zeros rather than a NaN into the encoder`() {
        val arc = PgnssFixtures.sp3.getValue("G01")
        val blank = Sp3.Arc(arc.t, arc.p, DoubleArray(arc.size) { Double.NaN })
        val got = Orbit.clockFit(blank, arc.t[50])
        assertEquals(0.0, got[0], 0.0)
        assertEquals(0.0, got[1], 0.0)
    }

    @Test
    fun `the linear clock extrapolation matches the reference, past the end of the arc as well`() {
        var checked = 0
        for (f in PgnssFixtures.rows("clockextrap.txt")) {
            val arc = PgnssFixtures.sp3.getValue(f[1])
            val grid = doubleArrayOf(PgnssFixtures.d(f[2]))
            val want = PgnssFixtures.d(f[3])
            val got = Orbit.clockExtrapolate(arc, grid)[0]
            assertEquals("${f[1]} at ${grid[0]}", want, got, 1e-12 * kotlin.math.max(1e-9, abs(want)))
            checked++
        }
        assertEquals(35, checked)
    }

    @Test
    fun `a clock with too few published samples extrapolates to NaN rather than to a line`() {
        val arc = PgnssFixtures.sp3.getValue("G01")
        val sparse = Sp3.Arc(arc.t, arc.p, DoubleArray(arc.size) {
            if (it < 5) arc.clock[it] else Double.NaN
        })
        val out = Orbit.clockExtrapolate(sparse, doubleArrayOf(arc.t[50]))
        assertTrue("five samples is not enough to extrapolate a clock", out[0].isNaN())
    }

    @Test
    fun `the constellation split reproduces Huawei's own`() {
        // The mini file carries no BeiDou, so this exercises the rule itself: a MEO by semi-major
        // axis, and the two synthetic geosynchronous cases by inclination.
        val arc = PgnssFixtures.sp3.getValue("G01")
        assertEquals("MEO", Orbit.bdsKind(arc, arc.t[50]))

        // A synthetic geosynchronous arc, built from the circular-orbit relations rather than from
        // any number this package produced: a = 42 164 km, so v = sqrt(mu/a) in the inertial frame.
        for ((inclinationDeg, expected) in listOf(0.5 to "GEO", 55.0 to "IGSO")) {
            val a = 42_164_000.0
            val speed = Math.sqrt(Orbit.MU / a)
            val inc = Math.toRadians(inclinationDeg)
            val n = speed / a
            val ts = DoubleArray(21) { arc.t[40] + (it - 10) * 300.0 }
            val p = DoubleArray(3 * ts.size)
            for (i in ts.indices) {
                val th = n * (ts[i] - ts[10])
                // Inertial circle, tilted by the inclination, then turned into the rotating frame.
                val xi = a * Math.cos(th)
                val yi = a * Math.sin(th) * Math.cos(inc)
                val zi = a * Math.sin(th) * Math.sin(inc)
                val g = Orbit.OMEGA_E * (ts[i] - ts[10])
                p[3 * i] = xi * Math.cos(g) + yi * Math.sin(g)
                p[3 * i + 1] = -xi * Math.sin(g) + yi * Math.cos(g)
                p[3 * i + 2] = zi
            }
            val synthetic = Sp3.Arc(ts, p, DoubleArray(ts.size))
            assertEquals("$inclinationDeg deg", expected, Orbit.bdsKind(synthetic, ts[10]))
        }
    }
}
