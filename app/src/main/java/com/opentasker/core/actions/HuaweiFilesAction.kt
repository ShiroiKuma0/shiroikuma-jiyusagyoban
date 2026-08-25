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

        // Refuse a pull that lands too soon after the last one, and say when it may run.
        //
        // Not a rate limit for politeness: the band's file service visibly degrades under repeated
        // use, and a pull inside the window returns a fraction of what a rested one does. Refusing
        // is better than trying — a wasted attempt costs a Bluetooth session AND makes the next one
        // worse, and a partial file that looks like a band fault is the expensive kind of wrong.
        //
        // `force` exists because a floor this soft must never be a wall: the window is a judgement
        // from ONE morning's evidence, not a measurement, and 白い熊 can always overrule it.
        val minGapMin = args["min_gap_min"]?.trim()?.toIntOrNull()?.coerceIn(0, 24 * 60) ?: DEFAULT_MIN_GAP_MIN
        val forced = args["force"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
        val sinceMs = System.currentTimeMillis() - HuaweiSettings.lastFilePull(ctx.app)
        val waitMin = minGapMin - (sinceMs / 60_000L)
        if (!forced && minGapMin > 0 && HuaweiSettings.lastFilePull(ctx.app) > 0 && waitMin > 0) {
            val text = "前回から ${sinceMs / 60_000L} 分。あと $waitMin 分待つこと。\n" +
                "Last pull ${sinceMs / 60_000L} min ago — $waitMin min to go."
            ctx.variables.set("${prefix}FilesResult", text)
            store?.let { ctx.variables.set(it, text) }
            ctx.logger("Huawei files: skipped, $waitMin min of the ${minGapMin}-min gap remain")
            return ActionResult.Success
        }

        val now = System.currentTimeMillis() / 1000
        val from = now - days * 86_400L
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
            .format(java.util.Date())

        // rrisqi FIRST, and this ordering is the whole point.
        //
        // It used to be last, after every sequence_data id, and that is why it answered
        // `nothing (100004)` on four attempts across two days — read at the time as the band having
        // no RR data at all, and turned into a hypothesis that some switch had to be flipped to make
        // it record any. The band was recording the whole time. 700004 alone is 1.16 MB and does not
        // finish (378691 of it after 8 rounds on 2026-08-25 07:05); by the time the channel had been
        // dragged through that, everything after it came back empty. Asked first on a rested
        // channel, rrisqi returned 20377 bytes — 308 windows over 2.7 days — in 1.7 seconds.
        //
        // It goes first rather than the ids being trimmed because it is both the smallest file and
        // the one the whole Huawei experiment was for: 20 KB ahead of a megabyte costs the
        // sequence_data pulls nothing they were reliably getting anyway.
        val requests = buildList {
            add(Triple(HuaweiFileClient.RRI_DATA, HuaweiFileClient.RRI_TYPE, null))
            ids.forEach {
                add(Triple(HuaweiFileClient.SEQUENCE_DATA, HuaweiFileClient.SEQUENCE_TYPE, it as Int?))
            }
        }

        // Marked before the fetch, not after: an attempt that fails still used the channel, and it
        // is the USE that degrades it. Marking on success would let a failing pull retry in a tight
        // loop, which is precisely the thing that produced the 8-block result.
        HuaweiSettings.markFilePull(ctx.app)
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
        /**
         * Minutes to leave between pulls unless a task says otherwise.
         *
         * 白い熊's call, and honestly a judgement rather than a measurement: the evidence is a single
         * morning in which a pull minutes after two large ones returned 8 blocks where a rested one
         * returned 54.
         */
        const val DEFAULT_MIN_GAP_MIN = 10

        /** The three ids Huawei Health was captured asking `sequence_data` for. */
        const val DEFAULT_IDS = "700004,700013,700021"
    }
}
