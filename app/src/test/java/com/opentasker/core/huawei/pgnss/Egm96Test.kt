package com.opentasker.core.huawei.pgnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The failure this prevents is a **silently normalised gravity field**.
 *
 * ICGEM publishes fully normalised coefficients; [GeoPotential] runs on unnormalised ones. Feeding it
 * the file's own numbers does not crash and does not look wrong: it scales J2 by 1/sqrt(5), the orbit
 * comes out with the wrong flattening, and the arc fit absorbs enough of it into the epoch state to
 * report a small residual. The error only appears three days later as tens of kilometres, at which
 * point nothing in the pipeline is still looking.
 *
 * So the two coefficients whose unnormalised magnitudes are textbook facts are pinned here:
 * `C20 = -1.0826e-3` (which *is* -J2) and `C22 = 1.5746e-6`.
 *
 * The second failure is arithmetic: writing `N(n,m)` with literal factorials. `(n+m)!` overflows a
 * double at n = 85 and EGM96 runs to degree 360, so the obvious transcription hands back `NaN` for
 * most of the file — harmless while only degree 12 is kept, and a trap the moment anyone raises it.
 */
class Egm96Test {

    private fun fixture(): GravityField =
        BufferedReader(InputStreamReader(checkNotNull(javaClass.getResourceAsStream("/pgnss/egm96-deg12.gfc")))).use {
            Egm96.parse(it, 12)
        }

    @Test
    fun `EGM96 unnormalises to the textbook C20 and C22`() {
        val f = fixture()
        assertEquals(-1.0826266e-3, f.c(2, 0), 2e-9)
        assertEquals(1.57446e-6, f.c(2, 2), 2e-10)
        assertEquals(1.0, f.c(0, 0), 0.0)
        assertEquals(0.0, f.s(0, 0), 0.0)
        assertEquals(0.0, f.s(2, 0), 0.0)
    }

    @Test
    fun `the unnormalising factor matches its definition without forming a factorial`() {
        // sqrt( (n-m)! (2n+1) (2 - d0m) / (n+m)! ), evaluated the naive way, for degrees small
        // enough that the naive way still works.
        fun factorial(k: Int): Double {
            var v = 1.0
            for (i in 2..k) v *= i
            return v
        }
        for (n in 0..12) {
            for (m in 0..n) {
                val want = sqrt(factorial(n - m) * (2 * n + 1) * (if (m > 0) 2.0 else 1.0) / factorial(n + m))
                assertEquals("N($n,$m)", want, Egm96.unnormalise(n, m), abs(want) * 1e-12 + 1e-300)
            }
        }
    }

    @Test
    fun `the factor survives the degrees where a literal factorial does not`() {
        // Two separate ways the obvious transcription dies on the file's own contents.
        //
        // (200, 10): both factorials overflow, so the naive expression evaluates inf/inf = NaN. The
        // answer is about 2.8e-22 and perfectly ordinary.
        val a = Egm96.unnormalise(200, 10)
        assertTrue("N(200,10) = $a", a.isFinite() && a > 0.0)

        // (200, 100): the RATIO is 1e-453 and underflows to zero, while the answer, 1.6e-227, is
        // perfectly representable — which is why the division happens inside the square root rather
        // than before it.
        val b = Egm96.unnormalise(200, 100)
        assertTrue("N(200,100) = $b", b.isFinite() && b > 0.0)
        assertEquals(-226.806, Math.log10(b), 1e-3)

        // And the honest limit: past about (200, 130) the answer itself is below the smallest double
        // and really is zero. (360, 360) is 1e-872. Nothing here goes near those degrees.
        assertEquals(0.0, Egm96.unnormalise(360, 360), 0.0)
    }

    @Test
    fun `truncation keeps exactly the requested degree`() {
        val f = BufferedReader(InputStreamReader(checkNotNull(javaClass.getResourceAsStream("/pgnss/egm96-deg12.gfc")))).use {
            Egm96.parse(it, 4)
        }
        assertEquals(4, f.nmax)
        assertEquals((4 + 1) * (4 + 2) / 2, f.c.size)
        assertEquals(-1.0826266e-3, f.c(2, 0), 2e-9)
    }

    @Test
    fun `header lines and blank lines are ignored rather than parsed`() {
        val text = """
            product_type                gravity_field
            modelname                   EGM96
            max_degree                     360

            key    L    M         C                   S
            end_of_head ====================================
            gfc     0   0  1.000000000000e+00  0.000000000000e+00
            gfc     2   0 -0.484165371736e-03  0.000000000000e+00
            gfc     2   2  0.243914352398e-05 -0.140016683654e-05
        """.trimIndent()
        val f = Egm96.parse(BufferedReader(text.reader()), 2)
        assertEquals(-1.0826266e-3, f.c(2, 0), 2e-9)
        assertEquals(1.57446e-6, f.c(2, 2), 2e-10)
    }

    @Test
    fun `a Fortran D exponent parses`() {
        val f = Egm96.parse(
            BufferedReader("gfc     2   0 -0.484165371736D-03  0.000000000000D+00".reader()),
            2,
        )
        assertEquals(-1.0826266e-3, f.c(2, 0), 2e-9)
    }
}
