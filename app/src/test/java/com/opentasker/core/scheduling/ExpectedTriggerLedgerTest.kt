package com.opentasker.core.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectedTriggerLedgerTest {
    @Test
    fun overdueTriggerIsConsumedOnceWithDelay() {
        val tracker = ExpectedTriggerTracker()
        tracker.recordExpected(ExpectedTriggerKind.MINUTE_TICK, expectedAtMillis = 60_000L, nowMillis = 0L)

        assertNull(tracker.consumeMissed(59_999L))
        val missed = tracker.consumeMissed(180_000L)

        assertEquals(ExpectedTriggerKind.MINUTE_TICK, missed?.kind)
        assertEquals(60_000L, missed?.expectedAtMillis)
        assertEquals(180_000L, missed?.detectedAtMillis)
        assertEquals(120_000L, missed?.delayMillis)
        assertNull(tracker.consumeMissed(240_000L))
    }

    @Test
    fun reschedulingAfterGapDefersTheOldExpectedFire() {
        val tracker = ExpectedTriggerTracker()
        tracker.recordExpected(ExpectedTriggerKind.MINUTE_TICK, expectedAtMillis = 60_000L, nowMillis = 0L)
        tracker.recordExpected(ExpectedTriggerKind.RECOVERY, expectedAtMillis = 125_000L, nowMillis = 120_000L)

        val missed = tracker.consumeMissed(120_000L)

        assertEquals(60_000L, missed?.expectedAtMillis)
        assertTrue((missed?.delayMillis ?: 0L) > 0L)
    }

    @Test
    fun deliveredExpectedFireIsNotReportedAsMissed() {
        val tracker = ExpectedTriggerTracker()
        tracker.recordExpected(ExpectedTriggerKind.MINUTE_TICK, expectedAtMillis = 60_000L, nowMillis = 0L)
        tracker.markDelivered(actualAtMillis = 65_000L)

        assertNull(tracker.consumeMissed(180_000L))
    }

    @Test
    fun reportedMissCanBeRequeuedWhenLogWriteFails() {
        val tracker = ExpectedTriggerTracker()
        tracker.recordExpected(ExpectedTriggerKind.MINUTE_TICK, expectedAtMillis = 60_000L, nowMillis = 0L)
        val missed = requireNotNull(tracker.consumeMissed(120_000L))
        tracker.requeue(missed)

        assertEquals(60_000L, tracker.consumeMissed(120_000L)?.expectedAtMillis)
    }
}
