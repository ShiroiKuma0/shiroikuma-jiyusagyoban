package com.opentasker.core.huawei

import java.security.MessageDigest

/**
 * Putting a file ONTO the band — the route a watch face takes.
 *
 * Reconstructed from a capture of Huawei Health installing two faces on 白い熊's band, and verified
 * against it: three files reassembled from that capture reproduce, byte for byte, the SHA-256 that
 * Health sent the band before transferring them.
 *
 * ## The band drives
 *
 * Nothing is pushed. Once the offer is accepted the band asks for a digest, states its own
 * parameters, then repeatedly asks for a `[offset, length]` block which we answer. So this is a
 * pump that reacts to whatever arrives, not a loop that writes:
 *
 * ```
 * --> 0x28/0x02  {name, size, fileId, assetId, version}
 * <-- 0x28/0x03  "give me the digest"        -->  0x28/0x03  {SHA-256}
 * <-- 0x28/0x04  {chunk size, block size}    -->  0x28/0x04  ok
 * <-- 0x28/0x05  {fileId, offset, length}    -->  0x28/0x06 × ceil(length / chunk)
 *       … repeats until the file is exhausted …
 * <-- 0x28/0x07  done                        -->  0x28/0x07  ok
 * ```
 *
 * ## Two things that are easy to get wrong
 *
 * **The data frames are RAW and unencrypted.** Every other command in this protocol is TLV inside an
 * AES-GCM envelope; these are `fileId | seq | offset | bytes`. Health encrypts small files and sends
 * large ones in the clear, and a watch face is decidedly large.
 *
 * **The digest is not optional and not ours to invent.** The band verifies it, so a file that is one
 * frame short is rejected outright rather than installed broken — which is the behaviour you want,
 * and the reason [upload] can report honestly whether the band took it.
 */
class HuaweiUploadClient(private val session: HuaweiSession) {

    /** What happened, in the band's own terms. */
    /**
     * What one install attempt did.
     *
     * [needsRoom] is not just another failure: it means the band has no free slot and the install
     * never started, so the remedy is a decision about which face to give up rather than a retry.
     * [store] carries what the band was holding when it said so, because the caller has to show
     * that list to ask the question and a second read would be a second session.
     */
    data class Outcome(
        val ok: Boolean,
        val bytesSent: Int,
        val blocks: Int,
        val message: String,
        val needsRoom: Boolean = false,
        val store: FaceStore? = null,
    )

    /**
     * One face the band is holding. [showing] is the one on screen right now.
     *
     * There is deliberately no "factory" or "protected" field. Tag 5 was read as one on the strength
     * of two records that happened to agree, and it is not: seven of 白い熊's own faces carry the
     * same 04 as Huawei's, so the picker locked them. What tag 5 actually tracks is the current
     * face — it becomes 05 on whichever face was last installed, and moves again when the face is
     * changed on the band by hand. Whether a face can be removed is the band's answer to give, not
     * ours to predict.
     */
    data class InstalledFace(val assetId: String, val version: String, val showing: Boolean)

    /** What the band is holding, and how much room is left. [freeUnits] is the band's own figure. */
    data class FaceStore(val faces: List<InstalledFace>, val freeUnits: Int)

    /**
     * Ask the band which faces it holds.
     *
     * The reply puts each face in a repeated nested record under tag 129: `0x82 <len>` and then
     * tag 3 = the ten-digit asset id, tag 4 = version, tag 5 = a flag which is 04 on the faces that
     * came with the band and 01 on the ones a companion installed. Tag 9 is free space in the band's
     * own units — it falls by one when a face lands and rises again when one is removed, which is
     * how "is there room?" gets answered without guessing.
     */
    suspend fun listWatchFaces(timeoutMs: Long = 6_000): FaceStore? {
        val cfg = HuaweiCommands
        val reply = runCatching {
            session.decrypt(
                session.request(cfg.SVC_WATCHFACE, cfg.WF_LIST, cfg.watchFaceList(), timeoutMs = timeoutMs),
            )
        }.getOrNull() ?: return null
        val free = reply.firstOrNull { it.tag == 9 }?.value?.let { HuaweiProtocol.bytesToInt(it) } ?: -1
        val blob = reply.firstOrNull { it.tag == 129 }?.value ?: return FaceStore(emptyList(), free)

        val faces = mutableListOf<InstalledFace>()
        var i = 0
        while (i + 2 <= blob.size && blob[i] == 0x82.toByte()) {
            val len = blob[i + 1].toInt() and 0xFF
            val body = blob.copyOfRange(i + 2, minOf(i + 2 + len, blob.size))
            i += 2 + len
            val fields = runCatching { HuaweiProtocol.parseTlvs(body) }.getOrNull() ?: break
            fun f(t: Int) = fields.firstOrNull { it.tag == t }?.value
            val id = f(3)?.toString(Charsets.US_ASCII) ?: continue
            faces += InstalledFace(
                assetId = id,
                version = f(4)?.toString(Charsets.US_ASCII) ?: "",
                showing = f(5)?.firstOrNull()?.toInt() == 5,
            )
        }
        return FaceStore(faces, free)
    }

    /**
     * Remove a face from the band.
     *
     * Nothing is refused up front. An earlier version declined faces it believed were built in,
     * using a flag that turned out to mark the face on screen instead — so it locked seven of
     * 白い熊's own faces behind a rule that did not exist. The band knows what it will part with;
     * asking it and reporting the answer is both simpler and true.
     */
    suspend fun deleteWatchFace(assetId: String, version: String): Boolean {
        val cfg = HuaweiCommands
        session.send(cfg.SVC_WATCHFACE, cfg.WF_CONTROL, cfg.watchFaceDelete(assetId, version))
        // Confirm against the band's own list. The delete is not acknowledged in a way worth
        // trusting, and "it is gone" is the only claim worth making.
        val after = listWatchFaces() ?: return false
        return after.faces.none { it.assetId == assetId }
    }

    /**
     * An install that ended with nothing transferred, and what that most likely means.
     *
     * **The band does not say it is full.** Measured on 白い熊's band, 2026-08-28: holding eighteen
     * faces and reporting 85 free by its own figure, it accepted the announcement (`wf/3 r=0 s=1`,
     * "send it") and then never asked for a single byte — twice, identically, for the same face.
     * Its free-space number goes on claiming room long after it has stopped taking faces, so the
     * `freeUnits == 0` test above will not fire and cannot be what raises the question.
     *
     * What actually identifies the condition is this: the band engaged and no byte ever moved. That
     * is 白い熊's report — *"the band is full and a face needs to be removed"* — and removing one is
     * the remedy that works, so this is where the window is asked which face may go.
     *
     * A transfer that had started and then died is NOT this: the link dropping mid-file is its own
     * fault with its own fix, and offering to delete a face for it would be answering the wrong
     * question. Hence the `bytesSent <= 0` guard.
     */
    private suspend fun noTransfer(
        why: String,
        store: FaceStore?,
        bytesSent: Int,
        blocks: Int,
        heard: CharSequence,
        askAgain: Boolean = false,
    ): Outcome {
        // The pre-flight read is on a short leash so it cannot delay a report, which means it can
        // come back empty on a band that was merely slow for a moment. Without a list there is no
        // room question — the window has nothing to put in front of 白い熊 — so the one path that
        // needs one asks a second time.
        //
        // Only that path, and only on a live link. Re-asking a band that has just been established
        // to be silent, or one that hung up, is dead air added to a report of dead air: the first
        // version of this did exactly that, and the stall tests caught it by the clock.
        @Suppress("NAME_SHADOWING")
        val store = store ?: if (askAgain && session.isOpen) listWatchFaces(PREFLIGHT_TIMEOUT_MS) else null
        val shelf = store?.let { " · ${it.faces.size} faces, ${it.freeUnits} free" } ?: ""
        val nothingMoved = bytesSent <= 0
        val hint = if (nothingMoved && store != null) {
            " — it takes no more faces until one is removed"
        } else {
            ""
        }
        return Outcome(
            ok = false,
            bytesSent = bytesSent,
            blocks = blocks,
            message = "$why$hint$shelf · $heard",
            needsRoom = nothingMoved && store != null,
            store = store,
        )
    }

    /**
     * Bring a face the band ALREADY holds to the front.
     *
     * There is no "make active" command in this protocol — that reading cost a face, because the
     * tag it named (`0x27/0x03` tag 3 = 02) deletes rather than selects. What there is instead is
     * the observation that installing IS activating: the second `0x27/0x03` of an install, tag 3 =
     * 01, is what puts the new face on screen, and it carries nothing but the asset id, the version
     * and the screen size. None of that is the file. So the same command sent for a face already on
     * the band should move it to the front without a byte being transferred.
     *
     * **Measured on 白い熊's band, 2026-08-28**: `7186018013` was on the band and not on screen, and
     * this brought it to the front in 3.7 s with zero bytes sent — the band's own list reporting it
     * as showing afterwards. Then the same command put `7184229813` back. What used to cost a
     * minute of transfer, or was simply impossible without one, is two round trips.
     *
     * It is still someone else's firmware, so the answer is verified rather than believed: the
     * band's own list is re-read and the return value is whether the asset now carries the showing
     * flag. A false return means the face is still there and still not on screen — nothing is lost,
     * and the caller can fall back to a full install.
     *
     * Deliberately never tag 3 = 02, at any point, for any reason.
     */
    suspend fun activate(assetId: String, version: String): Boolean {
        val cfg = HuaweiCommands
        val caps = runCatching {
            session.decrypt(session.request(cfg.SVC_WATCHFACE, cfg.WF_CAPABILITY, cfg.watchFaceCapability()))
        }.getOrNull()
        fun cap(t: Int) = caps?.firstOrNull { it.tag == t }?.value?.let { HuaweiProtocol.bytesToInt(it) }
        session.send(
            cfg.SVC_WATCHFACE, cfg.WF_CONTROL,
            cfg.watchFaceInstall(assetId, version, cap(2) ?: 286, cap(3) ?: 482),
        )
        // The band takes a moment to redraw, and asking too early reads the old answer.
        kotlinx.coroutines.delay(ACTIVATE_SETTLE_MS)
        val after = listWatchFaces() ?: return false
        return after.faces.any { it.assetId == assetId && it.showing }
    }

    /**
     * Install a captured watch face: announce it, send it when the band asks, then apply it.
     *
     * The order is not decoration. The band will happily accept a file it never asked for, verify
     * its digest and acknowledge the transfer — and then drop it, because nothing has told it a face
     * exists. That failure reports complete success at every step and leaves the wrist unchanged,
     * which is precisely how it was missed twice.
     */
    suspend fun installWatchFace(
        assetId: String,
        version: String,
        bytes: ByteArray,
        metaJson: String,
        timeoutMs: Long = 240_000,
        silenceMs: Long = SILENCE_TIMEOUT_MS,
        engageMs: Long = ENGAGE_TIMEOUT_MS,
        roomMs: Long = ROOM_TIMEOUT_MS,
        onProgress: (Int) -> Unit = {},
    ): Outcome {
        val cfg = HuaweiCommands
        val name = "${assetId}_$version"
        var shelf = ""

        // Ask what the band is holding BEFORE announcing anything. Two things are settled here for
        // the price of one round trip inside the session we already have:
        //
        //  * A face the band already holds does not need a megabyte sent to it again. Announcing it
        //    anyway ends in the band simply never asking for the file, which this used to report as
        //    "it may already have this face installed" — a guess, where the list is an answer.
        //  * A band with no free slot refuses the announcement, and the refusal is all 白い熊 ever
        //    saw. Catching it here instead means the window can ask WHICH face to give up while
        //    nothing has been sent and the session is still open.
        // On a short leash. A band that is answering replies to this in milliseconds, and the
        // whole point of asking is to save work — so a band that has stopped answering must not be
        // able to spend the install's budget on a question whose answer is only an optimisation.
        val before = listWatchFaces(PREFLIGHT_TIMEOUT_MS)
        if (before != null) {
            val already = before.faces.firstOrNull { it.assetId == assetId }
            if (already != null) {
                val shown = if (already.showing) "already on the wrist" else "already on the band"
                return if (already.showing) {
                    Outcome(true, 0, 0, "$name $shown", store = before)
                } else {
                    // On the band but not on screen: that is the activate job, not the install job,
                    // and it costs one command instead of a minute of transfer.
                    val ok = activate(assetId, version)
                    Outcome(
                        ok, 0, 0,
                        if (ok) "$name was $shown — now showing" else "$name is $shown, but it would not come to the front",
                        store = listWatchFaces() ?: before,
                    )
                }
            }
            // Carried into every message below, not just the refusal. A band that accepts the
            // announcement and then goes quiet looks identical to a band with no room, and the
            // difference is a number it already told us — measured 2026-08-28, when an install
            // stalled after `wf/3 r=0 s=1` and nothing in the report said whether the shelf was
            // the reason.
            shelf = " · ${before.faces.size} faces, ${before.freeUnits} free"
            // Only believed when it says zero, and never relied on: tag 9 is not a slot count.
            // 白い熊's band reports 85 free while holding eighteen faces and refusing the next one,
            // so this catches a band honest enough to admit it and the stall budget catches the
            // rest. Do not re-derive "full" from this number.
            if (before.freeUnits == 0) {
                return Outcome(
                    false, 0, 0,
                    "the band has no room for $name — ${before.faces.size} faces and no free slot",
                    needsRoom = true,
                    store = before,
                )
            }
        }

        // The band's own screen size travels in the announcement, so ask rather than assume.
        val caps = runCatching {
            session.decrypt(session.request(cfg.SVC_WATCHFACE, cfg.WF_CAPABILITY, cfg.watchFaceCapability()))
        }.getOrNull()
        fun cap(t: Int) = caps?.firstOrNull { it.tag == t }?.value?.let { HuaweiProtocol.bytesToInt(it) }
        val width = cap(2) ?: 286
        val height = cap(3) ?: 482

        session.sendLarge(
            cfg.SVC_WATCHFACE, cfg.WF_CONTROL,
            cfg.watchFaceAnnounce(assetId, version, width, height, metaJson),
        )

        var sent = 0
        var offeredAt = 0L
        var blocks = 0
        var asked = false
        // The band either asks for the file within seconds of the announcement or it never will.
        // Waiting the full budget on a band that has already declined leaves 白い熊 staring at a
        // task list greyed out by a run that cannot succeed — the failure mode that matters more
        // than the failed install itself.
        val engageBy = System.currentTimeMillis() + engageMs
        var installSent = false
        var installed = false
        val heard = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        // The band drives, so its silence — not the clock — is the fault worth reporting. Waiting
        // out [timeoutMs] instead cost four minutes of dead air and then said only "timed out",
        // which names the symptom and not one thing about what went wrong.
        var lastHeard = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            val frames = session.poll(POLL_MS)
            if (frames.isNotEmpty()) lastHeard = System.currentTimeMillis()
            for (f in frames) {
                val tl = session.decrypt(f)
                fun t(n: Int) = tl.firstOrNull { it.tag == n }?.value
                val result = t(HuaweiProtocol.TAG_RESULT)?.let { HuaweiProtocol.bytesToInt(it) }

                if (f.serviceId == cfg.SVC_WATCHFACE) {
                    val state = t(4)?.firstOrNull()?.toInt()
                    heard.append("wf/${f.commandId} r=$result s=$state; ")
                    when (f.commandId) {
                        cfg.WF_PROGRESS -> {
                            session.send(
                                cfg.SVC_WATCHFACE, cfg.WF_PROGRESS,
                                cfg.watchFaceProgressAck(assetId, version),
                            )
                            // Transferring the file is not the same as installing it. Once the band
                            // acknowledges the transfer it WAITS to be told to unpack — a second
                            // 0x27/0x03, this time without the metadata but carrying the screen
                            // size. Health sends it right after the first progress report.
                            //
                            // Without it the band sits on a complete, digest-verified file forever
                            // and the whole run looks like a success that produced nothing: the
                            // announcement is accepted, every byte arrives, and the wrist never
                            // changes. That is precisely what it did.
                            if (!installSent && sent >= bytes.size) {
                                installSent = true
                                session.send(
                                    cfg.SVC_WATCHFACE, cfg.WF_CONTROL,
                                    cfg.watchFaceInstall(assetId, version, width, height),
                                )
                            }
                        }
                        // state 1 = "send it"; state 0 with a good result = "installed".
                        cfg.WF_CONTROL -> when {
                            state == 1 -> asked = true
                            result != null && result != 0 ->
                                return noTransfer(
                                    "the band refused $name (error $result)", before, sent, blocks, heard,
                                )
                            else -> installed = true
                        }
                    }
                }

                if (f.serviceId == cfg.SVC_FILE_UPLOAD) {
                    when (f.commandId) {
                        cfg.UPLOAD_HASH -> session.send(
                            cfg.SVC_FILE_UPLOAD, cfg.UPLOAD_HASH,
                            cfg.uploadHash(WATCH_FACE_FILE_ID, sha256(bytes)),
                        )
                        cfg.UPLOAD_PARAMS -> {
                            t(5)?.let { chunk = HuaweiProtocol.bytesToInt(it).coerceIn(64, 4096) }
                            session.send(cfg.SVC_FILE_UPLOAD, cfg.UPLOAD_PARAMS, cfg.uploadAck(WATCH_FACE_FILE_ID))
                        }
                        cfg.UPLOAD_BLOCK -> {
                            val offset = t(2)?.let { HuaweiProtocol.bytesToInt(it) } ?: continue
                            val length = t(3)?.let { HuaweiProtocol.bytesToInt(it) } ?: continue
                            sent = maxOf(sent, sendBlock(WATCH_FACE_FILE_ID, bytes, offset, length, chunk))
                            blocks++
                            onProgress(sent)
                        }
                        cfg.UPLOAD_DONE ->
                            session.send(cfg.SVC_FILE_UPLOAD, cfg.UPLOAD_DONE, cfg.uploadAck(WATCH_FACE_FILE_ID))
                    }
                }
            }

            // A dead link and a quiet band both read as "nothing arrived", and only one of them is
            // worth waiting on. Without this the pump polled a closed socket — which returns
            // instantly — for the rest of its budget, burning a core to report a timeout that was
            // really a hang-up.
            if (!session.isOpen) {
                return Outcome(false, maxOf(sent, 0), blocks, "the link to the band closed$shelf · $heard")
            }

            // Only offer the file once the band has asked for it.
            if (asked && sent == 0) {
                asked = false
                session.send(
                    cfg.SVC_FILE_UPLOAD, cfg.UPLOAD_REQUEST,
                    cfg.uploadRequest(name, bytes.size, WATCH_FACE_FILE_ID, assetId, version),
                )
                sent = -1                       // "offered"; the first block sets a real count
                offeredAt = System.currentTimeMillis()
            }

            // Offered, and not one byte asked for since.
            //
            // This IS the full band, and it is worth catching in its own right rather than letting
            // the silence budget find it forty-five seconds later. A band with room asks for its
            // first block immediately — a whole 921 KB face moves in about a minute across 124 of
            // them — so a band that said "send it" and then wants nothing at all is not slow, it is
            // out of space. 白い熊 met the old behaviour as an install that "just hangs" (2026-08-28):
            // the question it needed to ask was already answerable, eight seconds in.
            if (sent == -1 && offeredAt > 0 && System.currentTimeMillis() - offeredAt > roomMs) {
                return noTransfer(
                    "the band asked for $name and then took nothing", before, 0, 0, heard,
                    askAgain = true,
                )
            }

            if (sent == 0 && !asked && System.currentTimeMillis() > engageBy) {
                return noTransfer(
                    "the band never asked for $name", before, 0, 0, heard,
                )
            }

            if (installed) {
                // Nothing more is sent. The install IS the activation — the band puts the face on
                // screen itself — and the command that used to follow this line (tag 3 = 02) deletes
                // a face rather than selecting one, so it threw away the file we had just sent.
                return Outcome(true, maxOf(sent, 0), blocks, "$name installed$shelf · $heard")
            }

            // The band asking for a block and then saying nothing is the case the engage timeout
            // above cannot catch — by then it HAS engaged, so that check is switched off for good.
            // That is how a run that had already failed still held the task list for four minutes.
            val silent = System.currentTimeMillis() - lastHeard
            if (silent > silenceMs) {
                return noTransfer(
                    "the band stopped answering ${silent / 1000}s ago, part way through $name",
                    before, maxOf(sent, 0), blocks, heard,
                )
            }
        }
        return Outcome(false, maxOf(sent, 0), blocks, "timed out$shelf · $heard")
    }

    /**
     * How long the pre-install question may take before it is abandoned.
     *
     * Short on purpose: knowing what the band holds saves a wasted megabyte and turns "the band
     * refused it" into "which face should go?", but neither is worth delaying the report that a
     * band has gone silent. Without a bound this took the session's default six seconds against a
     * band that answers nothing, and the stall tests measured it.
     */
    private val PREFLIGHT_TIMEOUT_MS = 2_500L

    /**
     * How long a band may sit on an accepted file before it is treated as having no room.
     *
     * Generous against how fast a working transfer starts (immediately) and short against how long
     * 白い熊 will watch a spinner with nothing happening.
     */


    /** How long the band is given to redraw before its list is asked who is on screen. */
    private val ACTIVATE_SETTLE_MS = 1_200L

    private var chunk = DEFAULT_CHUNK

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    /** Answer one `[offset, length]` request, in as many frames as the chunk size requires. */
    private suspend fun sendBlock(
        id: Int,
        bytes: ByteArray,
        offset: Int,
        length: Int,
        chunk: Int,
    ): Int {
        var pos = offset
        val end = minOf(offset + length, bytes.size)
        var seq = 0
        while (pos < end) {
            val n = minOf(chunk, end - pos)
            session.send(
                HuaweiCommands.SVC_FILE_UPLOAD, HuaweiCommands.UPLOAD_DATA,
                HuaweiCommands.uploadFrame(id, seq, pos, bytes.copyOfRange(pos, pos + n)),
                encrypted = false,
            )
            pos += n
            seq++
        }
        return pos
    }

    companion object {
        /** The slot Huawei Health uses for a watch face. */
        const val WATCH_FACE_FILE_ID = 1

        /** Only used until the band states its own in `0x28/0x04`; it asked for 935. */
        private const val DEFAULT_CHUNK = 935

        /** How long each poll waits on the band, and so how promptly a silence is noticed. */
        private const val POLL_MS = 3_000L

        /**
         * How long the band may say NOTHING AT ALL before we conclude it has stopped.
         *
         * Generous because the band goes quiet legitimately at the end: unpacking a megabyte of
         * watch face on its own CPU is not instant, and the progress messages that punctuate it are
         * the band's, not ours to schedule. Finite because the alternative — waiting out the whole
         * install budget — is four minutes in which nothing is happening, nothing is reported, and
         * the Tasks list is greyed out.
         */
        /**
         * How long a band may sit on an accepted file before it is treated as having no room.
         *
         * Generous against how fast a working transfer starts — a band with space asks for its
         * first block at once, and a whole 921 KB face moves in about a minute across 124 of them —
         * and short against how long 白い熊 will watch a spinner do nothing. Wrong in the safe
         * direction either way: being asked which face to give up can be cancelled, and nothing on
         * the band is touched until it is answered.
         */
        const val ROOM_TIMEOUT_MS = 8_000L

        private const val SILENCE_TIMEOUT_MS = 45_000L

        /** How long to wait for the band to ask for the file before concluding it will not. */
        private const val ENGAGE_TIMEOUT_MS = 25_000L
    }
}
