package com.opentasker.core.huawei

import java.security.MessageDigest

/**
 * The watch's satellite asks, answered the way Health answers them.
 *
 * The watch asks on its own, as soon as a companion is connected and whenever its data has gone
 * stale: `0x1F/0x01`, then names what it wants in its answer to `0x1F/0x02`. Health answers every
 * ask on every connection, and that is why the walk of 2026-10-18 had a fix in its first second —
 * Health had served fresh broadcast ephemeris at 10:16. Our sync never answered, so only the
 * 衛星 tasks could feed the watch. Captured from Health (2026-10-17 19:29 and 2026-10-18 10:39),
 * one ask per kind, in this order:
 *
 *     higeo/v1/gnssinfo?type=0x0004/HW_AGNSS                                → the RTCM file
 *     com.huawei.higeo_dataModule_PGNSSConfig?type=HW_EEV2/HW_PGNSS_PRED;   → the six predicted files
 *     higeo/v1/gnssIonInfo/<lat>,<lon>/HW_PRECSION_ION                      → declined, ready = 04
 *
 * Health lists only the files of the kind asked for, and declines what it has none of with ready
 * `04` instead of `03`. So do we.
 */
object HuaweiGnssServer {

    /** What one ask wants, read from the end of its source string. */
    enum class Want { BROADCAST, PREDICTED, OTHER }

    fun wantOf(source: String?): Want = when {
        source == null -> Want.OTHER
        source.endsWith("/HW_AGNSS") -> Want.BROADCAST
        source.contains("HW_PGNSS_PRED") -> Want.PREDICTED
        else -> Want.OTHER
    }

    /** `0x1F/0x03` tag 1: 03 "it is here", 04 "nothing for this" (Health's answer to the ionosphere ask). */
    private const val READY_SERVE: Byte = 3
    private const val READY_NONE: Byte = 4

    /**
     * The 64 hex characters ahead of the file name in every `0x1C/0x04` answer: the SHA-256 of the
     * device's Bluetooth address, upper-case hex without colons. Checked against both captures —
     * the Band's constant and the D3's are each the hash of their own address. Until 2026-10-18 the
     * Band's was sent to every device.
     */
    fun token(address: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(address.replace(":", "").uppercase().toByteArray())
            .joinToString("") { "%02X".format(it) }

    /**
     * One `0x1C` conversation: the watch drives it — list, params, pick, blocks, done — and this
     * answers each frame. Shared by [HuaweiSyncRunner.serveGnss] and [answer].
     */
    class Transfer(private val files: Map<String, ByteArray>, private val token: String) {
        val served = LinkedHashSet<String>()
        var bytes = 0
            private set
        /** The watch restates both in `0x1C/0x02`; these are the captured defaults. */
        private var unit = 862
        var current: String? = null
            private set

        /** What [handle] did with one frame. */
        enum class Step { CONTINUE, FILE_DONE, UNKNOWN_FILE, UNHANDLED }

        suspend fun handle(
            session: HuaweiSession,
            f: HuaweiProtocol.Frame,
            onProgress: (phase: String, line: String?) -> Unit = { _, _ -> },
        ): Step {
            val tlvs = runCatching { session.decrypt(f) }.getOrElse { emptyList() }
            fun str(tag: Int) = tlvs.firstOrNull { it.tag == tag }?.value?.toString(Charsets.UTF_8)
            fun num(tag: Int) = tlvs.firstOrNull { it.tag == tag }?.let { HuaweiProtocol.bytesToInt(it.value) }
            val svc = HuaweiCommands.SVC_GNSS_FILES
            when (f.commandId) {
                HuaweiCommands.GNSS_LIST -> {
                    onProgress("Transferring", "Offering: " + files.keys.joinToString(", "))
                    // NOTE: no result tag on this one — the capture's answer is tag 1 alone.
                    session.send(svc, HuaweiCommands.GNSS_LIST, HuaweiProtocol.tlv(1, files.keys.joinToString(";")))
                }
                HuaweiCommands.GNSS_PARAMS -> {
                    num(3)?.let { if (it in 1..4096) unit = it }
                    session.send(svc, HuaweiCommands.GNSS_PARAMS, ok())
                }
                HuaweiCommands.GNSS_PICK -> {
                    current = str(1)
                    onProgress("Transferring", "It chose ${current ?: "?"}")
                    val data = files[current]
                    if (data == null) {
                        onProgress("Refused", "It asked for $current, which we do not have")
                        return Step.UNKNOWN_FILE
                    }
                    // Size and CRC, and again no result tag.
                    session.send(
                        svc, HuaweiCommands.GNSS_PICK,
                        HuaweiProtocol.tlv(2, HuaweiProtocol.intBytes(data.size, 4)) +
                            HuaweiProtocol.tlv(3, HuaweiProtocol.intBytes(HuaweiProtocol.crc16(data), 2)),
                    )
                }
                HuaweiCommands.GNSS_BLOCK -> {
                    val name = str(1) ?: current
                    val data = files[name] ?: return Step.UNKNOWN_FILE
                    val offset = num(2) ?: 0
                    val length = (num(3) ?: 0).coerceAtMost(data.size - offset)
                    session.send(
                        svc, HuaweiCommands.GNSS_BLOCK,
                        ok() + HuaweiProtocol.tlv(2, token + (name ?: "")) +
                            HuaweiProtocol.tlv(3, HuaweiProtocol.intBytes(offset, 4)),
                    )
                    // 0x1C/0x05 is NOT TLV: one sequence byte, then raw file bytes. The sequence
                    // restarts at 0 for every block, which is why it never exceeds 7 in the capture.
                    var sent = 0
                    var seq = 0
                    while (sent < length) {
                        val n = minOf(unit, length - sent)
                        session.send(
                            svc, HuaweiCommands.GNSS_DATA,
                            byteArrayOf(seq.toByte()) + data.copyOfRange(offset + sent, offset + sent + n),
                        )
                        sent += n; seq++
                    }
                    bytes += sent
                    onProgress("Transferring", "  sent $sent B of ${current ?: name}  ($bytes B total)")
                }
                HuaweiCommands.GNSS_DONE -> {
                    session.send(svc, HuaweiCommands.GNSS_DONE, ok())
                    val done = current ?: return Step.CONTINUE
                    served.add(done)
                    current = null
                    return Step.FILE_DONE
                }
                else -> return Step.UNHANDLED
            }
            return Step.CONTINUE
        }
    }

    private fun ok() = HuaweiProtocol.tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.RESULT_SUCCESS, 4)

    /** Acknowledge an ask and read what it wants — the source string, or null when it said nothing. */
    suspend fun acceptAsk(session: HuaweiSession): String? {
        runCatching { session.send(HuaweiCommands.SVC_GNSS_ASK, HuaweiCommands.GNSS_NOTIFY, ok()) }
        return runCatching {
            val what = session.request(
                HuaweiCommands.SVC_GNSS_ASK, HuaweiCommands.GNSS_WHAT, HuaweiProtocol.tlv(129),
                timeoutMs = 6_000,
            )
            session.decrypt(what).firstOrNull { it.tag == 129 }?.let { outer ->
                HuaweiProtocol.parseTlvs(outer.value).firstOrNull { it.tag == 6 }
                    ?.value?.toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    suspend fun ready(session: HuaweiSession, serve: Boolean) {
        runCatching {
            session.send(
                HuaweiCommands.SVC_GNSS_ASK, HuaweiCommands.GNSS_READY,
                HuaweiProtocol.tlv(1, byteArrayOf(if (serve) READY_SERVE else READY_NONE)),
            )
        }
    }

    /** One answered ask: what it wanted, and what the watch took. */
    data class Round(val want: Want, val source: String?, val offered: List<String>, val served: List<String>, val bytes: Int, val note: String)

    /**
     * Answer every ask the watch raises while this listens, one round at a time, as Health does.
     *
     * [supply] gives the files for one kind of ask, or null when there are none worth giving — the
     * ask is then declined with ready `04` rather than met with an empty list. The first ask is
     * waited for up to [firstWaitMs] (0 = only one already queued); after each round the watch is
     * given [nextWaitMs] to raise the next, since Health's captures show the rounds back to back.
     */
    suspend fun answer(
        session: HuaweiSession,
        address: String,
        firstWaitMs: Long,
        supply: suspend (Want) -> Map<String, ByteArray>?,
        nextWaitMs: Long = 3_000,
        maxMs: Long = 120_000,
    ): List<Round> {
        val rounds = ArrayList<Round>()
        val start = System.currentTimeMillis()
        val token = token(address)
        var wait = firstWaitMs
        while (System.currentTimeMillis() - start < maxMs) {
            session.awaitFrame(HuaweiCommands.SVC_GNSS_ASK, HuaweiCommands.GNSS_NOTIFY, wait.coerceAtLeast(1)) ?: break
            wait = nextWaitMs
            val source = acceptAsk(session)
            val want = wantOf(source)
            val files = runCatching { supply(want) }.getOrNull()?.takeIf { it.isNotEmpty() }
            if (files == null) {
                ready(session, serve = false)
                rounds += Round(want, source, emptyList(), emptyList(), 0, "declined")
                continue
            }
            ready(session, serve = true)
            val transfer = Transfer(files, token)
            var note = ""
            // The watch drives the transfer; a quiet line for 12 s means it has stopped.
            while (!transfer.served.containsAll(files.keys) && System.currentTimeMillis() - start < maxMs) {
                val f = session.awaitService(HuaweiCommands.SVC_GNSS_FILES, 12_000)
                if (f == null) { note = "the watch went quiet"; break }
                val step = transfer.handle(session, f)
                if (step == Transfer.Step.UNKNOWN_FILE) { note = "asked for ${transfer.current}, not offered"; break }
            }
            rounds += Round(want, source, files.keys.toList(), transfer.served.toList(), transfer.bytes, note)
        }
        return rounds
    }
}
