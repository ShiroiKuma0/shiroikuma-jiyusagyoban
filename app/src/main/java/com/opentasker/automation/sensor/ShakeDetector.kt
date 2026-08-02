package com.opentasker.automation.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.opentasker.automation.MonitorLifecycle
import com.opentasker.core.contexts.ShakeContextEvents
import com.opentasker.core.logging.AppLogger
import kotlin.math.sqrt

class ShakeDetector(context: Context) {

    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val lifecycle = MonitorLifecycle()

    private var lastShakeTime = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val gx = x / SensorManager.GRAVITY_EARTH
            val gy = y / SensorManager.GRAVITY_EARTH
            val gz = z / SensorManager.GRAVITY_EARTH
            val magnitude = sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat()

            val now = System.currentTimeMillis()
            if (!shouldPublishShake(magnitude, lastShakeTime, now)) return
            lastShakeTime = now

            AppLogger.info(TAG, "Shake detected: magnitude=${magnitude}g")
            ShakeContextEvents.publish(magnitude)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start(): Boolean {
        return lifecycle.start {
            if (accelerometer == null) {
                AppLogger.warn(TAG, "No accelerometer sensor available")
                return@start false
            }
            val registered = sensorManager?.registerListener(
                listener,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI,
            ) == true
            if (registered) {
                AppLogger.info(TAG, "Shake detector started")
            } else {
                AppLogger.warn(TAG, "Accelerometer listener could not be registered")
            }
            registered
        }
    }

    fun stop() {
        lifecycle.stop {
            sensorManager?.unregisterListener(listener)
            AppLogger.info(TAG, "Shake detector stopped")
        }
    }

    companion object {
        private const val TAG = "OpenTasker"
        private const val SHAKE_THRESHOLD_G = 2.5f
        private const val DEBOUNCE_MS = 1000L

        internal fun shouldPublishShake(magnitudeG: Float, lastShakeAtMs: Long, nowMs: Long): Boolean =
            magnitudeG >= SHAKE_THRESHOLD_G && nowMs - lastShakeAtMs >= DEBOUNCE_MS
    }
}
