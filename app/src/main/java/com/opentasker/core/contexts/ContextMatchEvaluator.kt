package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.location.FossGeofenceEvaluator
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

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
        ContextType.PLUGIN -> "plugin"
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
            ContextType.PLUGIN -> matchesPlugin(spec, event)
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
        val configuredDays = firstConfig(spec, "days", "day")
        if (configuredDays.isBlank()) return false
        val currentDay = event.metadata["day"] ?: DaySchedule.currentDayToken()
        return DaySchedule.matches(configuredDays, currentDay)
    }

    private fun matchesLocation(spec: ContextSpec, event: ContextEvent): Boolean {
        return FossGeofenceEvaluator.evaluate(spec.config, event.metadata)?.matches == true
    }

    private fun matchesState(spec: ContextSpec, event: ContextEvent): Boolean {
        val predicate = spec.config["predicate"]?.trim().orEmpty()
        if (predicate.isNotBlank()) return stateMatches(predicate, event.metadata)

        val key = spec.config["key"]?.trim().orEmpty()
        val operator = spec.config["operator"]?.trim()?.takeIf { it.isNotBlank() } ?: "="
        val expectedValue = spec.config["value"]?.trim().orEmpty()
        if (key.isBlank() || expectedValue.isBlank()) return false
        return stateMatches("$key$operator$expectedValue", event.metadata)
    }

    private fun matchesEvent(spec: ContextSpec, event: ContextEvent): Boolean {
        val actualEvent = event.metadata["event"].orEmpty()
        val expectedEvent = spec.config["event"]?.trim().orEmpty()
        if (expectedEvent.isMinuteTick()) {
            return matchesMinuteTick(spec, event)
        }
        if (expectedEvent.equals("sec_tick", ignoreCase = true)) {
            return matchesSecTick(spec, event)
        }
        if (expectedEvent.isSunEvent()) {
            return matchesSunEvent(spec, event, expectedEvent)
        }
        if (expectedEvent.isNotBlank() && !actualEvent.equals(expectedEvent, ignoreCase = true)) {
            return false
        }

        val expectedStates = firstConfig(spec, "state", "calendarState")
            .splitCsv()
            .map { it.lowercase(Locale.US) }
            .toSet()
        if (expectedStates.isNotEmpty()) {
            val actualState = event.metadata["state"].orEmpty().lowercase(Locale.US)
            if (actualState !in expectedStates) return false
        }

        val calendarAllowlist = firstConfig(spec, "calendar", "calendars")
            .splitCsv()
            .map { it.lowercase(Locale.US) }
            .toSet()
        if (calendarAllowlist.isNotEmpty()) {
            val actualCalendar = event.metadata["calendar"].orEmpty().lowercase(Locale.US)
            if (actualCalendar !in calendarAllowlist) return false
        }

        val beforeMinutes = firstConfig(spec, "beforeMinutes", "withinMinutes").toIntOrNull()
        if (beforeMinutes != null) {
            val minutesUntilStart = event.metadata["minutesUntilStart"]?.toIntOrNull() ?: return false
            if (minutesUntilStart !in 0..beforeMinutes) return false
        }

        spec.config["allDay"]?.toBooleanStrictOrNull()?.let { expectedAllDay ->
            val actualAllDay = event.metadata["allDay"]?.toBooleanStrictOrNull() ?: return false
            if (actualAllDay != expectedAllDay) return false
        }

        val packageAllowlist = firstConfig(spec, "package", "packages", "apps")
            .splitCsv()
            .map { it.lowercase(Locale.US) }
            .toSet()
        if (packageAllowlist.isNotEmpty()) {
            val actualPackage = event.metadata["package"].orEmpty().lowercase(Locale.US)
            if (actualPackage !in packageAllowlist) return false
        }

        // Device-orientation trigger: optionally narrow to specific quadrant(s) (portrait / landscape /
        // reverse-portrait / reverse-landscape). Omit to fire on any orientation change.
        val expectedOrientations = firstConfig(spec, "orientation", "orientations")
            .splitCsv()
            .map { it.lowercase(Locale.US) }
            .toSet()
        if (expectedOrientations.isNotEmpty()) {
            val actualOrientation = event.metadata["orientation"].orEmpty().lowercase(Locale.US)
            if (actualOrientation !in expectedOrientations) return false
        }

        // Hardware-key trigger (event=hardware_key): narrow by key (volume_up / volume_down / power)
        // and/or press type (short / long / double). Omit either to fire on any value.
        val expectedKeys = firstConfig(spec, "key", "keys")
            .splitCsv()
            .map { it.lowercase(Locale.US) }
            .toSet()
        if (expectedKeys.isNotEmpty()) {
            val actualKey = event.metadata["key"].orEmpty().lowercase(Locale.US)
            if (actualKey !in expectedKeys) return false
        }
        val expectedPresses = firstConfig(spec, "press", "presses")
            .splitCsv()
            .map { it.lowercase(Locale.US) }
            .toSet()
        if (expectedPresses.isNotEmpty()) {
            val actualPress = event.metadata["press"].orEmpty().lowercase(Locale.US)
            if (actualPress !in expectedPresses) return false
        }

        // Broadcast (Intent Received) trigger: narrow by the intent action(s). Case-sensitive.
        val configuredActions = firstConfig(spec, "action", "actions")
            .splitCsv()
            .filter { it.isNotBlank() }
            .toSet()
        if (configuredActions.isNotEmpty()) {
            val actualAction = event.metadata["action"].orEmpty()
            if (actualAction !in configuredActions) return false
        }

        val configuredTagIds = firstConfig(spec, "tagId", "tagIds", "tag")
            .splitCsv()
            .map(NfcContextEvents::normalizeTagId)
            .filter { it.isNotBlank() }
            .toSet()
        if (configuredTagIds.isNotEmpty()) {
            val actualTagId = NfcContextEvents.normalizeTagId(event.metadata["tagId"].orEmpty())
            if (actualTagId !in configuredTagIds) return false
        }

        val regex = spec.config["regex"]?.toBooleanStrictOrNull() ?: false
        val titleFilter = spec.config["title"]?.trim().orEmpty()
        if (titleFilter.isNotBlank() && !textMatches(event.metadata["title"].orEmpty(), titleFilter, regex)) {
            return false
        }
        val bodyFilter = spec.config["body"]?.trim().orEmpty()
        if (bodyFilter.isNotBlank() && !textMatches(event.metadata["body"].orEmpty(), bodyFilter, regex)) {
            return false
        }

        val filter = spec.config["filter"]?.trim().orEmpty()
        if (filter.isBlank()) return actualEvent.isNotBlank()
        return event.metadata.values.any { textMatches(it, filter, regex) }
    }

    private fun matchesPlugin(spec: ContextSpec, event: ContextEvent): Boolean {
        val expectedPackage = spec.config["package"]?.trim().orEmpty()
        if (expectedPackage.isBlank()) return false
        val actualPackage = event.metadata["package"].orEmpty()
        if (!actualPackage.equals(expectedPackage, ignoreCase = true)) return false
        val expectedBundle = spec.config["bundleJson"]?.trim().orEmpty().ifBlank { "{}" }
        val actualBundle = event.metadata["bundleJson"].orEmpty().ifBlank { "{}" }
        if (expectedBundle != actualBundle) return false
        return event.metadata["state"].equals("satisfied", ignoreCase = true)
    }

    /**
     * A periodic clock tick. Rides the internal per-minute `sun_tick` pulse, so it fires once every
     * minute (the natural cadence for a clock widget); an optional `everyMinutes`/`interval` config
     * fires only every N minutes, aligned to the top of the hour.
     */
    private fun matchesMinuteTick(spec: ContextSpec, event: ContextEvent): Boolean {
        if (!event.metadata["event"].orEmpty().equals("sun_tick", ignoreCase = true)) return false
        val every = firstConfig(spec, "everyMinutes", "interval", "minutes").toIntOrNull()?.coerceAtLeast(1) ?: 1
        if (every <= 1) return true  // every-minute consumers (clock, battery line) also take the one-shot refresh tick
        // Interval profiles (everyMinutes>1, e.g. 話す時計) fire ONLY on a real minute boundary — never on the
        // one-shot refresh tick the engine emits on each (re)subscription, which would double-fire them at :00/:30.
        if (event.metadata["refresh"] == "true") return false
        val minute = event.metadata["time"]?.let(::parseClockMinutes) ?: currentMinuteOfDay()
        return minute % every == 0
    }

    /** Sub-minute periodic trigger: fires every `interval` seconds (config `interval`/`everySeconds`/
     *  `seconds`), riding the per-second `sec_tick`. Used by the notification wakedance. */
    private fun matchesSecTick(spec: ContextSpec, event: ContextEvent): Boolean {
        if (!event.metadata["event"].orEmpty().equals("sec_tick", ignoreCase = true)) return false
        val every = firstConfig(spec, "everySeconds", "interval", "seconds").toIntOrNull()?.coerceAtLeast(1) ?: 1
        if (every <= 1) return true
        val epochSec = event.metadata["epochSecond"]?.toLongOrNull() ?: return false
        return epochSec % every == 0L
    }

    private fun matchesSunEvent(spec: ContextSpec, event: ContextEvent, expectedEvent: String): Boolean {
        if (!event.metadata["event"].orEmpty().equals("sun_tick", ignoreCase = true)) return false
        val latitude = firstConfig(spec, "latitude", "lat").toDoubleOrNull() ?: return false
        val longitude = firstConfig(spec, "longitude", "lon", "lng").toDoubleOrNull() ?: return false
        val date = event.metadata["date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
        val zone = event.metadata["zone"]?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
        val currentMinute = event.metadata["time"]?.let(::parseClockMinutes) ?: currentMinuteOfDay()
        val baseMinute = SunEventCalculator.eventMinuteOfDay(date, latitude, longitude, expectedEvent, zone) ?: return false
        val offset = firstConfig(spec, "offsetMinutes", "offset").toIntOrNull() ?: 0
        val window = (firstConfig(spec, "windowMinutes", "window").toIntOrNull() ?: 1).coerceIn(1, 180)
        val start = Math.floorMod(baseMinute + offset, MINUTES_PER_DAY)
        val end = Math.floorMod(start + window - 1, MINUTES_PER_DAY)
        return minuteInWindow(currentMinute, start, end)
    }

    private fun firstConfig(spec: ContextSpec, vararg keys: String): String =
        keys.firstNotNullOfOrNull { spec.config[it]?.trim()?.takeIf(String::isNotBlank) }.orEmpty()

    private fun String.splitCsv(): List<String> =
        split(',', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun textMatches(value: String, filter: String, regex: Boolean): Boolean {
        if (!regex) return value.contains(filter, ignoreCase = true)
        if (filter.length > MAX_REGEX_PATTERN_CHARS || value.length > MAX_REGEX_INPUT_CHARS) return false
        return runCatching {
            Regex(filter, RegexOption.IGNORE_CASE).containsMatchIn(value)
        }.getOrDefault(false)
    }

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

    private const val MAX_REGEX_PATTERN_CHARS = 160
    private const val MAX_REGEX_INPUT_CHARS = 1_000
    private const val MINUTES_PER_DAY = 24 * 60
}

private fun String.isSunEvent(): Boolean =
    equals("sunrise", ignoreCase = true) || equals("sunset", ignoreCase = true)

private fun String.isMinuteTick(): Boolean =
    equals("minute", ignoreCase = true) || equals("clock", ignoreCase = true) ||
        equals("clock_tick", ignoreCase = true) || equals("tick", ignoreCase = true)
