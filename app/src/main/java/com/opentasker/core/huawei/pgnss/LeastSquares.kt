package com.opentasker.core.huawei.pgnss

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A bounded Levenberg–Marquardt solver, written here because this project has no linear-algebra
 * library and none may be added.
 *
 * It stands in for scipy's `least_squares` in `scripts/pgnss-build.py`. It is NOT a port of MINPACK
 * and does not try to reproduce its iterate sequence: the two land on the same minimum by different
 * routes, and the last bits of a fifteen-parameter non-linear solve are not reproducible across
 * implementations. That is why the acceptance test grades the ENCODERS against the reference's bytes
 * and grades the fit against the reference's own measured position error — never against a number
 * this solver reports about itself. A solver that has wandered off reports its own happiness.
 *
 * Three things here are load-bearing:
 *
 * * **Everything happens in scaled coordinates** `z = x / scale`. The parameter vector spans 5153
 *   (√A, in √metres) down to 1e-12 (rates, in rad/s); a step, a convergence test or a finite
 *   difference measured in raw units is meaningless for fourteen of the fifteen.
 * * **The finite-difference step is one whole unit of that scale**, taken centrally, and NOT a
 *   fraction of `x` itself. Six of the fifteen parameters are seeded at exactly zero, where a
 *   relative step is no step at all — and this is not a theoretical worry. MINPACK's rule, which is
 *   what the reference gets from `method="lm"`, differences a parameter sitting at zero with a step
 *   of `sqrt(eps)` = 1.49e-8. For `crc`, whose unit is a METRE of orbital radius, that is a
 *   perturbation of 15 nanometres against positions of 2.7e7 m whose own ulp is 3.7 nm: the column
 *   comes out three parts signal to one part rounding noise. Measured on the twelve fixture slices,
 *   a scale-sized central difference lands every one of them at 0.005-0.037 m where the reference's
 *   own sets score 0.15-5.9 m by its own measure, and scipy's `trf` re-run on those slices with
 *   tight tolerances reproduces this solver's answer on eight of the twelve to three decimals.
 * * **The damped system is solved by Householder QR on the augmented matrix**, not by forming
 *   `JᵀJ`. The Jacobian of this problem is close to rank-deficient — the radius harmonics trade
 *   against √A and the argument-of-latitude harmonics against M0 — and squaring its condition
 *   number throws away half the digits at exactly the place it matters.
 */
object LeastSquares {

    /** Writes the residual vector for parameters [x] into [out]. */
    fun interface Residuals {
        fun eval(x: DoubleArray, out: DoubleArray)
    }

    /**
     * [converged] is true when the solver stopped because it could not improve further — a vanishing
     * gradient, a cost reduction under [minimise]'s `ftol`, or no damping large enough to find a
     * descent step. It is false only when the iteration or evaluation budget ran out first. It is
     * NOT a claim that the answer is good: that is what `Orbit.checkError` is for, and the reference
     * lost a 27 800 km element set among 2196 to exactly this distinction.
     */
    class Result(
        val x: DoubleArray,
        val residuals: DoubleArray,
        val cost: Double,
        val iterations: Int,
        val evaluations: Int,
        val converged: Boolean,
    )

    /**
     * Minimise `½‖f(x)‖²` subject to `lower ≤ x ≤ upper`.
     *
     * [scale] is the characteristic size of each parameter and must be strictly positive. Bounds may
     * be infinite componentwise; passing null for both is the unbounded problem.
     */
    fun minimise(
        x0: DoubleArray,
        m: Int,
        f: Residuals,
        scale: DoubleArray,
        lower: DoubleArray? = null,
        upper: DoubleArray? = null,
        maxIterations: Int = 200,
        maxEvaluations: Int = 20_000,
        ftol: Double = 1e-12,
        xtol: Double = 0.0,
        gtol: Double = 1e-12,
        diffStep: Double = 1.0,
    ): Result {
        val n = x0.size
        require(scale.size == n) { "one scale per parameter" }
        require(scale.all { it > 0.0 }) { "scales must be positive" }

        val x = DoubleArray(n) { clampTo(x0[it], lower?.get(it), upper?.get(it)) }
        val r = DoubleArray(m)
        val rTrial = DoubleArray(m)
        val rPerturbed = DoubleArray(m)
        val rBack = DoubleArray(m)
        val xTrial = DoubleArray(n)
        val jac = DoubleArray(m * n)
        val grad = DoubleArray(n)
        val colNorm = DoubleArray(n)
        val delta = DoubleArray(n)
        val augmented = DoubleArray((m + n) * n)
        val rhs = DoubleArray(m + n)
        val free = BooleanArray(n)

        var evaluations = 0
        f.eval(x, r)
        evaluations++
        var cost = halfSquare(r)

        var lambda = 1e-3
        var nu = 2.0
        var iterations = 0
        var converged = false

        while (iterations < maxIterations && evaluations < maxEvaluations) {
            iterations++

            // CENTRAL differences, one whole `diffStep` unit either side in SCALED coordinates.
            //
            // Both halves of that were measured on the twelve fixture slices, not assumed. Forward
            // differences at a thousandth of the scale stall the solver between 1.4 m and 3.9 m;
            // central differences at one whole unit land every one of them under 0.04 m. What limits
            // the Jacobian here is ROUNDING, not truncation — `crc` is in metres of orbital radius
            // against positions of 2.7e7 m, so a small step differences two numbers that agree to
            // their last few bits, while the model is near enough linear that a large one costs
            // nothing. And the step is measured in the parameter's characteristic scale, never
            // relative to the parameter itself: six of the fifteen are seeded at exactly zero, where
            // a relative step is no step at all.
            for (j in 0 until n) {
                val h = diffStep * scale[j]
                val saved = x[j]
                val up = upper == null || saved + h <= upper[j]
                val down = lower == null || saved - h >= lower[j]
                if (up && down) {
                    x[j] = saved + h
                    f.eval(x, rPerturbed)
                    x[j] = saved - h
                    f.eval(x, rBack)
                    evaluations += 2
                    for (i in 0 until m) jac[i * n + j] = (rPerturbed[i] - rBack[i]) / (2 * diffStep)
                } else {
                    // Against a wall, a one-sided difference that stays inside it.
                    x[j] = if (up) saved + h else saved - h
                    f.eval(x, rPerturbed)
                    evaluations++
                    val den = if (up) diffStep else -diffStep
                    for (i in 0 until m) jac[i * n + j] = (rPerturbed[i] - r[i]) / den
                }
                x[j] = saved
            }

            for (j in 0 until n) {
                var g = 0.0
                var c = 0.0
                for (i in 0 until m) {
                    val v = jac[i * n + j]
                    g += v * r[i]
                    c += v * v
                }
                grad[j] = g
                colNorm[j] = sqrt(c)
            }

            // Active set: a parameter pinned to a bound whose gradient pushes it further out is
            // frozen for this step. Without this the solver spends every iteration proposing a step
            // that the clamp immediately undoes, and never converges.
            var gradientNorm = 0.0
            for (j in 0 until n) {
                val atLower = lower != null && x[j] <= lower[j] && grad[j] > 0.0
                val atUpper = upper != null && x[j] >= upper[j] && grad[j] < 0.0
                free[j] = !(atLower || atUpper)
                if (!free[j]) {
                    grad[j] = 0.0
                    for (i in 0 until m) jac[i * n + j] = 0.0
                    colNorm[j] = 0.0
                }
                gradientNorm = max(gradientNorm, abs(grad[j]))
            }
            if (gradientNorm <= gtol) {
                converged = true
                break
            }
            for (j in 0 until n) if (colNorm[j] <= 0.0) colNorm[j] = 1.0

            var stepTaken = false
            var inner = 0
            while (inner < 30 && evaluations < maxEvaluations) {
                inner++
                java.util.Arrays.fill(augmented, 0.0)
                java.util.Arrays.fill(rhs, 0.0)
                for (i in 0 until m) {
                    for (j in 0 until n) augmented[i * n + j] = jac[i * n + j]
                    rhs[i] = -r[i]
                }
                val damp = sqrt(lambda)
                for (j in 0 until n) augmented[(m + j) * n + j] = damp * colNorm[j]
                if (!solveQr(augmented, m + n, n, rhs, delta)) {
                    lambda *= nu
                    nu *= 2.0
                    continue
                }
                for (j in 0 until n) {
                    val d = if (free[j]) delta[j] else 0.0
                    xTrial[j] = clampTo(x[j] + scale[j] * d, lower?.get(j), upper?.get(j))
                }
                f.eval(xTrial, rTrial)
                evaluations++
                val costTrial = halfSquare(rTrial)

                // Predicted reduction from the linear model, measured on the step actually taken.
                var predicted = 0.0
                for (i in 0 until m) {
                    var jd = 0.0
                    for (j in 0 until n) jd += jac[i * n + j] * ((xTrial[j] - x[j]) / scale[j])
                    val before = r[i]
                    val after = r[i] + jd
                    predicted += 0.5 * (before * before - after * after)
                }

                if (costTrial < cost) {
                    var stepNorm = 0.0
                    var xNorm = 0.0
                    for (j in 0 until n) {
                        val d = (xTrial[j] - x[j]) / scale[j]
                        stepNorm += d * d
                        xNorm += (x[j] / scale[j]) * (x[j] / scale[j])
                    }
                    stepNorm = sqrt(stepNorm)
                    xNorm = sqrt(xNorm)
                    val reduction = cost - costTrial
                    System.arraycopy(xTrial, 0, x, 0, n)
                    System.arraycopy(rTrial, 0, r, 0, m)
                    cost = costTrial
                    stepTaken = true
                    val rho = if (predicted > 0.0) reduction / predicted else 1.0
                    val shrink = max(1.0 / 3.0, 1.0 - (2.0 * rho - 1.0) * (2.0 * rho - 1.0) *
                        (2.0 * rho - 1.0))
                    lambda = max(lambda * shrink, 1e-14)
                    nu = 2.0
                    if (reduction <= ftol * (cost + ftol) ||
                        (xtol > 0.0 && stepNorm <= xtol * (xNorm + xtol))
                    ) {
                        converged = true
                    }
                    break
                }
                lambda *= nu
                nu *= 2.0
                if (lambda > 1e16) break
            }
            if (!stepTaken || converged) {
                if (!stepTaken) converged = true
                break
            }
        }
        return Result(x, r.copyOf(), cost, iterations, evaluations, converged)
    }

    private fun halfSquare(r: DoubleArray): Double {
        var s = 0.0
        for (v in r) s += v * v
        return 0.5 * s
    }

    private fun clampTo(v: Double, lo: Double?, hi: Double?): Double {
        var out = v
        if (lo != null && out < lo) out = lo
        if (hi != null && out > hi) out = hi
        return out
    }

    /**
     * Solve `min ‖A z − b‖` for a full-column-rank `A` by Householder QR, in place.
     *
     * [a] is row-major `rows × cols`, [b] has [rows] entries; both are overwritten. Returns false if
     * a pivot vanishes, which for the damped system above means the damping itself has underflowed
     * and the caller should raise it rather than trust a solution.
     */
    internal fun solveQr(
        a: DoubleArray,
        rows: Int,
        cols: Int,
        b: DoubleArray,
        out: DoubleArray,
    ): Boolean {
        val v = DoubleArray(rows)
        for (k in 0 until cols) {
            var norm = 0.0
            for (i in k until rows) {
                val e = a[i * cols + k]
                norm += e * e
            }
            norm = sqrt(norm)
            if (norm == 0.0 || !norm.isFinite()) return false
            val head = a[k * cols + k]
            val alpha = if (head >= 0.0) -norm else norm
            v[k] = head - alpha
            for (i in k + 1 until rows) v[i] = a[i * cols + k]
            val beta = alpha * v[k]
            if (beta == 0.0) {
                a[k * cols + k] = alpha
                continue
            }
            for (j in k + 1 until cols) {
                var s = 0.0
                for (i in k until rows) s += v[i] * a[i * cols + j]
                s /= beta
                for (i in k until rows) a[i * cols + j] += s * v[i]
            }
            var s = 0.0
            for (i in k until rows) s += v[i] * b[i]
            s /= beta
            for (i in k until rows) b[i] += s * v[i]
            a[k * cols + k] = alpha
            for (i in k + 1 until rows) a[i * cols + k] = 0.0
        }
        for (k in cols - 1 downTo 0) {
            var s = b[k]
            for (j in k + 1 until cols) s -= a[k * cols + j] * out[j]
            val d = a[k * cols + k]
            if (d == 0.0) return false
            out[k] = s / d
            if (!out[k].isFinite()) return false
        }
        return true
    }
}
