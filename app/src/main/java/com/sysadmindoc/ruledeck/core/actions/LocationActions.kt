package com.sysadmindoc.ruledeck.core.actions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.sysadmindoc.ruledeck.core.engine.Action
import com.sysadmindoc.ruledeck.core.engine.ActionCategory
import com.sysadmindoc.ruledeck.core.engine.ActionContext
import com.sysadmindoc.ruledeck.core.engine.ActionResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * `Get Location` — put the device's current position into variables.
 *
 * Framework [LocationManager], not Play Services, matching the FOSS geofence source this app
 * already ships.
 *
 * A cached fix is used when it is fresh enough (`max_age_ms`), because a stationary phone that
 * just reported its position has nothing new to say and waiting on GPS would only cost time and
 * battery. Otherwise one update is requested and awaited up to `timeout_ms`.
 *
 * Two things taken from upstream's own Get Location (6f3a541c), which is not shipped because it
 * claims this action's id with other arguments:
 * - `accuracy` (metres): a cached fix worse than this is not accepted, and the wait goes on past
 *   the first fix until one this good arrives. On timeout the best fix seen is still published —
 *   this action's rule is "something rather than nothing" — and `<prefix>Acc` says how good it was.
 * - `hide`: the coordinates are stored as sensitive, so the run log and previews never show them,
 *   and the log line leaves them out. OFF by default, deliberately: a sensitive variable taints
 *   whatever is built from it and is left out of exports, which would break tasks that write the
 *   position into a file name or a URI.
 *
 * Publishes `<prefix>Lat`, `<prefix>Lon`, `<prefix>Acc` (metres), `<prefix>AgeMs`,
 * `<prefix>Provider` and `<prefix>Ok`. `Ok` is written in every outcome — including failure —
 * so a task can branch on it without having to treat "action failed" as flow control.
 */
class GetLocationAction : Action {
    override val id = "location.get"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim().orEmpty().ifEmpty { "LOC_" }
        val timeoutMs = args["timeout_ms"]?.trim()?.toLongOrNull()?.coerceIn(1_000L, 120_000L) ?: 20_000L
        val maxAgeMs = args["max_age_ms"]?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 120_000L
        val wanted = args["accuracy"]?.trim()?.takeIf(String::isNotEmpty)?.let { text ->
            text.toFloatOrNull()?.takeIf { it.isFinite() && it >= 1f }
                ?: return ActionResult.Failure("accuracy must be a number of metres, 1 or more")
        }
        val hide = isTruthy(args["hide"])

        fun publish(location: Location?, provider: String) {
            ctx.variables.set("${prefix}Ok", (location != null).toString())
            ctx.variables.set("${prefix}Lat", location?.latitude?.let { fmt6(it) } ?: "", sensitive = hide && location != null)
            ctx.variables.set("${prefix}Lon", location?.longitude?.let { fmt6(it) } ?: "", sensitive = hide && location != null)
            ctx.variables.set("${prefix}Acc", location?.accuracy?.let { fmt1(it.toDouble()) } ?: "")
            ctx.variables.set("${prefix}AgeMs", location?.let { ageMs(it).toString() } ?: "")
            ctx.variables.set("${prefix}Provider", location?.provider ?: provider)
        }

        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(ctx.app, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            publish(null, "")
            return ActionResult.Failure("location permission not granted — grant it in Setup")
        }

        val manager = ctx.app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            publish(null, "")
            return ActionResult.Failure("Android location service unavailable")
        }

        bestLastKnown(manager)?.takeIf { ageMs(it) <= maxAgeMs && meets(it, wanted) }?.let { cached ->
            publish(cached, cached.provider.orEmpty())
            ctx.logger("Location: cached fix from ${cached.provider}, ${ageMs(cached)} ms old")
            return ActionResult.Success
        }

        // The best fix the wait saw, kept outside it so a timeout does not throw it away.
        val seen = java.util.concurrent.atomic.AtomicReference<Location?>(null)
        val fresh = withTimeoutOrNull(timeoutMs) { awaitFix(manager, wanted, seen) }
        if (fresh == null && seen.get() != null) {
            val best = seen.get()!!
            publish(best, best.provider.orEmpty())
            ctx.logger("Location: no fix within ${fmt1(wanted?.toDouble() ?: 0.0)} m in ${timeoutMs} ms, using the best seen (±${fmt1(best.accuracy.toDouble())} m)")
            return ActionResult.Success
        }
        if (fresh == null) {
            // Fall back to a stale fix rather than nothing: a coordinate from ten minutes ago still
            // says which town this run happened in, which is the whole point of recording it.
            val stale = bestLastKnown(manager)
            publish(stale, "")
            return if (stale != null) {
                ctx.logger("Location: no fresh fix within ${timeoutMs} ms, using one ${ageMs(stale)} ms old")
                ActionResult.Success
            } else {
                ActionResult.Failure("no location fix within ${timeoutMs} ms — is location switched on?")
            }
        }
        publish(fresh, fresh.provider.orEmpty())
        ctx.logger(
            if (hide) "Location: a fix from ${fresh.provider} (±${fmt1(fresh.accuracy.toDouble())} m), coordinates hidden"
            else "Location: ${fmt6(fresh.latitude)}, ${fmt6(fresh.longitude)} (±${fmt1(fresh.accuracy.toDouble())} m)",
        )
        return ActionResult.Success
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(manager: LocationManager): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .minByOrNull { ageMs(it) }

    /** Whether [location] is as good as [wanted] metres; any fix is when nothing was asked for. */
    private fun meets(location: Location, wanted: Float?): Boolean =
        wanted == null || (location.hasAccuracy() && location.accuracy <= wanted)

    private fun isTruthy(value: String?): Boolean =
        value?.trim()?.lowercase() in setOf("true", "1", "on", "yes")

    /**
     * The first update good enough for [wanted] from whichever enabled provider gives one; with no
     * accuracy asked for, simply the first. Every fix that is better than the last goes into [seen].
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitFix(
        manager: LocationManager,
        wanted: Float?,
        seen: java.util.concurrent.atomic.AtomicReference<Location?>,
    ): Location? =
        suspendCancellableCoroutine { continuation ->
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            if (providers.isEmpty()) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    seen.updateAndGet { best ->
                        if (best == null || !best.hasAccuracy() || (location.hasAccuracy() && location.accuracy <= best.accuracy)) location else best
                    }
                    if (!meets(location, wanted)) return
                    runCatching { manager.removeUpdates(this) }
                    if (continuation.isActive) continuation.resume(location)
                }

                // The three-arg overloads are abstract on older API levels; harmless no-ops here.
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            providers.forEach { provider ->
                runCatching { manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
            }
            continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
        }

    private fun ageMs(location: Location): Long =
        (System.currentTimeMillis() - location.time).coerceAtLeast(0L)

    /** Six decimals is ~0.1 m — past the point any phone's fix is meaningful, and short enough to read. */
    private fun fmt6(value: Double): String = String.format(java.util.Locale.US, "%.6f", value)

    private fun fmt1(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}
