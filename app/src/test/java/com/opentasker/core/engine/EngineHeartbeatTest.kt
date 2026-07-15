package com.opentasker.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineHeartbeatTest {
    @Test
    fun healthyRecentHeartbeatNeedsNoRecovery() {
        val heartbeat = EngineHeartbeat(lastAliveAtMillis = 100_000L, stoppedCleanly = false)

        assertFalse(heartbeat.needsRecovery(nowMillis = 100_000L + EngineHeartbeatStore.STALE_AFTER_MS - 1))
    }

    @Test
    fun timeoutAndMissingOrStaleHeartbeatsNeedRecovery() {
        assertTrue(EngineHeartbeat(100_000L, stoppedCleanly = true).needsRecovery(100_001L))
        assertTrue(EngineHeartbeat(0L, stoppedCleanly = false).needsRecovery(100_001L))
        assertTrue(
            EngineHeartbeat(100_000L, stoppedCleanly = false)
                .needsRecovery(100_000L + EngineHeartbeatStore.STALE_AFTER_MS),
        )
    }
}
