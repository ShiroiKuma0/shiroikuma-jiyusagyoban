package com.opentasker.core.huawei

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Request payloads, checked against the bytes a real Huawei Health pairing sent to 白い熊's band on
 * firmware 6.0.0.125 — recovered by decrypting a Bluetooth capture.
 *
 * Four of these commands were missing from an earlier build of the client and the band would not
 * leave its out-of-box wizard without them. Two more had subtly wrong payloads. Pinning them here
 * is what stops that recurring.
 */
class HuaweiCommandsTest {

    private fun hex(s: String) = HuaweiCrypto.hex(s)

    @Test
    fun `SecurityNegotiation carries phoneIdentify and a 16-char lowercase device id`() {
        val id = "58889d3e2c7d747c"
        val payload = HuaweiCommands.securityNegotiation(authMode = 4, deviceId = id, phoneModel = "skfs")
        val tlvs = HuaweiProtocol.parseTlvs(payload)

        assertEquals(4, tlvs.first { it.tag == 1 }.value[0].toInt())
        assertEquals(id, String(tlvs.first { it.tag == 5 }.value))
        // Tag 6 empty is what we shipped for hours; Health sends this literal string.
        assertEquals("phoneIdentify", String(tlvs.first { it.tag == 6 }.value))
        assertEquals(16, id.length)
        assertEquals("device id must be lowercase hex", id.lowercase(), id)
    }

    @Test
    fun `ReverseCapabilities is the seven-byte value, not Gadgetbridge's six`() {
        assertArrayEquals(hex("0107FDF773FA29BF3B"), HuaweiCommands.reverseCapabilities())
        val value = HuaweiProtocol.parseTlvs(HuaweiCommands.reverseCapabilities())
            .first { it.tag == 1 }.value
        assertEquals(7, value.size)
        assertArrayEquals(hex("FDF773FA29BF3B"), value)
    }

    @Test
    fun `AcceptAgreements carries exactly two blocks`() {
        val outer = HuaweiProtocol.parseTlvs(HuaweiCommands.acceptAgreements())
        val inner = HuaweiProtocol.parseTlvs(outer.first { it.tag == 0x81 }.value)
        val blocks = inner.filter { it.tag == 0x82 }
        assertEquals("Health sends two blocks; a hand-built three-block blob does not work", 2, blocks.size)
        val names = blocks.map { block ->
            String(HuaweiProtocol.parseTlvs(block.value).first { it.tag == 0x03 }.value)
        }
        assertEquals(listOf("user_license_agreement", "device_information_management"), names)
    }

    @Test
    fun `SetUpDeviceStatus carries the band's own name`() {
        val payload = HuaweiCommands.setUpDeviceStatus("HUAWEI Band 11 Pro-90F")
        assertArrayEquals(
            hex("01010102164855415745492042616E642031312050726F2D393046030100"),
            payload,
        )
    }

    @Test
    fun `SettingRelated asks for six empty tags`() {
        val tlvs = HuaweiProtocol.parseTlvs(HuaweiCommands.settingRelated())
        assertEquals(listOf(1, 2, 3, 4, 5, 6), tlvs.map { it.tag })
        assertTrue(tlvs.all { it.value.isEmpty() })
    }

    @Test
    fun `ProductInfo asks for the specific tag set, not a blind range`() {
        val tags = HuaweiProtocol.parseTlvs(HuaweiCommands.productInfo()).map { it.tag }
        assertEquals(HuaweiCommands.PRODUCT_INFO_TAGS.toList(), tags)
        assertFalse("tag 3 is not requested", tags.contains(3))
    }

    @Test
    fun `PhoneInfo reply fills each requested tag per the observed contract`() {
        val requested = listOf(0x02, 0x04, 0x08, 0x0F, 0x10, 0x11, 0x13, 0x14, 0x15, 0x16)
        val tlvs = HuaweiProtocol.parseTlvs(HuaweiCommands.phoneInfoReply(requested))

        assertFalse("0x0F is omitted entirely", tlvs.any { it.tag == 0x0F })
        assertTrue(tlvs.first { it.tag == 0x02 }.value.isEmpty())
        assertTrue(tlvs.first { it.tag == 0x15 }.value.isEmpty())
        assertEquals("14", String(tlvs.first { it.tag == 0x08 }.value))
        assertEquals(1_600_103_320, HuaweiProtocol.bytesToInt(tlvs.first { it.tag == 0x11 }.value))
        assertArrayEquals(byteArrayOf(0), tlvs.first { it.tag == 0x10 }.value)
    }

    @Test
    fun `authMode is 4 for the HiChain3 device support types`() {
        assertEquals(4, HuaweiCommands.authModeFor(4))
        assertEquals(4, HuaweiCommands.authModeFor(1))
        assertEquals(4, HuaweiCommands.authModeFor(3))
        assertEquals(0, HuaweiCommands.authModeFor(0))
        assertEquals(0, HuaweiCommands.authModeFor(2))
    }

    @Test
    fun `fitness count is count-then-index over an epoch range`() {
        val tlvs = HuaweiProtocol.parseTlvs(HuaweiCommands.fitnessCount(0, 1_787_346_631))
        assertTrue(tlvs.first { it.tag == 0x81 }.value.isEmpty())
        assertEquals(0, HuaweiProtocol.bytesToInt(tlvs.first { it.tag == 3 }.value))
        assertEquals(1_787_346_631, HuaweiProtocol.bytesToInt(tlvs.first { it.tag == 4 }.value))
    }

    @Test
    fun `SetTime encodes a negative zone offset as 128 plus hours`() {
        // The band's own encoding: west-of-UTC offsets are sent offset-by-128, not two's complement.
        val tlvs = HuaweiProtocol.parseTlvs(HuaweiCommands.setTime(1_787_346_631L, 128 + 5, 0))
        assertEquals(133, tlvs.first { it.tag == 2 }.value[0].toInt() and 0xFF)
    }
}
