package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import java.util.Calendar
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Applies a persisted [ContextSpec] to raw context-source events.
 *
 * Context sources emit coarse device facts. Profiles store user intent. Keeping
 * this translation in one place prevents broad source events from accidentally
 * activating every profile of the same family.
 */
object ContextMatchEvaluator {
    fun sourceKey(type: ContextType): String? = when (type) {
        ContextType.APPLICATION -> "app"
        ContextType.TIME -> "time"
        ContextType.DAY -> "time"
        ContextType.LOCATION -> "location"
        ContextType.STATE -> "state"
        ContextType.EVENT -> "event"
    }

    fun matches(spec: ContextSpec, event: ContextEvent): Boolean {
        if (!event.matched) return false
        val expectedSource = sourceKey(spec.type) ?: return false
        if (event.type != expectedSource) return false

        return when (spec.type) {
            ContextType.APPLICATION -> matchesApplication(spec, event)
            ContextType.TIME -> matchesTime(spec, event)
            ContextType.DAY -> matchesDay(spec, event)
            ContextType.LOCATION -> matchesLocation(spec, event)
            ContextType.STATE -> matchesState(spec, event)
            ContextType.EVENT -> matchesEvent(spec, event)
        }
    }

    private fun matchesApplication(spec: ContextSpec, event: ContextEvent): Boolean {
        val foreground = event.metadata["foreground"].orEmpty().ifBlank { event.metadata["package"].orEmpty() }
        if (foreground.isBlank()) return false
        val configuredPackages = firstConfig(spec, "package", "packages", "apps")
            .splitCsv()
            .map { it.lowercase(Locale.US) }
        if (configuredPackages.isEmpty()) return false
        return foreground.lowercase(Locale.US) in configuredPackages
    }

    private fun matchesTime(spec: ContextSpec, event: ContextEvent): Boolean {
        val start = firstConfig(spec, "start", "from").takeIf { it.isNotBlank() } ?: return false
        val end = firstConfig(spec, "end", "to").takeIf { it.isNotBlank() } ?: return false
        val current = event.metadata["time"]?.let(::parseClockMinutes) ?: currentMinuteOfDay()
        val startMinutes = parseClockMinutes(start) ?: return false
        val endMinutes = parseClockMinutes(end) ?: return false
        return minuteInWindow(current, startMinutes, endMinutes)
    }

    private fun matchesDay(spec: ContextSpec, event: ContextEvent): Boolean {
        val configuredDays = firstConfig(spec, "days", "day").splitCsv()
            .mapNotNull(::normalizeDayToken)
            .toSet()
        if (configuredDays.isEmpty()) return false
        val currentDay = event.metadata["day"]?.let(::normalizeDayToken) ?: currentDayToken()
        return currentDay in configuredDays
    }

    private fun matchesLocation(spec: ContextSpec, event: ContextEvent): Boolean {
        val currentLat = event.metadata["latitude"]?.toDoubleOrNull() ?: return false
        val currentLon = event.metadata["longitude"]?.toDoubleOrNull() ?: return false
        val centerLat = firstConfig(spec, "latitude", "lat").toDoubleOrNull() ?: return false
        val centerLon = firstConfig(spec, "longitude", "lon", "lng").toDoubleOrNull() ?: return false
        val radiusMeters = firstConfig(spec, "radiusMeters", "radius").toDoubleOrNull()
            ?.takeIf { it >= 0.0 }
            ?: return false
        return distanceMeters(currentLat, currentLon, centerLat, centerLon) <= radiusMeters
    }

    private fun matchesState(spec: ContextSpec, event: ContextEvent): Boolean {
        val predicate = spec.config["predicate"]?.trim().orEmpty()
        if (predicate.isNotBlank()) return stateMatches(predicate, event.metadata)

        val key = spec.config["key"]?.trim().orEmpty()
        val expectedValue = spec.config["value"]?.trim().orEmpty()
        if (key.isBlank() || expectedValue.isBlank()) return false
        return stateMatches("${normalizeStateKey(key)}=$expectedValue", event.metadata)
    }

    private fun matchesEvent(spec: ContextSpec, event: ContextEvent): Boolean {
        val actualEvent = event.metadata["event"].orEmpty()
        val expectedEvent = spec.config["event"]?.trim().orEmpty()
        if (expectedEvent.isNotBlank() && !actualEvent.equals(expectedEvent, ignoreCase = true)) {
            return false
        }

        val filter = spec.config["filter"]?.trim().orEmpty()
        if (filter.isBlank()) return actualEvent.isNotBlank()
        return event.metadata.values.any { it.contains(filter, ignoreCase = true) }
    }

    private fun firstConfig(spec: ContextSpec, vararg keys: String): String =
        keys.firstNotNullOfOrNull { spec.config[it]?.trim()?.takeIf(String::isNotBlank) }.orEmpty()

    private fun String.splitCsv(): List<String> =
        split(',', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun parseClockMinutes(value: String): Int? {
        val parts = value.trim().split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun currentMinuteOfDay(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    private fun minuteInWindow(current: Int?, start: Int, end: Int): Boolean {
        current ?: return false
        return if (start <= end) current in start..end else current >= start || current <= end
    }

    private fun normalizeDayToken(value: String): String? = when (value.trim().uppercase(Locale.US)) {
        "SUN", "SUNDAY", "0", "7" -> "SUN"
        "MON", "MONDAY", "1" -> "MON"
        "TUE", "TUESDAY", "2" -> "TUE"
        "WED", "WEDNESDAY", "3" -> "WED"
        "THU", "THURSDAY", "4" -> "THU"
        "FRI", "FRIDAY", "5" -> "FRI"
        "SAT", "SATURDAY", "6" -> "SAT"
        else -> null
    }

    private fun currentDayToken(): String {
        val calendar = Calendar.getInstance()
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "SUN"
            Calendar.MONDAY -> "MON"
            Calendar.TUESDAY -> "TUE"
            Calendar.WEDNESDAY -> "WED"
            Calendar.THURSDAY -> "THU"
            Calendar.FRIDAY -> "FRI"
            Calendar.SATURDAY -> "SAT"
            else -> "SUN"
        }
    }

    private fun normalizeStateKey(key: String): String = when (key.lowercase(Locale.US)) {
        "battery" -> "battery_level"
        "wifi" -> "wifi"
        "headset" -> "headphones"
        else -> key
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeters * c
    }
}
