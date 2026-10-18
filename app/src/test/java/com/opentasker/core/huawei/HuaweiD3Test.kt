package com.opentasker.core.huawei

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Watch D3's new data paths, against the bytes Huawei Health exchanged with it on 2026-10-17
 * (docs/huawei-watch-d3.md §3.7). A decoder that mispairs a field id with its value would put a
 * pulse where a systolic pressure belongs, and nothing downstream could tell.
 */
class HuaweiD3Test {

    private fun hex(s: String) = HuaweiCrypto.hex(s)

    /** The watch's answer to class 10002 right after a spot measurement: 129/88, pulse 81. */
    private val bpAnswer =
        "0101040000000102040000271283814e84814b0508000001a14b152b680c08000001a14b152b6887813488100904000007d60a08" +
            "406020000000000088100904000007d70a084056000000000000881009040098a0690a084054400000000000881009040098" +
            "a1650a083ff0000000000000881009040098a10a0a083ff0000000000000881009040098a01e0a0840b00000000000008810" +
            "09040098a09a0a0840000000000000008810090400989f3c0a08402a0000000000008810090400989f820a08407060000000" +
            "0000881009040098a16e0a080000000000000000"

    @Test
    fun `the blood-pressure record decodes to the watch's own reading`() {
        val records = HuaweiDictionary.parseAnswer(hex(bpAnswer))!!
        assertEquals(1, records.size)
        val r = records.single()
        assertEquals(HuaweiDictionary.BLOOD_PRESSURE, r.cls)
        assertEquals(0x01a14b152b68L, r.epochMs)
        assertEquals(129.0, r.fields[2006L]!!, 0.0)
        assertEquals(88.0, r.fields[2007L]!!, 0.0)
        assertEquals(81.0, r.fields[10002537L]!!, 0.0)
        assertEquals("bp_systolic", HuaweiDictionary.metricFor(r.cls, 2006L))
        assertEquals("bp_pulse", HuaweiDictionary.metricFor(r.cls, 10002537L))
        // An unnamed field is kept, not dropped.
        assertEquals("dict_10002_10002462", HuaweiDictionary.metricFor(r.cls, 10002462L))
        assertEquals(4096.0, r.fields[10002462L]!!, 0.0)
    }

    @Test
    fun `an empty answer is no records rather than a failure`() {
        assertTrue(HuaweiDictionary.parseAnswer(hex("010104000000010204000027128300"))!!.isEmpty())
    }

    @Test
    fun `our query is Health's query`() {
        assertArrayEquals(
            hex("01010101020227120508000001a14b152f500608000001a14b1531ac0d0101"),
            HuaweiDictionary.queryBody(10002, 0x01a14b152f50L, 0x01a14b1531acL),
        )
        // Three-byte classes, as Health writes 400017.
        assertArrayEquals(hex("061a91"), HuaweiDictionary.classBytes(400017))
    }

    @Test
    fun `the D3 app message wraps the Band's module body`() {
        // Health switching emotion / stress OFF on the D3.
        val msg = HuaweiCommands.appMessage(
            HuaweiCommands.D3.EMOTION_PHONE, HuaweiCommands.D3.EMOTION_WATCH,
            HuaweiCommands.MODULE_EMOTION, HuaweiCommands.appSwitch(false),
        )
        val tl = HuaweiProtocol.parseTlvs(msg)
        assertEquals("hw.health.emotion", tl.first { it.tag == 1 }.value.toString(Charsets.UTF_8))
        assertEquals("hw.watch.health.emotion", tl.first { it.tag == 2 }.value.toString(Charsets.UTF_8))
        assertArrayEquals(hex("840e050435ac8a3c0601010703010100"), tl.first { it.tag == 0x83 }.value)
        assertArrayEquals(HuaweiCommands.moduleConfig(HuaweiCommands.MODULE_EMOTION, false), tl.first { it.tag == 0x83 }.value)
    }

    @Test
    fun `ECG activation is Health's bytes apart from the clock`() {
        val msg = HuaweiCommands.appMessage(
            HuaweiCommands.D3.ECG_PHONE, HuaweiCommands.D3.ECG_WATCH, HuaweiCommands.D3.ECG_ACTIVATE,
            HuaweiCommands.D3.ecgActivateItems(), stampMs = 0x01a14b11ef95L,
        )
        val body = HuaweiProtocol.parseTlvs(msg).first { it.tag == 0x83 }.value
        assertArrayEquals(
            hex("842f050435a97ce5060101071a0101010215" + "68772e6865616c74682e656367616e616c79736973" + "0808000001a14b11ef95"),
            body,
        )
    }

    @Test
    fun `skin temperature is 0x07 0x2A as Health sends it`() {
        assertArrayEquals(hex("0101010201010302ffff"), HuaweiCommands.skinTemperature(true))
        assertArrayEquals(hex("0101010201000302ffff"), HuaweiCommands.skinTemperature(false))
    }

    @Test
    fun `an ECG file yields its recordings with the watch's verdict`() {
        val header = ByteArray(57).also { "hw.health.ecganalysis".toByteArray().copyInto(it, 33) }
        fun rec(start: Long, verdict: Int, hr: Int, samples: Int): ByteArray {
            val b = java.nio.ByteBuffer.allocate(56 + samples * 4)
            b.putInt(start.toInt()); b.putInt((start + 30).toInt()); b.putInt(samples * 4)
            val block = ByteArray(44); "V1.0.0.27".toByteArray().copyInto(block)
            block[34] = 0; block[35] = verdict.toByte(); block[36] = 0; block[37] = hr.toByte()
            b.put(block)
            repeat(samples) { b.putFloat(it.toFloat()) }
            return b.array()
        }
        val file = header + rec(0x6ad3bd36, 0x40, 82, 15000) + rec(0x6ad3bd7f, 0x01, 80, 15000)
        val recs = HuaweiEcg.parse(file)
        assertEquals(2, recs.size)
        assertEquals(0x6ad3bd36L, recs[0].startSeconds)
        assertEquals(HuaweiEcg.VERDICT_INCONCLUSIVE, recs[0].verdict)
        assertEquals(82, recs[0].meanHr)
        assertEquals(HuaweiEcg.VERDICT_SINUS, recs[1].verdict)
        assertEquals(80, recs[1].meanHr)
        assertEquals("V1.0.0.27", recs[1].analysisVersion)
        assertEquals(15000, recs[1].sampleCount)
        assertEquals(14999f, HuaweiEcg.decode(recs[1].samples).last(), 0f)
    }

    @Test
    fun `the companion hello is Health's pairing bytes`() {
        // Captured 2026-10-17 19:27:52.858 (privacy), 19:27:51.388 (sync device), 19:27:53.396 (BP).
        val (privacy, syncDevice, bp) = HuaweiCommands.D3.companionHello(0x01A14AE7E32DL)
        fun body(m: ByteArray) = HuaweiProtocol.parseTlvs(m).first { it.tag == 0x83 }.value
        fun name(m: ByteArray, tag: Int) = HuaweiProtocol.parseTlvs(m).first { it.tag == tag }.value.toString(Charsets.UTF_8)
        assertArrayEquals(hex("840c050435a66fa5060101070100"), body(privacy))
        assertEquals("privacySwitch", name(privacy, 2))
        assertArrayEquals(hex("840e050435a66fa30601030703080101"), body(syncDevice))
        assertEquals("hw.unitedevice.syncdevice", name(syncDevice, 1))
        assertArrayEquals(hex("8413050435a97d1c0601020808000001a14ae7e32d"), body(bp))
        assertEquals("hw.watch.health.bp", name(bp, 2))
    }

    @Test
    fun `the full account step is Health's bytes`() {
        // 19:27:31.894 0x1A/0x05, decrypted.
        assertArrayEquals(
            hex("0111" + "3132333435363738393031323334353637" + "030101" + "040100" + "0502435a" + "0602435a" +
                "0700" + "0805656e2d5553" + "090100"),
            HuaweiCommands.extendedAccountFull("CZ", "en-US", "12345678901234567"),
        )
    }
}
