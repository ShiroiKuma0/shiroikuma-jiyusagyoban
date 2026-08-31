package com.opentasker.core.huawei.pgnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The integrator is the one component whose errors are completely invisible from inside.
 *
 * A propagator that is slightly wrong produces a smooth, plausible, self-consistent trajectory. The
 * arc fit then fits *that* trajectory, reports a small residual, and the element sets built from it
 * fit it too — so every number the pipeline prints about itself agrees, and the satellite is
 * kilometres from where it is. That is not hypothetical: the generator this replaces graded itself
 * against its own corrupted clock and shipped a set that was 53 692 m out while reporting 0.3 m.
 *
 * So nothing here is graded against this file's own output. The checks are:
 *
 * * **Closed forms** — a Kepler orbit that must close on itself after twenty revolutions, and the
 *   same orbit written down analytically and turned into the rotating frame, which the centrifugal
 *   and Coriolis terms must reproduce over 72 hours.
 * * **The Adams coefficients' defining property**, which catches a mis-transcribed table. They are
 *   derived rather than transcribed here precisely because a table is so easy to get wrong; this
 *   test is what makes the derivation trustworthy.
 * * **The reference implementation**, [PythonReference] — a trajectory integrated by the Python's
 *   adaptive DOP853, which the fixed-step Adams method must land on.
 * * **A fit whose answer is known**, because the pseudo-observations were made by propagating a
 *   state this test chose. Recovering it is a statement about the fit; it says nothing about the
 *   force model, which is why it is not the only test here.
 */
class PropagatorTest {

    private val ref = PythonReference.load()

    private fun field(nmax: Int = 12): GravityField =
        BufferedReader(InputStreamReader(checkNotNull(javaClass.getResourceAsStream("/pgnss/egm96-deg12.gfc")))).use {
            Egm96.parse(it, nmax)
        }

    private fun erp(): EarthOrientation =
        EarthOrientation.parse(
            listOf(BufferedReader(InputStreamReader(checkNotNull(javaClass.getResourceAsStream("/pgnss/code-predicted.erp"))))),
        )

    private fun pointMass(): GravityField {
        val c = DoubleArray(1)
        c[0] = 1.0
        return GravityField(0, c, DoubleArray(1))
    }

    @Test
    fun `adams coefficients integrate polynomials exactly`() {
        // The defining property: with nodes s_j, sum_j b_j s_j^p must equal the integral of s^p over
        // [0, 1] for every p below the order. A coefficient wrong in the ninth digit passes every
        // short smoke test and costs the better part of a metre over a three-day arc, so this is the
        // check that the derivation replaced a transcription with.
        for (order in 1..8) {
            val ab = AdamsCoefficients.bashforth(order)
            val am = AdamsCoefficients.moulton(order)
            assertEquals(order, ab.size)
            assertEquals(order, am.size)
            for (p in 0 until order) {
                var sb = 0.0
                var sm = 0.0
                var mag = 0.0
                for (j in 0 until order) {
                    val tb = ab[j] * if (p == 0) 1.0 else Math.pow(-j.toDouble(), p.toDouble())
                    val tm = am[j] * if (p == 0) 1.0 else Math.pow((1 - j).toDouble(), p.toDouble())
                    sb += tb
                    sm += tm
                    mag = maxOf(mag, abs(tb), abs(tm))
                }
                // The check itself cancels: at order 8 the terms reach 6e4 and sum to 1/6, so the
                // tolerance has to be scaled by the size of what cancelled or it is measuring
                // double arithmetic rather than the coefficients.
                val tol = 1e-14 * mag + 1e-14
                val want = 1.0 / (p + 1)
                assertEquals("Bashforth order $order, power $p", want, sb, tol)
                assertEquals("Moulton order $order, power $p", want, sm, tol)
            }
        }
        // The published tables, to the last digit, as a second and independent statement. They and
        // the derivation were written from different sources; agreeing means both are right. Each
        // set also sums to its own denominator, which is the exactness condition for a constant.
        val abNum = intArrayOf(434241, -1152169, 2183877, -2664477, 2102243, -1041723, 295767, -36799)
        val amNum = intArrayOf(36799, 139849, -121797, 123133, -88547, 41499, -11351, 1375)
        assertEquals(120960, abNum.sum())
        assertEquals(120960, amNum.sum())
        for (j in 0..7) {
            assertEquals("AB8[$j]", abNum[j] / 120960.0, AdamsCoefficients.bashforth(8)[j], 1e-15)
            assertEquals("AM8[$j]", amNum[j] / 120960.0, AdamsCoefficients.moulton(8)[j], 1e-15)
        }
        // And the two everyone knows by heart, as an anchor on the sign convention.
        assertEquals(1.5, AdamsCoefficients.bashforth(2)[0], 1e-15)
        assertEquals(-0.5, AdamsCoefficients.bashforth(2)[1], 1e-15)
        assertEquals(0.5, AdamsCoefficients.moulton(2)[0], 1e-15)
        assertEquals(0.5, AdamsCoefficients.moulton(2)[1], 1e-15)
    }

    @Test
    fun `twenty Kepler revolutions close on themselves`() {
        val f = ForceModel(pointMass(), 0, srp = false, tide = false, relativity = false, third = false, omegaFixed = 0.0)
        val p = Propagator(f)
        val a0 = 2.6561e7
        val v0 = sqrt(PgnssConstants.GM_EARTH / a0)
        val y0 = doubleArrayOf(a0, 0.0, 0.0, 0.0, v0 * cos(0.96), v0 * sin(0.96))
        val period = 2 * PI * sqrt(a0 * a0 * a0 / PgnssConstants.GM_EARTH)
        val got = p.propagateOne(y0, 0.0, doubleArrayOf(20 * period), null, 0)
        val d = sqrt((0..2).sumOf { (got[it] - y0[it]) * (got[it] - y0[it]) })
        assertTrue("closure error ${d * 1e3} mm", d < 0.05)
    }

    @Test
    fun `centrifugal and Coriolis reproduce a turned Kepler orbit over 72 hours`() {
        // The rotating-frame terms have no closed form of their own, but a Kepler orbit written down
        // analytically and then rotated into the turning frame does — and the integrator must land
        // on it. Getting the sign of either term wrong, or writing the rate as the IERS nominal mean
        // instead of d(ERA)/dt, shows up here immediately.
        val w = PgnssConstants.OMEGA_ERA
        val a0 = 2.6561e7
        val inc = Math.toRadians(55.0)
        val ecc = 0.01
        val n = sqrt(PgnssConstants.GM_EARTH / (a0 * a0 * a0))

        fun ecef(t: Double): Pair<DoubleArray, DoubleArray> {
            val m = n * t
            var e = m
            repeat(60) { e -= (e - ecc * sin(e) - m) / (1 - ecc * cos(e)) }
            val nu = 2 * atan2(sqrt(1 + ecc) * sin(e / 2), sqrt(1 - ecc) * cos(e / 2))
            val rr = a0 * (1 - ecc * cos(e))
            val h = sqrt(PgnssConstants.GM_EARTH * a0 * (1 - ecc * ecc))
            fun turn(v: DoubleArray) = doubleArrayOf(v[0], cos(inc) * v[1], sin(inc) * v[1])
            val ri = turn(doubleArrayOf(rr * cos(nu), rr * sin(nu), 0.0))
            val vi = turn(
                doubleArrayOf(
                    -PgnssConstants.GM_EARTH / h * sin(nu),
                    PgnssConstants.GM_EARTH / h * (ecc + cos(nu)),
                    0.0,
                ),
            )
            val th = w * t
            val re = doubleArrayOf(cos(th) * ri[0] + sin(th) * ri[1], -sin(th) * ri[0] + cos(th) * ri[1], ri[2])
            val ve = doubleArrayOf(cos(th) * vi[0] + sin(th) * vi[1], -sin(th) * vi[0] + cos(th) * vi[1], vi[2])
            return re to doubleArrayOf(ve[0] + w * re[1], ve[1] - w * re[0], ve[2])
        }

        val f = ForceModel(pointMass(), 0, srp = false, tide = false, relativity = false, third = false, omegaFixed = w)
        val p = Propagator(f)
        val (r0, v0) = ecef(0.0)
        val got = p.propagateOne(
            doubleArrayOf(r0[0], r0[1], r0[2], v0[0], v0[1], v0[2]),
            0.0,
            doubleArrayOf(72 * 3600.0),
            null,
            0,
        )
        val want = ecef(72 * 3600.0).first
        val d = sqrt((0..2).sumOf { (got[it] - want[it]) * (got[it] - want[it]) })
        assertTrue("72 h error ${d * 1e6} um", d < 1e-3)
    }

    @Test
    fun `the trajectory lands where the reference implementation puts it`() {
        // The reference integrates the same force model with an adaptive DOP853. This one is
        // fixed-step Adams at 30 s. Two different methods on the same equations of motion, forwards
        // and backwards, on and off the step grid.
        //
        // The fixture is the reference at CONVERGED tolerances, not its shipped ones, and the
        // difference is not academic: at its own rtol=1e-11 / atol=1e-4 / max_step=600 the reference
        // lands 0.40 m from its own converged answer at +12 h on this state, all of it the kink in
        // the conical shadow function that an adaptive step chatters across. This method at 30 s
        // lands 5.5 mm from converged, and at 7.5 s 0.13 mm — so the tolerance below is set by the
        // shadow kink and by nothing else, and it shrinks with the step if it ever needs to.
        val p = Propagator(ForceModel(field(), 12, frame = erp()))
        val grid = ref.traj.map { it.first }.toDoubleArray()
        val got = p.propagateOne(ref.trajState, ref.trajEpoch, grid, ref.srp, ref.srp.size)
        var worst = 0.0
        for (i in ref.traj.indices) {
            val want = ref.traj[i].second
            val d = sqrt((0..2).sumOf { (got[3 * i + it] - want[it]) * (got[3 * i + it] - want[it]) })
            worst = maxOf(worst, d)
        }
        assertTrue("worst disagreement with the reference: $worst m", worst < 0.05)
    }

    @Test
    fun `an epoch exactly at the integration epoch is answered from the state itself`() {
        val p = Propagator(ForceModel(field(), 12, frame = erp()))
        val got = p.propagateOne(ref.trajState, ref.trajEpoch, doubleArrayOf(ref.trajEpoch), ref.srp, ref.srp.size)
        for (i in 0..2) assertEquals(ref.trajState[i], got[i], 0.0)
    }

    @Test
    fun `an off-grid epoch costs nothing over the grid point beside it`() {
        // The output epochs the band's grid asks for are 14 s off a multiple of 7200, and no step
        // size lands on both those and the SP3's multiples of 300. An epoch inside a step is reached
        // by a single RK4 excursion; this is the measurement that the excursion is free, made by
        // asking for the same instant twice — once as the end of a whole number of steps, once as a
        // point inside one.
        val p = Propagator(ForceModel(field(), 12, frame = erp()), step = 30.0)
        val q = Propagator(ForceModel(field(), 12, frame = erp()), step = 31.0)
        val t = ref.trajEpoch + 6000.0                      // 200 steps of 30 s, 193.5 steps of 31 s
        val a = p.propagateOne(ref.trajState, ref.trajEpoch, doubleArrayOf(t), ref.srp, ref.srp.size)
        val b = q.propagateOne(ref.trajState, ref.trajEpoch, doubleArrayOf(t), ref.srp, ref.srp.size)
        val d = sqrt((0..2).sumOf { (a[it] - b[it]) * (a[it] - b[it]) })
        assertTrue("grid-aligned versus mid-step: $d m", d < 1e-3)
    }

    @Test
    fun `a batch of states is integrated identically to the same states one at a time`() {
        // Batching exists so the Jacobian's columns share the Sun, the Moon and the tide. If it also
        // changed the answer, every Jacobian column would carry the difference and the fit would be
        // solving a slightly different problem from the one it reports.
        val p = Propagator(ForceModel(field(), 12, frame = erp()))
        val a = ref.trajState.copyOf()
        val b = ref.trajState.copyOf()
        b[0] += 1000.0
        b[4] -= 0.05
        val srp1 = ref.srp
        val srp2 = DoubleArray(ref.srp.size) { ref.srp[it] * 1.3 }
        val grid = doubleArrayOf(ref.trajEpoch + 3600.0, ref.trajEpoch + 12345.0, ref.trajEpoch - 1800.0)
        val batch = p.propagate(a + b, 2, ref.trajEpoch, grid, srp1 + srp2, srp1.size)
        val one = p.propagateOne(a, ref.trajEpoch, grid, srp1, srp1.size)
        val two = p.propagateOne(b, ref.trajEpoch, grid, srp2, srp2.size)
        for (e in grid.indices) {
            for (c in 0..2) {
                assertEquals(one[3 * e + c], batch[3 * (e * 2) + c], 0.0)
                assertEquals(two[3 * e + c], batch[3 * (e * 2 + 1) + c], 0.0)
            }
        }
    }

    @Test
    fun `the fit recovers a state and radiation-pressure coefficients it was not given`() {
        // Pseudo-observations made by propagating a state chosen here, then handed to the fit with a
        // seed kilometres away and the wrong radiation pressure. Recovering the truth to
        // centimetres is a statement about the solve, not about the dynamics — the dynamics are
        // graded in the tests above and against observed orbits in `.scratch/pgnss-kt`.
        val p = Propagator(ForceModel(field(), 12, frame = erp()))
        val t0 = ref.trajEpoch
        val truth = doubleArrayOf(1.5e7, -2.0e7, 1.2e7, 1200.0, 900.0, -2500.0)
        val truthSrp = doubleArrayOf(-9.3e-8, 2.1e-9, -4.4e-9)
        val tObs = DoubleArray(97) { t0 - 24 * 3600.0 + it * 900.0 }
        val pObs = p.propagateOne(truth, t0, tObs, truthSrp, 3)

        val seed = doubleArrayOf(
            truth[0] + 2500.0, truth[1] - 1800.0, truth[2] + 900.0,
            truth[3] + 0.4, truth[4] - 0.3, truth[5] + 0.2,
        )
        val fit = p.fitArc(tObs, pObs, t0, seed, 3, TrustRegionLsq(), 40)
        assertTrue("arc rms ${fit.rms} m", fit.rms < 0.05)
        val s = fit.state()
        for (i in 0..2) assertEquals("position component $i", truth[i], s[i], 0.05)
        for (i in 3..5) assertEquals("velocity component $i", truth[i], s[i], 1e-4)
        val c = fit.srp()
        for (i in 0..2) assertEquals("srp coefficient $i", truthSrp[i], c[i], 2e-11)
    }

    @Test
    fun `the fit pulls a badly seeded state back to the arc`() {
        // The seed the pipeline actually hands over is a state read off a precise orbit one second
        // away from the epoch it is claimed for — three kilometres of along-track error before the
        // solver starts. It must not matter.
        val p = Propagator(ForceModel(field(), 12, frame = erp()))
        val t0 = ref.trajEpoch
        val truth = doubleArrayOf(1.5e7, -2.0e7, 1.2e7, 1200.0, 900.0, -2500.0)
        val tObs = DoubleArray(97) { t0 - 24 * 3600.0 + it * 900.0 }
        val pObs = p.propagateOne(truth, t0, tObs, doubleArrayOf(-1e-7, 0.0, 0.0), 3)
        val seed = doubleArrayOf(truth[0] + 1200.0, truth[1] + 900.0, truth[2] - 2500.0, truth[3], truth[4], truth[5])
        val fit = p.fitArc(tObs, pObs, t0, seed, 3, TrustRegionLsq(), 40)
        assertTrue("arc rms ${fit.rms} m", fit.rms < 0.05)
        assertTrue(abs(fit.state()[0] - truth[0]) < 0.05)
    }
}

/**
 * A Levenberg-Marquardt with Nielsen's gain-ratio damping, on scaled variables — enough of a
 * trust-region method to exercise [Propagator.fitArc] without depending on `LeastSquares.kt`, which
 * another change owns.
 *
 * It is deliberately **not** a fixed-damping LM. That variant converges linearly on an orbit fit and
 * stalls at tens of metres, and the stall looks exactly like a missing force — see the note on
 * [NlsSolver]. If the production solver is substituted here and these tests still pass, it is doing
 * its job.
 */
private class TrustRegionLsq(private val tau: Double = 1e-3, private val maxIter: Int = 60) : NlsSolver {

    override fun solve(
        x0: DoubleArray,
        xScale: DoubleArray,
        residual: (DoubleArray) -> DoubleArray,
        jacobian: (DoubleArray) -> Array<DoubleArray>,
        maxEval: Int,
    ): DoubleArray {
        val n = x0.size
        val x = x0.copyOf()
        var r = residual(x)
        var evals = 1
        var cost = dot(r, r)
        var lambda = -1.0
        var nu = 2.0
        var iter = 0
        while (iter < maxIter && evals < maxEval) {
            iter++
            val j = jacobian(x)
            val a = Array(n) { DoubleArray(n) }
            val g = DoubleArray(n)
            for (row in j.indices) {
                val jr = j[row]
                for (p in 0 until n) {
                    val jp = jr[p] * xScale[p]
                    g[p] += jp * r[row]
                    for (q in p until n) a[p][q] += jp * (jr[q] * xScale[q])
                }
            }
            for (p in 0 until n) for (q in 0 until p) a[p][q] = a[q][p]
            if (lambda < 0) {
                var mx = 0.0
                for (p in 0 until n) mx = maxOf(mx, a[p][p])
                lambda = tau * maxOf(mx, 1e-30)
            }
            var accepted = false
            var inner = 0
            while (inner < 30 && evals < maxEval) {
                inner++
                val aa = Array(n) { p -> DoubleArray(n) { q -> a[p][q] } }
                for (p in 0 until n) aa[p][p] += lambda * maxOf(a[p][p], 1e-30)
                val d = cholesky(aa, DoubleArray(n) { -g[it] })
                if (d == null) {
                    lambda *= nu
                    nu *= 2
                    continue
                }
                val xn = DoubleArray(n) { x[it] + d[it] * xScale[it] }
                val rn = residual(xn)
                evals++
                val costN = dot(rn, rn)
                var denom = 0.0
                for (p in 0 until n) denom += d[p] * (lambda * maxOf(a[p][p], 1e-30) * d[p] - g[p])
                val rho = if (denom > 0) (cost - costN) / denom else -1.0
                if (rho > 0 && costN < cost) {
                    System.arraycopy(xn, 0, x, 0, n)
                    r = rn
                    val rel = (cost - costN) / maxOf(cost, 1e-300)
                    cost = costN
                    val f = 1.0 - (2 * rho - 1).let { it * it * it }
                    lambda *= maxOf(1.0 / 3.0, f)
                    nu = 2.0
                    accepted = true
                    if (rel < 1e-14) return x
                    break
                }
                lambda *= nu
                nu *= 2
                if (lambda > 1e30) return x
            }
            if (!accepted) return x
        }
        return x
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun cholesky(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (k in 0..i) {
                var s = a[i][k]
                for (p in 0 until k) s -= l[i][p] * l[k][p]
                if (i == k) {
                    if (s <= 0) return null
                    l[i][i] = sqrt(s)
                } else {
                    l[i][k] = s / l[k][k]
                }
            }
        }
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var s = b[i]
            for (p in 0 until i) s -= l[i][p] * y[p]
            y[i] = s / l[i][i]
        }
        val out = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = y[i]
            for (p in i + 1 until n) s -= l[p][i] * out[p]
            out[i] = s / l[i][i]
        }
        return out
    }
}
