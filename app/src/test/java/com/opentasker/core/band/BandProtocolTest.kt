package com.opentasker.core.band

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame encoding against the three command frames captured from 白い熊's band, plus the BCD and
 * checksum rules they depend on.
 */
class BandProtocolTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `GET TIME frame matches the device capture`() {
        val frame = BandProtocol.encode(BandCommand.info(BandInfoQuery.CLOCK))
        assertArrayEquals(hex("41 00 00 00 00 00 00 00 00 00 00 00 00 00 00 41"), frame)
    }

    @Test
    fun `heart rate start frame matches the device capture`() {
        val frame = BandProtocol.encode(
            BandCommand.start(BandStream.HEART_RATE, BandLocalTime(2026, 7, 28)),
        )
        assertArrayEquals(hex("55 00 00 00 26 07 28 00 00 00 00 00 00 00 00 AA"), frame)
    }

    @Test
    fun `continue frame carries a ZERO date, not a repeat of the start date`() {
        val frame = BandProtocol.encode(BandCommand.cont(BandStream.HEART_RATE))
        assertArrayEquals(hex("55 02 00 00 00 00 00 00 00 00 00 00 00 00 00 57"), frame)
        for (i in 4..9) assertEquals("byte $i must be zero", 0, frame[i].toInt())
    }

    @Test
    fun `checksum is the low byte of bytes 0 through 14`() {
        val frame = BandProtocol.encode(BandCommand.start(BandStream.HRV, BandLocalTime(2026, 8, 2)))
        assertEquals(BandProtocol.checksum(frame), frame[15])
    }

    @Test
    fun `BCD reads decimal digits as hex nibbles`() {
        assertEquals(0x26.toByte(), BandProtocol.toBcd(26))
        assertEquals(0x08.toByte(), BandProtocol.toBcd(8))
        assertEquals(26, BandProtocol.fromBcd(0x26.toByte()))
        assertNull("0x1A is not valid BCD", BandProtocol.fromBcd(0x1A.toByte()))
    }

    @Test
    fun `readBcdDateTime rejects a slice that is not a real timestamp`() {
        assertEquals(
            20260802152034L,
            BandProtocol.readBcdDateTime(hex("55 00 00 26 08 02 15 20 34 49"), 3),
        )
        // month 0x13 = 13
        assertNull(BandProtocol.readBcdDateTime(hex("55 00 00 26 13 02 15 20 34 49"), 3))
        assertNull(BandProtocol.readBcdDateTime(ByteArray(10), 3))
    }

    @Test
    fun `terminator is a trailing 0xFF`() {
        assertTrue(BandProtocol.isTerminator(hex("53 ff")))
        assertFalse(BandProtocol.isTerminator(hex("55 00 00 26 08 02 15 20 34 49")))
    }

    /**
     * The info replies, as captured off 白い熊's band on 2026-08-02 at 15:23 local.
     *
     * These were the last golden vectors that lived only in the sync hand-off, which has been
     * deleted. Ground truth belongs in a test, not in prose that self-destructs.
     */
    @Test
    fun `info replies decode as captured from the device`() {
        assertEquals("0.0.2.5", BandProtocol.parseFirmware(hex("2700000205")))
        assertEquals(76, BandProtocol.parseBattery(hex("134c")))

        // 0x41 band clock: 2026-08-02 15:23:05, BCD, same reader the record streams use.
        assertEquals(20260802152305L, BandProtocol.readBcdDateTime(hex("41260802152305"), 1))

        // 0x4b step goal: LE16 0x2710 = 10000.
        assertEquals(10000, BandProtocol.le16(hex("4b1027"), 1))

        // 0x22 MAC: D5:A7:06:DC:A1:3A.
        val mac = hex("22d5a706dca13a")
        assertEquals(
            "D5:A7:06:DC:A1:3A",
            (1..6).joinToString(":") { "%02X".format(mac[it].toInt() and 0xFF) },
        )

        // 0x57 alarms: a bare terminator means "none", and must not read as a record.
        assertTrue(BandProtocol.isTerminator(hex("57ff")))
    }

    @Test
    fun `a short or empty info reply yields null rather than a wrong number`() {
        assertNull(BandProtocol.parseFirmware(hex("270000")))
        assertNull(BandProtocol.parseFirmware(ByteArray(0)))
        assertNull(BandProtocol.parseBattery(hex("13")))
        assertNull(BandProtocol.parseBattery(ByteArray(0)))
    }

    @Test
    fun `the delete mode is not expressible`() {
        assertEquals(listOf(0, 2), BandReadMode.entries.map { it.raw })
        assertEquals(setOf(0, 2), BandProtocol.ALLOWED_MODE_BYTES)
    }
}
