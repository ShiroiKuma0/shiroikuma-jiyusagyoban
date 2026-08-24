package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.HuaweiFileClient
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner

/**
 * `Pull Huawei Band files` — fetch `sequence_data` and `rrisqi_data.bin` off the band and write
 * them down, WITHOUT interpreting them.
 *
 * These two files are where the band keeps what the fitness service never hands out: sleep, and the
 * per-beat RR intervals that are the reason this band was bought at all. They are fetched by name
 * over service `0x2C`, which is a different mechanism from the indexed records a sync walks.
 *
 * ## Why this dumps instead of decoding
 *
 * `sequence_data` is a container, and which stream inside it holds sleep is not known — Huawei
 * Health was captured asking for three different ids, of which two returned data. `rrisqi_data.bin`
 * has never returned anything to us, because the band had only just been told to record RR
 * intervals when the capture was taken. A decoder written against that would be a guess with
 * nothing to check it against; bytes on disk can be checked. So this answers the prior question —
 * *which id is sleep, and does the RR file fill up?* — and the decoder comes after.
 *
 * Each id is tried independently: one failing tells us something about that id and must not cost
 * the others.
 */
class HuaweiFilesAction : Action {
    override val id = "huawei.files"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: "HUAWEI_"
        val store = args["store"]?.trim()?.ifEmpty { null }
        val address = args["address"]?.trim()?.ifEmpty { null } ?: HuaweiSettings.address(ctx.app)
        val outDir = args["out"]?.trim()?.ifEmpty { null } ?: "/sdcard/tmp"
        val days = args["days"]?.trim()?.toIntOrNull()?.coerceIn(1, 30) ?: 3
        val ids = (args["ids"]?.trim()?.ifEmpty { null } ?: DEFAULT_IDS)
            .split(',').mapNotNull { it.trim().toIntOrNull() }

        val now = System.currentTimeMillis() / 1000
        val from = now - days * 86_400L
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
            .format(java.util.Date())

        val requests = buildList {
            ids.forEach {
                add(Triple(HuaweiFileClient.SEQUENCE_DATA, HuaweiFileClient.SEQUENCE_TYPE, it as Int?))
            }
            add(Triple(HuaweiFileClient.RRI_DATA, HuaweiFileClient.RRI_TYPE, null))
        }

        val result = HuaweiSyncRunner.fetchFiles(
            ctx.app, address, requests, from, now, outDir, stamp,
        )
        return result.fold(
            onSuccess = { rows ->
                val text = rows.joinToString("\n") { r ->
                    val who = if (r.id == null) r.name else "${r.name} ${r.id}"
                    // A partial file must never read like a whole one. Before this, an incomplete
                    // transfer and a complete one differed only by the word "partial" buried in the
                    // FILENAME — the summary line said "421634 B → …" either way, which is exactly
                    // the shape of report that gets skimmed and believed.
                    if (r.bytes > 0 && r.note == "ok") "$who — ${r.bytes} B → ${r.path}"
                    else if (r.bytes > 0) "$who — ${r.note} → ${r.path}"
                    else "$who — ${r.note}"
                }
                ctx.variables.set("${prefix}Files", rows.count { it.bytes > 0 }.toString())
                ctx.variables.set("${prefix}Summary", text)
                store?.let { ctx.variables.set(it, text) }
                ctx.logger("Huawei files:\n$text")
                ActionResult.Success
            },
            onFailure = {
                val why = it.message ?: it::class.java.simpleName
                ctx.variables.set("${prefix}Summary", why)
                store?.let { k -> ctx.variables.set(k, why) }
                ActionResult.Failure(why)
            },
        )
    }

    companion object {
        /** The three ids Huawei Health was captured asking `sequence_data` for. */
        const val DEFAULT_IDS = "700004,700013,700021"
    }
}
