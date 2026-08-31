package com.opentasker.core.huawei.pgnss

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The propagator the band itself runs, the osculating seed that starts a fit, and the fit.
 *
 * A port of `propagate` / `propagate_bds` / `geo_frame` / `seed_elements` / `wrap` / `luni_solar` /
 * `fit` / `clock_fit` / `_check` / `bds_kind` in `scripts/pgnss-build.py`.
 *
 * The point of fitting against *this* propagator rather than a tidier model is that what gets
 * minimised is the error the BAND will see. Extrapolating a broadcast Kepler set is useless — 28 m
 * at toe+4 h, 438 m at +24 h — so the file is 36 two-hour slices precisely so that every element set
 * is only ever evaluated near its own toe.
 */
object Orbit {

    /** GPS's gravitational constant, and the one `seed_elements` uses for every constellation. */
    const val MU = 3.986005e14
    const val OMEGA_E = 7.2921151467e-5

    /**
     * BeiDou runs on its OWN constants and its own time scale, and both matter.
     *
     * The rotation rate differs from GPS's in the eleventh digit, which would be beneath notice
     * except that the propagation formula carries a term −ω_e·t_oe: the difference is multiplied not
     * by the few thousand seconds since toe but by the up-to-604800 seconds since the start of the
     * week, so 1.5e-12 rad/s becomes 8.9e-7 rad, 25 m at MEO.
     */
    const val BDS_MU = 3.986004418e14
    const val BDS_OMEGA = 7.2921150e-5

    /** BDT = GPS − 14 s, and BeiDou's toe/toc are seconds of the BeiDou week. */
    const val BDT_OFFSET = 14

    /**
     * The ICD's π, which is NOT [kotlin.math.PI].
     *
     * They differ in the thirteenth digit, and every angle in every record is a count of a power of
     * two SEMICIRCLES — so this constant is a scale factor on the encoded integer, not a maths
     * nicety. Using the true π instead moves Ω0 by about a tenth of a count; using it in [wrap]
     * moves the fold point. It is written the way the reference writes it.
     */
    const val PI = 3.1415926535898

    /** GPS − UTC, seconds, as of 2026. Only ever used to convert real UTC — never on an SP3 epoch. */
    const val LEAP = 18

    private const val N = 15

    const val SQRT_A = 0
    const val ECC = 1
    const val I0 = 2
    const val OMEGA0 = 3
    const val OMEGA = 4
    const val M0 = 5
    const val DELTA_N = 6
    const val OMEGA_DOT = 7
    const val I_DOT = 8
    const val CUC = 9
    const val CUS = 10
    const val CRC = 11
    const val CRS = 12
    const val CIC = 13
    const val CIS = 14

    /** The fitted parameters, in the reference's `ORDER`. The fixtures are written in this order. */
    val ORDER = listOf(
        "sqrtA", "e", "i0", "omega0", "omega", "m0", "dn", "omegadot", "idot",
        "cuc", "cus", "crc", "crs", "cic", "cis",
    )

    /**
     * One broadcast element set, backed by a flat [DoubleArray] in [ORDER].
     *
     * Flat because the solver hands the fit a raw parameter vector 2196 times over and a boxed map
     * per residual evaluation would dominate the run.
     */
    class Elements(val values: DoubleArray = DoubleArray(N)) {
        var toe: Double = 0.0

        init {
            require(values.size == N) { "an element set has $N parameters" }
        }

        var sqrtA: Double
            get() = values[SQRT_A]
            set(x) { values[SQRT_A] = x }
        var e: Double
            get() = values[ECC]
            set(x) { values[ECC] = x }
        var i0: Double
            get() = values[I0]
            set(x) { values[I0] = x }
        var omega0: Double
            get() = values[OMEGA0]
            set(x) { values[OMEGA0] = x }
        var omega: Double
            get() = values[OMEGA]
            set(x) { values[OMEGA] = x }
        var m0: Double
            get() = values[M0]
            set(x) { values[M0] = x }
        var deltaN: Double
            get() = values[DELTA_N]
            set(x) { values[DELTA_N] = x }
        var omegaDot: Double
            get() = values[OMEGA_DOT]
            set(x) { values[OMEGA_DOT] = x }
        var iDot: Double
            get() = values[I_DOT]
            set(x) { values[I_DOT] = x }
        var cuc: Double
            get() = values[CUC]
            set(x) { values[CUC] = x }
        var cus: Double
            get() = values[CUS]
            set(x) { values[CUS] = x }
        var crc: Double
            get() = values[CRC]
            set(x) { values[CRC] = x }
        var crs: Double
            get() = values[CRS]
            set(x) { values[CRS] = x }
        var cic: Double
            get() = values[CIC]
            set(x) { values[CIC] = x }
        var cis: Double
            get() = values[CIS]
            set(x) { values[CIS] = x }

        fun copyFrom(x: DoubleArray) = System.arraycopy(x, 0, values, 0, N)

        fun copy(): Elements = Elements(values.copyOf()).also { it.toe = toe }
    }

    /** A position-only propagator: the band's algorithm for one constellation. */
    fun interface Propagator {
        fun position(el: Elements, t: Double, out: DoubleArray)
    }

    /** Osculating elements from one ECEF state — the fit's starting point. */
    fun interface Seeder {
        fun seed(p: DoubleArray, v: DoubleArray, toe: Double, tow: Double): Elements
    }

    /**
     * Python's float `%`, which is a FLOOR modulo — Kotlin's is a truncated one.
     *
     * `(tk + 302400) % 604800` and [wrap] both rely on the result taking the sign of the divisor.
     * With Kotlin's `%` a negative `tk` folds to the wrong half-week and the satellite lands on the
     * far side of its orbit.
     */
    internal fun pyMod(x: Double, y: Double): Double {
        var r = x % y
        if (r != 0.0 && (r < 0.0) != (y < 0.0)) r += y
        return r
    }

    /**
     * Fold an angle into [−π, π).
     *
     * Not cosmetic: the broadcast convention carries Ω0 referenced to the week start, so a fit hands
     * back values like 31.4 rad — ten times π. The field is a signed 32-bit count of 2⁻³¹
     * semicircles, so anything outside ±π SATURATES, and a saturated Ω0 puts the satellite on the
     * far side of its orbit: 27 000 km of error from a set whose own fit residual was under a metre.
     * Everything the propagator does with these angles goes through a sine or a cosine, so folding
     * is free.
     */
    fun wrap(a: Double): Double = pyMod(a + PI, 2 * PI) - PI

    /** Standard broadcast Kepler propagation, ECEF. [t] is GPS seconds of week. */
    fun propagate(el: Elements, t: Double, out: DoubleArray) {
        kepler(el, t, MU, OMEGA_E, out)
    }

    /**
     * BeiDou broadcast propagation, ECEF. [t] is BDT seconds of week.
     *
     * [geo] picks the variant the ICD applies to the four geostationary satellites: their Ω_k drops
     * the −ω_e·t_k term, and the result is turned by R_X(−5°) and R_Z(ω_e·t_k). That is why Huawei's
     * four GEOs carry i0 of 3–6° rather than the fraction of a degree they actually fly at. THE SIGN
     * IS SETTLED BY MEASUREMENT: with this one Huawei's own C01–C04 records read 5–10 m against
     * Wuhan's orbits, with the other 2.5–7.2 Mm, and with no rotation at all 1.3–3.6 Mm.
     */
    fun propagateBds(el: Elements, t: Double, geo: Boolean, out: DoubleArray) {
        if (!geo) {
            kepler(el, t, BDS_MU, BDS_OMEGA, out)
            return
        }
        val tk = pyMod(t - el.toe + 302400.0, 604800.0) - 302400.0
        keplerGeo(el, t, out)
        val cp = cos(BDS_GEO_TILT)
        val sp = sin(BDS_GEO_TILT)
        val y1 = out[1] * cp - out[2] * sp
        val z1 = out[1] * sp + out[2] * cp
        val q = BDS_OMEGA * tk
        val gx = out[0]
        out[0] = gx * cos(q) + y1 * sin(q)
        out[1] = -gx * sin(q) + y1 * cos(q)
        out[2] = z1
    }

    /** The tilt the BeiDou ICD applies to a GEO record, as an angle. */
    private val BDS_GEO_TILT = Math.toRadians(5.0)

    private fun kepler(el: Elements, t: Double, mu: Double, omegaE: Double, out: DoubleArray) {
        val a = el.sqrtA * el.sqrtA
        val tk = pyMod(t - el.toe + 302400.0, 604800.0) - 302400.0
        val n = sqrt(mu / Math.pow(a, 3.0)) + el.deltaN
        val m = el.m0 + n * tk
        val ecc = el.e
        var eAnom = m
        for (unused in 0 until 25) {
            eAnom -= (eAnom - ecc * sin(eAnom) - m) / (1 - ecc * cos(eAnom))
        }
        val v = atan2(sqrt(1 - ecc * ecc) * sin(eAnom), cos(eAnom) - ecc)
        val phi = v + el.omega
        val s2 = sin(2 * phi)
        val c2 = cos(2 * phi)
        val u = phi + el.cus * s2 + el.cuc * c2
        val r = a * (1 - ecc * cos(eAnom)) + el.crs * s2 + el.crc * c2
        val i = el.i0 + el.cis * s2 + el.cic * c2 + el.iDot * tk
        val xp = r * cos(u)
        val yp = r * sin(u)
        val om = el.omega0 + (el.omegaDot - omegaE) * tk - omegaE * el.toe
        out[0] = xp * cos(om) - yp * cos(i) * sin(om)
        out[1] = xp * sin(om) + yp * cos(i) * cos(om)
        out[2] = yp * sin(i)
    }

    /** The same, with the GEO variant's Ω_k, before the tilt and spin are applied. */
    private fun keplerGeo(el: Elements, t: Double, out: DoubleArray) {
        val a = el.sqrtA * el.sqrtA
        val tk = pyMod(t - el.toe + 302400.0, 604800.0) - 302400.0
        val n = sqrt(BDS_MU / Math.pow(a, 3.0)) + el.deltaN
        val m = el.m0 + n * tk
        val ecc = el.e
        var eAnom = m
        for (unused in 0 until 25) {
            eAnom -= (eAnom - ecc * sin(eAnom) - m) / (1 - ecc * cos(eAnom))
        }
        val v = atan2(sqrt(1 - ecc * ecc) * sin(eAnom), cos(eAnom) - ecc)
        val phi = v + el.omega
        val s2 = sin(2 * phi)
        val c2 = cos(2 * phi)
        val u = phi + el.cus * s2 + el.cuc * c2
        val r = a * (1 - ecc * cos(eAnom)) + el.crs * s2 + el.crc * c2
        val i = el.i0 + el.cis * s2 + el.cic * c2 + el.iDot * tk
        val xp = r * cos(u)
        val yp = r * sin(u)
        val om = el.omega0 + el.omegaDot * tk - BDS_OMEGA * el.toe
        out[0] = xp * cos(om) - yp * cos(i) * sin(om)
        out[1] = xp * sin(om) + yp * cos(i) * cos(om)
        out[2] = yp * sin(i)
    }

    /**
     * Undo the GEO tilt so a geostationary satellite can be seeded with the same extraction.
     *
     * The forward map is `r_ecef = R_Z(ω_e t_k) · A · g` with A the 5° tilt; at `t_k = 0` that
     * leaves `g = A⁻¹ r` and, differentiating, `ġ = A⁻¹ (v + ω × r)` — the inertial velocity, tilted.
     */
    fun geoFrame(
        p: DoubleArray,
        v: DoubleArray,
        outP: DoubleArray,
        outV: DoubleArray,
        omegaE: Double = BDS_OMEGA,
    ) {
        val cp = cos(BDS_GEO_TILT)
        val sp = sin(BDS_GEO_TILT)
        val vix = v[0] + (-omegaE * p[1])
        val viy = v[1] + (omegaE * p[0])
        val viz = v[2]
        outP[0] = p[0]
        outP[1] = cp * p[1] + sp * p[2]
        outP[2] = -sp * p[1] + cp * p[2]
        val gvx = vix
        val gvy = cp * viy + sp * viz
        val gvz = -sp * viy + cp * viz
        outV[0] = gvx - (-omegaE * outP[1])
        outV[1] = gvy - (omegaE * outP[0])
        outV[2] = gvz
    }

    /**
     * Osculating Kepler elements from one ECEF state.
     *
     * The reference rotates ECEF to an inertial-like frame at toe first — with a rotation angle of
     * `omega_e * 0.0`, so the rotation is the identity and only the ω × r term of the velocity
     * survives. It is written out here rather than dressed up as a matrix, but it is the same map,
     * and [MU] is deliberately GPS's for every constellation, exactly as the reference has it: the
     * fit absorbs whatever this gets slightly wrong.
     */
    fun seedElements(
        p: DoubleArray,
        v: DoubleArray,
        toe: Double,
        tow: Double,
        omegaE: Double = OMEGA_E,
    ): Elements {
        val rx = p[0]
        val ry = p[1]
        val rz = p[2]
        val vx = v[0] - omegaE * p[1]
        val vy = v[1] + omegaE * p[0]
        val vz = v[2]
        val rn = sqrt(rx * rx + ry * ry + rz * rz)
        val hx = ry * vz - rz * vy
        val hy = rz * vx - rx * vz
        val hz = rx * vy - ry * vx
        val hn = sqrt(hx * hx + hy * hy + hz * hz)
        val ex = (vy * hz - vz * hy) / MU - rx / rn
        val ey = (vz * hx - vx * hz) / MU - ry / rn
        val ez = (vx * hy - vy * hx) / MU - rz / rn
        val e = sqrt(ex * ex + ey * ey + ez * ez)
        val vv = vx * vx + vy * vy + vz * vz
        val a = 1.0 / (2.0 / rn - vv / MU)
        val i0 = acos(clamp1(hz / hn))
        val nx = -hy
        val ny = hx
        val nn = sqrt(nx * nx + ny * ny)
        val om = if (nn > 0) atan2(ny, nx) else 0.0
        var w = if (nn > 0 && e > 0) acos(clamp1((nx * ex + ny * ey) / (nn * e))) else 0.0
        if (ez < 0) w = 2 * PI - w
        var nu = if (e > 0) acos(clamp1((ex * rx + ey * ry + ez * rz) / (e * rn))) else 0.0
        if (rx * vx + ry * vy + rz * vz < 0) nu = 2 * PI - nu
        val eAnom = 2 * atan2(tan(nu / 2) * sqrt(1 - e), sqrt(1 + e))
        val m = eAnom - e * sin(eAnom)
        val el = Elements()
        el.sqrtA = sqrt(a)
        el.e = e
        el.i0 = i0
        el.omega0 = om + omegaE * toe
        el.omega = w
        el.m0 = m
        el.deltaN = 0.0
        el.omegaDot = -2.5e-9
        el.iDot = 0.0
        el.toe = toe
        return el
    }

    private fun clamp1(x: Double): Double = if (x < -1.0) -1.0 else if (x > 1.0) 1.0 else x

    // ── GLONASS ──────────────────────────────────────────────────────────────────────────────────

    const val GLO_MU = 3.986004418e14
    const val GLO_AE = 6378136.0
    const val GLO_J2 = 1.0826257e-3

    /**
     * The part of the acceleration the band does NOT model itself, written into [out].
     *
     * Its integrator adds central gravity, J2, and the rotating-frame terms; the record carries only
     * the luni-solar residual on top, which is why Huawei's own files sit around 3 µm/s². Storing
     * the TOTAL acceleration instead overruns the field — 8 signed bits of 2⁻³⁰ km/s² stop at
     * 118 µm/s² — so it saturates and is wrong in the bargain: 205 µm/s² clipped, against their 3.09.
     */
    fun luniSolar(p: DoubleArray, v: DoubleArray, aTotal: DoubleArray, out: DoubleArray) {
        val x = p[0]
        val y = p[1]
        val z = p[2]
        val r = sqrt(x * x + y * y + z * z)
        val mr = GLO_MU / (r * r)
        val rho = GLO_AE / r
        val k = 1.5 * GLO_J2 * mr * rho * rho
        val om = OMEGA_E
        out[0] = aTotal[0] -
            (-mr * (x / r) + k * (x / r) * (5 * z * z / (r * r) - 1) + om * om * x + 2 * om * v[1])
        out[1] = aTotal[1] -
            (-mr * (y / r) + k * (y / r) * (5 * z * z / (r * r) - 1) + om * om * y - 2 * om * v[0])
        out[2] = aTotal[2] -
            (-mr * (z / r) + k * (z / r) * (5 * z * z / (r * r) - 3))
    }

    // ── the fit ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The characteristic scale of each parameter, in [ORDER]. The solver's steps, its convergence
     * tests and its finite differences are all measured in these units — without them a
     * fifteen-parameter vector whose entries span 5153 down to 1e-12 cannot be solved at all.
     */
    val SCALE = doubleArrayOf(
        1e-3, 1e-9, 1e-9, 1e-9, 1e-9, 1e-9, 1e-12, 1e-12, 1e-12,
        1e-8, 1e-8, 1e-3, 1e-3, 1e-8, 1e-8,
    )

    /** `(2^16 − 1)` counts of 2⁻⁴³ semicircles — the largest Δn that survives the round trip. */
    val DELTA_N_MAX = (65535.0) * PI / 8_796_093_022_208.0

    /**
     * Bound every parameter to what its FIELD can hold, not to what the orbit could want.
     *
     * An unconstrained fit is free to drive idot — a 16-bit count of 2⁻⁴³ semicircles, so
     * |idot| ≤ 1.17e-8 rad/s — far past its ceiling and let the encoder clip it: 112 of 1116 GPS
     * records did exactly that, and clipping a rate turns a sub-metre fit into 1.9 km of error at
     * the edge of the slice.
     *
     * The three ANGLES are deliberately unbounded. Ω0 is referenced to the week start, so it
     * legitimately reaches 30-odd radians; bounding it at ±4π clipped good solutions and threw away
     * 631 of 2196 sets. They cost nothing to leave free because [wrap] folds them at encode time.
     *
     * e goes to 0.30, not the 0.05 a nominal MEO needs, because Galileo E14 and E18 were launched
     * into wrong, highly elliptical orbits (e ≈ 0.16) and bounding e at 0.05 dropped both.
     *
     * Δn is bounded NON-NEGATIVE, which is a stronger claim than "what the field holds". Huawei's
     * own 1044 GPS sets are every one positive, and bytes 14-15 are zero in every record of every
     * constellation they ship; ours drove Δn negative in 47 of 1116, which sign-extends 0xFFFF into
     * those two bytes — a pattern their files do not contain. Whether the field is 32 bits or 16
     * with something else beside it cannot be told from data where the top half is always zero, and
     * non-negative-and-inside-16-bits is correct under both readings.
     */
    val LOWER = doubleArrayOf(
        4.0e3, 0.0, -PI, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Double.NEGATIVE_INFINITY, 0.0, -7.6e-4, -1.17e-8,
        -6.10e-5, -6.10e-5, -1023.0, -1023.0, -6.10e-5, -6.10e-5,
    )
    val UPPER = doubleArrayOf(
        7.0e3, 0.30, PI, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY, DELTA_N_MAX, 7.6e-4, 1.17e-8,
        6.10e-5, 6.10e-5, 1023.0, 1023.0, 6.10e-5, 6.10e-5,
    )

    /**
     * What the BeiDou fields can hold, in the same order.
     *
     * Every one is the ICD's own field width, cross-checked against the widest value in Huawei's two
     * captured vintages: Δn reaches 65308 of its 65535 counts and is never negative, idot stays
     * inside a signed 16 at ±3637, cuc/cus reach 74553 of 131071, crc/crs 68689, cic/cis 4018.
     */
    val BDS_LOWER = doubleArrayOf(
        4.0e3, 0.0, -PI, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Double.NEGATIVE_INFINITY, 0.0, -3.0e-6, -1.1702e-8,
        -6.104e-5, -6.104e-5, -2048.0, -2048.0, -6.104e-5, -6.104e-5,
    )
    val BDS_UPPER = doubleArrayOf(
        7.0e3, 0.30, PI, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY, DELTA_N_MAX, 3.0e-6, 1.1702e-8,
        6.104e-5, 6.104e-5, 2048.0, 2048.0, 6.104e-5, 6.104e-5,
    )

    /**
     * A record worse than this is dropped rather than shipped.
     *
     * AGNSS wants tens of metres; a fit that cannot reach 50 m has not converged at all, and a
     * diverged Kepler set does not degrade gracefully — it puts the satellite on the far side of its
     * orbit and would send the receiver hunting in the wrong place. A block with one satellite fewer
     * costs nothing: Huawei's own GPS blocks carry 29.
     */
    const val MAX_ERROR_M = 50.0

    class FitResult(val el: Elements, val rms: Double, val iterations: Int, val evaluations: Int)

    /**
     * Fit one element set to the precise orbit over `[toe − half, toe + half]`.
     *
     * The residual is evaluated with [propagator] — the band's own algorithm — so what is minimised
     * is the error the BAND will see, not the error of some tidier model.
     *
     * Unbounded first: it is faster and nine records in ten land inside the fields anyway. Only the
     * ones that would CLIP are refitted under bounds, which is where the cost is worth paying.
     */
    fun fit(
        arc: Sp3.Arc,
        toeAbs: Double,
        toeTow: Double,
        half: Double = 3600.0,
        samples: Int = 25,
        propagator: Propagator = Propagator { el, t, out -> propagate(el, t, out) },
        seeder: Seeder = Seeder { p, v, toe, tow -> seedElements(p, v, toe, tow) },
        lower: DoubleArray = LOWER,
        upper: DoubleArray = UPPER,
    ): FitResult {
        val ts = DoubleArray(samples)
        val step = (2.0 * half) / (samples - 1)
        for (i in 0 until samples) ts[i] = (toeAbs - half) + step * i
        ts[samples - 1] = toeAbs + half

        val truth = DoubleArray(3 * samples)
        val tmp = DoubleArray(3)
        for (i in 0 until samples) {
            Sp3.interpolatePosition(arc, ts[i], tmp)
            truth[3 * i] = tmp[0]
            truth[3 * i + 1] = tmp[1]
            truth[3 * i + 2] = tmp[2]
        }
        val tows = DoubleArray(samples) { toeTow + (ts[it] - toeAbs) }

        val p = DoubleArray(3)
        val v = DoubleArray(3)
        Sp3.stateAt(arc, toeAbs, p, v)
        val seed = seeder.seed(p, v, toeTow, toeTow)
        val work = Elements()
        work.toe = toeTow

        val residual = LeastSquares.Residuals { x, out ->
            work.copyFrom(x)
            for (i in 0 until samples) {
                propagator.position(work, tows[i], tmp)
                out[3 * i] = tmp[0] - truth[3 * i]
                out[3 * i + 1] = tmp[1] - truth[3 * i + 1]
                out[3 * i + 2] = tmp[2] - truth[3 * i + 2]
            }
        }

        var sol = LeastSquares.minimise(seed.values.copyOf(), 3 * samples, residual, SCALE)
        var evaluations = sol.evaluations
        var iterations = sol.iterations
        if (sol.x.indices.any { sol.x[it] < lower[it] || sol.x[it] > upper[it] }) {
            val start = DoubleArray(N) {
                val loI = if (lower[it].isFinite()) lower[it] + 1e-12 else lower[it]
                val hiI = if (upper[it].isFinite()) upper[it] - 1e-12 else upper[it]
                if (sol.x[it] < loI) loI else if (sol.x[it] > hiI) hiI else sol.x[it]
            }
            sol = LeastSquares.minimise(
                start, 3 * samples, residual, SCALE, lower, upper, maxIterations = 400,
            )
            evaluations += sol.evaluations
            iterations += sol.iterations
        }
        val out = Elements(sol.x)
        out.toe = toeTow
        var sum = 0.0
        for (r in sol.residuals) sum += r * r
        return FitResult(out, sqrt(sum / sol.residuals.size * 3), iterations, evaluations)
    }

    /**
     * The worst position error over the slice this set is responsible for, against the precise orbit.
     *
     * Every set is checked with this before it is allowed out. The fit is a fifteen-parameter
     * non-linear solve on someone else's model and a few of them do diverge — one run produced a
     * 27 800 km set among 2196 — and a residual reported by the solver is not evidence, because a
     * solver that has wandered off reports its own happiness.
     */
    fun checkError(
        el: Elements,
        arc: Sp3.Arc,
        toeAbs: Double,
        toeTow: Double,
        propagator: Propagator = Propagator { e, t, out -> propagate(e, t, out) },
    ): Double {
        val got = DoubleArray(3)
        val want = DoubleArray(3)
        var worst = 0.0
        for (dt in doubleArrayOf(-3600.0, -1800.0, 0.0, 1800.0, 3600.0)) {
            propagator.position(el, toeTow + dt, got)
            Sp3.interpolatePosition(arc, toeAbs + dt, want)
            val dx = got[0] - want[0]
            val dy = got[1] - want[1]
            val dz = got[2] - want[2]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d > worst) worst = d
        }
        return worst
    }

    /** af0/af1 from the predicted clock, or zeros when the product published none. */
    fun clockFit(arc: Sp3.Arc, toeAbs: Double, half: Double = 3600.0): DoubleArray {
        var n = 0
        for (i in 0 until arc.size) {
            if (arc.t[i] >= toeAbs - half && arc.t[i] <= toeAbs + half && arc.clock[i].isFinite()) n++
        }
        if (n < 3) return doubleArrayOf(0.0, 0.0)
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        var k = 0
        for (i in 0 until arc.size) {
            if (arc.t[i] >= toeAbs - half && arc.t[i] <= toeAbs + half && arc.clock[i].isFinite()) {
                xs[k] = arc.t[i] - toeAbs
                ys[k] = arc.clock[i]
                k++
            }
        }
        val fitted = linearFit(xs, ys)
        return doubleArrayOf(fitted[0], fitted[1])
    }

    /**
     * Linear clock extrapolation onto [grid], with one outlier-rejection pass.
     *
     * Orbits can be integrated; clocks cannot, so the last day of a BeiDou window carries a straight
     * line through the last day and a half of published offsets. A linear fit beats a quadratic for
     * extrapolation; the satellites whose drift changed inside the window are meant to be screened
     * out rather than shipped with a confident wrong number.
     */
    fun clockExtrapolate(arc: Sp3.Arc, grid: DoubleArray, hours: Double = 36.0): DoubleArray {
        val last = arc.t[arc.size - 1]
        val idx = ArrayList<Int>()
        for (i in 0 until arc.size) {
            if (arc.clock[i].isFinite() && arc.t[i] >= last - hours * 3600.0) idx.add(i)
        }
        if (idx.size < 10) return DoubleArray(grid.size) { Double.NaN }
        var xs = DoubleArray(idx.size) { arc.t[idx[it]] - last }
        var ys = DoubleArray(idx.size) { arc.clock[idx[it]] }
        var c = linearFit(xs, ys)
        val resid = DoubleArray(xs.size) { ys[it] - (c[0] + c[1] * xs[it]) }
        var mean = 0.0
        for (r in resid) mean += r
        mean /= resid.size
        var varSum = 0.0
        for (r in resid) varSum += (r - mean) * (r - mean)
        val sd = sqrt(varSum / resid.size)
        val keep = resid.indices.filter { abs(resid[it]) < 3 * kotlin.math.max(sd, 1e-12) }
        if (keep.size >= 10) {
            xs = DoubleArray(keep.size) { xs[keep[it]] }
            ys = DoubleArray(keep.size) { ys[keep[it]] }
            c = linearFit(xs, ys)
        }
        return DoubleArray(grid.size) { c[0] + c[1] * (grid[it] - last) }
    }

    /** `[intercept, slope]` by least squares, about the means so the normal equations stay tame. */
    internal fun linearFit(xs: DoubleArray, ys: DoubleArray): DoubleArray {
        val n = xs.size
        var mx = 0.0
        var my = 0.0
        for (i in 0 until n) {
            mx += xs[i]
            my += ys[i]
        }
        mx /= n
        my /= n
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            sxy += dx * (ys[i] - my)
            sxx += dx * dx
        }
        val slope = if (sxx == 0.0) 0.0 else sxy / sxx
        return doubleArrayOf(my - slope * mx, slope)
    }

    /**
     * `"GEO"`, `"IGSO"` or `"MEO"` from the orbit itself, at time [t].
     *
     * Huawei's split is reproduced exactly by this rule on their own records: C01–C04 are the four
     * geostationary satellites and take the GEO propagation variant, C09 and C10 are inclined
     * geosynchronous and take the ordinary one, and everything at 27 906 km is MEO.
     */
    fun bdsKind(arc: Sp3.Arc, t: Double): String {
        val p = DoubleArray(3)
        val v = DoubleArray(3)
        Sp3.stateAt(arc, t, p, v)
        val el = seedElements(p, v, 0.0, 0.0, omegaE = BDS_OMEGA)
        val aKm = el.sqrtA * el.sqrtA / 1000.0
        val inc = Math.toDegrees(el.i0)
        if (aKm < 40000) return "MEO"
        return if (inc < 20.0) "GEO" else "IGSO"
    }
}
