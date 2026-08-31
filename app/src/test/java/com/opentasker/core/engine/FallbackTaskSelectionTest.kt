package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order a failed task's recovery is looked for in. The database lookup around it is mechanical;
 * this is the part that decides what runs, so it is the part worth pinning down.
 */
class FallbackTaskSelectionTest {
    @Test
    fun theProfilesOwnRecoveryTaskOutranksTheGlobalOne() {
        assertEquals(
            listOf(7L to "profile", 9L to "global"),
            fallbackCandidateIds(profileFallbackTaskId = 7L, globalFallbackTaskId = 9L, failedTaskId = 1L),
        )
    }

    @Test
    fun theGlobalRecoveryTaskIsUsedWhenTheProfileNamesNone() {
        assertEquals(
            listOf(9L to "global"),
            fallbackCandidateIds(profileFallbackTaskId = null, globalFallbackTaskId = 9L, failedTaskId = 1L),
        )
        // 0 is "unset" in the stored column, not a task id.
        assertEquals(
            listOf(9L to "global"),
            fallbackCandidateIds(profileFallbackTaskId = 0L, globalFallbackTaskId = 9L, failedTaskId = 1L),
        )
    }

    @Test
    fun aTaskIsNeverItsOwnRecovery() {
        // Both slots point at the task that just failed: recovering it with itself would re-run the
        // failure, handed its own error as input.
        assertEquals(
            emptyList<Pair<Long, String>>(),
            fallbackCandidateIds(profileFallbackTaskId = 5L, globalFallbackTaskId = 5L, failedTaskId = 5L),
        )
        // The global one still stands when only the profile's choice is the failed task itself.
        assertEquals(
            listOf(9L to "global"),
            fallbackCandidateIds(profileFallbackTaskId = 5L, globalFallbackTaskId = 9L, failedTaskId = 5L),
        )
    }

    @Test
    fun theSameTaskIsNotTriedTwiceUnderTwoNames() {
        assertEquals(
            listOf(7L to "profile"),
            fallbackCandidateIds(profileFallbackTaskId = 7L, globalFallbackTaskId = 7L, failedTaskId = 1L),
        )
    }

    @Test
    fun noRecoveryConfiguredMeansNothingToTry() {
        assertEquals(
            emptyList<Pair<Long, String>>(),
            fallbackCandidateIds(profileFallbackTaskId = null, globalFallbackTaskId = null, failedTaskId = 1L),
        )
    }
}
