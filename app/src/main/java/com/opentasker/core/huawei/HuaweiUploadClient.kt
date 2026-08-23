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
    data class Outcome(val ok: Boolean, val bytesSent: Int, val blocks: Int, val message: String)

    /** One face the band is holding. [builtIn] faces shipped with it and cannot be replaced. */
    data class InstalledFace(val assetId: String, val version: String, val builtIn: Boolean)

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
    suspend fun listWatchFaces(): FaceStore? {
        val cfg = HuaweiCommands
        val reply = runCatching {
            session.decrypt(session.request(cfg.SVC_WATCHFACE, cfg.WF_LIST, cfg.watchFaceList()))
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
                builtIn = f(5)?.firstOrNull()?.toInt() == 4,
            )
        }
        return FaceStore(faces, free)
    }

    /**
     * Remove a face from the band.
     *
     * Refuses the built-in faces rather than asking the band to do something it will not do — and,
     * more to the point, rather than letting a prune walk off the end of 白い熊's own faces into the
     * ones that came with the watch.
     */
    suspend fun deleteWatchFace(assetId: String, version: String): Boolean {
        val cfg = HuaweiCommands
        val before = listWatchFaces()
        if (before?.faces?.any { it.assetId == assetId && it.builtIn } == true) return false
        session.send(cfg.SVC_WATCHFACE, cfg.WF_CONTROL, cfg.watchFaceDelete(assetId, version))
        // Confirm against the band's own list. The delete is not acknowledged in a way worth
        // trusting, and "it is gone" is the only claim worth making.
        val after = listWatchFaces() ?: return false
        return after.faces.none { it.assetId == assetId }
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
        onProgress: (Int) -> Unit = {},
    ): Outcome {
        val cfg = HuaweiCommands
        val name = "${assetId}_$version"

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
                                return Outcome(false, sent, blocks, "the band refused $name (error $result) · $heard")
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
                return Outcome(false, maxOf(sent, 0), blocks, "the link to the band closed · $heard")
            }

            // Only offer the file once the band has asked for it.
            if (asked && sent == 0) {
                asked = false
                session.send(
                    cfg.SVC_FILE_UPLOAD, cfg.UPLOAD_REQUEST,
                    cfg.uploadRequest(name, bytes.size, WATCH_FACE_FILE_ID, assetId, version),
                )
                sent = -1                       // "offered"; the first block sets a real count
            }

            if (sent == 0 && !asked && System.currentTimeMillis() > engageBy) {
                return Outcome(
                    false, 0, 0,
                    "the band never asked for $name — it may already have this face installed · $heard",
                )
            }

            if (installed) {
                // Nothing more is sent. The install IS the activation — the band puts the face on
                // screen itself — and the command that used to follow this line (tag 3 = 02) deletes
                // a face rather than selecting one, so it threw away the file we had just sent.
                return Outcome(true, maxOf(sent, 0), blocks, "$name installed · $heard")
            }

            // The band asking for a block and then saying nothing is the case the engage timeout
            // above cannot catch — by then it HAS engaged, so that check is switched off for good.
            // That is how a run that had already failed still held the task list for four minutes.
            val silent = System.currentTimeMillis() - lastHeard
            if (silent > silenceMs) {
                return Outcome(
                    false, maxOf(sent, 0), blocks,
                    "the band stopped answering ${silent / 1000}s ago, part way through $name · $heard",
                )
            }
        }
        return Outcome(false, maxOf(sent, 0), blocks, "timed out · $heard")
    }

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
        private const val SILENCE_TIMEOUT_MS = 45_000L

        /** How long to wait for the band to ask for the file before concluding it will not. */
        private const val ENGAGE_TIMEOUT_MS = 25_000L
    }
}
