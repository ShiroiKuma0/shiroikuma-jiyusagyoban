package com.opentasker.core.huawei.pgnss

import com.opentasker.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * BeiDou's group delay comes out of the broadcast file, and this is the measurement that says so.
 *
 * ## The claim being tested
 *
 * Bytes 20-23 of a BeiDou record were believed underivable — a hardware calibration, in no orbit
 * product — and were lifted from a capture of Huawei's own set, which is why a phone without that
 * capture could not build. They are `TGD1`, which the satellites BROADCAST and which the RINEX
 * navigation file this build already downloads carries at `BROADCAST ORBIT - 6` field 3.
 *
 * So the test is a comparison between two committed fixtures that have never been reconciled with
 * each other: Huawei's own capture, and a broadcast file from the same days. If the encoding were
 * wrong in scale, sign or position, they could not agree.
 *
 * ## Why two counts and not zero
 *
 * A count is 0.1 ns, about 3 cm of range. The capture and the broadcast file are from different
 * hours, and the control segment re-estimates these; the residual is that drift. Demanding equality
 * would be demanding that a measured quantity not change, and would fail on the next fixture.
 */
class BdsGroupDelayTest {

    private val broadcast = Records.bdsTails(
        Almanac.parseRinexBds(String(PgnssFixtures.bytes("e2e-brdc.rnx"), Charsets.ISO_8859_1)),
    )

    private val captured = Almanac.let {
        val tails = LinkedHashMap<Int, ByteArray>()
        val bytes = PgnssFixtures.bytes("captured/HW_PGNSS_BDS")
        // Same walk CapturedSet.read does, kept here so this test grades the ENCODER rather than
        // agreeing with whatever that happens to do today.
        for (block in 0 until Records.BLOCKS) {
            val off = u32(bytes, 12 * block + 4)
            if (off < 1008 || off + 4 > bytes.size) continue
            val count = u32(bytes, off)
            for (k in 0 until count) {
                val from = off + 4 + k * Records.BDS_RECLEN
                if (from + Records.BDS_RECLEN > bytes.size) break
                tails[u16(bytes, from)] = bytes.copyOfRange(from + 20, from + 24)
            }
        }
        tails
    }

    private fun u16(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int) =
        (0..3).sumOf { (b[off + it].toInt() and 0xFF) shl (8 * it) }

    private fun counts(tail: ByteArray): Int =
        ((tail[2].toInt() and 0xFF) or (tail[3].toInt() shl 8)).toShort().toInt()

    @Test
    fun `the broadcast group delay is the captured one`() {
        val shared = captured.keys.intersect(broadcast.keys)
        assertTrue("the fixtures share no satellites — one of them is not what it claims", shared.size >= 25)
        val disagreed = shared.filter { abs(counts(broadcast.getValue(it)) - counts(captured.getValue(it))) > 2 }
        assertEquals(
            "these satellites' derived group delay is not the captured one: " +
                disagreed.joinToString(", ") {
                    "C%02d broadcast=%d captured=%d".format(it + 1, counts(broadcast.getValue(it)), counts(captured.getValue(it)))
                },
            emptyList<Int>(),
            disagreed,
        )
    }

    /** Bytes 20-21 are zero in every captured record, and must stay zero in every derived one. */
    @Test
    fun `only the low half of the tail is written`() {
        for ((idx, tail) in broadcast) {
            assertEquals("C%02d writes byte 20".format(idx + 1), 0, tail[0].toInt())
            assertEquals("C%02d writes byte 21".format(idx + 1), 0, tail[1].toInt())
            assertEquals(4, tail.size)
        }
    }

    /**
     * The point of the change: the broadcast file knows about satellites the capture never held.
     *
     * They were not merely given a zero delay — they were not SHIPPED, because the capture decided
     * which satellites were carried as well as what their delay was.
     */
    @Test
    fun `the broadcast file covers satellites the capture never had`() {
        val extra = broadcast.keys - captured.keys
        assertTrue(
            "the broadcast fixture adds nothing over the capture, so nothing here is being gained",
            extra.isNotEmpty(),
        )
    }

    /**
     * A half-written navigation file is one with no GPS records, and this is what sees it.
     *
     * BKG publishes by writing in place, so a fetch inside that window gets a valid gzip of an
     * incomplete file — measured as a 134 kB `BRDC00IGS_R` with C, E and R and no G, J, I or S.
     */
    @Test
    fun `record counts see a partial file`() {
        val full = Almanac.countRinexRecords(
            String(PgnssFixtures.bytes("e2e-brdc.rnx"), Charsets.ISO_8859_1),
        )
        assertTrue("the fixture must carry BeiDou records to count", (full['C'] ?: 0) > 100)
        val partial = Almanac.countRinexRecords(
            """
            |     3.05           N: GNSS NAV DATA    M: MIXED            RINEX VERSION / TYPE
            |                                                            END OF HEADER
            |C01 2026 08 30 00 00 00-1.000000000000E-04 0.0E+00 0.0E+00
            |    0.0E+00 0.0E+00 0.0E+00 0.0E+00
            |E01 2026 08 30 00 00 00-1.000000000000E-04 0.0E+00 0.0E+00
            """.trimMargin(),
        )
        assertEquals(1, partial['C'])
        assertEquals(1, partial['E'])
        assertEquals(null, partial['G'])
    }
}

/**
 * `spanned` must refuse a time OUTSIDE its stencil, not merely a holed one.
 *
 * This is the bug that produced every impossible BeiDou number this project has ever seen. The
 * check is cheap; recognising a 3.3e13 m "divergence" as a measurement artefact rather than a
 * physical claim cost two days.
 */
class Sp3StencilTest {

    /** 300 s samples, contiguous, exactly as a merged orbit product looks. */
    private val times = DoubleArray(64) { 1_000_000.0 + it * 300.0 }

    @Test
    fun `a time inside the series is spanned`() {
        assertTrue(Sp3.spanned(times, times[0]))
        assertTrue(Sp3.spanned(times, times[30] + 150.0))
        assertTrue(Sp3.spanned(times, times[times.size - 1]))
    }

    @Test
    fun `a time before the first sample is not`() {
        assertFalse("this is the extrapolation trap", Sp3.spanned(times, times[0] - 1.0))
        assertFalse(Sp3.spanned(times, times[0] - 86400.0))
    }

    @Test
    fun `a time after the last sample is not`() {
        assertFalse(Sp3.spanned(times, times[times.size - 1] + 1.0))
        assertFalse(Sp3.spanned(times, times[times.size - 1] + 86400.0))
    }

    /** And the hole it was written for is still caught. */
    @Test
    fun `a stencil straddling a day-long hole is not spanned`() {
        val holed = DoubleArray(20) { if (it < 10) 1_000_000.0 + it * 300.0 else 1_100_000.0 + it * 300.0 }
        assertFalse(Sp3.spanned(holed, holed[9] + 150.0))
    }
}

/**
 * What is seeded is exactly what is read back — no more, and no less.
 *
 * Two failures are possible and each is quiet. Seeding too little makes a phone unable to build and
 * says so only on the next run; seeding too much puts bytes nothing reads into the store and, since
 * the store travels, into every backup made from it. This compares the two lists directly.
 */
class CapturedSeedTest {

    private val source = ProductionSources.read("com/opentasker/core/huawei/pgnss/PredictedSet.kt")

    /** The names `CapturedSet.read` opens, taken from the reader itself. */
    private val read: Set<String> = run {
        val body = ProductionSources.block(
            "com/opentasker/core/huawei/pgnss/PredictedSet.kt",
            "fun read(dir: File): CapturedSet {",
            "/** Walk every record",
        )
        val constants = Regex("""(NAME_[A-Z]+) = "([^"]+)"""").findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }
        constants.filterKeys { body.contains("PredictedSet.$it") }.values.toSet()
    }

    @Test
    fun `the seed copies exactly the files the capture is read for`() {
        val seeded = Regex("""val CAPTURED_NAMES = listOf\(([^)]*)\)""").find(source)
            ?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.mapNotNull { Regex("""$it = "([^"]+)"""").find(source)?.groupValues?.get(1) }
            ?.toSet()
        assertEquals("CapturedSet.read and CAPTURED_NAMES disagree", read, seeded)
        assertTrue("the reader must open at least the two underivable files", read.size >= 2)
    }

    /** And the copy loop uses that list, not the full set of six. */
    @Test
    fun `seedCaptured walks the narrowed list`() {
        val body = ProductionSources.block(
            "com/opentasker/core/huawei/pgnss/PredictedSet.kt",
            "fun seedCaptured(",
            "// ── ",
        )
        assertTrue("seedCaptured is back on all six names", body.contains("for (name in CAPTURED_NAMES)"))
    }
}
