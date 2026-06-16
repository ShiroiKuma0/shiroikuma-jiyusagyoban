package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DayScheduleTest {
    @Test
    fun aliasesCanonicalizeToStableDayLists() {
        assertEquals("MON,TUE,WED,THU,FRI", DaySchedule.canonicalize("weekdays"))
        assertEquals("SAT,SUN", DaySchedule.canonicalize("weekend"))
        assertEquals("MON,TUE,WED,THU,FRI,SAT,SUN", DaySchedule.canonicalize("every day"))
    }

    @Test
    fun rangesExpandInCanonicalOrder() {
        assertEquals("MON,TUE,WED,THU,FRI", DaySchedule.canonicalize("MON-FRI"))
        assertEquals("MON,FRI,SAT,SUN", DaySchedule.canonicalize("FRI-MON"))
    }

    @Test
    fun matchingAcceptsAliasesRangesAndNumericTokens() {
        assertTrue(DaySchedule.matches("weekdays", "Wednesday"))
        assertTrue(DaySchedule.matches("FRI-MON", "0"))
        assertFalse(DaySchedule.matches("weekend", "TUE"))
        assertFalse(DaySchedule.matches("not-a-day", "MON"))
    }

    @Test
    fun calendarTokensUseStableUppercaseScheduleTokens() {
        val calendar = Calendar.getInstance()

        val expected = mapOf(
            Calendar.MONDAY to "MON",
            Calendar.TUESDAY to "TUE",
            Calendar.WEDNESDAY to "WED",
            Calendar.THURSDAY to "THU",
            Calendar.FRIDAY to "FRI",
            Calendar.SATURDAY to "SAT",
            Calendar.SUNDAY to "SUN",
        )

        expected.forEach { (dayOfWeek, token) ->
            calendar.set(Calendar.DAY_OF_WEEK, dayOfWeek)
            assertEquals(token, DaySchedule.tokenFor(calendar))
        }
    }
}
