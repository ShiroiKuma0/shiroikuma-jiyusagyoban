package com.opentasker.core.contexts

import java.util.Calendar
import java.util.Locale

object DaySchedule {
    val orderedDays: List<String> = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    val weekdays: Set<String> = orderedDays.take(5).toSet()
    val weekends: Set<String> = setOf("SAT", "SUN")

    fun parse(value: String): Set<String> {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return emptySet()
        aliasDays(trimmed)?.let { return it }

        return trimmed
            .split(',', ';')
            .flatMap(::expandSegment)
            .toSet()
    }

    fun canonicalize(value: String): String? = canonicalize(parse(value))

    fun canonicalize(days: Set<String>): String? {
        val ordered = orderedDays.filter { it in days }
        return ordered.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    fun matches(configuredDays: String, observedDay: String): Boolean {
        val configured = parse(configuredDays)
        if (configured.isEmpty()) return false
        val observed = normalizeToken(observedDay) ?: return false
        return observed in configured
    }

    fun displayLabel(value: String): String {
        val days = parse(value)
        return when {
            days.isEmpty() -> "No days selected"
            days == orderedDays.toSet() -> "Every day"
            days == weekdays -> "Weekdays"
            days == weekends -> "Weekends"
            else -> orderedDays.filter { it in days }.joinToString(", ")
        }
    }

    fun currentDayToken(): String {
        return tokenFor(Calendar.getInstance())
    }

    fun tokenFor(calendar: Calendar): String =
        when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "MON"
            Calendar.TUESDAY -> "TUE"
            Calendar.WEDNESDAY -> "WED"
            Calendar.THURSDAY -> "THU"
            Calendar.FRIDAY -> "FRI"
            Calendar.SATURDAY -> "SAT"
            Calendar.SUNDAY -> "SUN"
            else -> "SUN"
        }

    fun normalizeToken(value: String): String? = when (value.trim().uppercase(Locale.US)) {
        "SUN", "SUNDAY", "0", "7" -> "SUN"
        "MON", "MONDAY", "1" -> "MON"
        "TUE", "TUES", "TUESDAY", "2" -> "TUE"
        "WED", "WEDS", "WEDNESDAY", "3" -> "WED"
        "THU", "THUR", "THURS", "THURSDAY", "4" -> "THU"
        "FRI", "FRIDAY", "5" -> "FRI"
        "SAT", "SATURDAY", "6" -> "SAT"
        else -> null
    }

    private fun expandSegment(rawSegment: String): List<String> {
        val segment = rawSegment.trim()
        if (segment.isBlank()) return emptyList()
        aliasDays(segment)?.let { days -> return orderedDays.filter { it in days } }

        val rangeParts = segment.split('-', limit = 2)
        if (rangeParts.size == 2) {
            val start = normalizeToken(rangeParts[0]) ?: return emptyList()
            val end = normalizeToken(rangeParts[1]) ?: return emptyList()
            return expandRange(start, end)
        }

        return normalizeToken(segment)?.let(::listOf).orEmpty()
    }

    private fun expandRange(start: String, end: String): List<String> {
        val startIndex = orderedDays.indexOf(start)
        val endIndex = orderedDays.indexOf(end)
        if (startIndex < 0 || endIndex < 0) return emptyList()
        if (startIndex <= endIndex) return orderedDays.subList(startIndex, endIndex + 1)
        return orderedDays.subList(startIndex, orderedDays.size) + orderedDays.subList(0, endIndex + 1)
    }

    private fun aliasDays(value: String): Set<String>? = when (value.trim().lowercase(Locale.US).replace(" ", "")) {
        "all",
        "daily",
        "everyday",
        "everydayoftheweek" -> orderedDays.toSet()
        "weekday",
        "weekdays",
        "workday",
        "workdays" -> weekdays
        "weekend",
        "weekends" -> weekends
        else -> null
    }
}
