package com.opentasker.core.engine

import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CausalExecutionTrackerTest {
    @Test
    fun twoProfileCycleCarriesParentAndIsStoppedWithNamedChain() {
        var nowMs = 1_000L
        val tracker = CausalExecutionTracker(
            maxDepth = 4,
            attributionWindowMs = 1_000L,
            clock = { nowMs },
        )

        val home = tracker.nextForProfile(1L, "Home")
        assertTrue(home.allowed)
        assertNull(home.parentExecutionId)
        assertEquals(0, home.depth)
        assertEquals(listOf("Home"), home.profileChain)
        tracker.remember(envelope("Home", 1L, home, "home-1"), nowMs)

        nowMs += 1
        val work = tracker.nextForProfile(2L, "Work")
        assertTrue(work.allowed)
        assertEquals("home-1", work.parentExecutionId)
        assertEquals(1, work.depth)
        assertEquals(listOf("Home", "Work"), work.profileChain)
        tracker.remember(envelope("Work", 2L, work, "work-1"), nowMs)

        nowMs += 1
        val cycle = tracker.nextForProfile(1L, "Home")
        assertFalse(cycle.allowed)
        assertEquals("work-1", cycle.parentExecutionId)
        assertEquals(2, cycle.depth)
        assertEquals(listOf("Home", "Work", "Home"), cycle.profileChain)
        assertEquals("Causal profile cycle stopped: Home -> Work -> Home", cycle.blockedReason)
    }

    @Test
    fun fixedDepthStopsAcyclicChainAndAttributionExpires() {
        var nowMs = 0L
        val tracker = CausalExecutionTracker(
            maxDepth = 1,
            attributionWindowMs = 5L,
            clock = { nowMs },
        )

        val first = tracker.nextForProfile(profileId = 1L, profileName = "One")
        tracker.remember(envelope("One", 1L, first, "one-1"), nowMs)
        val second = tracker.nextForProfile(profileId = 2L, profileName = "Two")
        assertTrue(second.allowed)
        tracker.remember(envelope("Two", 2L, second, "two-1"), nowMs)

        val tooDeep = tracker.nextForProfile(profileId = 3L, profileName = "Three")
        assertFalse(tooDeep.allowed)
        assertTrue(requireNotNull(tooDeep.blockedReason).contains("depth limit (1)"))
        assertTrue(requireNotNull(tooDeep.blockedReason).contains("One -> Two -> Three"))

        nowMs = 6L
        val fresh = tracker.nextForProfile(profileId = 3L, profileName = "Three")
        assertTrue(fresh.allowed)
        assertNull(fresh.parentExecutionId)
        assertEquals(0, fresh.depth)
        assertEquals(listOf("Three"), fresh.profileChain)
    }

    private fun envelope(
        profileName: String,
        profileId: Long,
        decision: CausalExecutionDecision,
        executionId: String,
    ): ExecutionEnvelope = ExecutionEnvelope.create(
        task = Task(id = profileId, name = profileName),
        source = "Profile: $profileName",
        profileId = profileId,
        parentExecutionId = decision.parentExecutionId,
        causalDepth = decision.depth,
        causalProfileChain = decision.profileChain,
        executionId = executionId,
    )
}
