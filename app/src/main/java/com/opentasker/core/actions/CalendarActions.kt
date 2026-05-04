package com.opentasker.core.actions

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import com.opentasker.core.engine.Action
import java.util.TimeZone

/**
 * Calendar-related actions: read events, create events, send invites.
 * Requires READ_CALENDAR and WRITE_CALENDAR permissions.
 */

class ReadCalendarEventsAction : Action {
    override val id = "read_calendar_events"
    override val label = "Read Calendar Events"
    override val description = "Read events from a calendar date range"

    override suspend fun execute(context: Context, params: Map<String, String>): Result {
        val startDateStr = params["start_date"] ?: ""
        val endDateStr = params["end_date"] ?: ""
        val calendarId = params["calendar_id"] ?: "1"
        val maxResults = params["max_results"]?.toIntOrNull() ?: 50

        return try {
            if (startDateStr.isEmpty() || endDateStr.isEmpty()) {
                return Result(success = false, message = "start_date and end_date required")
            }

            val startTime = android.text.format.DateFormat.parse(startDateStr)?.time ?: 0
            val endTime = android.text.format.DateFormat.parse(endDateStr)?.time ?: 0

            val resolver = context.contentResolver
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION
            )
            val selection = "${CalendarContract.Events.CALENDAR_ID} = ? " +
                "AND ${CalendarContract.Events.DTSTART} >= ? " +
                "AND ${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(calendarId, startTime.toString(), endTime.toString())

            val cursor = resolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC LIMIT $maxResults"
            ) ?: return Result(success = false, message = "Failed to query calendar")

            val events = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val title = cursor.getString(cursor.getColumnIndex(CalendarContract.Events.TITLE)) ?: ""
                val startMs = cursor.getLong(cursor.getColumnIndex(CalendarContract.Events.DTSTART))
                events.add("$title@${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", startMs)}")
            }
            cursor.close()

            Result(success = true, message = "Found ${events.size} events: ${events.joinToString("; ")}")
        } catch (e: Exception) {
            Result(success = false, message = "Error reading calendar: ${e.message}")
        }
    }

    override fun metadata() = ActionMetadata(
        id = id,
        label = label,
        description = description,
        args = listOf(
            ActionArg("start_date", "Start Date (ISO 8601)", "text", "2026-05-01"),
            ActionArg("end_date", "End Date (ISO 8601)", "text", "2026-05-31"),
            ActionArg("calendar_id", "Calendar ID", "text", "1"),
            ActionArg("max_results", "Max Results", "number", "50")
        ),
        permissions = listOf("android.permission.READ_CALENDAR")
    )
}

class CreateCalendarEventAction : Action {
    override val id = "create_calendar_event"
    override val label = "Create Calendar Event"
    override val description = "Create a new calendar event"

    override suspend fun execute(context: Context, params: Map<String, String>): Result {
        val title = params["title"] ?: ""
        val description = params["description"] ?: ""
        val location = params["location"] ?: ""
        val startDateStr = params["start_date"] ?: ""
        val endDateStr = params["end_date"] ?: ""
        val calendarId = params["calendar_id"] ?: "1"

        return try {
            if (title.isEmpty() || startDateStr.isEmpty()) {
                return Result(success = false, message = "title and start_date required")
            }

            val startTime = android.text.format.DateFormat.parse(startDateStr)?.time ?: return Result(
                success = false,
                message = "Invalid start_date format"
            )
            val endTime = if (endDateStr.isNotEmpty()) {
                android.text.format.DateFormat.parse(endDateStr)?.time ?: (startTime + 3600000)
            } else {
                startTime + 3600000 // Default 1 hour duration
            }

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.DTSTART, startTime)
                put(CalendarContract.Events.DTEND, endTime)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val eventId = ContentUris.parseId(uri)
                Result(success = true, message = "Event created (ID: $eventId)")
            } else {
                Result(success = false, message = "Failed to create event")
            }
        } catch (e: Exception) {
            Result(success = false, message = "Error creating event: ${e.message}")
        }
    }

    override fun metadata() = ActionMetadata(
        id = id,
        label = label,
        description = description,
        args = listOf(
            ActionArg("title", "Event Title", "text", "Team Meeting"),
            ActionArg("description", "Description", "text", ""),
            ActionArg("location", "Location", "text", ""),
            ActionArg("start_date", "Start Date (ISO 8601)", "text", "2026-05-05T09:00:00"),
            ActionArg("end_date", "End Date (ISO 8601)", "text", "2026-05-05T10:00:00"),
            ActionArg("calendar_id", "Calendar ID", "text", "1")
        ),
        permissions = listOf("android.permission.WRITE_CALENDAR")
    )
}

class ReadClipboardAction : Action {
    override val id = "read_clipboard"
    override val label = "Read Clipboard"
    override val description = "Read text from clipboard"

    override suspend fun execute(context: Context, params: Map<String, String>): Result {
        return try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return Result(success = false, message = "Clipboard unavailable")

            val primaryClip = clipboardManager.primaryClip
            val text = if (primaryClip != null && primaryClip.itemCount > 0) {
                primaryClip.getItemAt(0).text?.toString() ?: ""
            } else {
                ""
            }

            Result(success = text.isNotEmpty(), message = text)
        } catch (e: Exception) {
            Result(success = false, message = "Error reading clipboard: ${e.message}")
        }
    }

    override fun metadata() = ActionMetadata(
        id = id,
        label = label,
        description = description,
        args = emptyList(),
        permissions = listOf("android.permission.READ_CLIPBOARD")
    )
}

class WriteClipboardAction : Action {
    override val id = "write_clipboard"
    override val label = "Write Clipboard"
    override val description = "Write text to clipboard"

    override suspend fun execute(context: Context, params: Map<String, String>): Result {
        val text = params["text"] ?: ""

        return try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return Result(success = false, message = "Clipboard unavailable")

            val clip = android.content.ClipData.newPlainText("text", text)
            clipboardManager.setPrimaryClip(clip)

            Result(success = true, message = "Clipboard updated (${text.length} chars)")
        } catch (e: Exception) {
            Result(success = false, message = "Error writing clipboard: ${e.message}")
        }
    }

    override fun metadata() = ActionMetadata(
        id = id,
        label = label,
        description = description,
        args = listOf(
            ActionArg("text", "Text to Copy", "text", "")
        ),
        permissions = emptyList()
    )
}

class BluetoothDiscoveryAction : Action {
    override val id = "bluetooth_discovery"
    override val label = "Bluetooth Discovery"
    override val description = "Scan for nearby Bluetooth devices"

    override suspend fun execute(context: Context, params: Map<String, String>): Result {
        return try {
            val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                ?: return Result(success = false, message = "Bluetooth not available")

            if (!bluetoothAdapter.isEnabled) {
                return Result(success = false, message = "Bluetooth is disabled")
            }

            val pairedDevices = bluetoothAdapter.bondedDevices
            val deviceList = pairedDevices.map { "${it.name} (${it.address})" }

            Result(
                success = true,
                message = "Found ${deviceList.size} paired devices: ${deviceList.joinToString("; ")}"
            )
        } catch (e: Exception) {
            Result(success = false, message = "Error scanning Bluetooth: ${e.message}")
        }
    }

    override fun metadata() = ActionMetadata(
        id = id,
        label = label,
        description = description,
        args = emptyList(),
        permissions = listOf(
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN"
        )
    )
}
