package com.opentasker.core.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AutomationLiveConditionStateTest {
    @Before
    fun setUp() {
        AutomationLiveConditionState.clear()
    }

    @After
    fun tearDown() {
        AutomationLiveConditionState.clear()
    }

    @Test
    fun missingStateIsUnknownUntilMatcherPublishesIt() {
        assertNull(AutomationLiveConditionState.profileState(7))
        assertNull(AutomationLiveConditionState.contextState(7, 0))

        AutomationLiveConditionState.updateProfile(7, true)
        AutomationLiveConditionState.updateContext(7, 0, false)

        assertEquals(true, AutomationLiveConditionState.profileState(7))
        assertEquals(false, AutomationLiveConditionState.contextState(7, 0))
    }

    @Test
    fun retainingProfilesDropsStaleContextState() {
        AutomationLiveConditionState.updateProfile(7, true)
        AutomationLiveConditionState.updateContext(7, 0, true)
        AutomationLiveConditionState.updateProfile(8, false)
        AutomationLiveConditionState.retainProfiles(setOf(8))

        assertNull(AutomationLiveConditionState.profileState(7))
        assertNull(AutomationLiveConditionState.contextState(7, 0))
        assertEquals(false, AutomationLiveConditionState.profileState(8))
    }
}
