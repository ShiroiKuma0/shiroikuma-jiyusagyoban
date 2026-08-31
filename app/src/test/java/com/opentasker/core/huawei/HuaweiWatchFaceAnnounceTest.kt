package com.opentasker.core.huawei

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Our watch-face announcement, diffed against the one Huawei Health actually sent.
 *
 * Three rounds of trying this on the band produced three different wrong answers, each of which
 * reported success. The fixtures here are Health's own frames, captured and decrypted, so the
 * comparison is exact and costs nothing — no band, no second phone, no waiting.
 *
 * `watchface-announce-plain.bin` is the decrypted TLV payload; the two `frag` files are the wire
 * frames it was split across.
 */
class HuaweiWatchFaceAnnounceTest {

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/huawei/$name")) { "missing fixture $name" }
            .readBytes()

    private val plain by lazy { fixture("watchface-announce-plain.bin") }

    @Test
    fun `Health's announcement carries the tags we think it does`() {
        val tl = HuaweiProtocol.parseTlvs(plain).associateBy { it.tag }
        assertEquals("7185695173", String(tl.getValue(1).value, Charsets.US_ASCII))
        assertEquals("2.1.1", String(tl.getValue(2).value, Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(1), tl.getValue(3).value)
        assertEquals(286, HuaweiProtocol.bytesToInt(tl.getValue(5).value))
        assertEquals(482, HuaweiProtocol.bytesToInt(tl.getValue(6).value))
        assertTrue(String(tl.getValue(8).value, Charsets.UTF_8).startsWith("{\"resultinfo\""))
    }

    @Test
    fun `our builder reproduces Health's payload byte for byte`() {
        val tl = HuaweiProtocol.parseTlvs(plain).associateBy { it.tag }
        val json = String(tl.getValue(8).value, Charsets.UTF_8)
        val ours = HuaweiCommands.watchFaceAnnounce("7185695173", "2.1.1", 286, 482, json)
        if (!ours.contentEquals(plain)) {
            val a = HuaweiProtocol.parseTlvs(plain).map { "${it.tag}:${it.value.size}" }
            val b = HuaweiProtocol.parseTlvs(ours).map { "${it.tag}:${it.value.size}" }
            throw AssertionError(
                "announcement differs\n  Health: $a (${plain.size} B)\n  ours  : $b (${ours.size} B)",
            )
        }
    }

    @Test
    fun `fragmenting reproduces Health's own split`() {
        // Health's envelope was 1214 bytes and went out as 1022 + 208 on the wire. The exact split
        // is what proves our limit and header layout match, not just that we split at all.
        val f1 = fixture("watchface-announce-frag1.bin")
        val f2 = fixture("watchface-announce-frag2.bin")
        val envelope = f1.copyOfRange(7, f1.size - 2) + f2.copyOfRange(5, f2.size - 2)

        val ours = HuaweiProtocol.fragments(0x27, 0x03, envelope)
        assertEquals("Health used two fragments", 2, ours.size)

        // Header shape: magic, length, slice flag, index, then service+command on the FIRST only.
        assertEquals(0x5A.toByte(), ours[0][0])
        assertEquals("first fragment flag", 1, ours[0][3].toInt())
        assertEquals("first fragment index", 0, ours[0][4].toInt())
        assertEquals("service", 0x27, ours[0][5].toInt())
        assertEquals("command", 0x03, ours[0][6].toInt())
        assertEquals("last fragment flag", 3, ours[1][3].toInt())
        assertEquals("last fragment index", 1, ours[1][4].toInt())

        assertArrayEquals("fragment 1 differs from Health's", f1, ours[0])
        assertArrayEquals("fragment 2 differs from Health's", f2, ours[1])
    }
}
