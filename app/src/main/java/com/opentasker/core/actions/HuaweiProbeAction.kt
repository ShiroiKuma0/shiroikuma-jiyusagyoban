package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.HuaweiCommands
import com.opentasker.core.huawei.HuaweiCrypto
import com.opentasker.core.huawei.HuaweiProtocol
import com.opentasker.core.huawei.HuaweiRecords
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner
import java.io.File

/**
 * `Probe Huawei Band` — ask the band what it actually supports, and write the answer to a file.
 *
 * This exists because the charts can only show what we ask for, and until now we did not know what
 * there was to ask. Heart rate, blood oxygen and resting heart rate all read "nothing recorded" on
 * the first real report — not because the band has no such data, but because the step-record service
 * carries only motion fields and nothing else was ever requested.
 *
 * The band answers all of this itself. Its service census is a byte per service in the order we ask,
 * and its command census is the same one level down — both were captured during provisioning and
 * both were truncated in the logs, so this re-asks and keeps the whole reply.
 *
 * Worth keeping rather than deleting after one use: a firmware update can change any of it, and the
 * next person to wonder where a metric lives should be able to ask rather than infer.
 */
class HuaweiProbeAction : Action {

    private companion object {
        const val SWEEP_ARG = "sweep"
        const val WEATHER_SWEEP_ARG = "weather_sweep"
    }

    override val id = "huawei.probe"
    override val category = ActionCategory.SYSTEM

    /**
     * The fitness commands whose meaning is established. Always probed: both only count.
     *
     * 0x0C is here despite the census not flagging it — it answers anyway, which is why the census
     * is treated as a guide rather than gospel.
     */
    private val fitnessProbes = listOf(
        0x0A to "step count",
        0x0C to "activity-bout count",
    )

    /**
     * Commands whose meaning is unknown. **Opt-in, because these are not reads.**
     *
     * In the captured Huawei Health session every one of these answered with a bare result tag
     * while the queries answered with data — a setter accepting an argument, not a query declining
     * one. 0x0E, 0x16, 0x17, 0x18 and 0x19 all returned 100000 OK to a count-shaped payload from
     * this probe, so something was set on the band and what is unknown.
     */
    private val sweepProbes = listOf(
        0x0E to "SETTER", 0x11 to "?", 0x12 to "?", 0x13 to "?", 0x14 to "?",
        0x15 to "?", 0x16 to "SETTER", 0x17 to "SETTER", 0x18 to "SETTER",
        0x19 to "SETTER", 0x1A to "?", 0x1B to "?",
    )

    /**
     * Fitness commands Huawei Health uses to READ, whose meaning we do not know.
     *
     * Safe without the sweep flag, and that is evidence rather than optimism: in the capture these
     * answered with data where the setters answered with a bare acknowledgement. Sleep is expected
     * to be among them — the band displays last night's sleep so it holds it, and every query
     * already accounted for is steps or activity bouts.
     */
    private val unknownQueries = listOf(0x03, 0x1E, 0x1F, 0x26)

    /** 100000 is success; everything else is the band saying why not. */
    private fun resultOf(tlvs: List<HuaweiProtocol.Tlv>): String {
        val r = tlvs.firstOrNull { it.tag == HuaweiProtocol.TAG_RESULT } ?: return "no result tag"
        val code = HuaweiProtocol.bytesToInt(r.value)
        return "result $code" + if (code == HuaweiProtocol.RESULT_SUCCESS) " (OK)" else ""
    }

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val address = args["address"]?.trim()?.ifEmpty { null } ?: HuaweiSettings.address(ctx.app)
        val out = args["out"]?.trim()?.ifEmpty { null }
            ?: "/sdcard/tmp/huawei-probe_${System.currentTimeMillis()}.txt"
        val report = StringBuilder()
        fun line(s: String = "") = report.append(s).append('\n')

        val now = System.currentTimeMillis() / 1000
        val dayAgo = now - 24 * 3600

        // A census of what is actually STORED, before any radio is touched. Written first and
        // unconditionally: when a chart and a coverage card disagree, the rows are the arbiter, and
        // asking the band about it answers a different question entirely.
        run {
            val db = com.opentasker.app.OpenTaskerApp_NoHilt.db
            val dao = db.huaweiSampleDao()
            line("=== stored samples, last 24 h (hour buckets, local time) ===")
            val metrics = runCatching { dao.metrics() }.getOrDefault(emptyList())
            for (metric in metrics) {
                val times = runCatching { dao.timesFor(metric, dayAgo, now) }.getOrDefault(emptyList())
                val buckets = IntArray(25)
                times.forEach { t ->
                    val h = ((t - dayAgo) / 3600).toInt().coerceIn(0, 24)
                    buckets[h]++
                }
                val strip = buckets.joinToString("") {
                    when {
                        it == 0 -> "."
                        it < 10 -> it.toString()
                        else -> "#"
                    }
                }
                val first = times.firstOrNull()?.let { java.text.SimpleDateFormat("MM-dd HH:mm").format(it * 1000) }
                val last = times.lastOrNull()?.let { java.text.SimpleDateFormat("MM-dd HH:mm").format(it * 1000) }
                // Values, not only counts: a metric can be present in every minute and still draw
                // nothing, because its readings are zeros the chart is told to treat as absent.
                val vals = runCatching { dao.range(metric, dayAgo, now).map { it.value } }
                    .getOrDefault(emptyList())
                val zeros = vals.count { it == 0.0 }
                val stat = if (vals.isEmpty()) "" else
                    " min=%.0f max=%.0f zero=%d".format(vals.min(), vals.max(), zeros)
                line("%-14s %5d  %s  %s .. %s%s".format(
                    metric, times.size, strip, first ?: "-", last ?: "-", stat,
                ))
            }
            line("legend: one character per hour, oldest first — . none, 1-9 that many, # ten or more")
            line()

            line("=== stored sleep ===")
            val sleepDao = db.huaweiSleepDao()
            line("segments ${runCatching { sleepDao.count() }.getOrDefault(-1)}")
            val newest = runCatching { sleepDao.newestSession() }.getOrNull()
            line("newest session start " + (newest?.let {
                java.text.SimpleDateFormat("MM-dd HH:mm").format(it * 1000)
            } ?: "none"))
            newest?.let { st ->
                runCatching { sleepDao.session(st) }.getOrNull()?.forEach {
                    line("  %s  %s .. %s".format(
                        it.stage,
                        java.text.SimpleDateFormat("HH:mm").format(it.startSeconds * 1000),
                        java.text.SimpleDateFormat("HH:mm").format((it.startSeconds + it.durationSeconds) * 1000),
                    ))
                }
            }
            line()

            line("=== recent syncs ===")
            runCatching { db.huaweiSyncDao().recent(8) }.getOrDefault(emptyList()).forEach {
                line("  %s ok=%s %s".format(
                    java.text.SimpleDateFormat("MM-dd HH:mm").format(it.startedAt),
                    it.ok, (it.message ?: "").take(90),
                ))
            }
            line()
        }

        if (args["census_only"]?.trim().equals("true", ignoreCase = true)) {
            runCatching { File(out).writeText(report.toString()) }
            ctx.variables.set("HUAWEI_ProbeFile", out)
            args["store"]?.trim()?.ifEmpty { null }?.let { ctx.variables.set(it, out) }
            return ActionResult.Success
        }

        val result = HuaweiSyncRunner.withSession(ctx.app, address) { session, api ->
            line("=== identity ===")
            runCatching { api.identity() }.onSuccess {
                line("firmware ${it.firmware}   model ${it.model}   serial ${it.serial}")
            }.onFailure { line("identity failed: ${it.message}") }
            line("battery ${runCatching { api.battery() }.getOrNull()}")
            line()

            // The census is a byte per service, in the order we asked. Decoded here rather than
            // dumped, because a 46-byte hex string is not an answer to "where does heart rate live".
            line("=== service census — what the band says it supports ===")
            runCatching {
                val f = session.request(
                    HuaweiCommands.SVC_DEVICE_CONFIG, HuaweiCommands.CMD_SUPPORTED_SERVICES,
                    HuaweiCommands.supportedServices(), timeoutMs = 8_000,
                )
                val answer = session.decrypt(f).firstOrNull { it.tag == 2 }?.value
                    ?: session.decrypt(f).firstOrNull()?.value
                if (answer == null) {
                    line("no census in the reply")
                } else {
                    val asked = HuaweiCommands.SUPPORTED_SERVICES
                    line("asked about ${asked.size}, answered ${answer.size}")
                    val yes = ArrayList<String>()
                    for (i in asked.indices) {
                        val svc = asked[i].toInt() and 0xFF
                        val ok = i < answer.size && answer[i].toInt() != 0
                        if (ok) yes += "0x%02X".format(svc)
                        line("  0x%02X  %s".format(svc, if (ok) "YES" else "no"))
                    }
                    line("supported: ${yes.joinToString(" ")}")
                }
            }.onFailure { line("census failed: ${it.message}") }
            line()

            line("=== command census (raw) ===")
            runCatching {
                val f = session.request(
                    HuaweiCommands.SVC_DEVICE_CONFIG, HuaweiCommands.CMD_SUPPORTED_COMMANDS,
                    HuaweiCommands.supportedCommands(), timeoutMs = 8_000,
                )
                session.decrypt(f).forEach {
                    line("  tag 0x%02X (%d B) %s".format(it.tag, it.value.size, HuaweiCrypto.upperHex(it.value)))
                }
            }.onFailure { line("command census failed: ${it.message}") }
            line()

            // The direct question: which count commands on the fitness service answer at all, and
            // with what. A count is the safe probe — it reads nothing and changes nothing.
            line("=== unknown QUERIES Health reads (0x03, 0x1E, 0x1F, 0x26) ===")
            for (cmd in unknownQueries) {
                runCatching {
                    val f = session.request(
                        HuaweiCommands.SVC_FITNESS, cmd,
                        HuaweiCommands.fitnessCount(dayAgo, now), timeoutMs = 6_000,
                    )
                    val tlvs = session.decrypt(f)
                    val count = HuaweiRecords.parseCount(tlvs)
                    line("  0x07/0x%02X  %s".format(cmd, if (count != null) "count=$count" else resultOf(tlvs)))
                    for (t in tlvs) {
                        line("      tag 0x%02X (%d B) %s".format(
                            t.tag, t.value.size, HuaweiCrypto.upperHex(t.value).take(120)))
                        if (t.tag and 0x80 != 0 && t.value.size > 2) {
                            runCatching {
                                for (n in HuaweiProtocol.parseTlvs(t.value)) {
                                    line("        0x%02X (%d B) %s".format(
                                        n.tag, n.value.size, HuaweiCrypto.upperHex(n.value).take(80)))
                                }
                            }
                        }
                    }
                }.onFailure { line("  0x07/0x%02X  — %s".format(cmd, it.message)) }
            }
            line()

            val sweep = args[SWEEP_ARG]?.trim().equals("true", ignoreCase = true)
            line("=== fitness service 0x07 — count probes over the last 24 h ===")
            if (!sweep) line("  (unknown-command sweep OFF — pass sweep=true to include it)")
            for ((cmd, guess) in fitnessProbes + if (sweep) sweepProbes else emptyList()) {
                val r = runCatching {
                    val f = session.request(
                        HuaweiCommands.SVC_FITNESS, cmd,
                        HuaweiCommands.fitnessCount(dayAgo, now), timeoutMs = 4_000,
                    )
                    val tlvs = session.decrypt(f)
                    val count = HuaweiRecords.parseCount(tlvs)
                    // The result CODE, not merely that a result tag was present: without it there is
                    // no telling "wrong command" from "right command, wrong payload", which is the
                    // only question this sweep exists to answer.
                    buildString {
                        append(if (count != null) "count=$count" else resultOf(tlvs))
                        append("   tags=")
                        append(tlvs.joinToString(",") { "0x%02X".format(it.tag) })
                    }
                }.getOrElse { "— ${it::class.java.simpleName}: ${it.message}" }
                line("  0x07/0x%02X  %-18s %s".format(cmd, guess, r))
            }
            line()

            // Sleep is reachable (0x0C counts) but its RECORD shape is unknown, and a parser
            // cannot be written against a guess. Fetch one and print its TLV structure — the same
            // way the step record was decoded. Read-only: a count and an indexed read.
            // 0x0C/0x0D was labelled "sleep" on nothing but the plan's guess, and it is NOT sleep.
            // Decoded on 2026-08-22 its events run right through the working day — 09:18, 10:23,
            // 11:57, 12:14 — and 白い熊 confirmed the 36-minute one at 08:37 was a morning walk and
            // the short night-time ones were trips to the bathroom. They are activity bouts:
            //
            //   0x83 per event: 0x04 = 01 (constant), 0x06 = 00 (constant),
            //   0x05 = <4-byte epoch start><2-byte duration in MINUTES>, non-overlapping.
            //
            // Sleep is elsewhere. The band displays last night's sleep, so it holds it; which
            // command serves it is not yet known, and guessing is what produced this mislabel.
            line("=== activity bouts — 0x07/0x0C count, 0x0D fetch (NOT sleep) ===")
            runCatching {
                val cf = session.request(
                    HuaweiCommands.SVC_FITNESS, 0x0C,
                    HuaweiCommands.fitnessCount(dayAgo, now), timeoutMs = 8_000,
                )
                val n = HuaweiRecords.parseCount(session.decrypt(cf)) ?: 0
                line("count=$n over the last 24 h")
                // Zero-based, as the step service turned out to be.
                for (index in 0 until minOf(n, 2)) {
                    val rf = session.request(
                        HuaweiCommands.SVC_FITNESS, 0x0D,
                        HuaweiCommands.fitnessRecord(index), timeoutMs = 8_000,
                    )
                    line("  record $index:")
                    fun dump(tlvs: List<HuaweiProtocol.Tlv>, indent: String) {
                        for (t in tlvs) {
                            val hex = HuaweiCrypto.upperHex(t.value)
                            line("$indent tag 0x%02X (%d B) %s".format(t.tag, t.value.size, hex.take(96)))
                            // Container tags carry nested TLVs; 0x80 marks them in this protocol.
                            if (t.tag and 0x80 != 0 && t.value.size > 2) {
                                runCatching { dump(HuaweiProtocol.parseTlvs(t.value), "$indent  ") }
                            }
                        }
                    }
                    dump(session.decrypt(rf), "   ")
                }
            }.onFailure { line("sleep fetch failed: ${it.message}") }
            line()

            // 0x19/0x01 already answered 100000 (success) to a count-shaped payload, so the
            // command is right and the payload is not. Three shapes, cheapest first.
            line("=== service 0x19 (RR intervals — real HRV) ===")
            val rriPayloads = listOf(
                "empty" to ByteArray(0),
                "tlv(1)" to HuaweiProtocol.tlv(1),
                "tlv(1,=1)" to HuaweiProtocol.tlv(1, byteArrayOf(1)),
                "count(24h)" to HuaweiCommands.fitnessCount(dayAgo, now),
            )
            for (cmd in listOf(0x01, 0x02)) {
                for ((name, payload) in rriPayloads) {
                    val r = runCatching {
                        val f = session.request(HuaweiCommands.SVC_RRI, cmd, payload, timeoutMs = 4_000)
                        val tlvs = session.decrypt(f)
                        resultOf(tlvs) + "   " + tlvs.joinToString(",") {
                            "0x%02X=%s".format(it.tag, HuaweiCrypto.upperHex(it.value).take(24))
                        }
                    }.getOrElse { "— ${it::class.java.simpleName}: ${it.message}" }
                    line("  0x19/0x%02X  %-11s %s".format(cmd, name, r))
                }
            }

            // The weather service, swept on request only.
            //
            // The band's own census says 0x0F answers commands 0x01..0x09 — nine of them — and this
            // app has only ever used two: 0x01 to push and 0x05 to set the unit. 0x0C (disable) is
            // known from a capture and is not even in the census, so the census undercounts.
            //
            // Worth knowing because the weather push is broken in a way nothing here can see: the
            // band acknowledges the frame with a success code and its screen keeps showing Huawei
            // Health's last reading from 2026-08-23. Our record is not merely unrendered, it is not
            // stored — the place name never changes. Two of these commands are worth hoping for: a
            // read-back, which would let the app check its own work instead of asking 白い熊 to look
            // at the wrist, and the "weather reports ON" that the capture notes concluded did not
            // exist, on the strength of Health simply resuming pushes.
            //
            // Empty payloads on purpose: an empty one is normally refused outright (0x19 answers
            // 100013 to one), which makes it the safest possible knock on a door whose function is
            // unknown. This is still a sweep of unknown commands on 白い熊's own band, which is why
            // it is opt-in and never runs as part of an ordinary diagnostic.
            if (args[WEATHER_SWEEP_ARG]?.trim().equals("true", ignoreCase = true)) {
                line("")
                line("=== service 0x0F (weather) — command sweep, empty payloads ===")
                line("  known: 0x01 push · 0x05 unit · 0x0C disable (not in the census)")
                for (cmd in 0x02..0x09) {
                    if (cmd == 0x05) {
                        line("  0x0F/0x05  (unit — known, not swept)")
                        continue
                    }
                    val r = runCatching {
                        val f = session.request(HuaweiCommands.SVC_WEATHER, cmd, ByteArray(0), timeoutMs = 4_000)
                        val tlvs = session.decrypt(f)
                        resultOf(tlvs) + "   " + tlvs.joinToString(",") {
                            "0x%02X=%s".format(it.tag, HuaweiCrypto.upperHex(it.value).take(48))
                        }
                    }.getOrElse { "— ${it::class.java.simpleName}: ${it.message}" }
                    line("  0x0F/0x%02X  %s".format(cmd, r))
                }
            }
            report.toString()
        }

        return result.fold(
            onSuccess = { text ->
                runCatching { File(out).writeText(text) }
                ctx.variables.set("HUAWEI_ProbeFile", out)
                ctx.logger("Huawei probe written to $out")
                args["store"]?.trim()?.ifEmpty { null }?.let { ctx.variables.set(it, out) }
                ActionResult.Success
            },
            onFailure = {
                val why = it.message ?: it::class.java.simpleName
                args["store"]?.trim()?.ifEmpty { null }?.let { k -> ctx.variables.set(k, why) }
                ActionResult.Failure(why)
            },
        )
    }
}
