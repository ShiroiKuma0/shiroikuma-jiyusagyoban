package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryPercentTest {
    @Test
    fun normalizesAgainstScale() {
        assertEquals(80, batteryPercent(level = 80, scale = 100))
        // Devices that report scale = 255 must still yield a real percentage.
        assertEquals(50, batteryPercent(level = 128, scale = 255))
        assertEquals(100, batteryPercent(level = 255, scale = 255))
        assertEquals(0, batteryPercent(level = 0, scale = 255))
    }

    @Test
    fun rejectsUnknownLevelOrScale() {
        assertNull(batteryPercent(level = -1, scale = 100))
        assertNull(batteryPercent(level = 50, scale = 0))
        assertNull(batteryPercent(level = 50, scale = -1))
    }
}
