package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner
import java.io.File

/**
 * `Huawei Band GNSS` — give the band its satellite assistance data.
 *
 * The band cannot fetch this itself. Without a companion feeding it, its AGNSS store goes stale and
 * it says "Data expires in 6 h" — after which a GPS fix takes minutes of cold search instead of
 * seconds.
 *
 * **The band asks and we answer**, which is the reverse of every other Huawei action here: it
 * raises `0x1F/0x01`, names a source, and then drives a pull over `0x1C` in an order we do not
 * choose. See [HuaweiSyncRunner.serveGnss].
 *
 * **We do not fetch what the band names.** Its request string points at Huawei's own cloud, and
 * honouring a URL a device hands us would make this app the band's general HTTP client — the same
 * `hw.wearable.httpProxy` hazard we decline elsewhere. Instead this action serves files already on
 * disk, so the source is 白い熊's choice and an ordinary `http.get` task is what puts them there.
 *
 * The file NAMES matter: the band asks for them by name, so they must be its own —
 * `HW_AGNSS_RTCM_33` is the broadcast-ephemeris file that expires in hours, and the six
 * `HW_PGNSS_*` are Huawei's predicted ephemeris, which last days and are an opaque format we
 * cannot generate. Serving only the AGNSS file is a legitimate configuration: it is the one that
 * goes stale.
 */
class HuaweiGnssAction : Action {
    override val id = "huawei.gnss"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: "HUAWEI_"
        val store = args["store"]?.trim()?.ifEmpty { null }
        val address = args["address"]?.trim()?.ifEmpty { null } ?: HuaweiSettings.address(ctx.app)

        val dir = args["dir"]?.trim()?.ifEmpty { null }
            ?: return fail(ctx, prefix, store, "no dir given")
        val root = File(dir)
        // `http.request` confines output_file to the app's own user_files, MIRRORING the path
        // underneath it — so a download written to "/sdcard/tmp/gnss/X" actually lands at
        // "<files>/user_files/sdcard/tmp/gnss/X". That is the HTTP action being careful with
        // shared storage, not a bug, and it means the two halves of this feature naturally live in
        // two places: files staged over adb sit on /sdcard, freshly downloaded ones in the sandbox.
        // Searching both lets ONE path string serve both, so the task never has to know.
        val mirror = File(File(ctx.app.filesDir, "user_files"), dir.trimStart('/', '\\'))

        // Seeding the app's own store, once.
        //
        // `adb push` cannot reach internal storage on a release build, and the predicted-ephemeris
        // files cannot be downloaded (their endpoint wants a Huawei account). So there has to be a
        // way in for files that arrive by some other route. `stage_from` copies them once; after
        // that the shared-storage copy can be deleted and everything lives in the app's own store,
        // overwritten in place by each download.
        // A MIRROR of the staging directory, not a merge into the store.
        //
        // It used to copy in and never remove, which meant a predicted file we had stopped shipping
        // went on being served forever. On 2026-08-29 the build stopped generating BeiDou and QZSS
        // because their captured sets had expired, the staging directory was cleared of them, the
        // directory was checked — and the band was handed the same 31-hour-old BeiDou anyway, out of
        // this store. Deleting the staged copy was never enough; the store is what decides.
        //
        // Only the predicted files are mirrored. `HW_AGNSS_RTCM_33` arrives by download and is never
        // staged, so a blind mirror would delete the very file the task had just fetched.
        var pruned = 0
        args["stage_from"]?.trim()?.ifEmpty { null }?.let { from ->
            val src = File(from)
            if (src.isDirectory) {
                mirror.mkdirs()
                val staged = src.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet().orEmpty()
                src.listFiles()?.forEach { f ->
                    if (f.isFile) runCatching { f.copyTo(File(mirror, f.name), overwrite = true) }
                }
                mirror.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.startsWith(PREDICTED_PREFIX) && f.name !in staged) {
                        if (f.delete()) pruned++
                    }
                }
            }
        }
        if (!root.isDirectory && !mirror.isDirectory) {
            return fail(ctx, prefix, store, "no such folder, on disk or in the app's store: $dir")
        }

        // Only the band's own names, and only regular files. A directory swept blindly would offer
        // the band whatever happened to be beside them and then fail the transfer on a name it
        // never asked for.
        val wanted = args["files"]?.trim()?.ifEmpty { null }
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: DEFAULT_FILES
        val files = LinkedHashMap<String, ByteArray>()
        var fresh = 0
        for (name in wanted) {
            // The sandbox copy wins: it is the one a download just refreshed, while the version on
            // shared storage is whatever was staged there once and never moves.
            val m = File(mirror, name)
            val d = File(root, name)
            when {
                // Counted, not called "downloaded": the store holds both the file a download just
                // refreshed and the ones seeded in by hand, and the count cannot tell them apart.
                m.isFile -> { files[name] = gunzipIfNeeded(m.readBytes()); fresh++ }
                d.isFile -> files[name] = gunzipIfNeeded(d.readBytes())
            }
        }

        // Every predicted file is offered, INCLUDING an expired one, and its window is reported.
        //
        // Dropping the expired ones is what this did for one build, on the reasoning that a set whose
        // last block is in the past is worse than none — the band trusts what it is given and stops
        // looking for better. The band disagreed, and it was measured: carrying Huawei's stale BeiDou
        // and QZSS the fix took about a minute; with those two files removed and nothing else changed,
        // it went back to about three (白い熊, 2026-08-29, two standing walks). Whatever the band does
        // with an out-of-date orbit, it is worth two minutes more than having no orbit at all.
        //
        // So the age is REPORTED and never acted on. If a file should not be served, the thing that
        // decides that is the staging directory above, where a person can see it.
        val nowGps = System.currentTimeMillis() / 1000 - GPS_UNIX_EPOCH + GPS_LEAP_SECONDS
        val expired = mutableListOf<String>()
        var windowEnd = 0L
        for (name in files.keys) {
            if (!name.startsWith(PREDICTED_PREFIX) || name == PREDICTED_STATIC) continue
            val last = lastBlockSeconds(files[name]!!) ?: continue
            if (last < nowGps) {
                expired += name
            } else if (windowEnd == 0L || last < windowEnd) {
                windowEnd = last
            }
        }
        ctx.variables.set(
            "${prefix}GnssPredUntil",
            if (windowEnd == 0L) "" else {
                val unix = (windowEnd - GPS_LEAP_SECONDS + GPS_UNIX_EPOCH) * 1000
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    .format(java.util.Date(unix))
            },
        )
        ctx.variables.set("${prefix}GnssPredHours", if (windowEnd == 0L) "" else
            ((windowEnd - nowGps) / 3600).toString())
        if (files.isEmpty()) {
            return fail(
                ctx, prefix, store,
                "none of ${wanted.joinToString(", ")} found in $dir — nothing to serve",
            )
        }

        // Capped, so that the ceiling which fires is this one and not the engine's. TaskRunner wraps
        // every action in `withTimeout`, and an action that outlives its budget is killed where it
        // stands — for a band that means walking away mid-conversation. The budget is set from
        // [MAX_WAIT_SEC] with room for the transfer that follows the ask.
        val waitSec = (args["wait"]?.trim()?.toLongOrNull() ?: 20L).coerceIn(0L, MAX_WAIT_SEC)
        // Same convention as BandScanAction: a variable set to "1" calls the wait off. A watch left
        // running for an hour needs a way out that is not force-stopping the app.
        val cancelVar = args["cancel_var"]?.trim()?.ifEmpty { null }
        cancelVar?.let { ctx.variables.set(it, "0") }

        fun hms(sec: Long) = when {
            sec >= 3600 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
            sec >= 60 -> "${sec / 60}m ${sec % 60}s"
            else -> "${sec}s"
        }

        // ── the four-step panel, steps 3 and 4 ─────────────────────────────────────────────────
        //
        // `huawei.pgnss` downloads and builds; THIS action tells the band and hands the files over.
        // The panel is one flow across both, so the second half has to publish into the same
        // variables — without this the run reached "2. Building the set — done" and then nothing
        // ever moved again, which since the panel learned to spot a dead run made it blank itself
        // mid-transfer (白い熊, 2026-08-30: "it skipped showing #3").
        //
        // Off unless asked for: `huawei.gnss` is also used on its own, where there is no panel and
        // writing four-step variables would be noise.
        val panel = args["panel"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
        val runStartedAt = ctx.variables.get("${prefix}PgnssStartedAt")?.trim()?.toLongOrNull()
            ?: System.currentTimeMillis()
        fun panelSet(name: String, value: String) {
            if (panel) ctx.variables.set("$prefix$name", value)
        }
        /** The heartbeat and the run clock, which the panel uses to tell a live run from a corpse. */
        fun panelBeat() {
            if (!panel) return
            val now = System.currentTimeMillis()
            ctx.variables.set("${prefix}PgnssHeartbeat", now.toString())
            ctx.variables.set("${prefix}PgnssElapsed", hms((now - runStartedAt) / 1000))
        }
        // Live state for a scene to bind to. A transfer is a minute of silence otherwise, and the
        // band's own screen sits at 0 % throughout, so without this there is nothing to look at.
        val lines = ArrayDeque<String>()
        ctx.variables.set("${prefix}GnssLog", "")
        ctx.variables.set("${prefix}GnssPhase", "Starting")
        fun progress(phase: String, line: String?) {
            ctx.variables.set("${prefix}GnssPhase", phase)
            panelBeat()
            if (line != null) {
                lines.addLast(line)
                while (lines.size > MAX_LOG_LINES) lines.removeFirst()
                ctx.variables.set("${prefix}GnssLog", lines.joinToString("\n"))
            }
        }
        val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        ctx.variables.set("${prefix}GnssSince", clock.format(java.util.Date()))
        ctx.variables.set("${prefix}GnssRunning", "0s")
        if (pruned > 0) progress("Staging", "removed $pruned stale predicted file(s) from the store")
        if (expired.isNotEmpty()) {
            progress("Staging", "past its window, offered anyway: ${expired.joinToString(", ")}")
        }
        progress("Offering ${files.size} file(s)", "Ready: ${files.keys.joinToString(", ")}")
        val announce = args["announce"]?.trim()?.lowercase() !in setOf("0", "false", "no", "off")
        // Whether the predicted set is on the table. If it is, the band merely taking its 7 KB
        // broadcast file — which it asks for on its own whenever it wants a fix — is NOT this
        // finishing, and must not turn step 3 green or end the run reporting success.
        val predicted = files.keys.filter { it.startsWith(HuaweiSyncRunner.PGNSS_PREFIX) }
        val predictedOffered = predicted.isNotEmpty()
        // The count is against what the run is FOR. Offering seven files and counting the broadcast
        // one among them left the panel reading "6/7" after a complete, successful update, because
        // the band takes that seventh on its own errand and may simply not want it.
        val target = if (predictedOffered) predicted else files.keys.toList()
        panelSet("PgnssSteps", "done,done,run,wait")
        panelSet("PgnssPhase", "Waiting for the band")
        panelSet("PgnssDetail", "${files.size} file(s) ready · ${files.values.sumOf { it.size } / 1024} KB")
        panelSet("PgnssCount", "0/${files.size}")
        panelSet("PgnssPct", "90")
        panelBeat()


        return HuaweiSyncRunner.serveGnss(
            ctx.app, address, files, waitForAskMs = waitSec * 1000, announce = announce,
            onProgress = ::progress,
            // Live, so the panel can report a finished transfer while the action is still waiting
            // to see whether the band asks for anything else.
            onServed = { served, bytes ->
                ctx.variables.set("${prefix}GnssServed", served.joinToString(","))
                ctx.variables.set("${prefix}GnssBytes", bytes.toString())
                // Step 3 is "press Update on the band", so it goes green when the PREDICTED round
                // starts — not when the band helps itself to the broadcast file, which it does
                // unprompted and which says nothing about whether 白い熊 has pressed anything.
                val predictedServed = served.any { it.startsWith(HuaweiSyncRunner.PGNSS_PREFIX) }
                if (!predictedOffered || predictedServed) {
                    panelSet("PgnssSteps", "done,done,done,run")
                    panelSet("PgnssPhase", "The band is taking the files")
                } else {
                    panelSet("PgnssPhase", "Waiting for the band")
                }
                panelSet("PgnssDetail", served.joinToString(", "))
                panelSet("PgnssCount", "${served.count { it in target }}/${target.size}")
                panelSet(
                    "PgnssPct",
                    (90 + 10.0 * served.count { it in target } / target.size.coerceAtLeast(1))
                        .toInt().toString(),
                )
                panelBeat()
            },
            shouldCancel = {
                cancelVar != null && ctx.variables.get(cancelVar)?.trim() == "1"
            },
            onTick = { elapsedMs ->
                ctx.variables.set("${prefix}GnssRunning", hms(elapsedMs / 1000))
                panelBeat()
            },
        ).fold(
            onSuccess = { r ->
                val text = buildString {
                    append(if (r.asked) "band asked" else "band did not ask")
                    r.source?.let { append(" for $it") }
                    append(" · offered ${files.size} file(s), ${files.values.sumOf { it.size }} B")
                    if (fresh > 0) append(" ($fresh from the app's own store)")
                    if (r.served.isEmpty()) {
                        append(" · the band took NOTHING")
                    } else {
                        append(" · it took ${r.served.joinToString(", ")} (${r.bytes} B)")
                    }
                    if (r.detail.isNotEmpty()) append(" · ${r.detail}")
                }
                // Taking ONLY the broadcast file is not this action's job done. The band asks for
                // that on its own whenever it wants a fix; the predicted set is what the run exists
                // to deliver, and it arrives only after Update is pressed on the band.
                val onlyBroadcast = predictedOffered &&
                    r.served.isNotEmpty() && r.served.none { it.startsWith(HuaweiSyncRunner.PGNSS_PREFIX) }
                progress(if (r.served.isEmpty()) "Nothing taken" else "Done", null)
                if (r.served.isEmpty() || onlyBroadcast) {
                    panelSet("PgnssSteps", "done,done,fail,wait")
                    panelSet(
                        "PgnssFailed",
                        if (onlyBroadcast) {
                            "On the band: it took only the broadcast file and never asked for the " +
                                "forecast — press Update on the band while this is open"
                        } else {
                            "On the band: the band took nothing"
                        },
                    )
                } else {
                    panelSet("PgnssSteps", "done,done,done,done")
                    panelSet("PgnssPhase", "Transferred")
                    panelSet("PgnssDetail", "")
                    panelSet("PgnssCount", "${r.served.count { it in target }}/${target.size}")
                    panelSet("PgnssPct", "100")
                    panelSet("PgnssResult", text)
                }
                panelBeat()
                ctx.variables.set("${prefix}GnssSummary", text)
                ctx.variables.set("${prefix}GnssServed", r.served.joinToString(","))
                // Stats for the result panel: a watch that ran for half an hour should be able to
                // say WHEN it caught the band and how long that took, not merely that it did.
                ctx.variables.set("${prefix}GnssBytes", r.bytes.toString())
                ctx.variables.set(
                    "${prefix}GnssCaughtAt",
                    if (r.caughtAtMs == 0L) "" else java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date(r.caughtAtMs)),
                )
                ctx.variables.set(
                    "${prefix}GnssWaited",
                    if (r.caughtAtMs == 0L) "" else hms(r.waitedMs / 1000),
                )
                ctx.variables.set("${prefix}GnssSource", r.source ?: "")
                store?.let { ctx.variables.set(it, text) }
                ctx.logger("Huawei GNSS: $text")
                // Offering data the band declines is not success. Saying so here is the whole
                // lesson of the weather bug: a transfer that reports "sent" while the band kept
                // nothing is indistinguishable from one that worked.
                if (r.served.isEmpty() || onlyBroadcast) fail(ctx, prefix, store, text)
                else ActionResult.Success
            },
            onFailure = {
                val why = it.message ?: it::class.java.simpleName
                panelSet("PgnssSteps", "done,done,fail,wait")
                panelSet("PgnssFailed", "On the band: $why")
                panelBeat()
                fail(ctx, prefix, store, why)
            },
        )
    }

    /**
     * Decompress when the file arrived gzipped, which Huawei's own endpoint always does.
     *
     * `https://geo-dre.platform.dbankcloud.com/higeo/v1/gnssinfo?type=0x0004` returns the AGNSS
     * broadcast ephemeris gzipped — 6451 bytes on the wire, 7403 after, which is exactly the size
     * and exactly the message mix (1019 x31, 1020 x24, 1042 x31, 1046 x28) that Huawei Health was
     * captured serving the band. No account and no token: the server answers a plain GET.
     *
     * Handling it here means a task can `http.get` straight into the folder and nothing else needs
     * to know the transport compressed it. A file that is not gzipped is returned untouched, so
     * bytes captured off the wire still work.
     */
    private fun gunzipIfNeeded(raw: ByteArray): ByteArray {
        if (raw.size < 2 || raw[0] != 0x1F.toByte() || raw[1] != 0x8B.toByte()) return raw
        return runCatching {
            java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() }
        }.getOrElse { raw }   // a bad gzip is served as-is; the band's CRC check is the real gate
    }

    private fun fail(ctx: ActionContext, prefix: String, store: String?, why: String): ActionResult {
        ctx.variables.set("${prefix}GnssSummary", why)
        store?.let { ctx.variables.set(it, why) }
        ctx.logger("Huawei GNSS failed: $why")
        return ActionResult.Failure(why)
    }

    internal companion object {
        /** Enough to see what happened, few enough to stay readable on a phone panel. */
        const val MAX_LOG_LINES = 12

        /**
         * The longest watch, in seconds — an hour, which is what 衛星待受 asks for.
         *
         * The band raises its request when an outdoor walk starts, so the watch has to still be
         * standing when 白い熊 presses start; a minute of listening catches nothing but luck.
         * `TaskRunner.HUAWEI_GNSS_TIMEOUT_MS` is set above this plus the transfer, so this ceiling
         * is the one that fires and the action gets to say what happened.
         */
        const val MAX_WAIT_SEC = 3600L

        /** The band's own names, in the order Health serves them. */
        /** Predicted-ephemeris files: staged from the PC, never downloadable here. */
        const val PREDICTED_PREFIX = "HW_PGNSS_"

        /** The static blob — almanacs, iono, channel tables. Not a 36-block epoch file. */
        const val PREDICTED_STATIC = "HW_PGNSS_EXTRA"

        /** 1980-01-06 in Unix seconds, and the current GPS-UTC offset. */
        const val GPS_UNIX_EPOCH = 315_964_800L
        const val GPS_LEAP_SECONDS = 18L

        /**
         * When a predicted file stops being about the future: the last of its 36 block stamps.
         *
         * The header is 36 entries of three little-endian u32 — (GPS seconds, offset, length) — so
         * the last stamp is the start of the final two-hour slice. Null when the bytes are not that
         * shape, which is the honest answer for a file we did not write.
         */
        fun lastBlockSeconds(bytes: ByteArray): Long? {
            if (bytes.size < 1008) return null
            val off = 12 * 35
            var v = 0L
            for (i in 3 downTo 0) v = (v shl 8) or (bytes[off + i].toLong() and 0xFF)
            return v.takeIf { it > GPS_UNIX_EPOCH / 2 }
        }

        val DEFAULT_FILES = listOf(
            "HW_AGNSS_RTCM_33",
            "HW_PGNSS_GPS", "HW_PGNSS_BDS", "HW_PGNSS_GLONASS",
            "HW_PGNSS_GALILEO", "HW_PGNSS_QZS", "HW_PGNSS_EXTRA",
        )
    }
}
