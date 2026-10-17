package com.opentasker.core.huawei

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability decoders, against the Band 11 Pro's own replies to Huawei Health — decrypted from
 * the pairing capture (`.scratch/hw/health_caps.txt`). These are what decide, per device, which sync
 * steps are sent at all, so a decoder that mispairs ids and flags would quietly skip real data.
 */
class HuaweiCapabilitiesTest {

    private fun hex(s: String) = HuaweiCrypto.hex(s)

    private val servicesReply =
        "01000100000101010101010000010000000001000101010101010000000001000000010001000100010101000101"

    /** The inner value of tag 0x81 in the Band's SupportedCommands reply. */
    private val commandsReplyInner =
        "020101041E0101010000010100010001000000010100000001010100000101010001000201020406010100010001" +
            "020104040101020107041B0101000100010101000101000101000001010101010101010101010201080403010001" +
            "020109040601000101010102010A040301010102010B0402010102010C04010102010F0409010101010101010101" +
            "0201160403010100020117040A0101010101010001010102011804060101010101010201190402010002011A0405" +
            "010100010102011B040400000001020120040600000000010002012504030100000201270402010102012B040100" +
            "02012E040301000102013004010102013204010102013404010102013504020101"

    @Test
    fun `the request Health sends is the request we send`() {
        assertEquals(
            "02030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F202223242526272A2B2D2E3032333435",
            HuaweiCrypto.upperHex(HuaweiCommands.SUPPORTED_SERVICES),
        )
    }

    @Test
    fun `services pair with their flag by position`() {
        val reply = listOf(HuaweiProtocol.Tlv(2, hex(servicesReply)))
        val s = HuaweiCapabilities.parseServices(HuaweiCommands.SUPPORTED_SERVICES, reply)
        assertEquals(46, s.size)
        assertEquals(true, s[0x02])
        assertEquals(false, s[0x03])
        assertEquals(true, s[0x07])        // fitness
        assertEquals(true, s[0x35])
        assertNull(s[0x01])                // never asked: unknown, not "no"
        val caps = HuaweiCapabilities(services = s)
        assertNull(caps.supportsService(0xF0))
        assertTrue(caps.supportedServices().containsAll(listOf(0x02, 0x07, 0x35)))
    }

    @Test
    fun `commands pair with their flag by position, per service`() {
        val reply = listOf(HuaweiProtocol.Tlv(0x81, hex(commandsReplyInner)))
        val c = HuaweiCapabilities.parseCommands(HuaweiCommands.SUPPORTED_COMMANDS, reply)
        // Service 0x01 was asked 30 commands starting 04 07 08 09 0A; flags 01 01 01 00 00.
        assertEquals(30, c[0x01]?.size)
        assertEquals(true, c[0x01]?.get(0x04))
        assertEquals(true, c[0x01]?.get(0x08))
        assertEquals(false, c[0x01]?.get(0x09))
        // Service 0x1B: asked 01 0F 19 1A, answered 00 00 00 01.
        assertEquals(mapOf(0x01 to false, 0x0F to false, 0x19 to false, 0x1A to true), c[0x1B])
        val caps = HuaweiCapabilities(commands = c)
        assertEquals(true, caps.supportsCommand(0x1B, 0x1A))
        assertNull(caps.supportsCommand(0x1B, 0x02))
    }

    @Test
    fun `a single-service command request is two TLVs`() {
        assertArrayEquals(
            hex("0201070302" + "0A0B"),
            HuaweiCapabilities.commandsRequestInner(mapOf(0x07 to listOf(0x0A, 0x0B))),
        )
    }

    @Test
    fun `expand bits count from the least significant bit of byte 0`() {
        val caps = HuaweiCapabilities(expand = hex("FF7F01"))
        assertEquals(true, caps.expandBit(0))
        assertEquals(true, caps.expandBit(14))
        assertEquals(false, caps.expandBit(15))
        assertEquals(true, caps.expandBit(16))
        assertEquals(false, caps.expandBit(17))
        assertNull(caps.expandBit(24))
        assertNull(HuaweiCapabilities().expandBit(0))
    }

    @Test
    fun `merge keeps both halves and the newer one wins`() {
        val a = HuaweiCapabilities(services = mapOf(1 to true, 2 to false), commands = mapOf(1 to mapOf(4 to true)), recordedAt = 1)
        val b = HuaweiCapabilities(services = mapOf(2 to true), commands = mapOf(1 to mapOf(7 to false)), recordedAt = 2)
        val m = a.merge(b)
        assertEquals(mapOf(1 to true, 2 to true), m.services)
        assertEquals(mapOf(4 to true, 7 to false), m.commands[1])
        assertEquals(2L, m.recordedAt)
        assertFalse(m.supportsCommand(1, 7)!!)
    }

    @Test
    fun `an empty reply decodes to nothing rather than throwing`() {
        assertTrue(HuaweiCapabilities.parseServices(HuaweiCommands.SUPPORTED_SERVICES, emptyList()).isEmpty())
        assertTrue(HuaweiCapabilities.parseCommands(HuaweiCommands.SUPPORTED_COMMANDS, emptyList()).isEmpty())
        assertNull(HuaweiCapabilities.parseExpand(listOf(HuaweiProtocol.Tlv(1, ByteArray(0)))))
    }
}
