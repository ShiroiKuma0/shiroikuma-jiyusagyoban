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
}
