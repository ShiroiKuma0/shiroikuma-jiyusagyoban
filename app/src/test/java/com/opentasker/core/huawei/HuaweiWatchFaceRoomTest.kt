package com.opentasker.core.huawei

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens before a single byte of a face is sent.
 *
 * 白い熊, 2026-08-28: *"the band is full and a face needs to be removed, before a new one gets
 * installed — however it isn't done automatically"*. It could not be: the only thing the install
 * knew was that the band had refused the announcement, which reads as a broken install rather than
 * as a full shelf.
 *
 * Asking `0x27/0x02` first — what the band holds, and its own free figure — answers most of it: a
 * face the band already has is never sent again, and one it has but is not showing is brought to
 * the front instead.
 *
 * **But the free figure is not what identifies a full band.** Measured on 白い熊's band the same
 * day: eighteen faces, 85 reported free, the announcement accepted (`wf/3 r=0 s=1`, "send it") and
 * then not one byte ever asked for — twice, identically. The band goes on claiming room long after
 * it has stopped taking faces. So what raises the question is the thing that actually happened:
 * the band engaged and no byte moved. A transfer that started and then died is deliberately NOT
 * that, because the fix for a dropped link is not deleting one of 白い熊's faces.
 */
class HuaweiWatchFaceRoomTest {

    /**
     * A band that answers its face list and nothing else.
     *
     * Nothing else is needed: every test here is about the decision taken BEFORE the announcement,
     * so a band that goes quiet afterwards is a perfectly good stand-in — and it keeps these tests
     * to milliseconds.
     */
    private class BandWithShelf(
        private val faces: List<Triple<String, String, Boolean>>,
        private val freeUnits: Int,
    ) : HuaweiTransport {
        val sawFromClient = mutableListOf<String>()
        private val out = ArrayDeque<ByteArray>()
        override var isOpen: Boolean = true

        private fun tlv(t: Int, v: ByteArray) = HuaweiProtocol.tlv(t, v)

        /** One face record: `0x82 <len>` then tag 3 = asset id, 4 = version, 5 = the showing flag. */
        private fun record(assetId: String, version: String, showing: Boolean): ByteArray {
            val body = tlv(3, assetId.toByteArray()) + tlv(4, version.toByteArray()) +
                tlv(5, byteArrayOf(if (showing) 5 else 1))
            return byteArrayOf(0x82.toByte(), body.size.toByte()) + body
        }

        override suspend fun write(data: ByteArray) {
            val slice = data[3].toInt()
            if (slice == 2 || slice == 3) return
            val svc: Int
            val cmd: Int
            if (slice == 1) {
                svc = data[5].toInt() and 0xFF
                cmd = data[6].toInt() and 0xFF
            } else {
                svc = data[4].toInt() and 0xFF
                cmd = data[5].toInt() and 0xFF
            }
            sawFromClient += "0x%02x/0x%02x".format(svc, cmd)

            if (svc != HuaweiCommands.SVC_WATCHFACE) return
            when (cmd) {
                HuaweiCommands.WF_LIST -> out.add(
                    HuaweiProtocol.frame(
                        svc, cmd,
                        tlv(9, HuaweiProtocol.intBytes(freeUnits, 4)) +
                            tlv(129, faces.fold(ByteArray(0)) { acc, (id, v, s) -> acc + record(id, v, s) }),
                    ),
                )
                HuaweiCommands.WF_CAPABILITY -> out.add(
                    HuaweiProtocol.frame(
                        svc, cmd,
                        tlv(1, "2.9".toByteArray()) +
                            tlv(2, HuaweiProtocol.intBytes(286, 2)) +
                            tlv(3, HuaweiProtocol.intBytes(482, 2)),
                    ),
                )
            }
        }

        override suspend fun read(timeoutMs: Long): ByteArray? {
            out.removeFirstOrNull()?.let { return it }
            delay(minOf(timeoutMs, 25))
            return null
        }

        override suspend fun close() {
            isOpen = false
        }
    }

    private fun metaJson(): String {
        val plain = checkNotNull(javaClass.getResourceAsStream("/huawei/watchface-announce-plain.bin"))
            .readBytes()
        return String(HuaweiProtocol.parseTlvs(plain).first { it.tag == 8 }.value, Charsets.UTF_8)
    }

    private val face = ByteArray(40_000) { it.toByte() }

    @Test
    fun `a full band stops the install before anything is announced`() = runBlocking {
        val band = BandWithShelf(
            faces = listOf(
                Triple("2182762613", "2.9.5", false),
                Triple("7185922173", "2.1.1", true),
            ),
            freeUnits = 0,
        )
        val outcome = HuaweiUploadClient(HuaweiSession(band)).installWatchFace(
            assetId = "7185695173", version = "2.1.1", bytes = face, metaJson = metaJson(),
            timeoutMs = 4_000, silenceMs = 300, engageMs = 300,
        )

        assertFalse(outcome.ok)
        assertTrue("a full band is a question, not a failure", outcome.needsRoom)
        assertEquals(0, outcome.bytesSent)
        // The list has to travel back with it: the window cannot ask WHICH face should go without
        // it, and reading the band a second time would be a second session.
        assertNotNull(outcome.store)
        assertEquals(2, outcome.store!!.faces.size)
        // Nothing was announced. An announcement here is a megabyte offered to a band with no room
        // for it, and it is what 白い熊 was hitting.
        assertEquals(listOf("0x27/0x02"), band.sawFromClient)
    }

    @Test
    fun `a face the band already shows is not sent again`() = runBlocking {
        val band = BandWithShelf(
            faces = listOf(Triple("7185695173", "2.1.1", true)),
            freeUnits = 4,
        )
        val outcome = HuaweiUploadClient(HuaweiSession(band)).installWatchFace(
            assetId = "7185695173", version = "2.1.1", bytes = face, metaJson = metaJson(),
            timeoutMs = 4_000, silenceMs = 300, engageMs = 300,
        )

        assertTrue("it is already on the wrist — that is success, not a transfer", outcome.ok)
        assertEquals(0, outcome.bytesSent)
        assertEquals(listOf("0x27/0x02"), band.sawFromClient)
    }

    @Test
    fun `a face on the band but not showing is brought forward instead of re-sent`() = runBlocking {
        val band = BandWithShelf(
            faces = listOf(
                Triple("7185695173", "2.1.1", false),
                Triple("2182762613", "2.9.5", true),
            ),
            freeUnits = 4,
        )
        val outcome = HuaweiUploadClient(HuaweiSession(band)).installWatchFace(
            assetId = "7185695173", version = "2.1.1", bytes = face, metaJson = metaJson(),
            timeoutMs = 4_000, silenceMs = 300, engageMs = 300,
        )

        // This band's list never changes, so the activation cannot be confirmed and the outcome
        // says so rather than claiming a screen it did not change. What matters here is the route
        // taken: the capability query and the control command, and NOT a transfer.
        assertEquals(0, outcome.bytesSent)
        assertTrue(
            "it must ask the band's screen size and send the control command: ${band.sawFromClient}",
            band.sawFromClient.containsAll(listOf("0x27/0x01", "0x27/0x03")),
        )
        assertFalse(
            "the file must never be offered for a face the band already holds",
            band.sawFromClient.any { it.startsWith("0x28") },
        )
    }

    @Test
    fun `a band that engages and then sends nothing raises the room question anyway`() = runBlocking {
        // The shape 白い熊 actually hits, and the reason the free-space figure cannot be the test:
        // measured 2026-08-28, the band held eighteen faces, reported 85 free, accepted the
        // announcement and then never asked for a byte. This fixture is that band — it answers the
        // list, and nothing after it.
        val band = BandWithShelf(faces = listOf(Triple("2182762613", "2.9.5", true)), freeUnits = 85)
        val outcome = HuaweiUploadClient(HuaweiSession(band)).installWatchFace(
            assetId = "7185695173", version = "2.1.1", bytes = face, metaJson = metaJson(),
            timeoutMs = 4_000, silenceMs = 300, engageMs = 300,
        )

        assertFalse(outcome.ok)
        assertEquals(0, outcome.bytesSent)
        assertTrue(
            "a band that took nothing is the full band, whatever its free figure says",
            outcome.needsRoom,
        )
        assertNotNull(outcome.store)
        // It still got as far as announcing: the pre-flight must not stand in the way of an install
        // that could have worked.
        assertTrue("it must announce the face: ${band.sawFromClient}", band.sawFromClient.contains("0x27/0x03"))
        assertTrue(
            "the report must say what the band was holding: ${outcome.message}",
            outcome.message.contains("85 free"),
        )
    }

    @Test
    fun `activate names the face and never the delete tag`() = runBlocking {
        val band = BandWithShelf(faces = listOf(Triple("7185695173", "2.1.1", false)), freeUnits = 3)
        val ok = HuaweiUploadClient(HuaweiSession(band)).activate("7185695173", "2.1.1")

        // The band's list is unchanged, so the honest answer is "no": activation is claimed only
        // when the band itself reports the face as the one on screen.
        assertFalse("an unchanged list must not be read as success", ok)
        assertTrue(band.sawFromClient.contains("0x27/0x03"))
    }
}
