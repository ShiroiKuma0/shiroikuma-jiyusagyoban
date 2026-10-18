package com.opentasker.core.huawei

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync's answer to the watch's satellite asks, against a watch that asks the way the Watch D3
 * asked Health on 2026-10-17 19:29: the broadcast file, then the forecast, then the ionosphere —
 * which Health declined with ready `04`.
 */
class HuaweiGnssServerTest {

    private val address = "AA:BB:CC:DD:EE:FF"

    /** The watch, as captured: it asks, names a source, and drives the `0x1C` pull itself. */
    private class FakeWatch(private val sources: List<String>) : HuaweiTransport {
        private val out = ArrayDeque<ByteArray>()
        private var round = 0
        private var listed: List<String> = emptyList()
        private var picking = 0
        private var size = 0
        private var got = ByteArray(0)
        val readies = mutableListOf<Int>()
        val lists = mutableListOf<List<String>>()
        val tokens = mutableListOf<String>()
        val files = LinkedHashMap<String, ByteArray>()

        init { ask() }

        private fun tlv(t: Int, v: ByteArray) = HuaweiProtocol.tlv(t, v)
        private fun send(svc: Int, cmd: Int, payload: ByteArray) = out.add(HuaweiProtocol.frame(svc, cmd, payload))
        private fun ask() {
            if (round < sources.size) send(HuaweiCommands.SVC_GNSS_ASK, HuaweiCommands.GNSS_NOTIFY, tlv(2, byteArrayOf(1)))
        }
        private fun pick() {
            if (picking >= listed.size) { round++; ask(); return }
            send(HuaweiCommands.SVC_GNSS_FILES, HuaweiCommands.GNSS_PICK, HuaweiProtocol.tlv(1, listed[picking]))
        }

        override suspend fun write(data: ByteArray) {
            val svc = data[4].toInt() and 0xFF
            val cmd = data[5].toInt() and 0xFF
            val payload = data.copyOfRange(6, data.size - 2)
            if (svc == HuaweiCommands.SVC_GNSS_FILES && cmd == HuaweiCommands.GNSS_DATA) {
                got += payload.copyOfRange(1, payload.size)
                if (got.size >= size) {
                    files[listed[picking]] = got
                    send(svc, HuaweiCommands.GNSS_DONE, tlv(1, byteArrayOf(1)))
                }
                return
            }
            val t = HuaweiProtocol.parseTlvs(payload)
            fun v(tag: Int) = t.firstOrNull { it.tag == tag }?.value
            when (svc to cmd) {
                HuaweiCommands.SVC_GNSS_ASK to HuaweiCommands.GNSS_WHAT ->
                    send(svc, cmd, tlv(129, tlv(4, byteArrayOf(0x1E)) + tlv(5, byteArrayOf(3)) + HuaweiProtocol.tlv(6, sources[round])))
                HuaweiCommands.SVC_GNSS_ASK to HuaweiCommands.GNSS_READY -> {
                    val r = v(1)!![0].toInt()
                    readies += r
                    if (r == 3) send(HuaweiCommands.SVC_GNSS_FILES, HuaweiCommands.GNSS_LIST, tlv(2, byteArrayOf(1)))
                    else { round++; ask() }
                }
                HuaweiCommands.SVC_GNSS_FILES to HuaweiCommands.GNSS_LIST -> {
                    listed = v(1)!!.toString(Charsets.UTF_8).split(';')
                    lists += listed
                    picking = 0
                    send(svc, HuaweiCommands.GNSS_PARAMS, tlv(3, HuaweiProtocol.intBytes(862, 2)))
                }
                HuaweiCommands.SVC_GNSS_FILES to HuaweiCommands.GNSS_PARAMS -> pick()
                HuaweiCommands.SVC_GNSS_FILES to HuaweiCommands.GNSS_PICK -> {
                    size = HuaweiProtocol.bytesToInt(v(2)!!)
                    got = ByteArray(0)
                    send(svc, HuaweiCommands.GNSS_BLOCK, HuaweiProtocol.tlv(1, listed[picking]) +
                        tlv(2, HuaweiProtocol.intBytes(0, 4)) + tlv(3, HuaweiProtocol.intBytes(size, 4)))
                }
                HuaweiCommands.SVC_GNSS_FILES to HuaweiCommands.GNSS_BLOCK ->
                    tokens += v(2)!!.toString(Charsets.UTF_8).removeSuffix(listed[picking])
                HuaweiCommands.SVC_GNSS_FILES to HuaweiCommands.GNSS_DONE -> { picking++; pick() }
            }
        }

        override suspend fun read(timeoutMs: Long): ByteArray? = out.removeFirstOrNull()
        override suspend fun close() {}
    }

    private val rtcm = ByteArray(7401) { (it * 7).toByte() }
    private val gps = ByteArray(2000) { (it * 3).toByte() }
    private val gal = ByteArray(1500) { (it * 5).toByte() }

    private fun supply(want: HuaweiGnssServer.Want): Map<String, ByteArray>? = when (want) {
        HuaweiGnssServer.Want.BROADCAST -> mapOf("HW_AGNSS_RTCM_33" to rtcm)
        HuaweiGnssServer.Want.PREDICTED -> mapOf("HW_PGNSS_GPS" to gps, "HW_PGNSS_GALILEO" to gal)
        HuaweiGnssServer.Want.OTHER -> null
    }

    @Test
    fun `answers each ask with its own kind and declines the ionosphere`() = runBlocking {
        val watch = FakeWatch(
            listOf(
                "higeo/v1/gnssinfo?type=0x0004/HW_AGNSS",
                "com.huawei.higeo_dataModule_PGNSSConfig?type=HW_EEV2/HW_PGNSS_PRED;",
                "higeo/v1/gnssIonInfo/50.02,14.42/HW_PRECSION_ION",
            ),
        )
        val rounds = HuaweiGnssServer.answer(HuaweiSession(watch), address, 1_000, nextWaitMs = 200, supply = ::supply)

        assertEquals(
            listOf(HuaweiGnssServer.Want.BROADCAST, HuaweiGnssServer.Want.PREDICTED, HuaweiGnssServer.Want.OTHER),
            rounds.map { it.want },
        )
        // Health's answers: 03, 03, 04 — and a list of only the kind asked for.
        assertEquals(listOf(3, 3, 4), watch.readies)
        assertEquals(listOf(listOf("HW_AGNSS_RTCM_33"), listOf("HW_PGNSS_GPS", "HW_PGNSS_GALILEO")), watch.lists)
        assertArrayEquals(rtcm, watch.files["HW_AGNSS_RTCM_33"])
        assertArrayEquals(gps, watch.files["HW_PGNSS_GPS"])
        assertArrayEquals(gal, watch.files["HW_PGNSS_GALILEO"])
        assertEquals(listOf("HW_PGNSS_GPS", "HW_PGNSS_GALILEO"), rounds[1].served)
        assertTrue(rounds[2].offered.isEmpty())
        assertTrue(watch.tokens.all { it == HuaweiGnssServer.token(address) })
    }

    @Test
    fun `no ask, no answer`() = runBlocking {
        val rounds = HuaweiGnssServer.answer(HuaweiSession(FakeWatch(emptyList())), address, 200, supply = ::supply)
        assertTrue(rounds.isEmpty())
    }

    @Test
    fun `the block token is the SHA-256 of the address, as Health sends it`() {
        // Health's constant to the Band 11 Pro, from the 2026-08-25 capture — the hash of the Band's
        // address. The Watch D3's differs and is the hash of its own.
        assertEquals(
            "42E41FAF3CAABEF0E56DFD793DF99E6DF15EA2FC9B18A5D73ABF0DC1D0F06CCA",
            HuaweiGnssServer.token("A4:AA:FE:34:29:0F"),
        )
    }

    @Test
    fun `asks are told apart by their source`() {
        assertEquals(HuaweiGnssServer.Want.BROADCAST, HuaweiGnssServer.wantOf("higeo/v1/gnssinfo?type=0x0004/HW_AGNSS"))
        assertEquals(
            HuaweiGnssServer.Want.PREDICTED,
            HuaweiGnssServer.wantOf("com.huawei.higeo_dataModule_PGNSSConfig?type=HW_EEV2/HW_PGNSS_PRED;"),
        )
        assertEquals(HuaweiGnssServer.Want.OTHER, HuaweiGnssServer.wantOf("higeo/v1/gnssIonInfo/50.02,14.42/HW_PRECSION_ION"))
        assertEquals(HuaweiGnssServer.Want.OTHER, HuaweiGnssServer.wantOf(null))
    }
}
