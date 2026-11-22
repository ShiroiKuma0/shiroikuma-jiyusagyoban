package com.opentasker.core.actions

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.text.countOf
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

internal data class CalendarInfo(val id: Long, val name: String, val writable: Boolean)

internal data class NewCalendarEvent(
    val calendarId: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val timeZone: String,
    val description: String?,
    val location: String?,
)

/** One occurrence of an event. An all-day row's bounds are midnight UTC, as the provider stores them. */
internal data class CalendarEventRow(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val location: String,
)

/** The Calendar Provider calls the calendar actions make, so a test can stand in for it. */
internal interface CalendarStore {
    fun calendars(): List<CalendarInfo>

    /** Inserts the event and returns its id, or null when the provider didn't take it. */
    fun insert(event: NewCalendarEvent): Long?

    /** Occurrences the provider has between [fromMs] and [toMs], soonest first, read up to a fixed cap. */
    fun instances(fromMs: Long, toMs: Long, calendarIds: Set<Long>?): List<CalendarEventRow>
}

/**
 * Adds an event to a calendar named by its display name or id, and puts the new event's id in
 * `%event_id`. Args: `calendar`, `title`, `start`, then `end` or `duration` (minutes, 60 by
 * default), `all_day`, `description`, `location` and `var`, the prefix. Times take `now`, epoch
 * milliseconds, a date (2026-11-22) or a date and time (2026-11-22 09:30) in the phone's zone.
 * An all-day event runs from its start day through its end day.
 *
 * Nothing about the event goes into the log line, since a calendar entry can say as much as a
 * contact does. A synced calendar sends the event to its account, so the action counts as one
 * that sends data out.
 */
class CalendarEventAddAction internal constructor(
    private val permitted: (Context, String) -> Boolean,
    private val store: (Context) -> CalendarStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : DeclaredAction(ActionCatalog.require("calendar.event.add")) {

    constructor() : this(::calendarPermissionGranted, ::ProviderCalendarStore)

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = calendarPrefix(args) ?: return ActionResult.Failure("invalid variable prefix")
        val calendarName = args["calendar"]?.trim().orEmpty()
        if (calendarName.isEmpty()) return ActionResult.Failure("missing calendar, so give its name or id")
        val title = args["title"]?.trim().orEmpty()
        if (title.isEmpty()) return ActionResult.Failure("missing title")
        if (title.length > MAX_TITLE_CHARS) return ActionResult.Failure("the title is longer than $MAX_TITLE_CHARS characters")
        val description = args["description"]?.takeIf(String::isNotBlank)
        if (description != null && description.length > MAX_DESCRIPTION_CHARS) {
            return ActionResult.Failure("the description is longer than $MAX_DESCRIPTION_CHARS characters")
        }
        val location = args["location"]?.trim()?.takeIf(String::isNotEmpty)
        if (location != null && location.length > MAX_TITLE_CHARS) {
            return ActionResult.Failure("the location is longer than $MAX_TITLE_CHARS characters")
        }
        val allDay = args["all_day"]?.trim()?.toBooleanStrictOrNull() == true
        val zone = zone()
        val now = clock()
        val start = readTime(args, "start", zone, now) ?: return ActionResult.Failure(timeFormatMessage("start"))
        val endText = args["end"]?.takeIf(String::isNotBlank)
        val durationText = args["duration"]?.takeIf(String::isNotBlank)
        if (endText != null && durationText != null) return ActionResult.Failure("give an end or a duration, not both")
        val end = endText?.let { parseCalendarTime(it, zone, now) ?: return ActionResult.Failure(timeFormatMessage("end")) }

        val bounds = if (allDay) {
            if (durationText != null) return ActionResult.Failure("an all-day event takes an end day, not a duration")
            val firstDay = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val lastDay = end?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: firstDay
            if (lastDay < firstDay) return ActionResult.Failure("the end day is before the start day")
            // The provider wants an all-day event at midnight UTC, ending the midnight after its last day.
            Triple(utcMidnight(firstDay), utcMidnight(lastDay.plusDays(1)), "UTC")
        } else {
            val endMs = end ?: run {
                val minutes = durationText?.let { text ->
                    text.trim().toLongOrNull()?.takeIf { it in 1..MAX_DURATION_MINUTES }
                        ?: return ActionResult.Failure("duration must be a whole number of minutes from 1 to $MAX_DURATION_MINUTES")
                } ?: DEFAULT_DURATION_MINUTES
                start + minutes * MILLIS_PER_MINUTE
            }
            if (endMs <= start) return ActionResult.Failure("the end is not after the start")
            Triple(start, endMs, zone.id)
        }

        if (!permitted(ctx.app, Manifest.permission.READ_CALENDAR) || !permitted(ctx.app, Manifest.permission.WRITE_CALENDAR)) {
            return ActionResult.Failure(ADD_PERMISSION_MISSING)
        }
        val calendars = store(ctx.app)
        val named = runCatching { calendars.calendars() }
            .getOrElse { return ActionResult.Failure("the calendars couldn't be read: ${it.message}") }
            .let { calendarsNamed(it, calendarName) }
        if (named.isEmpty()) return ActionResult.Failure(NO_SUCH_CALENDAR)
        val writable = named.filter(CalendarInfo::writable)
        if (writable.isEmpty()) return ActionResult.Failure("that calendar is read-only, so nothing was added")
        if (writable.size > 1) {
            return ActionResult.Failure(
                "${countOf(writable.size, "calendar")} have that name (ids ${writable.joinToString { it.id.toString() }}), so give the id instead",
            )
        }
        val calendar = writable.single()
        val (startMs, endMs, timeZone) = bounds
        val eventId = runCatching {
            calendars.insert(NewCalendarEvent(calendar.id, title, startMs, endMs, allDay, timeZone, description, location))
        }.getOrElse { return ActionResult.Failure("the event couldn't be added: ${it.message}") }
            ?: return ActionResult.Failure("the calendar didn't take the event")
        ctx.variables.set("${prefix}_id", eventId.toString())
        ctx.logger("Added calendar event $eventId to calendar ${calendar.id}")
        return ActionResult.Success
    }

    companion object {
        const val MAX_DURATION_MINUTES = 366L * 24L * 60L
        private const val DEFAULT_DURATION_MINUTES = 60L
        internal const val MAX_TITLE_CHARS = 1024
        private const val MAX_DESCRIPTION_CHARS = 8192
        internal const val ADD_PERMISSION_MISSING =
            "calendar.event.add needs calendar editing, so nothing was added. Grant it on the Calendar editing row in Setup."
    }
}

/**
 * Reads the events between `from` (now by default) and `to` (a day after `from` by default) into
 * `%event_count`, the first event's `%event_title`, `_start`, `_end`, `_all_day`, `_location`,
 * `_id` and `_calendar_id`, and the arrays `%event_titles`, `_starts`, `_ends`, `_locations` and
 * `_ids`. Args: `from`, `to`, `calendar` (a name or id to look in, every calendar otherwise),
 * `max` (10 by default, 50 at most) and `var`, the prefix. Repeating events count once for each
 * time they happen in the window. Times come out as epoch milliseconds, so Format Date-Time reads
 * them. An all-day event starts at midnight where the phone is on its first day and ends at
 * midnight on its last day, the end day Add Calendar Event takes. Titles and locations past 1024
 * characters are cut there, as Add Calendar Event won't write longer ones.
 *
 * Titles and locations are stored as sensitive, the way contact lookups are, so the run log and
 * previews never show them.
 */
class CalendarEventQueryAction internal constructor(
    private val permitted: (Context, String) -> Boolean,
    private val store: (Context) -> CalendarStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : DeclaredAction(ActionCatalog.require("calendar.event.query")) {

    constructor() : this(::calendarPermissionGranted, ::ProviderCalendarStore)

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = calendarPrefix(args) ?: return ActionResult.Failure("invalid variable prefix")
        val zone = zone()
        val now = clock()
        val from = args["from"]?.takeIf(String::isNotBlank)
            ?.let { parseCalendarTime(it, zone, now) ?: return ActionResult.Failure(timeFormatMessage("from")) }
            ?: now
        val to = args["to"]?.takeIf(String::isNotBlank)
            ?.let { parseCalendarTime(it, zone, now) ?: return ActionResult.Failure(timeFormatMessage("to")) }
            ?: (from + MILLIS_PER_DAY)
        if (to <= from) return ActionResult.Failure("to is not after from")
        if (to - from > MAX_WINDOW_DAYS * MILLIS_PER_DAY) return ActionResult.Failure("the window is longer than $MAX_WINDOW_DAYS days")
        val max = args["max"]?.takeIf(String::isNotBlank)?.let { text ->
            text.trim().toIntOrNull()?.takeIf { it in 1..MAX_RESULTS }
                ?: return ActionResult.Failure("max must be a whole number from 1 to $MAX_RESULTS")
        } ?: DEFAULT_RESULTS

        if (!permitted(ctx.app, Manifest.permission.READ_CALENDAR)) return ActionResult.Failure(QUERY_PERMISSION_MISSING)
        val calendars = store(ctx.app)
        val events = runCatching {
            val only = args["calendar"]?.trim()?.takeIf(String::isNotEmpty)?.let { named ->
                calendarsNamed(calendars.calendars(), named).mapTo(LinkedHashSet<Long>()) { it.id }
                    .ifEmpty { return ActionResult.Failure(NO_SUCH_CALENDAR) }
            }
            // An all-day row is stored at midnight UTC, up to a day off from the day it means here,
            // so the provider is asked for a day more on each side and the rest is decided locally.
            selectCalendarEvents(calendars.instances(from - MILLIS_PER_DAY, to + MILLIS_PER_DAY, only), from, to, zone, max)
        }.getOrElse { return ActionResult.Failure("the calendar couldn't be read: ${it.message}") }

        val first = events.firstOrNull()
        ctx.variables.set("${prefix}_count", events.size.toString())
        ctx.variables.set("${prefix}_id", first?.eventId?.toString().orEmpty())
        ctx.variables.set("${prefix}_calendar_id", first?.calendarId?.toString().orEmpty())
        ctx.variables.set("${prefix}_title", first?.title.orEmpty(), sensitive = true)
        ctx.variables.set("${prefix}_start", first?.startMs?.toString().orEmpty())
        ctx.variables.set("${prefix}_end", first?.let { lastDayEnd(it, zone) }?.toString().orEmpty())
        ctx.variables.set("${prefix}_all_day", first?.allDay?.toString().orEmpty())
        ctx.variables.set("${prefix}_location", first?.location.orEmpty(), sensitive = true)
        ctx.variables.setArray("${prefix}_ids", events.map { it.eventId.toString() })
        ctx.variables.setArray("${prefix}_titles", events.map(CalendarEventRow::title), sensitive = true)
        ctx.variables.setArray("${prefix}_starts", events.map { it.startMs.toString() })
        ctx.variables.setArray("${prefix}_ends", events.map { lastDayEnd(it, zone).toString() })
        ctx.variables.setArray("${prefix}_locations", events.map(CalendarEventRow::location), sensitive = true)
        ctx.logger("Calendar query found ${countOf(events.size, "event")}")
        return ActionResult.Success
    }

    companion object {
        const val MAX_RESULTS = 50
        private const val DEFAULT_RESULTS = 10
        private const val MAX_WINDOW_DAYS = 366L
        internal const val QUERY_PERMISSION_MISSING =
            "calendar.event.query needs calendar access, so nothing was read. Grant it on the Calendar access row in Setup."
    }
}

const val DEFAULT_CALENDAR_PREFIX = "event"

/** The calendars a step names: the one with that id, or else every one with that display name. */
internal fun calendarsNamed(calendars: List<CalendarInfo>, named: String): List<CalendarInfo> {
    val wanted = named.trim()
    val byId = wanted.toLongOrNull()?.let { id -> calendars.filter { it.id == id } }.orEmpty()
    return byId.ifEmpty { calendars.filter { it.name.trim().equals(wanted, ignoreCase = true) } }
}

/** Occurrences overlapping [fromMs]..[toMs] in local time, soonest first, at most [limit]. */
internal fun selectCalendarEvents(
    rows: List<CalendarEventRow>,
    fromMs: Long,
    toMs: Long,
    zone: ZoneId,
    limit: Int,
): List<CalendarEventRow> = rows.asSequence()
    .map { if (it.allDay) it.copy(startMs = localMidnight(it.startMs, zone), endMs = localMidnight(it.endMs, zone)) else it }
    // An event with no length still counts when it starts inside the window.
    .filter { it.startMs < toMs && (it.endMs > fromMs || it.startMs >= fromMs) }
    .sortedWith(compareBy(CalendarEventRow::startMs, CalendarEventRow::eventId))
    .take(limit)
    .map {
        it.copy(
            title = it.title.cutAt(CalendarEventAddAction.MAX_TITLE_CHARS),
            location = it.location.cutAt(CalendarEventAddAction.MAX_TITLE_CHARS),
        )
    }
    .toList()

/** At most [max] characters, one fewer when the cut would split an emoji or another surrogate pair. */
private fun String.cutAt(max: Int): String =
    if (length <= max) this else take(if (this[max - 1].isHighSurrogate()) max - 1 else max)

/**
 * The end to report for [row] once [selectCalendarEvents] has it in local time: an all-day event
 * ends at the start of its last day rather than the midnight after it, so passing a found event's
 * start and end back to Add Calendar Event makes the same days.
 */
internal fun lastDayEnd(row: CalendarEventRow, zone: ZoneId): Long {
    if (!row.allDay) return row.endMs
    val lastDay = Instant.ofEpochMilli(row.endMs).atZone(zone).toLocalDate().minusDays(1)
    return maxOf(row.startMs, lastDay.atStartOfDay(zone).toInstant().toEpochMilli())
}

/** `now`, epoch milliseconds, a date (2026-11-22) or a date and time (2026-11-22 09:30) in [zone]. */
internal fun parseCalendarTime(raw: String, zone: ZoneId, nowMs: Long): Long? {
    val text = raw.trim()
    if (text.equals("now", ignoreCase = true)) return nowMs
    val parsed = text.toLongOrNull()
        ?: runCatching { LocalDateTime.parse(text.replaceFirst(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDate.parse(text).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()
    // Years 1 to 9999 only. A time far past them overflowed the window check and the day added
    // on each side of a query.
    return parsed?.takeIf { it in EARLIEST_CALENDAR_MS..LATEST_CALENDAR_MS }
}

private val EARLIEST_CALENDAR_MS = LocalDate.of(1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private val LATEST_CALENDAR_MS = LocalDate.of(10_000, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1

private fun readTime(args: Map<String, String>, key: String, zone: ZoneId, nowMs: Long): Long? =
    args[key]?.takeIf(String::isNotBlank)?.let { parseCalendarTime(it, zone, nowMs) }

private fun timeFormatMessage(key: String) =
    "$key must be now, epoch milliseconds, a date like 2026-11-22 or a date and time like 2026-11-22 09:30, in the years 1 to 9999"

private const val NO_SUCH_CALENDAR = "no calendar has that name or id"

private fun calendarPrefix(args: Map<String, String>): String? =
    args["var"].orEmpty().ifBlank { DEFAULT_CALENDAR_PREFIX }.let(VariableNamePolicy::normalize)
        ?.takeIf { VariableNamePolicy.normalize("${it}_calendar_id") != null }

private fun utcMidnight(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun localMidnight(utcMidnightMs: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(utcMidnightMs).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

private fun calendarPermissionGranted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_DAY = 24L * 60L * MILLIS_PER_MINUTE

private class ProviderCalendarStore(context: Context) : CalendarStore {
    private val resolver = context.contentResolver

    override fun calendars(): List<CalendarInfo> = resolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        ),
        null,
        null,
        "${CalendarContract.Calendars._ID} ASC",
    )?.use { cursor ->
        buildList {
            while (cursor.moveToNext() && size < MAX_CALENDARS) {
                add(
                    CalendarInfo(
                        id = cursor.getLong(0),
                        name = cursor.getString(1).orEmpty(),
                        writable = cursor.getInt(2) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    ),
                )
            }
        }
    }.orEmpty()

    override fun insert(event: NewCalendarEvent): Long? {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, event.calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DTSTART, event.startMs)
            put(CalendarContract.Events.DTEND, event.endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, event.timeZone)
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
            event.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        return resolver.insert(CalendarContract.Events.CONTENT_URI, values)?.let(ContentUris::parseId)
    }

    override fun instances(fromMs: Long, toMs: Long, calendarIds: Set<Long>?): List<CalendarEventRow> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val ids = calendarIds?.toList()
        return resolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
            ),
            ids?.let { list -> "${CalendarContract.Instances.CALENDAR_ID} IN (${list.joinToString(",") { "?" }})" },
            ids?.map(Long::toString)?.toTypedArray(),
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < MAX_ROWS_READ) {
                    add(
                        CalendarEventRow(
                            eventId = cursor.getLong(0),
                            calendarId = cursor.getLong(1),
                            title = cursor.getString(2).orEmpty(),
                            startMs = cursor.getLong(3),
                            endMs = cursor.getLong(4),
                            allDay = cursor.getInt(5) == 1,
                            location = cursor.getString(6).orEmpty(),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private companion object {
        const val MAX_CALENDARS = 200
        // The window can be a year long, so the rows read are capped before anything is sorted.
        const val MAX_ROWS_READ = 2000
    }
}
