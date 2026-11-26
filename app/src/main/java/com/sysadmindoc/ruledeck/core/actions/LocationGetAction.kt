package com.sysadmindoc.ruledeck.core.actions

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.sysadmindoc.ruledeck.core.contexts.LocationListenerRegistrationState
import com.sysadmindoc.ruledeck.core.contexts.currentOnlyLocationListener
import com.sysadmindoc.ruledeck.core.contexts.enabledLocationProviders
import com.sysadmindoc.ruledeck.core.contexts.hasForegroundLocationAccess
import com.sysadmindoc.ruledeck.core.contexts.replaceProviders
import com.sysadmindoc.ruledeck.core.engine.ActionContext
import com.sysadmindoc.ruledeck.core.engine.ActionResult
import com.sysadmindoc.ruledeck.core.location.LocationPolicyDisclosures
import com.sysadmindoc.ruledeck.core.model.VariableNamePolicy
import java.math.BigDecimal
import kotlin.math.roundToLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** A position as Get Location reports it. */
internal data class LocationReading(
    val latitude: Double,
    val longitude: Double,
    /** Meters, or null when the provider didn't say. */
    val accuracyMeters: Double?,
    /** How long ago the fix was taken. */
    val ageMs: Long,
    val provider: String?,
)

/** The platform calls Get Location makes, so a test can stand in for Android's location service. */
internal interface LocationReadings {
    /** The providers the Location context would listen to, in its order. */
    fun enabledProviders(): List<String>

    fun lastKnown(provider: String): LocationReading?

    /** Starts updates on [providers] and returns what stops them. [onFix] can run on any thread. */
    fun start(providers: List<String>, onFix: (LocationReading) -> Unit): () -> Unit
}

/**
 * Reads the phone's position into `%location_lat`, `_lon`, `_accuracy` (meters) and `_age`
 * (seconds since the fix). Args: `mode` (`fix`, the default, asks the GPS and network providers for
 * a fresh fix; `last_known` only reads what the phone already has and starts nothing), `accuracy`
 * (meters a fix has to be good to, any by default), `timeout` (seconds to wait for a fresh fix) and
 * `var`, the prefix.
 *
 * Framework LocationManager only, through the same provider list and listener bookkeeping as the
 * Location context. The coordinates say where the phone is, so they're stored as sensitive, the way
 * contact lookups and Wi-Fi BSSIDs are: a task can send them anywhere it likes, and the run log,
 * previews and the log line never show them.
 */
class LocationGetAction internal constructor(
    private val permitted: (Context) -> Boolean,
    private val readings: (Context) -> LocationReadings?,
) : DeclaredAction(ActionCatalog.require("location.get")) {

    constructor() : this(::hasForegroundLocationAccess, ::platformLocationReadings)

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["var"].orEmpty().ifBlank { DEFAULT_PREFIX }.let(VariableNamePolicy::normalize)
            ?.takeIf { VariableNamePolicy.normalize(it + LONGEST_SUFFIX) != null }
            ?: return ActionResult.Failure("invalid variable prefix")
        val mode = args["mode"]?.trim()?.lowercase().orEmpty().ifBlank { MODE_FIX }
        if (mode != MODE_FIX && mode != MODE_LAST_KNOWN) {
            return ActionResult.Failure("mode must be $MODE_FIX or $MODE_LAST_KNOWN")
        }
        val wanted = args["accuracy"]?.trim()?.takeIf(String::isNotEmpty)?.let { text ->
            text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1.0 }
                ?: return ActionResult.Failure("accuracy must be a number of meters, 1 or more")
        }
        val timeoutSeconds = args["timeout"]?.trim()?.takeIf(String::isNotEmpty)?.let { text ->
            text.toLongOrNull()?.takeIf { it in 1L..MAX_TIMEOUT_SECONDS }
                ?: return ActionResult.Failure("timeout must be a whole number of seconds from 1 to $MAX_TIMEOUT_SECONDS")
        } ?: DEFAULT_TIMEOUT_SECONDS

        // Checked before the location service is even looked up, so a missing grant starts nothing.
        if (!permitted(ctx.app)) return ActionResult.Failure(LocationPolicyDisclosures.actionPermissionMissing)
        val source = readings(ctx.app)
            ?: return ActionResult.Failure("location.get couldn't reach Android's location service")
        val providers = source.enabledProviders()
        if (providers.none { it != LocationManager.PASSIVE_PROVIDER }) {
            return ActionResult.Failure("location.get found GPS and network location both switched off, so no fix can come. Turn Location on.")
        }

        val fix = if (mode == MODE_LAST_KNOWN) {
            providers.mapNotNull { provider -> runCatching { source.lastKnown(provider) }.getOrNull() }
                .filter { it.meets(wanted) }
                .minByOrNull(LocationReading::ageMs)
                ?: return ActionResult.Failure(
                    "location.get found no last known location${wanted?.let { " within ${meters(it)} m" }.orEmpty()}. " +
                        "Pick Fresh fix to ask for one.",
                )
        } else {
            when (val outcome = freshFix(source, providers, wanted, timeoutSeconds * 1000L)) {
                is FreshFix.Got -> outcome.reading
                is FreshFix.CouldNotStart -> return ActionResult.Failure("location.get couldn't start location updates: ${outcome.reason}")
                is FreshFix.TimedOut -> return ActionResult.Failure(timeoutMessage(timeoutSeconds, wanted, outcome))
            }
        }

        ctx.variables.set("${prefix}_lat", plain(fix.latitude), sensitive = true)
        ctx.variables.set("${prefix}_lon", plain(fix.longitude), sensitive = true)
        ctx.variables.set("${prefix}_accuracy", fix.accuracyMeters?.roundToLong()?.toString().orEmpty())
        ctx.variables.set("${prefix}_age", (fix.ageMs.coerceAtLeast(0L) / 1000L).toString())
        // Never the coordinates: they say where the phone is.
        ctx.logger(
            "location.get -> %${prefix}_lat, _lon, _accuracy and _age from ${fix.provider ?: "an unnamed provider"}" +
                (fix.accuracyMeters?.let { ", good to ${it.roundToLong()} m" } ?: ", no accuracy given") +
                ", ${fix.ageMs.coerceAtLeast(0L) / 1000L} s old",
        )
        return ActionResult.Success
    }

    private sealed interface FreshFix {
        data class Got(val reading: LocationReading) : FreshFix
        data class CouldNotStart(val reason: String) : FreshFix
        data class TimedOut(val closestMeters: Double?, val staleAgeMs: Long?) : FreshFix
    }

    /**
     * The first fix good enough, or what came closest when none is in time. Updates always stop.
     * A fix older than [MAX_FRESH_AGE_MS] doesn't count: a new listener can be handed the last fix
     * Android had, however old (an emulator's GPS replayed one four minutes old), and Fresh fix
     * promises one taken now. Android's own getCurrentLocation draws the line at the same 30 s.
     */
    private suspend fun freshFix(
        source: LocationReadings,
        providers: List<String>,
        wanted: Double?,
        timeoutMs: Long,
    ): FreshFix {
        val lock = Any()
        var closest: Double? = null
        var staleAgeMs: Long? = null
        val good = CompletableDeferred<LocationReading>()
        val stop = try {
            source.start(providers) { reading ->
                when {
                    reading.ageMs > MAX_FRESH_AGE_MS ->
                        synchronized(lock) { staleAgeMs = minOf(staleAgeMs ?: reading.ageMs, reading.ageMs) }
                    reading.meets(wanted) -> good.complete(reading)
                    else -> reading.accuracyMeters?.let { accuracy ->
                        synchronized(lock) { closest = minOf(closest ?: accuracy, accuracy) }
                    }
                }
            }
        } catch (error: RuntimeException) {
            // A grant revoked since the check, or a provider that went away.
            return FreshFix.CouldNotStart(error.message ?: error::class.java.simpleName)
        }
        return try {
            withTimeoutOrNull(timeoutMs) { good.await() }?.let { FreshFix.Got(it) }
                ?: synchronized(lock) { FreshFix.TimedOut(closest, staleAgeMs) }
        } finally {
            stop()
        }
    }

    private fun timeoutMessage(timeoutSeconds: Long, wanted: Double?, outcome: FreshFix.TimedOut): String {
        val closestMeters = outcome.closestMeters
        val got = if (wanted != null && closestMeters != null) {
            "location.get got no fix within ${meters(wanted)} m in $timeoutSeconds s; the closest was ${closestMeters.roundToLong()} m."
        } else {
            "location.get got no fix in $timeoutSeconds s."
        }
        val stale = outcome.staleAgeMs?.let {
            " Android only offered one from ${it / 1000L} s ago. Pick Last known only to take that."
        }.orEmpty()
        return "$got$stale ${LocationPolicyDisclosures.actionBackgroundNote}"
    }

    companion object {
        internal const val DEFAULT_PREFIX = "location"
        internal const val MODE_FIX = "fix"
        internal const val MODE_LAST_KNOWN = "last_known"
        internal const val DEFAULT_TIMEOUT_SECONDS = 30L
        internal const val MAX_TIMEOUT_SECONDS = 120L
        internal const val MAX_FRESH_AGE_MS = 30_000L
        private const val LONGEST_SUFFIX = "_accuracy"

        private fun LocationReading.meets(wanted: Double?): Boolean =
            wanted == null || (accuracyMeters != null && accuracyMeters <= wanted)

        /** Decimal text with no exponent, so a coordinate near zero doesn't read as 5.0E-5. */
        private fun plain(value: Double): String = BigDecimal.valueOf(value).toPlainString()

        private fun meters(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}

internal fun platformLocationReadings(context: Context): LocationReadings? =
    context.getSystemService(LocationManager::class.java)?.let(::PlatformLocationReadings)

private class PlatformLocationReadings(private val manager: LocationManager) : LocationReadings {
    override fun enabledProviders(): List<String> = enabledLocationProviders(manager)

    @SuppressLint("MissingPermission")
    override fun lastKnown(provider: String): LocationReading? = try {
        manager.getLastKnownLocation(provider)?.toReading()
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    @SuppressLint("MissingPermission")
    override fun start(providers: List<String>, onFix: (LocationReading) -> Unit): () -> Unit {
        val lock = Any()
        val registration = LocationListenerRegistrationState { listener -> runCatching { manager.removeUpdates(listener) } }
        val listener = currentOnlyLocationListener(registration, lock) { location -> onFix(location.toReading()) }
        registration.replaceProviders(providers.toSet(), listener) { provider, registered ->
            manager.requestLocationUpdates(provider, 0L, 0f, registered, Looper.getMainLooper())
        }
        return { synchronized(lock) { registration.stop() } }
    }

    private fun Location.toReading(): LocationReading = LocationReading(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        ageMs = if (elapsedRealtimeNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - time
        }.coerceAtLeast(0L),
        provider = provider,
    )
}
