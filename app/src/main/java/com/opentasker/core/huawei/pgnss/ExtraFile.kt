package com.opentasker.core.huawei.pgnss

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Assembles `HW_PGNSS_EXTRA` — the 6248-byte almanac / ionosphere companion Huawei Health serves a
 * HUAWEI Band 11 Pro alongside the predicted ephemeris set.
 *
 * The layout was reverse-engineered from two genuine Huawei vintages (2026-08-22 and 2026-08-25);
 * this is a port of `scripts/pgnss-extra-build.py`, whose output is on the band right now.
 *
 * ```
 *     offset  size  contents
 *     0x0000     8  two counters                                    COPIED (meaning unknown)
 *     0x0008     8  GPS UTC parameter set: t_ot, WN_t, dtLS, WN_LSF, DN, dtLSF
 *     0x0010     8  Klobuchar alpha[4], beta[4] (signed bytes)
 *     0x0018     4  u32 = 1
 *     0x001c   100  GLONASS frequency-channel table, 25 x 4 B
 *     0x0080   176  zero pad
 *     0x0130   264  per-GPS-satellite 8-byte table                  COPIED (X field unexplained)
 *     0x0238   792  three 264-byte id-list blocks                   COPIED (semantics unproven)
 *     0x0550  1028  GPS almanac      header + 32 x 32 B
 *     0x0954   772  GLONASS almanac  header + 24 x 32 B
 *     0x0c58   624  Galileo almanac  header + 28 x 22 B
 *     0x0ec8   176  Galileo slack (capacity 36)
 *     0x0f78  2272  BeiDou almanac   header + 63 x 36 B
 *     0x1858    16  trailer: valid_from, valid_to, 0, leap seconds
 * ```
 *
 * ABOUT 290 BYTES ARE NOT UNDERSTOOD and are copied verbatim from a captured file. Every such site
 * is marked COPIED below. The captured 2026-08-25 file ships as a classpath resource so the phone
 * can build the file with nothing but a network connection — see [capturedReference].
 *
 * SATELLITE NUMBERING: GPS, Galileo and BeiDou store a 0-BASED index (`field = PRN - 1`); GLONASS
 * stores the true slot 1..24. Settled against external sources, not against our own decoder.
 *
 * PURE JVM ON PURPOSE — no Android imports, so a JUnit test can build the file and diff it against
 * what the Python produced from the same inputs.
 */
object PgnssExtraFile {

    /** The file is a fixed 6248 bytes. Anything else is not this format. */
    const val SIZE: Int = 6248

    /** The captured vintage that supplies every COPIED region, embedded so the phone needs no file. */
    const val REFERENCE_RESOURCE: String = "/pgnss/HW_PGNSS_EXTRA_2026-08-25.bin"

    /** Slots the BeiDou almanac has room for. */
    private const val BDS_SLOTS = 63

    /** Reject a BeiDou element set whose residual against its own truth points exceeds this. */
    private const val BDS_MAX_RESIDUAL_M = 100e3

    /**
     * The captured 2026-08-25 `HW_PGNSS_EXTRA`, byte for byte.
     *
     * Loaded from the classpath rather than from the filesystem: the phone has no `.scratch`
     * directory, and a build that silently substituted zeros for the unidentified regions would
     * produce a file the band accepts and then behaves strangely on, which is the worst failure
     * available here.
     */
    fun capturedReference(): ByteArray {
        val bytes = PgnssExtraFile::class.java.getResourceAsStream(REFERENCE_RESOURCE)?.use { it.readBytes() }
            ?: throw IllegalStateException("missing packaged resource $REFERENCE_RESOURCE")
        require(bytes.size == SIZE) { "packaged reference is ${bytes.size} bytes, expected $SIZE" }
        return bytes
    }

    /**
     * Assemble the file.
     *
     * @param epochGps validity start in full GPS seconds; the trailer advertises [epochGps] ..
     *   [epochGps] + 604800.
     * @param reference a captured 6248-byte file supplying the COPIED regions.
     */
    fun build(
        epochGps: Long,
        reference: ByteArray,
        gps: Map<Int, GpsAlmanacEntry>,
        galileo: Map<Int, GalileoAlmanacEntry>,
        glonass: Map<Int, GlonassAlmanacEntry>,
        klobuchar: KlobucharSet,
        utc: UtcParameters,
        bds: BdsAlmanacFit,
    ): ByteArray {
        require(reference.size == SIZE) { "reference is ${reference.size} bytes, expected $SIZE" }
        require(gps.isNotEmpty()) { "no GPS almanac records" }
        require(galileo.isNotEmpty()) { "no Galileo almanac records" }
        require(glonass.isNotEmpty()) { "no GLONASS almanac records" }

        val b = ByteArray(SIZE)
        val w = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val ref = ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN)

        // ---- 0x0000  COPIED: two counters, meaning unknown (A=(3,1) B=(5,2)) --------------------
        reference.copyInto(b, 0x0000, 0x0000, 0x0008)

        // ---- 0x0008  GPS UTC parameter set -------------------------------------------------------
        b[0x08] = unsignedField(utc.tOt / 4096.0, 8).toByte()
        b[0x09] = (utc.wnT and 0xFF).toByte()
        b[0x0A] = unsignedField(utc.dtLS.toDouble(), 8).toByte()
        // WN_LSF / DN / dtLSF advertise the NEXT leap second. A RINEX header normally carries only
        // the current value, so these three are COPIED; the capture encodes "none pending".
        b[0x0B] = ((utc.wnLSF ?: reference[0x0B].toInt()) and 0xFF).toByte()
        b[0x0C] = unsignedField((utc.dn ?: (reference[0x0C].toInt() and 0xFF)).toDouble(), 8).toByte()
        b[0x0D] = unsignedField((utc.dtLSF ?: (reference[0x0D].toInt() and 0xFF)).toDouble(), 8).toByte()
        // 0x0E..0x0F stay zero.

        // ---- 0x0010  Klobuchar -------------------------------------------------------------------
        val alphaScale = intArrayOf(-30, -27, -24, -24)
        val betaScale = intArrayOf(11, 14, 16, 16)
        for (k in 0..3) b[0x10 + k] = signedField(klobuchar.alpha[k] / pow2(alphaScale[k]), 8).toByte()
        for (k in 0..3) b[0x14 + k] = signedField(klobuchar.beta[k] / pow2(betaScale[k]), 8).toByte()

        w.putInt(0x18, 1)

        // ---- 0x001c  GLONASS frequency-channel table ---------------------------------------------
        // Word 0 holds the count; word n holds slot n's channel. The 0x01c0 half-word and the zeroed
        // final word reproduce the capture exactly; their meaning is unknown.
        b[0x1C] = 24
        b[0x1D] = 0
        w.putShort(0x1E, 0x01C0.toShort())
        for (n in 1..24) {
            val p = 0x1C + 4 * n
            b[p] = (glonass[n]?.channel ?: 0).toByte()
            b[p + 1] = (n % 24).toByte()
            w.putShort(p + 2, (if (n < 24) 0x01C0 else 0).toShort())
        }

        // ---- 0x0130  COPIED: per-GPS-satellite 8-byte table. Its `X` field (7..32, and it moves
        // ---- between vintages) is unexplained, so the whole 264-byte slot is carried over. -------
        reference.copyInto(b, 0x0130, 0x0130, 0x0238)
        // ---- 0x0238  COPIED: three 264-byte id-list blocks; byte-identical across both captured
        // ---- vintages, semantics unproven. --------------------------------------------------------
        reference.copyInto(b, 0x0238, 0x0238, 0x0550)

        // ---- 0x0550  GPS almanac -------------------------------------------------------------------
        val gpsWeek = gps.values.maxOf { it.week }
        b[0x550] = (gpsWeek and 0xFF).toByte()
        b[0x551] = 32
        w.putShort(0x552, 0)
        for (prn in 1..32) {
            val a = gps[prn] ?: continue
            val p = 0x554 + (prn - 1) * 32
            w.putShort(p, (prn - 1).toShort())                                    // 0-BASED index
            w.putShort(p + 2, unsignedField(a.e / pow2(-21), 16).toShort())
            w.putShort(p + 4, unsignedField(a.toa / 4096.0, 16).toShort())
            w.putShort(p + 6, signedField((a.i0 / Math.PI - 0.30) / pow2(-19), 16).toShort())
            w.putShort(p + 8, signedField(a.omegaDot / Math.PI / pow2(-38), 16).toShort())
            w.putShort(p + 10, (if (a.health == 0) 0 else 255).toShort())
            w.putInt(p + 12, unsignedField(a.sqrtA / pow2(-11), 32).toInt())
            w.putInt(p + 16, signedField(semicircles(a.omega0) / pow2(-23), 32).toInt())
            w.putInt(p + 20, signedField(semicircles(a.omega) / pow2(-23), 32).toInt())
            w.putInt(p + 24, signedField(semicircles(a.m0) / pow2(-23), 32).toInt())
            w.putShort(p + 28, signedField(a.af0 / pow2(-20), 16).toShort())
            w.putShort(p + 30, signedField(a.af1 / pow2(-38), 16).toShort())
        }

        // ---- 0x0954  GLONASS almanac ---------------------------------------------------------------
        b[0x954] = 24
        b[0x955] = (gpsWeek and 0xFF).toByte()
        w.putShort(0x956, 0)
        for (n in 1..24) {
            val g = glonass[n] ?: continue
            val p = 0x958 + (n - 1) * 32
            w.putShort(p, g.na.toShort())
            b[p + 2] = n.toByte()
            b[p + 3] = (g.channel and 0x1F).toByte()
            w.putInt(p + 4, signedField(g.lambda / pow2(-20), 32).toInt())
            w.putInt(p + 8, signedField(g.tLambda / pow2(-5), 32).toInt())
            w.putInt(p + 12, signedField(g.deltaI / pow2(-20), 32).toInt())
            w.putInt(p + 16, signedField(g.deltaT / pow2(-9), 32).toInt())
            b[p + 20] = signedField(g.deltaTDot / pow2(-14), 8).toByte()
            b[p + 21] = 0
            w.putShort(p + 22, unsignedField(g.e / pow2(-20), 16).toShort())
            w.putShort(p + 24, 0)                       // unknown; zero in every captured record
            w.putShort(p + 26, signedField(g.tau / pow2(-18), 16).toShort())
            b[p + 28] = 1
            b[p + 29] = 1
            w.putShort(p + 30, 0)
        }

        // ---- 0x0c58  Galileo almanac ---------------------------------------------------------------
        val svids = galileo.keys.sorted()
        val galT0a = galileo.values.maxOf { it.t0a }
        val galWeek = galileo.values.maxOf { it.week }
        b[0xC58] = svids.size.toByte()
        b[0xC59] = (galWeek and 0xFF).toByte()
        w.putShort(0xC5A, 1)
        w.putShort(0xC5C, unsignedField(galT0a / 600.0, 16).toShort())
        w.putShort(0xC5E, 0)
        for ((k, svid) in svids.withIndex()) {
            val a = galileo.getValue(svid)
            val p = 0xC60 + k * 22
            w.putShort(p, (svid - 1).toShort())                                    // 0-BASED index
            w.putShort(p + 2, signedField(a.dSqrtA / pow2(-9), 16).toShort())
            w.putShort(p + 4, signedField(a.deltaI / pow2(-14), 16).toShort())
            w.putShort(p + 6, signedField(a.omegaDot / pow2(-33), 16).toShort())
            w.putShort(p + 8, unsignedField(a.health.toDouble(), 16).toShort())
            w.putShort(p + 10, unsignedField(a.e / pow2(-16), 16).toShort())
            w.putShort(p + 12, signedField(a.omega0 / pow2(-15), 16).toShort())
            w.putShort(p + 14, signedField(a.omega / pow2(-15), 16).toShort())
            w.putShort(p + 16, signedField(a.m0 / pow2(-15), 16).toShort())
            w.putShort(p + 18, signedField(a.af0 / pow2(-19), 16).toShort())
            w.putShort(p + 20, signedField(a.af1 / pow2(-38), 16).toShort())
        }

        // ---- 0x0f78  BeiDou almanac ------------------------------------------------------------------
        val bdsToaField = unsignedField(bds.toa / 4096.0, 8)
        b[0xF78] = BDS_SLOTS.toByte()
        b[0xF79] = (bds.week and 0xFF).toByte()
        b[0xF7A] = bdsToaField.toByte()
        b[0xF7B] = 0
        for (slot in 0 until BDS_SLOTS) {
            val el = bds.records[slot] ?: continue
            val p = 0xF7C + slot * 36
            b[p] = slot.toByte()
            b[p + 1] = bdsToaField.toByte()
            w.putShort(p + 2, 0)
            w.putInt(p + 4, unsignedField(el.sqrtA / pow2(-11), 32).toInt())
            w.putInt(p + 8, unsignedField(el.e / pow2(-21), 32).toInt())
            w.putInt(p + 12, signedField(semicircles(el.omega) / pow2(-23), 32).toInt())
            w.putInt(p + 16, signedField(semicircles(el.m0) / pow2(-23), 32).toInt())
            w.putInt(p + 20, signedField(semicircles(el.omega0) / pow2(-23), 32).toInt())
            w.putInt(p + 24, signedField(el.omegaDot / Math.PI / pow2(-38), 32).toInt())
            w.putShort(
                p + 28,
                signedField((el.i0 / Math.PI - bdsDeltaIReference(slot + 1)) / pow2(-19), 16).toShort(),
            )
            w.putShort(p + 30, signedField(el.af0 / pow2(-20), 16).toShort())
            w.putShort(p + 32, signedField(el.af1 / pow2(-38), 16).toShort())
            // +34 is an unidentified flags word (0x58 / 0x102 / 0xd8 in the captures); carry the
            // reference's value for this slot rather than invent one.  COPIED.
            w.putShort(p + 34, ref.getShort(p + 34))
        }

        // ---- 0x1858  trailer -------------------------------------------------------------------------
        w.putInt(0x1858, epochGps.toInt())
        w.putInt(0x185C, (epochGps + 604800L).toInt())
        w.putInt(0x1860, 0)
        w.putInt(0x1864, utc.dtLS)
        return b
    }

    /**
     * Fit the plain-Kepler BeiDou almanac to the BeiDou broadcast ephemeris.
     *
     * Everything is in ABSOLUTE BDT seconds (week * 604800 + seconds of week), because a broadcast
     * file straddles the week roll and seconds-of-week arithmetic across it is a reliable way to put
     * a satellite half an orbit out.
     *
     * The truth points come from the FULL broadcast model — harmonics, i-dot, and the geostationary
     * frame rotation — while the fit is of the seven-parameter model the band actually evaluates, so
     * an error in either shows up as a residual instead of cancelling.
     *
     * @param carryStale carry satellites with no public feed forward from [reference]. That is an
     *   EXTRAPOLATION of somebody else's stale almanac, not an independent source.
     */
    fun buildBds(
        nav: Map<Int, List<BdsNavRecord>>,
        reference: ByteArray,
        epochGps: Long,
        carryStale: Boolean = true,
    ): BdsAlmanacFit {
        require(reference.size == SIZE) { "reference is ${reference.size} bytes, expected $SIZE" }
        val gpsSow = Math.floorMod(epochGps, 604800L)
        val gpsWeek = Math.floorDiv(epochGps, 604800L)
        val epochBdt = (gpsWeek - BDS_WEEK_OFFSET) * 604800L + gpsSow - BDS_SECOND_OFFSET

        // Place toa inside the arc the broadcast data actually covers. A freshly published RINEX day
        // holds only the hours already elapsed, so toa normally lands a few hours before the validity
        // start; Huawei's own captures put it days earlier still.
        val allToe = nav.values.flatten().map { it.toeAbs }
        require(allToe.isNotEmpty()) { "no BeiDou broadcast ephemeris in the navigation file" }
        val centre = (allToe.min() + allToe.max()) / 2.0
        val target = minOf(centre, epochBdt.toDouble())
        // toa is transmitted as a single byte of 4096 s WITHIN THE WEEK, so it must be snapped in
        // seconds-of-week: 604800 is not a multiple of 4096, so snapping the absolute time instead
        // leaves up to 4096 s of error once the week is taken off (~900 km at MEO).
        val bwk = floor(target / 604800.0).toInt()
        val btoa = minOf(pyRound(target.mod(604800.0) / 4096.0) * 4096L, 147L * 4096L).toInt()
        val btoaAbs = bwk * 604800.0 + btoa

        val records = LinkedHashMap<Int, KeplerElements>()
        val residuals = LinkedHashMap<Int, Double>()
        val rejected = LinkedHashMap<Int, Double>()
        val osculating = ArrayList<Int>()

        for (prn in nav.keys.sorted()) {
            val slot = prn - 1                                              // 0-BASED index
            if (slot !in 0 until BDS_SLOTS) continue
            val rs = nav.getValue(prn).sortedBy { it.toeAbs }
            if (rs.isEmpty()) continue
            val near = rs.minByOrNull { abs(it.toeAbs - btoaAbs) } ?: continue
            val meanMotion = sqrt(MU / pow6(near.sqrtA)) + near.dn
            val seed = KeplerElements(
                sqrtA = near.sqrtA, e = near.e, i0 = near.i0, omega0 = near.omega0,
                omega = near.omega, m0 = near.m0 + meanMotion * (btoaAbs - near.toeAbs),
                omegaDot = near.omegaDot, af0 = near.af0, af1 = near.af1,
            )
            val period = 2 * Math.PI * sqrt(pow6(near.sqrtA) / MU)
            val lo = maxOf(btoaAbs - period / 2, rs.minOf { it.toeAbs } - 7200.0)
            val hi = minOf(btoaAbs + period / 2, rs.maxOf { it.toeAbs } + 7200.0)
            val ts = ArrayList<Double>()
            val ps = ArrayList<DoubleArray>()
            // np.arange(lo, hi + 1, 300.0): sample i is lo + 300*i, not a running sum.
            val samples = ceil((hi + 1.0 - lo) / 300.0).toInt()
            for (k in 0 until maxOf(samples, 0)) {
                val t = lo + 300.0 * k
                val r = rs.minByOrNull { abs(it.toeAbs - t) } ?: continue
                if (abs(r.toeAbs - t) > 7200.0) continue
                ts.add(t - btoaAbs)                        // signed seconds since toa, absolute
                ps.add(bdsEphemerisPosition(r, t.mod(604800.0)))
            }

            // Score the seed and the fit with the SAME independent metric and keep the better; a
            // least-squares solve that wanders off is otherwise indistinguishable from a good one
            // until it reaches the band.
            var best = seed
            var bestRms = if (ts.isNotEmpty()) almanacRms(seed, ts, ps, btoa.toDouble()) else 1e12
            var fitted = false
            if (ts.size >= 20) {
                val el = fitAlmanac(ts, ps, btoa.toDouble(), seed)
                if (el != null) {
                    val withClock = el.copy(af0 = near.af0, af1 = near.af1)
                    val rms = almanacRms(withClock, ts, ps, btoa.toDouble())
                    if (rms < bestRms) {
                        best = withClock
                        bestRms = rms
                        fitted = true
                    }
                }
            }
            if (bestRms > BDS_MAX_RESIDUAL_M) {
                // Rejected, not shipped. A 2000 km almanac record is worse than none: the band would
                // trust it and search the wrong sky.
                rejected[prn] = bestRms
                continue
            }
            records[slot] = best
            residuals[prn] = bestRms
            if (!fitted) osculating.add(prn)
        }

        val carried = ArrayList<Int>()
        if (carryStale) {
            val refWeek = liftWeek(reference[0xF79].toInt() and 0xFF, bwk)
            for (slot in 0 until BDS_SLOTS) {
                if (slot in records) continue
                val old = decodeReferenceBds(reference, slot) ?: continue
                // Re-reference the captured record to our own toa through the almanac's own model.
                val dt = btoaAbs - (refWeek * 604800.0 + old.toa)
                val n = sqrt(MU / pow6(old.elements.sqrtA))
                records[slot] = old.elements.copy(
                    m0 = old.elements.m0 + n * dt,
                    omega0 = old.elements.omega0 + old.elements.omegaDot * dt,
                )
                carried.add(slot + 1)
            }
        }
        return BdsAlmanacFit(
            week = bwk,
            toa = btoa,
            records = records,
            carried = carried,
            residuals = residuals,
            osculating = osculating,
            rejected = rejected,
        )
    }

    // ── propagation ────────────────────────────────────────────────────────────────────────────

    /** Kepler's equation, the same fixed 60 Newton steps the reference takes. */
    fun kepler(m: Double, e: Double): Double {
        var big = m
        repeat(60) { big -= (big - e * sin(big) - m) / (1 - e * cos(big)) }
        return big
    }

    /**
     * The plain broadcast-Kepler model the band applies to an almanac.
     *
     * [tk] is the SIGNED elapsed time since toa in seconds and may run past a week; [toaSow] is the
     * almanac reference time as seconds of week. Omega must be formed as
     * `(OmegaDot - OMEGA_E) * tk - OMEGA_E * toa`, which stays continuous as tk grows. The
     * algebraically "equivalent" `- OMEGA_E * (seconds of week of t)` is NOT equivalent across the
     * week roll — OMEGA_E * 604800 is 44.09 rad, not a multiple of 2*pi — and silently threw
     * satellites 4820 km out on any arc that straddled it.
     */
    fun almanacPosition(el: KeplerElements, tk: Double, toaSow: Double, mu: Double = MU): DoubleArray {
        val a = abs(el.sqrtA) * abs(el.sqrtA)
        val e = minOf(abs(el.e), 0.05)
        val big = kepler(el.m0 + sqrt(mu / (a * a * a)) * tk, e)
        val v = atan2(sqrt(1 - e * e) * sin(big), cos(big) - e)
        val u = v + el.omega
        val r = a * (1 - e * cos(big))
        val i = el.i0
        val xp = r * cos(u)
        val yp = r * sin(u)
        val om = el.omega0 + (el.omegaDot - OMEGA_E) * tk - OMEGA_E * toaSow
        return doubleArrayOf(
            xp * cos(om) - yp * cos(i) * sin(om),
            xp * sin(om) + yp * cos(i) * cos(om),
            yp * sin(i),
        )
    }

    /**
     * The full BeiDou broadcast model, including the geostationary frame rotation
     * (BDS-SIS-ICD 5.2.4.12). Used only to make the truth points the almanac is fitted to.
     *
     * The two rotation signs were settled by measurement, not by reading: with these, consecutive
     * broadcast records agree to 2 m where they overlap and the satellite comes out nearly fixed in
     * ECEF (6.8 km per 300 s, against 922 km of inertial motion) — which is what "geostationary"
     * means. Either sign flipped gives ~21 800 km of disagreement.
     */
    fun bdsEphemerisPosition(r: BdsNavRecord, tBdtSow: Double): DoubleArray {
        val a = r.sqrtA * r.sqrtA
        val tk = (tBdtSow - r.toe + 302400.0).mod(604800.0) - 302400.0
        val n = sqrt(MU / (a * a * a)) + r.dn
        val big = kepler(r.m0 + n * tk, r.e)
        val v = atan2(sqrt(1 - r.e * r.e) * sin(big), cos(big) - r.e)
        val phi = v + r.omega
        val s2 = sin(2 * phi)
        val c2 = cos(2 * phi)
        val u = phi + r.cus * s2 + r.cuc * c2
        val rad = a * (1 - r.e * cos(big)) + r.crs * s2 + r.crc * c2
        val i = r.i0 + r.idot * tk + r.cis * s2 + r.cic * c2
        val xp = rad * cos(u)
        val yp = rad * sin(u)
        val geo = a > 4.0e7 && abs(Math.toDegrees(r.i0)) < 10.0
        if (geo) {
            val om = r.omega0 + r.omegaDot * tk - OMEGA_E * r.toe
            val xg = xp * cos(om) - yp * cos(i) * sin(om)
            val yg = xp * sin(om) + yp * cos(i) * cos(om)
            val zg = yp * sin(i)
            // Rz(omega_e*tk) . Rx(-5deg) in the ICD's transposed convention.
            val p = Math.toRadians(5.0)
            val z = -OMEGA_E * tk
            val y1 = yg * cos(p) - zg * sin(p)
            val z1 = yg * sin(p) + zg * cos(p)
            return doubleArrayOf(xg * cos(z) - y1 * sin(z), xg * sin(z) + y1 * cos(z), z1)
        }
        val om = r.omega0 + (r.omegaDot - OMEGA_E) * tk - OMEGA_E * r.toe
        return doubleArrayOf(
            xp * cos(om) - yp * cos(i) * sin(om),
            xp * sin(om) + yp * cos(i) * cos(om),
            yp * sin(i),
        )
    }

    /** 3D RMS of an element set against truth points, in metres. */
    fun almanacRms(
        el: KeplerElements,
        tks: List<Double>,
        points: List<DoubleArray>,
        toaSow: Double,
        mu: Double = MU,
    ): Double {
        if (tks.isEmpty()) return Double.POSITIVE_INFINITY
        var sum = 0.0
        for (k in tks.indices) {
            val p = almanacPosition(el, tks[k], toaSow, mu)
            val q = points[k]
            val dx = p[0] - q[0]
            val dy = p[1] - q[1]
            val dz = p[2] - q[2]
            sum += dx * dx + dy * dy + dz * dz
        }
        return sqrt(sum / tks.size)
    }

    // ── least squares ──────────────────────────────────────────────────────────────────────────

    /** The seven fitted parameters, in the reference's order — the scaling below depends on it. */
    private val FIT_SCALE = doubleArrayOf(1.0, 1e-3, 1e-2, 1e-2, 1e-2, 1e-2, 1e-11)

    /**
     * Levenberg-Marquardt fit of the seven plain-Kepler almanac parameters to ECEF truth points.
     *
     * DELIBERATELY SELF-CONTAINED. The engine's shared solver is owned by another file in this
     * package and its interface is not settled; more importantly, the caller re-scores whatever
     * comes back here with [almanacRms] and keeps the seed if the fit is worse, so a solver that
     * wanders off costs accuracy, never correctness. Returns null if the solve produces nothing
     * usable.
     *
     * The parameters are scaled before the step is solved (`x_scale` in the reference): sqrtA is
     * ~6500 and omegaDot ~1e-9, and an unscaled normal-equation solve on that spread is dominated by
     * one column and moves the others not at all.
     */
    fun fitAlmanac(
        tks: List<Double>,
        points: List<DoubleArray>,
        toaSow: Double,
        seed: KeplerElements,
        mu: Double = MU,
    ): KeplerElements? {
        val n = 7
        val x = doubleArrayOf(seed.sqrtA, seed.e, seed.i0, seed.omega0, seed.omega, seed.m0, seed.omegaDot)

        fun residual(p: DoubleArray): DoubleArray {
            val el = KeplerElements(
                sqrtA = p[0], e = abs(p[1]), i0 = p[2], omega0 = p[3],
                omega = p[4], m0 = p[5], omegaDot = p[6],
            )
            val out = DoubleArray(tks.size * 3)
            for (k in tks.indices) {
                val q = almanacPosition(el, tks[k], toaSow, mu)
                out[3 * k] = q[0] - points[k][0]
                out[3 * k + 1] = q[1] - points[k][1]
                out[3 * k + 2] = q[2] - points[k][2]
            }
            return out
        }

        fun cost(r: DoubleArray): Double {
            var s = 0.0
            for (v in r) s += v * v
            return s
        }

        var r = residual(x)
        var f = cost(r)
        if (!f.isFinite()) return null
        var lambda = 1e-3
        val m = r.size

        // A plain `repeat` was wrong here: `return@repeat` CONTINUES the loop, so a converged fit
        // silently kept iterating and a stalled one never gave up. The exits have to be real breaks.
        for (iteration in 0 until 200) {
            // Numerical Jacobian in the SCALED parameters: column j is d(residual)/d(x_j/scale_j).
            val jac = Array(n) { DoubleArray(m) }
            for (j in 0 until n) {
                val h = FIT_SCALE[j] * 1e-6
                val plus = x.copyOf().also { it[j] += h }
                val minus = x.copyOf().also { it[j] -= h }
                val rp = residual(plus)
                val rm = residual(minus)
                val col = jac[j]
                for (i in 0 until m) col[i] = (rp[i] - rm[i]) / (2.0 * h) * FIT_SCALE[j]
            }
            // Normal equations.
            val jtj = Array(n) { DoubleArray(n) }
            val jtr = DoubleArray(n)
            for (a in 0 until n) {
                for (b in a until n) {
                    var s = 0.0
                    for (i in 0 until m) s += jac[a][i] * jac[b][i]
                    jtj[a][b] = s
                    jtj[b][a] = s
                }
                var s = 0.0
                for (i in 0 until m) s += jac[a][i] * r[i]
                jtr[a] = s
            }
            var improved = false
            var converged = false
            for (attempt in 0 until 12) {
                val lhs = Array(n) { a -> DoubleArray(n) { b -> jtj[a][b] } }
                for (a in 0 until n) lhs[a][a] += lambda * maxOf(jtj[a][a], 1e-30)
                val step = solve(lhs, DoubleArray(n) { -jtr[it] }) ?: break
                val trial = DoubleArray(n) { x[it] + step[it] * FIT_SCALE[it] }
                val rt = residual(trial)
                val ft = cost(rt)
                if (ft.isFinite() && ft < f) {
                    converged = (f - ft) / f < 1e-14
                    trial.copyInto(x)
                    r = rt
                    f = ft
                    lambda = maxOf(lambda / 3.0, 1e-12)
                    improved = true
                    break
                }
                lambda *= 5.0
                if (lambda > 1e12) break
            }
            if (!improved || converged) break
        }
        val out = KeplerElements(
            sqrtA = x[0], e = abs(x[1]), i0 = x[2], omega0 = x[3],
            omega = x[4], m0 = x[5], omegaDot = x[6],
        )
        return if (out.isFinite()) out else null
    }

    /** Gaussian elimination with partial pivoting. Null when the system is singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
            if (abs(a[pivot][col]) < 1e-300) return null
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            val t = b[col]; b[col] = b[pivot]; b[pivot] = t
            for (row in col + 1 until n) {
                val factor = a[row][col] / a[col][col]
                if (factor == 0.0) continue
                for (k in col until n) a[row][k] -= factor * a[col][k]
                b[row] -= factor * b[col]
            }
        }
        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var s = b[row]
            for (k in row + 1 until n) s -= a[row][k] * x[k]
            x[row] = s / a[row][row]
        }
        return if (x.all { it.isFinite() }) x else null
    }

    // ── captured-record decoding ───────────────────────────────────────────────────────────────

    /** One BeiDou almanac record read back out of a captured file. Angles in radians. */
    data class CapturedBdsRecord(val toa: Double, val health: Int, val elements: KeplerElements)

    /** Read one BeiDou almanac record out of a captured file. Null when the slot is empty. */
    fun decodeReferenceBds(reference: ByteArray, slot: Int): CapturedBdsRecord? {
        val v = ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN)
        val p = 0xF7C + slot * 36
        val toa = (reference[p + 1].toInt() and 0xFF) * 4096.0
        val health = v.getShort(p + 2).toInt() and 0xFFFF
        val sa = v.getInt(p + 4).toLong() and 0xFFFFFFFFL
        if (sa == 0L) return null
        val e = v.getInt(p + 8).toLong() and 0xFFFFFFFFL
        val omega = v.getInt(p + 12)
        val m0 = v.getInt(p + 16)
        val omega0 = v.getInt(p + 20)
        val omegaDot = v.getInt(p + 24)
        val di = v.getShort(p + 28).toInt()
        val af0 = v.getShort(p + 30).toInt()
        val af1 = v.getShort(p + 32).toInt()
        return CapturedBdsRecord(
            toa = toa,
            health = health,
            elements = KeplerElements(
                sqrtA = sa * pow2(-11),
                e = e * pow2(-21),
                i0 = (bdsDeltaIReference(slot + 1) + di * pow2(-19)) * Math.PI,
                omega0 = omega0 * pow2(-23) * Math.PI,
                omega = omega * pow2(-23) * Math.PI,
                m0 = m0 * pow2(-23) * Math.PI,
                omegaDot = omegaDot * pow2(-38) * Math.PI,
                af0 = af0 * pow2(-20),
                af1 = af1 * pow2(-38),
            ),
        )
    }

    /** Recover a full week number from its low byte, nearest to [near]. */
    fun liftWeek(lsb: Int, near: Int): Int =
        near + Math.floorMod(Math.floorMod(lsb - near, 256) + 128, 256) - 128

    private fun pow6(x: Double): Double {
        val x2 = x * x
        return x2 * x2 * x2
    }
}

/** Exact powers of two, so a scale factor is never a rounded decimal literal. */
internal fun pow2(exponent: Int): Double = Math.scalb(1.0, exponent)

/** The seven plain-Kepler almanac parameters plus the two clock terms carried alongside them. */
data class KeplerElements(
    val sqrtA: Double,
    val e: Double,
    val i0: Double,
    val omega0: Double,
    val omega: Double,
    val m0: Double,
    val omegaDot: Double,
    val af0: Double = 0.0,
    val af1: Double = 0.0,
) {
    fun isFinite(): Boolean =
        sqrtA.isFinite() && e.isFinite() && i0.isFinite() && omega0.isFinite() &&
            omega.isFinite() && m0.isFinite() && omegaDot.isFinite()
}

/**
 * The result of fitting the BeiDou almanac.
 *
 * [records] is keyed by 0-BASED SLOT (`PRN - 1`), which is what the file stores; [carried],
 * [residuals], [osculating] and [rejected] are keyed by true PRN, which is what a human reads.
 * [rejected] exists so a discarded satellite is reported rather than silently absent — C40 was
 * rejected on the live 2026-08-30 run and that has to be visible.
 */
data class BdsAlmanacFit(
    val week: Int,
    val toa: Int,
    val records: Map<Int, KeplerElements>,
    val carried: List<Int>,
    val residuals: Map<Int, Double>,
    val osculating: List<Int>,
    val rejected: Map<Int, Double>,
)
