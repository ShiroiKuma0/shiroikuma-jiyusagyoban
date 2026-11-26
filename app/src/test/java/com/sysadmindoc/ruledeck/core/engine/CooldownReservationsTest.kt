package com.sysadmindoc.ruledeck.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CooldownReservationsTest {

    private class FakeClock(var millis: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = millis
    }

    @Test
    fun aRetriggerInsideTheWindowIsRejectedWithTheTimeLeft() {
        val clock = FakeClock()
        val cooldowns = CooldownReservations(now = clock)

        assertTrue(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)

        clock.millis += 20_000
        val rejected = cooldowns.reserve(profileId = 1, cooldownSec = 60)
        assertFalse(rejected.accepted)
        assertEquals(40_000L, rejected.remainingMs)
    }

    @Test
    fun theWindowReopensOnceItExpires() {
        val clock = FakeClock()
        val cooldowns = CooldownReservations(now = clock)
        assertTrue(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)

        clock.millis += 60_000
        assertTrue(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)
    }

    @Test
    fun aProfileWithoutACooldownIsNeverBlockedAndArmsNothing() {
        val cooldowns = CooldownReservations(now = FakeClock())
        repeat(5) { assertTrue(cooldowns.reserve(profileId = 1, cooldownSec = 0).accepted) }
        assertTrue(cooldowns.snapshot().isEmpty())
    }

    @Test
    fun cooldownsAreScopedPerProfile() {
        val cooldowns = CooldownReservations(now = FakeClock())
        assertTrue(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)
        assertTrue(cooldowns.reserve(profileId = 2, cooldownSec = 60).accepted)
        assertFalse(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)
    }

    @Test
    fun theArmedDeadlineIsPersistedSoItSurvivesAProcessRestart() {
        val clock = FakeClock()
        val persisted = mutableMapOf<Long, Long>()
        val cooldowns = CooldownReservations(now = clock, persist = { id, deadline -> persisted[id] = deadline })
        cooldowns.reserve(profileId = 7, cooldownSec = 30)
        assertEquals(mapOf(7L to clock.millis + 30_000), persisted)

        val afterRestart = CooldownReservations(now = clock)
        afterRestart.seed(persisted)
        assertFalse(afterRestart.reserve(profileId = 7, cooldownSec = 30).accepted)
    }

    @Test
    fun onlyOneOfManySimultaneousTriggersWinsTheWindow() {
        // Regression lock for the check-then-write race: two contexts matching the same profile in
        // the same instant both read an expired deadline and both start a run.
        val threads = 32
        val cooldowns = CooldownReservations(now = FakeClock())
        val accepted = AtomicInteger()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    if (cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted) accepted.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("workers did not finish", done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, accepted.get())
    }

    @Test
    fun aReleasedReservationPutsTheEarlierDeadlineBack() {
        val clock = FakeClock()
        val persisted = mutableMapOf<Long, Long>()
        val cooldowns = CooldownReservations(now = clock, persist = { id, deadline -> persisted[id] = deadline })
        assertTrue(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)
        clock.millis += 60_000
        val second = cooldowns.reserve(profileId = 1, cooldownSec = 60)
        assertTrue(second.accepted)

        cooldowns.release(profileId = 1, reservation = second)

        assertEquals(1_060_000L, persisted[1L])
        assertEquals(mapOf(1L to 1_060_000L), cooldowns.snapshot())
        assertTrue(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)
    }

    /**
     * A deadline saved before the clock went back a day, or under a longer cooldown, held the
     * profile for the old span. It counts for at most one current window.
     */
    @Test
    fun aDeadlineCountsForAtMostOneCurrentWindow() {
        val clock = FakeClock()
        val persisted = mutableMapOf<Long, Long>()
        val cooldowns = CooldownReservations(now = clock, persist = { id, deadline -> persisted[id] = deadline })
        cooldowns.seed(mapOf(1L to clock.millis + 86_400_000L, 2L to clock.millis + 3_600_000L))

        val clockSetBack = cooldowns.reserve(profileId = 1, cooldownSec = 60)
        assertFalse(clockSetBack.accepted)
        assertEquals(60_000L, clockSetBack.remainingMs)
        assertEquals("brought in once and saved, so the next start doesn't load the day", clock.millis + 60_000L, persisted[1L])
        assertTrue("a cooldown since removed holds nothing", cooldowns.reserve(profileId = 2, cooldownSec = 0).accepted)

        clock.millis += 60_000
        assertTrue(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)
        assertEquals(clock.millis + 60_000L, cooldowns.snapshot()[1L])
    }

    /**
     * A queued run's ten-minute window, brought in to one minute by a trigger after the cooldown
     * was shortened, is still that run's to give back when admission then refuses it.
     */
    @Test
    fun aRunRefusedAfterItsCooldownWasShortenedStillGivesItBack() {
        val clock = FakeClock()
        val restored = mutableListOf<Triple<Long, Long, Long>>()
        val cooldowns = CooldownReservations(
            now = clock,
            restore = { id, reserved, previous -> restored += Triple(id, reserved, previous) },
        )
        val queued = cooldowns.reserve(profileId = 1, cooldownSec = 600)
        clock.millis += 1_000
        assertFalse(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)

        cooldowns.release(profileId = 1, reservation = queued)

        assertEquals(listOf(Triple(1L, clock.millis + 60_000L, 0L)), restored)
        assertTrue(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)
    }

    /** The store puts the old deadline back only over the one this release gives back. */
    @Test
    fun aReleaseSavesThroughRestoreWithTheDeadlineItGivesBack() {
        val clock = FakeClock()
        val restored = mutableListOf<Triple<Long, Long, Long>>()
        val cooldowns = CooldownReservations(
            now = clock,
            restore = { id, reserved, previous -> restored += Triple(id, reserved, previous) },
        )
        val reservation = cooldowns.reserve(profileId = 1, cooldownSec = 60)

        cooldowns.release(profileId = 1, reservation = reservation)

        assertEquals(listOf(Triple(1L, 1_060_000L, 0L)), restored)
    }

    @Test
    fun aReleaseAfterALaterReservationLeavesTheLaterWindow() {
        val clock = FakeClock()
        val cooldowns = CooldownReservations(now = clock)
        val first = cooldowns.reserve(profileId = 1, cooldownSec = 60)
        clock.millis += 60_000
        assertTrue(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)

        cooldowns.release(profileId = 1, reservation = first)

        clock.millis += 1_000
        assertFalse(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)
    }

    @Test
    fun aClaimGivesTheCooldownBackOnlyWhenTheRunWasNotAdmitted() {
        val cooldowns = CooldownReservations(now = FakeClock())
        val refused = CooldownClaim(cooldowns, profileId = 1, reservation = cooldowns.reserve(profileId = 1, cooldownSec = 60))
        val admitted = CooldownClaim(cooldowns, profileId = 2, reservation = cooldowns.reserve(profileId = 2, cooldownSec = 60))

        refused.releaseUnlessAdmitted()
        admitted.admitted()
        admitted.releaseUnlessAdmitted()

        assertTrue(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)
        assertFalse(cooldowns.reserve(profileId = 2, cooldownSec = 60).accepted)
    }

    /** A job canceled before it starts never runs its body, where admission would have happened. */
    @Test
    fun aJobCanceledBeforeItStartsGivesTheCooldownBack() = runBlocking {
        val cooldowns = CooldownReservations(now = FakeClock())
        val canceled = CooldownClaim(cooldowns, profileId = 1, reservation = cooldowns.reserve(profileId = 1, cooldownSec = 60))
        val finished = CooldownClaim(cooldowns, profileId = 2, reservation = cooldowns.reserve(profileId = 2, cooldownSec = 60))

        val neverStarted = launch { canceled.admitted() }.givingBack(canceled)
        neverStarted.cancel()
        neverStarted.join()
        launch { finished.admitted() }.givingBack(finished).join()

        assertTrue(cooldowns.reserve(profileId = 1, cooldownSec = 60).accepted)
        assertFalse(cooldowns.reserve(profileId = 2, cooldownSec = 60).accepted)
    }
}
