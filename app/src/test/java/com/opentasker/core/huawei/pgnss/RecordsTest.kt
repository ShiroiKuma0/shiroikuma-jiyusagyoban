package com.opentasker.core.huawei.pgnss

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoders and the assembler, graded BYTE FOR BYTE against `scripts/pgnss-build.py`.
 *
 * This is the acceptance test, and it is deliberately not graded against a decoder of our own. Four
 * catastrophic bugs survived a full day in the reference because it measured its fit against a clock
 * it had itself corrupted, reporting 0.3 m while shipping 53 km; a port checked with its own decoder
 * would repeat exactly that mistake one language down. So: the reference's bytes, or nothing.
 *
 * `the whole GLONASS file is byte-identical` is the strongest of these, because it is end to end —
 * the trimmed SP3 goes in, the interpolation, the symmetric-difference velocity and acceleration,
 * the luni-solar subtraction, the Moscow-time tb, the negated tau and the block layout all run, and
 * 31 056 bytes come out. Nothing in that path is a fit, so it is reproducible exactly, and every one
 * of those steps has been wrong at least once: tau written un-negated cost a median 37.9 km of range
 * bias on 100 % of records, tb from GPS time-of-day ran three hours low, sub-epoch 0 on the block
 * stamp instead of the UTC hour put every state 59 min 42 s late, and byte 12 written as 0 rather
 * than 1 disagreed with all 11 960 of Huawei's.
 */
class RecordsTest {

    @Test
    fun `rounding is half-to-even and clamping is to the field, not to the value`() {
        var signed = 0
        var unsigned = 0
        for (f in PgnssFixtures.rows("scalars.txt")) {
            val v = PgnssFixtures.d(f[1])
            when (f[0]) {
                "S" -> {
                    assertEquals("sgn($v, ${f[2]})", f[3].toLong(), Records.sgn(v, f[2].toInt()))
                    signed++
                }
                "U" -> {
                    assertEquals("uns($v, ${f[2]})", f[3].toLong(), Records.uns(v, f[2].toInt()))
                    unsigned++
                }
            }
        }
        assertEquals(15, signed)
        assertEquals(9, unsigned)
        // Math.round would give 3 and -2 here; Python's round, and Math.rint, give 2 and -2.
        assertEquals(2L, Records.sgn(2.5, 32))
        assertEquals(4L, Records.sgn(3.5, 32))
        assertEquals(-2L, Records.sgn(-2.5, 32))
    }

    @Test
    fun `every encoded record is byte-identical to the reference's`() {
        var gps = 0
        var gal = 0
        var bds = 0
        var glo = 0
        for (f in PgnssFixtures.rows("records.txt")) {
            val want = PgnssFixtures.unhex(f.last())
            val got: ByteArray = when (f[0]) {
                "GLO" -> {
                    glo++
                    Records.encodeGlonass(
                        f[1].toInt(), f[2].toLong(),
                        DoubleArray(3) { PgnssFixtures.d(f[3 + it]) },
                        DoubleArray(3) { PgnssFixtures.d(f[6 + it]) },
                        DoubleArray(3) { PgnssFixtures.d(f[9 + it]) },
                        PgnssFixtures.d(f[12]),
                    )
                }
                else -> {
                    val idx = f[1].toInt()
                    val week = f[2].toLong()
                    val af0 = PgnssFixtures.d(f[3])
                    val af1 = PgnssFixtures.d(f[4])
                    val toe = f[5].toLong()
                    val toc = f[6].toLong()
                    val extra = f[7].toInt()
                    val el = PgnssFixtures.elements(f, 8, toe.toDouble())
                    when (f[0]) {
                        "GPS" -> { gps++; Records.encodeGps(idx, week, el, af0, af1, toe, toc, extra) }
                        "GAL" -> { gal++; Records.encodeGalileo(idx, el, af0, af1, toe, toc, extra) }
                        else -> {
                            bds++
                            val tail = byteArrayOf(
                                (extra and 0xFF).toByte(), 0,
                                ((extra * 7) and 0xFF).toByte(), ((extra * 3) and 0xFF).toByte(),
                            )
                            Records.encodeBds(idx, el, af0, af1, toe, toc, tail)
                        }
                    }
                }
            }
            assertEquals(
                "${f[0]} idx ${f[1]}\n  want ${PgnssFixtures.hex(want)}\n  got  ${PgnssFixtures.hex(got)}",
                PgnssFixtures.hex(want), PgnssFixtures.hex(got),
            )
        }
        assertEquals(10, gps)
        assertEquals(10, gal)
        assertEquals(10, bds)
        assertEquals(9, glo)
    }

    @Test
    fun `the constant bytes nothing else would check are written`() {
        val el = Orbit.Elements(DoubleArray(15))
        el.sqrtA = 5153.0
        el.e = 0.01
        val gps = Records.encodeGps(0, 2433, el, 0.0, 0.0, 604784, 604784, 0)
        assertEquals("GPS byte 77 is a constant 0xFF", 0xFF, gps[77].toInt() and 0xFF)
        val glo = Records.encodeGlonass(0, 44, DoubleArray(3), DoubleArray(3), DoubleArray(3), 0.0)
        assertEquals("GLONASS byte 12 is the satellite-type flag, always 1", 1, glo[12].toInt())
        assertEquals(0, glo[13].toInt())
    }

    @Test
    fun `tau is negated and the acceleration field saturates rather than wrapping`() {
        val tau = 1.0e-4
        val rec = Records.encodeGlonass(
            0, 44, DoubleArray(3), DoubleArray(3), doubleArrayOf(1.0, -1.0, 0.0), tau,
        )
        val stored = (rec[4].toInt() and 0xFF) or ((rec[5].toInt() and 0xFF) shl 8) or
            ((rec[6].toInt() and 0xFF) shl 16) or (rec[7].toInt() shl 24)
        assertEquals(Records.sgn(-tau * 1073741824.0, 32).toInt(), stored)
        assertTrue("tau is stored negated", stored < 0)
        assertEquals("saturates high", 127, rec[24].toInt())
        assertEquals("saturates low", -128, rec[36].toInt())
    }

    @Test
    fun `BeiDou refuses a toe that is not on its eight-second grid`() {
        val el = Orbit.Elements(DoubleArray(15))
        el.sqrtA = 5282.0
        el.e = 0.001
        val tail = ByteArray(4)
        // A block stamp that forgot the +14 s offset lands here, and truncating instead of refusing
        // cost 7.5 km of pure along-track error, invisibly.
        try {
            Records.encodeBds(0, el, 0.0, 0.0, 604786, 604786, tail)
            throw AssertionError("a toe congruent to 2 mod 8 must be refused, not truncated")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("multiples of 8"))
        }
        // With the offset applied it is exact.
        Records.encodeBds(0, el, 0.0, 0.0, 604784, 604784, tail)
    }

    @Test
    fun `the GLONASS grid is the UTC hour and Moscow time-of-day`() {
        // Huawei's own block: stamp 1471683600 is 08:59:42 UTC, and its first state is at 08:00:00
        // UTC, whose tb is 44 = 11:00 Moscow.
        val hour = Records.glonassHour(1471683600L)
        assertEquals(1471680018L, hour)
        assertEquals(44L, Records.glonassTb(hour))
        assertEquals(45L, Records.glonassTb(hour + 900))
        assertEquals(51L, Records.glonassTb(hour + 7 * 900))
    }

    @Test
    fun `the whole GLONASS file is byte-identical to the reference's`() {
        val want = PgnssFixtures.bytes("glonass.bin")
        val stamps = longArrayOf(1472040000L - 3600, 1472047200L - 3600, 1472054400L - 3600)
        val got = Records.buildGlonassFile(PgnssFixtures.sp3, stamps)
        assertEquals("length", want.size, got.size)
        assertEquals("first difference at byte ${firstDifference(want, got)}", -1,
            firstDifference(want, got))
        assertArrayEquals(want, got)
    }

    @Test
    fun `the assembled GPS and Galileo files are byte-identical to the reference's`() {
        val stamps = longArrayOf(1472040000L, 1472047200L, 1472054400L)
        val rows = PgnssFixtures.rows("fits.txt")
        for ((system, name, capacity, reclen) in listOf(
            Quad("GPS", "gps.bin", Records.GPS_CAPACITY, Records.GPS_RECLEN),
            Quad("GALILEO", "galileo.bin", Records.GALILEO_CAPACITY, Records.GALILEO_RECLEN),
        )) {
            val blocks = stamps.map { ts ->
                rows.filter { it[1] == system && it[3].toLong() == ts }
                    .sortedBy { it[2] }
                    .map { f ->
                        val idx = f[2].substring(1).toInt() - 1
                        val week = f[4].toLong()
                        val tow = f[5].toLong()
                        val delay = f[6].toInt()
                        val el = PgnssFixtures.elements(f, 12, tow.toDouble())
                        val af0 = PgnssFixtures.d(f[10])
                        val af1 = PgnssFixtures.d(f[11])
                        if (system == "GPS") {
                            Records.encodeGps(idx, week, el, af0, af1, tow, tow, delay)
                        } else {
                            Records.encodeGalileo(idx, el, af0, af1, tow, tow, delay)
                        }
                    }
            }
            assertTrue("$system blocks", blocks.all { it.size == 2 })
            val got = Records.assemble(stamps, blocks, capacity, reclen)
            val want = PgnssFixtures.bytes(name)
            assertEquals("$name first difference at ${firstDifference(want, got)}", -1,
                firstDifference(want, got))
            assertArrayEquals(want, got)
        }
    }

    @Test
    fun `the assembled BeiDou file is byte-identical, stamps included`() {
        val stamps = longArrayOf(
            1472040000L + Records.BDS_STAMP_OFFSET,
            1472047200L + Records.BDS_STAMP_OFFSET,
            1472054400L + Records.BDS_STAMP_OFFSET,
        )
        val rows = PgnssFixtures.rows("bdsblocks.txt")
        val blocks = (0 until 3).map { bi ->
            rows.filter { it[1].toInt() == bi }.map { f ->
                val toe = f[5].toLong()
                Records.encodeBds(
                    f[2].toInt(), PgnssFixtures.elements(f, 8, toe.toDouble()),
                    PgnssFixtures.d(f[3]), PgnssFixtures.d(f[4]), toe, f[6].toLong(),
                    PgnssFixtures.unhex(f[7]),
                )
            }
        }
        val got = Records.assemble(stamps, blocks, Records.BDS_CAPACITY, Records.BDS_RECLEN)
        val want = PgnssFixtures.bytes("bds.bin")
        assertEquals("first difference at ${firstDifference(want, got)}", -1,
            firstDifference(want, got))
        assertArrayEquals(want, got)
        // The stamp offset is what puts toe on the 8 s grid. A block stamped at a plain multiple
        // of 7200 gives a BDT seconds-of-week congruent to 2 mod 8, which the field cannot hold, and
        // truncating it instead of refusing cost 7.5 km of along-track error on every satellite.
        assertEquals(2L, Math.floorMod(1472040000L - Orbit.BDT_OFFSET, 8L))
        assertEquals(0L, Math.floorMod(stamps[0] - Orbit.BDT_OFFSET, 8L))
    }

    private data class Quad(val a: String, val b: String, val c: Int, val d: Int)

    private fun firstDifference(want: ByteArray, got: ByteArray): Int {
        if (want.size != got.size) return kotlin.math.min(want.size, got.size)
        for (i in want.indices) if (want[i] != got[i]) return i
        return -1
    }
}
