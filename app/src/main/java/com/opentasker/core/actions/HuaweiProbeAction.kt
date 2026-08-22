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

    private companion object { const val SWEEP_ARG = "sweep" }

    override val id = "huawei.probe"
    override val category = ActionCategory.SYSTEM

    /**
     * Every command the fitness service flags, plus 0x0C.
     *
     * 0x0C is included precisely BECAUSE the census does not flag it and it answers anyway with a
     * real sleep count — so the census is a guide, not gospel, and a sweep beats trusting it.
     *
     * The protocol's shape is count/record pairs (0x0A/0x0B is steps, 0x0C/0x0D is sleep), so an
     * even command answering a count query is the signature worth looking for.
     */
    private val fitnessProbes = listOf(
        0x0A to "step count (known)",
        0x0C to "sleep count (answers despite not being flagged)",
        0x0E to "?", 0x11 to "?", 0x12 to "?", 0x13 to "?", 0x14 to "?",
        0x15 to "?", 0x16 to "?", 0x17 to "?", 0x18 to "?", 0x19 to "?",
        0x1A to "?", 0x1B to "?",
    )

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
            line("=== fitness service 0x07 — count probes over the last 24 h ===")
            for ((cmd, guess) in fitnessProbes) {
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
