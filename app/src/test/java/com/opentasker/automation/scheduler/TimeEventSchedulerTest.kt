package com.opentasker.automation.scheduler

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
}
