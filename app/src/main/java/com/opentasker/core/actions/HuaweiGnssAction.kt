package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.GnssForecastReminder
import com.opentasker.core.huawei.pgnss.PredictedSet
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

        // WHAT THE BAND IS HOLDING — read before anything is offered, so the answer exists even on
        // a run that never reaches the band.
        //
        // Every record this feature kept was about the PHONE: `built-log.txt` says what was built,
        // `GnssSummary` says what was offered. Nothing said what the band ACCEPTED, or until when
        // that set is good — and on 2026-09-18 that was the whole story. A set was built (window to
        // 09-21 11:59 UTC), the transfer after it moved ZERO bytes, and the band went on wearing the
        // set from 09-15 whose window closed 09-18 13:59 UTC. The phone was in perfect health and
        // said so; the band was starving and nothing could say that. 白い熊 lost four days to it and
        // three separate theories were built about stale almanacs, all wrong (2026-09-19).
        //
        // So the accepted window is written down when the band takes a set, and read back here.
        bandHoldsUntil(mirror)?.let { untilMs ->
            val hoursLeft = (untilMs - System.currentTimeMillis()) / 3_600_000L
            ctx.variables.set("${prefix}GnssBandUntil", stamp(untilMs))
            ctx.variables.set("${prefix}GnssBandHours", hoursLeft.toString())
            // Days AND hours, so the panel can be held up against the band's own screen without
            // arithmetic (白い熊, 2026-09-21) — and what that screen will be claiming, beside it.
            ctx.variables.set("${prefix}GnssBandLeft", GnssForecastReminder.leftLabel(untilMs))
            // What the BAND will be saying is a question about when it took the set, which this
            // early read does not know — only the transfer below does. Left to that.
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
        var live = 0
        var windowEnd = 0L
        for (name in files.keys) {
            if (!name.startsWith(PREDICTED_PREFIX) || name == PREDICTED_STATIC) continue
            if (name in ALWAYS_STALE) continue
            val last = coveredUntil(name, lastBlockSeconds(files[name]!!) ?: continue)
            if (last < nowGps) {
                expired += name
            } else {
                live++
                if (windowEnd == 0L || last < windowEnd) windowEnd = last
            }
        }
        // UTC, like every other satellite time this feature writes.
        //
        // This one formatter had no zone set, so it printed the phone's own — and the panel showed
        // `GnssPredUntil` as 16:59 beside `GnssBandUntil` as 14:59 and made one instant look like
        // two, two hours apart (白い熊, 2026-09-21). One instant, one spelling.
        ctx.variables.set("${prefix}GnssPredUntil", if (windowEnd == 0L) "" else stamp(unixOf(windowEnd)))
        ctx.variables.set("${prefix}GnssPredHours", if (windowEnd == 0L) "" else
            ((windowEnd - nowGps) / 3600).toString())
        if (files.isEmpty()) {
            return fail(
                ctx, prefix, store,
                "none of ${wanted.joinToString(", ")} found in $dir — nothing to serve",
            )
        }

        // A set with NOTHING live in it is not handed over. This is the one case the 2026-08-29
        // measurement above did not cover, and it is the opposite result.
        //
        // That measurement removed the two stale files from a set whose GPS and Galileo were good,
        // and the fix got worse — so a partly-stale set is still worth serving, and it still is.
        // A set where every constellation is dead is a different animal: the band takes it, marks
        // its assistance data current, and then STOPS ASKING for the broadcast ephemeris that would
        // have rescued it — "The band never asked — its data is still fresh", in the app's own log.
        // 白い熊 then waited nineteen minutes for a fix on 2026-09-06, against the 581 s this
        // repository measured with no valid set at all. Handing over a wholly dead set is
        // measurably worse than handing over nothing, so it stops here.
        val predictedHeld = files.keys.count {
            it.startsWith(PREDICTED_PREFIX) && it != PREDICTED_STATIC && it !in ALWAYS_STALE
        }
        if (predictedHeld > 0 && live == 0) {
            val until = expired.joinToString(", ")
            ctx.variables.set(
                "${prefix}PgnssAlert",
                "THE BAND WAS NOT GIVEN THE FORECAST — every file in the set is out of date " +
                    "($until). Nothing was handed over: a dead set stops the band asking for the " +
                    "broadcast ephemeris it can still use, which is worse than giving it nothing. " +
                    "Run 衛星更新 again to build a current one.",
            )
            return fail(
                ctx, prefix, store,
                "NOT HANDED OVER — the whole predicted set is past its window ($until)",
            )
        }
        // A BUILD THAT FAILED IS NOT OURS TO ERASE, AND NOT OURS TO PAPER OVER.
        //
        // 2026-09-19, and it is the whole of why 白い熊's walk failed two days later. The build died
        // on `Unable to resolve host "download.aiub.unibe.ch"` and wrote its reason into
        // `PgnssAlert`; 「衛星 生成」 carries `continueOnError` on that step, so the task walked on to
        // THIS action; the line below used to overwrite that alert with "" because nothing was
        // recorded about the band yet; the transfer of the PREVIOUS set then succeeded and rewrote
        // the panel to `done,done,done,done`; and the task's last notification — gated on exactly
        // that string — announced 「予測暦 済」. A failed build was converted into a success message
        // and a two-day-old set was handed over as though it were three days of forecast.
        //
        // So the serve refuses outright while a build failure is standing. It did not repair the
        // failure and it will not stand in front of it. `serve_stale` is the deliberate override —
        // 「衛星 再送」 exists precisely to hand over the set already on the phone, and it clears the
        // failure variables at its own start, so it never trips this.
        val standingAlert = ctx.variables.get("${prefix}PgnssAlert").orEmpty()
        val buildFailureStanding = standingAlert.startsWith(NOT_REBUILT)
        val serveStale = args["serve_stale"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
        if (buildFailureStanding && !serveStale && predictedHeld > 0) {
            GnssForecastReminder.refusedStaleSet(ctx.app, standingAlert)
            return fail(
                ctx, prefix, store,
                "NOT HANDED OVER — the build did not produce a set ($standingAlert) and the band " +
                    "must not be given the old one as though it were new. Fix the build and run " +
                    "衛星 生成 again, or use 衛星 再送 to hand over the old set deliberately.",
            )
        }

        // AND A BAR ON FRESHNESS, for the caller that knows what it just built.
        //
        // `min_hours` is what a task asserts about the set it is about to hand over: a run that has
        // just built one holds 72 hours, so anything materially less means the build did not happen
        // and the store still has yesterday's. Absent, nothing changes — only a wholly dead set is
        // refused, which is the 2026-08-29 measurement's rule and stays.
        args["min_hours"]?.trim()?.toLongOrNull()?.let { minHours ->
            val leftHours = if (windowEnd == 0L) -1L else (windowEnd - nowGps) / 3600
            if (predictedHeld > 0 && leftHours < minHours) {
                ctx.variables.set(
                    "${prefix}PgnssAlert",
                    "THE SET ON THE PHONE HAS ONLY $leftHours h LEFT, and this run wanted at least " +
                        "$minHours. Nothing was handed over. Rebuild with 衛星 生成.",
                )
                GnssForecastReminder.refusedStaleSet(
                    ctx.app,
                    "手元の一式は残り $leftHours 時間しかありません（$minHours 時間必要）。/ " +
                        "the set on the phone has $leftHours h left, $minHours were required",
                )
                return fail(
                    ctx, prefix, store,
                    "NOT HANDED OVER — the set on the phone has $leftHours h left, below the " +
                        "$minHours h this run required",
                )
            }
        }

        // The band's own forecast, not the phone's, is what the banner warns about from here on.
        // A phone holding a perfect set says nothing about a band still wearing last week's, and
        // that gap is what cost four days in September 2026 — see [bandHoldsUntil].
        val bandLeftHours = bandHoldsUntil(mirror)
            ?.let { (it - System.currentTimeMillis()) / 3_600_000L }
        ctx.variables.set(
            "${prefix}PgnssAlert",
            when {
                // Never overwritten with silence: see the block above. Kept even when this run is
                // allowed to proceed, because the set it is serving is still the one nobody rebuilt.
                buildFailureStanding -> standingAlert
                bandLeftHours == null -> ""
                bandLeftHours < 0 -> "THE BAND'S FORECAST RAN OUT ${-bandLeftHours} h AGO — it has " +
                    "been fixing the slow way since. Press 更新 on the band while this is open."
                bandLeftHours < BAND_WARN_HOURS -> "The band's forecast has $bandLeftHours h left. " +
                    "Press 更新 on the band while this is open."
                else -> ""
            },
        )

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

                // The other half of `built-log.txt`. That file answers "what did the phone build";
                // this one answers "did the band ever get it", which is the question the 2026-09-18
                // failure needed and nothing could answer. One line per attempt, success or not.
                val tookPredicted = r.served.any { it.startsWith(HuaweiSyncRunner.PGNSS_PREFIX) }
                recordServed(
                    mirror,
                    buildString {
                        append(if (r.served.isEmpty()) "TOOK NOTHING" else "took ${r.served.size} file(s), ${r.bytes} B")
                        if (onlyBroadcast) append(" — BROADCAST ONLY, no forecast")
                        append(" · offered ${files.size}")
                        if (tookPredicted && windowEnd != 0L) {
                            append(" · the band now holds a forecast to ${stamp(unixOf(windowEnd))} UTC")
                        }
                        if (!r.asked) append(" · the band never asked")
                    },
                    ctx.logger,
                )
                // Only a PREDICTED file changes what the band holds. It helps itself to the
                // broadcast file whenever it wants a fix, and that is not a forecast.
                if (tookPredicted && windowEnd != 0L) {
                    writeBandHolds(mirror, unixOf(windowEnd), ctx.logger)
                    ctx.variables.set("${prefix}GnssBandUntil", stamp(unixOf(windowEnd)))
                    ctx.variables.set(
                        "${prefix}GnssBandHours",
                        ((unixOf(windowEnd) - System.currentTimeMillis()) / 3_600_000L).toString(),
                    )
                    ctx.variables.set(
                        "${prefix}GnssBandLeft",
                        GnssForecastReminder.leftLabel(unixOf(windowEnd)),
                    )
                    // THE BAND COUNTS FROM RECEIPT, and this is the moment of it. Recorded so the
                    // panel can print what its screen will be claiming beside what the data has —
                    // the two differ by exactly the delay between building and handing over, which
                    // is the thing worth seeing (白い熊, 2026-09-21).
                    val receivedAt = System.currentTimeMillis()
                    ctx.variables.set("${prefix}GnssBandFrom", stamp(receivedAt))
                    ctx.variables.set(
                        "${prefix}GnssBandClaims",
                        GnssForecastReminder.leftLabel(
                            receivedAt + GnssForecastReminder.BAND_WINDOW_HOURS * 3_600_000L,
                        ),
                    )
                    // AND THE BANNER COMES DOWN. It was raised before the wait, from what the band
                    // was holding THEN — "THE BAND'S FORECAST RAN OUT n h AGO" — and nothing
                    // recomputed it afterwards, so it stayed shouting over a transfer that had just
                    // succeeded while the result line underneath said so (白い熊, 2026-09-25). The
                    // band holds a live forecast now; that is the whole of what the banner exists to
                    // say, and it has nothing left to say.
                    ctx.variables.set("${prefix}PgnssAlert", "")
                    // SAY IT, AND COME BACK AND SAY IT AGAIN. A window written to a file and a
                    // variable is a window nobody reads: on 2026-09-21 白い熊 walked five hours past
                    // the end of a forecast while the band's own screen promised eighteen more, and
                    // every record needed to know better was already on the phone. See
                    // [GnssForecastReminder] for what the band's countdown actually means.
                    GnssForecastReminder.arm(ctx.app, unixOf(windowEnd), receivedAt)
                }
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
                recordServed(mirror, "FAILED · $why", ctx.logger)
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
         * How a failed BUILD announces itself, written by `huawei.pgnss` and read here.
         *
         * A string rather than a variable of its own because it is already the text 白い熊 sees on
         * the panel; making the serve key off the same words is what keeps "the build failed" from
         * being a fact only one action knows.
         */
        const val NOT_REBUILT = "THE SET WAS NOT REBUILT"

        /**
         * When a predicted file's last block stops covering the sky — NOT the stamp it carries.
         *
         * Two corrections, both worth an hour, and together they were most of the gap 白い熊 found
         * between our panel and the band's own screen on 2026-09-21.
         *
         * **GLONASS is stamped an hour early on purpose.** `Records.glonassHour` floors the block to
         * the UTC hour and the builder writes `stamps - 3600`; that is the format's convention, not
         * a file that expires sooner. Taking a plain minimum across the six therefore always picked
         * GLONASS and always read an hour pessimistic against GPS, Galileo and BeiDou.
         *
         * **A block covers the hour after it.** Every element set is fitted ±[Orbit.FIT_HALF] — the
         * grader samples exactly that span around each stamp — so a block stamped 15:59 is good
         * until 16:59, and reporting the stamp throws away the last hour we actually shipped.
         */
        internal fun coveredUntil(name: String, lastBlockGps: Long): Long =
            lastBlockGps +
                (if (name == PredictedSet.NAME_GLONASS) GLONASS_STAMP_OFFSET_SEC else 0L) +
                BLOCK_COVERS_SEC

        /** What the builder subtracts from a GLONASS block stamp; added back to compare like with like. */
        const val GLONASS_STAMP_OFFSET_SEC = 3600L

        /** [Orbit.FIT_HALF] as seconds: how far past its own stamp one element set stays good. */
        const val BLOCK_COVERS_SEC = 3600L

        /** A predicted block stamp, in GPS seconds, as Unix milliseconds. */
        internal fun unixOf(gpsSeconds: Long): Long =
            (gpsSeconds - GPS_LEAP_SECONDS + GPS_UNIX_EPOCH) * 1000

        /** `yyyy-MM-dd HH:mm` in UTC — the same clock `built-log.txt` writes in, and never local. */
        internal fun stamp(unixMs: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(unixMs))

        /**
         * One line per transfer attempt, beside the set it describes.
         *
         * The twin of `built-log.txt`, and the half that was missing. That file answers *what did the
         * phone build*; this one answers *did the band ever get it* — which on 2026-09-18 was the only
         * question that mattered and the only one nothing could answer. A failure is written as loudly
         * as a success, because the failure is the line someone comes looking for.
         */
        internal fun recordServed(outDir: File, what: String, log: (String) -> Unit) {
            runCatching {
                outDir.mkdirs()
                val file = File(outDir, SERVED_NAME)
                val kept = if (file.isFile) file.readLines().takeLast(SERVED_LINES - 1) else emptyList()
                file.writeText(
                    (kept + "${stamp(System.currentTimeMillis())} UTC · $what").joinToString("\n", postfix = "\n"),
                )
            }.onFailure { log("Huawei GNSS: could not write $SERVED_NAME — ${it.message}") }
        }

        /**
         * Remember the window of the forecast the band actually accepted.
         *
         * One line, so it can be read by eye as well as by code: the Unix milliseconds the window ends,
         * a tab, then the same instant written out. Only a run in which the band took a PREDICTED file
         * writes here — it helps itself to the broadcast file whenever it wants a fix, and that is not
         * a forecast.
         */
        internal fun writeBandHolds(outDir: File, untilMs: Long, log: (String) -> Unit) {
            runCatching {
                outDir.mkdirs()
                File(outDir, BAND_HOLDS_NAME).writeText("$untilMs\t${stamp(untilMs)} UTC\n")
            }.onFailure { log("Huawei GNSS: could not write $BAND_HOLDS_NAME — ${it.message}") }
        }

        /** What [writeBandHolds] left, or null when the band has never been seen taking a forecast. */
        internal fun bandHoldsUntil(outDir: File): Long? = runCatching {
            File(outDir, BAND_HOLDS_NAME).takeIf { it.isFile }
                ?.readText()?.substringBefore('\t')?.trim()?.toLongOrNull()
        }.getOrNull()

        /** The kept series of TRANSFERS: what the band took, every time, in order. */
        const val SERVED_NAME = "served-log.txt"

        /** Months of daily transfers, and a few kilobytes — the same budget `built-log.txt` keeps. */
        const val SERVED_LINES = 400

        /** One line: when the forecast the band last ACCEPTED runs out. */
        const val BAND_HOLDS_NAME = "band-holds.txt"

        /**
         * Hours of forecast left on the BAND below which the panel says so.
         *
         * A day: enough warning to rebuild and transfer before a morning walk, and not so much that
         * the banner is always up. The set is good for 72 h, so this fires on the last third.
         */
        const val BAND_WARN_HOURS = 24L

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

        /**
         * Files whose window is expired BY CONSTRUCTION, and which must not raise the alarm.
         *
         * `HW_PGNSS_QZS` is Huawei's own captured file, copied verbatim rather than fitted — it has
         * carried the window 2026-08-25 → 08-28 since the day it was captured and always will. It
         * is byte-identical in tonight's set and in both baseline archives, INCLUDING the one that
         * fixed in 13 s, so it demonstrably costs nothing; and QZSS is a regional system over East
         * Asia that is permanently below the horizon in Prague, so it can never earn anything
         * either. Reporting it as expired on every single run is a warning that is always lit, and
         * a warning that is always lit is one that hides the real one — which is part of how a set
         * four days dead went unnoticed for four days (白い熊, 2026-09-06).
         */
        val ALWAYS_STALE = setOf("HW_PGNSS_QZS")

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
