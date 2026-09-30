package com.opentasker.automation.scheduler

import com.opentasker.core.scheduling.AlarmSchedulePrecision
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeEventSchedulerTest {
    @Test
    fun nextMinuteBoundaryRoundsUpFromMiddleOfMinute() {
        assertEquals(120_000L, TimeEventScheduler.nextMinuteBoundaryMillis(61_234L))
    }

    @Test
    fun nextMinuteBoundaryAdvancesWhenAlreadyOnBoundary() {
        assertEquals(180_000L, TimeEventScheduler.nextMinuteBoundaryMillis(120_000L))
    }

    @Test
    fun recoveryAlarmIsScheduledPromptlyAfterTimeout() {
        assertEquals(15_000L, TimeEventScheduler.recoveryTriggerAtMillis(10_000L))
    }

    @Test
    fun aBackedOffRecoveryNeverReplacesTheNextMinuteTickWithALaterAlarm() {
        // Recovery and the minute tick share one PendingIntent, so a 5-minute backoff booked at
        // 00:10 used to cancel the 01:00 tick and leave time triggers dark until 05:10.
        assertEquals(60_000L, TimeEventScheduler.recoveryTriggerAtMillis(10_000L, delayMillis = 300_000L))
        assertEquals(60_000L, TimeEventScheduler.recoveryTriggerAtMillis(50_000L, delayMillis = 30_000L))
        assertEquals(40_000L, TimeEventScheduler.recoveryTriggerAtMillis(10_000L, delayMillis = 30_000L))
    }

    @Test
    fun alarmPermissionLossUsesInexactFallbackAndExactAccessKeepsExactMode() {
        assertEquals(
            AlarmSchedulePrecision.InexactFallback,
            TimeEventScheduler.scheduleMode(AlarmSchedulePrecision.InexactFallback),
        )
        assertEquals(
            AlarmSchedulePrecision.Exact,
            TimeEventScheduler.scheduleMode(AlarmSchedulePrecision.Exact),
        )
    }
}
