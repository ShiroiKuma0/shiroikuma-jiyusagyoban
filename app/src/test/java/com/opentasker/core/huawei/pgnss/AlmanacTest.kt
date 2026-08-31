package com.opentasker.core.huawei.pgnss

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The almanac parsers, against fixtures copied verbatim out of the real published files.
 *
 * Every one of these formats is COLUMN- or LABEL-exact, and every one of them fails silently when
 * read loosely: a whitespace split of a RINEX header reads the BeiDou ionosphere as the GPS one, a
 * blank field in a navigation record shifts the week by four slots, and a YUMA week taken at face
 * value dates the almanac to 1999. None of those throw. They all produce a complete, correctly
 * sized file that the band accepts and then behaves badly on, which is why the fixtures here are
 * real text and not something convenient.
 */
class AlmanacTest {

    /** Full GPS seconds somewhere in week 2434 — the era the fixture's mod-1024 week belongs to. */
    private val nowGps = 2434.0 * 604800.0

    private fun lines(vararg l: String) = l.joinToString("\n")

    // ── YUMA ───────────────────────────────────────────────────────────────────────────────────

    private val yuma = lines(
        "******** Week 386 almanac for PRN-01 ********",
        "ID:                         01",
        "Health:                     000",
        "Eccentricity:               0.1913547516E-002",
        "Time of Applicability(s):  233472.0000",
        "Orbital Inclination(rad):   0.9568468817",
        "Rate of Right Ascen(r/s):  -0.8091765626E-008",
        "SQRT(A)  (m 1/2):           5153.541016",
        "Right Ascen at Week(rad):  -0.1229075891E+000",
        "Argument of Perigee(rad):   0.152539334",
        "Mean Anom(rad):             0.8761985159E+000",
        "Af0(s):                     0.1831054688E-003",
        "Af1(s/s):                  -0.7275957614E-011",
        "week:                        386",
        "",
        "******** Week 386 almanac for PRN-02 ********",
        "ID:                         02",
        "Health:                     063",
        "Eccentricity:               0.1700019836E-001",
        "Time of Applicability(s):  233472.0000",
        "Orbital Inclination(rad):   0.9364686012",
        "Rate of Right Ascen(r/s):  -0.7817283000E-008",
        "SQRT(A)  (m 1/2):           5153.601074",
        "Right Ascen at Week(rad):   0.1024194891E+001",
        "Argument of Perigee(rad):  -1.548767090",
        "Mean Anom(rad):             0.2761985159E+000",
        "Af0(s):                     0.4425048828E-003",
        "Af1(s/s):                   0.0000000000E+000",
        "week:                        386",
    )

    /**
     * YUMA carries the week MODULO 1024. Shipping 386 unchanged dates the almanac to March 1999,
     * and the band's only sane response to a seven-hundred-week-old almanac is to ignore the file.
     */
    @Test
    fun yumaWeekIsLiftedIntoTheCurrentEra() {
        val out = Almanac.parseYuma(yuma, nowGps)
        assertEquals(2434, out.getValue(1).week)
        assertEquals(2434, out.getValue(2).week)
    }

    /**
     * Navcen writes the semi-major-axis label with TWO spaces: `SQRT(A)  (m 1/2)`. A lookup that
     * normalises whitespace, or that assumes one space, finds nothing — and the record then carries
     * no orbit size at all.
     */
    @Test
    fun yumaReadsTheLabelWithTwoSpacesInIt() {
        val a = Almanac.parseYuma(yuma, nowGps).getValue(1)
        assertEquals(5153.541016, a.sqrtA, 1e-9)
    }

    /** The remaining fields, so a column or sign slip anywhere in the block is caught. */
    @Test
    fun yumaReadsEveryFieldOfARecord() {
        val a = Almanac.parseYuma(yuma, nowGps).getValue(1)
        assertEquals(1, a.prn)
        assertEquals(0, a.health)
        assertEquals(233472.0, a.toa, 1e-9)
        assertEquals(0.1913547516e-2, a.e, 1e-15)
        assertEquals(0.9568468817, a.i0, 1e-12)
        assertEquals(-0.8091765626e-8, a.omegaDot, 1e-20)
        assertEquals(-0.1229075891, a.omega0, 1e-12)
        assertEquals(0.152539334, a.omega, 1e-12)
        assertEquals(0.8761985159, a.m0, 1e-12)
        assertEquals(0.1831054688e-3, a.af0, 1e-15)
        assertEquals(-0.7275957614e-11, a.af1, 1e-22)
        assertEquals(63, Almanac.parseYuma(yuma, nowGps).getValue(2).health)
    }

    // ── Galileo ────────────────────────────────────────────────────────────────────────────────

    private val gssc = """
        <?xml version="1.0" encoding="UTF-8"?>
        <signalData>
          <header><GAL-header><issueDate>2026-08-28T09:59:59.0Z</issueDate></GAL-header></header>
          <body><Almanacs>
            <svAlmanac>
              <SVID>02</SVID>
              <almanac>
                <aSqRoot>0.03125</aSqRoot><ecc>0.00030517578125</ecc>
                <deltai>-0.0052490234375</deltai><omega0>-0.61956787109375</omega0>
                <omegaDot>-1.862645149230958878720099e-09</omegaDot>
                <w>0.003021240234375</w><m0>-0.44671630859375</m0>
                <af0>6.4849853515625e-05</af0><af1>3.63797880709171295166015625e-12</af1>
                <iod>10</iod><t0a>466800</t0a><wna>1</wna>
              </almanac>
              <svINavSignalStatus><statusE5b>0</statusE5b><statusE1B>2</statusE1B></svINavSignalStatus>
            </svAlmanac>
            <svAlmanac>
              <SVID>36</SVID>
              <almanac>
                <aSqRoot>0.0625</aSqRoot><ecc>0.000335693359375</ecc>
                <deltai>-0.00506591796875</deltai><omega0>0.28656005859375</omega0>
                <omegaDot>-1.862645149230958878720099e-09</omegaDot>
                <w>-0.72271728515625</w><m0>0.11376953125</m0>
                <af0>-1.9073486328125e-06</af0><af1>0.0</af1>
                <iod>10</iod><t0a>466800</t0a><wna>1</wna>
              </almanac>
              <svINavSignalStatus><statusE5b>0</statusE5b><statusE1B>0</statusE1B></svINavSignalStatus>
            </svAlmanac>
          </Almanacs></body>
        </signalData>
    """.trimIndent()

    /**
     * The XML's `wna` is the two-bit BROADCAST week, not a week number. Taking it literally files a
     * 2026 almanac under week 1 and the whole Galileo block ages out instantly; the full week has to
     * come from the issue date, which is within hours of t0a by construction.
     */
    @Test
    fun galileoWeekComesFromTheIssueDateNotTheTwoBitField() {
        val out = Almanac.parseGssc(gssc)
        assertEquals(2433, out.getValue(2).week)
        assertEquals(2433, out.getValue(36).week)
    }

    /** SVIDs stay one-based here; the minus-one belongs at the single point that writes the file. */
    @Test
    fun galileoKeepsTheTrueSvid() {
        assertEquals(setOf(2, 36), Almanac.parseGssc(gssc).keys)
    }

    /** Health is the WORSE of the two I/NAV statuses; taking either alone can call a sick SV well. */
    @Test
    fun galileoHealthIsTheWorseOfTheTwoSignalStatuses() {
        val out = Almanac.parseGssc(gssc)
        assertEquals(2, out.getValue(2).health)
        assertEquals(0, out.getValue(36).health)
    }

    /** The published values are semicircles and offsets, and are stored as published. */
    @Test
    fun galileoReadsTheOffsetParameterisationUnchanged() {
        val a = Almanac.parseGssc(gssc).getValue(2)
        assertEquals(0.03125, a.dSqrtA, 1e-15)
        assertEquals(-0.0052490234375, a.deltaI, 1e-15)
        assertEquals(466800.0, a.t0a, 1e-9)
        assertEquals(-1.862645149230958878720099e-09, a.omegaDot, 1e-24)
    }

    // ── GLONASS ────────────────────────────────────────────────────────────────────────────────

    private val agl = lines(
        "10 08 2026   20679",
        " 1   1  1  08 08 2026  0.567975000E+04  0.000000000E+00  0.000000000E+00 -0.179290771E-03",
        " 0.7449627E+00  0.1131916E-01  0.2088318E+00  0.3948212E-03 -0.2656191E+04 -0.4272461E-03",
        "10 08 2026   20680",
        " 2  -4  1  08 08 2026  0.106102188E+05  0.000000000E+00  0.000000000E+00 -0.114440918E-04",
        " 0.6452951E+00  0.1358891E-01 -0.7549438E+00  0.2023697E-02 -0.2656049E+04 -0.5493164E-03",
    )

    /**
     * The record is recognised by its four-field index line and nothing else. A looser rule picks up
     * the IAC's own trailing content and produces slots that do not exist.
     */
    @Test
    fun glonassTripletsAreFoundByTheirIndexLine() {
        val out = Almanac.parseAgl(agl)
        assertEquals(setOf(1, 2), out.keys)
        assertEquals(1, out.getValue(1).channel)
        assertEquals(-4, out.getValue(2).channel)
        assertEquals(LocalDate.of(2026, 8, 8), out.getValue(1).date)
        assertEquals(5679.75, out.getValue(1).tLambda, 1e-9)
        assertEquals(0.7449627, out.getValue(1).lambda, 1e-12)
        assertEquals(-2656.191, out.getValue(1).deltaT, 1e-9)
    }

    /**
     * N_A is the day inside the four-year interval. Verified against Huawei's own capture, which
     * carries N_A 967 for 2026-08-24 — an off-by-one or a wrong interval start moves every GLONASS
     * satellite along its orbit by a day.
     */
    @Test
    fun glonassNaMatchesHuaweisOwnCapturedValue() {
        assertEquals(967, glonassNaFromDate(LocalDate.of(2026, 8, 24)))
        assertEquals(1, glonassNaFromDate(LocalDate.of(2024, 1, 1)))
        assertEquals(366, glonassNaFromDate(LocalDate.of(2024, 12, 31)))
        assertEquals(367, glonassNaFromDate(LocalDate.of(2025, 1, 1)))
    }

    // ── RINEX ──────────────────────────────────────────────────────────────────────────────────

    private val rinexHeader = lines(
        "     3.05           NAVIGATION DATA     MIXED               RINEX VERSION / TYPE",
        "BDSA   3.2596e-08  6.7055e-08 -1.0133e-06  1.5497e-06       IONOSPHERIC CORR    ",
        "BDSB   1.1264e+05  2.6214e+05 -3.9322e+05  6.5536e+04       IONOSPHERIC CORR    ",
        "GAL    5.5000e+01  1.7188e-01  4.5776e-03  0.0000e+00       IONOSPHERIC CORR    ",
        "GPSA   2.9800e-08  7.4510e-09 -1.7880e-07   .0000E+00       IONOSPHERIC CORR    ",
        "GPSB   1.3310e+05  0.0000e+00 -2.6210e+05   .6554E+05       IONOSPHERIC CORR    ",
        "GPUT -9.3132257462E-10 8.881784197E-16 233472 2434          TIME SYSTEM CORR    ",
        "    18                                                      LEAP SECONDS        ",
        "                                                            END OF HEADER       ",
        "C01 2026 08 30 01 00 00-8.493661880493e-07 7.815970093361e-14 0.000000000000e+00",
    )

    /**
     * A mixed BRDC file carries a dozen BDSA/BDSB lines BEFORE the GPS ones. Matching on
     * "IONOSPHERIC CORR" alone, or reading whichever ionosphere comes first, hands the band BeiDou's
     * coefficients as GPS's — a silent, plausible, wrong ionosphere.
     */
    @Test
    fun rinexTakesTheGpsIonosphereAndNotTheBeidouLinesAboveIt() {
        val (klob, _) = Almanac.parseRinexHeader(rinexHeader)
        assertEquals(2.98e-08, klob.alpha[0], 1e-20)
        assertEquals(7.451e-09, klob.alpha[1], 1e-20)
        assertEquals(-1.788e-07, klob.alpha[2], 1e-20)
        assertEquals(133100.0, klob.beta[0], 1e-6)
    }

    /**
     * RINEX writes a zero as `  .0000E+00`, with no digit before the point, and pads unused fields
     * with spaces. A parser that requires a leading digit drops the coefficient; one that trims and
     * splits reads the label as data.
     */
    @Test
    fun rinexReadsAFieldWithNoLeadingDigit() {
        val (klob, _) = Almanac.parseRinexHeader(rinexHeader)
        assertEquals(0.0, klob.alpha[3], 0.0)
        assertEquals(65540.0, klob.beta[3], 1.0)
    }

    /** The GPUT columns are fixed; reading them by split picks up the two coefficients instead. */
    @Test
    fun rinexReadsTheUtcParametersFromTheirOwnColumns() {
        val (_, utc) = Almanac.parseRinexHeader(rinexHeader)
        assertEquals(233472.0, utc.tOt, 1e-9)
        assertEquals(2434, utc.wnT)
        assertEquals(18, utc.dtLS)
    }

    /**
     * With no leap second pending, the header carries ONE number. The three future fields must come
     * back null so the caller copies the capture's "none pending" bytes instead of inventing them —
     * a zero there tells the band a leap second lands at week 0.
     */
    @Test
    fun rinexLeavesTheFutureLeapFieldsNullWhenNoneIsPending() {
        val (_, utc) = Almanac.parseRinexHeader(rinexHeader)
        assertNull(utc.dtLSF)
        assertNull(utc.wnLSF)
        assertNull(utc.dn)
    }

    private val rinexBds = lines(
        "C01 2026 08 30 01 00 00-8.493661880493e-07 7.815970093361e-14 0.000000000000e+00",
        "     1.000000000000e+00 6.103125000000e+01 2.097230215199e-09-9.957537287511e-01",
        "     1.850072294474e-06 5.270406836644e-04 2.714246511459e-05 6.493338323593e+03",
        "     3.600000000000e+03-5.820766091347e-08-2.880519757736e+00 7.683411240578e-08",
        "     7.994812193161e-02-8.236718750000e+02 2.987151034374e-01-1.140761803022e-09",
        "     1.003613233091e-10                    1.078000000000e+03                   ",
        "     2.000000000000e+00 0.000000000000e+00 4.499999928242e-09 4.500000000000e-09",
        "     3.600000000000e+03 1.000000000000e+00",
    )

    /**
     * The line that carries the week has a BLANK field before it and another after. Splitting on
     * whitespace shifts everything past the blank by one, which puts the week where i-dot was: the
     * satellite then lands in the wrong BeiDou week and the whole almanac record is a week out.
     */
    @Test
    fun rinexBlankFieldStillOccupiesItsSlot() {
        val r = Almanac.parseRinexBds(rinexBds).getValue(1).single()
        assertEquals(1078, r.week)
        assertEquals(1.003613233091e-10, r.idot, 1e-24)
        assertEquals(3600.0, r.toe, 1e-9)
        assertEquals(1078 * 604800.0 + 3600.0, r.toeAbs, 1e-6)
    }

    /** The rest of the record, so a column slip anywhere in the eight lines is caught. */
    @Test
    fun rinexReadsEveryFieldOfABeidouRecord() {
        val r = Almanac.parseRinexBds(rinexBds).getValue(1).single()
        assertEquals(-8.493661880493e-07, r.af0, 1e-20)
        assertEquals(6.103125e+01, r.crs, 1e-9)
        assertEquals(-9.957537287511e-01, r.m0, 1e-12)
        assertEquals(5.270406836644e-04, r.e, 1e-16)
        assertEquals(6.493338323593e+03, r.sqrtA, 1e-9)
        assertEquals(7.994812193161e-02, r.i0, 1e-12)
        assertEquals(2.987151034374e-01, r.omega, 1e-12)
        assertEquals(-1.140761803022e-09, r.omegaDot, 1e-21)
    }

    /**
     * Two navigation files are merged to lengthen the arc, and they OVERLAP. De-duplication is on
     * absolute BDT seconds; without it the same epoch is fitted twice and weights that instant
     * double, and with a seconds-of-week key the week roll makes two different epochs collide.
     */
    @Test
    fun mergingASecondDayDropsTheEpochsAlreadyPresent() {
        val a = Almanac.parseRinexBds(rinexBds)
        val b = Almanac.parseRinexBds(rinexBds)
        Almanac.mergeBdsNav(a, b)
        assertEquals(1, a.getValue(1).size)

        val shifted = Almanac.parseRinexBds(rinexBds.replace("3.600000000000e+03-5.82", "7.200000000000e+03-5.82"))
        Almanac.mergeBdsNav(a, shifted)
        assertEquals(2, a.getValue(1).size)
        assertTrue(a.getValue(1).map { it.toe }.containsAll(listOf(3600.0, 7200.0)))
    }
}
