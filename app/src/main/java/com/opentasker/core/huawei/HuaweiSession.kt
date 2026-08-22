package com.opentasker.core.huawei

/**
 * A byte pipe to the band. Implemented for real by the RFCOMM client; implemented by a fake in
 * tests, which is the whole point — it keeps the handshake logic out of the untestable Android layer.
 */
interface HuaweiTransport {
    /** Write raw bytes. */
    suspend fun write(data: ByteArray)

    /** Read whatever has arrived, or null on timeout. */
    suspend fun read(timeoutMs: Long): ByteArray?

    suspend fun close()
}

/** The band answered, but not with success. */
class HuaweiResultException(val service: Int, val command: Int, val result: Int) :
    Exception("service ${"%#04x".format(service)} command ${"%#04x".format(command)} -> $result")

/** The band said nothing in time. For a fire-and-forget command this is expected, not a fault. */
class HuaweiTimeoutException(val service: Int, val command: Int) :
    Exception("no reply to ${"%#04x".format(service)}/${"%#04x".format(command)}")

/**
 * A framed, optionally encrypted request/response session over [HuaweiTransport].
 *
 * Deliberately Android-free so it can be exercised against a fake transport.
 *
 * Two rules here were learned the hard way against the real band and are enforced in code rather
 * than left to callers:
 *
 *  * **Unsolicited frames are queued, never dropped.** The band asks us things mid-session —
 *    PhoneInfo, account commands, DataSync — and a request/response loop that discards anything not
 *    matching the current request will silently starve it.
 *  * **Nothing carrying the result tag is ever treated as a request.** That is the band
 *    acknowledging us; answering it makes both sides ping-pong indefinitely.
 */
class HuaweiSession(private val transport: HuaweiTransport) {

    private val rx = HuaweiProtocol.Reassembler()
    private val queue = ArrayDeque<HuaweiProtocol.Frame>()

    /** Session key from the HiChain auth pass. Null until the handshake completes. */
    var sessionKey: ByteArray? = null

    /**
     * `deviceSupportType` from LinkParams. 4 means a fresh random IV per frame rather than a
     * counter-derived one, and it also selects GCM over CBC for transactions.
     */
    var deviceSupportType: Int = 0

    private var counter: Int = 0

    /** GCM whenever the band reports deviceSupportType 4 — which this model does. */
    private val useGcm: Boolean get() = deviceSupportType == 0x04

    private fun nextIv(): ByteArray =
        if (useGcm) {
            HuaweiCrypto.randomBytes(16)
        } else {
            counter = if (counter >= 0xFFFF_FFFE) 1 else counter + 1
            HuaweiCrypto.randomBytes(12) + HuaweiProtocol.intBytes(counter, 4)
        }

    /** Wrap a payload in the crypto envelope, if a key is established. */
    private fun encrypt(payload: ByteArray): ByteArray {
        val key = sessionKey ?: return payload
        val iv = nextIv()
        val ct = if (useGcm) HuaweiCrypto.encryptGcm(payload, key, iv)
        else HuaweiCrypto.encryptCbc(payload, key, iv)
        return HuaweiProtocol.tlv(HuaweiProtocol.TAG_ENCRYPTION, byteArrayOf(1)) +
            HuaweiProtocol.tlv(HuaweiProtocol.TAG_IV, iv) +
            HuaweiProtocol.tlv(HuaweiProtocol.TAG_CIPHERTEXT, ct)
    }

    /**
     * Decrypt a frame's payload when it carries the crypto envelope. A frame we cannot decrypt is
     * returned as-is rather than discarded — losing it outright would hide a protocol change.
     */
    fun decrypt(frame: HuaweiProtocol.Frame): List<HuaweiProtocol.Tlv> {
        val key = sessionKey ?: return frame.tlvs
        val ct = frame.tag(HuaweiProtocol.TAG_CIPHERTEXT) ?: return frame.tlvs
        val iv = frame.tag(HuaweiProtocol.TAG_IV) ?: return frame.tlvs
        return runCatching {
            val plain = if (useGcm) HuaweiCrypto.decryptGcm(ct, key, iv)
            else HuaweiCrypto.decryptCbc(ct, key, iv)
            HuaweiProtocol.parseTlvs(plain)
        }.getOrDefault(frame.tlvs)
    }

    /** Send without waiting. Correct for the commands the band never answers. */
    suspend fun send(service: Int, command: Int, payload: ByteArray, encrypted: Boolean = true) {
        val body = if (encrypted) encrypt(payload) else payload
        transport.write(HuaweiProtocol.frame(service, command, body))
    }

    /**
     * Send and wait for the matching reply, queueing anything else that arrives meanwhile.
     *
     * @throws HuaweiTimeoutException if nothing matching arrives — expected for the fire-and-forget
     *   commands listed in [HuaweiCommands.FIRE_AND_FORGET].
     */
    suspend fun request(
        service: Int,
        command: Int,
        payload: ByteArray,
        encrypted: Boolean = true,
        timeoutMs: Long = 6_000,
    ): HuaweiProtocol.Frame {
        send(service, command, payload, encrypted)
        return await(service, command, timeoutMs)
            ?: throw HuaweiTimeoutException(service, command)
    }

    /** Like [request], but returns the result code and throws when it is not success. */
    suspend fun requireOk(
        service: Int,
        command: Int,
        payload: ByteArray,
        encrypted: Boolean = true,
        timeoutMs: Long = 6_000,
    ): HuaweiProtocol.Frame {
        val f = request(service, command, payload, encrypted, timeoutMs)
        val tlvs = decrypt(f)
        val result = tlvs.firstOrNull { it.tag == HuaweiProtocol.TAG_RESULT }
            ?.let { HuaweiProtocol.bytesToInt(it.value) }
        if (result != null && result != HuaweiProtocol.RESULT_SUCCESS) {
            throw HuaweiResultException(service, command, result)
        }
        return f
    }

    private suspend fun await(service: Int, command: Int, timeoutMs: Long): HuaweiProtocol.Frame? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            queue.indexOfFirst { it.serviceId == service && it.commandId == command }
                .takeIf { it >= 0 }
                ?.let { return queue.removeAt(it) }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val chunk = transport.read(remaining)
            if (chunk == null) {
                // A read that returns nothing is normally a real timeout, so this loop paces
                // itself. It does NOT on a closed or already-drained stream, where the read comes
                // back instantly and the bare `continue` burns a core until the deadline. Yielding
                // costs nothing on the slow path and removes the hot spin on the fast one.
                kotlinx.coroutines.delay(20)
                continue
            }
            queue.addAll(rx.feed(chunk))
        }
    }

    /**
     * Anything the band sent that we did not ask for: its own requests, and notifications.
     * Callers drain this to serve the band while a session is open.
     */
    suspend fun poll(timeoutMs: Long): List<HuaweiProtocol.Frame> {
        val out = ArrayList<HuaweiProtocol.Frame>()
        if (queue.isNotEmpty()) { out.addAll(queue); queue.clear() }
        transport.read(timeoutMs)?.let { out.addAll(rx.feed(it)) }
        return out
    }

    suspend fun close() = transport.close()
}
