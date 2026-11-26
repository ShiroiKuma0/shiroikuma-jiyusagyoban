package com.sysadmindoc.ruledeck.core.contexts

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun closeWaitsForARegistrationUnderWayThenRemovesIt() {
        val registered = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ensures = AtomicInteger()
        val registration = RecheckingRegistration(
            ensure = {
                ensures.incrementAndGet()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                registered.set(true)
            },
            stop = { registered.set(false) },
            intervalMs = 10,
        )
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            registration.start(scope)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // The profile closes while the sensor is still being registered.
            val closer = thread { registration.close() }
            Thread.sleep(100)
            release.countDown()
            closer.join(5_000)
            assertFalse("a sensor registered as the source closed was left on", registered.get())
            val count = ensures.get()
            Thread.sleep(100)
            assertEquals("no recheck runs after close", count, ensures.get())
        } finally {
            scope.cancel()
        }
    }
}
