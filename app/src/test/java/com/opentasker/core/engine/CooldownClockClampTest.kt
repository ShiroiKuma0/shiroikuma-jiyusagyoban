package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** The fork's port of upstream's clock clamp: a cooldown never holds a profile longer than itself. */
class CooldownClockClampTest {
    private val now = 1_000_000_000L

    @Test
    fun aDeadlineInsideTheWindowStands() {
        assertEquals(now + 20_000, clampedCooldownDeadline(now + 20_000, cooldownSec = 60, nowMs = now))
    }

    @Test
    fun aPassedDeadlineStands() {
        assertEquals(now - 5_000, clampedCooldownDeadline(now - 5_000, cooldownSec = 60, nowMs = now))
    }

    @Test
    fun aClockSetBackADayLeavesOneWindowNotADay() {
        val armedBeforeTheClockMoved = now + 86_400_000L + 60_000
        assertEquals(now + 60_000, clampedCooldownDeadline(armedBeforeTheClockMoved, cooldownSec = 60, nowMs = now))
    }

    @Test
    fun aShortenedCooldownStopsTheOldLongerOne() {
        assertEquals(now + 10_000, clampedCooldownDeadline(now + 600_000, cooldownSec = 10, nowMs = now))
    }

    @Test
    fun aCooldownSwitchedOffHoldsNothing() {
        assertEquals(now, clampedCooldownDeadline(now + 600_000, cooldownSec = 0, nowMs = now))
        assertEquals(now, clampedCooldownDeadline(now + 600_000, cooldownSec = -3, nowMs = now))
    }
}
