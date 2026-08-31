package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band's page stamp, and the nap, against nights this test builds itself.
 *
 * Synthetic rather than captured on purpose: the whole question is where in a record the stamp
 * lands, and a real file puts it wherever it happens to fall. Every stamp in every capture so far
 * has landed in a night's configuration block, which is the one place it costs nothing — so a
 * captured fixture would prove only that we have been lucky.
 *
 * The nights below are the ordinary shape: a leading awake run, sleep, a trailing awake block, and
 * the identity the parser leans on — anchored on that leading run, the last sleeping segment ends
 * exactly on the declared wake time.
 */
class HuaweiSleepPageStampTest {

    private val start = 1_787_400_000L
    private val segments = listOf(600 to 4, 1800 to 1, 1200 to 3, 900 to 1, 300 to 4)

    /** lead 600, then 1800 + 1200 + 900 of sleep: the wake time is 3900 s after the bed time. */
    private val end = start + 3900

    private fun be32(v: Long) = ByteArray(4) { ((v shr (8 * (3 - it))) and 0xFF).toByte() }
    private fun le32(v: Int) = ByteArray(4) { ((v shr (8 * it)) and 0xFF).toByte() }

    /**
     * One night's block, preceded by [pad] zero bytes.
     *
     * The padding is how a field is moved onto a page boundary: nothing else in the file may be
     * where the stamp is wanted, and zeros are never mistaken for a block base.
     */
    private fun night(pad: Int, segs: List<Pair<Int, Int>> = segments, s: Long = start, e: Long = end): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(ByteArray(pad))
        out.write(be32(s))
        out.write(be32(e))
        out.write(ByteArray(0x18))          // flags and padding, contents unread
        out.write(0x81)                     // the container marker the base scan demands
        out.write(8)                        // VarInt length of the configuration block
        out.write(ByteArray(8))             // the configuration block itself, skipped wholesale
        for ((duration, stage) in segs) {
            out.write(le32(duration))
            out.write(le32(stage))
        }
        // Long enough that a page boundary exists at all.
        out.write(ByteArray(maxOf(0, 1100 - out.size())))
        return out.toByteArray()
    }

    /** Stamp every page boundary the way the band does: the byte at k×976 is k. */
    private fun stamped(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        var at = 0
        var index = 0
        while (at < out.size) {
            out[at] = (index and 0xFF).toByte()
            at += HuaweiPagedFile.PAGE
            index++
        }
        return out
    }

    /** Where the block's field at [rel] must sit so that the first page boundary lands on it. */
    private fun padToPage(rel: Int) = HuaweiPagedFile.PAGE - rel

    /** Byte offsets inside the block: 0x20 marker, 0x21 length, 8 config bytes, then the pairs. */
    private fun segmentAt(n: Int) = 0x22 + 8 + n * 8

    @Test
    fun `a nap is sleep, not an unknown`() {
        val file = night(pad = 0, segs = listOf(5580 to 5), s = start, e = start + 5580)
        val night = checkNotNull(HuaweiSleep.parse(file))

        assertEquals(HuaweiSleep.Stage.NAP, night.segments.single().stage)
        // Before the code was named this whole block counted as nothing at all.
        assertEquals(5580, night.asleepSeconds)
        // And the file's own check agrees it is sleep: the segment ends on the declared wake time.
        assertTrue("a nap must anchor like sleep", night.alignsWithHeader)
        assertEquals(mapOf(HuaweiSleep.Stage.NAP to 5580), night.totals())
    }

    @Test
    fun `a stamp in a duration's high bytes costs nothing`() {
        // Byte 3 of a little-endian duration: real segments are minutes, so it is always zero and
        // the value underneath survives whole.
        val rel = segmentAt(2) + 3
        val file = stamped(night(pad = padToPage(rel)))
        val night = checkNotNull(HuaweiSleep.parse(file))

        assertEquals(1200, night.segments[2].durationSeconds)
        assertTrue("the pair was touched", night.segments[2].mended)
        assertTrue("but nothing was lost", night.segments[2].exact)
        assertTrue(night.alignsWithHeader)
    }

    @Test
    fun `a stamp in a duration's low byte is solved from the night's own arithmetic`() {
        val rel = segmentAt(2)
        val file = stamped(night(pad = padToPage(rel)))
        val night = checkNotNull(HuaweiSleep.parse(file))

        // Nothing about 1200 survives in the file; it comes back because the wake time, the leading
        // awake run and the other four durations leave exactly one value it could have been.
        assertEquals(1200, night.segments[2].durationSeconds)
        assertTrue(night.alignsWithHeader)
        assertEquals(1, night.mendedSegments)
    }

    @Test
    fun `a stamp in a stage's high bytes leaves the stage intact`() {
        val rel = segmentAt(2) + 4 + 2
        val file = stamped(night(pad = padToPage(rel)))
        val night = checkNotNull(HuaweiSleep.parse(file))

        assertEquals(HuaweiSleep.Stage.DEEP, night.segments[2].stage)
        assertEquals(1200, night.segments[2].durationSeconds)
        assertTrue(night.segments[2].exact)
    }

    @Test
    fun `a stamp in the header's bed time loses a byte, not the night`() {
        val file = stamped(night(pad = padToPage(3)))
        val night = HuaweiSleep.parse(file)

        // The strict base scan rejected a stamped epoch outright, which did not corrupt the night —
        // it made the whole night invisible, and an append-only file never offers it again.
        assertNotNull("a stamped epoch must not lose the night", night)
        assertEquals(start, night!!.startSeconds)
        assertTrue(night.headerExact)
        assertEquals(segments.size, night.segments.size)
    }

    @Test
    fun `two lost durations are admitted rather than guessed`() {
        // One equation cannot solve two unknowns. The night is still returned — losing it whole is
        // worse — but the segments say they are not measurements.
        val rel = segmentAt(1)
        val file = stamped(night(pad = padToPage(rel))).copyOf()
        val second = padToPage(rel) + segmentAt(3)
        file[second] = 0x7F                       // a second duration destroyed, by hand
        file[second + 1] = 0x7F
        val night = checkNotNull(HuaweiSleep.parse(file))
        assertFalse("the array cannot be exact with two holes", night.alignsWithHeader)
        assertTrue(night.segments.any { !it.exact })
    }

    @Test
    fun `a file with no stamps is read exactly as before`() {
        val night = checkNotNull(HuaweiSleep.parse(night(pad = 0)))
        assertEquals(0, night.mendedSegments)
        assertEquals(0, night.inexactSegments)
        assertTrue(night.headerExact)
        assertEquals(3900, night.asleepSeconds)
        assertTrue(night.alignsWithHeader)
    }
}
