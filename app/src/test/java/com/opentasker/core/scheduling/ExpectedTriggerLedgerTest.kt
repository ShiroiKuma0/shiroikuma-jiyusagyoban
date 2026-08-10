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

/**
 * The scheduler owned by the long-lived service, the per-tick broadcast receiver, and the watchdog
 * worker each build their own [ExpectedTriggerLedger] over one shared store. Regression cover for
 * the split-brain that re-filed every healthy tick as a missed trigger.
 */
class ExpectedTriggerLedgerPersistenceTest {
    @Test
    fun deliveredTicksAreNeverReportedAsMissedAcrossLedgerInstances() {
        val store = InMemoryExpectedTriggerStateStore()
        // The service keeps one long-lived scheduler; its ledger outlives every tick.
        val serviceLedger = ExpectedTriggerLedger(store)
        var now = 60_000L

        serviceLedger.recordExpected(ExpectedTriggerKind.MINUTE_TICK, expectedAtMillis = now, nowMillis = now - 60_000L)

        repeat(5) {
            // Each tick: a fresh receiver marks delivery, then the service re-arms the next minute.
            ExpectedTriggerLedger(store).markDelivered(now)
            serviceLedger.recordExpected(ExpectedTriggerKind.MINUTE_TICK, expectedAtMillis = now + 60_000L, nowMillis = now)
            now += 60_000L

            // The watchdog runs on its own instance and must find nothing to report.
            assertNull(
                "a delivered tick must never be reported as missed",
                ExpectedTriggerLedger(store).consumeMissed(now),
            )
        }
    }

    @Test
    fun genuinelyMissedTicksAreStillReportedOnce() {
        val store = InMemoryExpectedTriggerStateStore()
        val serviceLedger = ExpectedTriggerLedger(store)

        serviceLedger.recordExpected(ExpectedTriggerKind.MINUTE_TICK, expectedAtMillis = 60_000L, nowMillis = 0L)
        // No receiver ever marks delivery: the tick really was dropped.
        val missed = ExpectedTriggerLedger(store).consumeMissed(180_000L)

        assertEquals(ExpectedTriggerKind.MINUTE_TICK, missed?.kind)
        assertEquals(120_000L, missed?.delayMillis)
        assertNull("a missed tick is reported once, not every watchdog pass", ExpectedTriggerLedger(store).consumeMissed(240_000L))
    }
}
