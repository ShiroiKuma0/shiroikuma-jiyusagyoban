package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
