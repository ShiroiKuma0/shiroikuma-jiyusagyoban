package com.opentasker.core.engine

import com.opentasker.core.contexts.ContextEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseEventContinuityTest {
    @Test
    fun replayedEventIsIgnoredForTheSameContextButDeliveredToAnotherContext() {
        val continuity = PulseEventContinuity()
        val event = ContextEvent(
            type = "event",
            matched = true,
            metadata = mapOf("event" to "share", "observedAtEpochMs" to "100"),
        )

        val first = continuity.observe(contextIndex = 0, event)
        val replay = continuity.observe(contextIndex = 0, event)
        val otherContext = continuity.observe(contextIndex = 1, event)

        assertFalse(first.duplicate)
        assertTrue(replay.duplicate)
        assertFalse(otherContext.duplicate)
        assertEquals(first.sequence, replay.sequence)
        assertEquals(first.sequence, otherContext.sequence)
    }

    @Test
    fun nonReplayablePulsesContinueAcrossMatcherRebuilds() {
        val continuity = PulseEventContinuity(initialSequence = 4)
        val event = ContextEvent(type = "event", matched = true, metadata = mapOf("event" to "shake"))

        val first = continuity.observe(contextIndex = 0, event)
        val rebuilt = continuity.observe(contextIndex = 0, event)

        assertEquals(5, first.sequence)
        assertEquals(6, rebuilt.sequence)
    }
}
