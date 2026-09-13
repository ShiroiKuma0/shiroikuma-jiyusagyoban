package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-beat stream, built from synthetic files of the documented shape.
 *
 * There is no captured fixture in the repository to replay, so every test here constructs the layout
 * the decoding note states and asserts the parser recovers what was put in. That is weaker than a
 * real capture for proving the LAYOUT and stronger for proving the PARSER — and the layout itself is
 * pinned by [headerIsFoundByItsLittleEndianEcho], which is the property the whole location strategy
 * rests on.
 */
class HuaweiBeatsTest {

    private val START = 1_787_000_000L

    /** One record: a 44-byte header with the LE echo at +0x24, then (interval, quality) pairs. */
    private fun record(start: Long, end: Long, beats: List<Pair<Int, Int>>): ByteArray {
        val out = ByteArray(HuaweiBeats.HEADER + beats.size * 4)
        fun be32(at: Int, v: Long) {
            for (n in 0 until 4) out[at + n] = ((v shr (8 * (3 - n))) and 0xFF).toByte()
        }
        fun le32(at: Int, v: Long) {
            for (n in 0 until 4) out[at + n] = ((v shr (8 * n)) and 0xFF).toByte()
        }
        be32(0, start)
        be32(4, end)
        le32(0x24, start)
        beats.forEachIndexed { i, (interval, quality) ->
            val at = HuaweiBeats.HEADER + i * 4
            out[at] = (interval and 0xFF).toByte()
            out[at + 1] = ((interval shr 8) and 0xFF).toByte()
            out[at + 2] = (quality and 0xFF).toByte()
            out[at + 3] = ((quality shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Sixty beats of 1000 ms: a record whose intervals sum to exactly its declared span. */
    private fun steadyMinute(start: Long) =
        record(start, start + 60, List(60) { 1000 to 100 })

    @Test
    fun `one record round-trips`() {
        val records = HuaweiBeats.parse(steadyMinute(START))
        assertEquals(1, records.size)
        val r = records.single()
        assertEquals(START, r.startSeconds)
        assertEquals(START + 60, r.endSeconds)
        assertEquals(60, r.beats.size)
        assertEquals(1000, r.beats.first().intervalMs)
        assertEquals(100, r.beats.first().quality)
        assertTrue("intervals sum to the declared span", r.exact)
    }

    @Test
    fun `consecutive records are all found`() {
        val file = steadyMinute(START) + steadyMinute(START + 60) + steadyMinute(START + 120)
        val records = HuaweiBeats.parse(file)
        assertEquals(3, records.size)
        assertEquals(listOf(START, START + 60, START + 120), records.map { it.startSeconds })
        assertTrue(records.all { it.beats.size == 60 })
    }

    /**
     * A file header sits in front of the first record and must not become a record.
     *
     * `00`, size as BE uint32, stream id as BE uint32 — nine bytes that carry no plausible epoch
     * pair, so the scan should simply walk past them.
     */
    @Test
    fun `the file header is not mistaken for a record`() {
        val head = byteArrayOf(
            0, 0, 0, 0x10, 0x00, 0x00, 0x0A, 0xAE.toByte(), 0x75,
        )
        val records = HuaweiBeats.parse(head + steadyMinute(START))
        assertEquals(1, records.size)
        assertEquals(START, records.single().startSeconds)
    }

    /**
     * The echo is what makes header location safe.
     *
     * A run of beat bytes can happen to look like two plausible epochs; it cannot also carry a
     * little-endian copy of the first one 36 bytes along. With the echo corrupted the block must be
     * rejected outright rather than half-read.
     */
    @Test
    fun headerIsFoundByItsLittleEndianEcho() {
        val bytes = steadyMinute(START)
        bytes[0x24] = (bytes[0x24] + 1).toByte()
        assertTrue("a header without its echo is not a header", HuaweiBeats.parse(bytes).isEmpty())
    }

    @Test
    fun `an implausible epoch pair is rejected`() {
        // End before start.
        assertTrue(HuaweiBeats.parse(record(START, START - 60, List(10) { 1000 to 100 })).isEmpty())
        // A span longer than an hour.
        assertTrue(HuaweiBeats.parse(record(START, START + 7200, List(10) { 1000 to 100 })).isEmpty())
    }

    /**
     * A record whose beats do not add up to its span is kept and flagged, never dropped.
     *
     * The beats may be perfectly sound — this is the file disagreeing with itself about time — and
     * dropping the record would throw away every HRV statistic to protect one frequency estimate.
     */
    @Test
    fun `a record that fails its own arithmetic is flagged not discarded`() {
        val r = HuaweiBeats.parse(record(START, START + 600, List(60) { 1000 to 100 })).single()
        assertEquals(60, r.beats.size)
        assertTrue("beats survive", r.beats.all { it.intervalMs == 1000 })
        assertTrue("but the record is not exact", !r.exact)
    }

    /**
     * A page stamp in a quality byte costs the quality and keeps the interval.
     *
     * This is the case that actually occurs — 88 of 100 stamps in the measured capture — because a
     * 44-byte header and a 4-byte beat put every stamp on the same byte of the same field.
     */
    @Test
    fun `a stamp in the quality field keeps the interval`() {
        val one = steadyMinute(START)
        // Pad so that a page boundary lands on a beat's quality byte: beats begin at 44, so offset
        // 976 sits at 44 + 4k + 2 when the record starts at 976 - 46 = 930.
        val pad = ByteArray(930)
        val file = pad + one
        val stampAt = HuaweiPagedFile.PAGE
        file[stampAt] = HuaweiPagedFile.expectedAt(stampAt).toByte()

        val r = HuaweiBeats.parse(file).single()
        assertEquals("no beat is lost", 60, r.beats.size)
        val damaged = r.beats.filter { it.quality == null }
        assertEquals("exactly one quality is unreadable", 1, damaged.size)
        assertTrue("its interval survives intact", damaged.single().intervalMs == 1000)
    }

    /** The blob encoding round-trips, including the "quality unknown" marker. */
    @Test
    fun `encode and decode round-trip`() {
        val beats = listOf(
            HuaweiBeats.Beat(820, 100),
            HuaweiBeats.Beat(1040, 50),
            HuaweiBeats.Beat(900, 0),
            HuaweiBeats.Beat(960, null),
        )
        val back = HuaweiBeats.decode(HuaweiBeats.encode(beats))
        assertEquals(beats, back)
        assertNull("null quality survives as null, not as zero", back.last().quality)
        assertEquals(0, back[2].quality)
        assertEquals("four bytes a beat", 16, HuaweiBeats.encode(beats).size)
    }

    @Test
    fun `an empty file yields nothing rather than throwing`() {
        assertEquals(emptyList<HuaweiBeats.Record>(), HuaweiBeats.parse(ByteArray(0)))
        assertEquals(emptyList<HuaweiBeats.Record>(), HuaweiBeats.parse(ByteArray(37)))
    }

    /** The stream id is the one the container is asked for, and it is the documented number. */
    @Test
    fun `stream id is 700021`() {
        assertEquals(700_021, HuaweiBeats.STREAM_ID)
        assertEquals(HuaweiBeats.STREAM_ID, HuaweiFileClient.BEAT_STREAM_ID)
        assertNotNull(HuaweiFileClient.SEQUENCE_DATA)
    }
}
