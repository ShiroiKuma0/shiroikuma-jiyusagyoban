package com.opentasker.core.contexts

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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * FOSS platform location source for Location contexts.
 *
 * This deliberately uses Android's framework LocationManager instead of Play
 * Services. It emits raw location samples; geofence matching and dwell logic
 * stay in [com.opentasker.core.location.FossGeofenceEvaluator].
 */
class LocationContextSourceImpl(
    private val minTimeMs: Long = DEFAULT_MIN_TIME_MS,
    private val minDistanceMeters: Float = DEFAULT_MIN_DISTANCE_METERS,
) : ContextSource {
    override val type = LocationContextEvents.TYPE

    override fun events(app: Context): Flow<ContextEvent> = callbackFlow {
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            trySend(
                LocationContextEvents.setupBlocked(
                    reason = "location_service_unavailable",
                    detail = "Android location service is unavailable on this device.",
                ),
            )
            awaitClose { }
            return@callbackFlow
        }

        var activeListener: LocationListener? = null
        var activeProviders: Set<String> = emptySet()

        fun stopUpdates() {
            activeListener?.let { listener ->
                runCatching { locationManager.removeUpdates(listener) }
            }
            activeListener = null
            activeProviders = emptySet()
        }

        fun emitBlocked(reason: String, detail: String) {
            trySend(LocationContextEvents.setupBlocked(reason, detail))
        }

        @SuppressLint("MissingPermission")
        fun emitBestLastKnown(providers: List<String>): Boolean {
            val best = providers
                .mapNotNull { provider ->
                    runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                }
                .maxByOrNull { it.time }
                ?: return false
            trySend(LocationContextEvents.location(best.toPlatformSample()))
            return true
        }

        @SuppressLint("MissingPermission")
        fun startUpdatesIfReady() {
            if (!hasLocationPermission(app)) {
                stopUpdates()
                emitBlocked(
                    reason = "missing_location_permission",
                    detail = "Foreground location permission is required before OpenTasker can emit live location context values.",
                )
                return
            }

            val providers = enabledLocationProviders(locationManager)
            if (providers.isEmpty()) {
                stopUpdates()
                emitBlocked(
                    reason = "location_provider_disabled",
                    detail = "No GPS or network location provider is enabled for live location context checks.",
                )
                return
            }

            val providerSet = providers.toSet()
            if (activeListener != null && activeProviders == providerSet) return

            stopUpdates()
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    trySend(LocationContextEvents.location(location.toPlatformSample()))
                }

                override fun onProviderEnabled(provider: String) {
                    emitBlocked(
                        reason = "location_provider_enabled",
                        detail = "$provider location provider is available; waiting for the next location fix.",
                    )
                }

                override fun onProviderDisabled(provider: String) {
                    emitBlocked(
                        reason = "location_provider_disabled",
                        detail = "$provider location provider was disabled.",
                    )
                }

                @Deprecated("Deprecated in Android framework")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }

            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    minTimeMs,
                    minDistanceMeters,
                    listener,
                    Looper.getMainLooper(),
                )
            }
            activeListener = listener
            activeProviders = providerSet

            if (!emitBestLastKnown(providers)) {
                trySend(
                    ContextEvent(
                        type,
                        false,
                        mapOf(
                            "setup" to "waiting_for_location",
                            "reason" to "Live location source is registered and waiting for the first GPS or network fix.",
                            "providers" to providers.joinToString(","),
                        ),
                    ),
                )
            }
        }

        val monitorJob = launch {
            while (isActive) {
                runCatching { startUpdatesIfReady() }
                    .onFailure { error ->
                        stopUpdates()
                        emitBlocked(
                            reason = "location_source_error",
                            detail = error.message ?: error::class.java.simpleName,
                        )
                    }
                delay(SETUP_RECHECK_MS)
            }
        }

        awaitClose {
            monitorJob.cancel()
            stopUpdates()
        }
    }

    private fun Location.toPlatformSample(): PlatformLocationSample =
        PlatformLocationSample(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
            observedAtEpochMs = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            provider = provider,
        )

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enabledLocationProviders(locationManager: LocationManager): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false) }

    companion object {
        private const val DEFAULT_MIN_TIME_MS = 60_000L
        private const val DEFAULT_MIN_DISTANCE_METERS = 50f
        private const val SETUP_RECHECK_MS = 30_000L
    }
}
