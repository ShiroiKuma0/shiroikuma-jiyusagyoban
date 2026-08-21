package com.opentasker.core.storage

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import com.opentasker.core.diagnostics.EngineHealthReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledWorkOutcomesTest {
    @Test
    fun anOutcomeSurvivesEncodingAndDecoding() {
        val outcome = ScheduledWorkOutcome(
            worker = ScheduledWorkerId.CONFIGURATION_SNAPSHOT,
            kind = ScheduledWorkOutcomeKind.STOPPED,
            stopReason = WorkInfo.STOP_REASON_QUOTA,
            timestampMillis = 1_723_000_000_000L,
        )

        val decoded = decodeScheduledWorkOutcome(
            ScheduledWorkerId.CONFIGURATION_SNAPSHOT,
            encodeScheduledWorkOutcome(outcome),
        )

        assertEquals(outcome, decoded)
    }

    @Test
    fun anUnreadableRowReadsAsNoRecordRatherThanAFalseOutcome() {
        listOf(
            null,
            "",
            "STOPPED",
            "STOPPED|not-a-number|1",
            "STOPPED|${WorkInfo.STOP_REASON_QUOTA}|0",
            "GAVE_UP|${WorkInfo.STOP_REASON_QUOTA}|1",
            "STOPPED|${WorkInfo.STOP_REASON_QUOTA}|1|extra",
        ).forEach { raw ->
            assertNull("Expected no outcome for '$raw'", decodeScheduledWorkOutcome(ScheduledWorkerId.UPDATE_CHECK, raw))
        }
    }

    @Test
    fun aQuotaStopIsDistinguishableFromCompletionAndFromFailure() {
        assertEquals(ScheduledWorkOutcomeKind.COMPLETED, scheduledWorkOutcomeKind(ListenableWorker.Result.success()))
        assertEquals(ScheduledWorkOutcomeKind.RETRYING, scheduledWorkOutcomeKind(ListenableWorker.Result.retry()))
        assertEquals(ScheduledWorkOutcomeKind.FAILED, scheduledWorkOutcomeKind(ListenableWorker.Result.failure()))
        assertEquals(
            "A failure that carries output data is still a failure",
            ScheduledWorkOutcomeKind.FAILED,
            scheduledWorkOutcomeKind(ListenableWorker.Result.failure(Data.Builder().putString("why", "no destination").build())),
        )

        val quotaStop = ScheduledWorkOutcome(
            worker = ScheduledWorkerId.ENGINE_WATCHDOG,
            kind = ScheduledWorkOutcomeKind.STOPPED,
            stopReason = WorkInfo.STOP_REASON_QUOTA,
            timestampMillis = 1L,
        )
        assertEquals("Out of run quota", EngineHealthReader.workerStopReasonLabel(quotaStop.stopReason))
        assertEquals("App standby bucket", EngineHealthReader.workerStopReasonLabel(WorkInfo.STOP_REASON_APP_STANDBY))
    }

    @Test
    fun everyWorkerKeyRoundTrips() {
        ScheduledWorkerId.entries.forEach { worker ->
            assertEquals(worker, ScheduledWorkerId.fromKey(worker.key))
        }
        assertNull(ScheduledWorkerId.fromKey("not_a_worker"))
        assertEquals(
            "Worker keys must be unique so one worker cannot overwrite another's record",
            ScheduledWorkerId.entries.size,
            ScheduledWorkerId.entries.map { it.key }.toSet().size,
        )
    }
}
