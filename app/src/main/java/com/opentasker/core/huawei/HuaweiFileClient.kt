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

        /**
         * The band served some of the file and stopped.
         *
         * **A value, not an exception, and the bytes come with it.** This used to throw, which meant
         * 390 KB of real data was assembled and then dropped on the floor — the caller was handed a
         * message and nothing else. A partial `sequence_data` is worth having: it is the only way to
         * ask whether the declared size is even honest, and for a container of dated records the
         * part that arrived is readable on its own.
         *
         * It must never be mistaken for a whole file, so it is a distinct type rather than a short
         * [Data] — a caller that has not thought about incompleteness will not compile.
         */
        data class Partial(
            val name: String,
            val type: Int,
            val bytes: ByteArray,
            val declared: Int,
            val received: Int,
            val rounds: Int,
            val negotiated: Negotiated?,
        ) : Result() {
            val missing: Int get() = declared - received
            val summary: String
                get() = "$received of $declared bytes after $rounds round(s)" +
                    (negotiated?.let { " (chunk ${it.chunkSize}, max ${it.maxSize}, mode ${it.mode})" } ?: "")
        }
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
        timeoutMs: Long = OVERALL_TIMEOUT_MS,
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
        val negotiated = runCatching {
            val n = session.decrypt(
                session.request(cfg.SVC_FILE_TRANSFER, cfg.FILE_NEGOTIATE, cfg.fileNegotiate(type)),
            )
            fun t(tag: Int) = n.firstOrNull { it.tag == tag }?.let { HuaweiProtocol.bytesToInt(it.value) } ?: 0
            Negotiated(t(3), t(4), t(5))
        }.getOrNull()

        // 3–4. Ask for it, and keep asking from wherever it stopped.
        //
        // **The band does not send a whole file in answer to one FILE_START.** Two runs twenty
        // minutes apart both stopped at byte 195201 of a declared 695389 — identical to the byte,
        // which a timeout cannot produce: link conditions would move the cutoff. Something on the
        // band's side ends the push at a fixed point, and `fileStart` has carried an `offset`
        // parameter all along that was only ever called with 0.
        //
        // So the transfer is a loop: start at the lowest byte still missing, collect until the band
        // goes quiet, and start again from the new gap. A round that adds nothing is the signal to
        // stop — repeating it would only spend the budget.
        //
        // Two timeouts, doing different jobs. The **idle** one bounds the wait for the next frame,
        // so a stalled band is noticed in seconds rather than at the end. The **overall** one bounds
        // the whole fetch, because a band that keeps sending one byte per round must still end.
        // Before this there was a single whole-transfer deadline of 30 s, which for a 695 KB file at
        // this link's ~6.5 kB/s was a guarantee of truncation rather than a safeguard against one.
        val buffer = ByteArray(size + SLACK)
        val have = BooleanArray(size)
        var end = 0
        // Counted as we go rather than rescanned. `sequence_data` runs to ~700 KB, and re-checking
        // every byte per chunk would be a few hundred million comparisons across a transfer — a
        // quadratic loop hidden inside something that otherwise looks like a simple read.
        var received = 0
        var searchFrom = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        var rounds = 0
        var stalls = 0

        while (received < size && rounds < MAX_ROUNDS && System.currentTimeMillis() < deadline) {
            rounds++
            // The lowest byte still missing. Scanned forward from the last one rather than from
            // zero, so the search cannot become quadratic over a 700 KB file.
            while (searchFrom < size && have[searchFrom]) searchFrom++
            if (searchFrom >= size) break
            session.send(cfg.SVC_FILE_TRANSFER, cfg.FILE_START, cfg.fileStart(type, searchFrom, size, id))

            val before = received
            while (received < size) {
                val budget = minOf(IDLE_TIMEOUT_MS, deadline - System.currentTimeMillis())
                if (budget <= 0) break
                val frame = session.awaitFrame(cfg.SVC_FILE_TRANSFER, cfg.FILE_DATA, budget) ?: break
                val plain = session.decryptBytes(frame) ?: continue
                if (plain.size <= HEADER) continue
                if ((plain[0].toInt() and 0xFF) != type) continue
                val offset = HuaweiProtocol.bytesToInt(plain.copyOfRange(1, 5))
                // The slice is everything after the header. The copy is clamped to the buffer, so a
                // trailer that turns out to be data — or absent — cannot corrupt the result.
                var n = plain.size - HEADER
                if (offset < 0 || offset >= buffer.size) continue
                if (offset + n > buffer.size) n = buffer.size - offset
                if (n <= 0) continue
                System.arraycopy(plain, 5, buffer, offset, n)
                if (offset + n > end) end = offset + n
                // Re-sent bytes must not be counted twice, or a band that repeats a chunk would look
                // complete while a hole remained. Coverage is tracked over the DECLARED region only:
                // bytes past it are kept but cannot make a short transfer look finished.
                for (i in offset until minOf(offset + n, size)) if (!have[i]) { have[i] = true; received++ }
            }
            // Close the window before opening the next. The band serves a FIXED 200 chunks of 976
            // bytes per START — 195200 bytes, plus the one trailing byte it always adds — and then
            // stops. Measured: two runs twenty minutes apart both ended at exactly 195201, and once
            // this loop existed, two productive rounds delivered exactly 390402 = 2 × 195201 while
            // rounds three and four delivered nothing at all. Repeating START without closing the
            // previous window is what those empty rounds look like, and the band's own KDoc note
            // above says a half-open transfer poisons the next request. So each window gets its own
            // DONE, and only the final one is left to the code below.
            if (received < size) {
                runCatching { session.request(cfg.SVC_FILE_TRANSFER, cfg.FILE_DONE, cfg.fileDone(type)) }
            }
            if (received == before) {
                // Asking again from the same place produced nothing. Give the band a moment before
                // re-asking — an instant retry is what makes a slow window look like a dead one.
                if (++stalls >= MAX_STALLS) break
                kotlinx.coroutines.delay(RETRY_PAUSE_MS)
            } else {
                stalls = 0
            }
        }

        // 5. Tell it we are done. Sent even on a short read: the band keeps the transfer open
        //    otherwise, and a half-open transfer poisons the next request for the same file.
        runCatching { session.request(cfg.SVC_FILE_TRANSFER, cfg.FILE_DONE, cfg.fileDone(type)) }

        if (received < size) {
            return Result.Partial(name, type, buffer.copyOf(maxOf(end, 0)), size, received, rounds, negotiated)
        }
        return Result.Data(name, type, buffer.copyOf(maxOf(end, size)))
    }

    companion object {
        /** `type(1) | offset(4)` in front of every slice. */
        private const val HEADER = 5

        /** How long to wait for the NEXT frame before treating the band as stalled. */
        private const val IDLE_TIMEOUT_MS = 8_000L

        /** How long the whole fetch may take, however many rounds it needs. */
        private const val OVERALL_TIMEOUT_MS = 240_000L

        /** A ceiling on restarts, so a band that serves one byte a round still terminates. */
        private const val MAX_ROUNDS = 64

        /**
         * Consecutive rounds that add nothing before giving up.
         *
         * Raised from 2 once the windows turned out to be *unreliable* rather than capped: a run
         * that reached 421634 bytes did it as two full 200-chunk windows plus a third that died
         * after 32 chunks, so a short or empty window is a thing to ride out rather than a verdict.
         */
        private const val MAX_STALLS = 5

        /**
         * A pause before re-asking after a window that gave nothing.
         *
         * The band evidently needs a moment between windows; retrying instantly is what turns one
         * slow window into a "stall" and ends the transfer early.
         */
        private const val RETRY_PAUSE_MS = 1_200L

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

        /**
         * A workout's GPS track. Asked for by NAME with both timestamps zero — unlike sleep and the
         * RR intervals, a track is not addressed by a time range; the workout number in the name is
         * the whole address.
         */
        const val GPS_TYPE = 0x11

        /** The dead-reckoning track, for a workout the satellites never reached. */
        const val PDR_TYPE = 0x12
    }
}

/**
 * The band stopped sending before the file was complete.
 *
 * Deliberately NOT a partial result: a half-received sleep file would decode into a plausible night
 * that never happened, and there is no way for a caller downstream to tell it from a real one.
 */
/**
 * A transfer that ended short.
 *
 * Carries [rounds] and what the band offered at negotiation, because "N of M never arrived" was the
 * whole diagnosis for two days and it does not say which of several very different things happened.
 * One round means the band stopped once and refused to resume; many rounds means it is dribbling.
 */
class HuaweiFileIncompleteException(
    name: String,
    val size: Int,
    val missing: Int,
    val rounds: Int = 0,
    val negotiated: HuaweiFileClient.Negotiated? = null,
) :
    Exception(
        "$name: $missing of $size bytes never arrived after $rounds round(s)" +
            (negotiated?.let { " (band offered chunk ${it.chunkSize}, max ${it.maxSize}, mode ${it.mode})" } ?: ""),
    )
