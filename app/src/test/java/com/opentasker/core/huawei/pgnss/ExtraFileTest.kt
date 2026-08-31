package com.opentasker.core.huawei.pgnss

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

/**
 * `HW_PGNSS_EXTRA` assembly, graded against Huawei's own captured files and against the reference
 * implementation's bytes — never against a second copy of this code's own assumptions.
 *
 * The file is 6248 bytes of packed little-endian fields with no length prefixes, no checksums and
 * no version marker. Every mistake available here is silent: a field one byte out still parses, a
 * COPIED region filled with zeros still loads, a semicircle wrapped to +1 instead of -1 still
 * decodes. The band's only visible symptom is a slow fix. So the tests below check the things that
 * would still look right: the exact offsets, the copied regions, the wrap direction, and the
 * rejection of a fit that has gone wrong.
 */
class ExtraFileTest {

    private val reference by lazy { PgnssExtraFile.capturedReference() }

    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private val klobuchar = KlobucharSet(
        alpha = doubleArrayOf(2.98e-08, 7.451e-09, -1.788e-07, 0.0),
        beta = doubleArrayOf(133120.0, 0.0, -262144.0, 65536.0),
    )

    private val utc = UtcParameters(tOt = 233472.0, wnT = 2434, dtLS = 18, dtLSF = null, wnLSF = null, dn = null)

    private val gps = mapOf(
        1 to GpsAlmanacEntry(
            prn = 1, week = 2434, toa = 233472.0, e = 0.1913547516e-2, i0 = 0.9568468817,
            omegaDot = -0.8091765626e-8, sqrtA = 5153.541016, omega0 = -0.1229075891,
            omega = 0.152539334, m0 = 0.8761985159, af0 = 0.1831054688e-3,
            af1 = -0.7275957614e-11, health = 0,
        ),
    )

    private val galileo = mapOf(
        2 to GalileoAlmanacEntry(
            svid = 2, week = 2433, t0a = 466800.0, dSqrtA = 0.03125, e = 0.00030517578125,
            deltaI = -0.0052490234375, omega0 = -0.61956787109375, omegaDot = -1.862645149230959e-09,
            omega = 0.003021240234375, m0 = -0.44671630859375, af0 = 6.4849853515625e-05,
            af1 = 3.637978807091713e-12, health = 0,
        ),
    )

    private val glonass = mapOf(
        1 to GlonassAlmanacEntry(
            slot = 1, channel = 1, na = 951, date = LocalDate.of(2026, 8, 8), tLambda = 5679.75,
            tau = -0.179290771e-3, lambda = 0.7449627, deltaI = 0.1131916e-1, omega = 0.2088318,
            e = 0.3948212e-3, deltaT = -2656.191, deltaTDot = -0.4272461e-3,
        ),
        2 to GlonassAlmanacEntry(
            slot = 2, channel = -4, na = 951, date = LocalDate.of(2026, 8, 8), tLambda = 10610.2188,
            tau = -0.114440918e-4, lambda = 0.6452951, deltaI = 0.1358891e-1, omega = -0.7549438,
            e = 0.2023697e-2, deltaT = -2656.049, deltaTDot = -0.5493164e-3,
        ),
    )

    private val emptyBds = BdsAlmanacFit(
        week = 1077, toa = 569344, records = emptyMap(), carried = emptyList(),
        residuals = emptyMap(), osculating = emptyList(), rejected = emptyMap(),
    )

    private fun build(epoch: Long = 1_472_133_618L, bds: BdsAlmanacFit = emptyBds) =
        PgnssExtraFile.build(epoch, reference, gps, galileo, glonass, klobuchar, utc, bds)

    // ── the packaged capture ───────────────────────────────────────────────────────────────────

    /**
     * The captured file is shipped as a classpath resource so the phone can build without one. If
     * the packaging ever drops `src/main/resources`, this is the only place it shows: everywhere
     * else a missing reference would have to be replaced by zeros, and zeros in the unidentified
     * regions produce a file the band loads and misbehaves on.
     */
    @Test
    fun theCapturedReferenceIsPackagedAndIsTheRightSize() {
        assertEquals(PgnssExtraFile.SIZE, reference.size)
        // Its own trailer says 2026-08-25, which is the vintage the layout was decoded from.
        assertEquals(18, le(reference).getInt(0x1864))
    }

    // ── layout ────────────────────────────────────────────────────────────────────────────────

    /** 6248 bytes exactly. The band takes a length, not a terminator; short is silently truncated. */
    @Test
    fun theFileIsExactlySixThousandTwoHundredAndFortyEightBytes() {
        assertEquals(6248, build().size)
    }

    /**
     * About 290 bytes are still not understood. They are COPIED, and this is the test that says so:
     * if any of them is ever "cleaned up" to zero, or regenerated from a guess, the diff shows here
     * rather than on 白い熊's wrist.
     */
    @Test
    fun theUnidentifiedRegionsAreCopiedFromTheCaptureByteForByte() {
        val out = build()
        for ((from, to) in listOf(0x0000 to 0x0008, 0x0130 to 0x0238, 0x0238 to 0x0550)) {
            assertArrayEquals(
                "region 0x%04x..0x%04x must be copied verbatim".format(from, to),
                reference.copyOfRange(from, to),
                out.copyOfRange(from, to),
            )
        }
    }

    /**
     * WN_LSF / DN / dtLSF advertise the NEXT leap second and the RINEX header does not carry them.
     * Writing zeros there tells the band a leap second lands at week 0, day 0 — so those three
     * bytes are copied from the capture, which encodes "none pending".
     */
    @Test
    fun thePendingLeapFieldsAreCopiedWhenTheHeaderDoesNotCarryThem() {
        val out = build()
        assertEquals(reference[0x0B], out[0x0B])
        assertEquals(reference[0x0C], out[0x0C])
        assertEquals(reference[0x0D], out[0x0D])
        // ...but the CURRENT leap second and t_ot come from the header we actually parsed.
        assertEquals(18, out[0x0A].toInt())
        assertEquals(57, out[0x08].toInt())          // 233472 / 4096
    }

    /** The trailer is a validity PAIR — start, start + one week — then a zero, then leap seconds. */
    @Test
    fun theTrailerIsTheValidityPairThenZeroThenLeapSeconds() {
        val v = le(build(epoch = 1_472_133_618L))
        assertEquals(1_472_133_618, v.getInt(0x1858))
        assertEquals(1_472_133_618 + 604800, v.getInt(0x185C))
        assertEquals(0, v.getInt(0x1860))
        assertEquals(18, v.getInt(0x1864))
    }

    /**
     * GLONASS is the ONE constellation that stores its true slot; every other one stores PRN - 1.
     * Getting this backwards is not a crash, it is a satellite in the wrong place, and it is how the
     * numbering was settled in the first place (Galileo 27 km with the minus-one, 45 120 km without).
     */
    @Test
    fun everyConstellationExceptGlonassStoresAZeroBasedIndex() {
        val v = le(build())
        assertEquals(0, v.getShort(0x554).toInt())            // GPS PRN 1  -> 0
        assertEquals(1, v.getShort(0xC60).toInt())            // Galileo SVID 2 -> 1
        assertEquals(1, build()[0x958 + 2].toInt())           // GLONASS slot 1 -> 1
        assertEquals(2, build()[0x958 + 32 + 2].toInt())      // GLONASS slot 2 -> 2
    }

    /**
     * The GLONASS channel table's two constants — the 0x01c0 half-word on every slot but the last,
     * and the zero on the last — reproduce the capture exactly. Nobody knows what they mean, so any
     * change to them is a change to something unknown.
     */
    @Test
    fun theGlonassChannelTableReproducesTheCapturesConstants() {
        val out = build()
        val v = le(out)
        assertEquals(24, out[0x1C].toInt())
        assertEquals(0x01C0, v.getShort(0x1E).toInt())
        assertEquals(1, out[0x1C + 4].toInt())                // slot 1, channel +1
        assertEquals(-4, out[0x1C + 8].toInt())               // slot 2, channel -4, signed
        assertEquals(0x01C0, v.getShort(0x1C + 4 * 23 + 2).toInt())
        assertEquals(0, v.getShort(0x1C + 4 * 24 + 2).toInt())
        // The almanac block stores the same channel five bits wide and unsigned.
        assertEquals(1, out[0x958 + 3].toInt())
        assertEquals(28, out[0x958 + 32 + 3].toInt())         // -4 and 0x1F
    }

    /** The GPS record's scalings, spot-checked against the decoder's own inverse. */
    @Test
    fun theGpsRecordRoundTripsThroughTheDecodersScalings() {
        val v = le(build())
        val a = gps.getValue(1)
        assertEquals(a.e, (v.getShort(0x556).toInt() and 0xFFFF) * Math.scalb(1.0, -21), Math.scalb(1.0, -21))
        assertEquals(57, v.getShort(0x558).toInt())                       // toa / 4096
        assertEquals(a.i0 / Math.PI - 0.30, v.getShort(0x55A) * Math.scalb(1.0, -19), Math.scalb(1.0, -19))
        assertEquals(a.sqrtA, (v.getInt(0x560).toLong() and 0xFFFFFFFFL) * Math.scalb(1.0, -11), Math.scalb(1.0, -11))
        assertEquals(a.m0, v.getInt(0x56C) * Math.scalb(1.0, -23) * Math.PI, Math.scalb(1.0, -23) * Math.PI)
        assertEquals(0, v.getShort(0x55E).toInt())                        // health 0 stays 0
    }

    /** An unhealthy GPS satellite is flagged 255, not by its YUMA code. */
    @Test
    fun anUnhealthyGpsSatelliteIsFlagged255() {
        val sick = mapOf(1 to gps.getValue(1).copy(health = 63))
        val out = PgnssExtraFile.build(1_472_133_618L, reference, sick, galileo, glonass, klobuchar, utc, emptyBds)
        assertEquals(255, le(out).getShort(0x55E).toInt())
    }

    // ── numeric helpers ────────────────────────────────────────────────────────────────────────

    /**
     * Semicircles wrap into [-1, 1). Kotlin's `%` is truncated, not floored: with it a western right
     * ascension comes back near +1 instead of -1, the sign bit of a 32-bit field flips, and the
     * satellite appears on the far side of its orbit.
     */
    @Test
    fun semicirclesWrapNegativeAnglesDownwardsNotUpwards() {
        assertEquals(-0.5, semicircles(-Math.PI / 2), 1e-15)
        assertEquals(-0.5, semicircles(3 * Math.PI / 2), 1e-15)
        assertEquals(0.5, semicircles(Math.PI / 2), 1e-15)
        assertEquals(0.0, semicircles(0.0), 1e-15)
        assertTrue(semicircles(-0.001) < 0.0)
    }

    /**
     * The reference rounds with Python's `round`, which breaks ties to EVEN. `Math.round` breaks
     * them upward, and a one-bit difference in a field the band reads is indistinguishable from a
     * porting bug when the two builds are diffed.
     */
    @Test
    fun roundingBreaksTiesToEvenLikeTheReference() {
        assertEquals(0L, pyRound(0.5))
        assertEquals(2L, pyRound(1.5))
        assertEquals(2L, pyRound(2.5))
        assertEquals(-2L, pyRound(-2.5))
        assertEquals(1L, pyRound(0.6))
    }

    /** Fields SATURATE. Wrapping a too-large value produces a plausible number of the wrong sign. */
    @Test
    fun fieldsSaturateRatherThanWrap() {
        assertEquals(32767L, signedField(1e9, 16))
        assertEquals(-32768L, signedField(-1e9, 16))
        assertEquals(65535L, unsignedField(1e9, 16))
        assertEquals(0L, unsignedField(-5.0, 16))
    }

    /**
     * BeiDou's geostationary satellites reference delta-i to 0.00 semicircles, everything else to
     * 0.30. With 0.30 the 16-bit 2^-19 field saturates for a GEO, clamping it to i = 42.75 deg and
     * throwing it 20 000 km out — and it still encodes and decodes without complaint.
     */
    @Test
    fun theGeostationaryDeltaIReferenceIsZeroAndTheRestIsThreeTenths() {
        assertEquals(0.0, bdsDeltaIReference(1), 0.0)
        assertEquals(0.0, bdsDeltaIReference(5), 0.0)
        assertEquals(0.30, bdsDeltaIReference(6), 0.0)
        assertEquals(0.30, bdsDeltaIReference(58), 0.0)
        assertEquals(0.0, bdsDeltaIReference(59), 0.0)
        assertEquals(0.0, bdsDeltaIReference(63), 0.0)
    }

    /** A week number recovered from its low byte must land near the week we are actually in. */
    @Test
    fun aWeekIsLiftedOutOfItsLowByteToTheNearestCandidate() {
        assertEquals(1077, PgnssExtraFile.liftWeek(1077 and 0xFF, 1077))
        assertEquals(1077, PgnssExtraFile.liftWeek(1077 and 0xFF, 1100))
        assertEquals(1333, PgnssExtraFile.liftWeek(1077 and 0xFF, 1300))
    }

    // ── the BeiDou fit ─────────────────────────────────────────────────────────────────────────

    private val bdsSqrtA = 5282.6
    private val bdsMeanMotion = Math.sqrt(MU / Math.pow(bdsSqrtA, 6.0))

    /**
     * A BeiDou broadcast record at [toe] in BDT week [week].
     *
     * The mean anomaly is ADVANCED with toe by default, because consecutive real records describe
     * one continuous orbit. Repeating the same m0 at every epoch instead makes the satellite jump
     * back 6800 km every half hour, which no element set can fit and which is a property of the
     * fixture, not of the code under test.
     */
    private fun bdsRecord(
        prn: Int,
        week: Int,
        toe: Double,
        omega0: Double,
        m0: Double = 0.3 + bdsMeanMotion * (toe - 3600.0),
    ) = BdsNavRecord(
        prn = prn, af0 = 0.0, af1 = 0.0, af2 = 0.0, aode = 1.0, crs = 0.0, dn = 0.0,
        m0 = m0, cuc = 0.0, e = 0.0004, cus = 0.0, sqrtA = bdsSqrtA, toe = toe, cic = 0.0,
        omega0 = omega0, cis = 0.0, i0 = 0.96, crc = 0.0, omega = 0.2, omegaDot = -2.0e-9,
        idot = 0.0, week = week, toeAbs = week * 604800.0 + toe,
    )

    private val epochAfterTheArc = (1078L + BDS_WEEK_OFFSET) * 604800L + 300_000L

    /**
     * A least-squares solve that wanders off is indistinguishable from a good one until it reaches
     * the band. Records whose right ascension jumps a radian between epochs describe no Kepler orbit
     * at all; the element set has to be REJECTED, not shipped. C40 was rejected exactly this way on
     * the live 2026-08-30 run.
     */
    @Test
    fun anElementSetWithAnAbsurdResidualIsRejectedNotShipped() {
        val nav = mapOf(
            40 to (0..5).map { bdsRecord(40, 1078, 3600.0 + 1800.0 * it, if (it % 2 == 0) 0.5 else 1.5) },
        )
        val fit = PgnssExtraFile.buildBds(nav, reference, epochAfterTheArc, carryStale = false)
        assertTrue("C40 must be rejected", 40 in fit.rejected)
        assertTrue("its residual must be reported", fit.rejected.getValue(40) > 100e3)
        assertFalse("slot 39 must not be written", 39 in fit.records)
        assertTrue(fit.residuals.isEmpty())
    }

    /**
     * A satellite with no public feed — or one whose fit was rejected — is carried forward from the
     * capture and SAID SO. That is an extrapolation of somebody else's stale almanac, not an
     * independent source, and the only thing worse than doing it is doing it silently.
     */
    @Test
    fun aSlotWithNoUsableFitIsCarriedForwardFromTheCaptureAndReported() {
        val nav = mapOf(
            40 to (0..5).map { bdsRecord(40, 1078, 3600.0 + 1800.0 * it, if (it % 2 == 0) 0.5 else 1.5) },
        )
        val fit = PgnssExtraFile.buildBds(nav, reference, epochAfterTheArc, carryStale = true)
        assertTrue("C40 is carried", 40 in fit.carried)
        assertTrue("and its slot is filled", 39 in fit.records)
        val captured = PgnssExtraFile.decodeReferenceBds(reference, 39)
        assertNotNull(captured)
        // Carried forward means re-referenced to OUR toa, so m0 has moved but the orbit has not.
        assertEquals(captured!!.elements.sqrtA, fit.records.getValue(39).sqrtA, 1e-9)
        assertEquals(captured.elements.e, fit.records.getValue(39).e, 1e-12)
        assertTrue(abs(captured.elements.m0 - fit.records.getValue(39).m0) > 1e-6)
    }

    /** An empty capture slot carries nothing; it must not become a satellite at the origin. */
    @Test
    fun anEmptyCaptureSlotIsNotCarried() {
        val empty = (0 until 63).firstOrNull { PgnssExtraFile.decodeReferenceBds(reference, it) == null }
        assumeTrue("the capture has no empty slot to check", empty != null)
        assertNull(PgnssExtraFile.decodeReferenceBds(reference, empty!!))
    }

    /**
     * A clean arc is fitted to metres, not kilometres. The truth points come from the full broadcast
     * model and the fit is of the seven-parameter model the band evaluates, so this number is the
     * honest cost of the almanac approximation — it is not a self-comparison.
     */
    @Test
    fun aCleanArcFitsToWellUnderTheRejectionThreshold() {
        val nav = mapOf(
            12 to (0..47).map { bdsRecord(12, 1078, 3600.0 + 1800.0 * it, 0.5) },
        )
        val fit = PgnssExtraFile.buildBds(nav, reference, epochAfterTheArc, carryStale = false)
        assertTrue("C12 must be kept", 11 in fit.records)
        assertTrue("residual ${fit.residuals[12]} m", fit.residuals.getValue(12) < 1_000.0)
    }

    /**
     * Omega has to be formed as `(OmegaDot - OMEGA_E) * tk - OMEGA_E * toa`, which stays continuous
     * as tk grows past a week. The algebraically "equivalent" form using seconds-of-week is NOT
     * equivalent across the roll — OMEGA_E * 604800 is 44.09 rad, not a multiple of 2*pi — and threw
     * satellites 4820 km out on any arc that straddled it.
     */
    @Test
    fun theAlmanacModelStaysContinuousAcrossTheWeekRoll() {
        val el = KeplerElements(
            sqrtA = 5282.6, e = 0.0004, i0 = 0.96, omega0 = 0.5, omega = 0.2, m0 = 0.3,
            omegaDot = -2.0e-9,
        )
        // toa + tk crosses a week exactly at tk = 604800 - 8192; straddle THAT, not tk = 604800.
        val roll = 604800.0 - 8192.0
        val before = PgnssExtraFile.almanacPosition(el, roll - 1.0, 8192.0)
        val after = PgnssExtraFile.almanacPosition(el, roll + 1.0, 8192.0)
        val step = distance(before, after)
        assertTrue("two seconds of motion, got $step m", step < 20_000.0)
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (k in 0..2) s += (a[k] - b[k]) * (a[k] - b[k])
        return Math.sqrt(s)
    }

    // ── the golden diff ────────────────────────────────────────────────────────────────────────

    /**
     * The acceptance check: build from the SAME cached inputs `scripts/pgnss-extra-build.py` was run
     * on and diff the bytes.
     *
     * Everything outside the fitted BeiDou block must be identical. Inside it, the two builds solve
     * the same least-squares problem with different optimisers and land on the same minimum to
     * within its own noise — measured on 2026-08-30 at 43 differing bytes across 23 satellites, a
     * worst mutual position difference of 27.8 m against fit residuals of 500-1371 m, and a
     * difference in the quantity actually being minimised of at most 0.103 m.
     *
     * Skipped unless the fixtures are present. To regenerate them:
     * ```
     * python3 scripts/pgnss-extra-build.py --offline --epoch 2026-08-30T14:00:00 \
     *         --out .scratch/pgnss-extra-kt/PY_EXTRA.bin
     * ```
     */
    @Test
    fun matchesTheReferenceImplementationOutsideTheFittedBeidouBlock() {
        val golden = repoFile(".scratch/pgnss-extra-kt/PY_EXTRA.bin")
        val src = repoFile(".scratch/pgnss-extra/src")
        assumeTrue("golden fixtures absent", golden != null && src != null)

        val epoch = 1_472_133_618L                      // 2026-08-30T14:00:00Z, +18 s of leap
        val yuma = Almanac.parseYuma(File(src, "current_yuma.alm").readText(), epoch.toDouble())
        val gssc = Almanac.parseGssc(File(src, "galileo_2026-08-28.xml").readText())
        val agl = Almanac.parseAgl(File(src, "MCCT_260810.agl").readText())
        val today = File(src, "brdc242.rnx").readText()
        val (klob, utcSet) = Almanac.parseRinexHeader(today)
        val nav = Almanac.parseRinexBds(today)
        Almanac.mergeBdsNav(nav, Almanac.parseRinexBds(File(src, "brdc241.rnx").readText()))

        val bds = PgnssExtraFile.buildBds(nav, reference, epoch)
        val mine = PgnssExtraFile.build(epoch, reference, yuma, gssc, agl, klob, utcSet, bds)
        val theirs = golden!!.readBytes()

        assertEquals(theirs.size, mine.size)
        assertArrayEquals(
            "everything before the BeiDou almanac must be byte-identical",
            theirs.copyOfRange(0, 0x0F78),
            mine.copyOfRange(0, 0x0F78),
        )
        assertArrayEquals(
            "the trailer must be byte-identical",
            theirs.copyOfRange(0x1858, 0x1868),
            mine.copyOfRange(0x1858, 0x1868),
        )
        // The BeiDou block may differ only in the two degenerate angles (and, once, in omegaDot);
        // any difference in the orbit SIZE or SHAPE is a porting bug, not optimiser noise.
        for (slot in 0 until 63) {
            val p = 0x0F7C + slot * 36
            assertArrayEquals(
                "slot $slot header, semi-major axis and eccentricity",
                theirs.copyOfRange(p, p + 12),
                mine.copyOfRange(p, p + 12),
            )
            assertArrayEquals(
                "slot $slot inclination, clock and flags",
                theirs.copyOfRange(p + 28, p + 36),
                mine.copyOfRange(p + 28, p + 36),
            )
        }
    }

    /** Walk up from the test's working directory until the repository root is underfoot. */
    private fun repoFile(relative: String): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
