package com.opentasker.core.huawei

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing, VarInt/TLV and CRC, checked against vectors produced by the Python client that actually
 * drives 白い熊's Band 11 Pro — which is itself byte-verified against MIT `zyv/huawei-lpv2`.
 *
 * These are not self-consistency checks: every expected value below came off the working
 * implementation, so a green run means the Kotlin agrees with something that has moved real bytes.
 */
class HuaweiProtocolTest {

    private fun hex(s: String): ByteArray = HuaweiCrypto.hex(s)

    @Test
    fun `LinkParams frame matches the bytes the band answers`() {
        val payload = HuaweiProtocol.tlv(1) + HuaweiProtocol.tlv(2) +
            HuaweiProtocol.tlv(3) + HuaweiProtocol.tlv(4)
        val frame = HuaweiProtocol.frame(0x01, 0x01, payload)
        assertArrayEquals(hex("5A000B0001010100020003000400F13B"), frame)
    }

    @Test
    fun `length field counts the slice byte, not just the payload`() {
        // The trap: encoding payload.size instead of payload.size + 1 yields a frame the band
        // silently ignores — no error, no reply. Here the body is 10 bytes, so the field is 11.
        val payload = HuaweiProtocol.tlv(1) + HuaweiProtocol.tlv(2) +
            HuaweiProtocol.tlv(3) + HuaweiProtocol.tlv(4)
        val frame = HuaweiProtocol.frame(0x01, 0x01, payload)
        assertEquals("declared length", 0x0B, HuaweiProtocol.bytesToInt(frame, 1, 2))
        assertEquals("body is one less", 0x0A, payload.size + 2)
    }

    @Test
    fun `CRC16 XMODEM matches the reference check value`() {
        assertEquals(0x31C3, HuaweiProtocol.crc16("123456789".toByteArray()))
    }

    @Test
    fun `VarInt encodes the boundary cases`() {
        assertArrayEquals(hex("00"), HuaweiProtocol.varIntEncode(0))
        assertArrayEquals(hex("7F"), HuaweiProtocol.varIntEncode(127))
        assertArrayEquals(hex("8100"), HuaweiProtocol.varIntEncode(128))
        assertArrayEquals(hex("8624"), HuaweiProtocol.varIntEncode(804))
    }

    @Test
    fun `VarInt round-trips including multi-byte lengths`() {
        for (v in listOf(0, 1, 127, 128, 300, 804, 1022, 16383, 16384)) {
            val enc = HuaweiProtocol.varIntEncode(v)
            val dec = HuaweiProtocol.varIntDecode(enc)
            assertEquals("value $v", v, dec.value)
            assertEquals("size for $v", enc.size, dec.size)
        }
    }

    @Test
    fun `frame round-trips through unframe with a good CRC`() {
        val payload = HuaweiProtocol.tlv(5, hex("DEADBEEF")) + HuaweiProtocol.tlv(9, "band")
        val raw = HuaweiProtocol.frame(0x07, 0x0A, payload)
        val f = HuaweiProtocol.unframe(raw)
        assertEquals(0x07, f.serviceId)
        assertEquals(0x0A, f.commandId)
        assertTrue(f.crcOk)
        assertArrayEquals(hex("DEADBEEF"), f.tag(5))
        assertEquals("band", String(f.tag(9)!!))
    }

    @Test
    fun `a corrupted frame is reported rather than silently accepted`() {
        val raw = HuaweiProtocol.frame(0x01, 0x01, HuaweiProtocol.tlv(1))
        raw[raw.size - 1] = (raw[raw.size - 1] + 1).toByte()
        assertFalse(HuaweiProtocol.unframe(raw).crcOk)
    }

    @Test
    fun `TLVs with repeating tags are all preserved`() {
        // A map would lose these; the band really does send the same tag twice.
        val payload = HuaweiProtocol.tlv(0x82, "a") + HuaweiProtocol.tlv(0x82, "b")
        val tlvs = HuaweiProtocol.parseTlvs(payload)
        assertEquals(2, tlvs.size)
        assertEquals("a", String(tlvs[0].value))
        assertEquals("b", String(tlvs[1].value))
    }

    @Test
    fun `a result tag marks a frame as an acknowledgement and is never answered`() {
        val ack = HuaweiProtocol.unframe(
            HuaweiProtocol.frame(
                0x01, 0x10,
                HuaweiProtocol.tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.RESULT_SUCCESS, 4),
            ),
        )
        assertTrue(ack.isAck)
        assertEquals(HuaweiProtocol.RESULT_SUCCESS, ack.result)

        val request = HuaweiProtocol.unframe(
            HuaweiProtocol.frame(0x01, 0x10, HuaweiProtocol.tlv(2) + HuaweiProtocol.tlv(4)),
        )
        assertFalse(request.isAck)
        assertNull(request.result)
    }

    @Test
    fun `reassembler splits a stream carrying several frames in one read`() {
        val a = HuaweiProtocol.frame(0x01, 0x01, HuaweiProtocol.tlv(1))
        val b = HuaweiProtocol.frame(0x07, 0x0B, HuaweiProtocol.tlv(2, "xy"))
        val frames = HuaweiProtocol.Reassembler().feed(a + b)
        assertEquals(2, frames.size)
        assertEquals(0x01, frames[0].commandId)
        assertEquals(0x0B, frames[1].commandId)
    }

    @Test
    fun `reassembler holds a frame split across reads`() {
        val whole = HuaweiProtocol.frame(0x01, 0x01, HuaweiProtocol.tlv(1, "abcdef"))
        val r = HuaweiProtocol.Reassembler()
        assertTrue(r.feed(whole.copyOfRange(0, 4)).isEmpty())
        assertTrue("bytes should be held back", r.pending > 0)
        val out = r.feed(whole.copyOfRange(4, whole.size))
        assertEquals(1, out.size)
        assertEquals(0, r.pending)
    }

    @Test
    fun `reassembler resynchronises after leading garbage instead of wedging`() {
        val good = HuaweiProtocol.frame(0x01, 0x01, HuaweiProtocol.tlv(1))
        val frames = HuaweiProtocol.Reassembler().feed(hex("00112233") + good)
        assertEquals(1, frames.size)
        assertEquals(0x01, frames[0].serviceId)
    }
}
