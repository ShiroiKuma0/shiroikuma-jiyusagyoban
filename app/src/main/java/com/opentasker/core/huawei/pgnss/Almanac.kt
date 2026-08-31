package com.opentasker.core.huawei.pgnss

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The four broadcast almanacs, the Klobuchar ionosphere, the GPS UTC parameter set and the
 * GLONASS frequency-channel table that go into `HW_PGNSS_EXTRA`.
 *
 * This is a faithful port of the parsing half of `scripts/pgnss-extra-build.py`; the file that
 * script produced is live on 白い熊's band, so the reference is not a specification but a working
 * program, and the port follows it field for field, including its column offsets and its rounding.
 *
 * PURE JVM ON PURPOSE. Nothing here touches Android, so the whole almanac layer can be exercised
 * by a plain JUnit test against the very files the Python was run on. That is the only way to grade
 * this code against something that does not share its assumptions.
 *
 * SATELLITE NUMBERING. Every constellation except GLONASS is stored by Huawei as a 0-BASED index
 * (`field = PRN - 1`); GLONASS stores the true slot 1..24. These structures hold the TRUE PRN/SVID/
 * slot, and [PgnssExtraFile] does the subtraction at the single point where the bytes are written.
 * Keeping the minus-one in one place is deliberate: applying it twice puts Galileo 45 120 km out,
 * which is how the convention was settled in the first place.
 */

// ── physical and time constants ───────────────────────────────────────────────────────────────

/** Earth rotation rate, rad/s (WGS-84 / GTRF / CGCS2000 all agree to well inside our needs). */
const val OMEGA_E: Double = 7.2921151467e-5

/** Galileo / BeiDou / GLONASS gravitational constant, m^3/s^2. */
const val MU: Double = 3.986004418e14

/** Unix seconds at the GPS epoch, 1980-01-06T00:00:00Z. */
const val UNIX_GPS: Long = 315964800L

/** GPS week minus BeiDou week. */
const val BDS_WEEK_OFFSET: Int = 1356

/** GPST minus BDT, seconds. */
const val BDS_SECOND_OFFSET: Int = 14

/** Seconds in a GPS week. */
const val WEEK_SECONDS: Double = 604800.0

/**
 * BeiDou's geostationary slots. Their almanac delta-i is referenced to 0.00 semicircles while every
 * other BeiDou satellite uses 0.30 — measured against Huawei's own captured records, which land on
 * the published station longitudes (C01 140.02E vs 140.0) with 0.00 and 5-16 degrees away with 0.30.
 * With 0.30 the 16-bit 2^-19 field also saturates for any GEO, silently clamping every one of them
 * to i = 42.75 deg and throwing it 20 000 km out.
 */
val BDS_GEO: Set<Int> = ((1..5) + (59..63)).toSet()

/** The delta-i reference, in semicircles, for a BeiDou PRN. See [BDS_GEO]. */
fun bdsDeltaIReference(prn: Int): Double = if (prn in BDS_GEO) 0.0 else 0.30

// ── parsed structures ─────────────────────────────────────────────────────────────────────────

/** One YUMA almanac record. Angles in radians, [toa] in seconds of week, [week] a FULL GPS week. */
data class GpsAlmanacEntry(
    val prn: Int,
    val week: Int,
    val toa: Double,
    val e: Double,
    val i0: Double,
    val omegaDot: Double,
    val sqrtA: Double,
    val omega0: Double,
    val omega: Double,
    val m0: Double,
    val af0: Double,
    val af1: Double,
    val health: Int,
)

/** One ESA GSSC almanac record. Angles are SEMICIRCLES as published; [dSqrtA] is the offset from
 *  5440.588203 m^(1/2) and [deltaI] the offset from 56 degrees, exactly as Galileo broadcasts them. */
data class GalileoAlmanacEntry(
    val svid: Int,
    val week: Int,
    val t0a: Double,
    val dSqrtA: Double,
    val e: Double,
    val deltaI: Double,
    val omega0: Double,
    val omegaDot: Double,
    val omega: Double,
    val m0: Double,
    val af0: Double,
    val af1: Double,
    val health: Int,
)

/** One IAC `.agl` record — already in the parameterisation Huawei stores. Angles in semicircles. */
data class GlonassAlmanacEntry(
    val slot: Int,
    val channel: Int,
    val na: Int,
    val date: LocalDate,
    val tLambda: Double,
    val tau: Double,
    val lambda: Double,
    val deltaI: Double,
    val omega: Double,
    val e: Double,
    val deltaT: Double,
    val deltaTDot: Double,
)

/** GPS Klobuchar ionosphere coefficients in SI, as the RINEX header publishes them. */
data class KlobucharSet(val alpha: DoubleArray, val beta: DoubleArray) {
    init {
        require(alpha.size == 4 && beta.size == 4) { "Klobuchar needs alpha[4] and beta[4]" }
    }

    override fun equals(other: Any?): Boolean =
        other is KlobucharSet && alpha.contentEquals(other.alpha) && beta.contentEquals(other.beta)

    override fun hashCode(): Int = 31 * alpha.contentHashCode() + beta.contentHashCode()
}

/**
 * The GPS UTC parameter set. [dtLSF], [wnLSF] and [dn] advertise the NEXT leap second and are null
 * whenever the RINEX header carries only the current value — which is the normal case. The caller
 * then copies those three bytes from the captured reference rather than inventing "none pending".
 */
data class UtcParameters(
    val tOt: Double,
    val wnT: Int,
    val dtLS: Int,
    val dtLSF: Int?,
    val wnLSF: Int?,
    val dn: Int?,
)

/** One BeiDou broadcast ephemeris record. Times are BDT; [toeAbs] is week*604800 + toe. */
data class BdsNavRecord(
    val prn: Int,
    val af0: Double,
    val af1: Double,
    val af2: Double,
    val aode: Double,
    val crs: Double,
    val dn: Double,
    val m0: Double,
    val cuc: Double,
    val e: Double,
    val cus: Double,
    val sqrtA: Double,
    val toe: Double,
    val cic: Double,
    val omega0: Double,
    val cis: Double,
    val i0: Double,
    val crc: Double,
    val omega: Double,
    val omegaDot: Double,
    val idot: Double,
    val week: Int,
    val toeAbs: Double,
)

// ── time ──────────────────────────────────────────────────────────────────────────────────────

/** Full GPS seconds for a unix instant. */
fun gpsFromUnix(unixSeconds: Double, leap: Int = 18): Double = unixSeconds - UNIX_GPS + leap

/** Unix seconds for a full GPS time. */
fun unixFromGps(gpsSeconds: Double, leap: Int = 18): Double = gpsSeconds + UNIX_GPS - leap

/**
 * GLONASS N_A: the day number inside the current four-year interval, 1-based.
 * Verified against Huawei's own capture — N_A 967 is 2026-08-24 — not against our decoder.
 */
fun glonassNaFromDate(date: LocalDate): Int {
    val start = LocalDate.of(date.year - Math.floorMod(date.year - 2024, 4), 1, 1)
    return (date.toEpochDay() - start.toEpochDay()).toInt() + 1
}

// ── parsers ───────────────────────────────────────────────────────────────────────────────────

/** Everything that turns a downloaded almanac source into the structures above. */
object Almanac {

    /**
     * Navcen YUMA -> PRN-keyed records.
     *
     * YUMA carries the week MODULO 1024, so it is lifted into the era around [nowGps]. Shipping the
     * raw mod-1024 week would date the almanac to 1999 and the band would treat it as expired.
     */
    fun parseYuma(text: String, nowGps: Double): Map<Int, GpsAlmanacEntry> {
        val out = LinkedHashMap<Int, GpsAlmanacEntry>()
        // The Python splits on the "******" banner and takes everything after the first one.
        val blocks = text.split("******").drop(1)
        for (block in blocks) {
            val fields = keyedLines(block)
            val id = fields["ID"]?.toIntOrNull() ?: continue
            val wk = fields.required("week", id).toInt()
            val era = Math.rint((nowGps / WEEK_SECONDS - wk) / 1024.0).toInt()
            out[id] = GpsAlmanacEntry(
                prn = id,
                week = wk + 1024 * era,
                toa = fields.required("Time of Applicability(s)", id).toDouble(),
                e = fields.required("Eccentricity", id).toDouble(),
                i0 = fields.required("Orbital Inclination(rad)", id).toDouble(),
                omegaDot = fields.required("Rate of Right Ascen(r/s)", id).toDouble(),
                // Two spaces inside the label are part of Navcen's own text, not a typo here.
                sqrtA = fields.required("SQRT(A)  (m 1/2)", id).toDouble(),
                omega0 = fields.required("Right Ascen at Week(rad)", id).toDouble(),
                omega = fields.required("Argument of Perigee(rad)", id).toDouble(),
                m0 = fields.required("Mean Anom(rad)", id).toDouble(),
                af0 = fields.required("Af0(s)", id).toDouble(),
                af1 = fields.required("Af1(s/s)", id).toDouble(),
                health = fields.required("Health", id).toInt(),
            )
        }
        return out
    }

    /**
     * ESA GSSC almanac XML -> SVID-keyed records.
     *
     * Scanned with a tag reader rather than `javax.xml.parsers`: on Android the unit-test classpath
     * puts the stubbed `android.jar` in front of the JDK, so `DocumentBuilderFactory` throws
     * "not mocked" in exactly the tests that are supposed to prove this parser right. The document
     * is a flat, namespace-free, machine-generated dump of one shape, so a tag reader loses nothing.
     *
     * The `wna` field in the XML is the 2-bit broadcast week; the full week is recovered from the
     * issue date, which is by construction within a few hours of t0a.
     */
    fun parseGssc(xml: String): Map<Int, GalileoAlmanacEntry> {
        val issue = firstTagText(xml, "issueDate")
            ?: throw IllegalArgumentException("Galileo almanac XML has no issueDate")
        val issued = LocalDateTime.parse(issue.take(19)).toEpochSecond(ZoneOffset.UTC).toDouble()
        val issuedGps = gpsFromUnix(issued)

        val out = LinkedHashMap<Int, GalileoAlmanacEntry>()
        for (block in blocks(xml, "svAlmanac")) {
            val svid = firstTagText(block, "SVID")?.trim()?.toIntOrNull() ?: continue
            val t0a = tagDouble(block, "t0a")
            // health = the worse of the two I/NAV signal statuses, as the Python takes it.
            val e5b = firstTagText(block, "statusE5b")?.trim()?.toIntOrNull() ?: 0
            val e1b = firstTagText(block, "statusE1B")?.trim()?.toIntOrNull() ?: 0
            out[svid] = GalileoAlmanacEntry(
                svid = svid,
                week = Math.rint((issuedGps - t0a) / WEEK_SECONDS).toInt(),
                t0a = t0a,
                dSqrtA = tagDouble(block, "aSqRoot"),
                e = tagDouble(block, "ecc"),
                deltaI = tagDouble(block, "deltai"),
                omega0 = tagDouble(block, "omega0"),
                omegaDot = tagDouble(block, "omegaDot"),
                omega = tagDouble(block, "w"),
                m0 = tagDouble(block, "m0"),
                af0 = tagDouble(block, "af0"),
                af1 = tagDouble(block, "af1"),
                health = maxOf(e5b, e1b),
            )
        }
        if (out.isEmpty()) throw IllegalArgumentException("Galileo almanac XML has no svAlmanac blocks")
        return out
    }

    /**
     * IAC GLONASS `.agl` -> slot-keyed records.
     *
     * The file is triplets: a 4-field index line, then two data lines. The record is recognised by
     * that 4-field line and nothing else, exactly as the Python does — the IAC pads the file with
     * other content and a looser rule picks it up.
     */
    fun parseAgl(text: String): Map<Int, GlonassAlmanacEntry> {
        val lines = text.split("\n")
        val out = LinkedHashMap<Int, GlonassAlmanacEntry>()
        var i = 0
        while (i + 2 < lines.size) {
            val head = lines[i]
            if (head.isNotBlank() && words(head).size == 4) {
                val f = words(lines[i + 1])
                val g = words(lines[i + 2])
                if (f.size >= 10 && g.size >= 6) {
                    val date = LocalDate.of(f[5].toInt(), f[4].toInt(), f[3].toInt())
                    val slot = f[0].toInt()
                    out[slot] = GlonassAlmanacEntry(
                        slot = slot,
                        channel = f[1].toInt(),
                        na = glonassNaFromDate(date),
                        date = date,
                        tLambda = f[6].toDouble(),
                        tau = f[9].toDouble(),
                        lambda = g[0].toDouble(),
                        deltaI = g[1].toDouble(),
                        omega = g[2].toDouble(),
                        e = g[3].toDouble(),
                        deltaT = g[4].toDouble(),
                        deltaTDot = g[5].toDouble(),
                    )
                }
                i += 3
            } else {
                i += 1
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("no GLONASS almanac records in the .agl file")
        return out
    }

    /**
     * RINEX 3 navigation header -> Klobuchar coefficients and the GPS UTC parameter set.
     *
     * The header is COLUMN-formatted, and the columns are the ones the format fixes, not the ones a
     * whitespace split would find: a mixed BRDC file carries a dozen BDSA/BDSB lines before GPSA,
     * and several fields ("  .0000E+00") have no leading digit at all.
     */
    fun parseRinexHeader(text: String): Pair<KlobucharSet, UtcParameters> {
        var alpha: DoubleArray? = null
        var beta: DoubleArray? = null
        var tOt: Double? = null
        var wnT: Int? = null
        var dtLS: Int? = null
        var dtLSF: Int? = null
        var wnLSF: Int? = null
        var dn: Int? = null

        for (raw in text.lineSequence()) {
            if (raw.contains("END OF HEADER")) break
            val label = raw.drop(60).trim()
            val kind = raw.take(4)
            when {
                label == "IONOSPHERIC CORR" && kind == "GPSA" && alpha == null ->
                    alpha = DoubleArray(4) { k -> rinexDouble(raw, 5 + 12 * k, 17 + 12 * k) }
                label == "IONOSPHERIC CORR" && kind == "GPSB" && beta == null ->
                    beta = DoubleArray(4) { k -> rinexDouble(raw, 5 + 12 * k, 17 + 12 * k) }
                label == "TIME SYSTEM CORR" && kind == "GPUT" -> {
                    tOt = rinexDouble(raw, 38, 45)
                    wnT = slice(raw, 45, 50).trim().toInt()
                }
                label == "LEAP SECONDS" -> {
                    val v = words(slice(raw, 0, 60))
                    if (v.isNotEmpty()) {
                        dtLS = v[0].toInt()
                        if (v.size >= 4) {
                            dtLSF = v[1].toInt()
                            wnLSF = v[2].toInt()
                            dn = v[3].toInt()
                        }
                    }
                }
            }
        }
        val a = alpha ?: throw IllegalArgumentException("RINEX header carries no GPSA ionospheric correction")
        val b = beta ?: throw IllegalArgumentException("RINEX header carries no GPSB ionospheric correction")
        val utc = UtcParameters(
            tOt = tOt ?: throw IllegalArgumentException("RINEX header carries no GPUT time-system correction"),
            wnT = wnT ?: throw IllegalArgumentException("RINEX header carries no GPUT week"),
            dtLS = dtLS ?: throw IllegalArgumentException("RINEX header carries no LEAP SECONDS"),
            dtLSF = dtLSF,
            wnLSF = wnLSF,
            dn = dn,
        )
        return KlobucharSet(a, b) to utc
    }

    /**
     * RINEX 3 navigation body -> BeiDou broadcast ephemeris, keyed by PRN.
     *
     * The epoch line carries three values at columns 23/42/61 and each of the seven continuation
     * lines four at 4/23/42/61. A BLANK FIELD STILL OCCUPIES A SLOT — reading by whitespace split
     * shifts every field after the first blank one, which in this file is the value right before
     * the BDT week.
     */
    fun parseRinexBds(text: String): MutableMap<Int, MutableList<BdsNavRecord>> {
        val lines = text.split("\n")
        val out = LinkedHashMap<Int, MutableList<BdsNavRecord>>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (EPOCH_LINE.containsMatchIn(line)) {
                val prn = line.substring(1, 3).toInt()
                val vals = ArrayList<Double>(31)
                for (col in intArrayOf(23, 42, 61)) vals.add(rinexField(line, col))
                for (k in 1..7) {
                    val cont = if (i + k < lines.size) lines[i + k] else ""
                    for (col in intArrayOf(4, 23, 42, 61)) vals.add(rinexField(cont, col))
                }
                if (vals.size >= 27) {
                    val week = vals[21].toInt()
                    out.getOrPut(prn) { ArrayList() }.add(
                        BdsNavRecord(
                            prn = prn,
                            af0 = vals[0], af1 = vals[1], af2 = vals[2],
                            aode = vals[3], crs = vals[4], dn = vals[5], m0 = vals[6],
                            cuc = vals[7], e = vals[8], cus = vals[9], sqrtA = vals[10],
                            toe = vals[11], cic = vals[12], omega0 = vals[13], cis = vals[14],
                            i0 = vals[15], crc = vals[16], omega = vals[17], omegaDot = vals[18],
                            idot = vals[19], week = week,
                            toeAbs = week * WEEK_SECONDS + vals[11],
                        ),
                    )
                }
                i += 8
            } else {
                i += 1
            }
        }
        return out
    }

    /**
     * Merge a second navigation file into the first, skipping records already present.
     *
     * A RINEX day published mid-day holds only the hours already elapsed, which is too short an arc
     * to fit mean elements to; the previous day supplies the rest. De-duplication is on ABSOLUTE BDT
     * seconds, so the week roll between the two files is a non-issue.
     */
    fun mergeBdsNav(
        into: MutableMap<Int, MutableList<BdsNavRecord>>,
        extra: Map<Int, List<BdsNavRecord>>,
    ) {
        for ((prn, records) in extra) {
            val existing = into.getOrPut(prn) { ArrayList() }
            val seen = existing.mapTo(HashSet()) { it.toeAbs }
            existing.addAll(records.filter { it.toeAbs !in seen })
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────

    private val EPOCH_LINE = Regex("^C\\d\\d \\d{4} ")

    /** `label: value` lines, mirroring the Python's `^\s*([A-Za-z0-9()/ .]+?):\s+(\S+)\s*$`. */
    private val KEYED_LINE = Regex("^\\s*([A-Za-z0-9()/ .]+?):\\s+(\\S+)\\s*$", RegexOption.MULTILINE)

    private fun keyedLines(block: String): Map<String, String> =
        KEYED_LINE.findAll(block).associate { it.groupValues[1] to it.groupValues[2] }

    private fun Map<String, String>.required(key: String, prn: Int): String =
        this[key] ?: throw IllegalArgumentException("YUMA record PRN $prn has no \"$key\" field")

    private fun words(s: String): List<String> =
        s.trim().split(WHITESPACE).filter { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")

    private fun slice(line: String, from: Int, to: Int): String =
        if (from >= line.length) "" else line.substring(from, minOf(to, line.length))

    /** Fortran `D` exponents are Fortran's; everything else is ordinary. Blank reads as zero. */
    private fun rinexDouble(line: String, from: Int, to: Int): Double {
        val t = slice(line, from, to).trim()
        return if (t.isEmpty()) 0.0 else t.replace("D", "E").toDouble()
    }

    private fun rinexField(line: String, col: Int): Double = rinexDouble(line, col, col + 19)

    private fun blocks(xml: String, tag: String): List<String> {
        val open = "<$tag>"
        val close = "</$tag>"
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val a = xml.indexOf(open, from)
            if (a < 0) break
            val b = xml.indexOf(close, a)
            if (b < 0) break
            out.add(xml.substring(a + open.length, b))
            from = b + close.length
        }
        return out
    }

    private fun firstTagText(xml: String, tag: String): String? {
        val open = "<$tag>"
        val a = xml.indexOf(open)
        if (a < 0) return null
        val b = xml.indexOf("</$tag>", a)
        if (b < 0) return null
        return xml.substring(a + open.length, b)
    }

    private fun tagDouble(xml: String, tag: String): Double =
        firstTagText(xml, tag)?.trim()?.toDouble()
            ?: throw IllegalArgumentException("Galileo almanac record has no <$tag>")
}

// ── shared numeric helpers ────────────────────────────────────────────────────────────────────

/**
 * Round exactly as Python's `round()` does — ties to EVEN, correctly rounded.
 *
 * `Math.round` rounds ties up, which is a different function; using it would make the Kotlin build
 * differ from the reference build by one least-significant bit whenever a scaled value lands on a
 * half, and a one-bit difference in a field the band reads is indistinguishable from a porting bug.
 */
fun pyRound(v: Double): Long = Math.rint(v).toLong()

/** Signed [bits]-wide field, saturating rather than wrapping. */
fun signedField(v: Double, bits: Int): Long {
    val lo = -(1L shl (bits - 1))
    val hi = (1L shl (bits - 1)) - 1
    return pyRound(v).coerceIn(lo, hi)
}

/** Unsigned [bits]-wide field, saturating rather than wrapping. */
fun unsignedField(v: Double, bits: Int): Long {
    val hi = (1L shl bits) - 1
    return pyRound(v).coerceIn(0L, hi)
}

/** radians -> semicircles wrapped into [-1, 1). */
fun semicircles(radians: Double): Double {
    val x = radians / Math.PI + 1.0
    // Python's % is floored; Kotlin's % is truncated and returns a negative for a negative left
    // operand, which would wrap a western right-ascension to +1 instead of -1.
    val m = x.mod(2.0)
    return m - 1.0
}
