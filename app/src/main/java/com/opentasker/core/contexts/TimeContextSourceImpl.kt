package com.opentasker.core.contexts

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlinx.coroutines.delay

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

        while (!isClosedForSend) {
            val cal = Calendar.getInstance()
            val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            if (now != lastMinute) {
                lastMinute = now
                trySend(
                    ContextEvent(
                        type,
                        true,
                        mapOf("time" to "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)))
                    )
                )
            }
            delay(1000)
        }

        awaitClose()
    }
}

fun timeMatches(from: String, to: String): Boolean {
    val cal = Calendar.getInstance()
    val now = cal.get(Calendar.HOUR_OF_DAY) * 100 + cal.get(Calendar.MINUTE)
    val fromParts = from.split(":").map { it.toInt() }
    val toParts = to.split(":").map { it.toInt() }
    val fh = fromParts[0] * 100
    val fm = fromParts.getOrNull(1) ?: 0
    val th = toParts[0] * 100
    val tm = toParts.getOrNull(1) ?: 0
    val fromMin = fh + fm
    val toMin = th + tm
    return if (fromMin <= toMin) now in fromMin..toMin else (now >= fromMin || now <= toMin)
}
