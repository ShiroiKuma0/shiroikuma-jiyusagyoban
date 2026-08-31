package com.opentasker.automation.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.opentasker.core.contexts.FoldContextEvents
import com.opentasker.core.logging.AppLogger

/**
 * Exposes the foldable posture as the super-global %FOLD (folded / semi / unfolded) by watching the
 * device's HALL-effect fold sensor — the magnet-based sensor that SNAPS between discrete postures.
 *
 * Why the HALL sensor and not display metrics: a physical fold emits a burst of display/hinge-angle
 * changes while the panels reconfigure. Reading geometry on those (even debounced, even just having a
 * DisplayListener live) stalled the fold for seconds on the Mate XT — completely unacceptable. The HALL
 * sensor instead reports ONCE, the instant the posture actually changes, so folds stay instantaneous and
 * %FOLD updates on HALL change and nothing else. This mirrors the Tasker 画面状態 project (a Sensor watch
 * on "HALL sensor"). Its value on this device: ≈0 = unfolded, ≈4 = semi, ≈6 = folded (HALL prefixes 0, 4, 6).
 * Registered only while an enabled profile uses the "fold" event, like the shake / orientation detectors.
 */
class FoldDetector(context: Context) {

    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)
    // The HALL fold sensor isn't a standard type, so find it by name (Tasker sees it as "HALL sensor").
    private val hallSensor: Sensor? = sensorManager?.getSensorList(Sensor.TYPE_ALL)
        ?.firstOrNull { it.name.contains("hall", ignoreCase = true) }
    private var last: String? = null
    private var seeded = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val fold = classify(event.values.firstOrNull() ?: return)
            if (fold == last) return
            // The value delivered ON registration seeds %FOLD WITHOUT firing a fold event; later changes fire.
            val isChange = seeded
            last = fold
            seeded = true
            AppLogger.info(TAG, "Fold (HALL=${event.values.firstOrNull()}) -> $fold")
            if (isChange) FoldContextEvents.publish(fold) else FoldContextEvents.setCurrent(fold)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Map the discrete HALL reading to a posture (values ≈0 / 4 / 6 on this device; nearest-of-those). */
    private fun classify(v: Float): String = when {
        v < 2f -> FOLD_UNFOLDED   // ≈0
        v < 5f -> FOLD_SEMI       // ≈4
        else -> FOLD_FOLDED       // ≈6
    }

    fun start() {
        last = null
        seeded = false
        val sensor = hallSensor
        if (sensor == null) {
            AppLogger.warn(TAG, "No HALL sensor on this device — %FOLD unavailable")
            return
        }
        // On-change sensor: registering delivers the current value once, then only on posture change. The
        // dedupe on `last` means even a chatty sensor does no work unless the posture actually changed.
        sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        AppLogger.info(TAG, "Fold detector started (HALL sensor: ${sensor.name})")
    }

    fun stop() {
        sensorManager?.unregisterListener(listener)
        AppLogger.info(TAG, "Fold detector stopped")
    }

    companion object {
        private const val TAG = "OpenTasker"

        const val FOLD_FOLDED = "folded"
        const val FOLD_SEMI = "semi"
        const val FOLD_UNFOLDED = "unfolded"
    }
}
