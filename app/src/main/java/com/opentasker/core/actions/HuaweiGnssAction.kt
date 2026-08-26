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
        args["stage_from"]?.trim()?.ifEmpty { null }?.let { from ->
            val src = File(from)
            if (src.isDirectory) {
                mirror.mkdirs()
                src.listFiles()?.forEach { f ->
                    if (f.isFile) runCatching { f.copyTo(File(mirror, f.name), overwrite = true) }
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
        if (files.isEmpty()) {
            return fail(
                ctx, prefix, store,
                "none of ${wanted.joinToString(", ")} found in $dir — nothing to serve",
            )
        }

        val waitSec = args["wait"]?.trim()?.toLongOrNull() ?: 20L
        // Same convention as BandScanAction: a variable set to "1" calls the wait off. A watch left
        // running for an hour needs a way out that is not force-stopping the app.
        val cancelVar = args["cancel_var"]?.trim()?.ifEmpty { null }
        cancelVar?.let { ctx.variables.set(it, "0") }

        // Live state for a scene to bind to. A transfer is a minute of silence otherwise, and the
        // band's own screen sits at 0 % throughout, so without this there is nothing to look at.
        val lines = ArrayDeque<String>()
        ctx.variables.set("${prefix}GnssLog", "")
        ctx.variables.set("${prefix}GnssPhase", "Starting")
        fun progress(phase: String, line: String?) {
            ctx.variables.set("${prefix}GnssPhase", phase)
            if (line != null) {
                lines.addLast(line)
                while (lines.size > MAX_LOG_LINES) lines.removeFirst()
                ctx.variables.set("${prefix}GnssLog", lines.joinToString("\n"))
            }
        }
        fun hms(sec: Long) = when {
            sec >= 3600 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
            sec >= 60 -> "${sec / 60}m ${sec % 60}s"
            else -> "${sec}s"
        }
        val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        ctx.variables.set("${prefix}GnssSince", clock.format(java.util.Date()))
        ctx.variables.set("${prefix}GnssRunning", "0s")
        progress("Offering ${files.size} file(s)", "Ready: ${files.keys.joinToString(", ")}")
        val announce = args["announce"]?.trim()?.lowercase() !in setOf("0", "false", "no", "off")

        return HuaweiSyncRunner.serveGnss(
            ctx.app, address, files, waitForAskMs = waitSec * 1000, announce = announce,
            onProgress = ::progress,
            shouldCancel = {
                cancelVar != null && ctx.variables.get(cancelVar)?.trim() == "1"
            },
            onTick = { elapsedMs -> ctx.variables.set("${prefix}GnssRunning", hms(elapsedMs / 1000)) },
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
                progress(if (r.served.isEmpty()) "Nothing taken" else "Done", null)
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
                if (r.served.isEmpty()) fail(ctx, prefix, store, text) else ActionResult.Success
            },
            onFailure = { fail(ctx, prefix, store, it.message ?: it::class.java.simpleName) },
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

    private companion object {
        /** Enough to see what happened, few enough to stay readable on a phone panel. */
        const val MAX_LOG_LINES = 12

        /** The band's own names, in the order Health serves them. */
        val DEFAULT_FILES = listOf(
            "HW_AGNSS_RTCM_33",
            "HW_PGNSS_GPS", "HW_PGNSS_BDS", "HW_PGNSS_GLONASS",
            "HW_PGNSS_GALILEO", "HW_PGNSS_QZS", "HW_PGNSS_EXTRA",
        )
    }
}
