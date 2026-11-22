package com.opentasker.core.actions

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * A-359 against the phone's real Calendar Provider: an event added by name lands on that calendar
 * with the bounds asked for, and a query finds it again. Runs on a throwaway local calendar that
 * is deleted afterwards, events and all.
 */
class CalendarActionsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver: ContentResolver = context.contentResolver

    @Test
    fun anAddedEventLandsOnTheNamedCalendarAndAQueryFindsIt() = runBlocking {
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val name = "OpenTasker test ${System.nanoTime()}"
        val calendarId = createLocalCalendar(name)
        try {
            val variables = VariableStore()
            val ctx = ActionContext(context, variables, logger = {})
            val start = (System.currentTimeMillis() / 60_000L + 120L) * 60_000L

            val added = CalendarEventAddAction().run(
                ctx,
                mapOf("calendar" to name, "title" to "Checkup", "start" to start.toString(), "duration" to "45", "location" to "Clinic"),
            )
            assertEquals(ActionResult.Success, added)
            val eventId = variables.get("event_id")!!.toLong()
            resolver.query(
                ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                arrayOf(Events.CALENDAR_ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.EVENT_LOCATION),
                null,
                null,
                null,
            )!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(calendarId, cursor.getLong(0))
                assertEquals("Checkup", cursor.getString(1))
                assertEquals(start, cursor.getLong(2))
                assertEquals(start + 45 * 60_000L, cursor.getLong(3))
                assertEquals("Clinic", cursor.getString(4))
            }

            val found = CalendarEventQueryAction().run(ctx, mapOf("calendar" to calendarId.toString(), "max" to "5"))
            assertEquals(ActionResult.Success, found)
            assertEquals("1", variables.get("event_count"))
            assertEquals(eventId.toString(), variables.get("event_id"))
            assertEquals("Checkup", variables.get("event_title"))
            assertEquals(start.toString(), variables.get("event_start"))
            assertEquals(calendarId.toString(), variables.get("event_calendar_id"))

            // An all-day event two days out, found by asking for just that day where the phone is.
            val day = LocalDate.now(ZoneId.systemDefault()).plusDays(2)
            assertEquals(ActionResult.Success, CalendarEventAddAction().run(ctx, mapOf("calendar" to name, "title" to "Trip", "start" to day.toString(), "all_day" to "true")))
            val wholeDay = CalendarEventQueryAction().run(ctx, mapOf("calendar" to name, "from" to day.toString(), "to" to "$day 23:59"))
            assertEquals(ActionResult.Success, wholeDay)
            assertEquals(listOf("Trip"), variables.getArrayItems("event_titles"))
            assertEquals("true", variables.get("event_all_day"))
            assertEquals(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli().toString(), variables.get("event_start"))
        } finally {
            resolver.delete(syncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId)), null, null)
        }
    }

    private fun createLocalCalendar(name: String): Long {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, name)
            put(Calendars.CALENDAR_DISPLAY_NAME, name)
            put(Calendars.CALENDAR_COLOR, 0xFF3366CC.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }
        return ContentUris.parseId(resolver.insert(syncAdapter(Calendars.CONTENT_URI), values)!!)
    }

    private fun syncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    private companion object {
        const val ACCOUNT = "opentasker-test"
    }
}
