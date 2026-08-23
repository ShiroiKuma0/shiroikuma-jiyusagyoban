package com.opentasker.core.huawei

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The whole watch-face install, driven against a band that answers the way the real one did.
 *
 * Four attempts on 白い熊's actual band produced four confident wrong answers, the last of which hung
 * for eighteen minutes without reporting anything. Every one of those could have been caught here in
 * under a second: the capture already says exactly what the band replies to each command, so a fake
 * that replays those replies exercises the entire flow with no hardware, no second phone and no
 * waiting.
 *
 * This is not a mock of our own assumptions. Every response below is the shape the real band sent,
 * taken from a decrypted capture of Huawei Health installing this same face.
 */
class HuaweiWatchFaceReplayTest {

    /** The band, as observed: it answers, then drives the transfer by asking for blocks. */
    private class FakeBand(private val fileSize: Int, private val chunk: Int = 935) : HuaweiTransport {
        val received = ByteArray(fileSize)
        val covered = BooleanArray(fileSize)
        var announced: ByteArray? = null
        var offeredName: String? = null
        var digest: ByteArray? = null
        var deleted: String? = null   // tag 3 = 02 removes a face; nothing should send it here
        /** Set when the client tells the band to install what it transferred. */
        var installOrdered = false
        /** Every command the client sent, so a failure says what it did rather than what it did not. */
        val sawFromClient = mutableListOf<String>()
        private var nextOffset = 0
        private var wantFrom = 0
        private var wantTo = 0
        private val out = ArrayDeque<ByteArray>()

        private fun tlv(t: Int, v: ByteArray) = HuaweiProtocol.tlv(t, v)
        private fun send(svc: Int, cmd: Int, payload: ByteArray) =
            out.add(HuaweiProtocol.frame(svc, cmd, payload))

        /** Blocks of 8 chunks, exactly as the band asked for them. */
        private fun askForBlock() {
            wantFrom = nextOffset
            wantTo = minOf(nextOffset + chunk * 8, fileSize)
            if (nextOffset >= fileSize) {
                send(HuaweiCommands.SVC_FILE_UPLOAD, HuaweiCommands.UPLOAD_DONE,
                    tlv(1, byteArrayOf(1)) + tlv(2, byteArrayOf(1)))
                return
            }
            val len = minOf(chunk * 8, fileSize - nextOffset)
            send(
                HuaweiCommands.SVC_FILE_UPLOAD, HuaweiCommands.UPLOAD_BLOCK,
                tlv(1, byteArrayOf(1)) +
                    tlv(2, HuaweiProtocol.intBytes(nextOffset, 4)) +
                    tlv(3, HuaweiProtocol.intBytes(len, 4)),
            )
        }

        override suspend fun write(data: ByteArray) {
            // Reassemble fragments the way the band must.
            val slice = data[3].toInt()
            if (slice == 1) { frag = mutableListOf(data.copyOfRange(7, data.size - 2)); fragSvc = data[5].toInt(); fragCmd = data[6].toInt(); return }
            if (slice == 2) { frag?.add(data.copyOfRange(5, data.size - 2)); return }
            val svc: Int; val cmd: Int; val payload: ByteArray
            if (slice == 3) {
                frag?.add(data.copyOfRange(5, data.size - 2))
                svc = fragSvc; cmd = fragCmd
                payload = frag!!.fold(ByteArray(0)) { a, b -> a + b }
                frag = null
            } else {
                svc = data[4].toInt() and 0xFF; cmd = data[5].toInt() and 0xFF
                payload = data.copyOfRange(6, data.size - 2)
            }
            // Data frames are RAW, not TLV — parsing them as TLV throws on arbitrary file bytes,
            // which is a fair imitation of what the band must also avoid doing.
            if (svc == HuaweiCommands.SVC_FILE_UPLOAD && cmd == HuaweiCommands.UPLOAD_DATA) {
                if (sawFromClient.lastOrNull() != "data…") sawFromClient += "data…"
                val off = HuaweiProtocol.bytesToInt(payload.copyOfRange(2, 6))
                val body = payload.copyOfRange(6, payload.size)
                val n = minOf(body.size, fileSize - off)
                if (n > 0) {
                    System.arraycopy(body, 0, received, off, n)
                    for (i in off until off + n) covered[i] = true
                }
                // Ask for the next block only when the block actually requested is complete —
                // asking after every frame races the sender and stalls the transfer.
                if (wantTo > wantFrom && (wantFrom until wantTo).all { covered[it] }) {
                    nextOffset = wantTo
                    askForBlock()
                }
                return
            }

            sawFromClient += "0x%02x/0x%02x".format(svc, cmd)
            val tl = HuaweiProtocol.parseTlvs(payload).associateBy { it.tag }
            fun v(t: Int) = tl[t]?.value

            when {
                svc == HuaweiCommands.SVC_WATCHFACE && cmd == HuaweiCommands.WF_CAPABILITY ->
                    send(svc, cmd, tlv(1, "2.9".toByteArray()) +
                        tlv(2, HuaweiProtocol.intBytes(286, 2)) +
                        tlv(3, HuaweiProtocol.intBytes(482, 2)))

                svc == HuaweiCommands.SVC_WATCHFACE && cmd == HuaweiCommands.WF_CONTROL -> {
                    // TWO different commands share this shape and only tag 8 tells them apart: the
                    // announcement carries the face's store metadata, the install does not. A fake
                    // that ignores the difference answers both the same way and would let a client
                    // that never sends the install pass — which is exactly the bug that reached the
                    // band, so the distinction is the point of this branch.
                    when {
                        v(3)?.firstOrNull()?.toInt() == 1 && v(8) != null -> {
                            announced = payload
                            send(svc, cmd, tlv(1, v(1)!!) + tlv(2, v(2)!!) + tlv(4, byteArrayOf(1)) +
                                tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.intBytes(0, 4)))
                        }
                        v(3)?.firstOrNull()?.toInt() == 1 -> {   // install what was transferred
                            installOrdered = true
                            send(svc, HuaweiCommands.WF_PROGRESS,
                                tlv(1, v(1)!!) + tlv(2, v(2)!!) + tlv(3, byteArrayOf(1)) + tlv(4, byteArrayOf(0)))
                            send(svc, cmd, tlv(1, v(1)!!) + tlv(2, v(2)!!) + tlv(4, byteArrayOf(0)) +
                                tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.intBytes(0, 4)))
                        }
                        v(3)?.firstOrNull()?.toInt() == 2 ->
                            deleted = v(1)?.toString(Charsets.US_ASCII)
                    }
                }

                svc == HuaweiCommands.SVC_FILE_UPLOAD && cmd == HuaweiCommands.UPLOAD_REQUEST -> {
                    offeredName = v(1)?.toString(Charsets.US_ASCII)
                    send(svc, cmd, tlv(1, v(1)!!) + tlv(3, byteArrayOf(1)) + tlv(4, byteArrayOf(1)) +
                        tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.intBytes(HuaweiProtocol.RESULT_SUCCESS, 4)))
                    send(svc, HuaweiCommands.UPLOAD_HASH,
                        tlv(1, byteArrayOf(1)) + tlv(2, byteArrayOf(3)) + tlv(4, HuaweiProtocol.intBytes(0, 4)))
                }

                svc == HuaweiCommands.SVC_FILE_UPLOAD && cmd == HuaweiCommands.UPLOAD_HASH -> {
                    digest = v(3)
                    send(svc, HuaweiCommands.UPLOAD_PARAMS,
                        tlv(1, byteArrayOf(1)) + tlv(2, "1.0.0".toByteArray()) +
                            tlv(5, HuaweiProtocol.intBytes(chunk, 2)) +
                            tlv(6, HuaweiProtocol.intBytes(chunk * 8, 4)))
                }

                svc == HuaweiCommands.SVC_FILE_UPLOAD && cmd == HuaweiCommands.UPLOAD_PARAMS -> askForBlock()

                svc == HuaweiCommands.SVC_FILE_UPLOAD && cmd == HuaweiCommands.UPLOAD_DONE -> {
                    // Progress only. The real band reports the transfer and then WAITS to be told
                    // to unpack; it does not volunteer completion.
                    send(HuaweiCommands.SVC_WATCHFACE, HuaweiCommands.WF_PROGRESS,
                        tlv(1, "7185695173".toByteArray()) + tlv(2, "2.1.1".toByteArray()) +
                            tlv(3, byteArrayOf(2)) + tlv(4, byteArrayOf(0)))
                }
            }
        }

        private var frag: MutableList<ByteArray>? = null
        private var fragSvc = 0
        private var fragCmd = 0

        override suspend fun read(timeoutMs: Long): ByteArray? = out.removeFirstOrNull()
        override suspend fun close() = Unit
    }

    private fun metaJson(): String {
        val plain = checkNotNull(javaClass.getResourceAsStream("/huawei/watchface-announce-plain.bin"))
            .readBytes()
        val tl = HuaweiProtocol.parseTlvs(plain).associateBy { it.tag }
        return String(tl.getValue(8).value, Charsets.UTF_8)
    }

    @Test
    fun `the whole install completes against a band that behaves like the real one`() = runBlocking {
        val face = ByteArray(120_000) { ((it * 31) % 251).toByte() }
        val band = FakeBand(face.size)
        val client = HuaweiUploadClient(HuaweiSession(band))

        // A generous bound that is still far short of "hangs forever" — the failure this exists for.
        val outcome = withTimeoutOrNull(20_000) {
            client.installWatchFace("7185695173", "2.1.1", face, metaJson(), timeoutMs = 15_000)
        }
        checkNotNull(outcome) { "installWatchFace never returned — the hang reproduces here" }
        assertTrue(
            "install reported failure: ${outcome.message}\n  client sent: ${band.sawFromClient}",
            outcome.ok,
        )

        assertArrayEquals("the band did not receive the face intact", face, band.received)
        assertEquals("7185695173_2.1.1", band.offeredName)
        assertArrayEquals(
            "the digest we sent is not SHA-256 of the file",
            MessageDigest.getInstance("SHA-256").digest(face), band.digest,
        )
        assertTrue("the band was never told to install what it received", band.installOrdered)
        // The install is what puts the face on screen. The client used to follow it with
        // tag 3 = 02, believing that selected a face; it DELETES one, so the band showed the
        // new face for an instant and then dropped it. Asserting the frame is absent is the
        // only way this stays fixed — the previous version of this test asserted it present.
        assertNull("a face was deleted — tag 3 = 02 must never follow an install", band.deleted)
    }

    @Test
    fun `the announcement we send is the one the band was given`() = runBlocking {
        val face = ByteArray(4_000) { it.toByte() }
        val band = FakeBand(face.size)
        withTimeoutOrNull(20_000) {
            HuaweiUploadClient(HuaweiSession(band))
                .installWatchFace("7185695173", "2.1.1", face, metaJson(), timeoutMs = 15_000)
        }
        val sent = checkNotNull(band.announced) { "no announcement reached the band" }
        val expected = checkNotNull(javaClass.getResourceAsStream("/huawei/watchface-announce-plain.bin"))
            .readBytes()
        assertArrayEquals("announcement differs from Health's", expected, sent)
    }
}
