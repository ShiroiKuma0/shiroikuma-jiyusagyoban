package com.opentasker.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLifecyclePolicyTest {
    @Test
    fun expiryAndConsumedLifetimeSuppressOnlyAfterTheirBoundary() {
        val expiring = Profile(
            id = 1,
            name = "Temporary",
            enterTaskId = 2,
            lifetime = ProfileLifetime.UNTIL_DATE,
            expiresAtMs = 1_000L,
        )

        assertNull(ProfileLifecyclePolicy.suppressionReason(expiring, 999L))
        assertTrue(ProfileLifecyclePolicy.isSuppressed(expiring, 1_000L))
        assertTrue(
            ProfileLifecyclePolicy.isSuppressed(
                expiring.copy(lifetime = ProfileLifetime.ONCE, lifetimeConsumed = true),
                0L,
            ),
        )
    }

    @Test
    fun onlyStrictlyHigherPrioritySuppressesAndDisabledProfilesAreIgnored() {
        val lowerId = Profile(id = 4, name = "Lower ID", enterTaskId = 1, priority = 5)
        val higherId = Profile(id = 9, name = "Higher ID", enterTaskId = 1, priority = 5)
        val highest = Profile(id = 20, name = "Highest", enterTaskId = 1, priority = 8)
        val disabled = Profile(id = 0, name = "Disabled", enabled = false, enterTaskId = 1, priority = 100)

        assertEquals(highest, ProfileLifecyclePolicy.suppressor(lowerId, listOf(higherId, disabled, highest, lowerId)))
        assertEquals(
            "Suppressed by higher-priority profile 'Highest'.",
            ProfileLifecyclePolicy.suppressionByPriority(lowerId, listOf(lowerId, highest)),
        )
        assertNull("a disabled profile never suppresses", ProfileLifecyclePolicy.suppressor(lowerId, listOf(lowerId, disabled)))
    }

    @Test
    fun equalPriorityProfilesRunConcurrently() {
        // Default-priority profiles are independent. Arbitrating equal priorities by profile ID
        // made a single long-lived matched profile suppress every higher-ID profile all day.
        val home = Profile(id = 2, name = "At home", enterTaskId = 1)
        val notify = Profile(id = 5, name = "Notify me", enterTaskId = 1)
        val matched = listOf(home, notify)

        assertNull(ProfileLifecyclePolicy.suppressor(home, matched))
        assertNull(ProfileLifecyclePolicy.suppressor(notify, matched))
        assertNull(ProfileLifecyclePolicy.suppressionByPriority(notify, matched))
    }

    @Test
    fun aProfileNeverSuppressesItself() {
        val onlyOne = Profile(id = 7, name = "Solo", enterTaskId = 1, priority = 3)
        val stale = onlyOne.copy(priority = 9)

        assertNull(ProfileLifecyclePolicy.suppressor(onlyOne, listOf(onlyOne)))
        assertNull("a stale copy of the same profile must not outrank it", ProfileLifecyclePolicy.suppressor(onlyOne, listOf(stale)))
    }

    @Test
    fun normalizeClampsEditorValuesAndDropsRuntimeStateFromOtherLifetimes() {
        val normalized = ProfileLifecyclePolicy.normalize(
            Profile(
                id = 3,
                name = "Profile",
                enterTaskId = 1,
                priority = 999,
                gracePeriodSec = -4,
                lifetime = ProfileLifetime.NEVER,
                expiresAtMs = 7_000L,
                lifetimeConsumed = true,
            ),
        )

        assertEquals(ProfileLifecyclePolicy.MAX_PRIORITY, normalized.priority)
        assertEquals(0, normalized.gracePeriodSec)
        assertNull(normalized.expiresAtMs)
        assertFalse(normalized.lifetimeConsumed)
    }
}
