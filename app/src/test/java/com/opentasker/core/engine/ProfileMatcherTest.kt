package com.opentasker.core.engine

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileMatcherTest {
    @Test
    fun repeatedMatchingEventPulsesActivateEachTime() = runBlocking {
        val changes = profileStateChangesFromSnapshots(
            snapshots = flowOf(
                ProfileMatchSnapshot(allMatched = false, pulseSequence = 0),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 1),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 2),
            ),
            hasPulseContexts = true,
        ).toList()

        assertEquals(
            listOf(ProfileStateChange.Activated, ProfileStateChange.Activated),
            changes,
        )
    }

    @Test
    fun eventPulseDoesNotActivateRetroactivelyWhenLevelContextMatchesLater() = runBlocking {
        val changes = profileStateChangesFromSnapshots(
            snapshots = flowOf(
                ProfileMatchSnapshot(allMatched = false, pulseSequence = 0),
                ProfileMatchSnapshot(allMatched = false, pulseSequence = 1),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 1),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 2),
            ),
            hasPulseContexts = true,
        ).toList()

        assertEquals(listOf(ProfileStateChange.Activated), changes)
    }

    @Test
    fun levelContextsKeepActivationAndDeactivationTransitions() = runBlocking {
        val changes = profileStateChangesFromSnapshots(
            snapshots = flowOf(
                ProfileMatchSnapshot(allMatched = false, pulseSequence = 0),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 0),
                ProfileMatchSnapshot(allMatched = true, pulseSequence = 0),
                ProfileMatchSnapshot(allMatched = false, pulseSequence = 0),
            ),
            hasPulseContexts = false,
        ).toList()

        assertEquals(
            listOf(ProfileStateChange.Activated, ProfileStateChange.Deactivated),
            changes,
        )
    }
}
