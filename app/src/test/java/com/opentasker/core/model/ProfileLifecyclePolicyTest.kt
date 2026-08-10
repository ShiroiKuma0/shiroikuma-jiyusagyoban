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
    fun winnerUsesPriorityThenStableIdAndIgnoresDisabledProfiles() {
        val lowerId = Profile(id = 4, name = "Lower ID", enterTaskId = 1, priority = 5)
        val higherId = Profile(id = 9, name = "Higher ID", enterTaskId = 1, priority = 5)
        val highest = Profile(id = 20, name = "Highest", enterTaskId = 1, priority = 8)
        val disabled = Profile(id = 0, name = "Disabled", enabled = false, enterTaskId = 1, priority = 100)

        assertEquals(highest, ProfileLifecyclePolicy.winner(listOf(higherId, disabled, highest, lowerId)))
        assertEquals(lowerId.name, ProfileLifecyclePolicy.winner(listOf(higherId, lowerId))?.name)
        assertEquals(
            "Suppressed by higher-priority profile 'Highest'.",
            ProfileLifecyclePolicy.suppressionByPriority(lowerId, listOf(lowerId, highest)),
        )
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
