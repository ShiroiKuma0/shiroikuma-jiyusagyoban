package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.CalendarEventAddAction
import com.opentasker.core.actions.CalendarEventQueryAction
import com.opentasker.core.actions.CalendarEventRow
import com.opentasker.core.actions.CalendarInfo
import com.opentasker.core.actions.CalendarStore
import com.opentasker.core.actions.NewCalendarEvent
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A-359: calendar steps as the run log keeps them, with titles and locations hidden like a contact's details. */
class CalendarRunLogTest {
    private val store = object : CalendarStore {
        override fun calendars() = listOf(CalendarInfo(5, "Work", writable = true))
        override fun insert(event: NewCalendarEvent): Long = 41
        override fun instances(fromMs: Long, toMs: Long, calendarIds: Set<Long>?): List<CalendarEventRow> {
            val start = System.currentTimeMillis() + 3_600_000L
            return listOf(CalendarEventRow(41, 5, "Dentist with Dr. Kim", start, start + 1_800_000L, allDay = false, location = "12 Main St"))
        }
    }

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(CalendarEventAddAction({ _, _ -> true }, { store }))
        ActionRegistry.register(CalendarEventQueryAction({ _, _ -> true }, { store }))
    }

    @After
    fun tearDown() {
        registerCoreRuntime()
    }

    @Test
    fun theRunLogHidesEventTitlesAndLocations() {
        val logs = mutableListOf<String>()
        val task = Task(
            name = "Dentist",
            actions = listOf(
                ActionSpec(
                    type = "calendar.event.add",
                    args = mapOf("calendar" to "Work", "title" to "Dentist with Dr. Kim", "start" to "now", "location" to "12 Main St", "description" to "Bring the forms"),
                ),
                ActionSpec(type = "calendar.event.query"),
            ),
        )
        val report = runBlocking { TaskRunner(ActionContext(ContextWrapper(null), VariableStore(), logger = { logs += it })).run(task) }
        assertTrue(report.success)

        val serialized = runLogMessage(source = "manual", traces = report.traces)
        val changes = serialized.toRunLogDiagnostics().traces.flatMap { it.variableChanges }.associate { it.name to it.value }

        assertEquals("41", changes["event_id"])
        assertEquals("1", changes["event_count"])
        assertEquals("<redacted>", changes["event_title"])
        assertEquals("<redacted>", changes["event_location"])
        listOf("Dr. Kim", "Main St", "Bring the forms").forEach { secret ->
            assertFalse(secret, secret in serialized)
            assertFalse(secret, logs.any { secret in it })
        }
    }
}
