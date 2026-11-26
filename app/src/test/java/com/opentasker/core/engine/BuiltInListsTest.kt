package com.opentasker.core.engine

import com.opentasker.core.engine.variables.BuiltInVariables
import com.opentasker.core.platform.VOLUME_BUILT_IN_STREAMS
import com.opentasker.core.platform.latLonText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The list built-ins: `%PACTIVE`, `%PENABLED` and `%TRUN` list profile and task names between
 * commas, the `%VOL` ones each read one audio stream, and `%LOC`/`%LOCN` show a fix as
 * latitude,longitude.
 */
class BuiltInListsTest {
    @Before
    fun setUp() {
        BuiltInVariables.resetForTests()
        AutomationLiveConditionState.clear()
        ActiveExecutionRegistry.reset()
    }

    @After
    fun tearDown() {
        BuiltInVariables.resetForTests()
        AutomationLiveConditionState.clear()
        ActiveExecutionRegistry.reset()
    }

    @Test
    fun enabledProfilesAreListedOnceEachInTheOrderTheyWereMade() {
        BuiltInVariables.watchProfiles(mapOf(7L to "Night", 2L to "Home", 5L to "Car", 9L to "Home", 11L to " "))

        assertEquals(",Home,Car,Night,", BuiltInVariables.value("PENABLED"))
    }

    @Test
    fun activeProfilesAreListedInTheOrderTheyWentOn() {
        BuiltInVariables.watchProfiles(mapOf(1L to "Home", 2L to "Car", 3L to "Night"))
        AutomationLiveConditionState.updateProfile(3, true)
        AutomationLiveConditionState.updateProfile(1, true)
        AutomationLiveConditionState.updateProfile(2, true)
        AutomationLiveConditionState.updateProfile(2, false)
        // On, but not one the engine watches any more.
        AutomationLiveConditionState.updateProfile(40, true)

        assertEquals(",Night,Home,", BuiltInVariables.value("PACTIVE"))
        assertTrue(VariableStore().evaluateCondition("%PACTIVE ~ *Home*"))
        assertFalse(VariableStore().evaluateCondition("%PACTIVE ~ *Car*"))
    }

    @Test
    fun runningTasksAreListedOnceEach() {
        ActiveExecutionRegistry.register(1, "Morning", "Manual", null, 0L)
        ActiveExecutionRegistry.register(2, "Backup", "Profile: Home", null, 0L)
        ActiveExecutionRegistry.register(1, "Morning", "Manual", null, 0L)

        assertEquals(",Morning,Backup,", BuiltInVariables.value("TRUN"))
    }

    @Test
    fun anEmptyListLeavesTheVariableUnset() {
        listOf("PACTIVE", "PENABLED", "TRUN").forEach { name -> assertNull(name, BuiltInVariables.value(name)) }
        assertTrue(VariableStore().evaluateCondition("%TRUN not_set"))
    }

    @Test
    fun eachVolumeReadsItsOwnStream() {
        // AudioManager stream numbers: alarm 4, call 0, DTMF 8, music 3, notification 5, ring 2, system 1.
        assertEquals(
            mapOf("VOLA" to 4, "VOLC" to 0, "VOLD" to 8, "VOLM" to 3, "VOLN" to 5, "VOLR" to 2, "VOLS" to 1),
            VOLUME_BUILT_IN_STREAMS,
        )
        assertTrue(VOLUME_BUILT_IN_STREAMS.keys.all { it in BuiltInVariables.SUPPORTED })
    }

    @Test
    fun aFixReadsAsLatitudeCommaLongitude() {
        assertEquals("37.4219983,-122.084", latLonText(37.4219983, -122.084))
        assertEquals("40.0,-74.0", latLonText(40.0, -74.0))
    }
}
