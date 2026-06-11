package com.opentasker.core.contexts

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootContextEventsTest {
    @After
    fun tearDown() {
        BootContextEvents.clearPendingForTests()
    }

    @Test
    fun buildEventMarksBootCompletedPulse() {
        val event = BootContextEvents.buildEvent(nowMs = 1_234L)

        assertEquals("event", event.type)
        assertTrue(event.matched)
        assertEquals("boot_completed", event.metadata["event"])
        assertEquals("1234", event.metadata["observedAtEpochMs"])
    }

    @Test
    fun freshBootPulseReplaysForNewSubscribers() = runBlocking {
        BootContextEvents.publishBootCompleted(nowMs = 1_000L)

        val event = BootContextEvents.events { 1_500L }.first()

        assertEquals("boot_completed", event.metadata["event"])
        assertEquals("1000", event.metadata["observedAtEpochMs"])
    }

    @Test
    fun staleBootPulseIsNotReplayedToLaterSubscribers() = runBlocking {
        BootContextEvents.publishBootCompleted(nowMs = 1_000L)

        val event = withTimeoutOrNull(100L) {
            BootContextEvents.events { 1_000L + BootContextEvents.PENDING_PULSE_REPLAY_MS + 1 }.first()
        }

        assertNull(event)
    }
}
