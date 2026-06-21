package com.opentasker.core.contexts

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.EngineHeartbeat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

object CalendarSunContextEvents {
    fun events(app: Context): Flow<ContextEvent> = callbackFlow {
        var lastMinute = -1L
        val tickJob = launch(Dispatchers.IO) {
            while (isActive) {
                val now = System.currentTimeMillis()
                val minute = now / MILLIS_PER_MINUTE
                if (minute != lastMinute) {
                    lastMinute = minute
                    buildCalendarEvent(app, now).forEach { trySend(it) }
                    trySend(buildSunTick(now))
                    EngineHeartbeat.markTick(now)
                }
                delay(1_000)
            }
        }

        awaitClose { tickJob.cancel() }
    }

    internal fun buildCalendarEvent(app: Context, nowMs: Long): List<ContextEvent> {
        if (!hasCalendarPermission(app)) {
            return listOf(
                ContextEvent(
                    type = "event",
                    matched = false,
                    metadata = mapOf("event" to "calendar", "state" to "permission_denied"),
                ),
            )
        }
        val instances = runCatching { queryCalendarInstances(app, nowMs) }
            .getOrElse {
                return listOf(
                    ContextEvent(
                        type = "event",
                        matched = false,
                        metadata = mapOf("event" to "calendar", "state" to "query_error"),
                    ),
                )
            }
        return listOf(selectCalendarEvent(instances, nowMs))
    }

    internal fun selectCalendarEvent(
        instances: List<CalendarInstance>,
        nowMs: Long,
        beforeWindowMinutes: Int = DEFAULT_BEFORE_WINDOW_MINUTES,
    ): ContextEvent {
        val relevant = instances
            .filter { it.endMs > nowMs && it.availability != "free" }
            .sortedBy { it.beginMs }
        val during = relevant.firstOrNull { it.beginMs <= nowMs && it.endMs > nowMs }
        if (during != null) {
            return ContextEvent(
                type = "event",
                matched = true,
                metadata = during.metadata(
                    state = "during",
                    nowMs = nowMs,
                ),
            )
        }

        val upcoming = relevant.firstOrNull {
            val minutesUntilStart = minutesBetween(nowMs, it.beginMs)
            minutesUntilStart in 0..beforeWindowMinutes
        }
        if (upcoming != null) {
            return ContextEvent(
                type = "event",
                matched = true,
                metadata = upcoming.metadata(
                    state = "upcoming",
                    nowMs = nowMs,
                ),
            )
        }

        return ContextEvent(
            type = "event",
            matched = false,
            metadata = mapOf("event" to "calendar", "state" to "idle"),
        )
    }

    internal fun buildSunTick(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): ContextEvent {
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        return ContextEvent(
            type = "event",
            matched = true,
            metadata = mapOf(
                "event" to "sun_tick",
                "date" to local.toLocalDate().toString(),
                "time" to "%02d:%02d".format(local.hour, local.minute),
                "zone" to zone.id,
            ),
        )
    }

    private fun queryCalendarInstances(app: Context, nowMs: Long): List<CalendarInstance> {
        val begin = nowMs - MILLIS_PER_MINUTE
        val end = nowMs + DEFAULT_LOOKAHEAD_MILLIS
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, begin)
        ContentUris.appendId(uriBuilder, end)

        app.contentResolver.query(
            uriBuilder.build(),
            CALENDAR_PROJECTION,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        ).use { cursor ->
            if (cursor == null) return emptyList()
            val items = mutableListOf<CalendarInstance>()
            while (cursor.moveToNext()) {
                items += CalendarInstance(
                    calendarName = sanitizeCalendarName(cursor.getString(0)),
                    calendarId = cursor.getLong(1),
                    beginMs = cursor.getLong(2),
                    endMs = cursor.getLong(3),
                    allDay = cursor.getInt(4) == 1,
                    availability = availabilityLabel(cursor.getInt(5)),
                )
            }
            return items
        }
    }

    private fun CalendarInstance.metadata(state: String, nowMs: Long): Map<String, String> = buildMap {
        put("event", "calendar")
        put("state", state)
        put("calendar", calendarName)
        put("calendarId", calendarId.toString())
        put("allDay", allDay.toString())
        put("availability", availability)
        put("minutesUntilStart", minutesBetween(nowMs, beginMs).coerceAtLeast(0).toString())
        put("minutesUntilEnd", minutesBetween(nowMs, endMs).coerceAtLeast(0).toString())
    }

    private fun hasCalendarPermission(app: Context): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun sanitizeCalendarName(value: String?): String =
        value.orEmpty().trim().take(MAX_CALENDAR_NAME_CHARS).ifBlank { "Calendar" }

    private fun availabilityLabel(value: Int): String = when (value) {
        CalendarContract.Events.AVAILABILITY_FREE -> "free"
        CalendarContract.Events.AVAILABILITY_TENTATIVE -> "tentative"
        else -> "busy"
    }

    private fun minutesBetween(startMs: Long, endMs: Long): Int =
        ((endMs - startMs) / MILLIS_PER_MINUTE).toInt()

    private val CALENDAR_PROJECTION = arrayOf(
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        CalendarContract.Instances.CALENDAR_ID,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.AVAILABILITY,
    )

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val DEFAULT_LOOKAHEAD_MILLIS = 24L * 60L * MILLIS_PER_MINUTE
    private const val DEFAULT_BEFORE_WINDOW_MINUTES = 30
    private const val MAX_CALENDAR_NAME_CHARS = 80
}

data class CalendarInstance(
    val calendarName: String,
    val calendarId: Long,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val availability: String,
)
