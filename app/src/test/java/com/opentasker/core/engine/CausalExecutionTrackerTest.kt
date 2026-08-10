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

    @Test
    fun completedWorkStopsBeingACausalParent() {
        var nowMs = 1_000L
        val tracker = CausalExecutionTracker(attributionWindowMs = 30_000L, clock = { nowMs })

        val first = tracker.nextForProfile(1L, "Notify me")
        tracker.remember(envelope("Notify me", 1L, first, "run-1"), nowMs)
        tracker.forget("run-1")

        // Same profile fires again well inside the wall-clock window. The previous run already
        // finished, so this is an ordinary re-trigger and must not read as a self-cycle.
        nowMs += 10_000L
        val second = tracker.nextForProfile(1L, "Notify me")
        assertTrue("re-trigger after the parent finished must be allowed", second.allowed)
        assertNull(second.parentExecutionId)
        assertEquals(0, second.depth)
        assertEquals(listOf("Notify me"), second.profileChain)
    }

    @Test
    fun exitTaskOfTheRunningProfileIsNotACycle() {
        var nowMs = 1_000L
        val tracker = CausalExecutionTracker(attributionWindowMs = 30_000L, clock = { nowMs })

        val enter = tracker.nextForProfile(1L, "At home")
        tracker.remember(envelope("At home", 1L, enter, "enter-1"), nowMs)

        // Context deactivates while the enter task is still running.
        nowMs += 5_000L
        val exit = tracker.nextForProfile(1L, "At home", isExit = true)
        assertTrue("a profile's own exit task must not be blocked as a loop", exit.allowed)

        // A genuine A -> B -> A cycle that happens to land on an exit task is still stopped.
        tracker.remember(envelope("At home", 1L, exit, "exit-1"), nowMs)
        val other = tracker.nextForProfile(2L, "Away")
        tracker.remember(envelope("Away", 2L, other, "away-1"), nowMs)
        val loop = tracker.nextForProfile(1L, "At home", isExit = true)
        assertFalse("a real cycle ending on an exit task is still a cycle", loop.allowed)
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
