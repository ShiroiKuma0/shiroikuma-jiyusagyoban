package com.opentasker.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLifecyclePolicyTest {
    @Test
    fun lifecycleCopyComesFromTheCallerSuppliedStringProvider() {
        val profile = Profile(
            id = 1,
            name = "Temporary",
            enterTaskId = 2,
            lifetime = ProfileLifetime.UNTIL_DATE,
            expiresAtMs = 1_000L,
        )
        val localized = object : ProfileLifecycleStrings {
            override fun oneShotConsumed() = "localized-consumed"
            override fun missingExpiry() = "localized-missing"
            override fun expired(date: String) = "localized-expired"
            override fun suppressedByPriority(profileName: String) = "localized-priority-$profileName"
        }

        assertEquals("localized-expired", ProfileLifecyclePolicy.suppressionReason(profile, 1_000L, localized))
        assertEquals(
            "localized-priority-Highest",
            ProfileLifecyclePolicy.suppressionByPriority(
                profile.copy(priority = 1),
                listOf(profile.copy(id = 2, name = "Highest", priority = 2)),
                localized,
            ),
        )
    }

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

    private fun profile(id: Long, name: String, priority: Int = 0, enabled: Boolean = true) =
        Profile(id = id, name = name, enterTaskId = id * 10, priority = priority, enabled = enabled)

    @Test
    fun releasingTheOutrankingProfileFreesOnlyWhatNothingElseStillOutranks() {
        val high = profile(1, "High", priority = 10)
        val middle = profile(2, "Middle", priority = 5)
        val low = profile(3, "Low")

        // While all three match, High outranks both others.
        val all = listOf(high, middle, low)
        assertEquals(high, ProfileLifecyclePolicy.suppressor(middle, all))
        assertEquals(high, ProfileLifecyclePolicy.suppressor(low, all))

        // High deactivates: Middle is free, but Low is now outranked by Middle instead.
        val afterHigh = listOf(middle, low)
        assertEquals(listOf(middle), ProfileLifecyclePolicy.released(all, afterHigh))

        // Middle deactivates too: only now is Low released.
        assertEquals(listOf(low), ProfileLifecyclePolicy.released(afterHigh, listOf(low)))
    }

    @Test
    fun equalPrioritiesNeverSuppressEachOtherSoNothingIsEverReleased() {
        // The default priority is the common case: every profile left at 0 must run concurrently.
        // Arbitrating equal priorities by id would make each one mutually exclusive with the rest.
        val first = profile(1, "First")
        val second = profile(2, "Second")
        val third = profile(3, "Third")
        val all = listOf(first, second, third)

        assertNull(ProfileLifecyclePolicy.suppressor(second, all))
        assertNull(ProfileLifecyclePolicy.suppressor(third, all))
        assertEquals(emptyList<Profile>(), ProfileLifecyclePolicy.released(all, listOf(second, third)))
    }

    @Test
    fun aProfileThatStoppedMatchingIsNotReleased() {
        val high = profile(1, "High", priority = 10)
        val low = profile(2, "Low")

        // Both leave the matched set at once: Low is not "freed", it simply no longer matches.
        assertEquals(emptyList<Profile>(), ProfileLifecyclePolicy.released(listOf(high, low), emptyList()))
    }

    @Test
    fun aDisabledProfileOutranksNothing() {
        val disabledHigh = profile(1, "High", priority = 10, enabled = false)
        val low = profile(2, "Low")

        assertNull(ProfileLifecyclePolicy.suppressor(low, listOf(disabledHigh, low)))
    }
}
