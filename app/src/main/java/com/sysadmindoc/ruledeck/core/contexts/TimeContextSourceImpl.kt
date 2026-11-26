package com.sysadmindoc.ruledeck.core.contexts

import android.content.Context
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive

/** Alarm-backed time pulses shared by the receiver, service, and time/day context source. */
object TimeContextEvents {
    private val ticks = MutableSharedFlow<Long>(extraBufferCapacity = 4)

    fun publish(nowMillis: Long = System.currentTimeMillis()) {
        ticks.tryEmit(nowMillis)
    }

    internal fun events(): Flow<Long> = ticks
}

/**
 * Time context source driven by both an in-process minute boundary and AlarmManager wake pulses.
 * The coroutine path keeps visible/running-service updates prompt; the alarm path survives Doze.
 */
class TimeContextSourceImpl : ContextSource {
    override val type = "time"

    override fun events(app: Context): Flow<ContextEvent> = merge(
        flow {
            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                emit(timeContextEventAt(now))
                delay(millisUntilNextMinute(now))
            }
        },
        TimeContextEvents.events().map(::timeContextEventAt),
    ).distinctUntilChanged { previous, current -> previous.metadata == current.metadata }
}

internal fun timeContextEventAt(
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
): ContextEvent {
    val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
    return ContextEvent(
        type = "time",
        matched = true,
        metadata = mapOf(
            "time" to "%02d:%02d".format(
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
            ),
            "day" to DaySchedule.tokenFor(calendar),
        ),
    )
}

internal fun millisUntilNextMinute(nowMillis: Long): Long =
    60_000L - Math.floorMod(nowMillis, 60_000L)
