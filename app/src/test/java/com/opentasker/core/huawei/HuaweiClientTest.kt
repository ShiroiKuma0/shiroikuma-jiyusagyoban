package com.opentasker.core.huawei

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection logic, driven against a fake transport.
 *
 * This is why [HuaweiTransport] is an interface: the rules exercised here are the ones that cost
 * real debugging time on the band, and none of them could be tested at all if the session spoke
 * directly to `android.bluetooth`.
 */
class HuaweiClientTest {

    /** A scripted band: queues replies, records what we sent. */
    /**
     * A scripted band: queues replies, records what we sent.
     *
     * [autoAck] answers every unscripted command with a success result, which is both what a real
     * band does and what keeps this class fast. Without it each unanswered command waits out its
     * full six-second timeout, and `configure()` alone sends fifteen — this file used to account
     * for 156 of the suite's 167 seconds.
     */
    private class FakeTransport(private val autoAck: Boolean = true) : HuaweiTransport {
        val written = ArrayList<HuaweiProtocol.Frame>()
        private val toDeliver = ArrayDeque<ByteArray>()
        private val pending = ArrayDeque<Pair<Int, Int>>()

        fun queue(service: Int, command: Int, payload: ByteArray) {
            toDeliver.add(HuaweiProtocol.frame(service, command, payload))
        }

        override suspend fun write(data: ByteArray) {
            val frame = HuaweiProtocol.unframe(data)
            written += frame
            if (autoAck) pending.add(frame.serviceId to frame.commandId)
        }

        override suspend fun read(timeoutMs: Long): ByteArray? {
            toDeliver.removeFirstOrNull()?.let { return it }
            val (service, command) = pending.removeFirstOrNull() ?: return null
            return HuaweiProtocol.frame(
                service, command,
                HuaweiProtocol.tlv(
                    HuaweiProtocol.TAG_RESULT,
                    HuaweiProtocol.intBytes(HuaweiProtocol.RESULT_SUCCESS, 4),
                ),
            )
        }

        override suspend fun close() = Unit
    }

    private fun sessionWith(fake: FakeTransport) = HuaweiSession(fake)

    @Test
    fun `LinkParams extracts authVersion, support type and nonce`() = runBlocking {
        val fake = FakeTransport()
        // tag 5 = [00, authVersion, 16-byte nonce]; tag 7 = deviceSupportType
        val nonce = ByteArray(16) { it.toByte() }
        fake.queue(
            0x01, 0x01,
            HuaweiProtocol.tlv(1, byteArrayOf(2)) +
                HuaweiProtocol.tlv(5, byteArrayOf(0, 1) + nonce) +
                HuaweiProtocol.tlv(7, byteArrayOf(4)),
        )
        val link = HuaweiClient(sessionWith(fake)).linkParams()
        assertEquals(1, link.authVersion)
        assertEquals(4, link.deviceSupportType)
        assertEquals(HuaweiCrypto.upperHex(nonce), HuaweiCrypto.upperHex(link.serverNonce))
    }

    @Test
    fun `the PIN is decrypted with the universal digest secret`() = runBlocking {
        val fake = FakeTransport()
        val pin = ByteArray(64) { (it * 5).toByte() }
        val iv = ByteArray(16) { it.toByte() }
        val ct = HuaweiCrypto.encryptCbc(pin, HuaweiCrypto.DIGEST_SECRET_V1, iv)
        fake.queue(0x01, 0x2C, HuaweiProtocol.tlv(1, ct) + HuaweiProtocol.tlv(2, iv))
        val got = HuaweiClient(sessionWith(fake)).fetchPin(authVersion = 1)
        assertEquals(HuaweiCrypto.upperHex(pin), HuaweiCrypto.upperHex(got))
    }

    @Test
    fun `configure reproduces the reference provisioning sequence exactly, in order`() = runBlocking {
        // 白い熊, 2026-08-22: "the phone just didn't carry out the exact sequence of actions that we
        // learned to carry out when pairing on the PC side — we need to ensure this is done."
        //
        // So the sequence is pinned rather than described. This is the order and the exact set from
        // the run that put a factory-reset band onto a working watch face; every earlier attempt
        // that deviated failed while every individual command still returned success, which is what
        // makes a prose comment worthless here and a test worth having.
        val fake = FakeTransport()
        HuaweiClient(sessionWith(fake)).configure("HUAWEI Band 11 Pro-90F", 1_787_346_631L, 2, 0)
        val C = HuaweiCommands
        assertEquals(
            listOf(
                C.SVC_DEVICE_CONFIG to C.CMD_PRODUCT_INFO,
                C.SVC_DEVICE_CONFIG to C.CMD_SET_TIME,
                C.SVC_DEVICE_CONFIG to C.CMD_SET_TIME,          // twice, as the reference sends it
                C.SVC_DEVICE_CONFIG to C.CMD_SUPPORTED_SERVICES,
                C.SVC_DEVICE_CONFIG to C.CMD_SUPPORTED_COMMANDS,
                C.SVC_DEVICE_CONFIG to C.CMD_EXPAND_CAPABILITY,
                C.SVC_DEVICE_CONFIG to C.CMD_SETTING_RELATED,
                C.SVC_DEVICE_CONFIG to C.CMD_ACCEPT_AGREEMENTS,
                C.SVC_DEVICE_CONFIG to C.CMD_REVERSE_CAPABILITIES,
                C.SVC_DEVICE_CONFIG to C.CMD_SETUP_DEVICE_STATUS,
                C.SVC_ACCOUNT to C.ACC_UNKNOWN_04,
                C.SVC_ACCOUNT to C.ACC_EXTENDED_ACCOUNT,
                C.SVC_ACCOUNT to C.ACC_COUNTRY_CODE,
                C.SVC_ACCOUNT to C.ACC_SEND_ACCOUNT,
                C.SVC_ACCOUNT to C.ACC_UNKNOWN_04,              // again, closing the account dance
            ),
            fake.written.map { it.serviceId to it.commandId },
        )
    }

    @Test
    fun `the wizard payloads are the captured ones, byte for byte`() = runBlocking {
        // Hand-built versions of these were wrong in ways nothing reported: the agreements blob
        // carried three blocks where two are sent, and ReverseCapabilities was six bytes where the
        // band takes seven.
        val fake = FakeTransport()
        HuaweiClient(sessionWith(fake)).configure("HUAWEI Band 11 Pro-90F", 1L, 2, 0)
        fun hexOf(command: Int) = HuaweiCrypto.upperHex(
            fake.written.first { it.commandId == command }.payload,
        )
        assertEquals(
            HuaweiCrypto.upperHex(HuaweiCrypto.hex("010002000300040005000600")),
            hexOf(HuaweiCommands.CMD_SETTING_RELATED),
        )
        assertEquals(
            HuaweiCrypto.upperHex(HuaweiCrypto.hex("0107FDF773FA29BF3B")),
            hexOf(HuaweiCommands.CMD_REVERSE_CAPABILITIES),
        )
        assertEquals(
            HuaweiCrypto.upperHex(HuaweiCommands.ACCEPT_AGREEMENTS),
            hexOf(HuaweiCommands.CMD_ACCEPT_AGREEMENTS),
        )
        // SetUpDeviceStatus carries the BAND's own name, not the phone's.
        val setup = fake.written.first { it.commandId == HuaweiCommands.CMD_SETUP_DEVICE_STATUS }
        assertEquals("HUAWEI Band 11 Pro-90F", String(setup.tag(2)!!))
    }

    @Test
    fun `configure sends the four commands that close the wizard`() = runBlocking {
        val fake = FakeTransport()
        val session = sessionWith(fake)
        HuaweiClient(session).configure("HUAWEI Band 11 Pro-90F", 1_787_346_631L, 2, 0)
        val sent = fake.written.map { it.serviceId to it.commandId }.toSet()
        // Their absence is invisible — every other command still succeeds — so assert them by name.
        assertTrue("SettingRelated", 0x01 to 0x31 in sent)
        assertTrue("AcceptAgreements", 0x01 to 0x30 in sent)
        assertTrue("ReverseCapabilities", 0x01 to 0x3F in sent)
        assertTrue("SetUpDeviceStatus", 0x01 to 0x3E in sent)
    }

    @Test
    fun `configure does not block on the fire-and-forget commands`() = runBlocking {
        // The band never answers 0x1A/0x0A or 0x01/0x3E. Waiting on either burns the seconds it
        // gives us before abandoning its pairing flow, which is how a whole evening was lost.
        val fake = FakeTransport()
        HuaweiClient(sessionWith(fake)).configure("band", 1L, 0, 0)
        val sent = fake.written.map { it.serviceId to it.commandId }
        assertTrue("country code sent", 0x1A to 0x0A in sent)
        assertTrue("setup device status sent", 0x01 to 0x3E in sent)
    }

    @Test
    fun `serve answers a PhoneInfo request`() = runBlocking {
        val fake = FakeTransport(autoAck = false)
        fake.queue(
            0x01, 0x10,
            HuaweiProtocol.tlv(0x02) + HuaweiProtocol.tlv(0x08) + HuaweiProtocol.tlv(0x11),
        )
        val session = sessionWith(fake)
        val served = HuaweiClient(session).serve(10)
        assertEquals(1, served)
        val reply = fake.written.single()
        assertEquals(0x10, reply.commandId)
        assertEquals("14", String(reply.tag(0x08)!!))
    }

    @Test
    fun `serve never answers an acknowledgement`() = runBlocking {
        // A result tag means the band is answering US. Replying ping-pongs forever — 22 000 frames
        // in six minutes when it happened for real.
        val fake = FakeTransport(autoAck = false)
        fake.queue(
            0x01, 0x10,
            HuaweiProtocol.tlv(
                HuaweiProtocol.TAG_RESULT,
                HuaweiProtocol.intBytes(HuaweiProtocol.RESULT_SUCCESS, 4),
            ),
        )
        val served = HuaweiClient(sessionWith(fake)).serve(10)
        assertEquals(0, served)
        assertTrue(fake.written.isEmpty())
    }

    @Test
    fun `serve never answers WearStatus`() = runBlocking {
        // 0x3D is a notification. Answering makes the band resend at once — 6661 frames in 90s.
        val fake = FakeTransport(autoAck = false)
        fake.queue(0x01, 0x3D, HuaweiProtocol.tlv(1, byteArrayOf(2)))
        val served = HuaweiClient(sessionWith(fake)).serve(10)
        assertEquals(0, served)
        assertTrue(fake.written.isEmpty())
    }

    @Test
    fun `serve grants a permission check`() = runBlocking {
        val fake = FakeTransport(autoAck = false)
        fake.queue(0x01, 0x38, HuaweiProtocol.tlv(1, byteArrayOf(0, 7)))
        val session = sessionWith(fake)
        assertEquals(1, HuaweiClient(session).serve(10))
        val reply = fake.written.single()
        assertEquals(0x38, reply.commandId)
        // status 1 = granted; Gadgetbridge hardcodes 0 with a TODO conceding that is wrong.
        assertEquals(1, HuaweiProtocol.bytesToInt(reply.tag(2)!!))
    }

    @Test
    fun `serve acknowledges the account commands the band initiates`() = runBlocking {
        val fake = FakeTransport(autoAck = false)
        fake.queue(0x1A, 0x03, HuaweiProtocol.tlv(1, byteArrayOf(1)))
        fake.queue(0x1A, 0x06, HuaweiProtocol.tlv(1, byteArrayOf(0)))
        val session = sessionWith(fake)
        val client = HuaweiClient(session)
        val served = client.serve(10) + client.serve(10)
        assertEquals(2, served)
        assertEquals(2, fake.written.size)
        assertTrue(
            fake.written.all {
                HuaweiProtocol.bytesToInt(it.tag(HuaweiProtocol.TAG_RESULT)!!) ==
                    HuaweiProtocol.RESULT_SUCCESS
            },
        )
    }

    @Test
    fun `an unsolicited frame arriving mid-request is not lost`() = runBlocking {
        // The band interrupts with its own questions. A loop that discards anything not matching
        // the outstanding request would starve it — and that is exactly what happened for hours.
        val fake = FakeTransport(autoAck = false)
        fake.queue(0x01, 0x10, HuaweiProtocol.tlv(0x02))          // unsolicited, arrives first
        fake.queue(0x01, 0x08, HuaweiProtocol.tlv(1, byteArrayOf(61)))  // the reply we want
        val session = sessionWith(fake)
        val battery = HuaweiClient(session).battery()
        assertEquals(61, battery)
        // The PhoneInfo request is still queued and servable afterwards.
        assertEquals(1, HuaweiClient(session).serve(10))
    }
}
