package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class MaskPhoneNumberTest {
    @Test
    fun keepsOnlyLastFourDigits() {
        assertEquals("***6789", maskPhoneNumber("15551236789"))
        assertEquals("***3456", maskPhoneNumber("123456"))
    }

    @Test
    fun fullyMasksShortNumbers() {
        // A number of 4 or fewer chars would otherwise be fully revealed, so mask it entirely.
        assertEquals("***", maskPhoneNumber("5555"))
        assertEquals("***", maskPhoneNumber("123"))
        assertEquals("***", maskPhoneNumber(""))
    }
}
