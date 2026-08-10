package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateSensorEventsTest {
    /**
     * An accelerometer at rest reads +g along whichever device axis points upward, so face-up is
     * z = +9.8 and a device held normally - top edge up - is y = +9.8. The classifier previously
     * reported a normally-held phone as `portrait_upside_down`.
     */
    @Test
    fun orientationClassifierFollowsTheAndroidSensorConvention() {
        assertEquals("face_up", DeviceOrientationClassifier.classify(0f, 0f, 9.8f))
        assertEquals("face_down", DeviceOrientationClassifier.classify(0f, 0f, -9.8f))
        assertEquals("portrait", DeviceOrientationClassifier.classify(0f, 9.8f, 0f))
        assertEquals("portrait_upside_down", DeviceOrientationClassifier.classify(0f, -9.8f, 0f))
        assertEquals("landscape_left", DeviceOrientationClassifier.classify(9.8f, 0f, 0f))
        assertEquals("landscape_right", DeviceOrientationClassifier.classify(-9.8f, 0f, 0f))
    }

    @Test
    fun activityClassifierMapsStepCadenceAndMotion() {
        assertEquals("stationary", MotionActivityClassifier.fromStepRate(0))
        assertEquals("walking", MotionActivityClassifier.fromStepRate(60))
        assertEquals("running", MotionActivityClassifier.fromStepRate(120))
        assertEquals("stationary", MotionActivityClassifier.fromAcceleration(0f, 0f, 9.80665f))
        assertEquals("walking", MotionActivityClassifier.fromAcceleration(5f, 0f, 9.80665f))
        assertEquals("running", MotionActivityClassifier.fromAcceleration(12f, 0f, 9.80665f))
    }

    @Test
    fun speedCalculatorFallsBackToDistanceOverElapsedTime() {
        val previous = SpeedSample(0.0, 0.0, 0L)
        val current = SpeedSample(0.0, 0.0008983, 10_000L)

        val speed = SpeedCalculator.between(previous, current)

        assertTrue(speed != null)
        assertTrue(speed!! in 9.0..11.5)
    }
}
