package com.opentasker.core.diagnostics

import com.opentasker.core.engine.EngineHeartbeatSnapshot
import com.opentasker.core.engine.EngineExitCorrelationState
import com.opentasker.core.engine.HistoricalProcessExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineExitCorrelationTest {
    @Test
    fun fakeHistoricalExitSourcePairsTheFirstExitWithAStaleHeartbeat() {
        val fakeExitSource = HistoricalProcessExitRead(
            platformAvailable = true,
            records = listOf(
                HistoricalProcessExit(5_000L, "Java crash", "uncaught exception"),
                HistoricalProcessExit(2_000L, "Low memory"),
                HistoricalProcessExit(500L, "Older exit"),
            ),
        )

        val correlation = EngineHealthReader.correlateProcessExit(
            heartbeat = EngineHeartbeatSnapshot(lastAliveAtMillis = 1_000L, stoppedCleanly = false),
            nowMillis = 10_000L,
            read = fakeExitSource,
            staleAfterMillis = 1_000L,
        )

        assertEquals(EngineExitCorrelationState.MATCHED, correlation.state)
        assertEquals("Low memory", correlation.reason)
        assertEquals(2_000L, correlation.timestampMillis)
        assertEquals(9_000L, correlation.gapMillis)
        assertNull(correlation.description)
    }

    @Test
    fun staleHeartbeatWithoutRecordIsDistinguishedFromUnsupportedPlatform() {
        val heartbeat = EngineHeartbeatSnapshot(lastAliveAtMillis = 1_000L, stoppedCleanly = false)
        val noRecord = EngineHealthReader.correlateProcessExit(
            heartbeat = heartbeat,
            nowMillis = 10_000L,
            read = HistoricalProcessExitRead(platformAvailable = true, records = emptyList()),
            staleAfterMillis = 1_000L,
        )
        val unsupported = EngineHealthReader.correlateProcessExit(
            heartbeat = heartbeat,
            nowMillis = 10_000L,
            read = HistoricalProcessExitRead(platformAvailable = false, records = emptyList()),
            staleAfterMillis = 1_000L,
        )

        assertEquals(EngineExitCorrelationState.NO_MATCH, noRecord.state)
        assertTrue((noRecord.gapMillis ?: 0L) > 0L)
        assertEquals(EngineExitCorrelationState.UNAVAILABLE, unsupported.state)
    }

    @Test
    fun cleanOrCurrentHeartbeatDoesNotInventAnExit() {
        val current = EngineHealthReader.correlateProcessExit(
            heartbeat = EngineHeartbeatSnapshot(lastAliveAtMillis = 9_500L, stoppedCleanly = false),
            nowMillis = 10_000L,
            read = HistoricalProcessExitRead(
                platformAvailable = true,
                records = listOf(HistoricalProcessExit(9_900L, "Low memory")),
            ),
            staleAfterMillis = 1_000L,
        )
        val clean = EngineHealthReader.correlateProcessExit(
            heartbeat = EngineHeartbeatSnapshot(lastAliveAtMillis = 1_000L, stoppedCleanly = true),
            nowMillis = 10_000L,
            read = HistoricalProcessExitRead(
                platformAvailable = true,
                records = listOf(HistoricalProcessExit(2_000L, "Low memory")),
            ),
            staleAfterMillis = 1_000L,
        )

        assertEquals(EngineExitCorrelationState.NO_GAP, current.state)
        assertEquals(EngineExitCorrelationState.NO_GAP, clean.state)
    }
}
