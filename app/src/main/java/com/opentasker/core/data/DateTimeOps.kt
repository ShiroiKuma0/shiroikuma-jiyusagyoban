package com.opentasker.core.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Deterministic, offline date-time helpers used by the `datetime.*` actions. All functions return
 * null on invalid input so the actions fail closed. Epoch values are milliseconds.
 */
object DateTimeOps {
    /** Format an epoch-millis instant with [pattern] (e.g. `yyyy-MM-dd HH:mm`). */
    fun format(epochMillis: Long, pattern: String, zone: String?): String? = runCatching {
        DateTimeFormatter.ofPattern(pattern).withZone(zoneId(zone)).format(Instant.ofEpochMilli(epochMillis))
    }.getOrNull()

    /** Parse [text] with [pattern] into epoch millis; accepts date-time or date-only patterns. */
    fun parse(text: String, pattern: String, zone: String?): Long? {
        val formatter = runCatching { DateTimeFormatter.ofPattern(pattern) }.getOrNull() ?: return null
        val zoneId = zoneId(zone)
        runCatching { LocalDateTime.parse(text, formatter).atZone(zoneId).toInstant().toEpochMilli() }
            .getOrNull()?.let { return it }
        runCatching { LocalDate.parse(text, formatter).atStartOfDay(zoneId).toInstant().toEpochMilli() }
            .getOrNull()?.let { return it }
        return null
    }

    /**
     * Add [amount] (may be negative) of [unit] to an epoch-millis instant. Fixed units
     * (seconds..weeks) are zone-independent exact deltas; calendar units (months/years) use the
     * system zone so month/year length is honored.
     */
    fun add(epochMillis: Long, amount: Long, unit: String): Long? {
        val instant = Instant.ofEpochMilli(epochMillis)
        return runCatching {
            when (unit.trim().lowercase()) {
                "second", "seconds", "sec", "s" -> instant.plus(amount, ChronoUnit.SECONDS)
                "minute", "minutes", "min", "m" -> instant.plus(amount, ChronoUnit.MINUTES)
                "hour", "hours", "h" -> instant.plus(amount, ChronoUnit.HOURS)
                "day", "days", "d" -> instant.plus(amount, ChronoUnit.DAYS)
                "week", "weeks", "w" -> instant.plus(amount * 7, ChronoUnit.DAYS)
                "month", "months" -> instant.atZone(ZoneId.systemDefault()).plusMonths(amount).toInstant()
                "year", "years", "y" -> instant.atZone(ZoneId.systemDefault()).plusYears(amount).toInstant()
                else -> return null
            }.toEpochMilli()
        }.getOrNull()
    }

    private fun zoneId(zone: String?): ZoneId =
        if (zone.isNullOrBlank()) ZoneId.systemDefault()
        else runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())
}
