package com.opentasker.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the **persisted** heartbeat — [EngineHeartbeatSnapshot] + [needsRecovery], the reboot-survivable
 * record behind [EngineHeartbeatStore]. Not to be confused with the fork's in-memory `EngineHeartbeat`
 * object, which took that name for the +92 alarm-resurrect model and forced the rename to `…Snapshot`.
 */
class EngineHeartbeatTest {
    @Test
    fun healthyRecentHeartbeatNeedsNoRecovery() {
        val heartbeat = EngineHeartbeatSnapshot(lastAliveAtMillis = 100_000L, stoppedCleanly = false)

        assertFalse(heartbeat.needsRecovery(nowMillis = 100_000L + EngineHeartbeatStore.STALE_AFTER_MS - 1))
    }

    @Test
    fun timeoutAndMissingOrStaleHeartbeatsNeedRecovery() {
        assertTrue(EngineHeartbeatSnapshot(100_000L, stoppedCleanly = true).needsRecovery(100_001L))
        assertTrue(EngineHeartbeatSnapshot(0L, stoppedCleanly = false).needsRecovery(100_001L))
        assertTrue(
            EngineHeartbeatSnapshot(100_000L, stoppedCleanly = false)
                .needsRecovery(100_000L + EngineHeartbeatStore.STALE_AFTER_MS),
        )
    }
}
