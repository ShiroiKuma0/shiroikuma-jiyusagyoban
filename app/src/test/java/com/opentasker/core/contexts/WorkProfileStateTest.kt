package com.opentasker.core.contexts

import com.opentasker.core.contexts.WorkProfileState.OtherProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A-280: `work_profile` says available, paused or none from what Android reports. */
class WorkProfileStateTest {
    @Test
    fun noOtherProfileReadsNone() {
        assertEquals("none", WorkProfileState.stateOf(emptyList()))
    }

    @Test
    fun aWorkProfileReadsAvailableUntilItIsPaused() {
        assertEquals("available", WorkProfileState.stateOf(listOf(OtherProfile(managed = true, quietMode = false))))
        assertEquals("paused", WorkProfileState.stateOf(listOf(OtherProfile(managed = true, quietMode = true))))
    }

    @Test
    fun aProfileKnownNotToBeWorkDoesNotCount() {
        // Android 15 names a clone profile's type, and a clone has no quiet mode to pause.
        assertEquals("none", WorkProfileState.stateOf(listOf(OtherProfile(managed = false, quietMode = false))))
        assertEquals(
            "paused",
            WorkProfileState.stateOf(
                listOf(OtherProfile(managed = false, quietMode = false), OtherProfile(managed = true, quietMode = true)),
            ),
        )
    }

    @Test
    fun beforeAndroid15AnyOtherProfileCountsSinceItsKindIsUnknown() {
        assertEquals("available", WorkProfileState.stateOf(listOf(OtherProfile(managed = null, quietMode = false))))
        assertEquals("paused", WorkProfileState.stateOf(listOf(OtherProfile(managed = null, quietMode = true))))
    }

    @Test
    fun aStateContextMatchesTheThreeValuesInAnyCase() {
        val paused = mapOf("work_profile" to "paused")

        assertTrue(stateMatches("work_profile=paused", paused))
        assertTrue(stateMatches("work_profile=Paused", paused))
        assertFalse(stateMatches("work_profile=available", paused))
        assertTrue(stateMatches("work_profile=none", mapOf("work_profile" to "none")))
        assertFalse("an unknown value never matches", stateMatches("work_profile=off", mapOf("work_profile" to "off")))
        assertFalse("no reading yet never matches", stateMatches("work_profile=none", emptyMap()))
    }

    @Test
    fun theEditorPresetAsksForAPausedWorkProfile() {
        val preset = com.opentasker.ui.screens.StateContextPresets.all.single { it.id == "work_profile" }

        assertEquals(mapOf("key" to "work_profile", "operator" to "=", "value" to "paused"), preset.config)
    }
}
