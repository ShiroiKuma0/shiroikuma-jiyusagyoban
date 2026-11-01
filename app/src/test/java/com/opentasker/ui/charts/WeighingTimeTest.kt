package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The weight dialog's twelve digit boxes, read back as the instant a weighing is saved at. */
class WeighingTimeTest {
    @Test
    fun twelveDigitsAreADateAndATime() {
        assertEquals(java.time.LocalDateTime.of(2026, 10, 30, 7, 0), weighingTimeOf("202610300700"))
    }

    @Test
    fun anImpossibleDateOrTimeIsRefused() {
        assertNull("month 13", weighingTimeOf("202613010700"))
        assertNull("30 February", weighingTimeOf("202602300700"))
        assertNull("25:00", weighingTimeOf("202610302500"))
        assertNull("07:60", weighingTimeOf("202610300760"))
        assertNull("too short", weighingTimeOf("2026103007"))
    }
}
