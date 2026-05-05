package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class CalendarSunContextEventsTest {
    @Test
    fun selectCalendarEventPrefersActiveBusyEventAndRedactsTitle() {
        val now = epochMillis(2026, 5, 5, 14, 0)
        val event = CalendarSunContextEvents.selectCalendarEvent(
            instances = listOf(
                CalendarInstance(
                    calendarName = "Work",
                    calendarId = 7,
                    beginMs = now - 10 * MILLIS_PER_MINUTE,
                    endMs = now + 20 * MILLIS_PER_MINUTE,
                    allDay = false,
                    availability = "busy",
                ),
            ),
            nowMs = now,
        )

        assertTrue(event.matched)
        assertEquals("calendar", event.metadata["event"])
        assertEquals("during", event.metadata["state"])
        assertEquals("Work", event.metadata["calendar"])
        assertEquals("20", event.metadata["minutesUntilEnd"])
        assertFalse(event.metadata.containsKey("title"))
        assertFalse(event.metadata.containsKey("description"))
    }

    @Test
    fun selectCalendarEventEmitsUpcomingWithinWindow() {
        val now = epochMillis(2026, 5, 5, 14, 0)
        val event = CalendarSunContextEvents.selectCalendarEvent(
            instances = listOf(
                CalendarInstance(
                    calendarName = "Personal",
                    calendarId = 9,
                    beginMs = now + 15 * MILLIS_PER_MINUTE,
                    endMs = now + 45 * MILLIS_PER_MINUTE,
                    allDay = false,
                    availability = "tentative",
                ),
            ),
            nowMs = now,
            beforeWindowMinutes = 30,
        )

        assertTrue(event.matched)
        assertEquals("upcoming", event.metadata["state"])
        assertEquals("15", event.metadata["minutesUntilStart"])
    }

    @Test
    fun selectCalendarEventIgnoresFreeEventsAndReportsIdle() {
        val now = epochMillis(2026, 5, 5, 14, 0)
        val event = CalendarSunContextEvents.selectCalendarEvent(
            instances = listOf(
                CalendarInstance(
                    calendarName = "Work",
                    calendarId = 7,
                    beginMs = now - 10 * MILLIS_PER_MINUTE,
                    endMs = now + 20 * MILLIS_PER_MINUTE,
                    allDay = false,
                    availability = "free",
                ),
            ),
            nowMs = now,
        )

        assertFalse(event.matched)
        assertEquals("idle", event.metadata["state"])
    }

    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
