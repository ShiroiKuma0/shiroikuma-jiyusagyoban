package com.opentasker.core.huawei

/**
 * The Huawei LPv2 wire protocol — framing, VarInt/TLV, CRC16.
 *
 * Deliberately Android-free so it can be JVM-tested, exactly like [com.opentasker.core.band
 * .BandProtocol]. Every rule here was verified against 白い熊's Band 11 Pro (`A4:AA:FE:34:29:0F`,
 * firmware 6.0.0.125) and cross-checked byte-for-byte against the MIT-licensed
 * `zyv/huawei-lpv2`. Nothing is derived from Gadgetbridge, which is AGPLv3 — reading it to
 * understand the protocol shape is fine, copying it is not, and this file copies nothing.
 *
 * The band is NOT the Hume band. Where Hume takes no pairing and sends plaintext with an additive
 * checksum, this one requires a real Bluetooth pairing, a challenge/response handshake, and
 * AES-GCM on every frame after it.
 *
 * Transport is **Bluetooth Classic RFCOMM channel 16** (SDP "Private COM",
 * `82ff3820-8411-400c-b85a-55bdb32cf060`), not BLE — despite Gadgetbridge classifying this model
 * as an LE device.
 */
object HuaweiProtocol {

    /** Every frame starts with this. */
    const val MAGIC: Byte = 0x5A

    /** Result tag carried by a reply; its presence means "this is an answer, not a request". */
    const val TAG_RESULT = 0x7F

    /** Crypto envelope tags, used once the session key is established. */
    const val TAG_ENCRYPTION = 124
    const val TAG_IV = 125
    const val TAG_CIPHERTEXT = 126

    /** The band's success code. Anything else is a failure or a refusal. */
    const val RESULT_SUCCESS = 100_000

    /**
     * Encode a VarInt length: big-endian, 7 bits per byte, high bit set on all but the last.
     */
    fun varIntEncode(value: Int): ByteArray {
        require(value >= 0) { "VarInt must be non-negative, got $value" }
        if (value < 0x80) return byteArrayOf(value.toByte())
        val out = ArrayList<Byte>(5)
        var v = value
        while (v != 0) {
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        out[0] = (out[0].toInt() and 0x7F).toByte()
        return out.reversed().toByteArray()
    }

    /** Decoded VarInt plus how many bytes it consumed. */
    data class VarInt(val value: Int, val size: Int)

    fun varIntDecode(data: ByteArray, offset: Int = 0): VarInt {
        var value = 0
        var i = offset
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F)
            i++
            if (b and 0x80 == 0) return VarInt(value, i - offset)
        }
        throw IllegalArgumentException("truncated VarInt at offset $offset")
    }

    /** One tag/value pair. Tags repeat, so a list — never a map — is the honest representation. */
    data class Tlv(val tag: Int, val value: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Tlv && tag == other.tag && value.contentEquals(other.value)

        override fun hashCode(): Int = 31 * tag + value.contentHashCode()
    }

    fun tlv(tag: Int, value: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(tag.toByte()) + varIntEncode(value.size) + value

    fun tlv(tag: Int, value: String): ByteArray = tlv(tag, value.toByteArray(Charsets.UTF_8))

    fun tlv(tag: Int, value: Int, bytes: Int): ByteArray = tlv(tag, intBytes(value, bytes))

    /** Big-endian fixed-width integer, as every numeric field on this band is encoded. */
    fun intBytes(value: Int, bytes: Int): ByteArray =
        ByteArray(bytes) { i -> (value ushr (8 * (bytes - 1 - i))).toByte() }

    fun parseTlvs(payload: ByteArray): List<Tlv> {
        val out = ArrayList<Tlv>()
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF
            val len = varIntDecode(payload, i + 1)
            val start = i + 1 + len.size
            val end = start + len.value
            require(end <= payload.size) { "TLV tag $tag overruns payload" }
            out.add(Tlv(tag, payload.copyOfRange(start, end)))
            i = end
        }
        return out
    }

    /**
     * Build a frame.
     *
     * Layout: `5A | uint16 length | slice | serviceId | commandId | TLVs | CRC16`.
     *
     * The length counts the **slice byte as well as** service, command and payload — i.e.
     * `payload.size + 1`. Getting this off by one produces a frame the band silently ignores: no
     * error, no reply, nothing. That cost a debugging cycle on the real device.
     */
    fun frame(serviceId: Int, commandId: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(serviceId.toByte(), commandId.toByte()) + payload
        val head = byteArrayOf(MAGIC) + intBytes(body.size + 1, 2) + byteArrayOf(0) + body
        return head + intBytes(crc16(head), 2)
    }

    /**
     * Split one logical frame across several wire frames.
     *
     * The byte after the length is a **slice flag**, and everything we send until now has used 0 —
     * "not fragmented" — which works because every command is small. The watch-face announcement is
     * not: its metadata JSON pushes the frame past 1.2 KB, and the band's own LinkParams caps a
     * frame at 1022 bytes.
     *
     * `1` starts a fragmented frame and carries the service and command; `2` continues; `3` ends.
     * A fragment index follows the flag. Only the FIRST fragment carries service/command, which is
     * also why a decoder that ignores fragmentation sees the continuation as a frame on some absurd
     * service — that is how this went unnoticed until a face silently failed to install.
     */
    fun fragments(
        serviceId: Int,
        commandId: Int,
        payload: ByteArray,
        maxFrame: Int = MAX_FRAME,
    ): List<ByteArray> {
        // A frame is magic(1) + length(2) + body + crc(2), so the body budget is maxFrame - 5. The
        // body is flag + index + payload, and the FIRST fragment also carries service and command.
        val bodyBudget = maxFrame - 5
        if (payload.size + 3 <= bodyBudget) return listOf(frame(serviceId, commandId, payload))

        val out = ArrayList<ByteArray>()
        var pos = 0
        var index = 0
        while (pos < payload.size) {
            val first = pos == 0
            val room = bodyBudget - 2 - if (first) 2 else 0
            val n = minOf(room, payload.size - pos)
            val last = pos + n >= payload.size
            val body = byteArrayOf((if (first) 1 else if (last) 3 else 2).toByte(), index.toByte()) +
                (if (first) byteArrayOf(serviceId.toByte(), commandId.toByte()) else ByteArray(0)) +
                payload.copyOfRange(pos, pos + n)
            val head = byteArrayOf(MAGIC) + intBytes(body.size, 2) + body
            out += head + intBytes(crc16(head), 2)
            pos += n
            index++
        }
        return out
    }

    /**
     * The largest frame the band will take, straight from its own LinkParams (`0x03FE`).
     *
     * Not a guess and not a round number: a 960-byte limit split the watch-face announcement into
     * fragments the band did not answer, because it expects the split Health makes.
     */
    const val MAX_FRAME = 1022

    /** A frame taken off the wire. */
    data class Frame(
        val serviceId: Int,
        val commandId: Int,
        val payload: ByteArray,
        val crcOk: Boolean,
    ) {
        val tlvs: List<Tlv> get() = parseTlvs(payload)

        /** First value for [tag], or null. Tags can repeat; this returns the first. */
        fun tag(tag: Int): ByteArray? = tlvs.firstOrNull { it.tag == tag }?.value

        /** The band's result code, or null when the frame carries none. */
        val result: Int?
            get() = tag(TAG_RESULT)?.let { bytesToInt(it) }

        /**
         * True when this frame is the band ACKNOWLEDGING us rather than asking something.
         *
         * Replying to an acknowledgement makes the two sides ping-pong without end — 22 000 frames
         * in six minutes when it happened on the real device. Nothing carrying [TAG_RESULT] is ever
         * answered.
         */
        val isAck: Boolean get() = tlvs.any { it.tag == TAG_RESULT }

        override fun equals(other: Any?): Boolean =
            other is Frame && serviceId == other.serviceId && commandId == other.commandId &&
                payload.contentEquals(other.payload) && crcOk == other.crcOk

        override fun hashCode(): Int {
            var h = serviceId
            h = 31 * h + commandId
            h = 31 * h + payload.contentHashCode()
            return 31 * h + crcOk.hashCode()
        }
    }

    /** Total on-wire size of a frame whose declared length field is [declared]. */
    fun frameSize(declared: Int): Int = 1 + 2 + declared + 2

    /**
     * Decode one complete frame. Caller must have already established that [data] holds all of it —
     * use [Reassembler] for a stream.
     */
    fun unframe(data: ByteArray): Frame {
        require(data.size >= 6) { "frame too short: ${data.size} bytes" }
        require(data[0] == MAGIC) { "bad magic: ${"%02X".format(data[0])}" }
        val declared = bytesToInt(data, 1, 2)
        val total = frameSize(declared)
        require(total <= data.size) { "frame truncated: need $total, have ${data.size}" }
        val crcOk = crc16(data.copyOfRange(0, total - 2)) == bytesToInt(data, total - 2, 2)
        // data[3] is the slice byte; body starts after it.
        val body = data.copyOfRange(4, total - 2)
        return Frame(
            serviceId = body[0].toInt() and 0xFF,
            commandId = body[1].toInt() and 0xFF,
            payload = body.copyOfRange(2, body.size),
            crcOk = crcOk,
        )
    }

    /** CRC16/XMODEM (CCITT, init 0), over everything before the trailing checksum. */
    fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    fun bytesToInt(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var v = 0
        for (i in offset until offset + length) v = (v shl 8) or (data[i].toInt() and 0xFF)
        return v
    }

    fun bytesToInt(data: ByteArray): Int = bytesToInt(data, 0, data.size)

    /**
     * Reassembles frames from an RFCOMM byte stream.
     *
     * RFCOMM is a stream, not a datagram service: one read can carry half a frame or three of them.
     * Anything not starting with [MAGIC] is dropped a byte at a time rather than throwing, so a
     * desynchronised stream resynchronises instead of wedging the session.
     */
    class Reassembler {
        private val buffer = ArrayList<Byte>(4096)

        fun feed(chunk: ByteArray): List<Frame> {
            buffer.addAll(chunk.toList())
            val out = ArrayList<Frame>()
            while (true) {
                while (buffer.isNotEmpty() && buffer[0] != MAGIC) buffer.removeAt(0)
                if (buffer.size < 3) break
                val declared = ((buffer[1].toInt() and 0xFF) shl 8) or (buffer[2].toInt() and 0xFF)
                val total = frameSize(declared)
                if (total !in 6..8192) { buffer.removeAt(0); continue }
                if (buffer.size < total) break
                val raw = ByteArray(total) { buffer[it] }
                repeat(total) { buffer.removeAt(0) }
                runCatching { unframe(raw) }.getOrNull()?.let(out::add)
            }
            return out
        }

        /** Bytes held back awaiting the rest of a frame. Useful when diagnosing a stall. */
        val pending: Int get() = buffer.size

        fun reset() = buffer.clear()
    }
}
