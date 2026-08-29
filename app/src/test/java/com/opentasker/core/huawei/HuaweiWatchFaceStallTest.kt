package com.opentasker.core.huawei

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the watch-face pump does when the band stops holding up its end.
 *
 * [HuaweiWatchFaceReplayTest] proves the install works against a band that behaves. These prove the
 * other half, which is the half that was actually shipped broken: the band drives this transfer, so
 * every failure mode is the band going quiet, and the pump used to answer all of them by polling
 * silently for four minutes and then saying "timed out". Four minutes is not a report — and while
 * it ran, one boolean in the Tasks screen greyed out the Run arrow on every task 白い熊 owns.
 *
 * Each test sets its own budgets so it runs in milliseconds. The budgets are knobs, like the
 * existing `timeoutMs`; what is under test is that the pump gives up on the SILENCE rather than on
 * the ceiling, and says which.
 */
class HuaweiWatchFaceStallTest {

    /**
     * A band that answers the opening questions, asks for the file, and then stops.
     *
     * The nastiest real shape, because it is the one every early-exit check missed: the "it never
     * engaged" timeout is switched off the moment it asks, so from there the pump had nothing left
     * to notice with.
     */
    private open class BandThatGoesQuiet(
        /** True: acknowledge the announcement (asking for the file), then fall silent. False: never
         *  acknowledge it at all, which is the band declining a face it may already hold. */
        private val answerUntilAsked: Boolean = true,
    ) : HuaweiTransport {
        val sawFromClient = mutableListOf<String>()
        private val out = ArrayDeque<ByteArray>()
        override var isOpen: Boolean = true
            protected set

        private fun tlv(t: Int, v: ByteArray) = HuaweiProtocol.tlv(t, v)

        override suspend fun write(data: ByteArray) {
            // Fragments carry the service/command only in the first slice; the rest are body-only.
            val slice = data[3].toInt()
            if (slice == 2 || slice == 3) return
            // A first fragment carries service and command after the flag and index; a whole
            // frame carries them straight after the slice byte.
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

            // A full band is a HEALTHY band that has run out of space, so it answers everything
            // except the request for bytes. Answering the list here is not scene-setting: it is the
            // difference between "install failed" and a dialog that can name the faces to give up.
            if (svc == HuaweiCommands.SVC_WATCHFACE && cmd == HuaweiCommands.WF_LIST) {
                fun face(id: String, showing: Boolean): ByteArray {
                    val body = tlv(3, id.toByteArray()) + tlv(4, "1.0.0".toByteArray()) +
                        tlv(5, byteArrayOf(if (showing) 5 else 0))
                    return byteArrayOf(0x82.toByte(), body.size.toByte()) + body
                }
                out.add(
                    HuaweiProtocol.frame(
                        svc, cmd,
                        // 85 "free", while full. Not a contrivance — this is what 白い熊's band
                        // reports while holding eighteen faces and refusing a nineteenth. Whatever
                        // tag 9 counts, it is not slots, which is why the freeUnits == 0 pre-check
                        // upstream of this never fires on the real hardware and the stall has to be
                        // caught by behaviour instead.
                        tlv(9, HuaweiProtocol.intBytes(85, 4)) +
                            tlv(129, face("7186018013", true) + face("7184229813", false)),
                    ),
                )
                return
            }
            if (svc == HuaweiCommands.SVC_WATCHFACE && cmd == HuaweiCommands.WF_CAPABILITY) {
                out.add(
                    HuaweiProtocol.frame(
                        svc, cmd,
                        tlv(1, "2.9".toByteArray()) +
                            tlv(2, HuaweiProtocol.intBytes(286, 2)) +
                            tlv(3, HuaweiProtocol.intBytes(482, 2)),
                    ),
                )
                return
            }
            // The announcement arrives fragmented, so it is the FIRST slice that identifies it.
            if (svc == HuaweiCommands.SVC_WATCHFACE && cmd == HuaweiCommands.WF_CONTROL) {
                onAnnounced()
                if (answerUntilAsked) {
                    out.add(
                        HuaweiProtocol.frame(
                            svc, cmd,
                            tlv(1, "7185695173".toByteArray()) + tlv(2, "2.1.1".toByteArray()) +
                                tlv(4, byteArrayOf(1)) +
                                tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.intBytes(0, 4)),
                        ),
                    )
                }
            }
            // Everything after that — the upload offer included — goes unanswered. That is the point.
        }

        /** Hook for the variant that drops the link at exactly this moment. */
        protected open fun onAnnounced() = Unit

        override suspend fun read(timeoutMs: Long): ByteArray? {
            out.removeFirstOrNull()?.let { return it }
            // Honour the timeout the way a real transport does, so the pump is paced rather than
            // spun. A fake that returns instantly would hide a busy-wait instead of exposing it.
            delay(minOf(timeoutMs, 25))
            return null
        }

        override suspend fun close() {
            isOpen = false
        }
    }

    /** The band drops the RFCOMM link the instant the announcement lands. */
    private class BandThatHangsUp : BandThatGoesQuiet(answerUntilAsked = false) {
        override fun onAnnounced() {
            isOpen = false
        }
    }

    private fun metaJson(): String {
        val plain = checkNotNull(javaClass.getResourceAsStream("/huawei/watchface-announce-plain.bin"))
            .readBytes()
        return String(HuaweiProtocol.parseTlvs(plain).first { it.tag == 8 }.value, Charsets.UTF_8)
    }

    @Test
    fun `a band that asks for the face and then goes quiet is given up on in seconds`() = runBlocking {
        val face = ByteArray(40_000) { it.toByte() }
        val band = BandThatGoesQuiet()

        val started = System.currentTimeMillis()
        val outcome = withTimeoutOrNull(20_000) {
            HuaweiUploadClient(HuaweiSession(band)).installWatchFace(
                assetId = "7185695173", version = "2.1.1", bytes = face, metaJson = metaJson(),
                // A ceiling far above the silence budget: if the pump waits out the ceiling instead
                // of the silence, this test says so in the elapsed time rather than by hanging.
                timeoutMs = 15_000, silenceMs = 300, engageMs = 300,
            )
        }
        val elapsed = System.currentTimeMillis() - started

        assertNotNull("installWatchFace never returned", outcome)
        assertFalse("a band that sent nothing back cannot have installed anything", outcome!!.ok)
        assertTrue(
            "it waited out its whole ceiling instead of noticing the silence: ${elapsed}ms",
            elapsed < 5_000,
        )
        assertTrue(
            "the report must say the band went quiet, not merely that time ran out: " +
                outcome.message,
            outcome.message.contains("stopped answering"),
        )
        assertTrue(
            "the band did ask for the file, so the offer must have gone out: ${band.sawFromClient}",
            band.sawFromClient.contains("0x28/0x02"),
        )
    }

    /**
     * The full band, which is a stall with a question behind it.
     *
     * A band with no room accepts the announcement — it has no way of knowing the file is too big
     * until it tries to keep it — and then simply never asks for a block. That shape is identical to
     * a band that has crashed, so it used to be found by the silence budget forty-five seconds
     * later, or not at all if the band was chattering about something else. 白い熊 met it as an
     * install that "just hangs" (2026-08-28) and never saw the dialog that was waiting behind it.
     *
     * Two things are under test, and the second is the one that failed in his hands: that the stall
     * is noticed on its own short budget, and that the outcome carries the band's list, because the
     * window can only ask WHICH face to give up if it was told what the band is holding.
     */
    @Test
    fun `a band that accepts the face and then takes no bytes is reported as full, with its list`() = runBlocking {
        val face = ByteArray(40_000) { it.toByte() }
        val band = BandThatGoesQuiet()

        val started = System.currentTimeMillis()
        val outcome = withTimeoutOrNull(20_000) {
            HuaweiUploadClient(HuaweiSession(band)).installWatchFace(
                assetId = "7185695173", version = "2.1.1", bytes = face, metaJson = metaJson(),
                // Every other budget left long on purpose: only the room budget can end this in
                // time, so a regression that leans on the silence timer again shows up as elapsed.
                timeoutMs = 15_000, silenceMs = 12_000, engageMs = 12_000, roomMs = 300,
            )
        }
        val elapsed = System.currentTimeMillis() - started

        assertNotNull("installWatchFace never returned", outcome)
        assertFalse(outcome!!.ok)
        assertTrue(
            "a stalled offer must be given up on its own budget, not the silence one: ${elapsed}ms",
            elapsed < 5_000,
        )
        assertTrue(
            "the offer must have gone out — this is a band that ASKED: ${band.sawFromClient}",
            band.sawFromClient.contains("0x28/0x02"),
        )
        assertTrue(
            "nothing moved, so the window must be told it can ask for room: ${outcome.message}",
            outcome.needsRoom,
        )
        assertNotNull(
            "the room question needs the band's own list to offer, or it asks about nothing",
            outcome.store,
        )
        assertEquals(
            "and the list must be the band's real one: ${outcome.store?.faces}",
            listOf("7186018013", "7184229813"),
            outcome.store?.faces?.map { it.assetId },
        )
    }

    @Test
    fun `a band that never asks for the face says so, and does not offer it anyway`() = runBlocking {
        val face = ByteArray(4_000) { it.toByte() }
        val band = BandThatGoesQuiet(answerUntilAsked = false)

        val outcome = withTimeoutOrNull(20_000) {
            HuaweiUploadClient(HuaweiSession(band)).installWatchFace(
                assetId = "7185695173", version = "2.1.1", bytes = face, metaJson = metaJson(),
                timeoutMs = 15_000, silenceMs = 5_000, engageMs = 300,
            )
        }

        assertNotNull("installWatchFace never returned", outcome)
        assertFalse(outcome!!.ok)
        assertTrue(
            "a band that declined must be reported as declining: ${outcome.message}",
            outcome.message.contains("never asked"),
        )
        assertFalse(
            "the file must never be offered to a band that did not ask: ${band.sawFromClient}",
            band.sawFromClient.contains("0x28/0x02"),
        )
    }

    @Test
    fun `a link that closes under the transfer is reported as a hang-up, not a timeout`() = runBlocking {
        val face = ByteArray(4_000) { it.toByte() }
        val band = BandThatHangsUp()

        val started = System.currentTimeMillis()
        val outcome = withTimeoutOrNull(20_000) {
            HuaweiUploadClient(HuaweiSession(band)).installWatchFace(
                assetId = "7185695173", version = "2.1.1", bytes = face, metaJson = metaJson(),
                // Both other budgets left long: only noticing the dead link can end this in time.
                timeoutMs = 15_000, silenceMs = 12_000, engageMs = 12_000,
            )
        }
        val elapsed = System.currentTimeMillis() - started

        assertNotNull("installWatchFace never returned", outcome)
        assertFalse(outcome!!.ok)
        assertTrue(
            "a closed link must end the pump at once, not be polled to the deadline: ${elapsed}ms",
            elapsed < 5_000,
        )
        assertTrue(
            "the report must name the closed link: ${outcome.message}",
            outcome.message.contains("link to the band closed"),
        )
    }
}
