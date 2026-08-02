package com.opentasker.core.actions

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * `Speed test` — measure real download/upload throughput over ONE chosen transport.
 *
 * The transport is pinned with ConnectivityManager.requestNetwork and the transfer runs on that
 * Network's own socket factory. That is what lets a cellular test run **with WiFi still connected**:
 * WiFi keeps the default route for everything else, we simply do not use it. So no WiFi toggling, no
 * saving and restoring radio state, and nothing else on the phone notices the test happened.
 *
 * Each leg is time-boxed AND byte-capped, whichever ends first: a fast link stops on the clock (so the
 * bill stays small) and a slow link stops on bytes (so it never hangs for a minute). A fixed byte size
 * alone would be wrong in both directions — 10 MB is over before TCP leaves slow-start on 5G, and is a
 * long wait on a bad cell.
 *
 * Live progress is published into globals as it runs, so a scene bound to those names animates
 * without any polling: scene element configs are expanded on every variable change.
 */
class SpeedTestAction : Action {
    override val id = "net.speedtest"
    override val category = ActionCategory.NET

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val transport = when (args["transport"]?.trim()?.lowercase()) {
            "wifi" -> NetworkCapabilities.TRANSPORT_WIFI
            "cellular", "mobile", "sim" -> NetworkCapabilities.TRANSPORT_CELLULAR
            else -> null // any / current default route
        }
        val seconds = args["seconds"]?.trim()?.toDoubleOrNull()?.coerceIn(1.0, 60.0) ?: 10.0
        // The clock is the limiter, as in Ookla; this cap is only a runaway guard. A byte cap that binds
        // first ends the leg inside TCP slow-start and under-reports the link — 5 MB on this WiFi ended
        // in 0.28 s and yielded a single sample.
        val maxBytes = ((args["max_mb"]?.trim()?.toDoubleOrNull() ?: 4000.0).coerceIn(0.5, 20_000.0) * 1_000_000).toLong()
        val prefix = args["prefix"]?.trim().orEmpty().ifEmpty { "SPD_" }
        val direction = args["direction"]?.trim()?.lowercase() ?: "both"
        val rampMs = args["ramp_ms"]?.trim()?.toLongOrNull()?.coerceIn(0L, 10_000L) ?: 2_000L
        val streams = args["streams"]?.trim()?.toIntOrNull()?.coerceIn(1, 16) ?: 8
        // Endpoints, in order of preference:
        //
        //  1. The nearest Ookla server, discovered at run time. This is the network 白い熊 compares
        //     against, the servers exist to be speed-tested, and the API returns them ordered by
        //     distance from the client IP — so "pick a close server" is answered by the source of
        //     truth rather than guessed at.
        //  2. Tele2's public speed-test mirror, which is published for exactly this purpose.
        //
        // Cloudflare's /__down is NOT here: it answers 403 to a non-browser client. It backs their
        // web speed test and was never a public API; sending browser headers to get past that would
        // be impersonating one against an endpoint free to start refusing again.
        val ookla = if (args["down_url"].isNullOrBlank()) findNearestOoklaServer() else null
        val downUrls = args["down_url"]?.trim().orEmpty().takeIf { it.isNotEmpty() }?.let { listOf(it) }
            ?: listOfNotNull(
                ookla?.downloadUrl,
                "http://speedtest.tele2.net/100MB.zip",
                "https://proof.ovh.net/files/100Mb.dat",
            )
        val upUrls = args["up_url"]?.trim().orEmpty().takeIf { it.isNotEmpty() }?.let { listOf(it) }
            ?: listOfNotNull(
                ookla?.uploadUrl,
                "http://speedtest.tele2.net/upload.php",
            )

        SpeedTestCancel.clear()
        val cm = ContextCompat.getSystemService(ctx.app, ConnectivityManager::class.java)
            ?: return ActionResult.Failure("ConnectivityManager unavailable")

        return withContext(Dispatchers.IO) {
            var pinFailure: String? = null
            val pinned = if (transport == null) null else pinNetwork(cm, transport) { pinFailure = it }
            if (transport != null && pinned == null) {
                // Report what actually went wrong. Collapsing every cause into "timed out" hid a
                // throw from requestNetwork behind a 15s story the run never even took.
                return@withContext ActionResult.Failure(
                    pinFailure ?: (
                        "no ${transportName(transport)} network came up within 15s — " +
                            if (transport == NetworkCapabilities.TRANSPORT_CELLULAR) {
                                "is mobile data enabled on this SIM?"
                            } else {
                                "is WiFi connected?"
                            }
                        ),
                )
            }
            // Two ways to send traffic over a network that is NOT the system default, because devices
            // disagree about which they allow:
            //
            //  1. bindProcessToNetwork — routes THIS PROCESS's sockets. Coarse (everything the app does
            //     goes that way until it is cleared) but it is the one EMUI permits.
            //  2. network.openConnection — routes just this connection. Cleaner, and what we tried
            //     first, but it fails here with EPERM: "Binding socket to network 132 failed".
            //
            // Preferring (1) when a transport is pinned, falling back to (2), keeps this working on
            // both kinds of device. Whichever is used, WiFi is never switched off.
            val processBound = pinned != null &&
                runCatching { cm.bindProcessToNetwork(pinned.network) }.getOrDefault(false)
            val open: (URL) -> HttpURLConnection = when {
                pinned == null || processBound -> { url -> url.openConnection() as HttpURLConnection }
                else -> { url -> pinned.network.openConnection(url) as HttpURLConnection }
            }
            try {
                var down: Leg? = null
                var up: Leg? = null
                if (direction != "up") {
                    down = transfer(ctx, prefix, "down", open, downUrls, seconds, maxBytes, streams, rampMs)
                }
                if (direction != "down") {
                    up = transfer(ctx, prefix, "up", open, upUrls, seconds, maxBytes, streams, rampMs)
                }
                publishSummary(ctx, prefix, down, up)
                val parts = listOfNotNull(
                    down?.let { "down ${fmt(it.averageMbps)} Mb/s (peak ${fmt(it.peakMbps)})" },
                    up?.let { "up ${fmt(it.averageMbps)} Mb/s (peak ${fmt(it.peakMbps)})" },
                )
                if (parts.isEmpty()) {
                    ActionResult.Failure("nothing measured — direction was '$direction'")
                } else {
                    ctx.logger("Speed test: ${parts.joinToString(", ")}")
                    ActionResult.Success
                }
            } catch (e: Exception) {
                ActionResult.Failure("speed test failed: ${e.message}")
            } finally {
                // Always unbind: a process left pinned to cellular would quietly route the whole app —
                // widgets, HTTP actions, the lot — over mobile data long after the test ended.
                if (processBound) runCatching { cm.bindProcessToNetwork(null) }
                pinned?.release()
            }
        }
    }

    // --- one leg -------------------------------------------------------------------------------

    private class Leg(
        val bytes: Long,
        val millis: Long,
        val peakMbps: Double,
        val samples: Int,
        val latencyMs: Long,
        val settledBytes: Long = bytes,
        val settledMillis: Long = millis,
    ) {
        val rawMbps: Double get() = if (millis <= 0) 0.0 else bytes * 8.0 / millis / 1000.0

        /** The headline figure: the ramp excluded, which is what a speed test is actually claiming. */
        val averageMbps: Double
            get() = if (settledMillis <= 0) rawMbps else settledBytes * 8.0 / settledMillis / 1000.0
    }

    /**
     * One direction, run over [streams] concurrent connections against a shared byte counter.
     *
     * Concurrency is about accuracy, not speed for its own sake. A single TCP stream is bounded by
     * window/RTT, and on a short test a real share of the run is still in slow-start — so one stream
     * systematically *under*-reports a fast link. Several streams fill the pipe the way real usage
     * does, which is what "real throughput" has to mean here. Every stream shares the deadline and the
     * byte cap, so the leg still costs what the settings say it costs.
     *
     * [urls] is a fallback list, tried in order: the first host that connects wins. speed.cloudflare.com
     * is anycast, so the nearest PoP is already chosen by routing — the list exists for reachability
     * (this phone's WiFi is IPv6-only behind NAT64 and could not reach it at all), not for proximity.
     */
    private suspend fun transfer(
        ctx: ActionContext,
        prefix: String,
        phase: String,
        open: (URL) -> HttpURLConnection,
        urls: List<String>,
        seconds: Double,
        maxBytes: Long,
        streams: Int,
        rampMs: Long,
    ): Leg = coroutineScope {
        val deadline = System.nanoTime() + (seconds * 1_000_000_000L).toLong()
        val started = System.nanoTime()
        val total = java.util.concurrent.atomic.AtomicLong(0)
        val firstByte = java.util.concurrent.atomic.AtomicLong(0)
        val failures = mutableListOf<String>()
        // Which endpoint actually carried the leg. Without this a fallback is invisible and a slow
        // mirror reads as a slow link — exactly the wrong conclusion.
        val usedHost = java.util.concurrent.atomic.AtomicReference("")

        ctx.variables.set("${prefix}Phase", phase)
        ctx.variables.set("${prefix}Pct", "0")

        // Sampler: one publisher for all streams, so the UI sees aggregate throughput and the store is
        // written at a fixed cadence no matter how many connections are running.
        var peak = 0.0
        var samples = 0
        // Where the settled window starts: everything before it is TCP ramping up, and averaging it in
        // is what makes a short test read low. Reported separately from the raw average.
        var rampBytes = -1L
        var rampAt = 0L
        val sampler = launch {
            var lastBytes = 0L
            var lastAt = System.nanoTime()
            while (isActive) {
                kotlinx.coroutines.delay(SAMPLE_MS)
                val now = System.nanoTime()
                val seen = total.get()
                peak = max(peak, instantMbps(seen - lastBytes, now - lastAt))
                if (rampBytes < 0 && (now - started) / 1_000_000 >= rampMs) { rampBytes = seen; rampAt = now }
                samples++
                publish(ctx, prefix, phase, seen, started, now, peak, maxBytes, seconds)
                lastBytes = seen; lastAt = now
            }
        }

        val workers = (0 until streams).map { index ->
            async(Dispatchers.IO) {
                for (candidate in urls) {
                    try {
                        // Re-request until the deadline: one response is finite, the leg is not.
                        var served = false
                        while (isActive && !SpeedTestCancel.isRequested && total.get() < maxBytes && System.nanoTime() < deadline) {
                            runStream(open, candidate, phase, deadline, maxBytes, total, firstByte)
                            served = true
                        }
                        if (!served) runStream(open, candidate, phase, deadline, maxBytes, total, firstByte)
                        usedHost.compareAndSet("", hostOf(candidate))
                        return@async true
                    } catch (e: Exception) {
                        // Only the first stream's failure is worth reporting; the rest would repeat it.
                        if (index == 0) synchronized(failures) { failures += "${hostOf(candidate)}: ${e.message}" }
                    }
                }
                false
            }
        }
        val anyOk = workers.awaitAll().any { it }
        sampler.cancel()
        // Publish WHY an endpoint was skipped — BEFORE the throw, so a leg that fails everywhere still
        // says why. Swallowing this is how three runs got silently measured against a throttled mirror,
        // and how the first upload attempt failed with nothing to show for it.
        ctx.variables.set(
            "$prefix${phase.replaceFirstChar(Char::uppercase)}Fallback",
            failures.joinToString("; ").take(300),
        )
        if (!anyOk) throw java.io.IOException(failures.joinToString("; ").ifEmpty { "no endpoint reachable" })

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val latency = firstByte.get().takeIf { it > 0 }?.let { (it - started) / 1_000_000 } ?: 0L
        val endedAt = System.nanoTime()
        // Settled = after the ramp. Falls back to the whole leg when the run was too short to have one.
        val settledBytes = if (rampBytes >= 0) total.get() - rampBytes else total.get()
        val settledMs = if (rampBytes >= 0) (endedAt - rampAt) / 1_000_000 else elapsedMs
        val leg = Leg(total.get(), elapsedMs, peak, samples, latency, settledBytes, settledMs)
        publish(ctx, prefix, phase, total.get(), started, System.nanoTime(), peak, maxBytes, seconds)
        val cap = phase.replaceFirstChar(Char::uppercase)
        ctx.variables.set("$prefix${cap}Avg", fmt(leg.averageMbps))
        ctx.variables.set("$prefix${cap}Peak", fmt(leg.peakMbps))
        ctx.variables.set("$prefix${cap}AvgMB", mb(leg.averageMbps))
        ctx.variables.set("$prefix${cap}PeakMB", mb(leg.peakMbps))
        ctx.variables.set("$prefix${cap}Mb", fmt(total.get() / 1_000_000.0))
        ctx.variables.set("$prefix${cap}Ms", latency.toString())
        ctx.variables.set("$prefix${cap}Streams", streams.toString())
        ctx.variables.set("$prefix${cap}Raw", fmt(leg.rawMbps))
        ctx.variables.set("$prefix${cap}Host", usedHost.get())
        leg
    }

    /** One connection's share of a leg. Throws if this endpoint cannot be used at all. */
    private suspend fun runStream(
        open: (URL) -> HttpURLConnection,
        urlText: String,
        phase: String,
        deadline: Long,
        maxBytes: Long,
        total: java.util.concurrent.atomic.AtomicLong,
        firstByte: java.util.concurrent.atomic.AtomicLong,
    ) {
        val target = if (phase == "up") resolveRedirects(open, urlText) else urlText
        val conn = open(URL(target))
        conn.connectTimeout = 8_000
        conn.readTimeout = 12_000
        conn.useCaches = false
        try {
            val buffer = ByteArray(64 * 1024)
            if (phase == "up") {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setChunkedStreamingMode(buffer.size)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                java.util.Random(42).nextBytes(buffer)
                val out: OutputStream = conn.outputStream
                while (currentCoroutineContext().isActive && !SpeedTestCancel.isRequested &&
                    total.get() < maxBytes && System.nanoTime() < deadline
                ) {
                    out.write(buffer)
                    firstByte.compareAndSet(0L, System.nanoTime())
                    total.addAndGet(buffer.size.toLong())
                }
                out.flush()
                runCatching { out.close() }
                runCatching { conn.responseCode }
            } else {
                var code = conn.responseCode
                var hops = 0
                var active = conn
                while (code in 300..399 && hops < 3) {
                    val next = active.getHeaderField("Location")
                        ?: throw java.io.IOException("HTTP $code without Location")
                    runCatching { active.disconnect() }
                    // Cross-protocol (http -> https) is the whole reason this loop exists; the built-in
                    // follower drops it and hands back the 3xx.
                    active = open(URL(URL(urlText), next))
                    active.connectTimeout = 8_000
                    active.readTimeout = 12_000
                    active.useCaches = false
                    code = active.responseCode
                    hops++
                }
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
                firstByte.compareAndSet(0L, System.nanoTime())
                val input = active.inputStream
                while (currentCoroutineContext().isActive && !SpeedTestCancel.isRequested &&
                    total.get() < maxBytes && System.nanoTime() < deadline
                ) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total.addAndGet(read.toLong())
                }
                runCatching { input.close() }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * Walk 3xx hops with a zero-length probe and return the final URL.
     *
     * Needed only for uploads: the download path can follow a redirect mid-request, but a POST whose
     * body is already streaming cannot be replayed at the new location — the write just breaks. Every
     * Ookla server redirects its http upload.php to https, which is exactly this case.
     */
    private fun resolveRedirects(open: (URL) -> HttpURLConnection, urlText: String): String {
        var current = urlText
        repeat(3) {
            val conn = runCatching { open(URL(current)) }.getOrNull() ?: return current
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.useCaches = false
                conn.instanceFollowRedirects = false
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0)
                runCatching { conn.outputStream.close() }
                val code = conn.responseCode
                if (code !in 300..399) return current
                val next = conn.getHeaderField("Location") ?: return current
                current = URL(URL(current), next).toString()
            } catch (t: Throwable) {
                return current
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        return current
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    /** One Ookla server, as the public server-list API describes it. */
    private class OoklaServer(val downloadUrl: String, val uploadUrl: String, val label: String)

    /**
     * Ask Ookla for the servers nearest THIS client and take the closest.
     *
     * The list is ordered by distance from the caller's IP, so the choice tracks where 白い熊 actually
     * is — which is the whole point when the same test is run from different places. Returns null on
     * any failure; the static mirrors below then carry the leg.
     */
    private fun findNearestOoklaServer(): OoklaServer? = try {
        val conn = URL("https://www.speedtest.net/api/js/servers?engine=js&limit=5")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.setRequestProperty("Accept", "application/json")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val servers = org.json.JSONArray(body)
        if (servers.length() == 0) {
            null
        } else {
            val first = servers.getJSONObject(0)
            // `url` looks like http://host:8080/speedtest/upload.php — the download companion is the
            // random-image file beside it, which is how every Ookla client has fetched for years.
            val uploadUrl = first.getString("url")
            val downloadUrl = uploadUrl.replace("upload.php", "random4000x4000.jpg")
            OoklaServer(
                downloadUrl = downloadUrl,
                uploadUrl = uploadUrl,
                label = "${first.optString("sponsor")} ${first.optString("name")}".trim(),
            )
        }
    } catch (t: Throwable) {
        null
    }

    private fun publish(
        ctx: ActionContext,
        prefix: String,
        phase: String,
        total: Long,
        startedNs: Long,
        nowNs: Long,
        peak: Double,
        maxBytes: Long,
        seconds: Double,
    ) {
        val elapsedMs = (nowNs - startedNs) / 1_000_000
        val avg = if (elapsedMs <= 0) 0.0 else total * 8.0 / elapsedMs / 1000.0
        // Progress is whichever limit is closer to being hit — that IS the honest bar, since either
        // one ends the leg.
        val byPct = total.toDouble() / maxBytes
        val byTime = (nowNs - startedNs) / (seconds * 1_000_000_000L)
        ctx.variables.set("${prefix}Phase", phase)
        ctx.variables.set("${prefix}Cur", fmt(avg))
        ctx.variables.set("${prefix}Avg", fmt(avg))
        ctx.variables.set("${prefix}Peak", fmt(peak))
        ctx.variables.set("${prefix}CurMB", mb(avg))
        ctx.variables.set("${prefix}AvgMB", mb(avg))
        ctx.variables.set("${prefix}PeakMB", mb(peak))
        ctx.variables.set("${prefix}Mb", fmt(total / 1_000_000.0))
        ctx.variables.set("${prefix}Secs", fmt(elapsedMs / 1000.0))
        ctx.variables.set("${prefix}Pct", (max(byPct, byTime).coerceIn(0.0, 1.0) * 100).roundToInt().toString())
    }

    private fun publishSummary(ctx: ActionContext, prefix: String, down: Leg?, up: Leg?) {
        ctx.variables.set("${prefix}Phase", "done")
        ctx.variables.set("${prefix}Pct", "100")
        ctx.variables.set("${prefix}Samples", ((down?.samples ?: 0) + (up?.samples ?: 0)).toString())
        ctx.variables.set("${prefix}Ms", (down?.latencyMs ?: up?.latencyMs ?: 0L).toString())
    }

    // --- pinning a transport -------------------------------------------------------------------

    private class Pinned(val network: Network, val release: () -> Unit)

    /**
     * Hold a network of [transport] up for the duration of the test and hand back its Network handle.
     * Requires CHANGE_NETWORK_STATE (declared) — this does not change the system default route, so the
     * rest of the phone keeps using whatever it was using.
     */
    private suspend fun pinNetwork(
        cm: ConnectivityManager,
        transport: Int,
        onFailure: (String) -> Unit,
    ): Pinned? {
        val request = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val available = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!available.isCompleted) available.complete(network)
            }
        }
        return try {
            cm.requestNetwork(request, callback)
            val network = withTimeoutOrNull(15_000) { available.await() }
            if (network == null) {
                runCatching { cm.unregisterNetworkCallback(callback) }
                null
            } else {
                Pinned(network) { runCatching { cm.unregisterNetworkCallback(callback) } }
            }
        } catch (t: Throwable) {
            runCatching { cm.unregisterNetworkCallback(callback) }
            onFailure(
                "could not pin a ${transportName(transport)} network: " +
                    "${t.javaClass.simpleName}: ${t.message ?: "no detail"}",
            )
            null
        }
    }

    private fun transportName(transport: Int) =
        if (transport == NetworkCapabilities.TRANSPORT_WIFI) "WiFi" else "mobile"

    private fun instantMbps(bytes: Long, nanos: Long): Double =
        if (nanos <= 0) 0.0 else bytes * 8.0 * 1000.0 / nanos

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

    /** Megabits/s → 1024-based megabytes/s (MiB/s), the unit 白い熊 reads speeds in. */
    private fun mb(mbps: Double): String = fmt(mbps * 1_000_000.0 / 8.0 / 1_048_576.0)

    private companion object {
        /** Publish cadence. 250 ms is fast enough to look live and slow enough not to thrash the store. */
        const val SAMPLE_MS = 250L
    }
}
