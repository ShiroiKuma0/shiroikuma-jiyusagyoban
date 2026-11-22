package com.opentasker.automation.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {
    @Test
    fun shakeThresholdAndDebounceAreFailClosed() {
        assertFalse(ShakeDetector.shouldPublishShake(2.49f, 0L, 2_000L))
        assertTrue(ShakeDetector.shouldPublishShake(2.5f, 0L, 2_000L))
        assertFalse(ShakeDetector.shouldPublishShake(3f, 1_500L, 2_000L))
    }

    /** Shakes a second apart on the sensor's clock both count, whatever the wall clock says. */
    @Test
    fun theDebounceRunsOnTheSensorEventsClock() {
        val debounce = ShakeDetector.Debounce()
        val second = 1_000_000_000L

        assertTrue(debounce.accept(3f, 10 * second))
        assertFalse(debounce.accept(3f, 10 * second + second / 2))
        assertTrue(debounce.accept(3f, 11 * second))
        assertFalse(debounce.accept(2f, 13 * second))
        assertTrue(debounce.accept(3f, 13 * second))
    }
}
