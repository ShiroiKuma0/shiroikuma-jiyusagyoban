package com.opentasker.core.contexts

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Real TimeContextSource with minute-by-minute clock updates.
 *
 * Config:
 *   - "from": HH:MM (e.g., "09:00")
 *   - "to": HH:MM (e.g., "17:00")
 *   - "days": optional day mask (SMTWTFS, default all days)
 */
class TimeContextSourceImpl : ContextSource {
    override val type = "time"

    override fun events(app: Context): Flow<ContextEvent> = callbackFlow {
        var lastMinute = -1

        val tickJob = launch {
            while (isActive) {
                val cal = Calendar.getInstance()
                val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                if (now != lastMinute) {
                    lastMinute = now
                    trySend(
                        ContextEvent(
                            type,
                            true,
                            mapOf(
                                "time" to "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)),
                                "day" to DaySchedule.tokenFor(cal),
                            )
                        )
                    )
                }
                delay(1000)
            }
        }

        awaitClose { tickJob.cancel() }
    }
}

fun timeMatches(from: String, to: String): Boolean {
    val cal = Calendar.getInstance()
    val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val fromMin = parseClockMinutes(from) ?: return false
    val toMin = parseClockMinutes(to) ?: return false
    return if (fromMin <= toMin) now in fromMin..toMin else (now >= fromMin || now <= toMin)
}

private fun parseClockMinutes(value: String): Int? {
    val parts = value.trim().split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
