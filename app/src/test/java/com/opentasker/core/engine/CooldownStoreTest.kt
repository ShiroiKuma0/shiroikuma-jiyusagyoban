package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted half of cooldown. `CooldownReservationsTest` covers the in-memory half; these
 * cover what survives process death, which is where a wrong answer is invisible until a profile
 * either re-fires during its cooldown or stays silent past it.
 */
class CooldownStoreTest {
    private val now = 1_700_000_000_000L

    @Test
    fun onlyDeadlinesStillInTheFutureAreLoaded() {
        val stored = mapOf(
            "cd_1" to now + 60_000L,
            "cd_2" to now - 1L,
            "cd_3" to now,
        )

        assertEquals(mapOf(1L to now + 60_000L), liveCooldowns(stored, now))
        assertEquals(listOf("cd_2", "cd_3").sorted(), expiredCooldownKeys(stored, now).sorted())
    }

    @Test
    fun unrelatedPreferenceKeysAndNonLongValuesAreIgnored() {
        val stored = mapOf<String, Any?>(
            "cd_1" to now + 5_000L,
            "some_other_setting" to now + 5_000L,
            "cd_notanumber" to now + 5_000L,
            "cd_2" to "not a deadline",
            "cd_3" to null,
        )

        assertEquals(mapOf(1L to now + 5_000L), liveCooldowns(stored, now))
        assertTrue(
            "keys outside the cooldown namespace must never be removed",
            expiredCooldownKeys(stored, now).all { it.startsWith("cd_") },
        )
        assertTrue("a non-numeric suffix is not a profile id", "cd_notanumber" !in expiredCooldownKeys(stored, now))
    }

    @Test
    fun pruningKeepsDeadlinesForProfilesThatStillExist() {
        // The regression this guards: pruning against *enabled* profiles deleted the deadline of a
        // profile the user had merely switched off, so whether the cooldown still applied after
        // re-enabling depended on whether the service happened to restart in between.
        val keys = setOf("cd_1", "cd_2", "cd_3", "unrelated")
        val existingProfiles = setOf(1L, 3L)

        assertEquals(listOf("cd_2"), staleCooldownKeys(keys, existingProfiles))
    }

    @Test
    fun pruningRemovesEverythingWhenNoProfilesRemain() {
        assertEquals(
            listOf("cd_1", "cd_2").sorted(),
            staleCooldownKeys(setOf("cd_1", "cd_2", "unrelated"), emptySet()).sorted(),
        )
    }

    @Test
    fun keysAreParsedOnlyForTheCooldownNamespace() {
        assertEquals(7L, profileIdForCooldownKey("cd_7"))
        assertEquals(null, profileIdForCooldownKey("cooldown_7"))
        assertEquals(null, profileIdForCooldownKey("cd_"))
        assertEquals(null, profileIdForCooldownKey("cd_seven"))
    }
}
