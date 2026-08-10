package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectBootTimeSchedulerTest {
    @Test
    fun nextMinuteBoundaryAlwaysMovesToTheFollowingMinute() {
        assertEquals(60_000L, DirectBootTimeScheduler.nextMinuteBoundaryMillis(0L))
        assertEquals(60_000L, DirectBootTimeScheduler.nextMinuteBoundaryMillis(59_999L))
        assertEquals(120_000L, DirectBootTimeScheduler.nextMinuteBoundaryMillis(60_000L))
        assertEquals(120_000L, DirectBootTimeScheduler.nextMinuteBoundaryMillis(60_001L))
    }
}
