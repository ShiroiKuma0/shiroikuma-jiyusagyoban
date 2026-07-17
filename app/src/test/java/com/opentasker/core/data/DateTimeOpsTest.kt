package com.opentasker.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateTimeOpsTest {
    @Test
    fun formatsEpochInUtc() {
        assertEquals("1970-01-01 00:00:00", DateTimeOps.format(0L, "yyyy-MM-dd HH:mm:ss", "UTC"))
    }

    @Test
    fun formatRejectsInvalidPattern() {
        assertNull(DateTimeOps.format(0L, "yyyy-MM-dd 'unterminated", "UTC"))
    }

    @Test
    fun parseRoundTripsWithFormat() {
        val epoch = DateTimeOps.parse("2026-07-14 09:30", "yyyy-MM-dd HH:mm", "UTC")
        assertEquals("2026-07-14 09:30", epoch?.let { DateTimeOps.format(it, "yyyy-MM-dd HH:mm", "UTC") })
    }

    @Test
    fun parseAcceptsDateOnlyPattern() {
        val epoch = DateTimeOps.parse("2026-07-14", "yyyy-MM-dd", "UTC")
        assertEquals("2026-07-14 00:00:00", epoch?.let { DateTimeOps.format(it, "yyyy-MM-dd HH:mm:ss", "UTC") })
    }

    @Test
    fun parseFailsClosedOnMismatch() {
        assertNull(DateTimeOps.parse("not a date", "yyyy-MM-dd", "UTC"))
    }

    @Test
    fun addFixedUnitsAreExactAndZoneIndependent() {
        assertEquals(86_400_000L, DateTimeOps.add(0L, 1, "day"))
        assertEquals(7 * 86_400_000L, DateTimeOps.add(0L, 1, "week"))
        assertEquals(3_600_000L, DateTimeOps.add(0L, 1, "hour"))
        assertEquals(-60_000L, DateTimeOps.add(0L, -1, "minute"))
    }

    @Test
    fun addRejectsUnknownUnit() {
        assertNull(DateTimeOps.add(0L, 1, "fortnight"))
    }

    @Test
    fun invalidZoneFailsClosedInsteadOfFallingBackToSystemZone() {
        assertNull(DateTimeOps.format(0L, "yyyy-MM-dd", "Amercia/New_York"))
        assertNull(DateTimeOps.parse("2026-07-14", "yyyy-MM-dd", "Not/A_Zone"))
    }

    @Test
    fun addMonthsAdvancesCalendar() {
        // One month from a fixed instant must move strictly forward (calendar-based).
        val base = 1_600_000_000_000L
        val plusMonth = DateTimeOps.add(base, 1, "month")!!
        assert(plusMonth > base)
    }
}
