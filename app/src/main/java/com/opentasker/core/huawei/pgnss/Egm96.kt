package com.opentasker.core.huawei.pgnss

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.sqrt

/**
 * The Earth's gravity field, as the orbit integrator wants it: **unnormalised** C and S, truncated
 * to the degree actually used.
 *
 * ICGEM `.gfc` files — EGM96 is the one this pipeline uses — publish *fully normalised* coefficients,
 * because the normalised ones stay within a few orders of magnitude of each other all the way to
 * degree 360 and the unnormalised ones underflow. Cunningham's recursion in [GeoPotential] runs on
 * the unnormalised form, so every coefficient is multiplied on the way in by
 *
 *     N(n,m) = sqrt( (n-m)! (2n+1) (2 - delta_0m) / (n+m)! )
 *
 * The obvious transcription of that formula computes `(n+m)!` and, at the degree 360 the file
 * carries, hands back `inf / inf` = `NaN` — 720! overflows a double from n = 85 upward. The
 * factorials are therefore never formed; see [Egm96.unnormalise].
 *
 * ## Why the parse is streaming
 * EGM96.gfc is 5.6 MB and 65 339 coefficient lines, of which a degree-12 field needs 91. Reading it
 * into a list first costs about 12 MB of `String` on a phone to throw away 99.86 % of it. The parser
 * therefore reads a line at a time and keeps only what is in range.
 *
 * ## What this class prevents
 * A silently *normalised* field. Nothing about it crashes: the orbit simply comes out wrong by the
 * ratio N(2,0) = sqrt(5), so J2 is 2.24 times too small, and a fit absorbs part of that into its
 * epoch state and reports a small residual. [Egm96Test] pins C20 and C22 against the two values whose
 * unnormalised magnitudes are common knowledge (-1.0826e-3, which is -J2, and 1.5746e-6) — the same
 * check the Python reference makes, and for the same reason.
 */
class GravityField(
    /** Highest degree and order retained. */
    val nmax: Int,
    /** Unnormalised C, packed triangularly: `c[n * (n + 1) / 2 + m]`. */
    val c: DoubleArray,
    /** Unnormalised S, same packing. */
    val s: DoubleArray,
) {
    init {
        val need = (nmax + 1) * (nmax + 2) / 2
        require(c.size == need && s.size == need) { "coefficient arrays must be triangular for nmax=$nmax" }
    }

    /** Triangular index of (degree [n], order [m]). */
    fun index(n: Int, m: Int): Int = n * (n + 1) / 2 + m

    fun c(n: Int, m: Int): Double = c[index(n, m)]

    fun s(n: Int, m: Int): Double = s[index(n, m)]

    /** A mutable copy of [c], for the per-step solid-tide patch. */
    fun copyC(): DoubleArray = c.copyOf()

    /** A mutable copy of [s], for the per-step solid-tide patch. */
    fun copyS(): DoubleArray = s.copyOf()
}

object Egm96 {

    /**
     * Multiply a fully-normalised coefficient by this to get the unnormalised one.
     *
     * The factorials are never formed: `(n-m)!/(n+m)!` is one over a product of exactly 2m
     * consecutive integers, and the division is done **inside the square root**, one factor at a
     * time. Both of those matter at the degrees the file actually contains:
     *
     * * Forming `(n+m)!` gives infinity from n = 85 upward, and `inf / inf` — which is what the naive
     *   expression produces for, say, (200, 10) — is `NaN`, not a small number.
     * * Forming the whole ratio first and taking one square root at the end underflows to zero at
     *   (200, 100), where the ratio is 1e-453 and the answer, 1.6e-227, is perfectly representable.
     *
     * Past about (200, 130) the answer itself falls below the smallest double and really is zero:
     * (360, 180) is 1e-455 and (360, 360) is 1e-872. Unnormalised coefficients simply do not exist
     * in double at those degrees, which is why gravity fields are published normalised and why
     * Cunningham's recursion is only used to degree 12 here.
     */
    fun unnormalise(n: Int, m: Int): Double {
        require(m in 0..n) { "order $m out of range for degree $n" }
        var f = sqrt((2.0 * n + 1.0) * (if (m > 0) 2.0 else 1.0))
        for (k in (n - m + 1)..(n + m)) f /= sqrt(k.toDouble())
        return f
    }

    /** Parse an ICGEM `.gfc` file, keeping degrees 0..[nmax]. */
    fun read(file: File, nmax: Int): GravityField =
        file.bufferedReader().use { parse(it, nmax) }

    /** Parse an ICGEM `.gfc` stream, keeping degrees 0..[nmax]. */
    fun read(stream: InputStream, nmax: Int): GravityField =
        BufferedReader(InputStreamReader(stream)).use { parse(it, nmax) }

    /**
     * The parse itself. One line at a time; anything that is not a `gfc` record, or whose degree is
     * above [nmax], is dropped without allocating past the line.
     *
     * `C[0,0]` is forced to 1 afterwards. EGM96 does carry that line, but a field whose header is
     * trimmed differently would otherwise integrate with no central term at all — a satellite that
     * flies in a straight line, which is a spectacular failure only if something is looking.
     */
    fun parse(reader: BufferedReader, nmax: Int): GravityField {
        require(nmax >= 0) { "nmax must not be negative" }
        val size = (nmax + 1) * (nmax + 2) / 2
        val c = DoubleArray(size)
        val s = DoubleArray(size)
        var line = reader.readLine()
        while (line != null) {
            if (line.startsWith("gfc")) {
                val f = line.trim().split(WHITESPACE)
                if (f.size >= 5) {
                    val n = f[1].toIntOrNull()
                    val m = f[2].toIntOrNull()
                    if (n != null && m != null && n <= nmax && m in 0..n) {
                        val k = unnormalise(n, m)
                        val i = n * (n + 1) / 2 + m
                        c[i] = fortranDouble(f[3]) * k
                        s[i] = fortranDouble(f[4]) * k
                    }
                }
            }
            line = reader.readLine()
        }
        c[0] = 1.0
        s[0] = 0.0
        return GravityField(nmax, c, s)
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * ICGEM files are written by Fortran and a few of them still spell the exponent `D`.
     * `"0.1D-03".toDouble()` throws; the Python reference does the same replacement.
     */
    private fun fortranDouble(token: String): Double =
        (if (token.indexOf('D') >= 0 || token.indexOf('d') >= 0) {
            token.replace('D', 'e').replace('d', 'e')
        } else {
            token
        }).toDouble()
}
