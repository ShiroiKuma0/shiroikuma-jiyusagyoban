package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workout list and summary parsers, against replies this test builds.
 *
 * Like the track decoder, these encode the format as understood rather than as observed: the band
 * has never recorded a workout, and Huawei Health's every request for one in our capture came back
 * empty. So the fixtures are the hypothesis. What they pin down is that the parsers refuse cleanly
 * on anything unexpected instead of inventing a walk, which is the property that matters when the
 * first real reply turns out to be shaped differently.
 */
class HuaweiWorkoutTest {

    private fun tlv(tag: Int, value: ByteArray) = HuaweiProtocol.tlv(tag, value)
    private fun u16(v: Int) = HuaweiProtocol.intBytes(v, 2)
    private fun u32(v: Long) = HuaweiProtocol.intBytes(v.toInt(), 4)

    private fun listReply(vararg entries: ByteArray): List<HuaweiProtocol.Tlv> =
        HuaweiProtocol.parseTlvs(
            tlv(0x81, tlv(2, u16(entries.size)) + entries.fold(ByteArray(0)) { a, b -> a + b }),
        )

    private fun entry(number: Int, track: Boolean, samples: Int = 2, pace: Int = 1) =
        tlv(
            0x85,
            tlv(6, u16(number)) + tlv(7, u16(samples)) + tlv(8, u16(pace)) +
                tlv(0x0E, byteArrayOf(if (track) 1 else 0)),
        )

    @Test
    fun `a list of walks yields their numbers and whether a track exists`() {
        val parsed = HuaweiWorkout.parseList(listReply(entry(7, true), entry(8, false)))
        assertEquals(2, parsed.size)
        assertEquals(7, parsed[0].number)
        assertTrue(parsed[0].hasTrack)
        assertEquals(8, parsed[1].number)
        assertTrue(!parsed[1].hasTrack)
        assertEquals(2, parsed[0].sampleBlocks)
        assertEquals(1, parsed[0].paceBlocks)
    }

    @Test
    fun `an empty list is empty, not a phantom walk`() {
        // This is the reply the band actually gave Huawei Health, eight times out of eight.
        assertEquals(emptyList<HuaweiWorkout.Entry>(), HuaweiWorkout.parseList(listReply()))
        assertEquals(
            emptyList<HuaweiWorkout.Entry>(),
            HuaweiWorkout.parseList(HuaweiProtocol.parseTlvs(tlv(0x81, ByteArray(0)))),
        )
    }

    @Test
    fun `an entry without a number is dropped rather than numbered zero`() {
        val nameless = tlv(0x85, tlv(7, u16(1)) + tlv(0x0E, byteArrayOf(1)))
        val parsed = HuaweiWorkout.parseList(listReply(nameless, entry(9, true)))
        assertEquals(1, parsed.size)
        assertEquals(9, parsed[0].number)
    }

    @Test
    fun `a summary reads the fields that matter for a walk`() {
        val reply = HuaweiProtocol.parseTlvs(
            tlv(
                0x81,
                tlv(2, u16(7)) + tlv(4, u32(1_787_400_000L)) + tlv(5, u32(1_787_403_600L)) +
                    tlv(6, u32(240)) + tlv(7, u32(4_812)) + tlv(8, u32(6_130)) +
                    tlv(0x12, u32(3_600)) + tlv(0x14, byteArrayOf(2)),
            ),
        )
        val s = HuaweiWorkout.parseSummary(reply)!!
        assertEquals(7, s.number)
        assertEquals(1_787_400_000L, s.startSeconds)
        assertEquals(4_812, s.distanceMetres)
        assertEquals(6_130, s.steps)
        assertEquals(3_600L, s.durationSeconds)
        assertEquals("walk", s.kind)
        assertTrue("a walk is tracked outdoors", s.isOutdoor)
    }

    @Test
    fun `an unknown sport is named by its number, never flattened to other`() {
        val reply = HuaweiProtocol.parseTlvs(tlv(0x81, tlv(2, u16(1)) + tlv(0x14, byteArrayOf(27))))
        val s = HuaweiWorkout.parseSummary(reply)!!
        // A band that starts reporting 27 should make that visible in the summary line, not hide it
        // behind a friendly word — the same reason unknown record bits are kept as unknown_XX.
        assertEquals("type 27", s.kind)
        assertTrue(!s.isOutdoor)
    }

    @Test
    fun `indoor kinds are not treated as track-bearing`() {
        for (indoor in listOf(5, 6, 7, 13)) {
            val reply = HuaweiProtocol.parseTlvs(
                tlv(0x81, tlv(2, u16(1)) + tlv(0x14, byteArrayOf(indoor.toByte()))),
            )
            assertTrue("type $indoor must not be outdoor", !HuaweiWorkout.parseSummary(reply)!!.isOutdoor)
        }
    }

    @Test
    fun `a reply with no container is refused`() {
        assertNull(HuaweiWorkout.parseSummary(HuaweiProtocol.parseTlvs(tlv(2, u16(7)))))
        assertNull(HuaweiWorkout.parseSummary(emptyList()))
    }
}
