package com.opentasker.core.huawei

import com.opentasker.core.huawei.HuaweiProtocol.Tlv

/**
 * The Watch D3's data dictionary — where blood pressure, skin temperature, arterial stiffness and
 * the daily summary live. Decoded from Health's own traffic on 2026-10-17 (docs/huawei-watch-d3.md
 * §3.7); none of it exists on the Band.
 *
 * ## The transport
 *
 * P2P messages on service `0x34`, command `0x01`, between a phone module and a watch module:
 *
 *     tag 1  kind — 01 ping, 02 data, 03 acknowledgement
 *     tag 2  sequence number (u16), echoed by the acknowledgement
 *     tag 3  sender, tag 4 receiver — `hw.unitedevice.datadictionarysync` ↔ `hw.watch.health.filesync`
 *     tag 5  `UniteDeviceManagement`, tag 6 `SystemApp` (swapped on the way back)
 *     tag 7  the body
 *     tag 8  the acknowledgement's code — the watch sends `cf`, Health answers `000000cf`
 *
 * Every data message is acknowledged before anything else happens, in both directions.
 *
 * ## The body
 *
 * A version byte (`01`), then TLVs. A query: tag 1 `01`, tag 2 the class, tag 5 "since" (ms),
 * tag 6 "now" (ms), tag `0x0D` `01`. The answer: tag 1 a u32 (`1`), tag 2 the class, then a `0x83`
 * container of `0x84` records — each with its time twice (tags 5 and `0x0C`, ms) and an `0x87`
 * container of `0x88` fields `{9: field id (u32), 10: IEEE double}`. An empty answer is `83 00`.
 *
 * Health pages by asking again from the newest record it got, until the answer is empty, and so do
 * we. TLV lengths are base-128 varints, which [HuaweiProtocol.parseTlvs] already reads.
 */
object HuaweiDictionary {
    const val SVC = 0x34
    const val CMD = 0x01
    const val PHONE = "hw.unitedevice.datadictionarysync"
    const val WATCH = "hw.watch.health.filesync"
    const val PHONE_APP = "UniteDeviceManagement"
    const val WATCH_APP = "SystemApp"
    const val HEALTH = "com.huawei.health"

    const val BLOOD_PRESSURE = 10002
    const val SKIN_TEMPERATURE = 400012
    const val ARTERIAL_STIFFNESS = 400017
    const val PER_MINUTE = 200003
    const val DAILY_SUMMARY = 300002

    /**
     * Every class Health asked about on 2026-10-17. Most answered empty that evening; asking costs
     * one round trip each, and a class that starts answering later is then already being kept.
     */
    val CLASSES: List<Int> = listOf(BLOOD_PRESSURE, PER_MINUTE, 200005, DAILY_SUMMARY) +
        (400011..400026).toList() + (500001..500010).toList()

    /**
     * Field ids we can name, each checked against Health's or the watch's own screen. Everything
     * else is kept under [rawMetric] — the same rule as `unknown_XX` on the Band: decode later,
     * never drop.
     */
    val NAMED: Map<Pair<Int, Long>, String> = mapOf(
        (BLOOD_PRESSURE to 2006L) to "bp_systolic",           // 129 = the watch's 129/88
        (BLOOD_PRESSURE to 2007L) to "bp_diastolic",
        (BLOOD_PRESSURE to 10002537L) to "bp_pulse",          // 81 = the watch's pulse
        (SKIN_TEMPERATURE to 400012430L) to "skin_temp",      // 32.5 = Health's screen
        (ARTERIAL_STIFFNESS to 400017238L) to "pwv",          // 6.53 m/s = "6.5"
        (ARTERIAL_STIFFNESS to 400017498L) to "pwv_grade",    // 1 = Normal (< 8 m/s)
        (ARTERIAL_STIFFNESS to 400017567L) to "pwv_hr",       // 83 — heart rate, to confirm
    )

    fun rawMetric(cls: Int, field: Long) = "dict_${cls}_$field"

    fun metricFor(cls: Int, field: Long) = NAMED[cls to field] ?: rawMetric(cls, field)

    /** One record: its time and its fields. */
    data class Record(val cls: Int, val epochMs: Long, val fields: Map<Long, Double>)

    // ── encoding ───────────────────────────────────────────────────────────────────────────────

    private fun tlv(tag: Int, v: ByteArray) = HuaweiProtocol.tlv(tag, v)
    private fun tlv(tag: Int, s: String) = HuaweiProtocol.tlv(tag, s)

    /** The class as Health writes it: minimal big-endian, 2 bytes for 10002, 3 for the rest. */
    fun classBytes(cls: Int): ByteArray {
        var n = 1
        while (n < 4 && (cls ushr (8 * n)) != 0) n++
        return HuaweiProtocol.intBytes(cls, n)
    }

    fun queryBody(cls: Int, sinceMs: Long, nowMs: Long): ByteArray =
        byteArrayOf(1) + tlv(1, byteArrayOf(1)) + tlv(2, classBytes(cls)) +
            tlv(5, HuaweiCommands.longBytes(sinceMs)) + tlv(6, HuaweiCommands.longBytes(nowMs)) +
            tlv(0x0D, byteArrayOf(1))

    fun ping(seq: Int): ByteArray =
        tlv(1, byteArrayOf(1)) + tlv(2, HuaweiProtocol.intBytes(seq, 2)) + tlv(3, HEALTH) + tlv(4, WATCH)

    fun data(seq: Int, body: ByteArray): ByteArray =
        tlv(1, byteArrayOf(2)) + tlv(2, HuaweiProtocol.intBytes(seq, 2)) + tlv(3, PHONE) + tlv(4, WATCH) +
            tlv(5, PHONE_APP) + tlv(6, WATCH_APP) + tlv(7, body)

    /** Our acknowledgement of a watch message, in Health's exact form. */
    fun ack(seq: ByteArray, from: String, to: String): ByteArray =
        tlv(1, byteArrayOf(3)) + tlv(2, seq) + tlv(3, from) + tlv(4, to) + tlv(8, byteArrayOf(0, 0, 0, 0xCF.toByte()))

    // ── decoding ───────────────────────────────────────────────────────────────────────────────

    private fun u(b: ByteArray): Long = b.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xFF) }

    /**
     * The records of one answer body (tag 7 of a watch data message), or null when the body is not
     * a dictionary answer at all.
     */
    fun parseAnswer(body: ByteArray): List<Record>? {
        if (body.isEmpty()) return null
        val top = runCatching { HuaweiProtocol.parseTlvs(body.copyOfRange(1, body.size)) }.getOrNull() ?: return null
        val cls = top.firstOrNull { it.tag == 2 }?.let { u(it.value).toInt() } ?: return null
        val container = top.firstOrNull { it.tag == 0x83 } ?: return emptyList()
        val out = ArrayList<Record>()
        for (rec in parse(container.value)) {
            if (rec.tag != 0x84) continue
            val inner = parse(rec.value)
            val ms = inner.firstOrNull { it.tag == 5 }?.let { u(it.value) }
                ?: inner.firstOrNull { it.tag == 0x0C }?.let { u(it.value) } ?: continue
            val fields = LinkedHashMap<Long, Double>()
            inner.filter { it.tag == 0x87 }.forEach { group ->
                parse(group.value).filter { it.tag == 0x88 }.forEach { f ->
                    val kv = parse(f.value)
                    val id = kv.firstOrNull { it.tag == 9 }?.let { u(it.value) }
                    val v = kv.firstOrNull { it.tag == 10 }?.value
                    if (id != null && v != null && v.size == 8) {
                        fields[id] = java.lang.Double.longBitsToDouble(u(v))
                    }
                }
            }
            if (fields.isNotEmpty()) out += Record(cls, ms, fields)
        }
        return out
    }

    private fun parse(b: ByteArray): List<Tlv> = runCatching { HuaweiProtocol.parseTlvs(b) }.getOrDefault(emptyList())

    // ── the exchange ───────────────────────────────────────────────────────────────────────────

    /**
     * Ask the watch for every record of [classes] from [sinceMs] on. Pages per class until the
     * answer is empty, and acknowledges every watch message as Health does.
     *
     * Tolerant per class: a class that never answers costs its timeout and the rest go on.
     */
    /**
     * What a fetch brought, and how much of the watch answered — the sync reports both. [heard] is
     * every `0x34` frame the watch sent meanwhile, short form (`kind:from>to:code`), so a silent
     * watch can be told apart from one that refuses.
     */
    data class Fetch(val records: List<Record>, val asked: Int, val answered: Int, val heard: List<String> = emptyList())

    suspend fun fetch(
        session: HuaweiSession,
        classes: List<Int>,
        sinceMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        answerMs: Long = 4_000,
        maxPages: Int = 40,
    ): Fetch {
        // Health numbers its P2P messages from 0 on every connection; so do we (from 1, as 0 is its first).
        var seq = 1
        fun next() = seq++.also { if (seq > 0xFFF0) seq = 1 }
        val heard = ArrayList<String>()
        runCatching { session.send(SVC, CMD, ping(next())) }
        // The watch acknowledges the ping; drain it so it is not mistaken for an answer below.
        runCatching { awaitAndAck(session, 1_500, heard) }

        val out = ArrayList<Record>()
        var answered = 0
        var asked = 0
        for ((index, cls) in classes.withIndex()) {
            // A device with no dictionary at all would otherwise spend a timeout on every class.
            if (index >= 2 && answered == 0) break
            var since = sinceMs
            asked++
            for (page in 0 until maxPages) {
                session.send(SVC, CMD, data(next(), queryBody(cls, since, nowMs)))
                val records = awaitAndAck(session, answerMs, heard) ?: break
                if (page == 0) answered++
                val mine = records.filter { it.cls == cls }
                if (mine.isEmpty()) break
                out += mine
                val newest = mine.maxOf { it.epochMs }
                if (newest < since) break
                // Health asks again from the newest record PLUS one second (captured: 0x…2B68 → 0x…2F50),
                // so the last record is not served twice.
                since = newest + 1000
            }
        }
        return Fetch(out, asked, answered, heard)
    }

    /**
     * Wait for the watch's next DATA message, acknowledging everything that arrives on the way, and
     * return its records (empty for an `83 00` answer), or null when nothing came.
     */
    private suspend fun awaitAndAck(session: HuaweiSession, timeoutMs: Long, heard: MutableList<String>): List<Record>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val frame = session.awaitService(SVC, deadline - System.currentTimeMillis()) ?: return null
            if (frame.commandId != CMD) continue
            val tl = runCatching { session.decrypt(frame) }.getOrNull()
            if (tl == null) { heard += "undecryptable"; continue }
            fun t(n: Int) = tl.firstOrNull { it.tag == n }?.value
            fun name(n: Int) = t(n)?.toString(Charsets.UTF_8)?.substringAfterLast('.') ?: "-"
            if (heard.size < 24) {
                heard += "${t(1)?.toHex() ?: "?"}:${name(3)}>${name(4)}" + (t(8)?.let { ":${it.toHex()}" } ?: "")
            }
            val kind = t(1)?.firstOrNull()?.toInt() ?: continue
            if (kind != 2) continue                          // the watch's own acknowledgements
            val from = t(3)?.toString(Charsets.UTF_8) ?: WATCH
            val to = t(4)?.toString(Charsets.UTF_8) ?: PHONE
            t(2)?.let { runCatching { session.send(SVC, CMD, ack(it, to, from)) } }
            if (from != WATCH) continue                       // calendar, battery events …
            return parseAnswer(t(7) ?: continue) ?: continue
        }
        return null
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
