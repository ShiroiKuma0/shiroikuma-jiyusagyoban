package com.opentasker.core.huawei.pgnss

import kotlin.math.max
import kotlin.math.min

/**
 * SP3 orbit files: parsing, merging, and the 9-point Lagrange interpolation every reader of one is
 * expected to use.
 *
 * A direct port of `read_sp3` / `merge_sp3` / `interp` / `spanned` / `state_at` in
 * `scripts/pgnss-build.py`, which is the reference implementation and is correct. Deliberately free
 * of Android types so the whole numerical chain can be graded on the desktop against that script's
 * own bytes — the only grader that does not share its assumptions.
 *
 * The one thing in here that has already cost a shipped file: **SP3 epochs are already GPS time.**
 * Every product this reads declares `%c M  cc GPS`, and its own `##` week/seconds-of-week equals the
 * plain difference of its first calendar epoch. Adding the 18 leap seconds shifted the whole truth
 * timeline, so every element set was fitted to satellites 18 s from where they were — about 53 km
 * along-track — and the generator reported 0.3 m because it graded itself against the same shifted
 * clock. Do not add leap seconds here.
 */
object Sp3 {

    /** Seconds between the Unix epoch and the GPS epoch, 1980-01-06T00:00:00Z. */
    private const val GPS_EPOCH_UNIX = 315_964_800L

    /** SP3's "no clock published for this satellite at this epoch". */
    private const val CLOCK_UNAVAILABLE = 999_999.999999

    /**
     * One satellite's arc: GPS seconds, ECEF metres, clock seconds.
     *
     * Positions are ITRF/ECEF, which is the frame the broadcast propagator outputs, so nothing in
     * this package rotates them. Positions live in one flat array of `3 * size` doubles rather than
     * an array of triples: the fit reads them 2196 times over and every allocation avoided there is
     * an allocation the phone does not make.
     *
     * [clock] carries `NaN` where the product published none.
     */
    class Arc(val t: DoubleArray, val p: DoubleArray, val clock: DoubleArray) {
        val size: Int get() = t.size

        init {
            require(p.size == 3 * t.size) { "positions must be 3 per epoch" }
            require(clock.size == t.size) { "one clock value per epoch" }
        }

        fun x(i: Int): Double = p[3 * i]
        fun y(i: Int): Double = p[3 * i + 1]
        fun z(i: Int): Double = p[3 * i + 2]
    }

    /**
     * Parse an SP3 file into `{satellite: arc}`.
     *
     * Only `*` epoch headers and `P` position lines are read, which is all the reference reader
     * takes and all this package needs — velocities (`V`), correlations (`EP`/`EV`) and the header
     * block are ignored. Column positions are SP3-c's fixed ones, not whitespace splitting: a
     * position field can run into its neighbour and splitting then silently merges two numbers.
     */
    fun parse(lines: Sequence<String>): Map<String, Arc> {
        val ts = HashMap<String, MutableList<Double>>()
        val ps = HashMap<String, MutableList<Double>>()
        val cs = HashMap<String, MutableList<Double>>()
        var t = Double.NaN
        var haveEpoch = false
        for (line in lines) {
            if (line.startsWith("*")) {
                t = epochSeconds(line)
                haveEpoch = true
            } else if (line.startsWith("P") && haveEpoch) {
                val sat = slice(line, 1, 4).trim()
                if (sat.isEmpty()) continue
                val x = number(line, 4, 18) ?: continue
                val y = number(line, 18, 32) ?: continue
                val z = number(line, 32, 46) ?: continue
                val raw = number(line, 46, 60) ?: CLOCK_UNAVAILABLE
                ts.getOrPut(sat) { ArrayList() }.add(t)
                val pl = ps.getOrPut(sat) { ArrayList() }
                pl.add(x * 1e3)
                pl.add(y * 1e3)
                pl.add(z * 1e3)
                cs.getOrPut(sat) { ArrayList() }
                    .add(if (raw < 999_999.0) raw * 1e-6 else Double.NaN)
            }
        }
        return ts.keys.associateWith { sat ->
            Arc(ts.getValue(sat).toDoubleArray(), ps.getValue(sat).toDoubleArray(),
                cs.getValue(sat).toDoubleArray())
        }
    }

    fun parse(text: String): Map<String, Arc> = parse(text.lineSequence())

    /**
     * One arc per satellite from several files; later files win where they overlap.
     *
     * [keepSeconds] limits each file to its first that many seconds. Wuhan's near-real-time product
     * is 48 h of which the first 24 are fitted to observations and the second 24 are that file's own
     * prediction, so `keepSeconds = 86400` builds an arc of nothing but observed orbits — which is
     * the point: the dynamical fit must not be handed somebody else's prediction as if it were data.
     */
    fun merge(
        files: List<Map<String, Arc>>,
        prefix: Char? = null,
        keepSeconds: Double? = null,
    ): Map<String, Arc> {
        val acc = HashMap<String, java.util.TreeMap<Double, DoubleArray>>()
        for (one in files) {
            if (one.isEmpty()) continue
            val t0 = one.values.minOf { it.t[0] }
            for ((sat, d) in one) {
                if (prefix != null && sat[0] != prefix) continue
                val into = acc.getOrPut(sat) { java.util.TreeMap() }
                for (i in 0 until d.size) {
                    val t = d.t[i]
                    if (keepSeconds != null && t >= t0 + keepSeconds) continue
                    into[t] = doubleArrayOf(d.x(i), d.y(i), d.z(i), d.clock[i])
                }
            }
        }
        return acc.mapValues { (_, byTime) ->
            val n = byTime.size
            val t = DoubleArray(n)
            val p = DoubleArray(3 * n)
            val c = DoubleArray(n)
            var i = 0
            for ((time, row) in byTime) {
                t[i] = time
                p[3 * i] = row[0]
                p[3 * i + 1] = row[1]
                p[3 * i + 2] = row[2]
                c[i] = row[3]
                i++
            }
            Arc(t, p, c)
        }
    }

    /**
     * The index numpy's `searchsorted(times, t)` returns: the leftmost place [t] could be inserted
     * and leave [times] sorted.
     */
    fun searchSorted(times: DoubleArray, t: Double): Int {
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The first index of the [order]-sample stencil the reference reader centres on [t]. */
    fun stencilStart(times: DoubleArray, t: Double, order: Int = 9): Int {
        val i = searchSorted(times, t)
        return max(0, min(i - order / 2, times.size - order))
    }

    /**
     * Lagrange interpolation of a scalar series on the [order] samples nearest [t].
     *
     * The weight loop is written in the reference's own order — `w` built over ascending `j`, the
     * sum accumulated over ascending `k` — because the fixtures compare against its numbers.
     */
    fun interpolate(times: DoubleArray, values: DoubleArray, t: Double, order: Int = 9): Double {
        val lo = stencilStart(times, t, order)
        val n = min(order, times.size - lo)
        var out = 0.0
        for (k in 0 until n) {
            var w = 1.0
            for (j in 0 until n) {
                if (j != k) w *= (t - times[lo + j]) / (times[lo + k] - times[lo + j])
            }
            out += w * values[lo + k]
        }
        return out
    }

    /** The same interpolation, on the three position components at once, into [out]. */
    fun interpolatePosition(arc: Arc, t: Double, out: DoubleArray, order: Int = 9) {
        val times = arc.t
        val lo = stencilStart(times, t, order)
        val n = min(order, times.size - lo)
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (k in 0 until n) {
            var w = 1.0
            for (j in 0 until n) {
                if (j != k) w *= (t - times[lo + j]) / (times[lo + k] - times[lo + j])
            }
            x += w * arc.p[3 * (lo + k)]
            y += w * arc.p[3 * (lo + k) + 1]
            z += w * arc.p[3 * (lo + k) + 2]
        }
        out[0] = x
        out[1] = y
        out[2] = z
    }

    /**
     * Does the stencil around [t] sit on CONTIGUOUS samples?
     *
     * Wuhan drops a satellite from a file now and then, which leaves a 24-hour hole in an otherwise
     * 300 s series. A 9-point polynomial fitted across a hole that size does not interpolate, it
     * invents: two BeiDou satellites were graded at 37 km and 32 km against nothing but the
     * interpolator's imagination, and their orbits were fine. Anything that reads a merged series
     * has to ask this first.
     */
    fun spanned(times: DoubleArray, t: Double, order: Int = 9, tol: Double = 1.5): Boolean {
        val lo = stencilStart(times, t, order)
        val hi = min(lo + order, times.size)
        if (hi - lo < order) return false
        val diffs = DoubleArray(hi - lo - 1) { times[lo + it + 1] - times[lo + it] }
        return times[hi - 1] - times[lo] <= tol * median(diffs) * (order - 1)
    }

    /**
     * Position and velocity at [t], the velocity by symmetric difference over one second — SP3
     * carries no velocities in the products this reads.
     */
    fun stateAt(arc: Arc, t: Double, position: DoubleArray, velocity: DoubleArray) {
        val before = DoubleArray(3)
        val after = DoubleArray(3)
        interpolatePosition(arc, t, position)
        interpolatePosition(arc, t + 1.0, after)
        interpolatePosition(arc, t - 1.0, before)
        for (k in 0 until 3) velocity[k] = (after[k] - before[k]) / 2.0
    }

    /** `numpy.median`, which averages the two middle values on an even count. */
    internal fun median(values: DoubleArray): Double {
        val v = values.copyOf()
        v.sort()
        val n = v.size
        if (n == 0) return Double.NaN
        return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2.0
    }

    /**
     * GPS seconds from an SP3 `*` epoch line.
     *
     * NO leap seconds. See the class note: adding them shipped a 53 km file that measured 0.3 m.
     * The microsecond quantisation is the reference's, which builds the epoch as a `datetime` plus a
     * `timedelta` and so cannot carry finer than a microsecond.
     */
    internal fun epochSeconds(line: String): Double {
        val f = line.trim().split(Regex("\\s+"))
        require(f.size >= 7) { "malformed SP3 epoch line: $line" }
        val days = daysFromCivil(f[1].toInt(), f[2].toInt(), f[3].toInt())
        val whole = days * 86_400L + f[4].toLong() * 3600L + f[5].toLong() * 60L - GPS_EPOCH_UNIX
        val micros = Math.rint(f[6].toDouble() * 1e6).toLong()
        return (whole * 1_000_000L + micros) / 1e6
    }

    /** Howard Hinnant's `days_from_civil`, so no calendar library is needed on the phone. */
    internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146_097L + doe - 719_468L
    }

    /** Python's slicing: past the end is not an error, it is a shorter string. */
    private fun slice(s: String, from: Int, to: Int): String =
        if (from >= s.length) "" else s.substring(from, min(to, s.length))

    /** A fixed-column float, or null where the reference's `float()` would have raised. */
    private fun number(s: String, from: Int, to: Int): Double? =
        slice(s, from, to).trim().let { if (it.isEmpty()) null else it.toDoubleOrNull() }
}
