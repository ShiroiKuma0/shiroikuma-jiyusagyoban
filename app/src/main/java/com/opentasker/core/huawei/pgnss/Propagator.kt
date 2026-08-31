package com.opentasker.core.huawei.pgnss

import kotlin.math.sqrt

/**
 * Adams-Bashforth and Adams-Moulton coefficients, computed **exactly** rather than transcribed.
 *
 * The eighth-order coefficients are the sort of table that gets copied out of a book with one digit
 * wrong and then behaves perfectly on every short test. A single coefficient off by 1e-9 relative is
 * invisible over a hundred steps and worth the better part of a metre over the 8640 steps of a
 * 72-hour arc, because the error accumulates coherently: it multiplies `h * v`, which is 90 km per
 * step of position for a satellite doing 3 km/s.
 *
 * So they are derived here from the definition, in integer arithmetic, and
 * [PropagatorTest.adams_coefficients_integrate_polynomials_exactly] re-checks the defining property.
 *
 *     b_j = (1 / prod_{i != j} (s_j - s_i)) * integral_0^1 prod_{i != j} (s - s_i) ds
 *
 * with nodes `s_j` at 0, -1, ... for Bashforth and 1, 0, -1, ... for Moulton, `s` measured in steps
 * from the current point. Every quantity in that expression is an integer or a rational with a small
 * denominator, so the only rounding in the whole derivation is the final division.
 */
internal object AdamsCoefficients {

    /** Explicit Adams-Bashforth weights for `f_n, f_{n-1}, ... f_{n-order+1}`. */
    fun bashforth(order: Int): DoubleArray = weights(IntArray(order) { -it })

    /** Implicit Adams-Moulton weights for `f_{n+1}, f_n, ... f_{n-order+2}`. */
    fun moulton(order: Int): DoubleArray = weights(IntArray(order) { 1 - it })

    private fun weights(nodes: IntArray): DoubleArray {
        val k = nodes.size
        // One common denominator for the integral of every monomial up to s^(k-1).
        var lcm = 1L
        for (p in 1..k) lcm = lcm / gcd(lcm, p.toLong()) * p
        val out = DoubleArray(k)
        for (j in 0 until k) {
            // poly = prod_{i != j} (s - s_i), integer coefficients, ascending powers.
            val poly = LongArray(k)
            poly[0] = 1L
            var deg = 0
            for (i in 0 until k) {
                if (i == j) continue
                val root = nodes[i].toLong()
                for (p in deg + 1 downTo 1) poly[p] = poly[p - 1] - root * poly[p]
                poly[0] = -root * poly[0]
                deg++
            }
            var num = 0L
            for (p in 0..deg) num += poly[p] * (lcm / (p + 1))
            var den = 1L
            for (i in 0 until k) if (i != j) den *= (nodes[j] - nodes[i]).toLong()
            out[j] = num.toDouble() / (lcm.toDouble() * den.toDouble())
        }
        return out
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
}

/**
 * A least-squares solver, as [Propagator.fitArc] needs one.
 *
 * ## This must be a trust-region method, not a fixed-damping Levenberg-Marquardt
 * Orbit fits have genuinely near-degenerate parameter directions, and a fixed multiple of
 * `diag(J'J)` damps exactly those into immobility. The reference implementation tried a hand-rolled
 * LM with damping 1e-3 first: it converged *linearly*, about a factor of two per iteration, and
 * stalled at 30 m on a 24-hour GPS arc that a trust-region solver takes to 0.2 m. The stall looked so
 * much like a missing force that it cost an afternoon of ablating the force model for a defect that
 * was in the optimiser.
 *
 * The implementation lives in `LeastSquares.kt`. This interface is the seam: anything that solves
 *
 *     minimise  || residual(x) ||^2
 *
 * from [x0], using [jacobian] and honouring [xScale] as the per-parameter step scale, will do.
 */
fun interface NlsSolver {
    /**
     * @param x0 starting parameters.
     * @param xScale characteristic magnitude of each parameter; the solver's trust region should be
     *   measured in these units, because the epoch state is in metres and the radiation-pressure
     *   coefficients are in nanometres per second squared.
     * @param residual the vector to be driven to zero, length m.
     * @param jacobian `d residual / d x`, `m` rows of `n` columns.
     * @param maxEval a budget on [residual] evaluations.
     * @return the fitted parameters.
     */
    fun solve(
        x0: DoubleArray,
        xScale: DoubleArray,
        residual: (DoubleArray) -> DoubleArray,
        jacobian: (DoubleArray) -> Array<DoubleArray>,
        maxEval: Int,
    ): DoubleArray
}

/** What [Propagator.fitArc] hands back: the epoch state, the radiation-pressure coefficients, and how well it fitted. */
class ArcFit(
    /** `6 + nsrp` parameters: position, velocity, then the ECOM coefficients. */
    val x: DoubleArray,
    /** 3D rms of the fit residual against the arc, metres. */
    val rms: Double,
    /** Number of parameters that are radiation-pressure coefficients. */
    val nsrp: Int,
) {
    /** The epoch state alone, `[x, y, z, vx, vy, vz]`. */
    fun state(): DoubleArray = x.copyOfRange(0, 6)

    /** The radiation-pressure coefficients alone. */
    fun srp(): DoubleArray = x.copyOfRange(6, 6 + nsrp)
}

/**
 * Fixed-step numerical propagation in the terrestrial frame, and the arc fit that feeds it.
 *
 * ## Why fixed step, when the reference uses an adaptive DOP853
 * Two reasons, one of them subtle.
 *
 * The plain one is cost. This has to run on a phone. An eighth-order Adams predictor-corrector spends
 * **two** force evaluations per step where a Runge-Kutta of the same order spends eleven or twelve,
 * and at a 30 s step its truncation error on a MEO orbit is microns.
 *
 * The subtle one is the Jacobian. The reference implementation has a warning attached to its fit:
 * every column of the Jacobian must be integrated in the *same batch* as the nominal trajectory, so
 * that they share the integrator's adaptive step sequence — a column differenced against an
 * independently stepped integration carries that integration's own step-control difference, which at
 * these tolerances is the size of the derivative being measured. With a fixed step that hazard does
 * not exist at all: every trajectory takes literally the same steps whether or not it is batched.
 * Batching is kept anyway, because it shares the Sun, the Moon and the tide across the batch, but it
 * is now an optimisation rather than a correctness requirement.
 *
 * ## Output at arbitrary epochs
 * The grid the band wants is stamped 14 s off a multiple of 7200, and the arc is sampled off an SP3
 * whose epochs are multiples of 300 — no single step size lands on both. Rather than interpolate, an
 * epoch that falls inside a step is reached by a single classical RK4 **excursion** of length
 * `t - t_n` from the last grid state, which does not disturb the multistep history. Over at most one
 * 30 s step that excursion's own error is `(h n)^5` relative, about 4e-7 m: three orders of magnitude
 * below the integrator's own noise, and it never accumulates because each excursion starts fresh.
 *
 * Not thread-safe — it owns the [ForceModel] and the integration scratch. One instance per thread.
 */
class Propagator(
    val force: ForceModel,
    /** Grid step, seconds. 30 s is the measured default; see the class note. */
    val step: Double = DEFAULT_STEP,
    /** Order of the Adams predictor-corrector. */
    val order: Int = 8,
) {

    companion object {
        /**
         * 30 s.
         *
         * The eighth-order truncation error at this step is far below a millimetre over three days.
         * What actually sets it is the **shadow function**, which has a kink at penumbra entry and
         * exit that no smooth method integrates cleanly. The error a kink leaves is of order
         * `step * a_srp` — 30 s times 1.3e-7 m/s^2 is 4e-6 m/s, a fraction of a metre of position
         * over the rest of the window, and it shrinks linearly with the step if it ever needs to.
         */
        const val DEFAULT_STEP = 30.0

        /**
         * Sub-steps per grid step while the multistep method is starting up.
         *
         * The starter's error enters the whole trajectory, so it is bought down rather than
         * tolerated: RK4 at `step/8` over the seven starter steps contributes about 5e-8 m.
         */
        const val STARTER_SUBSTEPS = 8

        /**
         * **Three**, which fits the arc worse and predicts it better — the only thing that matters
         * here. Measured on a 48-hour arc, graded against orbits observed afterwards, median 3D
         * error at +72 h:
         *
         *     coefficients   arc rms    GPS      GLONASS   Galileo   BeiDou MEO/IGSO
         *     9              1.2 m      59 m     27 m      29 m      43 m
         *     3              2.2 m      25 m     11 m      13 m      19 m
         *
         * The extra six buy a factor of two on the arc and cost a factor of two to three on the
         * prediction, because the solver spends them on large cancelling once-per-revolution terms
         * that are only cancelling INSIDE the arc.
         */
        const val NSRP_DEFAULT = 3

        /**
         * Finite-difference increments for the fit: metres, metres per second, then m/s^2 for each
         * radiation-pressure coefficient.
         *
         * Each is small enough to stay in the linear regime and large enough to clear the
         * integrator's own noise by orders of magnitude — 1 nm/s^2 of radiation pressure moves a
         * satellite 0.2 m over six hours.
         */
        val STEP_STATE = doubleArrayOf(1.0, 1.0, 1.0, 1e-3, 1e-3, 1e-3)

        /** Starting radiation-pressure coefficients: a direct term of the right order, the rest zero. */
        val SRP0 = doubleArrayOf(-1e-7, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        private const val SRP_STEP = 1e-9

        /**
         * The default solver: `LeastSquares.minimise`, adapted to [NlsSolver].
         *
         * It differences the residual itself, centrally, one unit of [xScale] either side — so the
         * analytic Jacobian [fitArc] offers is **discarded** and each iteration costs `2n + 1`
         * propagations of the arc instead of the one batched run of `n + 1` trajectories that
         * [fitArc] can produce. On a 48-hour arc with three radiation-pressure coefficients that is
         * measurably slower (see the report in `.scratch/pgnss-kt/`), which is why the seam exists;
         * pass a solver that uses the Jacobian if the phone budget ever gets tight.
         */
        fun leastSquaresSolver(maxIterations: Int = 30): NlsSolver =
            NlsSolver { x0, xScale, residual, _, _ ->
                val m = residual(x0).size
                LeastSquares.minimise(
                    x0 = x0,
                    m = m,
                    f = LeastSquares.Residuals { x, out -> System.arraycopy(residual(x), 0, out, 0, m) },
                    scale = xScale,
                    maxIterations = maxIterations,
                    maxEvaluations = maxIterations * (2 * x0.size + 2),
                ).x
            }
    }

    private val ab = AdamsCoefficients.bashforth(order)
    private val am = AdamsCoefficients.moulton(order)

    /**
     * Integrate a batch of states and report positions at [tEval].
     *
     * @param y0 `6 * k` doubles, `[x, y, z, vx, vy, vz]` per satellite, in the terrestrial frame.
     * @param k number of states in the batch.
     * @param t0 epoch of [y0], GPS seconds.
     * @param tEval epochs wanted, in any order; epochs before and after [t0] are run as two
     *   integrations from the common epoch, and an epoch *at* [t0] is answered from [y0] directly.
     * @param srpParams `k * nsrp` radiation-pressure coefficients, row-major, or null for none.
     * @return `tEval.size * 3 * k` doubles: for epoch `e` and satellite `i`, position at
     *   `3 * (e * k + i)`.
     */
    fun propagate(
        y0: DoubleArray,
        k: Int,
        t0: Double,
        tEval: DoubleArray,
        srpParams: DoubleArray?,
        nsrp: Int,
    ): DoubleArray {
        require(y0.size == 6 * k) { "state must be 6*k = ${6 * k} doubles, got ${y0.size}" }
        val n = tEval.size
        val out = DoubleArray(n * 3 * k)
        val forward = ArrayList<Int>()
        val backward = ArrayList<Int>()
        for (i in 0 until n) {
            when {
                tEval[i] == t0 -> for (j in 0 until k) {
                    val o = 3 * (i * k + j)
                    out[o] = y0[6 * j]
                    out[o + 1] = y0[6 * j + 1]
                    out[o + 2] = y0[6 * j + 2]
                }
                tEval[i] > t0 -> forward.add(i)
                else -> backward.add(i)
            }
        }
        if (forward.isNotEmpty()) run(y0, k, t0, tEval, forward.sortedBy { tEval[it] }, 1.0, srpParams, nsrp, out)
        if (backward.isNotEmpty()) run(y0, k, t0, tEval, backward.sortedByDescending { tEval[it] }, -1.0, srpParams, nsrp, out)
        return out
    }

    /** Convenience for a single satellite: `6` doubles in, `tEval.size * 3` out. */
    fun propagateOne(y0: DoubleArray, t0: Double, tEval: DoubleArray, srpParams: DoubleArray?, nsrp: Int): DoubleArray =
        propagate(y0, 1, t0, tEval, srpParams, nsrp)

    // ── the integration itself ────────────────────────────────────────────────────────────────────

    private var dim = 0
    private lateinit var fHist: Array<DoubleArray>
    private lateinit var y: DoubleArray
    private lateinit var yTmp: DoubleArray
    private lateinit var k1: DoubleArray
    private lateinit var k2: DoubleArray
    private lateinit var k3: DoubleArray
    private lateinit var k4: DoubleArray
    private lateinit var fp: DoubleArray
    private lateinit var acc: DoubleArray

    private fun ensure(d: Int) {
        if (dim == d) return
        dim = d
        fHist = Array(order) { DoubleArray(d) }
        y = DoubleArray(d)
        yTmp = DoubleArray(d)
        k1 = DoubleArray(d)
        k2 = DoubleArray(d)
        k3 = DoubleArray(d)
        k4 = DoubleArray(d)
        fp = DoubleArray(d)
        acc = DoubleArray(d / 2)
    }

    /** The right-hand side: velocity into the first half, acceleration into the second. */
    private fun rhs(t: Double, state: DoubleArray, k: Int, srpParams: DoubleArray?, nsrp: Int, out: DoubleArray) {
        force.accel(t, state, k, srpParams, nsrp, acc)
        for (i in 0 until k) {
            val b = 6 * i
            out[b] = state[b + 3]
            out[b + 1] = state[b + 4]
            out[b + 2] = state[b + 5]
            out[b + 3] = acc[3 * i]
            out[b + 4] = acc[3 * i + 1]
            out[b + 5] = acc[3 * i + 2]
        }
    }

    private fun run(
        y0: DoubleArray,
        k: Int,
        t0: Double,
        tEval: DoubleArray,
        targets: List<Int>,
        dir: Double,
        srpParams: DoubleArray?,
        nsrp: Int,
        out: DoubleArray,
    ) {
        val d = 6 * k
        ensure(d)
        System.arraycopy(y0, 0, y, 0, d)
        var t = t0
        val h = dir * step

        // fHist[0] is always f at the current grid point.
        rhs(t, y, k, srpParams, nsrp, fHist[0])
        var histCount = 1

        var next = 0
        while (next < targets.size) {
            // Everything reachable from here by an excursion of at most one step.
            while (next < targets.size && (tEval[targets[next]] - t) * dir <= step) {
                val i = targets[next]
                emit(tEval[i] - t, t, k, srpParams, nsrp, out, i)
                next++
            }
            if (next >= targets.size) break
            if (histCount < order) {
                startStep(t, h, k, srpParams, nsrp)
            } else {
                pece(t, h, k, srpParams, nsrp)
            }
            t += h
            // Roll the history down and put the new f at the front.
            val last = fHist[order - 1]
            for (j in order - 1 downTo 1) fHist[j] = fHist[j - 1]
            fHist[0] = last
            if (histCount < order) {
                rhs(t, y, k, srpParams, nsrp, fHist[0])
                histCount++
            } else {
                System.arraycopy(fp, 0, fHist[0], 0, d)
            }
        }
    }

    /** One RK4 excursion of length [dt] from the current grid state, written straight into [out]. */
    private fun emit(
        dt: Double,
        t: Double,
        k: Int,
        srpParams: DoubleArray?,
        nsrp: Int,
        out: DoubleArray,
        epoch: Int,
    ) {
        val d = 6 * k
        if (dt == 0.0) {
            System.arraycopy(y, 0, yTmp, 0, d)
        } else {
            rk4(t, dt, y, yTmp, k, srpParams, nsrp)
        }
        for (i in 0 until k) {
            val o = 3 * (epoch * k + i)
            out[o] = yTmp[6 * i]
            out[o + 1] = yTmp[6 * i + 1]
            out[o + 2] = yTmp[6 * i + 2]
        }
    }

    /** A starter grid step, RK4 at [STARTER_SUBSTEPS] sub-steps, in place on [y]. */
    private fun startStep(t: Double, h: Double, k: Int, srpParams: DoubleArray?, nsrp: Int) {
        val d = 6 * k
        val hs = h / STARTER_SUBSTEPS
        var tt = t
        for (s in 0 until STARTER_SUBSTEPS) {
            rk4(tt, hs, y, yTmp, k, srpParams, nsrp)
            System.arraycopy(yTmp, 0, y, 0, d)
            tt += hs
        }
    }

    /** One predict-evaluate-correct-evaluate step, in place on [y]; leaves `f(t+h, y)` in [fp]. */
    private fun pece(t: Double, h: Double, k: Int, srpParams: DoubleArray?, nsrp: Int) {
        val d = 6 * k
        for (i in 0 until d) {
            var s = 0.0
            for (j in 0 until order) s += ab[j] * fHist[j][i]
            yTmp[i] = y[i] + h * s
        }
        rhs(t + h, yTmp, k, srpParams, nsrp, fp)
        for (i in 0 until d) {
            var s = am[0] * fp[i]
            for (j in 1 until order) s += am[j] * fHist[j - 1][i]
            y[i] += h * s
        }
        rhs(t + h, y, k, srpParams, nsrp, fp)
    }

    /** Classical RK4, from [yin] at [t] over [h], into [yout]. */
    private fun rk4(t: Double, h: Double, yin: DoubleArray, yout: DoubleArray, k: Int, srpParams: DoubleArray?, nsrp: Int) {
        val d = 6 * k
        rhs(t, yin, k, srpParams, nsrp, k1)
        for (i in 0 until d) yout[i] = yin[i] + 0.5 * h * k1[i]
        rhs(t + 0.5 * h, yout, k, srpParams, nsrp, k2)
        for (i in 0 until d) yout[i] = yin[i] + 0.5 * h * k2[i]
        rhs(t + 0.5 * h, yout, k, srpParams, nsrp, k3)
        for (i in 0 until d) yout[i] = yin[i] + h * k3[i]
        rhs(t + h, yout, k, srpParams, nsrp, k4)
        for (i in 0 until d) yout[i] = yin[i] + h / 6.0 * (k1[i] + 2 * k2[i] + 2 * k3[i] + k4[i])
    }

    // ── the arc fit ───────────────────────────────────────────────────────────────────────────────

    /**
     * Fit an epoch state plus radiation-pressure coefficients to precise positions used as
     * pseudo-observations. Montenbruck et al., NAVIGATION 68(1):199-215 (2021).
     *
     * Everything is in the **terrestrial** frame: [pObs] must already have been through
     * [EarthOrientation.toTirs], and so must [y0].
     *
     * @param tObs epochs of the pseudo-observations, GPS seconds.
     * @param pObs `3 * tObs.size` doubles, position per epoch.
     * @param t0 the epoch the fitted state refers to.
     * @param y0 a seed state at [t0] — osculating from the product is plenty; the fit moves it
     *   kilometres if it has to.
     * @param nsrp how many radiation-pressure coefficients to estimate; see [NSRP_DEFAULT].
     * @param solver see [NlsSolver] — and read its note before substituting a plain
     *   Levenberg-Marquardt.
     */
    fun fitArc(
        tObs: DoubleArray,
        pObs: DoubleArray,
        t0: Double,
        y0: DoubleArray,
        nsrp: Int = NSRP_DEFAULT,
        solver: NlsSolver = leastSquaresSolver(),
        maxEval: Int = 40,
    ): ArcFit {
        require(pObs.size == 3 * tObs.size) { "need one position per epoch" }
        require(y0.size == 6) { "seed state must be 6 doubles" }
        require(nsrp in 0..9) { "nsrp must be 0..9" }
        val npar = 6 + nsrp
        val xScale = DoubleArray(npar) { if (it < 6) STEP_STATE[it] else SRP_STEP }
        val x0 = DoubleArray(npar) { if (it < 6) y0[it] else SRP0[it - 6] }

        val residual = { x: DoubleArray ->
            val pos = propagate(x.copyOfRange(0, 6), 1, t0, tObs, x.copyOfRange(6, npar), nsrp)
            DoubleArray(pos.size) { pos[it] - pObs[it] }
        }

        val jacobian = { x: DoubleArray ->
            val kb = npar + 1
            val states = DoubleArray(6 * kb)
            val srp = DoubleArray(kb * nsrp)
            for (c in 0 until kb) {
                for (i in 0 until 6) states[6 * c + i] = x[i]
                for (i in 0 until nsrp) srp[c * nsrp + i] = x[6 + i]
                if (c > 0) {
                    val p = c - 1
                    if (p < 6) states[6 * c + p] += xScale[p] else srp[c * nsrp + (p - 6)] += xScale[p]
                }
            }
            val pos = propagate(states, kb, t0, tObs, srp, nsrp)
            val m = 3 * tObs.size
            Array(m) { row ->
                val e = row / 3
                val comp = row % 3
                val base = 3 * (e * kb) + comp
                val nominal = pos[base]
                DoubleArray(npar) { p -> (pos[base + 3 * (p + 1)] - nominal) / xScale[p] }
            }
        }

        val x = solver.solve(x0, xScale, residual, jacobian, maxEval)
        val r = residual(x)
        var sum = 0.0
        for (v in r) sum += v * v
        return ArcFit(x, sqrt(sum / r.size * 3.0), nsrp)
    }
}
