package com.opentasker.core.huawei.pgnss

import kotlin.math.max
import kotlin.math.min

/**
 * The five `HW_PGNSS_*` record encoders and the file assembler.
 *
 * A port of `sgn` / `uns` / `put` / `enc_gps` / `enc_gal` / `enc_glo` / `enc_bds` / `assemble` and
 * the GLONASS block loop in `scripts/pgnss-build.py`. These are the bytes the band actually reads,
 * so this file — not the fit — is what the acceptance test grades byte for byte.
 *
 * The layout, as decoded 2026-08-26:
 *
 * * 1008-byte header: 36 × (u32 full GPS seconds, u32 offset, u32 length), then 576 zero bytes.
 * * Each block: u32 count, then a fixed-capacity zero-padded record array — GPS 32 × 80 B, Galileo
 *   36 × 76 B, BeiDou 63 × 92 B, GLONASS 8 sub-blocks of (u32 count + 24 × 52 B) at 900 s.
 * * Satellite id is a 0-BASED INDEX: PRN = idx + 1. **In every constellation, GLONASS included** —
 *   the captured `HW_PGNSS_GLONASS` carries indices 0…23 with gaps, and there is no slot 0.
 * * Records are ordinary broadcast Kepler element sets, except GLONASS, which is an ECEF state
 *   vector with luni-solar acceleration — mirroring each system's own broadcast form.
 * * Galileo uses a different field ORDER from GPS and its toe/toc are in 60 s units, not 16 s.
 * * BeiDou is a third order again, in 8 s units of the BeiDou week, with finer harmonic scalings.
 */
object Records {

    const val GPS_CAPACITY = 32
    const val GALILEO_CAPACITY = 36
    const val GLONASS_CAPACITY = 24
    const val BDS_CAPACITY = 63

    const val GPS_RECLEN = 80
    const val GALILEO_RECLEN = 76
    const val GLONASS_RECLEN = 52
    const val BDS_RECLEN = 92

    /** 36 blocks, 7200 s apart: 72 hours. */
    const val BLOCKS = 36
    const val STEP = 7200L

    /**
     * BeiDou's blocks are stamped 14 s LATER than every other constellation's, and this is not a
     * detail.
     *
     * BeiDou's toe is a count of EIGHT seconds of the BeiDou week, and BDT is GPS − 14, so a stamp
     * that is a multiple of 7200 gives a seconds-of-week congruent to 2 mod 8 and the field cannot
     * hold it. Offsetting the stamp by 14 puts the week-seconds back on a multiple of 8. Ignoring
     * this truncated every toe by 2 s: the orbits were right, the encoder was right, and every
     * satellite was 7.5 km along its own track.
     */
    const val BDS_STAMP_OFFSET = Orbit.BDT_OFFSET

    private const val TWO_P5 = 32.0
    private const val TWO_P6 = 64.0
    private const val TWO_P11 = 2048.0
    private const val TWO_P19 = 524288.0
    private const val TWO_P20 = 1048576.0
    private const val TWO_P29 = 536870912.0
    private const val TWO_P30 = 1073741824.0
    private const val TWO_P31 = 2147483648.0
    private const val TWO_P33 = 8589934592.0
    private const val TWO_P34 = 17179869184.0
    private const val TWO_P43 = 8796093022208.0
    private const val TWO_P46 = 70368744177664.0
    private const val TWO_P50 = 1125899906842624.0

    /**
     * Round to nearest and clamp into a signed field of [bits].
     *
     * The rounding is HALF-TO-EVEN, which is what Python's `round` does and what [Math.rint] does —
     * `Math.round` is half-up and would disagree on every exact half. Halves are not hypothetical
     * here: a quantised harmonic lands on one whenever the fit happens to sit on a representable
     * midpoint, and one count of Ω0 is 1.5e-9 rad.
     */
    fun sgn(v: Double, bits: Int): Long {
        require(!v.isNaN()) { "cannot encode NaN into a $bits-bit field" }
        val lo = -(1L shl (bits - 1))
        val hi = (1L shl (bits - 1)) - 1
        return max(lo, min(hi, Math.rint(v).toLong()))
    }

    /** The same, into an unsigned field of [bits]. */
    fun uns(v: Double, bits: Int): Long {
        require(!v.isNaN()) { "cannot encode NaN into a $bits-bit field" }
        val hi = (1L shl bits) - 1
        return max(0L, min(hi, Math.rint(v).toLong()))
    }

    /** Little-endian, like everything inside this format and unlike everything outside it. */
    fun put(buf: ByteArray, off: Int, value: Long, width: Int) {
        var v = value
        for (i in 0 until width) {
            buf[off + i] = (v and 0xFF).toByte()
            v = v shr 8
        }
    }

    /** The 80-byte GPS record. */
    fun encodeGps(
        idx: Int,
        week: Long,
        el: Orbit.Elements,
        af0: Double,
        af1: Double,
        toeTow: Long,
        tocTow: Long,
        tgd: Int = 0,
    ): ByteArray {
        val r = ByteArray(GPS_RECLEN)
        put(r, 0, idx.toLong(), 2)
        put(r, 2, week, 2)
        put(r, 8, sgn(Orbit.wrap(el.m0) / Orbit.PI * TWO_P31, 32), 4)
        put(r, 12, sgn(el.deltaN / Orbit.PI * TWO_P43, 32), 4)
        put(r, 16, uns(el.e * TWO_P33, 32), 4)
        put(r, 20, uns(el.sqrtA * TWO_P19, 32), 4)
        put(r, 24, sgn(Orbit.wrap(el.omega0) / Orbit.PI * TWO_P31, 32), 4)
        put(r, 28, sgn(Orbit.wrap(el.i0) / Orbit.PI * TWO_P31, 32), 4)
        put(r, 32, sgn(Orbit.wrap(el.omega) / Orbit.PI * TWO_P31, 32), 4)
        put(r, 36, sgn(el.omegaDot / Orbit.PI * TWO_P43, 32), 4)
        put(r, 40, sgn(el.iDot / Orbit.PI * TWO_P43, 16), 2)
        put(r, 42, sgn(el.cuc * TWO_P29, 16), 2)
        put(r, 44, sgn(el.cus * TWO_P29, 16), 2)
        put(r, 46, sgn(el.crc * TWO_P5, 16), 2)
        put(r, 48, sgn(el.crs * TWO_P5, 16), 2)
        put(r, 50, sgn(el.cic * TWO_P29, 16), 2)
        put(r, 52, sgn(el.cis * TWO_P29, 16), 2)
        put(r, 54, tgd.toLong(), 1)
        put(r, 56, sgn(af0 * TWO_P31, 32), 4)
        put(r, 60, sgn(af1 * TWO_P43, 32), 4)
        put(r, 72, uns(Math.floorDiv(toeTow, 16L).toDouble(), 16), 2)
        put(r, 74, uns(Math.floorDiv(tocTow, 16L).toDouble(), 16), 2)
        // 0xFF, because every one of Huawei's 1044 GPS records carries it and none of ours did.
        //
        // It is the same value for every satellite in every one of their 36 epochs, so it is a flag
        // and not data — a per-record "usable" marker is the only reading that fits a constant. The
        // set that omitted it was accepted, counted down correctly, and produced a 3-4 minute fix
        // where Huawei's own file had produced 21 s. The orbits were never the problem: the
        // verification propagated them and they were sub-metre. It only ever read the fields it
        // wrote, so the byte nothing wrote was the byte nothing checked.
        put(r, 77, 0xFF, 1)
        return r
    }

    /** The 76-byte Galileo/QZSS record — a DIFFERENT field order from GPS, and 60 s time units. */
    fun encodeGalileo(
        idx: Int,
        el: Orbit.Elements,
        af0: Double,
        af1: Double,
        toeTow: Long,
        tocTow: Long,
        bgd: Int = 0,
    ): ByteArray {
        val r = ByteArray(GALILEO_RECLEN)
        put(r, 0, idx.toLong(), 4)
        put(r, 4, uns(Math.floorDiv(tocTow, 60L).toDouble(), 32), 4)
        put(r, 8, 0, 4)                                          // af2
        put(r, 12, sgn(af1 * TWO_P46, 32), 4)
        put(r, 16, sgn(af0 * TWO_P34, 32), 4)
        put(r, 20, bgd.toLong(), 2)                              // BGD, 16-bit here, not GPS's 8
        put(r, 24, uns(Math.floorDiv(toeTow, 60L).toDouble(), 32), 4)
        put(r, 28, sgn(Orbit.wrap(el.omega) / Orbit.PI * TWO_P31, 32), 4)
        put(r, 32, sgn(el.deltaN / Orbit.PI * TWO_P43, 32), 4)
        put(r, 36, sgn(Orbit.wrap(el.m0) / Orbit.PI * TWO_P31, 32), 4)
        put(r, 40, sgn(el.omegaDot / Orbit.PI * TWO_P43, 32), 4)
        put(r, 44, uns(el.e * TWO_P33, 32), 4)
        put(r, 48, sgn(el.iDot / Orbit.PI * TWO_P43, 16), 2)
        put(r, 52, uns(el.sqrtA * TWO_P19, 32), 4)
        put(r, 56, sgn(Orbit.wrap(el.i0) / Orbit.PI * TWO_P31, 32), 4)
        put(r, 60, sgn(Orbit.wrap(el.omega0) / Orbit.PI * TWO_P31, 32), 4)
        put(r, 64, sgn(el.crs * TWO_P5, 16), 2)
        put(r, 66, sgn(el.cis * TWO_P29, 16), 2)
        put(r, 68, sgn(el.cus * TWO_P29, 16), 2)
        put(r, 70, sgn(el.crc * TWO_P5, 16), 2)
        put(r, 72, sgn(el.cic * TWO_P29, 16), 2)
        put(r, 74, sgn(el.cuc * TWO_P29, 16), 2)
        return r
    }

    /**
     * The 92-byte BeiDou record. Times are BDT seconds of week in 8 s units, not GPS and not 16 s.
     *
     * [tail] is bytes 20-23, copied verbatim per satellite from the capture: read as a signed 32-bit
     * word the field is always a multiple of 65536, the values run −102 to +473, and as tenths of a
     * nanosecond that is the range and resolution of the BeiDou group delay TGD1 — a hardware
     * calibration that appears in no orbit product and cannot be fitted.
     *
     * The times MUST already be multiples of 8, and this refuses them otherwise instead of
     * truncating. Truncating cost 7.5 km of pure along-track error, and cost it INVISIBLY: the
     * builder's own check grades the element set before it is encoded, so it saw the un-truncated
     * toe the fit was anchored to and reported 0.60 m while the shipped bytes were 7557 m out.
     */
    fun encodeBds(
        idx: Int,
        el: Orbit.Elements,
        af0: Double,
        af1: Double,
        toeBdt: Long,
        tocBdt: Long,
        tail: ByteArray,
    ): ByteArray {
        require(Math.floorMod(toeBdt, 8L) == 0L && Math.floorMod(tocBdt, 8L) == 0L) {
            "BeiDou toe/toc must be multiples of 8 s, got $toeBdt/$tocBdt"
        }
        require(tail.size == 4) { "the BeiDou tail is four bytes" }
        val r = ByteArray(BDS_RECLEN)
        put(r, 0, idx.toLong(), 2)
        // Bytes 2-3: a 10-bit coarse epoch, floor(toe/512) mod 1024, sitting in the TOP ten bits.
        //
        // It is the same for every satellite in a block and steps by 14 or 15 counts between blocks,
        // which is 7200/512. Read as a plain u16 it is 64 × that, so the low six bits are always
        // clear — a bit-field whose neighbours Huawei leaves empty. Whatever it means, the rule
        // reproduces all 2236 records of both captured vintages exactly.
        put(r, 2, Math.floorMod(Math.floorDiv(toeBdt, 512L), 1024L) * 64L, 2)
        put(r, 8, uns(Math.floorDiv(tocBdt, 8L).toDouble(), 32), 4)
        put(r, 12, sgn(af0 * TWO_P33, 32), 4)
        put(r, 16, sgn(af1 * TWO_P50, 32), 4)
        System.arraycopy(tail, 0, r, 20, 4)
        put(r, 28, uns(Math.floorDiv(toeBdt, 8L).toDouble(), 32), 4)
        put(r, 32, uns(el.sqrtA * TWO_P19, 32), 4)
        put(r, 36, uns(el.e * TWO_P33, 32), 4)
        put(r, 40, sgn(Orbit.wrap(el.omega) / Orbit.PI * TWO_P31, 32), 4)
        put(r, 44, sgn(el.deltaN / Orbit.PI * TWO_P43, 32), 4)
        put(r, 48, sgn(Orbit.wrap(el.m0) / Orbit.PI * TWO_P31, 32), 4)
        put(r, 52, sgn(Orbit.wrap(el.omega0) / Orbit.PI * TWO_P31, 32), 4)
        put(r, 56, sgn(el.omegaDot / Orbit.PI * TWO_P43, 32), 4)
        put(r, 60, sgn(Orbit.wrap(el.i0) / Orbit.PI * TWO_P31, 32), 4)
        put(r, 64, sgn(el.iDot / Orbit.PI * TWO_P43, 16), 2)
        // BeiDou's harmonics are finer than GPS's: 2⁻³¹ for the angle terms against GPS's 2⁻²⁹, and
        // 2⁻⁶ metres for the radius terms against 2⁻⁵, each in a full 32-bit slot.
        put(r, 68, sgn(el.cuc * TWO_P31, 32), 4)
        put(r, 72, sgn(el.cus * TWO_P31, 32), 4)
        put(r, 76, sgn(el.crc * TWO_P6, 32), 4)
        put(r, 80, sgn(el.crs * TWO_P6, 32), 4)
        put(r, 84, sgn(el.cic * TWO_P31, 32), 4)
        put(r, 88, sgn(el.cis * TWO_P31, 32), 4)
        return r
    }

    /** The 52-byte GLONASS state vector: km, km/s, km/s², exactly its own broadcast form. */
    fun encodeGlonass(
        idx: Int,
        tb: Long,
        p: DoubleArray,
        v: DoubleArray,
        a: DoubleArray,
        tau: Double,
    ): ByteArray {
        val r = ByteArray(GLONASS_RECLEN)
        put(r, 0, idx.toLong(), 2)
        put(r, 2, tb, 2)
        // NEGATED. The GLONASS field is tau_n = −(clock bias); SP3 publishes the bias itself. Ours
        // wrote it straight through, so every satellite carried the exact negative of its own clock
        // correction — verified against the captured RTCM 1020 broadcast, where Huawei matches tau
        // to 0.01 µs on all 21 slots and ours was its mirror image on all 21. Cost 2·|tau|·c of
        // range bias: 3.75 km to 209 km per satellite, median 37.9 km, in 100 % of records.
        put(r, 4, sgn(-tau * TWO_P30, 32), 4)
        put(r, 8, 0, 4)
        // The satellite-type flag (GLONASS-M/K), 1 for every satellite flying. Huawei writes 1 in
        // all 11 960 records of both captured vintages; we wrote 0 in all 6048 of ours. This is NOT
        // the frequency channel — that varies −7..+6 per slot and lives in the almanac, not here.
        put(r, 12, 1, 4)
        val bases = intArrayOf(16, 28, 40)
        for (k in 0 until 3) {
            val base = bases[k]
            put(r, base, sgn(p[k] / 1e3 * TWO_P11, 32), 4)
            put(r, base + 4, sgn(v[k] / 1e3 * TWO_P20, 32), 4)
            put(r, base + 8, sgn(a[k] / 1e3 * TWO_P30, 8), 1)
        }
        return r
    }

    /** Header + blocks, one record set per block. */
    fun assemble(
        stamps: LongArray,
        blocks: List<List<ByteArray>>,
        capacity: Int,
        reclen: Int,
    ): ByteArray = assembleSub(stamps, blocks.map { listOf(it) }, capacity, reclen, 1)

    /**
     * Header + blocks, with [sub] nested sub-blocks each (GLONASS: 8 per block, 900 s apart).
     *
     * The count word is the number of records the block was HANDED, not the number written — the
     * reference writes `len(recs)` and then copies only the first [capacity] of them, and a file
     * that disagreed with it here would differ in a place nothing else would notice.
     */
    fun assembleSub(
        stamps: LongArray,
        blocks: List<List<List<ByteArray>>>,
        capacity: Int,
        reclen: Int,
        sub: Int,
    ): ByteArray {
        val blen = sub * (4 + capacity * reclen)
        val count = min(stamps.size, blocks.size)
        require(count <= 84) { "the 1008-byte header holds 84 entries at most" }
        val out = ByteArray(1008 + count * blen)
        for (i in 0 until count) {
            put(out, 12 * i, stamps[i], 4)
            put(out, 12 * i + 4, (1008 + i.toLong() * blen), 4)
            put(out, 12 * i + 8, blen.toLong(), 4)
            val recsets = blocks[i]
            require(recsets.size == sub) { "block $i has ${recsets.size} sub-blocks, expected $sub" }
            for (s in 0 until sub) {
                val at = 1008 + i * blen + s * (4 + capacity * reclen)
                val recs = recsets[s]
                put(out, at, recs.size.toLong(), 4)
                for (k in 0 until min(recs.size, capacity)) {
                    System.arraycopy(recs[k], 0, out, at + 4 + k * reclen, reclen)
                }
            }
        }
        return out
    }

    /**
     * Sub-epoch 0 sits on the UTC HOUR, not on the block stamp.
     *
     * The stamps are multiples of 7200 in GPS seconds, which is :59:42 in UTC — so writing the state
     * at the stamp itself puts every sub-epoch 59 min 42 s later than Huawei's. Theirs land exactly
     * on the UTC quarter-hour grid: block stamp 1471683600 (08:59:42 UTC) carries its first state at
     * 08:00:00 UTC to within 3.5 m.
     */
    fun glonassHour(stampGps: Long): Long =
        Math.floorDiv(stampGps - Orbit.LEAP, 3600L) * 3600L + Orbit.LEAP

    /**
     * `tb` is MOSCOW time-of-day in 900 s units.
     *
     * GLONASS runs on UTC+3 and the field is defined against it. Computing it from GPS time-of-day
     * instead ran about three hours low. Huawei's 44 = 11:00 MSK = 08:00 UTC, matching its own state.
     */
    fun glonassTb(tGps: Long): Long =
        Math.floorDiv(Math.floorMod(tGps - Orbit.LEAP + 3 * 3600L, 86400L), 900L)

    /**
     * Build the whole `HW_PGNSS_GLONASS` file: 8 sub-epochs of 900 s per block, straight off the
     * precise orbit with no fit anywhere in it.
     *
     * [gloStamps] are the GLONASS block stamps — an hour EARLIER than the other constellations'.
     */
    fun buildGlonassFile(sats: Map<String, Sp3.Arc>, gloStamps: LongArray): ByteArray {
        val names = sats.keys.filter { it[0] == 'R' }.sorted()
        val p = DoubleArray(3)
        val v = DoubleArray(3)
        val pAfter = DoubleArray(3)
        val vAfter = DoubleArray(3)
        val pBefore = DoubleArray(3)
        val vBefore = DoubleArray(3)
        val aTotal = DoubleArray(3)
        val a = DoubleArray(3)
        val blocks = ArrayList<List<List<ByteArray>>>(gloStamps.size)
        for (ts in gloStamps) {
            val subs = ArrayList<List<ByteArray>>(8)
            val hour = glonassHour(ts)
            for (s in 0 until 8) {
                val t = hour + 900L * s
                val td = t.toDouble()
                val recs = ArrayList<ByteArray>()
                for (sat in names) {
                    val d = sats.getValue(sat)
                    if (!(d.t[0] + 5 < td && td < d.t[d.size - 1] - 5)) continue
                    Sp3.stateAt(d, td, p, v)
                    Sp3.stateAt(d, td + 1.0, pAfter, vAfter)
                    Sp3.stateAt(d, td - 1.0, pBefore, vBefore)
                    for (k in 0 until 3) aTotal[k] = (vAfter[k] - vBefore[k]) / 2.0
                    Orbit.luniSolar(p, v, aTotal, a)
                    val c = Sp3.interpolate(d.t, d.clock, td)
                    val tau = if (!c.isFinite()) 0.0 else c
                    recs.add(encodeGlonass(sat.substring(1).toInt() - 1, glonassTb(t), p, v, a, tau))
                }
                subs.add(recs)
            }
            blocks.add(subs)
        }
        return assembleSub(gloStamps, blocks, GLONASS_CAPACITY, GLONASS_RECLEN, 8)
    }
}
