package com.opentasker.core.contexts

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/**
 * Time-of-day context. Matches when the current time falls within [config["from"]] and [config["to"]].
 *
 * Config keys:
 *   - "from": HH:MM (e.g., "09:00")
 *   - "to": HH:MM (e.g., "17:00")
 *   - "days": optional day mask (SMTWTFS)
 */
class TimeContextSource : ContextSource {
    override val type = "time"

    override fun events(app: Context): Flow<ContextEvent> {
        val state = MutableStateFlow(ContextEvent(type, false))
        // TODO: Wire up a WorkManager or Handler-based poller that checks time every minute
        // For now, return a cold flow
        return state.asStateFlow()
    }
}

/**
 * Day-of-week context. Matches on specific days (SMTWTFS bitmask).
 *
 * Config keys:
 *   - "days": bitmask string like "SMTWTFS" or "------S" for Sunday only
 */
class DayContextSource : ContextSource {
    override val type = "day"

    override fun events(app: Context): Flow<ContextEvent> {
        val state = MutableStateFlow(ContextEvent(type, matchesToday()))
        return state.asStateFlow()
    }

    private fun matchesToday(): Boolean {
        val cal = Calendar.getInstance()
        val today = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> 0
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> -1
        }
        return true // TODO: check against config day mask
    }
}
