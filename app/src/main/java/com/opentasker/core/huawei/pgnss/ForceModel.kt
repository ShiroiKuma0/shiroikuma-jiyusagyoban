package com.opentasker.core.huawei.pgnss

import java.io.BufferedReader
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Constants shared by the whole predicted-ephemeris propagator.
 *
 * [OMEGA_ERA] is the one to look at twice. It is `d(ERA)/d(UT1) = 2*pi*1.00273781191135448/86400`,
 * and it is **not** the IERS "nominal mean" 7.292115e-5 that every textbook prints. The two differ
 * by 1.47e-12 rad/s — two parts in 1e8, which sounds like nothing. In the rotating frame the rate
 * enters the Coriolis term, so the error is `2*dw*v = 9e-9 m/s^2`, and an unmodelled constant
 * acceleration grows as t^2: 0.2 m over a 2-hour arc, 1.9 m over 6 hours, 30 m over 24. Fitting a
 * 24-hour arc with the nominal value plateaued at exactly 30 m no matter how many iterations,
 * degrees of geopotential or radiation-pressure coefficients were thrown at it.
 */
object PgnssConstants {
    /** m^3/s^2, IERS/IGS conventional value. */
    const val GM_EARTH = 3.986004418e14
    const val GM_SUN = 1.32712440018e20
    const val GM_MOON = 4.9028000661e12

    /** m — EGM96's own reference radius, from its header. Must match the field being used. */
    const val RE = 6378136.3

    /** rad/s — see the class note. */
    const val OMEGA_ERA = 7.292115146706979e-5

    const val C_LIGHT = 299792458.0
    const val AU = 1.49597870700e11
    const val R_SUN = 6.957e8
    const val R_EARTH_SHADOW = 6378137.0

    /** Nominal degree-2 Love number, IERS Conventions. */
    const val K2_LOVE = 0.30

    /** 1980-01-06 00:00:00 UTC, as a Julian date. */
    const val GPS_EPOCH_JD = 2444244.5

    /** GPS - UTC, seconds, as of 2026. */
    const val LEAP = 18.0

    /** TT = TAI + 32.184 = GPS + 19 + 32.184. */
    const val TT_MINUS_GPS = 51.184

    const val DEG = PI / 180.0
    const val ARCSEC = DEG / 3600.0

    /** Degree and order of geopotential the reference implementation is measured at. */
    const val NMAX_DEFAULT = 12
}

/**
 * Sun and Moon, in the same Earth-fixed frame the integration runs in.
 *
 * ## The trap both of these fell into
 * **Both series are referred to the fixed mean equinox of J2000, not to the equinox of date**, and
 * neither says so on its face. It is visible only in the difference between their mean-longitude
 * *rate* and the of-date rate — the Moon's `481267.88088 - 1.3972` per century is the of-date rate
 * less the general precession. Treating a J2000 longitude as of-date is worth 1341 arcseconds today,
 * a third of a degree of Sun and Moon direction. The reference implementation measured both against
 * JPL Horizons before and after; the correction is the `+ (5029.0966 T + 1.11113 T^2)` arcseconds
 * applied in [eclipticToEcef] when `j2000` is set.
 *
 * The solar series here is the Astronomical Almanac's low-precision form, which *is* of-date. It
 * replaced Montenbruck & Gill's, which is J2000-referred and, as usually transcribed, runs 314
 * arcseconds slow in mean longitude against Horizons at every epoch tested.
 */
object Ephemeris {

    /** Julian centuries of TT since J2000, from a GPS second. */
    fun ttCenturies(tGps: Double): Double =
        (PgnssConstants.GPS_EPOCH_JD + (tGps + PgnssConstants.TT_MINUS_GPS) / 86400.0 - 2451545.0) / 36525.0

    /**
     * Greenwich mean sidereal time, radians.
     *
     * UT1 is approximated by UTC. That is 6 ms today, 0.03 m of Sun direction at the satellite —
     * irrelevant here, and quite different from the rotation *rate*, which is not approximated
     * (see [EarthOrientation.omega]).
     */
    fun gmst(tGps: Double): Double {
        val jdUt1 = PgnssConstants.GPS_EPOCH_JD + (tGps - PgnssConstants.LEAP) / 86400.0
        val tu = (jdUt1 - 2451545.0) / 36525.0
        val s = 67310.54841 + (876600.0 * 3600.0 + 8640184.812866) * tu +
            0.093104 * tu * tu - 6.2e-6 * tu * tu * tu
        var r = (s.mod(86400.0)) / 86400.0 * 2.0 * PI
        r = r.mod(2.0 * PI)
        return r
    }

    /**
     * Ecliptic spherical -> Earth-fixed cartesian, via the obliquity of date and GMST.
     *
     * [j2000] says the longitude is referred to the fixed mean equinox of J2000 rather than to the
     * equinox of date, in which case the general precession in longitude is added first.
     */
    fun eclipticToEcef(lonIn: Double, lat: Double, dist: Double, tGps: Double, j2000: Boolean, out: DoubleArray) {
        val t = ttCenturies(tGps)
        val lon = if (j2000) lonIn + (5029.0966 * t + 1.11113 * t * t) * PgnssConstants.ARCSEC else lonIn
        val eps = (84381.448 - 46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t) * PgnssConstants.ARCSEC
        val cl = cos(lon)
        val sl = sin(lon)
        val cb = cos(lat)
        val sb = sin(lat)
        val xe = dist * cb * cl
        val ye = dist * (cb * sl * cos(eps) - sb * sin(eps))
        val ze = dist * (cb * sl * sin(eps) + sb * cos(eps))
        val th = gmst(tGps)
        out[0] = xe * cos(th) + ye * sin(th)
        out[1] = -xe * sin(th) + ye * cos(th)
        out[2] = ze
    }

    /** Solar position in the Earth-fixed frame, metres. Astronomical Almanac low-precision, 0.01 deg. */
    fun sunEcef(tGps: Double, out: DoubleArray) {
        val n = PgnssConstants.GPS_EPOCH_JD + (tGps + PgnssConstants.TT_MINUS_GPS) / 86400.0 - 2451545.0
        val l = (280.460 + 0.9856474 * n) * PgnssConstants.DEG
        val g = (357.528 + 0.9856003 * n) * PgnssConstants.DEG
        val lon = l + (1.915 * sin(g) + 0.020 * sin(2 * g)) * PgnssConstants.DEG
        val dist = (1.00014 - 0.01671 * cos(g) - 0.00014 * cos(2 * g)) * PgnssConstants.AU
        eclipticToEcef(lon, 0.0, dist, tGps, false, out)
    }

    /**
     * Lunar position in the Earth-fixed frame, metres. Truncated series, Montenbruck & Gill 3.3.2,
     * **J2000 equinox**. Better than 90 arcsec in direction and 300 km in range against Horizons over
     * the days this is used on.
     */
    fun moonEcef(tGps: Double, out: DoubleArray) {
        val t = ttCenturies(tGps)
        val d2r = PgnssConstants.DEG
        val l0 = (218.31617 + 481267.88088 * t - 1.3972 * t) * d2r
        val l = (134.96292 + 477198.86753 * t) * d2r
        val lp = (357.52543 + 35999.04944 * t) * d2r
        val f = (93.27283 + 483202.01873 * t) * d2r
        val d = (297.85027 + 445267.11135 * t) * d2r
        val dl = 22640 * sin(l) + 769 * sin(2 * l) - 4586 * sin(l - 2 * d) + 2370 * sin(2 * d) -
            668 * sin(lp) - 412 * sin(2 * f) - 212 * sin(2 * l - 2 * d) - 206 * sin(l + lp - 2 * d) +
            192 * sin(l + 2 * d) - 165 * sin(lp - 2 * d) + 148 * sin(l - lp) - 125 * sin(d) -
            110 * sin(l + lp) - 55 * sin(2 * f - 2 * d)
        val lon = l0 + dl * PgnssConstants.ARCSEC
        val arg = f + (dl + 412 * sin(2 * f) + 541 * sin(lp)) * PgnssConstants.ARCSEC
        val lat = (18520 * sin(arg) - 526 * sin(f - 2 * d) + 44 * sin(l + f - 2 * d) -
            31 * sin(-l + f - 2 * d) - 25 * sin(-2 * l + f) - 23 * sin(lp + f - 2 * d) +
            21 * sin(-l + f) + 11 * sin(-lp + f - 2 * d)) * PgnssConstants.ARCSEC
        val dist = 385000e3 - 20905e3 * cos(l) - 3699e3 * cos(2 * d - l) - 2956e3 * cos(2 * d) -
            570e3 * cos(2 * l) + 246e3 * cos(2 * l - 2 * d) - 205e3 * cos(lp - 2 * d) -
            171e3 * cos(l + 2 * d) - 152e3 * cos(l + lp - 2 * d)
        eclipticToEcef(lon, lat, dist, tGps, true, out)
    }
}

/**
 * The frame the integration actually runs in, and that frame's rotation rate.
 *
 * ## THE FRAME IS NOT ITRF
 * It is the terrestrial intermediate frame, whose z-axis is the Earth's *actual* rotation axis.
 * ITRF's z-axis misses it by the polar motion, 0.2-0.3 arcseconds today. That sounds ignorable and
 * is the single largest error in this file if it is ignored, because the rotating-frame equations
 * single out the rotation axis: writing them about ITRF's z instead of the real one leaves a
 * spurious Coriolis term
 *
 *     |2 dw x v| = 2 * OMEGA_E * 1.95e-6 * 3070 = 8.8e-7 m/s^2
 *
 * which is **seven times** solar radiation pressure. Measured with no fitting anywhere: a state read
 * off CODE's five-day prediction and propagated with everything else modelled diverged from it by
 * 1.19 m in 30 minutes and 82 m in 4 hours. A fit cannot absorb it — a tilt of the frame is a
 * symmetry of neither the centrifugal term, the Coriolis term, nor the flattening.
 *
 * So positions go in through [toTirs] and come back out through [toItrs], and the integration lives
 * in between.
 *
 * ## The rate
 * [omega] is `d(ERA)/dt` corrected by the length of day, which is a 1e-8 relative effect and worth
 * tens of metres over three days.
 *
 * Both come from an Earth-orientation file. CODE publishes a 21-day *predicted* one beside the orbit
 * prediction, so this needs nothing that is not already being downloaded. Without one the class
 * falls back to a zero pole and a nominal rate, and [available] says so rather than pretending.
 *
 * Immutable once built, and therefore **safe to share across threads** — see [poleX].
 */
class EarthOrientation private constructor(
    private val mjd: DoubleArray?,
    private val xp: DoubleArray?,
    private val yp: DoubleArray?,
    private val lod: DoubleArray?,
) {

    companion object {
        /** An empty series: zero pole, nominal rate. [available] is false. */
        fun none(): EarthOrientation = EarthOrientation(null, null, null, null)

        /** Read one or more CODE-style `.ERP` files; later rows win where they overlap. */
        fun read(files: List<File>): EarthOrientation {
            val rows = sortedMapOf<Double, DoubleArray>()
            for (f in files) f.bufferedReader().use { collect(it, rows) }
            return build(rows)
        }

        /** Parse already-open readers, in order; later rows win where they overlap. */
        fun parse(readers: List<BufferedReader>): EarthOrientation {
            val rows = sortedMapOf<Double, DoubleArray>()
            for (r in readers) collect(r, rows)
            return build(rows)
        }

        private val WHITESPACE = Regex("\\s+")

        /**
         * One `.ERP` file into [rows].
         *
         * The columns taken are MJD, X-P and Y-P in units of 1e-6 arcseconds, and LOD in 1e-7
         * seconds per day. Anything that does not parse, or whose first field is not a plausible
         * MJD, is skipped — which is how the multi-line header, the units line and the dashes are
         * rejected without having to recognise them.
         */
        private fun collect(reader: BufferedReader, rows: MutableMap<Double, DoubleArray>) {
            var line = reader.readLine()
            while (line != null) {
                val f = line.trim().split(WHITESPACE)
                if (f.size >= 5) {
                    val m = f[0].toDoubleOrNull()
                    if (m != null && m > 40000.0 && m < 90000.0) {
                        val x = f[1].toDoubleOrNull()
                        val y = f[2].toDoubleOrNull()
                        val l = f[4].toDoubleOrNull()
                        if (x != null && y != null && l != null) {
                            rows[m] = doubleArrayOf(x * 1e-6, y * 1e-6, l * 1e-7)
                        }
                    }
                }
                line = reader.readLine()
            }
        }

        private fun build(rows: Map<Double, DoubleArray>): EarthOrientation {
            if (rows.isEmpty()) return none()
            val n = rows.size
            val mjd = DoubleArray(n)
            val xp = DoubleArray(n)
            val yp = DoubleArray(n)
            val lod = DoubleArray(n)
            var i = 0
            for ((m, v) in rows) {
                mjd[i] = m
                xp[i] = v[0]
                yp[i] = v[1]
                lod[i] = v[2]
                i++
            }
            return EarthOrientation(mjd, xp, yp, lod)
        }
    }

    fun available(): Boolean = mjd != null

    /** First and last MJD of the series, or `null` when there is none. */
    fun span(): Pair<Double, Double>? = mjd?.let { it[0] to it[it.size - 1] }

    /** MJD of a GPS second. */
    private fun toMjd(t: Double): Double =
        (PgnssConstants.GPS_EPOCH_JD - 2400000.5) + (t - PgnssConstants.LEAP) / 86400.0

    /**
     * Does the series span `[t0, t1]` in GPS seconds? **Ask before integrating anything.**
     *
     * Outside the series the interpolation does not fail, it CLAMPS to the end value — so a window
     * that runs past the file gets yesterday's pole held constant and no complaint. That is the
     * quiet-wrong-answer shape this whole file exists to avoid.
     */
    fun covers(t0: Double, t1: Double, marginDays: Double = 0.0): Boolean {
        val m = mjd ?: return false
        return m[0] - marginDays <= toMjd(t0) && toMjd(t1) <= m[m.size - 1] + marginDays
    }

    /**
     * Polar motion x, in radians, at GPS second [t]. Zero when there is no series.
     *
     * The two components are read one at a time, and nothing here writes to a field. **This class
     * holds no mutable state at all**, which is deliberate: the BeiDou build fits thirty-odd
     * satellites in parallel off one Earth-orientation series, and a scratch buffer shared between
     * those threads would let one interleave its pole read with another's rotation and quietly turn
     * a satellite by somebody else's polar motion — a wrong answer with no exception and no seam.
     * One instance may be shared by any number of threads.
     */
    fun poleX(t: Double): Double {
        val m = mjd ?: return 0.0
        return interp(m, xp!!, toMjd(t)) * PgnssConstants.ARCSEC
    }

    /** Polar motion y, in radians, at GPS second [t]. Zero when there is no series. */
    fun poleY(t: Double): Double {
        val m = mjd ?: return 0.0
        return interp(m, yp!!, toMjd(t)) * PgnssConstants.ARCSEC
    }

    /** Both components at once, into a caller-owned [out]; see [poleX] on thread safety. */
    fun pole(t: Double, out: DoubleArray) {
        out[0] = poleX(t)
        out[1] = poleY(t)
    }

    /** Rotation rate of the terrestrial frame at GPS second [t], rad/s. */
    fun omega(t: Double): Double {
        val m = mjd ?: return PgnssConstants.OMEGA_ERA
        val l = interp(m, lod!!, toMjd(t))
        return PgnssConstants.OMEGA_ERA * (1.0 - l / 86400.0)
    }

    /** `W(t) r`, with `W = R3(-s') R2(xp) R1(yp)` to first order in the pole coordinates. */
    fun toTirs(r: DoubleArray, t: Double, out: DoubleArray) {
        val px = poleX(t)
        val py = poleY(t)
        val x = r[0]
        val y = r[1]
        val z = r[2]
        out[0] = x - px * z
        out[1] = y + py * z
        out[2] = px * x - py * y + z
    }

    /** The inverse of [toTirs]. */
    fun toItrs(r: DoubleArray, t: Double, out: DoubleArray) {
        val px = poleX(t)
        val py = poleY(t)
        val x = r[0]
        val y = r[1]
        val z = r[2]
        out[0] = x + px * z
        out[1] = y - py * z
        out[2] = -px * x + py * y + z
    }

    /** Linear interpolation with clamping at both ends, matching `numpy.interp`. */
    private fun interp(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        if (x <= xs[0]) return ys[0]
        val n = xs.size
        if (x >= xs[n - 1]) return ys[n - 1]
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val f = (x - xs[lo]) / (xs[hi] - xs[lo])
        return ys[lo] + f * (ys[hi] - ys[lo])
    }
}

/**
 * Geopotential acceleration in the Earth-fixed frame, by Cunningham's recursion on **unnormalised**
 * coefficients (Montenbruck & Gill, *Satellite Orbits*, 3.2.4).
 *
 * The field is static in this frame, which is the whole reason the integration runs here: no
 * precession, no nutation, no frame chain, and the largest force after the central term needs no
 * time argument at all.
 *
 * Not thread-safe — it owns the recursion scratch. One instance per thread; the [GravityField]
 * itself is immutable and may be shared.
 */
class GeoPotential(private val nmax: Int) {

    private val n2 = nmax + 2
    private val stride = n2 + 1
    private val v = DoubleArray(stride * stride)
    private val w = DoubleArray(stride * stride)

    /**
     * Acceleration at [rx],[ry],[rz] from the coefficients [c],[s] (triangular packing, as in
     * [GravityField]), accumulated into [out] at offset [off].
     */
    fun accel(rx: Double, ry: Double, rz: Double, c: DoubleArray, s: DoubleArray, out: DoubleArray, off: Int) {
        val re = PgnssConstants.RE
        val r2 = rx * rx + ry * ry + rz * rz
        val rho = re * re / r2
        val x0 = re * rx / r2
        val y0 = re * ry / r2
        val z0 = re * rz / r2

        v[0] = re / sqrt(r2)
        w[0] = 0.0
        for (m in 0 until n2) {
            if (m > 0) {
                val vp = v[(m - 1) * stride + (m - 1)]
                val wp = w[(m - 1) * stride + (m - 1)]
                v[m * stride + m] = (2 * m - 1) * (x0 * vp - y0 * wp)
                w[m * stride + m] = (2 * m - 1) * (x0 * wp + y0 * vp)
            }
            v[(m + 1) * stride + m] = (2 * m + 1) * z0 * v[m * stride + m]
            w[(m + 1) * stride + m] = (2 * m + 1) * z0 * w[m * stride + m]
            for (n in (m + 2)..n2) {
                val d = (n - m).toDouble()
                v[n * stride + m] =
                    ((2 * n - 1) * z0 * v[(n - 1) * stride + m] - (n + m - 1) * rho * v[(n - 2) * stride + m]) / d
                w[n * stride + m] =
                    ((2 * n - 1) * z0 * w[(n - 1) * stride + m] - (n + m - 1) * rho * w[(n - 2) * stride + m]) / d
            }
        }

        var ax = 0.0
        var ay = 0.0
        var az = 0.0
        for (n in 0..nmax) {
            val base = n * (n + 1) / 2
            val row = (n + 1) * stride
            for (m in 0..n) {
                val cnm = c[base + m]
                val snm = s[base + m]
                if (cnm == 0.0 && snm == 0.0) continue
                if (m == 0) {
                    ax -= cnm * v[row + 1]
                    ay -= cnm * w[row + 1]
                } else {
                    val f = ((n - m + 2) * (n - m + 1)).toDouble()
                    val vp = v[row + m + 1]
                    val wp = w[row + m + 1]
                    val vm = v[row + m - 1]
                    val wm = w[row + m - 1]
                    ax += 0.5 * ((-cnm * vp - snm * wp) + f * (cnm * vm + snm * wm))
                    ay += 0.5 * ((-cnm * wp + snm * vp) + f * (-cnm * wm + snm * vm))
                }
                az += (n - m + 1) * (-cnm * v[row + m] - snm * w[row + m])
            }
        }
        val k = PgnssConstants.GM_EARTH / (re * re)
        out[off] = ax * k
        out[off + 1] = ay * k
        out[off + 2] = az * k
    }
}

/**
 * The complete equation of motion, in the terrestrial frame, for a batch of satellites.
 *
 * ## What is modelled
 * * Geopotential to degree/order `nmax` (EGM96, unnormalised, Cunningham) — **static** in this frame.
 * * Centrifugal and Coriolis, from the frame's own rotation.
 * * Sun and Moon as point masses, from the analytic ephemerides in [Ephemeris].
 * * Solid Earth tide, degree 2, nominal Love number.
 * * Relativistic (Schwarzschild) correction.
 * * Solar radiation pressure, ECOM in the D/Y/B frame with a conical shadow — **fitted**, not
 *   modelled, because the area, mass and reflectivity of these craft are not published.
 *
 * ## Batching
 * A batch is how the Jacobian of the arc fit gets integrated: the nominal trajectory and one
 * perturbed trajectory per parameter, all in one run, so every column shares the same step sequence
 * and the same Sun, Moon and tide. State is `6*k` doubles, `[x, y, z, vx, vy, vz]` per satellite;
 * acceleration comes back as `3*k`.
 *
 * Not thread-safe — it owns per-step caches and scratch. One instance per thread.
 */
class ForceModel(
    field: GravityField,
    val nmax: Int = field.nmax,
    private val srp: Boolean = true,
    private val tide: Boolean = true,
    private val relativity: Boolean = true,
    private val third: Boolean = true,
    /** Used only when [frame] is null or has no series. */
    private val omegaFixed: Double = PgnssConstants.OMEGA_ERA,
    private val frame: EarthOrientation? = null,
) {
    init {
        require(nmax <= field.nmax) { "asked for degree $nmax from a field that only carries ${field.nmax}" }
    }

    private val geo = GeoPotential(nmax)
    private val cBase = field.c
    private val sBase = field.s
    private val cWork = field.copyC()
    private val sWork = field.copyS()

    private var cachedT = Double.NaN
    val sun = DoubleArray(3)
    val moon = DoubleArray(3)

    /** Sun, Moon and the tide patch, all of which depend only on time. */
    private fun at(t: Double) {
        if (t == cachedT) return
        cachedT = t
        Ephemeris.sunEcef(t, sun)
        Ephemeris.moonEcef(t, moon)
        if (tide) {
            solidTide(sun, moon, dC, dS)
            for (m in 0..2) {
                val i = 3 + m                          // triangular index of (2, m)
                cWork[i] = cBase[i] + dC[m]
                sWork[i] = sBase[i] + dS[m]
            }
        }
    }

    private val dC = DoubleArray(3)
    private val dS = DoubleArray(3)

    /**
     * Degree-2 solid-Earth-tide corrections to the **unnormalised** C2m, S2m (IERS Conventions 6.6).
     *
     * The unnormalising factor is applied at the end, because the Legendre values `p` are the
     * normalised ones — mixing the two conventions here silently scales the tide by sqrt(5) and
     * leaves an error too small to see on an arc fit and large enough to matter at +72 h.
     */
    internal fun solidTide(sunPos: DoubleArray, moonPos: DoubleArray, dc: DoubleArray, ds: DoubleArray) {
        dc[0] = 0.0; dc[1] = 0.0; dc[2] = 0.0
        ds[0] = 0.0; ds[1] = 0.0; ds[2] = 0.0
        for (body in 0..1) {
            val p = if (body == 0) sunPos else moonPos
            val gm = if (body == 0) PgnssConstants.GM_SUN else PgnssConstants.GM_MOON
            val rj = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
            val sphi = p[2] / rj
            val lam = atan2(p[1], p[0])
            val cphi2 = max(0.0, 1.0 - sphi * sphi)
            val cphi = sqrt(cphi2)
            val p0 = 0.5 * (3 * sphi * sphi - 1) * sqrt(5.0)
            val p1 = 3 * sphi * cphi * sqrt(10.0 / 6.0)
            val p2 = 3 * cphi2 * sqrt(10.0 / 24.0)
            val re = PgnssConstants.RE / rj
            val k = (PgnssConstants.K2_LOVE / 5.0) * (gm / PgnssConstants.GM_EARTH) * re * re * re
            dc[0] += k * p0
            dc[1] += k * p1 * cos(lam)
            dc[2] += k * p2 * cos(2 * lam)
            ds[1] += k * p1 * sin(lam)
            ds[2] += k * p2 * sin(2 * lam)
        }
        for (m in 0..2) {
            val f = Egm96.unnormalise(2, m)
            dc[m] *= f
            ds[m] *= f
        }
    }

    /** The frame's rotation rate at [t]. */
    fun omega(t: Double): Double = frame?.omega(t) ?: omegaFixed

    private val vi = DoubleArray(3)
    private val eD = DoubleArray(3)
    private val eY = DoubleArray(3)
    private val eB = DoubleArray(3)

    /**
     * Acceleration of every satellite in [y] (`6*k` doubles) at GPS second [t], into [out] (`3*k`).
     *
     * [srpParams] is `k * nsrp` doubles, row-major: `D0, Y0, B0[, Bc, Bs[, Dc, Ds, Yc, Ys]]` in
     * m/s^2 at 1 AU. `nsrp = 3` is what the reference implementation ships — see the note on
     * `Propagator.NSRP_DEFAULT`.
     */
    fun accel(t: Double, y: DoubleArray, k: Int, srpParams: DoubleArray?, nsrp: Int, out: DoubleArray) {
        at(t)
        val w = omega(t)
        val gm = PgnssConstants.GM_EARTH
        val c2 = PgnssConstants.C_LIGHT * PgnssConstants.C_LIGHT
        for (i in 0 until k) {
            val b = 6 * i
            val o = 3 * i
            val rx = y[b]
            val ry = y[b + 1]
            val rz = y[b + 2]
            val vx = y[b + 3]
            val vy = y[b + 4]
            val vz = y[b + 5]

            geo.accel(rx, ry, rz, cWork, sWork, out, o)

            // Centrifugal and Coriolis — the price of a frame in which the gravity field is static.
            out[o] += w * w * rx + 2 * w * vy
            out[o + 1] += w * w * ry - 2 * w * vx

            if (third) {
                thirdBody(rx, ry, rz, sun, PgnssConstants.GM_SUN, out, o)
                thirdBody(rx, ry, rz, moon, PgnssConstants.GM_MOON, out, o)
            }

            // Inertial velocity, which is what relativity and the radiation-pressure frame want.
            vi[0] = vx - w * ry
            vi[1] = vy + w * rx
            vi[2] = vz

            if (relativity) {
                val rn = sqrt(rx * rx + ry * ry + rz * rz)
                val v2 = vi[0] * vi[0] + vi[1] * vi[1] + vi[2] * vi[2]
                val rv = rx * vi[0] + ry * vi[1] + rz * vi[2]
                val f = gm / (c2 * rn * rn * rn)
                val g = 4 * gm / rn - v2
                out[o] += f * (g * rx + 4 * rv * vi[0])
                out[o + 1] += f * (g * ry + 4 * rv * vi[1])
                out[o + 2] += f * (g * rz + 4 * rv * vi[2])
            }

            if (srp && srpParams != null && nsrp > 0) {
                val u = dybFrame(rx, ry, rz, vi, sun, eD, eY, eB)
                val dx = sun[0] - rx
                val dy = sun[1] - ry
                val dz = sun[2] - rz
                val dn = sqrt(dx * dx + dy * dy + dz * dz)
                val nu = shadow(rx, ry, rz, sun)
                val au = PgnssConstants.AU / dn
                val scale = nu * au * au
                val cu = cos(u)
                val su = sin(u)
                val p = i * nsrp
                var dCoef = srpParams[p]
                var yCoef = if (nsrp > 1) srpParams[p + 1] else 0.0
                var bCoef = if (nsrp > 2) srpParams[p + 2] else 0.0
                if (nsrp > 4) bCoef += srpParams[p + 3] * cu + srpParams[p + 4] * su
                if (nsrp > 8) {
                    dCoef += srpParams[p + 5] * cu + srpParams[p + 6] * su
                    yCoef += srpParams[p + 7] * cu + srpParams[p + 8] * su
                }
                out[o] += scale * dCoef * eD[0]
                out[o + 1] += scale * dCoef * eD[1]
                out[o + 2] += scale * dCoef * eD[2]
                if (nsrp > 1) {
                    out[o] += scale * yCoef * eY[0]
                    out[o + 1] += scale * yCoef * eY[1]
                    out[o + 2] += scale * yCoef * eY[2]
                }
                if (nsrp > 2) {
                    out[o] += scale * bCoef * eB[0]
                    out[o + 1] += scale * bCoef * eB[1]
                    out[o + 2] += scale * bCoef * eB[2]
                }
            }
        }
    }

    /** Point-mass perturbation: the direct pull minus the pull on the Earth itself. */
    private fun thirdBody(rx: Double, ry: Double, rz: Double, s: DoubleArray, gm: Double, out: DoubleArray, o: Int) {
        val dx = s[0] - rx
        val dy = s[1] - ry
        val dz = s[2] - rz
        val dn = sqrt(dx * dx + dy * dy + dz * dz)
        val sn = sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2])
        val fd = gm / (dn * dn * dn)
        val fs = gm / (sn * sn * sn)
        out[o] += fd * dx - fs * s[0]
        out[o + 1] += fd * dy - fs * s[1]
        out[o + 2] += fd * dz - fs * s[2]
    }

    /**
     * Conical-shadow illumination fraction in [0, 1].
     *
     * The annular branch matters: when the Earth's apparent disc fits entirely inside the Sun's, the
     * satellite is never in umbra and the fraction is `1 - (b/a)^2`, not zero. Dropping it turns a
     * grazing pass into a full eclipse.
     */
    internal fun shadow(rx: Double, ry: Double, rz: Double, s: DoubleArray): Double {
        val dx = s[0] - rx
        val dy = s[1] - ry
        val dz = s[2] - rz
        val dn = sqrt(dx * dx + dy * dy + dz * dz)
        val rn = sqrt(rx * rx + ry * ry + rz * rz)
        val a = asin(clamp(PgnssConstants.R_SUN / dn))
        val b = asin(clamp(PgnssConstants.R_EARTH_SHADOW / rn))
        val cosc = clamp((-rx * dx - ry * dy - rz * dz) / (rn * dn))
        val cc = acos(cosc)
        if (cc < b - a) {
            return if (b < a) 1.0 - (b / a) * (b / a) else 0.0
        }
        if (cc < a + b && cc >= abs(b - a)) {
            val x = (cc * cc + a * a - b * b) / (2 * cc)
            val yv = sqrt(max(a * a - x * x, 0.0))
            val area = a * a * acos(clamp(x / a)) + b * b * acos(clamp((cc - x) / b)) - cc * yv
            return min(1.0, max(0.0, 1.0 - area / (PI * a * a)))
        }
        return 1.0
    }

    /**
     * The ECOM axes and the argument of latitude.
     *
     * `eD` points from the satellite to the Sun, `eY` along the solar-panel axis, `eB` completes it.
     *
     * ## The argument of latitude, by a formula that survives a geostationary satellite
     * The obvious one, `u = atan2(z / sin i, r . n)`, divides by the sine of the inclination, and a
     * BeiDou geostationary satellite flies at a fraction of a degree: `sin i` goes to zero, the node
     * itself stops being defined, and `u` becomes noise that the once-per-revolution coefficients
     * then fit. One of the four came out of the arc fit 68.8 km wrong that way.
     *
     * Projecting `r` onto the node and onto `h x n` instead divides by nothing, and when the node
     * degenerates any fixed reference in the plane will do — the coefficients are empirical, so the
     * phase origin is arbitrary.
     *
     * `u` is identical in this frame and in an inertial one, because the two share a z-axis and
     * differ only by a rotation about it.
     */
    internal fun dybFrame(
        rx: Double,
        ry: Double,
        rz: Double,
        vInertial: DoubleArray,
        s: DoubleArray,
        outD: DoubleArray,
        outY: DoubleArray,
        outB: DoubleArray,
    ): Double {
        var dx = s[0] - rx
        var dy = s[1] - ry
        var dz = s[2] - rz
        val dn = sqrt(dx * dx + dy * dy + dz * dz)
        dx /= dn; dy /= dn; dz /= dn
        outD[0] = dx; outD[1] = dy; outD[2] = dz

        val rn = sqrt(rx * rx + ry * ry + rz * rz)
        val ex = rx / rn
        val ey = ry / rn
        val ez = rz / rn

        var yx = dy * ez - dz * ey
        var yy = dz * ex - dx * ez
        var yz = dx * ey - dy * ex
        val yn = sqrt(yx * yx + yy * yy + yz * yz)
        if (yn > 0.0) {
            yx /= yn; yy /= yn; yz /= yn
        }
        outY[0] = yx; outY[1] = yy; outY[2] = yz

        outB[0] = dy * yz - dz * yy
        outB[1] = dz * yx - dx * yz
        outB[2] = dx * yy - dy * yx

        val hx = ry * vInertial[2] - rz * vInertial[1]
        val hy = rz * vInertial[0] - rx * vInertial[2]
        val hz = rx * vInertial[1] - ry * vInertial[0]
        val hn = sqrt(hx * hx + hy * hy + hz * hz)
        val hux = if (hn > 0.0) hx / hn else 0.0
        val huy = if (hn > 0.0) hy / hn else 0.0
        val huz = if (hn > 0.0) hz / hn else 0.0

        var nx = -hy
        var ny = hx
        val nn = sqrt(nx * nx + ny * ny)
        if (nn < 1e-9 * hn || nn == 0.0) {
            nx = 1.0
            ny = 0.0
        } else {
            nx /= nn
            ny /= nn
        }
        // m = hhat x n, with n's z component zero.
        val mx = -huz * ny
        val my = huz * nx
        val mz = hux * ny - huy * nx
        return atan2(rx * mx + ry * my + rz * mz, rx * nx + ry * ny)
    }

    private fun clamp(v: Double): Double = if (v < -1.0) -1.0 else if (v > 1.0) 1.0 else v
}
