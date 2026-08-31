package com.opentasker.core.huawei.pgnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The solver that replaces scipy's `least_squares`, on problems whose answers are known in closed
 * form rather than on the orbit fit.
 *
 * The failure this prevents is the quiet one: a solver that *returns* — with a plausible residual
 * and `converged = true` — from a point that is not the minimum. The reference's own note is the
 * warning: "a residual reported by the solver is not evidence, because a solver that has wandered
 * off reports its own happiness", and one run of it produced a 27 800 km element set among 2196.
 * So every case here checks the ANSWER against something computed independently of the solver, and
 * the bounded cases check that the answer is on the boundary where the boundary is where the
 * optimum is — a bounded fit that silently ignores its bounds would let the encoder clip a rate, and
 * a clipped idot is 1.9 km of error at the edge of its slice.
 */
class LeastSquaresTest {

    @Test
    fun `the QR least-squares solve matches the closed-form answer`() {
        // A 4x2 system whose normal equations can be written down by hand.
        val a = doubleArrayOf(1.0, 1.0, 1.0, 2.0, 1.0, 3.0, 1.0, 4.0)
        val b = doubleArrayOf(6.0, 5.0, 7.0, 10.0)
        val out = DoubleArray(2)
        assertTrue(LeastSquares.solveQr(a.copyOf(), 4, 2, b.copyOf(), out))
        // Textbook: slope 1.4, intercept 3.5.
        assertEquals(3.5, out[0], 1e-12)
        assertEquals(1.4, out[1], 1e-12)
    }

    @Test
    fun `the QR solve refuses a singular system rather than returning a plausible vector`() {
        val a = doubleArrayOf(1.0, 2.0, 2.0, 4.0, 3.0, 6.0)
        val b = doubleArrayOf(1.0, 2.0, 3.0)
        assertFalse(LeastSquares.solveQr(a, 3, 2, b, DoubleArray(2)))
    }

    @Test
    fun `a badly scaled non-linear problem converges to the right parameters`() {
        // y = A exp(-k t), with A around 3e6 and k around 4e-9: the same eight orders of scale
        // separation the orbit fit has between the semi-major axis and the rates.
        val trueA = 3.21e6
        val trueK = 4.4e-9
        val ts = DoubleArray(40) { it * 2.0e7 }
        val ys = DoubleArray(40) { trueA * exp(-trueK * ts[it]) }
        val f = LeastSquares.Residuals { x, out ->
            for (i in ts.indices) out[i] = x[0] * exp(-x[1] * ts[i]) - ys[i]
        }
        val sol = LeastSquares.minimise(
            doubleArrayOf(1.0e6, 1.0e-9), 40, f, doubleArrayOf(1.0e3, 1.0e-12),
        )
        assertTrue("converged", sol.converged)
        assertEquals(trueA, sol.x[0], trueA * 1e-6)
        assertEquals(trueK, sol.x[1], trueK * 1e-6)
        assertTrue("cost ${sol.cost}", sol.cost < 1e-6)
    }

    @Test
    fun `a bound the optimum lies outside is honoured, and the answer sits on it`() {
        // Least squares for y = a + b x on data whose unconstrained slope is 2, with b capped at 1.
        val xs = DoubleArray(20) { it.toDouble() }
        val ys = DoubleArray(20) { 5.0 + 2.0 * it }
        val f = LeastSquares.Residuals { p, out ->
            for (i in xs.indices) out[i] = p[0] + p[1] * xs[i] - ys[i]
        }
        val free = LeastSquares.minimise(
            doubleArrayOf(0.0, 0.0), 20, f, doubleArrayOf(1.0, 1.0),
        )
        assertEquals(5.0, free.x[0], 1e-6)
        assertEquals(2.0, free.x[1], 1e-9)

        val bounded = LeastSquares.minimise(
            doubleArrayOf(0.0, 0.0), 20, f, doubleArrayOf(1.0, 1.0),
            lower = doubleArrayOf(Double.NEGATIVE_INFINITY, -1.0),
            upper = doubleArrayOf(Double.POSITIVE_INFINITY, 1.0),
        )
        assertEquals("the slope is pinned to its ceiling", 1.0, bounded.x[1], 1e-12)
        assertTrue("and never crosses it", bounded.x[1] <= 1.0)
        // With b pinned, the best a is the mean of (y - x): 5 + mean(x) = 5 + 9.5.
        assertEquals(14.5, bounded.x[0], 1e-6)
    }

    @Test
    fun `every returned parameter is inside its box, on a problem that pulls at every wall`() {
        val lower = DoubleArray(5) { -1.0 }
        val upper = DoubleArray(5) { 1.0 }
        val target = doubleArrayOf(-4.0, 4.0, 0.25, -0.25, 9.0)
        val f = LeastSquares.Residuals { x, out ->
            for (i in 0 until 5) out[i] = x[i] - target[i]
        }
        val sol = LeastSquares.minimise(
            DoubleArray(5), 5, f, DoubleArray(5) { 1.0 }, lower, upper,
        )
        assertEquals(-1.0, sol.x[0], 1e-12)
        assertEquals(1.0, sol.x[1], 1e-12)
        assertEquals(0.25, sol.x[2], 1e-6)
        assertEquals(-0.25, sol.x[3], 1e-6)
        assertEquals(1.0, sol.x[4], 1e-12)
        for (i in 0 until 5) {
            assertTrue("parameter $i escaped its box: ${sol.x[i]}",
                sol.x[i] >= lower[i] && sol.x[i] <= upper[i])
        }
    }

    @Test
    fun `it does not claim convergence from a point it has not reached`() {
        // Rosenbrock in least-squares form: the classic banana that a lazy line search stalls in.
        val f = LeastSquares.Residuals { x, out ->
            out[0] = 10.0 * (x[1] - x[0] * x[0])
            out[1] = 1.0 - x[0]
        }
        val sol = LeastSquares.minimise(
            doubleArrayOf(-1.2, 1.0), 2, f, doubleArrayOf(1.0, 1.0), maxIterations = 400,
        )
        assertEquals(1.0, sol.x[0], 1e-5)
        assertEquals(1.0, sol.x[1], 1e-5)
        assertTrue("residual norm ${sqrt(2 * sol.cost)}", sqrt(2 * sol.cost) < 1e-5)
        assertTrue(abs(sol.cost) < 1e-9)
    }
}
