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
