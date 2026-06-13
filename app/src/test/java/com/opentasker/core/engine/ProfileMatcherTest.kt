package com.opentasker.core.engine

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun match(matched: Boolean) = ContextMatchUpdate(matched, pulseContext = false, pulseSequence = 0)
    private fun spec(orGroup: String? = null) = ContextSpec(ContextType.STATE, orGroup = orGroup)

    @Test
    fun andOnlyAllMatchedReturnsTrue() {
        val matches = arrayOf(match(true), match(true))
        val specs = listOf(spec(), spec())
        assertTrue(evaluateWithOrGroups(matches, specs))
    }

    @Test
    fun andOnlyOneFailsReturnsFalse() {
        val matches = arrayOf(match(true), match(false))
        val specs = listOf(spec(), spec())
        assertFalse(evaluateWithOrGroups(matches, specs))
    }

    @Test
    fun orGroupMatchesWhenEitherIsTrue() {
        val matches = arrayOf(match(false), match(true))
        val specs = listOf(spec(orGroup = "wifi"), spec(orGroup = "wifi"))
        assertTrue(evaluateWithOrGroups(matches, specs))
    }

    @Test
    fun orGroupFailsWhenNoneMatch() {
        val matches = arrayOf(match(false), match(false))
        val specs = listOf(spec(orGroup = "wifi"), spec(orGroup = "wifi"))
        assertFalse(evaluateWithOrGroups(matches, specs))
    }

    @Test
    fun mixedAndOrGroupsRequireBoth() {
        val matches = arrayOf(match(true), match(false), match(true))
        val specs = listOf(spec(), spec(orGroup = "net"), spec(orGroup = "net"))
        assertTrue(evaluateWithOrGroups(matches, specs))
    }

    @Test
    fun mixedAndOrFailsWhenAndTermFails() {
        val matches = arrayOf(match(false), match(false), match(true))
        val specs = listOf(spec(), spec(orGroup = "net"), spec(orGroup = "net"))
        assertFalse(evaluateWithOrGroups(matches, specs))
    }

    @Test
    fun emptyContextMatchesReturnsFalse() {
        assertFalse(evaluateWithOrGroups(emptyArray(), emptyList()))
    }
}
