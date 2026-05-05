package com.opentasker.core.contexts

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SunEventCalculatorTest {
    @Test
    fun computesReasonableNewYorkSummerSolsticeSunTimes() {
        val date = LocalDate.of(2026, 6, 21)
        val zone = ZoneId.of("America/New_York")
        val sunrise = SunEventCalculator.eventMinuteOfDay(date, 40.7128, -74.0060, "sunrise", zone)!!
        val sunset = SunEventCalculator.eventMinuteOfDay(date, 40.7128, -74.0060, "sunset", zone)!!

        assertTrue("sunrise minute was $sunrise", sunrise in 310..340)
        assertTrue("sunset minute was $sunset", sunset in 1220..1250)
    }

    @Test
    fun returnsNullForUnsupportedSunEvent() {
        val result = SunEventCalculator.eventMinuteOfDay(
            date = LocalDate.of(2026, 6, 21),
            latitude = 40.7128,
            longitude = -74.0060,
            event = "noon",
            zone = ZoneId.of("America/New_York"),
        )

        assertTrue(result == null)
    }
}
