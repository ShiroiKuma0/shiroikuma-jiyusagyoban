package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Record parsing, checked against a step record pulled off 白い熊's own band (firmware 6.0.0.125,
 * record 9 of a 24-hour window) — not against a synthetic example.
 *
 * That record is also the clearest demonstration of the data's shape: thirty-four minutes, of which
 * exactly two carry anything. The sparsity is real and must survive parsing.
 */
class HuaweiRecordsTest {

    /** Record 9, verbatim off the band. */
    private val realRecord = HuaweiCrypto.hex(
        "0202000903046A89022884060501000601008406050101060100840605010206010084060501030601" +
            "00840605010406010084060501050601008406050106060100840605010706010084060501080601" +
            "00840605010906010084060501" +
            "0A0601008406050" +
            "10B0601008406050" +
            "10C0601008406050" +
            "10D0601008406050" +
            "10E0601008406050" +
            "10F060100840605011006010084060501110601008406050112060100840605011306010084060501" +
            "1406010084060501150601008406050116060100840605011706010084060501180601008406050119" +
            "0601008406050" +
            "11A0601008406050" +
            "11B0601008406050" +
            "11C0601008406050" +
            "11D0601008406050" +
            "11E060100" +
            "8408050" +
            "11F060308001884080501200603080002840605012106010008010" +
            "0",
    )

    @Test
    fun `the real record parses to its index and base timestamp`() {
        val rec = HuaweiRecords.parseStepRecord(
            listOf(HuaweiProtocol.Tlv(0x81, realRecord)),
        )!!
        assertEquals(9, rec.index)
        assertEquals(0x6A890228L, rec.baseEpochSeconds)
        assertEquals(34, rec.minutes.size)
    }

    @Test
    fun `only the two minutes that carry data are non-empty`() {
        val rec = HuaweiRecords.parseStepRecord(listOf(HuaweiProtocol.Tlv(0x81, realRecord)))!!
        val withData = rec.withData
        assertEquals("the grid is sparse; that is the data, not a fault", 2, withData.size)
        assertEquals(24, withData[0].distance)
        assertEquals(2, withData[1].distance)
    }

    @Test
    fun `minute timestamps are the base plus sixty seconds per offset`() {
        val rec = HuaweiRecords.parseStepRecord(listOf(HuaweiProtocol.Tlv(0x81, realRecord)))!!
        val base = rec.baseEpochSeconds
        assertEquals(base, rec.minutes[0].epochSeconds)
        assertEquals(base + 60, rec.minutes[1].epochSeconds)
        // Offsets 0x1F and 0x20 are the two carrying distance.
        assertEquals(base + 60 * 0x1F, rec.withData[0].epochSeconds)
        assertEquals(base + 60 * 0x20, rec.withData[1].epochSeconds)
    }

    @Test
    fun `an absent field is null, never zero`() {
        // Zero steps is a real reading; "the band did not record steps" is not. Conflating them
        // would silently invent data.
        val m = HuaweiRecords.decodeMinute(1000L, HuaweiCrypto.hex("08 00 18"))
        assertEquals(24, m.distance)
        assertNull(m.steps)
        assertNull(m.heartRate)
        assertNull(m.spo2)
    }

    @Test
    fun `an empty payload is an empty minute`() {
        val m = HuaweiRecords.decodeMinute(1000L, ByteArray(0))
        assertTrue(m.isEmpty)
        val z = HuaweiRecords.decodeMinute(1000L, byteArrayOf(0))
        assertTrue("a zero bitmap means nothing was recorded", z.isEmpty)
    }

    @Test
    fun `steps calories and distance are two-byte, heart rate is one`() {
        // bitmap 0x4E = steps|calories|distance|heartRate
        val m = HuaweiRecords.decodeMinute(
            0L,
            HuaweiCrypto.hex("4E" + "0064" + "00C8" + "012C" + "50"),
        )
        assertEquals(100, m.steps)
        assertEquals(200, m.calories)
        assertEquals(300, m.distance)
        assertEquals(0x50, m.heartRate)
    }

    @Test
    fun `the second bitmap carries spo2 and resting heart rate as single bytes`() {
        // bit 0x80 in bitmap1 announces bitmap2; 0x03 there selects spo2 + resting HR.
        val m = HuaweiRecords.decodeMinute(0L, HuaweiCrypto.hex("80 03 62 3C"))
        assertEquals(0x62, m.spo2)
        assertEquals(0x3C, m.restingHeartRate)
    }

    @Test
    fun `a truncated minute yields what was decodable rather than throwing`() {
        // One malformed minute must not cost the whole record.
        val m = HuaweiRecords.decodeMinute(0L, HuaweiCrypto.hex("4E 00 64"))
        assertEquals(100, m.steps)
        assertNull(m.calories)
    }

    @Test
    fun `unknown feature bits are kept rather than discarded`() {
        // bit 0x10 is not a field we understand; dropping it would hide a firmware change.
        val m = HuaweiRecords.decodeMinute(0L, HuaweiCrypto.hex("10 01 F4"))
        assertEquals(500, m.unknown[0x10])
    }

    @Test
    fun `count parses out of the nested container`() {
        val payload = HuaweiProtocol.tlv(0x81, HuaweiProtocol.tlv(0x02, 321, 2))
        assertEquals(321, HuaweiRecords.parseCount(HuaweiProtocol.parseTlvs(payload)))
    }
}
