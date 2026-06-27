package com.opentasker.automation.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.opentasker.core.contexts.ShakeContextEvents
import com.opentasker.core.logging.AppLogger
import kotlin.math.sqrt

class ShakeDetector(context: Context) {

    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

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

            if (magnitude < SHAKE_THRESHOLD_G) return

            val now = System.currentTimeMillis()
            if (now - lastShakeTime < DEBOUNCE_MS) return
            lastShakeTime = now

            AppLogger.info(TAG, "Shake detected: magnitude=${magnitude}g")
            ShakeContextEvents.publish(magnitude)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        if (accelerometer == null) {
            AppLogger.warn(TAG, "No accelerometer sensor available")
            return
        }
        sensorManager?.registerListener(
            listener,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI,
        )
        AppLogger.info(TAG, "Shake detector started")
    }

    fun stop() {
        sensorManager?.unregisterListener(listener)
        AppLogger.info(TAG, "Shake detector stopped")
    }

    companion object {
        private const val TAG = "OpenTasker"
        private const val SHAKE_THRESHOLD_G = 2.5f
        private const val DEBOUNCE_MS = 1000L
    }
}
