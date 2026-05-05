package com.opentasker.core.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90." }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180." }
    }
}

data class FossGeofenceSpec(
    val center: GeoPoint,
    val radiusMeters: Double,
    val maxAccuracyMeters: Double? = null,
    val dwellMillis: Long = 0L,
) {
    init {
        require(radiusMeters >= 0.0) { "Radius must be non-negative." }
        require(maxAccuracyMeters == null || maxAccuracyMeters >= 0.0) { "Max accuracy must be non-negative." }
        require(dwellMillis >= 0L) { "Dwell time must be non-negative." }
    }
}

data class LocationSample(
    val point: GeoPoint,
    val accuracyMeters: Double? = null,
    val observedAtEpochMs: Long? = null,
    val insideSinceEpochMs: Long? = null,
)

data class GeofenceEvaluation(
    val distanceMeters: Double,
    val withinRadius: Boolean,
    val accuracyAccepted: Boolean,
    val dwellSatisfied: Boolean,
    val blockReason: GeofenceBlockReason? = null,
) {
    val matches: Boolean
        get() = withinRadius && accuracyAccepted && dwellSatisfied
}

enum class GeofenceBlockReason {
    OutsideRadius,
    AccuracyTooLow,
    DwellNotSatisfied,
}

object FossGeofenceEvaluator {
    fun evaluate(spec: FossGeofenceSpec, sample: LocationSample): GeofenceEvaluation {
        val distance = distanceMeters(
            sample.point.latitude,
            sample.point.longitude,
            spec.center.latitude,
            spec.center.longitude,
        )
        val withinRadius = distance <= spec.radiusMeters
        if (!withinRadius) {
            return GeofenceEvaluation(
                distanceMeters = distance,
                withinRadius = false,
                accuracyAccepted = true,
                dwellSatisfied = spec.dwellMillis == 0L,
                blockReason = GeofenceBlockReason.OutsideRadius,
            )
        }

        val accuracyAccepted = spec.maxAccuracyMeters?.let { maxAccuracy ->
            sample.accuracyMeters?.let { it <= maxAccuracy } ?: false
        } ?: true
        if (!accuracyAccepted) {
            return GeofenceEvaluation(
                distanceMeters = distance,
                withinRadius = true,
                accuracyAccepted = false,
                dwellSatisfied = spec.dwellMillis == 0L,
                blockReason = GeofenceBlockReason.AccuracyTooLow,
            )
        }

        val dwellSatisfied = if (spec.dwellMillis == 0L) {
            true
        } else {
            val observedAt = sample.observedAtEpochMs
            val insideSince = sample.insideSinceEpochMs
            observedAt != null && insideSince != null && observedAt >= insideSince && observedAt - insideSince >= spec.dwellMillis
        }

        return GeofenceEvaluation(
            distanceMeters = distance,
            withinRadius = true,
            accuracyAccepted = true,
            dwellSatisfied = dwellSatisfied,
            blockReason = if (dwellSatisfied) null else GeofenceBlockReason.DwellNotSatisfied,
        )
    }

    fun evaluate(config: Map<String, String>, metadata: Map<String, String>): GeofenceEvaluation? {
        val centerLat = first(config, "latitude", "lat").toDoubleOrNull() ?: return null
        val centerLon = first(config, "longitude", "lon", "lng").toDoubleOrNull() ?: return null
        val radiusMeters = first(config, "radiusMeters", "radius").toDoubleOrNull()?.takeIf { it >= 0.0 } ?: return null
        val currentLat = first(metadata, "latitude", "lat").toDoubleOrNull() ?: return null
        val currentLon = first(metadata, "longitude", "lon", "lng").toDoubleOrNull() ?: return null
        val spec = FossGeofenceSpec(
            center = GeoPoint(centerLat, centerLon),
            radiusMeters = radiusMeters,
            maxAccuracyMeters = first(config, "maxAccuracyMeters", "maxAccuracy").toDoubleOrNull()?.takeIf { it >= 0.0 },
            dwellMillis = parseDwellMillis(config),
        )
        val sample = LocationSample(
            point = GeoPoint(currentLat, currentLon),
            accuracyMeters = first(metadata, "accuracyMeters", "accuracy").toDoubleOrNull()?.takeIf { it >= 0.0 },
            observedAtEpochMs = first(metadata, "observedAtEpochMs", "timestampEpochMs", "nowEpochMs").toLongOrNull(),
            insideSinceEpochMs = first(metadata, "insideSinceEpochMs", "enteredAtEpochMs").toLongOrNull(),
        )
        return evaluate(spec, sample)
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    private fun parseDwellMillis(config: Map<String, String>): Long {
        val millis = first(config, "dwellMillis", "dwellMs").toLongOrNull()
        if (millis != null) return millis.coerceAtLeast(0L)
        val seconds = first(config, "dwellSeconds", "dwellSec").toLongOrNull()
        return seconds?.coerceAtLeast(0L)?.times(1_000L) ?: 0L
    }

    private fun first(values: Map<String, String>, vararg keys: String): String =
        keys.firstNotNullOfOrNull { values[it]?.trim()?.takeIf(String::isNotBlank) }.orEmpty()

    private const val EARTH_RADIUS_METERS = 6_371_000.0
}
