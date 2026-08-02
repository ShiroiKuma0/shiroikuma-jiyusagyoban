package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressNotificationActionTest {
    @Test
    fun progressIsBounded() {
        assertEquals(50, parseProgress("50"))
        assertNull(parseProgress("101"))
        assertNull(parseProgress("not-a-number"))
    }

    @Test
    fun segmentLengthsIgnoreInvalidValues() {
        assertEquals(listOf(2, 3, 5), parseSegmentLengths("2,invalid;3,0,5"))
    }
}
