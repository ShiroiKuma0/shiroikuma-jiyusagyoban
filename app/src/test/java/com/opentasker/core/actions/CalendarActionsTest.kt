package com.opentasker.core.actions

import android.Manifest
import android.content.ContextWrapper
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.AutomationPower
import com.opentasker.core.capabilities.AutomationSensitivityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.capabilities.SetupRequirement
import com.opentasker.core.capabilities.SetupRequirementResolver
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A-359: adding and finding calendar events, against a stand-in for the Calendar Provider. */
class CalendarActionsTest {
    private var zone = ZoneId.of("America/New_York")
    private val now = at(2026, 11, 22, 8, 0)
    private val variables = VariableStore()
    private val logs = mutableListOf<String>()

    private class FakeStore(
        private val calendars: List<CalendarInfo>,
        private val rows: List<CalendarEventRow> = emptyList(),
        private val newId: Long = 77,
    ) : CalendarStore {
        val inserted = mutableListOf<NewCalendarEvent>()
        val asked = mutableListOf<Triple<Long, Long, Set<Long>?>>()

        override fun calendars(): List<CalendarInfo> = calendars

        override fun insert(event: NewCalendarEvent): Long? {
            inserted += event
            return newId
        }

        override fun instances(fromMs: Long, toMs: Long, calendarIds: Set<Long>?): List<CalendarEventRow> {
            asked += Triple(fromMs, toMs, calendarIds)
            return rows.filter { calendarIds == null || it.calendarId in calendarIds }
        }
    }

    private val calendars = listOf(
        CalendarInfo(1, "Holidays", writable = false),
        CalendarInfo(2, "Work", writable = true),
        CalendarInfo(3, "Home", writable = true),
    )

    @Before
    fun setUp() {
        registerCoreRuntime()
        registerActionMetadata()
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun utcMidnight(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun context() = ActionContext(ContextWrapper(null), variables, logger = { logs += it })

    private fun add(store: CalendarStore, vararg args: Pair<String, String>, granted: Set<String> = BOTH): ActionResult {
        val action = CalendarEventAddAction({ _, permission -> permission in granted }, { store }, { now }, { zone })
        return runBlocking { action.run(context(), mapOf(*args)) }
    }

    private fun query(store: CalendarStore, vararg args: Pair<String, String>, granted: Set<String> = BOTH): ActionResult {
        val action = CalendarEventQueryAction({ _, permission -> permission in granted }, { store }, { now }, { zone })
        return runBlocking { action.run(context(), mapOf(*args)) }
    }

    @Test
    fun addingPutsTheEventOnTheNamedCalendarAndReturnsItsId() {
        val store = FakeStore(calendars)

        val result = add(store, "calendar" to "work", "title" to "Dentist", "start" to "2026-11-23 09:30", "duration" to "30", "location" to "12 Main St")

        assertEquals(ActionResult.Success, result)
        val event = store.inserted.single()
        assertEquals(2L, event.calendarId)
        assertEquals("Dentist", event.title)
        assertEquals(at(2026, 11, 23, 9, 30), event.startMs)
        assertEquals(at(2026, 11, 23, 10, 0), event.endMs)
        assertEquals("America/New_York", event.timeZone)
        assertFalse(event.allDay)
        assertEquals("12 Main St", event.location)
        assertEquals("77", variables.get("event_id"))
        assertFalse(logs.toString(), logs.any { "Dentist" in it || "Main St" in it })
    }

    @Test
    fun anEventWithNoEndOrDurationRunsAnHour() {
        val store = FakeStore(calendars)

        assertEquals(ActionResult.Success, add(store, "calendar" to "Home", "title" to "Call", "start" to "now"))

        assertEquals(now + 3_600_000L, store.inserted.single().endMs)
        assertEquals(3L, store.inserted.single().calendarId)
    }

    @Test
    fun anAllDayEventIsStoredAtMidnightUtcThroughItsLastDay() {
        val store = FakeStore(calendars)

        val result = add(store, "calendar" to "3", "title" to "Trip", "start" to "2026-11-23", "end" to "2026-11-24", "all_day" to "true", "var" to "trip")

        assertEquals(ActionResult.Success, result)
        val event = store.inserted.single()
        assertEquals(3L, event.calendarId)
        assertTrue(event.allDay)
        assertEquals(utcMidnight(2026, 11, 23), event.startMs)
        assertEquals(utcMidnight(2026, 11, 25), event.endMs)
        assertEquals("UTC", event.timeZone)
        assertEquals("77", variables.get("trip_id"))
    }

    @Test
    fun aReadOnlyOrAmbiguousCalendarAddsNothing() {
        val readOnly = FakeStore(calendars)
        val readOnlyResult = add(readOnly, "calendar" to "Holidays", "title" to "x", "start" to "now")
        assertTrue(readOnlyResult.toString(), (readOnlyResult as ActionResult.Failure).message.contains("read-only"))

        val twins = FakeStore(calendars + CalendarInfo(4, "Work", writable = true))
        val twinsResult = add(twins, "calendar" to "Work", "title" to "x", "start" to "now")
        assertEquals("2 calendars have that name (ids 2, 4), so give the id instead", (twinsResult as ActionResult.Failure).message)

        val missing = FakeStore(calendars)
        val missingResult = add(missing, "calendar" to "Gym", "title" to "x", "start" to "now")
        assertEquals("no calendar has that name or id", (missingResult as ActionResult.Failure).message)

        assertTrue(readOnly.inserted.isEmpty() && twins.inserted.isEmpty() && missing.inserted.isEmpty())
    }

    @Test
    fun badTimesAreRefusedBeforeAnythingIsAdded() {
        val store = FakeStore(calendars)

        val unreadable = add(store, "calendar" to "Work", "title" to "x", "start" to "tomorrow")
        assertTrue((unreadable as ActionResult.Failure).message.startsWith("start must be now, epoch milliseconds"))
        val backwards = add(store, "calendar" to "Work", "title" to "x", "start" to "2026-11-23 10:00", "end" to "2026-11-23 09:00")
        assertEquals("the end is not after the start", (backwards as ActionResult.Failure).message)
        val both = add(store, "calendar" to "Work", "title" to "x", "start" to "now", "end" to "now", "duration" to "5")
        assertEquals("give an end or a duration, not both", (both as ActionResult.Failure).message)

        assertTrue(store.inserted.isEmpty())
    }

    @Test
    fun withoutThePermissionBothFailNamingTheSetupRow() {
        val untouched = object : CalendarStore {
            override fun calendars(): List<CalendarInfo> = error("read without permission")
            override fun insert(event: NewCalendarEvent): Long? = error("wrote without permission")
            override fun instances(fromMs: Long, toMs: Long, calendarIds: Set<Long>?): List<CalendarEventRow> = error("read without permission")
        }

        val noWrite = add(untouched, "calendar" to "Work", "title" to "x", "start" to "now", granted = setOf(Manifest.permission.READ_CALENDAR))
        assertEquals(CalendarEventAddAction.ADD_PERMISSION_MISSING, (noWrite as ActionResult.Failure).message)
        assertTrue(noWrite.message.contains("Calendar editing row in Setup"))
        val noRead = query(untouched, granted = setOf(Manifest.permission.WRITE_CALENDAR))
        assertEquals(CalendarEventQueryAction.QUERY_PERMISSION_MISSING, (noRead as ActionResult.Failure).message)
        assertTrue(noRead.message.contains("Calendar access row in Setup"))
    }

    @Test
    fun aQueryNeverReturnsMoreThanItsMaxAndSortsBySoonest() {
        val rows = (0 until 12).map { i ->
            CalendarEventRow(eventId = 100L + i, calendarId = 2, title = "Meeting $i", startMs = at(2026, 11, 22, 20, 0) - i * 600_000L, endMs = at(2026, 11, 22, 21, 0), allDay = false, location = "Room $i")
        }
        val store = FakeStore(calendars, rows)

        assertEquals(ActionResult.Success, query(store, "max" to "3"))

        assertEquals("3", variables.get("event_count"))
        assertEquals(listOf("111", "110", "109"), variables.getArrayItems("event_ids"))
        assertEquals(listOf("Meeting 11", "Meeting 10", "Meeting 9"), variables.getArrayItems("event_titles"))
        assertEquals("Meeting 11", variables.get("event_title"))
        assertEquals("Room 11", variables.get("event_location"))
        assertEquals((at(2026, 11, 22, 20, 0) - 11 * 600_000L).toString(), variables.get("event_start"))
        assertEquals("false", variables.get("event_all_day"))
        // From now through a day later, asked a day wider on each side for all-day rows.
        assertEquals(Triple(now - 86_400_000L, now + 2 * 86_400_000L, null as Set<Long>?), store.asked.single())
    }

    @Test
    fun aQueryTakesTenByDefaultAndRefusesMoreThanFifty() {
        val rows = (0 until 12).map { i ->
            CalendarEventRow(eventId = i.toLong(), calendarId = 2, title = "t$i", startMs = now + i * 60_000L, endMs = now + i * 60_000L + 1, allDay = false, location = "")
        }

        assertEquals(ActionResult.Success, query(FakeStore(calendars, rows)))
        assertEquals("10", variables.get("event_count"))
        assertEquals(10, variables.getArrayItems("event_titles")?.size)

        val tooMany = query(FakeStore(calendars, rows), "max" to "51")
        assertEquals("max must be a whole number from 1 to 50", (tooMany as ActionResult.Failure).message)
    }

    @Test
    fun anAllDayEventCountsOnItsOwnDayHereAndComesOutAtLocalMidnight() {
        val rows = listOf(
            CalendarEventRow(1, 2, "Today", utcMidnight(2026, 11, 22), utcMidnight(2026, 11, 23), allDay = true, location = ""),
            CalendarEventRow(2, 2, "Tomorrow", utcMidnight(2026, 11, 23), utcMidnight(2026, 11, 24), allDay = true, location = ""),
        )

        // Just today: 00:00 to 23:59 in New York, where midnight UTC on the 23rd is still the 22nd.
        assertEquals(ActionResult.Success, query(FakeStore(calendars, rows), "from" to "2026-11-22", "to" to "2026-11-22 23:59"))

        assertEquals(listOf("Today"), variables.getArrayItems("event_titles"))
        assertEquals(at(2026, 11, 22, 0, 0).toString(), variables.get("event_start"))
        // A one-day event ends on the day it starts, the end day Add Calendar Event takes.
        assertEquals(at(2026, 11, 22, 0, 0).toString(), variables.get("event_end"))
        assertEquals("true", variables.get("event_all_day"))
    }

    @Test
    fun aFoundAllDayEventAddedBackCoversTheSameDays() {
        // West and east of UTC, and over the night New York's clocks go back.
        for ((zoneId, first, last) in listOf(
            Triple("America/New_York", LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 25)),
            Triple("Asia/Tokyo", LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 25)),
            Triple("America/New_York", LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 2)),
        )) {
            zone = ZoneId.of(zoneId)
            val endDay = last.plusDays(1)
            val original = CalendarEventRow(
                1, 3, "Trip",
                utcMidnight(first.year, first.monthValue, first.dayOfMonth),
                utcMidnight(endDay.year, endDay.monthValue, endDay.dayOfMonth),
                allDay = true, location = "",
            )

            val found = query(FakeStore(calendars, listOf(original)), "from" to first.minusDays(1).toString(), "to" to last.plusDays(3).toString())
            assertEquals(zoneId, ActionResult.Success, found)
            assertEquals(zoneId, last.atStartOfDay(zone).toInstant().toEpochMilli().toString(), variables.get("event_end"))

            val store = FakeStore(calendars, emptyList())
            val copied = add(
                store,
                "calendar" to "3",
                "title" to "Trip",
                "start" to variables.get("event_start")!!,
                "end" to variables.get("event_end")!!,
                "all_day" to "true",
            )

            assertEquals(zoneId, ActionResult.Success, copied)
            assertEquals(zoneId, original.startMs to original.endMs, store.inserted.single().let { it.startMs to it.endMs })
        }
    }

    @Test
    fun aTimePastTheYear9999IsRefusedInsteadOfOverflowingTheWindow() {
        val rows = listOf(CalendarEventRow(1, 2, "Standup", now + 60_000L, now + 120_000L, allDay = false, location = ""))
        val store = FakeStore(calendars, rows)

        val result = query(store, "from" to "-5000000000000000000", "to" to "5000000000000000000")

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.startsWith("from must be now, epoch milliseconds"))
        assertTrue(store.asked.isEmpty())
        // The last moment of 9999 still counts.
        assertEquals(253402300799999L, parseCalendarTime("253402300799999", java.time.ZoneOffset.UTC, now))
        assertEquals(null, parseCalendarTime("253402300800000", java.time.ZoneOffset.UTC, now))
    }

    @Test
    fun aFoundTitleOrLocationIsCutAt1024Characters() {
        val long = "x".repeat(5000)
        val rows = listOf(CalendarEventRow(1, 2, long, now + 60_000L, now + 120_000L, allDay = false, location = long))

        assertEquals(ActionResult.Success, query(FakeStore(calendars, rows)))

        assertEquals(1024, variables.get("event_title")?.length)
        assertEquals(1024, variables.get("event_location")?.length)
    }

    @Test
    fun aCutTitleKeepsWholeEmoji() {
        // An emoji is two chars, and this one would straddle the 1024th.
        val title = "x".repeat(1023) + "\uD83D\uDE00" + "tail"
        val rows = listOf(CalendarEventRow(1, 2, title, now + 60_000L, now + 120_000L, allDay = false, location = ""))

        assertEquals(ActionResult.Success, query(FakeStore(calendars, rows)))

        assertEquals("x".repeat(1023), variables.get("event_title"))
    }

    @Test
    fun aCutTitleKeepsAnAccentWithItsLetter() {
        // An e followed by a combining acute accent is one letter on screen, and the accent would be the 1025th char.
        val title = "x".repeat(1023) + "e\u0301" + "tail"
        val rows = listOf(CalendarEventRow(1, 2, title, now + 60_000L, now + 120_000L, allDay = false, location = ""))

        assertEquals(ActionResult.Success, query(FakeStore(calendars, rows)))

        assertEquals("x".repeat(1023), variables.get("event_title"))
    }

    @Test
    fun aCutTitleKeepsAFlagWhole() {
        // The JDK's BreakIterator keeps a flag's two letters together only from Java 20, where it took
        // up the grapheme rules Android's ICU one always had, so an older test JVM can't check this.
        assumeTrue(System.getProperty("java.specification.version").substringAfter("1.").toInt() >= 20)
        // A flag is two regional indicator letters, four chars, and this one runs from the 1023rd to the 1026th.
        val title = "x".repeat(1022) + "\ud83c\uddfa\ud83c\uddf8" + "tail"
        val rows = listOf(CalendarEventRow(1, 2, title, now + 60_000L, now + 120_000L, allDay = false, location = ""))

        assertEquals(ActionResult.Success, query(FakeStore(calendars, rows)))

        assertEquals("x".repeat(1022), variables.get("event_title"))
    }

    @Test
    fun aQueryLooksOnlyInTheNamedCalendar() {
        val rows = listOf(
            CalendarEventRow(1, 2, "Standup", now + 60_000L, now + 120_000L, allDay = false, location = ""),
            CalendarEventRow(2, 3, "Groceries", now + 60_000L, now + 120_000L, allDay = false, location = ""),
        )
        val store = FakeStore(calendars, rows)

        assertEquals(ActionResult.Success, query(store, "calendar" to "home"))
        assertEquals(setOf(3L), store.asked.single().third)
        assertEquals(listOf("Groceries"), variables.getArrayItems("event_titles"))

        val missing = query(FakeStore(calendars, rows), "calendar" to "Gym")
        assertEquals("no calendar has that name or id", (missing as ActionResult.Failure).message)
    }

    @Test
    fun anEmptyWindowSucceedsWithACountOfZero() {
        variables.set("event_title", "stale")

        assertEquals(ActionResult.Success, query(FakeStore(calendars)))

        assertEquals("0", variables.get("event_count"))
        assertEquals("", variables.get("event_title"))
        assertEquals(emptyList<String>(), variables.getArrayItems("event_titles"))
    }

    @Test
    fun timesReadAsNowMillisADateOrADateAndTime() {
        assertEquals(now, parseCalendarTime(" NOW ", zone, now))
        assertEquals(1_800_000_000_000L, parseCalendarTime("1800000000000", zone, now))
        assertEquals(at(2026, 11, 23, 0, 0), parseCalendarTime("2026-11-23", zone, now))
        assertEquals(at(2026, 11, 23, 9, 30), parseCalendarTime("2026-11-23 09:30", zone, now))
        assertEquals(at(2026, 11, 23, 9, 30), parseCalendarTime("2026-11-23T09:30", zone, now))
        assertNull(parseCalendarTime("next tuesday", zone, now))
    }

    @Test
    fun bothActionsAreRegisteredWithTheirSetupRows() {
        assertTrue(ActionRegistry.get("calendar.event.add") is CalendarEventAddAction)
        assertTrue(ActionRegistry.get("calendar.event.query") is CalendarEventQueryAction)
        assertEquals(CapabilityLevel.RequiresSetup, ActionCapabilityRegistry.get("calendar.event.add").level)
        assertEquals(CapabilityLevel.RequiresSetup, ActionCapabilityRegistry.get("calendar.event.query").level)
        assertEquals(setOf(SetupRequirement.CALENDAR_WRITE), SetupRequirementResolver.forAction(ActionSpec(type = "calendar.event.add"), sdkInt = 36))
        assertEquals(setOf(SetupRequirement.CALENDAR), SetupRequirementResolver.forAction(ActionSpec(type = "calendar.event.query"), sdkInt = 36))
        assertEquals(setOf(AutomationPower.DATA_ACCESS), AutomationSensitivityRegistry.classify("calendar.event.query").powers)
        assertTrue(AutomationPower.EXTERNAL_TRANSMISSION in AutomationSensitivityRegistry.classify("calendar.event.add").powers)
        assertEquals(listOf("event_id"), declaredActionOutputs("calendar.event.add").mapNotNull { it.resolveName(emptyMap()) })
        assertTrue("event_titles" in declaredActionOutputs("calendar.event.query").mapNotNull { it.resolveName(emptyMap()) })
    }

    private companion object {
        val BOTH = setOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }
}
