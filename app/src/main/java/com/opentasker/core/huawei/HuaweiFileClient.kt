package com.opentasker.core.huawei

/**
 * Pulling FILES off the band — the route to sleep and to the per-beat RR intervals.
 *
 * The fitness service (`0x07`) hands out fixed-shape records one index at a time, and that is what
 * [HuaweiSyncEngine] walks. Two things never appear there:
 *
 *  * **`sequence_data`** (type `0x16`) — where sleep lives.
 *  * **`rrisqi_data.bin`** (type `0x10`) — the per-beat RR intervals, and the reason this band was
 *    worth buying: the Hume band reports a device-state index it calls HRV and never sent real
 *    beat-to-beat data at all.
 *
 * Both are fetched by NAME over service `0x2C`, reconstructed from a capture of Huawei Health
 * pairing 白い熊's band. The exchange:
 *
 * ```
 * --> 0x2C/0x01  {1: name, 2: type, 5: from, 6: to, [12: id]}
 * <-- 0x2C/0x01  {1: name, 2: type, 3: type, 4: SIZE, 127: result}
 * --> 0x2C/0x03  {1: type, 2: '', 3: '', 5: ''}          "what chunk size?"
 * <-- 0x2C/0x03  {1: type, 2: ?, 3: chunk, 4: max, 5: mode}
 * --> 0x2C/0x04  {1: type, 2: offset, 3: size, [4: id]}  "send it"
 * <-- 0x2C/0x05  type | offset | data …                  pushed, unprompted, until complete
 * --> 0x2C/0x06  {1: type, 2: 01}                        "got it all"
 * <-- 0x2C/0x06  {127: ok, 1: type}
 * ```
 *
 * Android-free, like the rest of this package, so the whole flow runs against a fake transport.
 */
class HuaweiFileClient(private val session: HuaweiSession) {

    /** A file the band holds, or the reason it holds none. */
    sealed class Result {
        data class Data(val name: String, val type: Int, val bytes: ByteArray) : Result()

        /**
         * The band answered that it has nothing for this window. **Not a failure**: an empty night
         * and a broken request must never look alike, so this is a value rather than an exception.
         */
        data class Empty(val name: String, val type: Int, val resultCode: Int) : Result()
    }

    /**
     * What the band proposed for chunking. Recorded rather than obeyed: we do not slice anything —
     * the band pushes — so this exists to be logged and compared across firmware versions.
     */
    data class Negotiated(val chunkSize: Int, val maxSize: Int, val mode: Int)

    /**
     * Fetch one file.
     *
     * [id] selects which stream inside a container file; `sequence_data` needs one and
     * `rrisqi_data.bin` does not. Pass null when the file takes none.
     *
     * @return [Result.Data] with the assembled bytes, or [Result.Empty] when the band holds nothing.
     */
    suspend fun fetch(
        name: String,
        type: Int,
        fromSeconds: Long,
        toSeconds: Long,
        id: Int? = null,
        timeoutMs: Long = 30_000,
    ): Result {
        val cfg = HuaweiCommands

        // 1. How much is there? A refusal here is the normal "nothing recorded" answer.
        val head = session.request(
            cfg.SVC_FILE_TRANSFER, cfg.FILE_REQUEST,
            cfg.fileRequest(name, type, fromSeconds, toSeconds, id),
        )
        val headTlvs = session.decrypt(head)
        val code = headTlvs.firstOrNull { it.tag == HuaweiProtocol.TAG_RESULT }
            ?.let { HuaweiProtocol.bytesToInt(it.value) }
        val size = headTlvs.firstOrNull { it.tag == 4 }?.let { HuaweiProtocol.bytesToInt(it.value) } ?: 0
        if (code != HuaweiProtocol.RESULT_SUCCESS || size <= 0) {
            return Result.Empty(name, type, code ?: 0)
        }

        // 2. Ask the band for its chunk size. We never slice anything ourselves — it pushes — so
        //    this is protocol etiquette Health performs and the band appears to expect.
        runCatching {
            val n = session.decrypt(
                session.request(cfg.SVC_FILE_TRANSFER, cfg.FILE_NEGOTIATE, cfg.fileNegotiate(type)),
            )
            fun t(tag: Int) = n.firstOrNull { it.tag == tag }?.let { HuaweiProtocol.bytesToInt(it.value) } ?: 0
            Negotiated(t(3), t(4), t(5))
        }

        // 3. "Send it", from the beginning.
        session.send(cfg.SVC_FILE_TRANSFER, cfg.FILE_START, cfg.fileStart(type, 0, size, id))

        // 4. Collect what it pushes.
        // The declared size is a LOWER BOUND, not the length. Both captured transfers carry
        // `size + 1` bytes — 643 for a declared 642, and 693 landing at offset 6832 of a declared
        // 7524 — so there is no trailing byte to discard; the band simply sends one more than it
        // announces. Clamping to the declared size cost the last sleep segment its high byte, which
        // happened to be zero and so did no visible harm. Relying on that is not a plan.
        //
        // So: allocate slack, keep whatever arrives, and return exactly what was received. The
        // declared size still decides when the transfer is COMPLETE — it is a reliable minimum —
        // but it no longer decides how much of the band's answer we are willing to keep.
        val buffer = ByteArray(size + SLACK)
        val have = BooleanArray(size)
        var end = 0
        // Counted as we go rather than rescanned. `sequence_data` runs to ~700 KB, and re-checking
        // every byte per chunk would be a few hundred million comparisons across a transfer — a
        // quadratic loop hidden inside something that otherwise looks like a simple read.
        var received = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (received < size) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val frame = session.awaitFrame(cfg.SVC_FILE_TRANSFER, cfg.FILE_DATA, remaining) ?: break
            val plain = session.decryptBytes(frame) ?: continue
            if (plain.size <= HEADER) continue
            if ((plain[0].toInt() and 0xFF) != type) continue
            val offset = HuaweiProtocol.bytesToInt(plain.copyOfRange(1, 5))
            // The slice is everything after the header, MINUS a single trailing byte whose meaning
            // is unknown. Rather than trust that constant, the copy is clamped to the size the band
            // declared: both captured transfers then land on their exact byte count, and a trailer
            // that turns out to be data (or absent) cannot corrupt the result either way.
            var n = plain.size - HEADER
            if (offset < 0 || offset >= buffer.size) continue
            if (offset + n > buffer.size) n = buffer.size - offset
            if (n <= 0) continue
            System.arraycopy(plain, 5, buffer, offset, n)
            if (offset + n > end) end = offset + n
            // Re-sent bytes must not be counted twice, or a band that repeats a chunk would look
            // complete while a hole remained.
            // Coverage is tracked over the DECLARED region only — that is what completeness
            // means here. Bytes past it are kept but cannot make a short transfer look finished.
            for (i in offset until minOf(offset + n, size)) if (!have[i]) { have[i] = true; received++ }
        }

        // 5. Tell it we are done. Sent even on a short read: the band keeps the transfer open
        //    otherwise, and a half-open transfer poisons the next request for the same file.
        runCatching { session.request(cfg.SVC_FILE_TRANSFER, cfg.FILE_DONE, cfg.fileDone(type)) }

        if (received < size) throw HuaweiFileIncompleteException(name, size, size - received)
        return Result.Data(name, type, buffer.copyOf(maxOf(end, size)))
    }

    companion object {
        /** `type(1) | offset(4)` in front of every slice. */
        private const val HEADER = 5

        /**
         * How much further than the declared size we are willing to accept.
         *
         * Small on purpose: it exists to catch a band that sends a little more than it announces
         * (this one sends exactly one byte more), not to let a runaway transfer allocate freely.
         */
        private const val SLACK = 8

        /** Where sleep lives. A container: [fetch] needs an id to say which stream. */
        const val SEQUENCE_DATA = "sequence_data"
        const val SEQUENCE_TYPE = 0x16

        /**
         * The stream inside `sequence_data` that holds sleep.
         *
         * Established by dumping all three ids Huawei Health was seen asking for and checking their
         * contents against the band's own Sleep screen — 700004 and 700021 hold something else.
         */
        const val SLEEP_STREAM_ID = 700_013

        /** Per-beat RR intervals. Takes no id. */
        const val RRI_DATA = "rrisqi_data.bin"
        const val RRI_TYPE = 0x10
    }
}

/**
 * The band stopped sending before the file was complete.
 *
 * Deliberately NOT a partial result: a half-received sleep file would decode into a plausible night
 * that never happened, and there is no way for a caller downstream to tell it from a real one.
 */
class HuaweiFileIncompleteException(name: String, val size: Int, val missing: Int) :
    Exception("$name: $missing of $size bytes never arrived")
