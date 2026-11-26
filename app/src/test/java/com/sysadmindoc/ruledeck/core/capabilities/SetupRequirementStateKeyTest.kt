package com.sysadmindoc.ruledeck.core.capabilities

import com.sysadmindoc.ruledeck.core.model.ContextSpec
import com.sysadmindoc.ruledeck.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A state context asks Setup for what its key reads, never for a word that happens to sit inside
 * the key or the value it compares against.
 */
class SetupRequirementStateKeyTest {
    private fun required(vararg config: Pair<String, String>): Set<SetupRequirement> =
        SetupRequirementResolver.resolveForTemplate(
            actionTypes = emptyList(),
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf(*config))),
        )

    @Test
    fun headphonesAreNotAPhoneCall() {
        assertEquals(emptySet<SetupRequirement>(), required("key" to "headphones", "value" to "connected"))
        assertEquals(emptySet<SetupRequirement>(), required("key" to "headset", "value" to "true"))
    }

    @Test
    fun aWifiNameIsReadAsANameAndNotAsWhatItSays() {
        assertEquals(
            setOf(SetupRequirement.NEARBY_WIFI, SetupRequirement.FOREGROUND_LOCATION, SetupRequirement.BACKGROUND_LOCATION),
            required("key" to "wifi", "value" to "Phone Hotspot Speed Activity"),
        )
        assertEquals(setOf(SetupRequirement.NEARBY_WIFI), required("predicate" to "ssid=connected"))
    }

    @Test
    fun aStateValueCannotPullInAnotherFamilysGrant() {
        assertEquals(emptySet<SetupRequirement>(), required("key" to "screen", "value" to "on", "note" to "call me"))
        assertEquals(emptySet<SetupRequirement>(), required("key" to "media_package", "value" to "com.example.bt.callrecorder"))
        assertEquals(emptySet<SetupRequirement>(), required("key" to "network_type", "value" to "wifi"))
        assertEquals(emptySet<SetupRequirement>(), required("predicate" to "battery_level<20"))
    }

    @Test
    fun eachSensorKeyAsksForItsOwnGrant() {
        assertEquals(setOf(SetupRequirement.PHYSICAL_ACTIVITY), required("key" to "motion", "value" to "walking"))
        assertEquals(setOf(SetupRequirement.FOREGROUND_LOCATION), required("predicate" to "velocity>=3"))
        assertEquals(setOf(SetupRequirement.PHONE_STATE), required("key" to "roaming_state", "value" to "true"))
        assertEquals(setOf(SetupRequirement.PHONE_STATE), required("key" to "call", "value" to "ringing"))
    }

    @Test
    fun aContextWithNoKeyStillAsksByWholeWord() {
        assertEquals(setOf(SetupRequirement.PHONE_STATE), required("state" to "phone_call ringing"))
        assertEquals(emptySet<SetupRequirement>(), required("state" to "headphones connected"))
    }
}
